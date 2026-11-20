package com.entityparser.parsers

import com.entityparser.geo.GeoGazetteer
import com.entityparser.llm._
import com.entityparser.model._
import com.entityparser.norm.EntityNormalizer

/**
 * 3.1 Country-Aware Address Parser + Geographic Hierarchy Validation + LLM Fallback.
 *
 * Local-conventions-first: determines country/region before applying country-specific
 * component order (CN/JP large-to-small, US small-to-large, DE street+houseNo -> postcode+city).
 */
final class AddressParser(
  gazetteer: GeoGazetteer = GeoGazetteer.default,
  llm: LlmBackend = NoOpLlmBackend,
  fallbackThreshold: Double = 0.75
) {

  private val AddressSchema: JsObject = JsObject(List(
    "type" -> JsString("object"),
    "properties" -> JsObject(List(
      "country"        -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "province_state" -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "city"           -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "district"       -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "street"         -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "house_number"   -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "postcode"       -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "confidence"     -> JsObject(List("type" -> JsString("number")))
    )),
    "required" -> JsArray(List(JsString("country"), JsString("city"), JsString("street"), JsString("house_number"), JsString("confidence")))
  ))

  private val FallbackPrompt: String =
    """ADDRESS_FALLBACK: Parse the postal address into ISO-2 country, province_state, city, district, street, house_number, postcode.
      |Preserve house_number and postcode digits verbatim. Return strict JSON.""".stripMargin

  // China large-to-small: 广东省广州市天河区体育西路103号 (optional 6-digit postcode)
  private val CnRegex =
    """^(?:中国)?(?:([\p{InCJK_Unified_Ideographs}]{2,8}(?:省|自治区|特别行政区))|((?:北京|上海|天津|重庆)市))?(?:([\p{InCJK_Unified_Ideographs}]{2,8}市))?(?:([\p{InCJK_Unified_Ideographs}]{2,8}(?:区|县|旗)))?([\p{InCJK_Unified_Ideographs}A-Za-z0-9]+?(?:大道|大街|路|街|巷|弄|胡同))(\d+[A-Za-z\-]*号)?(.*)$""".r
  private val CnPostcode = """\b(\d{6})\b""".r

  // US small-to-large: 1600 Amphitheatre Pkwy, Mountain View, CA 94043
  // Also handles: 12345 NE 36th St (where 12345 is house number, NE 36th St is street)
  private val UsRegex =
    """^(\d+[A-Za-z]?(?:-\d+)?)\s+([^,]+?)(?:,\s*(?:Apt|Suite|Ste|Unit|#)\s*([^,]+))?,\s*([^,]+?),\s*([A-Z]{2})\s+(\d{5})(?:-\d{4})?(?:,\s*(?:USA|US|United States))?$""".r
  private val UsRegexPoBox =
    """^PO\s+Box\s+(\d+),\s*([^,]+?),\s*([A-Z]{2})\s+(\d{5})(?:-\d{4})?(?:,\s*(?:USA|US|United States))?$""".r

  // US state abbreviation to full name mapping
  private val UsStateMap: Map[String, String] = Map(
    "AL" -> "Alabama", "AK" -> "Alaska", "AZ" -> "Arizona", "AR" -> "Arkansas",
    "CA" -> "California", "CO" -> "Colorado", "CT" -> "Connecticut", "DE" -> "Delaware",
    "FL" -> "Florida", "GA" -> "Georgia", "HI" -> "Hawaii", "ID" -> "Idaho",
    "IL" -> "Illinois", "IN" -> "Indiana", "IA" -> "Iowa", "KS" -> "Kansas",
    "KY" -> "Kentucky", "LA" -> "Louisiana", "ME" -> "Maine", "MD" -> "Maryland",
    "MA" -> "Massachusetts", "MI" -> "Michigan", "MN" -> "Minnesota", "MS" -> "Mississippi",
    "MO" -> "Missouri", "MT" -> "Montana", "NE" -> "Nebraska", "NV" -> "Nevada",
    "NH" -> "New Hampshire", "NJ" -> "New Jersey", "NM" -> "New Mexico", "NY" -> "New York",
    "NC" -> "North Carolina", "ND" -> "North Dakota", "OH" -> "Ohio", "OK" -> "Oklahoma",
    "OR" -> "Oregon", "PA" -> "Pennsylvania", "RI" -> "Rhode Island", "SC" -> "South Carolina",
    "SD" -> "South Dakota", "TN" -> "Tennessee", "TX" -> "Texas", "UT" -> "Utah",
    "VT" -> "Vermont", "VA" -> "Virginia", "WA" -> "Washington", "WV" -> "West Virginia",
    "WI" -> "Wisconsin", "WY" -> "Wyoming", "DC" -> "District of Columbia"
  )

  // Germany: Friedrichstraße 123, 10117 Berlin (or with Bundesland / Germany)
  private val DeRegex =
    """^([^,\d]+?)\s+(\d+[a-zA-Z]?(?:-\d+[a-zA-Z]?)?),\s*(\d{5})\s+([^,]+?)(?:,\s*([^,]+?))?(?:,\s*(?:Germany|Deutschland|DE))?$""".r

  // UK: 221B Baker Street, London, NW1 6XE (with comma before postcode)
  // Also: 10 Downing Street, London, SW1A 2AA, United Kingdom
  // Also handles: Flat 3, 10 Downing Street, Westminster, London SW1A 2AA (with subpremise + district)
  // And: The Old Rectory, Church Lane, York YO1 9AA (building name only)
  // UK postcode format: A9 9AA, A99 9AA, AA9 9AA, AA99 9AA, A9A 9AA, AA9A 9AA
  private val UkRegexBasic =
    """^(\d+[A-Za-z]?)\s+([^,]+?),\s*([^,]+?),\s*([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})(?:,\s*(?:UK|United Kingdom|GB))?$""".r
  private val UkRegexWithDistrict =
    """^(?:Flat|Floor|Fl|Apt|Suite|Unit|#)\s*([^,]+?),\s*([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})(?:,\s*(?:UK|United Kingdom|GB))?$""".r
  private val UkRegexBuildingOnly =
    """^([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})(?:,\s*(?:UK|United Kingdom|GB))?$""".r
  private val UkRegexBuildingOnlyNoHouse =
    """^([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})(?:,\s*(?:UK|United Kingdom|GB))?$""".r
  private val UkRegexBuildingNameOnly =
    """^([^,]+?),\s*([^,]+?),\s*([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})(?:,\s*(?:UK|United Kingdom|GB))?$""".r

  // Japan: 〒150-0002 東京都渋谷区渋谷2-21-1
  // Format: Prefecture (都/道/府/県), City (市), optional Ward (区), Street, HouseNumber
  // Tokyo: 都 + 区 (wards act as cities); Osaka: 府 + 市 + 区
  // Use two patterns: one for Tokyo (都 + 区), one for others (府/県 + 市 + optional 区)
  private val JpRegexTokyo =
    """^(?:〒\s*(\d{3}-?\d{4})\s*)?(東京都)([\p{IsHan}]{1,8}区)([\p{IsHan}A-Za-zぁ-んァ-ヶ]+?)(\d+(?:-\d+)*)(.*)$""".r
  private val JpRegexOther =
    """^(?:〒\s*(\d{3}-?\d{4})\s*)?(大阪府|京都府|北海道|[\p{IsHan}]{2,4}[都道府県])([\p{IsHan}]{1,8}市)(?:([\p{IsHan}]{1,8}区))?([\p{IsHan}A-Za-zぁ-んァ-ヶ]+?)(\d+(?:-\d+)*)(.*)$""".r
  private val JpRegexOsaka =
    """^(?:〒\s*(\d{3}-?\d{4})\s*)?(大阪府)([\p{IsHan}]{1,8}市)([\p{IsHan}]{1,8}区)([\p{IsHan}A-Za-zぁ-んァ-ヶ]+?)(\d+(?:-\d+)*)(.*)$""".r

  // Italy: Via Roma 42, 20121 Milano
  private val ItRegex =
    """^([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(\d{5})\s+([^,]+?)(?:,\s*(?:Italy|Italia|IT))?$""".r

  // Indonesia: Jl. Jend. Sudirman Kav. 52-53, Kebayoran Baru, Jakarta Selatan 12190 (3 commas)
  // Also: Jl. MH Thamrin No. 1, Kebon Sirih, Menteng, Jakarta Pusat 10340, Indonesia (4 commas)
  private val IdRegex3Comma =
    """^(?:Jl\.?\s+|Jalan\s+)([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:Indonesia|ID))?$""".r
  private val IdRegex4Comma =
    """^(?:Jl\.?\s+|Jalan\s+)([^,]+?),\s*([^,]+?),\s*([^,]+?),\s*(.+?)\s+(\d{5})(?:,\s*(?:Indonesia|ID))?$""".r
  private val IdRegex4CommaAlt =
    """^(?:Jl\.?\s+|Jalan\s+)([^,]+?),\s*([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:Indonesia|ID))?$""".r

  // South Korea: 152 Teheran-ro, Gangnam-gu, Seoul 06236 (houseNo-first)
  // Also: 제주특별자치도 제주시 중앙로 123, 63122 (street-first with province/city)
  private val KrRegex =
    """^(\d+[A-Za-z]?(?:-\d+)?)\s+([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:South Korea|Korea|KR))?$""".r
  private val KrRegexStreetFirst =
    """^[^,]+?\s+([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+)?),\s*(\d{5})(?:,\s*(?:South Korea|Korea|KR))?$""".r

  // Spain: Calle de Alcalá 45, 28014 Madrid
  private val EsRegex =
    """^([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(\d{5})\s+([^,]+?)(?:,\s*(?:España|Spain|ES))?$""".r

  // France: Champs-Élysées 75, 75008 Paris
  private val FrRegex =
    """^([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(\d{5})\s+([^,]+?)(?:,\s*(?:France|FR))?$""".r

  // Brazil: Rua Augusta 1500, 01305-100 São Paulo
  // Also: Av. Paulista, 1000 - 10º andar, Bela Vista, São Paulo - SP, 01310-100
  // Note: º (U+00BA) and ° (U+00B0) are ordinal/degree signs; match any chars between floor number and 'andar'
  private val BrRegex =
    """^([^,]+?),\s*(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?)\s*-\s*\d+.{0,3}\s*(andar|floor),\s*([^,]+?),\s*([^,]+?)\s*-\s*([A-Z]{2}),\s*(\d{5}-?\d{3})(?:,\s*(?:Brazil|Brasil|BR))?$""".r
  private val BrRegexSimple =
    """^([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(\d{5}-?\d{3})\s+([^,]+?)(?:,\s*(?:Brazil|Brasil|BR))?$""".r

  // Hong Kong: Flat B, 15/F, Tower 1, The Belcher's, 89 Pok Fu Lam Road, Hong Kong
  // Also: G/F, 123 Nathan Road, Tsim Sha Tsui, Kowloon, Hong Kong
  // No postcode system in Hong Kong
  // Street format: "89 Pok Fu Lam Road" or "123 Nathan Road" (houseNo + street)
  // Multiple optional comma-separated parts before street: subpremise, floor, building, buildingName
  // Then street, then optional district, then city (Hong Kong/Kowloon/New Territories)
  private val HkRegex =
    """^(?:[^,]+?,\s*){0,5}(\d+)\s+([^,]+?)\s+(?:Road|Street|Rd|St|Ave|Path|Drive|Dr|Lane|Ln|Square|Sq|Crescent|Cres|Close|Cl|Court|Ct|Place|Pl|Terrace|Ter|Mews|Circus|Circ|Bypass)\b[^,]*(?:,\s*[^,]+?){0,2},\s*(?:Hong Kong|Kowloon|New Territories|HK)$""".r
  private val HkRegexSubpremise =
    """^(?:((?:Flat|Floor|Fl|G/F|\d+/F|\d+[A-Z]?/F)(?:\s+[^,]+?)?),\s*)?(?:[^,]+?,\s*)*(\d+)\s+([^,]+?)\s+(?:Road|Street|Rd|St|Ave|Path|Drive|Dr|Lane|Ln|Square|Sq|Crescent|Cres|Close|Cl|Court|Ct|Place|Pl|Terrace|Ter|Mews|Circus|Circ|Bypass)\b[^,]*(?:,\s*[^,]+?){0,2},\s*(?:Hong Kong|Kowloon|New Territories|HK)$""".r
  private val HkRegexNoHouse =
    """^(?:[^,]+?,\s*){0,5}([^,]+?)\s+(?:Road|Street|Rd|St|Ave|Path|Drive|Dr|Lane|Ln|Square|Sq|Crescent|Cres|Close|Cl|Court|Ct|Place|Pl|Terrace|Ter|Mews|Circus|Circ|Bypass)\b[^,]*(?:,\s*[^,]+?){0,2},\s*(?:Hong Kong|Kowloon|New Territories|HK)$""".r

  // Macau: Avenida de Almeida Ribeiro, n.º 100, Edifício Luso, 5.º andar, Macau
  // Also Chinese: 澳門新口岸填海區孫逸仙大馬路618號澳門財富中心12樓
  // Note: º (U+00BA, No) and ° (U+00B0, So) are ordinal/degree signs; Portuguese uses "5.º" with period
  // Use permissive pattern for ordinal indicator (º, ª, °, ., superscript)
  private val MoRegex =
    """^([^,]+?),\s*n\.?\s*[ºª°oO]?\.?\s*(\d+[A-Za-z]?),\s*([^,]+?),\s*\d+\.?\s*[ºª°oOaA]?\.?\s*(andar|floor|樓),\s*(Macau|澳門)$""".r
  private val MoRegexAlt =
    """^([^,]+?),\s*n\.?\s*[ºª°oO]\.?\s*(\d+[A-Za-z]?),\s*([^,]+?),\s*\d+\.?\s*[ºª°oOaA]\.?\s*(andar|floor|樓),\s*(Macau|澳門)$""".r
  private val MoRegexChinese =
    """(澳門)([\u4E00-\u9FFF]+?(?:區|填海區))?([\u4E00-\u9FFF]+?(?:大道|大街|路|街|馬路))(\d+[A-Za-z]?號)([\u4E00-\u9FFF]+?(?:中心|大廈|樓))(\d+[樓層])""".r

  // Taiwan: 100台北市中正區忠孝西路一段4號 (Chinese)
  // Also: No. 168, Sec. 5, Nanjing E. Rd., Songshan Dist., Taipei City 105608, Taiwan
  private val TwRegexChinese =
    """^(\d{3,5})?([\u4E00-\u9FFF]+?(?:市|縣))([\u4E00-\u9FFF]+?(?:區|鄉|鎮|市))([\u4E00-\u9FFFA-Za-z0-9]+?(?:路|街|大道|大街))(一段|二段|三段|四段|五段)?(\d+[A-Za-z]?號)?""".r
  private val TwRegexEnglish =
    """^(?:No\.?\s*(\d+[A-Za-z]?),\s*)?(?:Sec\.?\s*(\d+),\s*)?([^,]+?(?:Rd\.?|Road|St\.?|Street|Ave\.?|Avenue|Blvd\.?|Boulevard|Dr\.?|Drive|Ln\.?|Lane|E\.|W\.|N\.|S\.)),\s*([^,]+?(?:Dist\.?|District)),\s*([^,]+?(?:City|County))(?:\s*,\s*|\s+)(\d{3,6})(?:,\s*(?:Taiwan|TW))?$""".r

  // Singapore: 1 Raffles Place, #45-01, One Raffles Place, Singapore 048616
  // Also: Blk 123 Ang Mo Kio Ave 3, #01-456, Singapore 560123
  private val SgRegex =
    """^(?:Blk\s+(\d+)\s+)?([^,]+?),\s*(?:#(\d+-\d+),\s*)?(?:([^,]+?),\s*)?Singapore\s+(\d{6})$""".r

  // Australia: Level 12, 123 Collins Street, Melbourne VIC 3000, Australia
  // Also: 10/45 King Street, Sydney NSW 2000
  private val AuRegex =
    """^(?:(?:Level|Floor|Lv|Fl)\s*(\d+),\s*)?(\d+(?:/\d+)?)\s+([^,]+?),\s*([^,]+?)\s+([A-Z]{2,3})\s+(\d{4})(?:,\s*(?:Australia|AU))?$""".r

  // Canada: 150 King Street West, Suite 200, Toronto, ON M5H 1J9, Canada
  // Also: C.P. 5000, Succursale Place-des-Arts, Montréal, QC H3B 4G3
  private val CaRegex =
    """^([^,]+?),\s*(?:Suite|Unit|Apt|#)\s*([^,]+?),\s*([^,]+?),\s*([A-Z]{2})\s+([A-Z]\d[A-Z]\s*\d[A-Z]\d)(?:,\s*(?:Canada|CA))?$""".r
  private val CaRegexPoBox =
    """^(?:C\.?P\.?\s*(\d+),\s*)?(?:Succursale\s+([^,]+?),\s*)?([^,]+?),\s*([A-Z]{2})\s+([A-Z]\d[A-Z]\s*\d[A-Z]\d)(?:,\s*(?:Canada|CA))?$""".r

  // British Virgin Islands (VG): PO Box 123, Road Town, Tortola, British Virgin Islands
  private val VgRegex =
    """^(?:PO\s+Box\s+(\d+),\s*)?([^,]+?),\s*([^,]+?),\s*British Virgin Islands$""".r

  // Cayman Islands (KY): PO Box 309, Grand Cayman KY1-1104, Cayman Islands
  private val KyRegex =
    """^(?:PO\s+Box\s+(\d+),\s*)?([^,]+?)\s+(KY\d-\d{4}),\s*Cayman Islands$""".r

  // Jersey (JE): 12 The Parade, St Helier, Jersey JE2 3QA
  private val JeRegex =
    """^([^,]+?),\s*([^,]+?),\s*Jersey\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})$""".r

  // Guernsey (GG): PO Box 65, St Peter Port, Guernsey GY1 3AZ
  private val GgRegex =
    """^(?:PO\s+Box\s+(\d+),\s*)?([^,]+?),\s*Guernsey\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})$""".r

  // Isle of Man (IM): 14 Athol Street, Douglas, Isle of Man IM1 1JA
  private val ImRegex =
    """^([^,]+?),\s*([^,]+?),\s*Isle of Man\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})$""".r

  // Gibraltar (GI): Suite 2.01, World Trade Center, 6 Bayside Road, Gibraltar GX11 1AA
  private val GiRegex =
    """^(?:(?:Suite|Unit|Floor|Fl)\s*([^,]+?),\s*)?([^,]+?),\s*([^,]+?),\s*Gibraltar\s+([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})$""".r

  // Bermuda (BM): Clarendon House, 2 Church Street, Hamilton HM 11, Bermuda
  private val BmRegex =
    """^([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(HM\s*\d{1,2}),\s*Bermuda$""".r

  // Turkey (TR): Levent Mahallesi, Buyukdere Caddesi No: 189, 34394 Sisli/Istanbul, Turkiye
  private val TrRegex =
    """^([^,]+?),\s*([^,]+?)\s+No\s*:\s*(\d+[A-Za-z]?),\s*(\d{5})\s+[^/]+/([^,]+?)(?:,\s*(?:T[üu]rkiye|Turkey|TR))?$""".r
  private val TrRegexAlt =
    """^([^,]+?),\s*([^,]+?),\s*(\d{5}),\s*([^/]+)/([^,]+?)(?:,\s*(?:T[üu]rkiye|Turkey|TR))?$""".r

  // India (IN): Flat 3B, 12 MG Road, Brigade Road Junction, Bengaluru, Karnataka 560001
  // Also: A-45, Sector 18, Noida, Uttar Pradesh 201301, India
  private val InRegex =
    """^(?:(?:Flat|Floor|Fl|Unit|Apt)\s*([^,]+?),\s*)?(?:([^,]+?)\s+)?([^,]+?),\s*(?:([^,]+?),\s*)?([^,]+?),\s*([^,]+?)\s+(\d{6})(?:,\s*(?:India|IN))?$""".r
  private val InRegexSector =
    """^(?:([^,]+?),\s*)?([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{6})(?:,\s*(?:India|IN))?$""".r

  // UAE (AE): Office 1502, Burj Khalifa, PO Box 12345, Dubai, United Arab Emirates
  private val AeRegex =
    """^(?:(?:Office|Suite|Unit|Flat|Floor|Fl)\s*([^,]+?),\s*)?([^,]+?),\s*PO\s+Box\s+(\d+),\s*([^,]+?),\s*(?:United Arab Emirates|UAE|AE)$""".r

  // Qatar (QA): Qatar Financial Centre, 8th Floor, PO Box 23456, Doha, Qatar
  private val QaRegex =
    """^(?:([^,]+?),\s*)?(?:(\d+(?:st|nd|rd|th)\s*(?:Floor|Fl)),\s*)?PO\s+Box\s+(\d+),\s*([^,]+?),\s*(?:Qatar|QA)$""".r

  // Portugal (PT): Avenida da Liberdade 125, 1250-146 Lisboa, Portugal
  // Also: Rua de Santa Catarina 300, 4000-452 Porto, Portugal
  private val PtRegex =
    """^([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(\d{4}-\d{3})\s+([^,]+?)(?:,\s*(?:Portugal|PT))?$""".r

  // Argentina (AR): Av. Corrientes 1234, 5° Piso, C1043AAZ Buenos Aires, Argentina
  private val ArRegex =
    """^([^,]+?)\s+(\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?),\s*(?:(\d+[º°]?\s*(?:Piso|Floor|Piso)),\s*)?([A-Z]\d{4}[A-Z]{3})\s+([^,]+?)(?:,\s*(?:Argentina|AR))?$""".r

  // South Africa (ZA): 15th Floor, 165 West Street, Sandton, Johannesburg 2196, South Africa
  private val ZaRegex =
    """^(?:(\d+(?:st|nd|rd|th)\s*(?:Floor|Fl)),\s*)?([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{4})(?:,\s*(?:South Africa|ZA))?$""".r

  // Philippines (PH): Unit 1501, Ayala Tower One, Ayala Avenue, Makati City 1226, Philippines
  private val PhRegex =
    """^(?:(?:Unit|Suite|Floor|Fl)\s*([^,]+?),\s*)?([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{4})(?:,\s*(?:Philippines|PH))?$""".r
  private val PhRegexCityFirst =
    """^(?:(?:Unit|Suite|Floor|Fl)\s*([^,]+?),\s*)?([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{4})(?:,\s*(?:Philippines|PH))?$""".r

  // Malaysia (MY): Level 20, Menara Petronas, KLCC, 50088 Kuala Lumpur, Malaysia
  private val MyRegex =
    """^(?:(?:Level|Floor|Fl)\s*(\d+),\s*)?([^,]+?),\s*([^,]+?),\s*(\d{5})\s+([^,]+?)(?:,\s*(?:Malaysia|MY))?$""".r

  // Nigeria (NG): Plot 123, Ahmadu Bello Way, Victoria Island, Lagos 101241, Nigeria
  private val NgRegex =
    """^(?:Plot\s+(\d+[A-Za-z]?),\s*)?([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{6})(?:,\s*(?:Nigeria|NG))?$""".r

  // Kenya (KE): Kenyatta Avenue, PO Box 30197, Nairobi 00100, Kenya
  private val KeRegex =
    """^([^,]+?),\s*PO\s+Box\s+(\d+),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:Kenya|KE))?$""".r

  // Egypt (EG): 123 Nile Street, Garden City, Cairo 11511, Egypt
  private val EgRegex =
    """^([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:Egypt|EG))?$""".r

  // Morocco (MA): Boulevard Mohammed V, Immeuble Atlas, 5ème étage, Casablanca 20000, Maroc
  private val MaRegex =
    """^([^,]+?),\s*([^,]+?),\s*(?:(\d+(?:ème|eme)?\s*(?:étage|floor|etage)),\s*)?([^,]+?)\s+(\d{5})(?:,\s*(?:Maroc|Morocco|MA))?$""".r

  // Kuwait (KW): Ahmed Al-Jaber Street, Al-Safat, Kuwait City 13001, Kuwait
  private val KwRegex =
    """^([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:Kuwait|KW))?$""".r
  private val KwRegexAlt =
    """^([^,]+?),\s*([^,]+?),\s*([^,]+?)\s+(\d{5})(?:,\s*(?:Kuwait|KW))?$""".r

  // Vietnam (VN): Tầng 15, Tòa nhà Bitexco, 2 Hai Bà Trưng, Quận 1, TP. Hồ Chí Minh 700000, Việt Nam
  private val VnRegex =
    """^(?:Tầng\s*(\d+),\s*)?(?:Tòa nhà\s+([^,]+?),\s*)?([^,]+?),\s*([^,]+?),\s*(?:TP\.\s*)?([^,]+?)\s+(\d{6})(?:,\s*(?:Việt Nam|Vietnam|VN))?$""".r

  def parse(cleaned: CleanedInput, countryHint: Option[String] = None): ParseResult[ParsedAddress] = {
    val text    = cleaned.cleanedText
    val country = CountryDetector.detectCountry(text, countryHint)

    val (ruleParsed, ruleConf, ruleWarnings) = country match {
      case Some("CN") => parseChina(text)
      case Some("US") => parseUs(text)
      case Some("DE") => parseGermany(text)
      case Some("GB") => parseUk(text)
      case Some("JP") => parseJapan(text)
      case Some("IT") => parseItaly(text)
      case Some("ID") => parseIndonesia(text)
      case Some("KR") => parseKorea(text)
      case Some("ES") => parseSpain(text)
      case Some("FR") => parseFrance(text)
      case Some("BR") => parseBrazil(text)
      case Some("HK") => parseHongKong(text)
      case Some("MO") => parseMacau(text)
      case Some("TW") => parseTaiwan(text)
      case Some("SG") => parseSingapore(text)
      case Some("AU") => parseAustralia(text)
      case Some("CA") => parseCanada(text)
      case Some("VG") => parseBVI(text)
      case Some("KY") => parseCayman(text)
      case Some("JE") => parseJersey(text)
      case Some("GG") => parseGuernsey(text)
      case Some("IM") => parseIsleOfMan(text)
      case Some("GI") => parseGibraltar(text)
      case Some("BM") => parseBermuda(text)
      case Some("TR") => parseTurkey(text)
      case Some("IN") => parseIndia(text)
      case Some("AE") => parseUAE(text)
      case Some("QA") => parseQatar(text)
      case Some("PT") => parsePortugal(text)
      case Some("AR") => parseArgentina(text)
      case Some("ZA") => parseSouthAfrica(text)
      case Some("PH") => parsePhilippines(text)
      case Some("MY") => parseMalaysia(text)
      case Some("NG") => parseNigeria(text)
      case Some("KE") => parseKenya(text)
      case Some("EG") => parseEgypt(text)
      case Some("MA") => parseMorocco(text)
      case Some("KW") => parseKuwait(text)
      case Some("VN") => parseVietnam(text)
      case _          => (ParsedAddress(country, None, None, None, None, None, None), 0.25, List("No deterministic rule template matched country/format"))
    }

    // Confidence-gated LLM fallback (§5): NEVER override high-confidence rule results
    val (merged, finalConf, provenance, extraWarnings) =
      if (ruleConf < fallbackThreshold && llm.isAvailable) {
        llm.completeJson(FallbackPrompt, text, AddressSchema, maxTokens = 256) match {
          case Right(obj) =>
            val llmConf = obj.field("confidence").flatMap(_.asOptDouble).getOrElse(0.5)
            val llmAddr = ParsedAddress(
              country       = ruleParsed.country.orElse(obj.fieldString("country")),
              provinceState = ruleParsed.provinceState.orElse(obj.fieldString("province_state")),
              city          = ruleParsed.city.orElse(obj.fieldString("city")),
              district      = ruleParsed.district.orElse(obj.fieldString("district")),
              street        = ruleParsed.street.orElse(obj.fieldString("street")),
              houseNumber   = ruleParsed.houseNumber.orElse(obj.fieldString("house_number")),
              postcode      = ruleParsed.postcode.orElse(obj.fieldString("postcode"))
            )
            val prov = if (ruleConf <= 0.30) Provenance.LlmFallback else Provenance.RuleMergedWithLlm
            (llmAddr, math.max(ruleConf, llmConf), prov, Nil)
          case Left(err) =>
            (ruleParsed, ruleConf, Provenance.RuleBased, List(s"LLM fallback unavailable: $err"))
        }
      } else {
        val prov = if (cleaned.usedModel && cleaned.changes.nonEmpty) Provenance.LlmCleanedThenRule else Provenance.RuleBased
        (ruleParsed, ruleConf, prov, Nil)
      }

    // Geographic hierarchy validation (§3.1)
    val geoVal = gazetteer.validate(merged.country, merged.provinceState, merged.city, merged.postcode)
    val validatedAddr = merged.copy(
      provinceState =
        if (merged.country.contains("JP") && merged.provinceState.exists(_.contains("大阪府"))) merged.provinceState
        else geoVal.canonicalAdmin1.orElse(merged.provinceState),
      city          =
        if (merged.country.contains("JP") && merged.city.exists(_.contains("大阪市"))) merged.city
        else geoVal.canonicalCity.orElse(merged.city),
      geoValidation = Some(geoVal)
    )

    val allWarnings = ruleWarnings ++ extraWarnings ++ geoVal.issues
    val adjustedConf =
      if (!geoVal.isValid && geoVal.issues.exists(_.startsWith("Hierarchy mismatch")))
        math.min(finalConf, 0.45)
      else if (!geoVal.isValid)
        math.max(0.10, finalConf - 0.15)
      else
        math.min(0.99, finalConf + 0.04)

    val finalProv =
      if (provenance == Provenance.RuleBased && geoVal.isValid) Provenance.RuleWithGeoValidation
      else provenance

    ParseResult(
      rawInput = cleaned.rawText,
      cleanedInput = cleaned,
      parsed = validatedAddr,
      matchingKey = EntityNormalizer.addressMatchingKey(validatedAddr),
      confidence = adjustedConf,
      provenance = finalProv,
      warnings = allWarnings,
      needsHumanReview = adjustedConf < 0.70 || !geoVal.isValid
    )
  }

  private def parseChina(text: String): (ParsedAddress, Double, List[String]) = {
    val pcOpt = CnPostcode.findFirstMatchIn(text).map(_.group(1))
    val stripped = CnPostcode.replaceAllIn(text, "").replaceAll("[,，\\s]+", "")
    stripped match {
      case CnRegex(prov, muni, city, dist, street, houseNo, rest) =>
        val pOpt = Option(prov).orElse(Option(muni))
        val cOpt = Option(city).orElse(Option(muni))
        val addr = ParsedAddress(
          country       = Some("CN"),
          provinceState = pOpt,
          city          = cOpt,
          district      = Option(dist),
          street        = Option(street),
          houseNumber   = Option(houseNo).map(_.stripSuffix("号")),
          postcode      = pcOpt,
          subpremise    = Option(rest).map(_.trim).filter(_.nonEmpty)
        )
        val conf = if (addr.houseNumber.isDefined) 0.92 else 0.78
        (addr, conf, Nil)
      case _ =>
        (ParsedAddress(Some("CN"), None, None, None, None, None, pcOpt), 0.35, List("Chinese address did not match standard large-to-small pattern"))
    }
  }

  private def parseUs(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case UsRegex(houseNo, street, sub, city, state, zip) =>
        val fullState = UsStateMap.getOrElse(state, state)
        val addr = ParsedAddress(
          country       = Some("US"),
          provinceState = Some(fullState),
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(zip.trim),
          subpremise    = Option(sub).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.94, Nil)
      case UsRegexPoBox(poBox, city, state, zip) =>
        val fullState = UsStateMap.getOrElse(state, state)
        val addr = ParsedAddress(
          country       = Some("US"),
          provinceState = Some(fullState),
          city          = Some(city.trim),
          district      = None,
          street        = Some(s"PO Box $poBox"),
          houseNumber   = None,
          postcode      = Some(zip.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("US"), None, None, None, None, None, None), 0.35, List("US address did not match small-to-large pattern"))
    }
  }

  private def parseGermany(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case DeRegex(street, houseNo, postcode, city, stateOpt) =>
        val addr = ParsedAddress(
          country       = Some("DE"),
          provinceState = Option(stateOpt).map(_.trim).filter(_.nonEmpty),
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.93, Nil)
      case _ =>
        (ParsedAddress(Some("DE"), None, None, None, None, None, None), 0.35, List("German address did not match street+houseNo, postcode+city pattern"))
    }
  }

  private def parseUk(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case UkRegexWithDistrict(sub, street, district, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("GB"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.toUpperCase(java.util.Locale.ROOT)),
          subpremise    = Option(sub).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.92, Nil)
      case UkRegexBasic(houseNo, street, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("GB"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim.toUpperCase(java.util.Locale.ROOT))
        )
        (addr, 0.94, Nil)
      case UkRegexBuildingOnly(building, street, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("GB"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.toUpperCase(java.util.Locale.ROOT)),
          subpremise    = Some(building.trim)
        )
        (addr, 0.88, Nil)
      case UkRegexBuildingNameOnly(building, street, postcode) =>
        // Building name only format: "The Old Rectory, Church Lane, York YO1 9AA"
        // building = "The Old Rectory", street = "Church Lane", postcode = "YO1 9AA"
        // Need to extract city from postcode lookup or assume it's in the street part
        // For UK, the city is often the post town which can be looked up from postcode
        val addr = ParsedAddress(
          country       = Some("GB"),
          provinceState = None,
          city          = None,  // Will be resolved by geo validation from postcode
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.toUpperCase(java.util.Locale.ROOT)),
          subpremise    = Some(building.trim)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("GB"), None, None, None, None, None, None), 0.35, List("UK address did not match standard pattern"))
    }
  }

  private def parseJapan(text: String): (ParsedAddress, Double, List[String]) = {
    val compact = text.replaceAll("\\s+", "")
    compact match {
      case JpRegexTokyo(pc, pref, city, street, houseNo, rest) =>
        val addr = ParsedAddress(
          country       = Some("JP"),
          provinceState = Some(pref),
          city          = Some(city),
          district      = None,
          street        = Some(street),
          houseNumber   = Some(houseNo),
          postcode      = Option(pc),
          subpremise    = Option(rest).filter(_.nonEmpty)
        )
        (addr, 0.91, Nil)
      case JpRegexOsaka(pc, pref, city, ward, street, houseNo, rest) =>
        val addr = ParsedAddress(
          country       = Some("JP"),
          provinceState = Some(pref),
          city          = Some(city),
          district      = Some(ward),
          street        = Some(street),
          houseNumber   = Some(houseNo),
          postcode      = Option(pc),
          subpremise    = Option(rest).filter(_.nonEmpty)
        )
        (addr, 0.91, Nil)
      case JpRegexOther(pc, pref, city, ward, street, houseNo, rest) =>
        val addr = ParsedAddress(
          country       = Some("JP"),
          provinceState = Some(pref),
          city          = Some(city),
          district      = Option(ward).filter(_.nonEmpty),
          street        = Some(street),
          houseNumber   = Some(houseNo),
          postcode      = Option(pc),
          subpremise    = Option(rest).filter(_.nonEmpty)
        )
        (addr, 0.91, Nil)
      case _ =>
        (ParsedAddress(Some("JP"), None, None, None, None, None, None), 0.35, List("Japanese address did not match standard large-to-small pattern"))
    }
  }

  private def parseItaly(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case ItRegex(street, houseNo, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("IT"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("IT"), None, None, None, None, None, None), 0.35, List("Italian address did not match street+houseNo, postcode+city pattern"))
    }
  }

  private def parseIndonesia(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case IdRegex4Comma(street, subdistrict, district, city, postcode) =>
        val houseNoOpt = """(?:Kav\.?|No\.?)\s*([\d\-]+)""".r.findFirstMatchIn(street).map(_.group(1))
        val addr = ParsedAddress(
          country       = Some("ID"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = houseNoOpt,
          postcode      = Some(postcode.trim),
          subpremise    = Some(subdistrict.trim)
        )
        (addr, 0.90, Nil)
      case IdRegex4CommaAlt(street, subdistrict, district, city, postcode) =>
        val houseNoOpt = """(?:Kav\.?|No\.?)\s*([\d\-]+)""".r.findFirstMatchIn(street).map(_.group(1))
        val addr = ParsedAddress(
          country       = Some("ID"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = houseNoOpt,
          postcode      = Some(postcode.trim),
          subpremise    = Some(subdistrict.trim)
        )
        (addr, 0.90, Nil)
      case IdRegex3Comma(street, district, city, postcode) =>
        val houseNoOpt = """(?:Kav\.?|No\.?)\s*([\d\-]+)""".r.findFirstMatchIn(street).map(_.group(1))
        val addr = ParsedAddress(
          country       = Some("ID"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = houseNoOpt,
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("ID"), None, None, None, None, None, None), 0.35, List("Indonesian address did not match Jl. street, district, [subdistrict,] city postcode pattern"))
    }
  }

  private def parseKorea(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case KrRegex(houseNo, street, district, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("KR"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case KrRegexStreetFirst(city, houseNo, postcode) =>
        val streetMatch = """^.+?\s+([^,]+?)\s+\d+[A-Za-z]?(?:-\d+)?,?""".r.findFirstMatchIn(text)
        val streetOpt = streetMatch.map(_.group(1).trim)
        val addr = ParsedAddress(
          country       = Some("KR"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = streetOpt,
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("KR"), None, None, None, None, None, None), 0.35, List("Korean address did not match houseNo street, district, city postcode or street-first pattern"))
    }
  }

  private def parseSpain(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case EsRegex(street, houseNo, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("ES"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("ES"), None, None, None, None, None, None), 0.35, List("Spanish address did not match street+houseNo, postcode+city pattern"))
    }
  }

  private def parseFrance(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case FrRegex(street, houseNo, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("FR"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("FR"), None, None, None, None, None, None), 0.35, List("French address did not match street+houseNo, postcode+city pattern"))
    }
  }

  private def parseBrazil(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case BrRegex(street, houseNo, floor, district, city, state, postcode) =>
        val addr = ParsedAddress(
          country       = Some("BR"),
          provinceState = Some(state.trim),
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim),
          subpremise    = Some(floor.trim)
        )
        (addr, 0.90, Nil)
      case BrRegexSimple(street, houseNo, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("BR"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("BR"), None, None, None, None, None, None), 0.35, List("Brazilian address did not match street+houseNo, postcode+city pattern"))
    }
  }

  private def parseHongKong(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case HkRegexSubpremise(sub, houseNo, street) =>
        val addr = ParsedAddress(
          country       = Some("HK"),
          provinceState = None,
          city          = Some("Hong Kong"),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = None,
          subpremise    = Option(sub).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.88, Nil)
      case HkRegex(houseNo, street) =>
        val addr = ParsedAddress(
          country       = Some("HK"),
          provinceState = None,
          city          = Some("Hong Kong"),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = None
        )
        (addr, 0.85, Nil)
      case HkRegexNoHouse(street) =>
        val addr = ParsedAddress(
          country       = Some("HK"),
          provinceState = None,
          city          = Some("Hong Kong"),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = None
        )
        (addr, 0.80, Nil)
      case _ =>
        (ParsedAddress(Some("HK"), None, None, None, None, None, None), 0.35, List("Hong Kong address did not match standard format"))
    }
  }

  private def parseMacau(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case MoRegex(street, houseNo, building, floor, _) =>
        val addr = ParsedAddress(
          country       = Some("MO"),
          provinceState = None,
          city          = Some("Macau"),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = None,
          subpremise    = Some(s"$building, $floor")
        )
        (addr, 0.88, Nil)
      case MoRegexAlt(street, houseNo, building, floor, _) =>
        val addr = ParsedAddress(
          country       = Some("MO"),
          provinceState = None,
          city          = Some("Macau"),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = None,
          subpremise    = Some(s"$building, $floor")
        )
        (addr, 0.88, Nil)
      case MoRegexChinese(country, district, street, houseNo, building, floor) =>
        val addr = ParsedAddress(
          country       = Some("MO"),
          provinceState = None,
          city          = Some("Macau"),
          district      = Option(district).map(_.trim).filter(_.nonEmpty),
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.stripSuffix("號")),
          postcode      = None,
          subpremise    = Some(building + floor)
        )
        (addr, 0.88, Nil)
      case _ =>
        // Try more permissive matching for Portuguese ordinal indicators
        val permissiveMo = """^([^,]+?),\s*n[ºª°\.]?\s*(\d+[A-Za-z]?),\s*([^,]+?),\s*\d+[ºª°\.]?\s*(andar|floor|樓),\s*(Macau|澳門)$""".r
        text match {
          case permissiveMo(street, houseNo, building, floor, _) =>
            val addr = ParsedAddress(
              country       = Some("MO"),
              provinceState = None,
              city          = Some("Macau"),
              district      = None,
              street        = Some(street.trim),
              houseNumber   = Some(houseNo.trim),
              postcode      = None,
              subpremise    = Some(s"$building, $floor")
            )
            (addr, 0.85, Nil)
          case _ =>
            (ParsedAddress(Some("MO"), None, None, None, None, None, None), 0.35, List("Macau address did not match Portuguese or Chinese format"))
        }
    }
  }

  private def parseTaiwan(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case TwRegexChinese(postcode, city, district, street, section, houseNo) =>
        val addr = ParsedAddress(
          country       = Some("TW"),
          provinceState = None,
          city          = Option(city).map(_.trim).filter(_.nonEmpty),
          district      = Option(district).map(_.trim).filter(_.nonEmpty),
          street        = Option(street).map(_.trim).filter(_.nonEmpty),
          houseNumber   = Option(houseNo).map(_.stripSuffix("號")).map(_.trim).filter(_.nonEmpty),
          postcode      = Option(postcode).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.88, Nil)
      case TwRegexEnglish(houseNo, section, street, district, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("TW"),
          provinceState = None,
          city          = Option(city).map(_.trim).filter(_.nonEmpty),
          district      = Option(district).map(_.trim).filter(_.nonEmpty),
          street        = Some(street.trim),
          houseNumber   = Option(houseNo).map(_.trim).filter(_.nonEmpty),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        // Try more permissive matching for directional abbreviations (E., W., N., S.)
        val permissiveTw = """^(?:No\.?\s*(\d+[A-Za-z]?),\s*)?(?:Sec\.?\s*(\d+),\s*)?([^,]+?(?:Rd\.?|Road|St\.?|Street|Ave\.?|Avenue|Blvd\.?|Boulevard|Dr\.?|Drive|Ln\.?|Lane|E\.|W\.|N\.|S\.)),\s*([^,]+?(?:Dist\.?|District)),\s*([^,]+?(?:City|County)),\s*(\d{5,6})(?:,\s*(?:Taiwan|TW))?$""".r
        text match {
          case permissiveTw(houseNo, section, street, district, city, postcode) =>
            val addr = ParsedAddress(
              country       = Some("TW"),
              provinceState = None,
              city          = Option(city).map(_.trim).filter(_.nonEmpty),
              district      = Option(district).map(_.trim).filter(_.nonEmpty),
              street        = Some(street.trim),
              houseNumber   = Option(houseNo).map(_.trim).filter(_.nonEmpty),
              postcode      = Some(postcode.trim)
            )
            (addr, 0.88, Nil)
          case _ =>
            (ParsedAddress(Some("TW"), None, None, None, None, None, None), 0.35, List("Taiwan address did not match Chinese or English format"))
        }
    }
  }

  private def parseSingapore(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case SgRegex(blkNo, street, unit, building, postcode) =>
        val houseNoOpt = Option(blkNo).filter(_.nonEmpty)
        val streetClean = Option(street).map(_.trim).filter(_.nonEmpty)
        val addr = ParsedAddress(
          country       = Some("SG"),
          provinceState = None,
          city          = Some("Singapore"),
          district      = None,
          street        = streetClean,
          houseNumber   = houseNoOpt,
          postcode      = Some(postcode.trim),
          subpremise    = Option(unit).orElse(Option(building)).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("SG"), None, None, None, None, None, None), 0.35, List("Singapore address did not match standard format"))
    }
  }

  private def parseAustralia(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case AuRegex(level, houseNo, street, city, state, postcode) =>
        val addr = ParsedAddress(
          country       = Some("AU"),
          provinceState = Some(state.trim),
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim),
          subpremise    = Option(level).map(l => s"Level $l").filter(_.nonEmpty)
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("AU"), None, None, None, None, None, None), 0.35, List("Australia address did not match standard format"))
    }
  }

  private def parseCanada(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case CaRegex(street, suite, city, province, postcode) =>
        val addr = ParsedAddress(
          country       = Some("CA"),
          provinceState = Some(province.trim),
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.replace(" ", "").toUpperCase(java.util.Locale.ROOT)),
          subpremise    = Some(suite.trim)
        )
        (addr, 0.92, Nil)
      case CaRegexPoBox(poBox, station, city, province, postcode) =>
        val addr = ParsedAddress(
          country       = Some("CA"),
          provinceState = Some(province.trim),
          city          = Some(city.trim),
          district      = Option(station).map(_.trim).filter(_.nonEmpty),
          street        = Option(poBox).map(p => s"PO Box $p").filter(_.nonEmpty),
          houseNumber   = None,
          postcode      = Some(postcode.trim.replace(" ", "").toUpperCase(java.util.Locale.ROOT)),
          subpremise    = None
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("CA"), None, None, None, None, None, None), 0.35, List("Canada address did not match standard format"))
    }
  }

  private def parseBVI(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case VgRegex(poBox, city, island) =>
        val addr = ParsedAddress(
          country       = Some("VG"),
          provinceState = Some(island.trim),
          city          = Some(city.trim),
          district      = None,
          street        = Option(poBox).map(p => s"PO Box $p"),
          houseNumber   = None,
          postcode      = None
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("VG"), None, None, None, None, None, None), 0.35, List("BVI address did not match standard format"))
    }
  }

  private def parseCayman(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case KyRegex(poBox, island, postcode) =>
        val addr = ParsedAddress(
          country       = Some("KY"),
          provinceState = Some(island.trim),
          city          = Some(island.trim),
          district      = None,
          street        = Option(poBox).map(p => s"PO Box $p"),
          houseNumber   = None,
          postcode      = Some(postcode.trim)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("KY"), None, None, None, None, None, None), 0.35, List("Cayman Islands address did not match standard format"))
    }
  }

  private def parseJersey(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case JeRegex(street, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("JE"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.replace(" ", "").toUpperCase(java.util.Locale.ROOT))
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("JE"), None, None, None, None, None, None), 0.35, List("Jersey address did not match standard format"))
    }
  }

  private def parseGuernsey(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case GgRegex(poBox, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("GG"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Option(poBox).map(p => s"PO Box $p"),
          houseNumber   = None,
          postcode      = Some(postcode.trim.replace(" ", "").toUpperCase(java.util.Locale.ROOT))
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("GG"), None, None, None, None, None, None), 0.35, List("Guernsey address did not match standard format"))
    }
  }

  private def parseIsleOfMan(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case ImRegex(street, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("IM"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.replace(" ", "").toUpperCase(java.util.Locale.ROOT))
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("IM"), None, None, None, None, None, None), 0.35, List("Isle of Man address did not match standard format"))
    }
  }

  private def parseGibraltar(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case GiRegex(sub, building, street, postcode) =>
        val addr = ParsedAddress(
          country       = Some("GI"),
          provinceState = None,
          city          = Some("Gibraltar"),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim.replace(" ", "").toUpperCase(java.util.Locale.ROOT)),
          subpremise    = Option(sub).orElse(Option(building)).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("GI"), None, None, None, None, None, None), 0.35, List("Gibraltar address did not match standard format"))
    }
  }

  private def parseBermuda(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case BmRegex(building, street, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("BM"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Some(building.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("BM"), None, None, None, None, None, None), 0.35, List("Bermuda address did not match standard format"))
    }
  }

  private def parseTurkey(text: String): (ParsedAddress, Double, List[String]) = {
    def normalizeTrCity(raw: String): String = raw.trim.replace('\u0130', 'I')
    text match {
      case TrRegex(district, street, houseNo, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("TR"),
          provinceState = None,
          city          = Some(normalizeTrCity(city)),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case TrRegexAlt(district, street, postcode, city, _) =>
        val addr = ParsedAddress(
          country       = Some("TR"),
          provinceState = None,
          city          = Some(normalizeTrCity(city)),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("TR"), None, None, None, None, None, None), 0.35, List("Turkey address did not match standard format"))
    }
  }

  // Indian state name normalization
  private val InStateMap: Map[String, String] = Map(
    "Uttar Pradesh" -> "Uttar Pradesh",
    "Maharashtra" -> "Maharashtra",
    "Tamil Nadu" -> "Tamil Nadu",
    "Karnataka" -> "Karnataka",
    "Gujarat" -> "Gujarat",
    "West Bengal" -> "West Bengal",
    "Rajasthan" -> "Rajasthan",
    "Andhra Pradesh" -> "Andhra Pradesh",
    "Telangana" -> "Telangana",
    "Kerala" -> "Kerala",
    "Madhya Pradesh" -> "Madhya Pradesh",
    "Punjab" -> "Punjab",
    "Haryana" -> "Haryana",
    "Bihar" -> "Bihar",
    "Odisha" -> "Odisha",
    "Jharkhand" -> "Jharkhand",
    "Assam" -> "Assam",
    "Chhattisgarh" -> "Chhattisgarh",
    "Uttarakhand" -> "Uttarakhand",
    "Himachal Pradesh" -> "Himachal Pradesh",
    "Goa" -> "Goa",
    "Delhi" -> "Delhi",
    "Jammu and Kashmir" -> "Jammu and Kashmir",
    "Ladakh" -> "Ladakh"
  )

  private def parseIndia(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case InRegex(sub, houseNo, street, landmark, city, state, postcode) =>
        val normState = InStateMap.getOrElse(state.trim, state.trim)
        // Extract house number from either houseNo group or from street (for formats like "A-45, Sector 18")
        val houseNum = Option(houseNo).filter(_.nonEmpty).orElse {
          // Try to extract from street if it contains a house/block number pattern (e.g. A-45, B/12, 12A)
          """^([A-Za-z]+[-/]\d+[A-Za-z]?|[A-Za-z]?\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)?)""".r.findFirstMatchIn(street.trim).map(_.group(1))
        }
        val addr = ParsedAddress(
          country       = Some("IN"),
          provinceState = Some(normState),
          city          = Some(city.trim),
          district      = Option(landmark).map(_.trim).filter(_.nonEmpty),
          street        = Some(street.trim),
          houseNumber   = houseNum.map(_.trim).filter(_.nonEmpty),
          postcode      = Some(postcode.trim),
          subpremise    = Option(sub).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.88, Nil)
      case InRegexSector(houseNo, landmark, city, state, postcode) =>
        val normState = InStateMap.getOrElse(state.trim, state.trim)
        val addr = ParsedAddress(
          country       = Some("IN"),
          provinceState = Some(normState),
          city          = Some(city.trim),
          district      = Option(landmark).map(_.trim).filter(_.nonEmpty),
          street        = Option(landmark).map(_.trim).filter(_.nonEmpty),
          houseNumber   = Option(houseNo).map(_.trim).filter(_.nonEmpty),
          postcode      = Some(postcode.trim),
          subpremise    = None
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("IN"), None, None, None, None, None, None), 0.35, List("India address did not match standard format"))
    }
  }

  private def parseUAE(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case AeRegex(sub, building, poBox, city) =>
        val addr = ParsedAddress(
          country       = Some("AE"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(building.trim),
          houseNumber   = None,
          postcode      = None,
          subpremise    = Option(sub).map(s => s"$s, PO Box $poBox").filter(_.nonEmpty)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("AE"), None, None, None, None, None, None), 0.35, List("UAE address did not match standard format"))
    }
  }

  private def parseQatar(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case QaRegex(building, floor, poBox, city) =>
        val addr = ParsedAddress(
          country       = Some("QA"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Option(building).map(_.trim).filter(_.nonEmpty),
          houseNumber   = None,
          postcode      = None,
          subpremise    = Option(floor).orElse(Option(poBox).map(p => s"PO Box $p")).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.88, Nil)
      case _ =>
        (ParsedAddress(Some("QA"), None, None, None, None, None, None), 0.35, List("Qatar address did not match standard format"))
    }
  }

  private def parsePortugal(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case PtRegex(street, houseNo, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("PT"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("PT"), None, None, None, None, None, None), 0.35, List("Portugal address did not match standard format"))
    }
  }

  private def parseArgentina(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case ArRegex(street, houseNo, floor, cpa, city) =>
        val addr = ParsedAddress(
          country       = Some("AR"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(cpa.trim),
          subpremise    = Option(floor).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("AR"), None, None, None, None, None, None), 0.35, List("Argentina address did not match standard format"))
    }
  }

  private def parseSouthAfrica(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case ZaRegex(floor, street, suburb, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("ZA"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(suburb.trim),
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Option(floor).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("ZA"), None, None, None, None, None, None), 0.35, List("South Africa address did not match standard format"))
    }
  }

  private def parsePhilippines(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case PhRegex(sub, building, street, city, postcode) =>
        val cleanCity = city.trim.replaceAll("(?i)\\s+City$", "")
        val addr = ParsedAddress(
          country       = Some("PH"),
          provinceState = None,
          city          = Some(cleanCity),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Option(sub).orElse(Option(building)).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("PH"), None, None, None, None, None, None), 0.35, List("Philippines address did not match standard format"))
    }
  }

  private def parseMalaysia(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case MyRegex(level, building, area, postcode, city) =>
        val addr = ParsedAddress(
          country       = Some("MY"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Option(area).map(_.trim).filter(_.nonEmpty),
          street        = Some(building.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Option(level).map(l => s"Level $l").filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("MY"), None, None, None, None, None, None), 0.35, List("Malaysia address did not match standard format"))
    }
  }

  private def parseNigeria(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case NgRegex(plot, street, district, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("NG"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = Option(plot).map(_.trim).filter(_.nonEmpty),
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("NG"), None, None, None, None, None, None), 0.35, List("Nigeria address did not match standard format"))
    }
  }

  private def parseKenya(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case KeRegex(street, poBox, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("KE"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Some(s"PO Box $poBox")
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("KE"), None, None, None, None, None, None), 0.35, List("Kenya address did not match standard format"))
    }
  }

  private def parseEgypt(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case EgRegex(street, district, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("EG"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("EG"), None, None, None, None, None, None), 0.35, List("Egypt address did not match standard format"))
    }
  }

  private def parseMorocco(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case MaRegex(street, building, floor, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("MA"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Option(building).orElse(Option(floor)).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("MA"), None, None, None, None, None, None), 0.35, List("Morocco address did not match standard format"))
    }
  }

  private def parseKuwait(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case KwRegex(street, district, city, postcode) =>
        val cleanCity = city.trim.replaceAll("(?i)\\s+City$", "")
        val addr = ParsedAddress(
          country       = Some("KW"),
          provinceState = None,
          city          = Some(cleanCity),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("KW"), None, None, None, None, None, None), 0.35, List("Kuwait address did not match standard format"))
    }
  }

  private def parseVietnam(text: String): (ParsedAddress, Double, List[String]) = {
    text match {
      case VnRegex(floor, building, street, district, city, postcode) =>
        val addr = ParsedAddress(
          country       = Some("VN"),
          provinceState = None,
          city          = Some(city.trim),
          district      = Some(district.trim),
          street        = Some(street.trim),
          houseNumber   = None,
          postcode      = Some(postcode.trim),
          subpremise    = Option(floor).map(f => s"Floor $f").orElse(Option(building)).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.90, Nil)
      case _ =>
        (ParsedAddress(Some("VN"), None, None, None, None, None, None), 0.35, List("Vietnam address did not match standard format"))
    }
  }
}
