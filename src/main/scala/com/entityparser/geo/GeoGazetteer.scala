package com.entityparser.geo

import java.io.File
import scala.io.Source
import com.entityparser.model.GeoValidation
import com.entityparser.norm.EntityNormalizer

/**
 * Global geographic reference hierarchy (Country -> Admin1 -> City) (§3.1).
 *
 * Ships with an embedded multi-region reference gazetteer for zero-setup execution
 * AND supports loading full GeoNames TSV dumps (`admin1CodesASCII.txt`, `cities15000.txt`)
 * at runtime for production global coverage.
 */
final case class Admin1Record(
  countryCode: String,
  admin1Code: String,
  canonicalName: String,
  aliases: Set[String]
)

final case class CityRecord(
  countryCode: String,
  admin1Code: String,
  canonicalName: String,
  aliases: Set[String]
)

/**
 * Gazetteer retrieval evidence: a matched city record with its full administrative path.
 * Used as evidence in Stage 1 country candidate extraction.
 */
final case class PlaceEvidence(
  city: CityRecord,
  matchedName: String,
  matchedKey: String
)

class GeoGazetteer(
  val admin1s: List[Admin1Record],
  val cities: List[CityRecord]
) {

  private val admin1ByCountryAndKey: Map[(String, String), Admin1Record] =
    admin1s.flatMap { r =>
      val keys = (r.aliases + r.canonicalName + r.admin1Code).map(k => EntityNormalizer.keyToken(k, r.countryCode == "DE"))
      keys.map(k => (r.countryCode, k) -> r)
    }.toMap

  private val admin1ByCode: Map[(String, String), Admin1Record] =
    admin1s.map(r => (r.countryCode, r.admin1Code) -> r).toMap

  private val citiesByCountryAndKey: Map[(String, String), List[CityRecord]] =
    cities.flatMap { c =>
      val keys = (c.aliases + c.canonicalName).map(k => EntityNormalizer.keyToken(k, c.countryCode == "DE"))
      keys.map(k => (c.countryCode, k) -> c)
    }.groupBy(_._1).view.mapValues(_.map(_._2).distinct).toMap

  /**
   * REVERSE INDEX: place name (normalized key) → List of CityRecords across ALL countries.
   * This is the core of the RAG retrieval: given a place name token, find all countries
   * where a city/admin1 with that name exists.
   */
  private val reversePlaceIndex: Map[String, List[CityRecord]] =
    cities.flatMap { c =>
      val keys = (c.aliases + c.canonicalName).map(k => EntityNormalizer.keyToken(k, c.countryCode == "DE"))
      keys.map(k => k -> c)
    }.groupBy(_._1).view.mapValues(_.map(_._2).distinct).toMap

  /** Also include admin1 names in reverse index for province/state level matches */
  private val reverseAdmin1Index: Map[String, List[Admin1Record]] =
    admin1s.flatMap { a =>
      val keys = (a.aliases + a.canonicalName + a.admin1Code).map(k => EntityNormalizer.keyToken(k, a.countryCode == "DE"))
      keys.map(k => k -> a)
    }.groupBy(_._1).view.mapValues(_.map(_._2).distinct).toMap

  private val postcodePatterns: Map[String, scala.util.matching.Regex] = Map(
    "US" -> """^\d{5}(-\d{4})?$""".r,
    "CN" -> """^\d{6}$""".r,
    "DE" -> """^\d{5}$""".r,
    "GB" -> """^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$""".r,
    "JP" -> """^\d{3}-?\d{4}$""".r,
    "ID" -> """^\d{5}$""".r,
    "SG" -> """^\d{6}$""".r,
    "IN" -> """^\d{6}$""".r,
    "AU" -> """^\d{4}$""".r,
    "FR" -> """^\d{5}$""".r,
    "RU" -> """^\d{6}$""".r
  )

  def lookupAdmin1(country: String, raw: String): Option[Admin1Record] = {
    val cc  = country.toUpperCase(java.util.Locale.ROOT)
    val key = EntityNormalizer.keyToken(raw, cc == "DE")
    admin1ByCountryAndKey.get((cc, key))
  }

  def lookupCity(country: String, raw: String): List[CityRecord] = {
    val cc  = country.toUpperCase(java.util.Locale.ROOT)
    val key = EntityNormalizer.keyToken(raw, cc == "DE")
    citiesByCountryAndKey.getOrElse((cc, key), Nil)
  }

  /**
   * RAG RETRIEVAL: Reverse lookup — place name → candidate CityRecords across ALL countries.
   * Returns PlaceEvidence with the matched city record, the raw matched name, and the normalized key.
   * This is the new capability required for Stage 1 candidate extraction.
   */
  def lookupPlace(raw: String): List[PlaceEvidence] = {
    val key = EntityNormalizer.keyToken(raw, raw.exists(c => c >= 'A' && c <= 'Z'))
    val cityMatches = reversePlaceIndex.getOrElse(key, Nil)
    val admin1Matches = reverseAdmin1Index.getOrElse(key, Nil).map { a1 =>
      CityRecord(a1.countryCode, a1.admin1Code, a1.canonicalName, a1.aliases)
    }
    (cityMatches ++ admin1Matches).distinct.map { c =>
      PlaceEvidence(c, raw, key)
    }
  }

  /**
   * Lookup place by normalized key directly (for internal use).
   */
  def lookupPlaceByKey(key: String): List[PlaceEvidence] = {
    val cityMatches = reversePlaceIndex.getOrElse(key, Nil)
    val admin1Matches = reverseAdmin1Index.getOrElse(key, Nil).map { a1 =>
      CityRecord(a1.countryCode, a1.admin1Code, a1.canonicalName, a1.aliases)
    }
    (cityMatches ++ admin1Matches).distinct.map { c =>
      PlaceEvidence(c, c.canonicalName, key)
    }
  }

  /**
   * Validate parsed address administrative hierarchy:
   * - Resolves canonical Admin1 and City names.
   * - Detects when a known city does NOT belong to the stated province/state.
   * - Checks national postal code format where defined.
   */
  def validate(
    countryOpt: Option[String],
    provinceOpt: Option[String],
    cityOpt: Option[String],
    postcodeOpt: Option[String]
  ): GeoValidation = {
    countryOpt match {
      case None =>
        GeoValidation(isValid = false, None, None, None, List("Missing ISO country code"))

      case Some(rawCc) =>
        val cc     = rawCc.toUpperCase(java.util.Locale.ROOT)
        val issues = scala.collection.mutable.ListBuffer.empty[String]

        // 1. Validate postcode syntax if present
        postcodeOpt.foreach { pc =>
          val normPc = pc.trim.toUpperCase(java.util.Locale.ROOT)
          postcodePatterns.get(cc).foreach { regex =>
            if (regex.findFirstIn(normPc).isEmpty) {
              issues += s"Postcode '$pc' does not match $cc national format"
            }
          }
        }

        // 2. Resolve Admin1
        val resolvedAdmin1 = provinceOpt.flatMap(lookupAdmin1(cc, _))
        if (provinceOpt.isDefined && resolvedAdmin1.isEmpty) {
          issues += s"Unrecognized province/state '${provinceOpt.get}' for country $cc"
        }

        // 3. Resolve City & check hierarchy consistency
        val matchedCities = cityOpt.map(lookupCity(cc, _)).getOrElse(Nil)
        val canonicalCity = (resolvedAdmin1, matchedCities) match {
          case (Some(a1), cs) if cs.nonEmpty =>
            cs.find(_.admin1Code == a1.admin1Code) match {
              case Some(validCity) => Some(validCity.canonicalName)
              case None =>
                val expectedAdmins = cs
                  .flatMap(c => admin1ByCode.get((cc, c.admin1Code)).map(_.canonicalName))
                  .distinct
                  .mkString(", ")
                issues += s"Hierarchy mismatch: city '${cityOpt.get}' does not belong to '${a1.canonicalName}' (expected: $expectedAdmins)"
                Some(cs.head.canonicalName)
            }
          case (None, cs) if cs.nonEmpty =>
            Some(cs.head.canonicalName)
          case _ =>
            cityOpt
        }

        // Infer canonical Admin1 from unambiguous city if province was omitted
        val inferredAdmin1 = resolvedAdmin1.map(_.canonicalName).orElse {
          if (matchedCities.map(_.admin1Code).distinct.size == 1)
            admin1ByCode.get((cc, matchedCities.head.admin1Code)).map(_.canonicalName)
          else None
        }

        GeoValidation(
          isValid = issues.isEmpty,
          countryCode = Some(cc),
          canonicalAdmin1 = inferredAdmin1.orElse(provinceOpt),
          canonicalCity = canonicalCity,
          issues = issues.toList
        )
    }
  }

  /**
   * Get all unique country codes present in the gazetteer.
   */
  def allCountryCodes: Set[String] = {
    (admin1s.map(_.countryCode) ++ cities.map(_.countryCode)).toSet
  }
}

object GeoGazetteer {

  /** Embedded reference gazetteer covering major regions for offline prototype & unit testing. */
  lazy val default: GeoGazetteer = new GeoGazetteer(
    admin1s = List(
      // China
      Admin1Record("CN", "GD", "Guangdong",  Set("广东省", "广东", "Guangdong Province")),
      Admin1Record("CN", "BJ", "Beijing",    Set("北京市", "北京")),
      Admin1Record("CN", "SH", "Shanghai",   Set("上海市", "上海")),
      Admin1Record("CN", "ZJ", "Zhejiang",   Set("浙江省", "浙江", "Zhejiang Province")),
      Admin1Record("CN", "JS", "Jiangsu",    Set("江苏省", "江苏", "Jiangsu Province")),
      Admin1Record("CN", "SC", "Sichuan",    Set("四川省", "四川", "Sichuan Province")),
      Admin1Record("CN", "HB", "Hubei",      Set("湖北省", "湖北", "Hubei Province")),
      Admin1Record("CN", "HN", "Hunan",      Set("湖南省", "湖南", "Hunan Province")),
      Admin1Record("CN", "HA", "Henan",      Set("河南省", "河南", "Henan Province")),
      Admin1Record("CN", "SN", "Shaanxi",    Set("陕西省", "陕西", "Shaanxi Province")),
      Admin1Record("CN", "SX", "Shanxi",     Set("山西省", "山西", "Shanxi Province")),
      Admin1Record("CN", "SD", "Shandong",   Set("山东省", "山东", "Shandong Province")),
      Admin1Record("CN", "LN", "Liaoning",   Set("辽宁省", "辽宁", "Liaoning Province")),
      Admin1Record("CN", "JL", "Jilin",      Set("吉林省", "吉林", "Jilin Province")),
      Admin1Record("CN", "HL", "Heilongjiang", Set("黑龙江省", "黑龙江", "Heilongjiang Province")),
      Admin1Record("CN", "NM", "Inner Mongolia", Set("内蒙古自治区", "内蒙古", "Inner Mongolia")),
      Admin1Record("CN", "GX", "Guangxi",    Set("广西壮族自治区", "广西", "Guangxi")),
      Admin1Record("CN", "YN", "Yunnan",     Set("云南省", "云南", "Yunnan Province")),
      Admin1Record("CN", "GZ", "Guizhou",    Set("贵州省", "贵州", "Guizhou Province")),
      Admin1Record("CN", "FJ", "Fujian",     Set("福建省", "福建", "Fujian Province")),
      Admin1Record("CN", "ZJ", "Zhejiang",   Set("浙江省", "浙江", "Zhejiang Province")),
      Admin1Record("CN", "AH", "Anhui",      Set("安徽省", "安徽", "Anhui Province")),
      Admin1Record("CN", "JX", "Jiangxi",    Set("江西省", "江西", "Jiangxi Province")),
      Admin1Record("CN", "HI", "Hainan",     Set("海南省", "海南", "Hainan Province")),
      Admin1Record("CN", "XJ", "Xinjiang",   Set("新疆维吾尔自治区", "新疆", "Xinjiang")),
      Admin1Record("CN", "XZ", "Tibet",      Set("西藏自治区", "西藏", "Tibet")),
      Admin1Record("CN", "QH", "Qinghai",    Set("青海省", "青海", "Qinghai Province")),
      Admin1Record("CN", "GS", "Gansu",      Set("甘肃省", "甘肃", "Gansu Province")),
      Admin1Record("CN", "NX", "Ningxia",    Set("宁夏回族自治区", "宁夏", "Ningxia")),
      Admin1Record("CN", "TW", "Taiwan",     Set("台湾省", "台湾", "Taiwan Province")),
      Admin1Record("CN", "HK", "Hong Kong",  Set("香港特别行政区", "香港", "Hong Kong SAR")),
      Admin1Record("CN", "MO", "Macau",      Set("澳门特别行政区", "澳门", "Macau SAR")),
      // United States
      Admin1Record("US", "CA", "California", Set("CA", "Calif")),
      Admin1Record("US", "NY", "New York",   Set("NY")),
      Admin1Record("US", "TX", "Texas",      Set("TX")),
      Admin1Record("US", "WA", "Washington", Set("WA")),
      Admin1Record("US", "IL", "Illinois",   Set("IL")),
      Admin1Record("US", "VA", "virginia",   Set("VA", "Virg", "virginia")),
      Admin1Record("US", "MD", "maryland",   Set("MD", "maryland")),
      Admin1Record("US", "GA", "georgia",    Set("GA", "georgia")),
      Admin1Record("US", "FL", "florida",    Set("FL", "florida")),
      Admin1Record("US", "NC", "north carolina", Set("NC", "north carolina")),
      Admin1Record("US", "TN", "tennessee",  Set("TN", "tennessee")),
      Admin1Record("US", "CO", "colorado",   Set("CO", "colorado")),
      Admin1Record("US", "AZ", "arizona",    Set("AZ", "arizona")),
      Admin1Record("US", "MA", "massachusetts", Set("MA", "massachusetts")),
      Admin1Record("US", "NJ", "new jersey", Set("NJ", "new jersey")),
      Admin1Record("US", "PA", "pennsylvania", Set("PA", "pennsylvania")),
      Admin1Record("US", "OH", "ohio",       Set("OH", "ohio")),
      Admin1Record("US", "MI", "michigan",   Set("MI", "michigan")),
      Admin1Record("US", "IN", "indiana",    Set("IN", "indiana")),
      Admin1Record("US", "MO", "missouri",   Set("MO", "missouri")),
      Admin1Record("US", "WI", "wisconsin",  Set("WI", "wisconsin")),
      Admin1Record("US", "MN", "minnesota",  Set("MN", "minnesota")),
      // Germany
      Admin1Record("DE", "BE", "Berlin",              Set("Berlin")),
      Admin1Record("DE", "BY", "Bayern",              Set("Bavaria", "BY")),
      Admin1Record("DE", "NW", "Nordrhein-Westfalen", Set("NRW", "North Rhine-Westphalia")),
      Admin1Record("DE", "HH", "Hamburg",             Set("HH")),
      // Japan
      Admin1Record("JP", "13", "Tokyo",   Set("東京都", "東京", "Tokyo-to")),
      Admin1Record("JP", "27", "Osaka",   Set("大阪府", "大阪", "Osaka-fu")),
      // India
      Admin1Record("IN", "UP", "Uttar Pradesh", Set("Uttar Pradesh", "UP")),
      Admin1Record("IN", "MH", "Maharashtra", Set("Maharashtra", "MH")),
      Admin1Record("IN", "TN", "Tamil Nadu", Set("Tamil Nadu", "TN")),
      Admin1Record("IN", "KA", "Karnataka", Set("Karnataka", "KA")),
      Admin1Record("IN", "GJ", "Gujarat", Set("Gujarat", "GJ")),
      Admin1Record("IN", "WB", "West Bengal", Set("West Bengal", "WB")),
      Admin1Record("IN", "RJ", "Rajasthan", Set("Rajasthan", "RJ")),
      Admin1Record("IN", "AP", "Andhra Pradesh", Set("Andhra Pradesh", "AP")),
      Admin1Record("IN", "TG", "Telangana", Set("Telangana", "TG")),
      Admin1Record("IN", "KL", "Kerala", Set("Kerala", "KL")),
      Admin1Record("IN", "MP", "Madhya Pradesh", Set("Madhya Pradesh", "MP")),
      Admin1Record("IN", "PB", "Punjab", Set("Punjab", "PB")),
      Admin1Record("IN", "HR", "Haryana", Set("Haryana", "HR")),
      Admin1Record("IN", "BR", "Bihar", Set("Bihar", "BR")),
      Admin1Record("IN", "OR", "Odisha", Set("Odisha", "OR")),
      Admin1Record("IN", "JH", "Jharkhand", Set("Jharkhand", "JH")),
      Admin1Record("IN", "AS", "Assam", Set("Assam", "AS")),
      Admin1Record("IN", "CG", "Chhattisgarh", Set("Chhattisgarh", "CG")),
      Admin1Record("IN", "UT", "Uttarakhand", Set("Uttarakhand", "UT")),
      Admin1Record("IN", "HP", "Himachal Pradesh", Set("Himachal Pradesh", "HP")),
      Admin1Record("IN", "GA", "Goa", Set("Goa", "GA")),
      Admin1Record("IN", "DL", "Delhi", Set("Delhi", "DL")),
      Admin1Record("IN", "JK", "Jammu and Kashmir", Set("Jammu and Kashmir", "JK")),
      Admin1Record("IN", "LA", "Ladakh", Set("Ladakh", "LA")),

      // Indonesia
      Admin1Record("ID", "JK", "DKI Jakarta", Set("Jakarta", "DKI Jakarta", "Jakarta Raya")),
      Admin1Record("ID", "JB", "Jawa Barat",  Set("West Java")),
      // United Kingdom
      Admin1Record("GB", "ENG", "England", Set("England", "Greater London")),
      // Singapore / Hong Kong
      Admin1Record("SG", "SG", "Singapore", Set("Singapore")),
      Admin1Record("HK", "HK", "Hong Kong", Set("香港", "Hong Kong SAR"))
    ),
    cities = List(
      // China
      CityRecord("CN", "GD", "Guangzhou", Set("广州市", "广州", "Canton")),
      CityRecord("CN", "GD", "Shenzhen",  Set("深圳市", "深圳")),
      CityRecord("CN", "ZJ", "Hangzhou",  Set("杭州市", "杭州")),
      CityRecord("CN", "JS", "Nanjing",   Set("南京市", "南京")),
      CityRecord("CN", "JS", "Suzhou",    Set("苏州市", "苏州")),
      CityRecord("CN", "SC", "Chengdu",   Set("成都市", "成都")),
      CityRecord("CN", "BJ", "Beijing",   Set("北京市", "北京")),
      CityRecord("CN", "SH", "Shanghai",  Set("上海市", "上海")),
      // US
      CityRecord("US", "CA", "San Francisco", Set("SF")),
      CityRecord("US", "CA", "Los Angeles",   Set("LA")),
      CityRecord("US", "CA", "Mountain View", Set("Mountain View")),
      CityRecord("US", "NY", "New York",      Set("NYC", "New York City", "Manhattan")),
      CityRecord("US", "TX", "Austin",        Set("Austin")),
      CityRecord("US", "WA", "Seattle",       Set("Seattle")),
      CityRecord("US", "IL", "Chicago",       Set("Chicago")),
      CityRecord("US", "VA", "Arlington",     Set("Arlington")),
      CityRecord("US", "VA", "Alexandria",    Set("Alexandria")),
      CityRecord("US", "MD", "Baltimore",     Set("Baltimore")),
      CityRecord("US", "GA", "Atlanta",       Set("Atlanta")),
      CityRecord("US", "FL", "Miami",         Set("Miami")),
      CityRecord("US", "NC", "Charlotte",     Set("Charlotte")),
      CityRecord("US", "TN", "Nashville",     Set("Nashville")),
      CityRecord("US", "CO", "Denver",        Set("Denver")),
      CityRecord("US", "AZ", "Phoenix",       Set("Phoenix")),
      CityRecord("US", "MA", "Boston",        Set("Boston")),
      CityRecord("US", "NJ", "Newark",        Set("Newark")),
      CityRecord("US", "PA", "Philadelphia",  Set("Philadelphia")),
      CityRecord("US", "OH", "Columbus",      Set("Columbus")),
      CityRecord("US", "MI", "Detroit",       Set("Detroit")),
      CityRecord("US", "IN", "Indianapolis",  Set("Indianapolis")),
      CityRecord("US", "MO", "Kansas City",   Set("Kansas City")),
      CityRecord("US", "WI", "Milwaukee",     Set("Milwaukee")),
      CityRecord("US", "MN", "Minneapolis",   Set("Minneapolis")),
      // Germany
      CityRecord("DE", "BE", "Berlin",  Set("Berlin")),
      CityRecord("DE", "BY", "München", Set("Munich", "Muenchen")),
      CityRecord("DE", "NW", "Köln",    Set("Cologne", "Koeln")),
      CityRecord("DE", "HH", "Hamburg", Set("Hamburg")),
      // Japan
      CityRecord("JP", "13", "Shibuya-ku", Set("渋谷区", "Shibuya", "Tokyo")),
      CityRecord("JP", "27", "Osaka",      Set("大阪市")),
      // India
      CityRecord("IN", "UP", "Noida",         Set("Noida")),
      CityRecord("IN", "UP", "Lucknow",       Set("Lucknow")),
      CityRecord("IN", "UP", "Kanpur",        Set("Kanpur")),
      CityRecord("IN", "UP", "Agra",          Set("Agra")),
      CityRecord("IN", "UP", "Varanasi",      Set("Varanasi")),
      CityRecord("IN", "MH", "Mumbai",        Set("Bombay", "Mumbai")),
      CityRecord("IN", "MH", "Pune",          Set("Pune")),
      CityRecord("IN", "MH", "Nagpur",        Set("Nagpur")),
      CityRecord("IN", "TN", "Chennai",       Set("Madras", "Chennai")),
      CityRecord("IN", "TN", "Coimbatore",    Set("Coimbatore")),
      CityRecord("IN", "KA", "Bengaluru",     Set("Bangalore", "Bengaluru")),
      CityRecord("IN", "KA", "Mysuru",        Set("Mysore", "Mysuru")),
      CityRecord("IN", "GJ", "Ahmedabad",     Set("Ahmedabad")),
      CityRecord("IN", "GJ", "Surat",         Set("Surat")),
      CityRecord("IN", "WB", "Kolkata",       Set("Calcutta", "Kolkata")),
      CityRecord("IN", "RJ", "Jaipur",        Set("Jaipur")),
      CityRecord("IN", "AP", "Visakhapatnam", Set("Visakhapatnam")),
      CityRecord("IN", "TG", "Hyderabad",     Set("Hyderabad")),
      CityRecord("IN", "KL", "Thiruvananthapuram", Set("Trivandrum", "Thiruvananthapuram")),
      CityRecord("IN", "MP", "Bhopal",        Set("Bhopal")),
      CityRecord("IN", "MP", "Indore",        Set("Indore")),
      CityRecord("IN", "PB", "Ludhiana",      Set("Ludhiana")),
      CityRecord("IN", "HR", "Gurugram",      Set("Gurgaon", "Gurugram")),
      CityRecord("IN", "HR", "Faridabad",     Set("Faridabad")),
      CityRecord("IN", "BR", "Patna",         Set("Patna")),
      CityRecord("IN", "OR", "Bhubaneswar",   Set("Bhubaneswar")),
      CityRecord("IN", "JH", "Ranchi",        Set("Ranchi")),
      CityRecord("IN", "AS", "Guwahati",      Set("Guwahati")),
      CityRecord("IN", "CG", "Raipur",        Set("Raipur")),
      CityRecord("IN", "UT", "Dehradun",      Set("Dehradun")),
      CityRecord("IN", "HP", "Shimla",        Set("Shimla")),
      CityRecord("IN", "GA", "Panaji",        Set("Panaji")),
      CityRecord("IN", "DL", "New Delhi",     Set("New Delhi", "Delhi")),
      CityRecord("IN", "JK", "Srinagar",      Set("Srinagar")),
      CityRecord("IN", "LA", "Leh",           Set("Leh")),

      // Indonesia
      CityRecord("ID", "JK", "Jakarta Selatan", Set("South Jakarta", "Jakarta")),
      CityRecord("ID", "JB", "Bandung",         Set("Bandung")),
      // UK
      CityRecord("GB", "ENG", "London", Set("London")),
      // SG / HK
      CityRecord("SG", "SG", "Singapore", Set("Singapore")),
      CityRecord("HK", "HK", "Hong Kong", Set("香港", "Central"))
    )
  )

  /**
   * Load full GeoNames dumps (`admin1CodesASCII.txt` and `cities15000.txt`) from disk
   * and merge them with the embedded aliases.
   */
  def loadFromGeoNames(admin1File: File, citiesFile: File): GeoGazetteer = {
    val loadedAdmin1 = if (admin1File.exists()) {
      val src = Source.fromFile(admin1File, "UTF-8")
      try {
        src.getLines().flatMap { line =>
          val cols = line.split("\t")
          if (cols.length >= 3 && cols(0).contains(".")) {
            val parts = cols(0).split("\\.", 2)
            Some(Admin1Record(parts(0), parts(1), cols(2), Set(cols(1), cols(2))))
          } else None
        }.toList
      } finally src.close()
    } else Nil

    val loadedCities = if (citiesFile.exists()) {
      val src = Source.fromFile(citiesFile, "UTF-8")
      try {
        src.getLines().flatMap { line =>
          val cols = line.split("\t")
          if (cols.length >= 11) {
            val cc     = cols(8)
            val a1     = cols(10)
            val name   = cols(1)
            val ascii  = cols(2)
            val alts   = cols(3).split(",").filter(_.nonEmpty).take(8).toSet
            Some(CityRecord(cc, a1, name, alts + ascii))
          } else None
        }.toList
      } finally src.close()
    } else Nil

    new GeoGazetteer(default.admin1s ++ loadedAdmin1, default.cities ++ loadedCities)
  }
}
