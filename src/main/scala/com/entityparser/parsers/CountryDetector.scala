package com.entityparser.parsers

import com.entityparser.geo.{GeoGazetteer, PlaceEvidence}
import com.entityparser.model._
import com.entityparser.norm.EntityNormalizer

/**
 * Local-Conventions-First region/country detector (§2).
 * Inspects Unicode scripts, legal-form prefixes/suffixes, postal formats,
 * and geographic anchors before dispatching to country-specific parsers.
 *
 * Also provides Stage 1 RAG-style candidate extraction: deterministic signals
 * + weighted global reverse-index gazetteer evidence → CountryCandidates (Top 5 raw scores).
 */
object CountryDetector {

  private val HasHan        = ".*\\p{InCJK_Unified_Ideographs}.*".r
  private val HasHiragana   = ".*\\p{InHiragana}.*".r
  private val HasKatakana   = ".*\\p{InKatakana}.*".r
  private val HasHangul     = ".*\\p{InHangul_Syllables}.*".r
  private val HasCyrillic   = ".*\\p{InCyrillic}.*".r
  private val HasArabic     = ".*\\p{InArabic}.*".r
  private val HasDevanagari = ".*\\p{InDevanagari}.*".r
  private val HasThai       = ".*\\p{InThai}.*".r

  private val UsStateZip  = """\b(AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)\s+\d{5}(-\d{4})?\b""".r
  private val UkPostcode  = """\b[A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2}\b""".r
  private val DeStreet    = """(?i)(straße|strasse|str\.|platz|allee|gmbh|e\.k\.|\bag\b|\bkg\b|\bmünchen\b|\bberlin\b|\bhamburg\b|\bköln\b)""".r
  private val IdAnchor    = """(?i)(\bpt\.?\s+|\bcv\.?\s+|\bud\.?\s+|\btbk\b|\bjakarta\b|\bbandung\b|\bjl\.\s*|\bjalan\s+)""".r
  private val VnAnchor    = """(?i)(công ty|tnhh|cổ phần|việt nam|hà nội|hồ chí minh)""".r
  private val JpAnchor    = """(株式会社|有限会社|合同会社|東京都|大阪府|渋谷区|〒\d{3}-\d{4})""".r
  private val CnAnchor    = """(有限公司|有限责任公司|股份有限公司|个体工商户|省|市|区|县|路|街|号)""".r
  private val TwAnchor    = """(台北|台中|高雄|新北|桃园|台南|台湾)""".r
  private val HkAnchor    = """(香港|九龙|新界|中环|港岛)""".r
  private val MoAnchor    = """(澳门|澳门特别行政区)""".r
  private val SgAnchor    = """(?i)(singapore|\bSG\b|\bPTE\.?\s*LTD)""".r
  private val MyAnchor    = """(?i)(malaysia|\bKL\b|\bKUALA\s+LUMPUR\b|\bSDN\.?\s*BHD)""".r
  private val PhAnchor    = """(?i)(philippines|\bPH\b|\bMAKATI\b|\bMANILA\b|\bCEBU\b)""".r
  private val ThAnchor    = """(?i)(thailand|\bTH\b|\bBANGKOK\b|\bBKK\b)""".r
  private val AeAnchor    = """(?i)(united arab emirates|\bUAE\b|\bDUBAI\b|\bABU\s+DHABI\b)""".r
  private val SaAnchor    = """(?i)(saudi arabia|\bSA\b|\bRIYADH\b|\bJEDDAH\b)""".r
  private val QaAnchor    = """(?i)(qatar|\bQA\b|\bDOHA\b)""".r
  private val KwAnchor    = """(?i)(kuwait|\bKW\b|\bKUWAIT\b)""".r
  private val EgAnchor    = """(?i)(egypt|\bEG\b|\bCAIRO\b)""".r
  private val MaAnchor    = """(?i)(morocco|\bMA\b|\bCASABLANCA\b|\bRABAT\b)""".r
  private val NgAnchor    = """(?i)(nigeria|\bNG\b|\bLAGOS\b|\bABUJA\b)""".r
  private val KeAnchor    = """(?i)(kenya|\bKE\b|\bNAIROBI\b)""".r
  private val ZaAnchor    = """(?i)(south africa|\bZA\b|\bJOHANNESBURG\b|\bCAPE\s+TOWN\b)""".r
  private val BrAnchor    = """(?i)(brazil|\bBR\b|\bSAO\s+PAULO\b|\bRIO\s+DE\s+JANEIRO\b)""".r
  private val ArAnchor    = """(?i)(argentina|\bAR\b|\bBUENOS\s+AIRES\b)""".r
  private val ClAnchor    = """(?i)(chile|\bCL\b|\bSANTIAGO\b)""".r
  private val CoAnchor    = """(?i)(colombia|\bCO\b|\bBOGOTA\b)""".r
  private val PeAnchor    = """(?i)(peru|\bPE\b|\bLIMA\b)""".r
  private val MxAnchor    = """(?i)(mexico|\bMX\b|\bMEXICO\s+CITY\b)""".r
  private val CaAnchor    = """(?i)(canada|\bCA\b|\bTORONTO\b|\bVANCOUVER\b|\bMONTREAL\b)""".r
  private val AuAnchor    = """(?i)(australia|\bAU\b|\bSYDNEY\b|\bMELBOURNE\b|\bBRISBANE\b)""".r
  private val NzAnchor    = """(?i)(new zealand|\bNZ\b|\bAUCKLAND\b|\bWELLINGTON\b)""".r
  private val FrAnchor    = """(?i)(france|\bFR\b|\bPARIS\b|\bLYON\b|\bMARSEILLE\b|\bsarl|\bsas)""".r
  private val EsAnchor    = """(?i)(spain|\bES\b|\bMADRID\b|\bBARCELONA\b|\bs\.l\.|\bs\.a\.)""".r
  private val PtAnchor    = """(?i)(portugal|\bPT\b|\bLISBOA\b|\bPORTO\b|\blda\.)""".r
  private val ItAnchor    = """(?i)(italy|\bIT\b|\bROME\b|\bMILAN\b|\bMILANO\b|\bspa\b|\bsrl\b)""".r
  private val NlAnchor    = """(?i)(netherlands|\bNL\b|\bAMSTERDAM\b|\bROTTERDAM\b|\bb\.v\.|\bn\.v\.)""".r
  private val BeAnchor    = """(?i)(belgium|\bBE\b|\bBRUSSELS\b|\bANTWERP\b)""".r
  private val ChAnchor    = """(?i)(switzerland|\bCH\b|\bZURICH\b|\bGENEVA\b)""".r
  private val AtAnchor    = """(?i)(austria|\bAT\b|\bVIENNA\b|\bWIEN\b)""".r
  private val SeAnchor    = """(?i)(sweden|\bSE\b|\bSTOCKHOLM\b)""".r
  private val NoAnchor    = """(?i)(norway|\bNO\b|\bOSLO\b)""".r
  private val DkAnchor    = """(?i)(denmark|\bDK\b|\bCOPENHAGEN\b)""".r
  private val FiAnchor    = """(?i)(finland|\bFI\b|\bHELSINKI\b)""".r
  private val PlAnchor    = """(?i)(poland|\bPL\b|\bWARSAW\b)""".r
  private val CzAnchor    = """(?i)(czech|\bCZ\b|\bPRAGUE\b)""".r
  private val HuAnchor    = """(?i)(hungary|\bHU\b|\bBUDAPEST\b)""".r
  private val RoAnchor    = """(?i)(romania|\bRO\b|\bBUCHAREST\b)""".r
  private val TrAnchor    = """(?i)(turkey|\bTR\b|\bISTANBUL\b|\bANKARA\b|\bA\.S\.\b|\bLTD\.\s*STI)""".r
  private val RuAnchor    = """(?i)(russia|\bRU\b|\bMOSCOW\b|\bOOO\b|\bAO\b|\bPAO\b)""".r
  private val UaAnchor    = """(?i)(ukraine|\bUA\b|\bKYIV\b|\bKIEV\b)""".r
  private val ByAnchor    = """(?i)(belarus|\bBY\b|\bMINSK\b)""".r
  private val KzAnchor    = """(?i)(kazakhstan|\bKZ\b|\bALMATY\b|\bASTANA\b)""".r
  private val IlAnchor    = """(?i)(israel|\bIL\b|\bTEL\s+AVIV\b|\bJERUSALEM\b)""".r
  private val IrAnchor    = """(?i)(iran|\bIR\b|\bTEHRAN\b)""".r
  private val PkAnchor    = """(?i)(pakistan|\bPK\b|\bKARACHI\b|\bLAHORE\b|\bISLAMABAD\b)""".r
  private val BdAnchor    = """(?i)(bangladesh|\bBD\b|\bDHAKA\b)""".r
  private val LkAnchor    = """(?i)(sri lanka|\bLK\b|\bCOLOMBO\b)""".r
  private val MmAnchor    = """(?i)(myanmar|\bMM\b|\bYANGON\b)""".r
  private val KhAnchor    = """(?i)(cambodia|\bKH\b|\bPHNOM\s+PENH\b)""".r
  private val LaAnchor    = """(?i)(laos|\bLA\b|\bVIENTIANE\b)""".r
  private val MnAnchor    = """(?i)(mongolia|\bMN\b|\bULAANBAATAR\b)""".r
  private val NpAnchor    = """(?i)(nepal|\bNP\b|\bKATHMANDU\b)""".r
  private val AfAnchor    = """(?i)(afghanistan|\bAF\b|\bKABUL\b)""".r
  private val IsAnchor    = """(?i)(iceland|\bIS\b|\bREYKJAVIK\b)""".r
  private val IeAnchor    = """(?i)(ireland|\bIE\b|\bDUBLIN\b)""".r

  // --- Tier-2 in-language-group discriminator patterns (weight 3.5, max 1 per country) ---
  private val SimpleDiscriminatorPatterns: List[(String, scala.util.matching.Regex)] = List(
    ("MX", """(?iu)\b(?:colonia|delegaci[oó]n)\b""".r),
    ("AR", """(?iu)\bcaba\b""".r),
    ("CL", """(?iu)\bcomuna\b""".r),
    ("GB", """(?iu)\bpostcode\b""".r),
    ("US", """(?iu)\bzip\s+code\b""".r),
    ("BR", """(?iu)\b(?:bairro|cep)\b""".r),
    ("PT", """(?iu)\bc[oó]digo\s+postal\b""".r),
    ("SA", """(?iu)\bksa\b""".r)
  )
  private val AuSuburbPattern        = """(?iu)\bsuburb\b""".r
  private val AuStateAbbrevPattern   = """(?iu)\b(?:nsw|vic|qld|sa|wa|tas|nt|act)\b""".r
  private val FourDigitNumberPattern = """(?<![-\d])\b\d{4}\b(?![-\d])""".r

  // --- Hard identifier patterns ---
  // Matches both compact IBANs (DE89370400440532013000) and the standard
  // printed spaced form in groups of 4 (DE89 3704 0044 0532 0130 00).
  private val IbanPattern  = """\b([A-Z]{2})\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\b""".r
  private val BicPattern   = """\b[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?\b""".r
  private val PhonePattern = """\+(\d{1,3})\s*\d""".r
  private val CctldPattern = """\b[a-z0-9-]+\.([a-z]{2})\b""".r

  // --- Generic address/admin stopwords skipped before gazetteer lookup ---
  private val GenericPlaceStopwords: Set[String] = Set(
    "street", "st", "road", "rd", "avenue", "ave", "boulevard", "blvd",
    "lane", "ln", "drive", "dr", "city", "town", "village", "province",
    "state", "district", "county", "street.", "rue", "calle", "avenida",
    "avenida.", "strada", "strasse", "straße", "plaza", "square", "road.",
    "省", "市", "区", "县", "镇", "村", "街道"
  )

  private val Log2 = math.log(2.0)

  // --- Legal form patterns (subset for country signals) ---
  private val LegalFormPatterns: Map[String, List[(String, String)]] = Map(
    "ID" -> List(("PT\\s+", "prefix"), ("\\bTbk\b", "suffix"), ("\\bPersero\b", "suffix"), ("\\bCV\b", "prefix")),
    "DE" -> List(("\\bGmbH\b", "suffix"), ("\\bAG\b", "suffix"), ("\\bKG\b", "suffix"), ("\\bOHG\b", "suffix"), ("\\bSE\b", "suffix"), ("\\bUG\b", "suffix"), ("\\be\\.K\\.", "suffix")),
    "JP" -> List(("株式会社", "either"), ("有限会社", "either"), ("合同会社", "either")),
    "KR" -> List(("주식회사", "either"), ("유한회사", "either")),
    "CN" -> List(("有限公司", "suffix"), ("有限责任公司", "suffix"), ("股份有限公司", "suffix"), ("个体工商户", "suffix")),
    "HK" -> List(("Limited", "suffix"), ("Ltd\\.", "suffix"), ("有限公司", "suffix")),
    "TW" -> List(("股份有限公司", "suffix"), ("有限公司", "suffix")),
    "SG" -> List(("Pte\\.\\s*Ltd\\.", "suffix"), ("Ltd\\.", "suffix"), ("LLP", "suffix")),
    "MY" -> List(("Sdn\\.\\s*Bhd\\.", "suffix"), ("Berhad", "suffix"), ("Bhd\\.", "suffix")),
    "VN" -> List(("Công ty TNHH", "prefix"), ("Công ty Cổ phần", "prefix")),
    "US" -> List(("Inc\\.", "suffix"), ("Corp\\.", "suffix"), ("LLC", "suffix"), ("L\\.L\\.C\\.", "suffix"), ("LP", "suffix"), ("LLP", "suffix")),
    "GB" -> List(("Limited", "suffix"), ("Ltd\\.", "suffix"), ("PLC", "suffix"), ("LLP", "suffix")),
    "FR" -> List(("SA", "suffix"), ("SAS", "suffix"), ("SARL", "suffix"), ("EURL", "suffix")),
    "ES" -> List(("S\\.A\\.", "suffix"), ("S\\.L\\.", "suffix"), ("S\\.L\\.U\\.", "suffix")),
    "PT" -> List(("SA", "suffix"), ("Lda\\.", "suffix"), ("SGPS", "suffix")),
    "IT" -> List(("S\\.p\\.A\\.", "suffix"), ("S\\.r\\.l\\.", "suffix")),
    "NL" -> List(("B\\.V\\.", "suffix"), ("N\\.V\\.", "suffix")),
    "BE" -> List(("NV/SA", "suffix"), ("BV/SRL", "suffix")),
    "BR" -> List(("SA", "suffix"), ("Ltda\\.", "suffix")),
    "MX" -> List(("S\\.A\\.\\s*de\\s*C\\.V\\.", "suffix"), ("S\\.de\\s*R\\.L\\.\\s*de\\s*C\\.V\\.", "suffix")),
    "AR" -> List(("SA", "suffix"), ("S\\.R\\.L\\.", "suffix")),
    "AU" -> List(("Pty Ltd", "suffix"), ("Ltd", "suffix")),
    "NZ" -> List(("Limited", "suffix"), ("Ltd", "suffix")),
    "AE" -> List(("LLC", "suffix"), ("PJSC", "suffix"), ("PrJSC", "suffix")),
    "SA" -> List(("LLC", "suffix"), ("JSC", "suffix")),
    "TR" -> List(("A\\.S\\.", "suffix"), ("Ltd\\.\\s*Sti\\.", "suffix")),
    "RU" -> List(("ООО", "prefix"), ("АО", "suffix"), ("ПАО", "suffix")),
    "IN" -> List(("Private Limited", "suffix"), ("Pvt\\.\\s*Ltd\\.", "suffix"), ("Limited", "suffix"), ("LLP", "suffix")),
    "ZA" -> List(("Pty\\) Ltd", "suffix"), ("Ltd", "suffix"), ("Inc\\.", "suffix")),
    "PH" -> List(("Corp\\.", "suffix"), ("Inc\\.", "suffix")),
    "EG" -> List(("S\\.A\\.E\\.", "suffix")),
    "MA" -> List(("SA", "suffix"), ("SARL", "suffix")),
    "NG" -> List(("Plc", "suffix"), ("Ltd", "suffix")),
    "KE" -> List(("Plc", "suffix"), ("Ltd", "suffix")),
    "TH" -> List(("Public Company Limited", "suffix"), ("PLC", "suffix"), ("Co\\.\\s*Ltd\\.", "suffix")),
    "CA" -> List(("Inc\\.", "suffix"), ("Ltd\\.", "suffix"), ("Corp\\.", "suffix"), ("ULC", "suffix"))
  )

  // --- Surname patterns (high-frequency, culture-specific) ---
  private val SurnamePatterns: Map[String, List[(String, String)]] = Map(
    "CN" -> List(("王", "chinese"), ("李", "chinese"), ("张", "chinese"), ("刘", "chinese"), ("陈", "chinese"), ("杨", "chinese"), ("黄", "chinese"), ("赵", "chinese"), ("吴", "chinese"), ("周", "chinese"), ("欧阳", "chinese"), ("司马", "chinese"), ("诸葛", "chinese")),
    "KR" -> List(("김", "korean"), ("이", "korean"), ("박", "korean"), ("최", "korean"), ("정", "korean"), ("강", "korean"), ("조", "korean"), ("윤", "korean"), ("장", "korean"), ("임", "korean")),
    "JP" -> List(("佐藤", "japanese"), ("鈴木", "japanese"), ("高橋", "japanese"), ("田中", "japanese"), ("伊藤", "japanese"), ("渡辺", "japanese"), ("山本", "japanese"), ("中村", "japanese"), ("小林", "japanese"), ("加藤", "japanese")),
    "VN" -> List(("Nguyễn", "vietnamese"), ("Trần", "vietnamese"), ("Lê", "vietnamese"), ("Phạm", "vietnamese"), ("Hoàng", "vietnamese"), ("Huỳnh", "vietnamese"), ("Phan", "vietnamese"), ("Vũ", "vietnamese"), ("Võ", "vietnamese"), ("Đặng", "vietnamese"), ("Bùi", "vietnamese"), ("Đỗ", "vietnamese")),
    "ES" -> List(("García", "spanish"), ("Rodríguez", "spanish"), ("González", "spanish"), ("Fernández", "spanish"), ("López", "spanish"), ("Martínez", "spanish"), ("Sánchez", "spanish"), ("Pérez", "spanish"), ("Gómez", "spanish"), ("Martín", "spanish")),
    "AR" -> List(("García", "spanish"), ("Rodríguez", "spanish"), ("González", "spanish"), ("Fernández", "spanish"), ("López", "spanish")),
    "MX" -> List(("García", "spanish"), ("Rodríguez", "spanish"), ("González", "spanish"), ("Fernández", "spanish"), ("López", "spanish")),
    "PT" -> List(("Silva", "portuguese"), ("Santos", "portuguese"), ("Ferreira", "portuguese"), ("Pereira", "portuguese"), ("Oliveira", "portuguese")),
    "BR" -> List(("Silva", "portuguese"), ("Santos", "portuguese"), ("Ferreira", "portuguese"), ("Pereira", "portuguese"), ("Oliveira", "portuguese")),
    "RU" -> List(("Ivanov", "russian"), ("Smirnov", "russian"), ("Kuznetsov", "russian"), ("Popov", "russian"), ("Sokolov", "russian")),
    "SA" -> List(("Al-Saud", "arabic"), ("Al-Mansoor", "arabic"), ("Al-Rashid", "arabic")),
    "AE" -> List(("Al-Maktoum", "arabic"), ("Al-Nahyan", "arabic")),
    "ID" -> List(("Wijaya", "indonesian"), ("Santoso", "indonesian"), ("Hidayat", "indonesian")),
    "MY" -> List(("Tan", "chinese_diaspora"), ("Lee", "chinese_diaspora"), ("Lim", "chinese_diaspora"), ("Ng", "chinese_diaspora"), ("Wong", "chinese_diaspora")),
    "SG" -> List(("Tan", "chinese_diaspora"), ("Lee", "chinese_diaspora"), ("Lim", "chinese_diaspora"), ("Ng", "chinese_diaspora"), ("Wong", "chinese_diaspora"), ("Ong", "chinese_diaspora")),
    "HK" -> List(("Chan", "chinese_diaspora"), ("Lee", "chinese_diaspora"), ("Wong", "chinese_diaspora"), ("Lam", "chinese_diaspora"), ("Cheung", "chinese_diaspora")),
    "TW" -> List(("Chen", "chinese_diaspora"), ("Lin", "chinese_diaspora"), ("Huang", "chinese_diaspora"), ("Chang", "chinese_diaspora"), ("Wu", "chinese_diaspora"))
  )

  // --- Phone country code map ---
  private val PhoneCountryCodes: Map[String, String] = Map(
    "1" -> "US", "7" -> "RU", "20" -> "EG", "27" -> "ZA", "30" -> "GR", "31" -> "NL",
    "32" -> "BE", "33" -> "FR", "34" -> "ES", "36" -> "HU", "39" -> "IT", "40" -> "RO",
    "41" -> "CH", "43" -> "AT", "44" -> "GB", "45" -> "DK", "46" -> "SE", "47" -> "NO",
    "48" -> "PL", "49" -> "DE", "51" -> "PE", "52" -> "MX", "53" -> "CU", "54" -> "AR",
    "55" -> "BR", "56" -> "CL", "57" -> "CO", "58" -> "VE", "60" -> "MY", "61" -> "AU",
    "62" -> "ID", "63" -> "PH", "64" -> "NZ", "65" -> "SG", "66" -> "TH", "81" -> "JP",
    "82" -> "KR", "84" -> "VN", "86" -> "CN", "90" -> "TR", "91" -> "IN", "92" -> "PK",
    "93" -> "AF", "94" -> "LK", "95" -> "MM", "98" -> "IR", "212" -> "MA", "213" -> "DZ",
    "216" -> "TN", "218" -> "LY", "220" -> "GM", "221" -> "SN", "222" -> "MR", "223" -> "ML",
    "224" -> "GN", "225" -> "CI", "226" -> "BF", "227" -> "NE", "228" -> "TG", "229" -> "BJ",
    "230" -> "MU", "231" -> "LR", "232" -> "SL", "233" -> "GH", "234" -> "NG", "235" -> "TD",
    "236" -> "CF", "237" -> "CM", "238" -> "CV", "239" -> "ST", "240" -> "GQ", "241" -> "GA",
    "242" -> "CG", "243" -> "CD", "244" -> "AO", "245" -> "GW", "246" -> "IO", "248" -> "AC",
    "249" -> "SD", "250" -> "RW", "251" -> "ET", "252" -> "SO", "253" -> "DJ", "254" -> "KE",
    "255" -> "TZ", "256" -> "UG", "257" -> "BI", "258" -> "MZ", "260" -> "ZM", "261" -> "MG",
    "262" -> "RE", "263" -> "ZW", "264" -> "SH", "265" -> "MW", "266" -> "LS", "267" -> "BW",
    "268" -> "SZ", "269" -> "KM", "290" -> "SH", "291" -> "ER", "297" -> "AW", "298" -> "FO",
    "350" -> "GI", "351" -> "PT", "352" -> "LU", "353" -> "IE", "354" -> "IS", "355" -> "AL",
    "356" -> "MT", "357" -> "CY", "358" -> "FI", "359" -> "BG", "370" -> "LT", "371" -> "LV",
    "372" -> "EE", "373" -> "MD", "374" -> "AM", "375" -> "BY", "376" -> "AD", "377" -> "MC",
    "378" -> "SM", "379" -> "VA", "380" -> "UA", "381" -> "RS", "382" -> "ME", "383" -> "XK",
    "385" -> "HR", "386" -> "SI", "387" -> "BA", "389" -> "MK", "420" -> "CZ", "421" -> "SK",
    "423" -> "LI", "500" -> "FK", "501" -> "BZ", "502" -> "GT", "503" -> "SV", "504" -> "HN",
    "505" -> "NI", "506" -> "CR", "507" -> "PA", "508" -> "PM", "509" -> "HT", "590" -> "GP",
    "591" -> "BO", "592" -> "GY", "593" -> "EC", "594" -> "GF", "595" -> "PY", "596" -> "MQ",
    "597" -> "SR", "598" -> "UY", "599" -> "CW", "670" -> "TL", "672" -> "NF", "673" -> "BN",
    "674" -> "NR", "675" -> "PG", "676" -> "TO", "677" -> "SB", "678" -> "VU", "679" -> "FJ",
    "680" -> "PW", "681" -> "WF", "682" -> "CK", "683" -> "NU", "685" -> "WS", "686" -> "KI",
    "687" -> "NC", "688" -> "TV", "689" -> "PF", "690" -> "TK", "691" -> "FM", "692" -> "MH",
    "850" -> "KP", "852" -> "HK", "853" -> "MO", "855" -> "KH", "856" -> "LA", "880" -> "BD",
    "886" -> "TW", "960" -> "MV", "961" -> "LB", "962" -> "JO", "963" -> "SY", "964" -> "IQ",
    "965" -> "KW", "966" -> "SA", "967" -> "YE", "968" -> "OM", "970" -> "PS", "971" -> "AE",
    "972" -> "IL", "973" -> "BH", "974" -> "QA", "975" -> "BT", "976" -> "MN", "977" -> "NP",
    "992" -> "TJ", "993" -> "TM", "994" -> "AZ", "995" -> "GE", "996" -> "KG", "998" -> "UZ"
  )

  // --- ccTLD to country map (subset) ---
  private val CctldToCountry: Map[String, String] = Map(
    "cn" -> "CN", "jp" -> "JP", "kr" -> "KR", "tw" -> "TW", "hk" -> "HK", "mo" -> "MO",
    "sg" -> "SG", "my" -> "MY", "id" -> "ID", "th" -> "TH", "vn" -> "VN", "ph" -> "PH",
    "us" -> "US", "uk" -> "GB", "gb" -> "GB", "ca" -> "CA", "au" -> "AU", "nz" -> "NZ",
    "de" -> "DE", "fr" -> "FR", "es" -> "ES", "it" -> "IT", "nl" -> "NL", "be" -> "BE",
    "ch" -> "CH", "at" -> "AT", "se" -> "SE", "no" -> "NO", "dk" -> "DK", "fi" -> "FI",
    "pt" -> "PT", "ie" -> "IE", "pl" -> "PL", "cz" -> "CZ", "ru" -> "RU", "br" -> "BR",
    "mx" -> "MX", "ar" -> "AR", "cl" -> "CL", "co" -> "CO", "pe" -> "PE", "ve" -> "VE",
    "in" -> "IN", "pk" -> "PK", "bd" -> "BD", "lk" -> "LK", "ng" -> "NG", "za" -> "ZA",
    "ke" -> "KE", "eg" -> "EG", "ma" -> "MA", "sa" -> "SA", "ae" -> "AE", "qa" -> "QA",
    "kw" -> "KW", "bh" -> "BH", "om" -> "OM", "tr" -> "TR", "il" -> "IL", "ir" -> "IR",
    "hk" -> "HK", "mo" -> "MO", "tw" -> "TW"
  )

  // --- Script/Language to country map ---
  private val ScriptToCountries: Map[String, List[String]] = Map(
    "hiragana"        -> List("JP"),
    "katakana"        -> List("JP"),
    "hangul"          -> List("KR"),
    "cyrillic"        -> List("RU", "UA", "BY", "KZ", "BG", "RS", "MK"),
    "arabic"          -> List("SA", "AE", "EG", "MA", "DZ", "TN", "LY", "IQ", "SY", "JO", "LB", "PS", "KW", "QA", "BH", "OM", "YE"),
    "devanagari"      -> List("IN", "NP"),
    "thai"            -> List("TH"),
    "han_simplified"  -> List("CN", "SG", "MY"),
    "han_traditional" -> List("TW", "HK", "MO")
  )

  /**
   * Original deterministic single-country detection (unchanged behavior).
   * Used by existing parsers as a hint.
   */
  def detectCountry(text: String, explicitHint: Option[String] = None): Option[String] = {
    explicitHint.map(_.trim.toUpperCase(java.util.Locale.ROOT)).filter(_.length == 2).orElse {
      val s     = text.trim
      val lower = s.toLowerCase(java.util.Locale.ROOT)

      if (HasHiragana.matches(s) || HasKatakana.matches(s) || JpAnchor.findFirstIn(s).isDefined) Some("JP")
      else if (HasHangul.matches(s)) Some("KR")
      else if (HasCyrillic.matches(s)) Some("RU")
      else if (HasArabic.matches(s)) Some("SA")
      else if (HasHan.matches(s)) {
        if (lower.contains("hong kong") || s.contains("香港") || HkAnchor.findFirstIn(s).isDefined) Some("HK")
        else if (lower.contains("macau") || s.contains("澳门") || MoAnchor.findFirstIn(s).isDefined) Some("MO")
        else if (lower.contains("taiwan") || s.contains("台湾") || TwAnchor.findFirstIn(s).isDefined) Some("TW")
        else if (lower.contains("singapore") || s.contains("新加坡") || SgAnchor.findFirstIn(lower).isDefined) Some("SG")
        else if (CnAnchor.findFirstIn(s).isDefined || !s.exists(c => c >= 'A' && c <= 'z')) Some("CN")
        else Some("HK") // Mixed Latin + Han (e.g. Diaspora Chinese)
      }
      else if (VnAnchor.findFirstIn(s).isDefined) Some("VN")
      else if (IdAnchor.findFirstIn(s).isDefined) Some("ID")
      else if (UsStateZip.findFirstIn(s).isDefined || lower.contains("united states") || lower.contains("usa")) Some("US")
      else if (UkPostcode.findFirstIn(s.toUpperCase(java.util.Locale.ROOT)).isDefined || lower.contains("united kingdom") || lower.contains("london") || lower.contains(" plc") || lower.contains(" t/a ")) Some("GB")
      else if (DeStreet.findFirstIn(s).isDefined || lower.contains("germany") || lower.contains("deutschland")) Some("DE")
      else if (lower.contains("singapore")) Some("SG")
      else if (lower.contains("india") || lower.contains("mumbai") || lower.contains("bengaluru")) Some("IN")
      else if (lower.contains("australia") || lower.contains("sydney") || lower.contains("melbourne")) Some("AU")
      else if (lower.contains("france") || lower.contains("paris") || lower.contains("sarl") || lower.contains(" sas")) Some("FR")
      else if (lower.contains("spain") || lower.contains("madrid") || lower.contains(" s.l.") || lower.contains(" s.a.")) Some("ES")
      else if (lower.contains("iceland") || lower.endsWith("son") || lower.endsWith("dóttir") || lower.endsWith("dottir")) Some("IS")
      else None
    }
  }

  // ========================================================================
  // Stage 1: RAG Candidate Extraction (Weighted Evidence + Uncapped Scoring)
  // ========================================================================

  private def isPureLatin(s: String): Boolean =
    s.nonEmpty && s.forall { c =>
      (c >= 'A' && c <= 'Z') ||
      (c >= 'a' && c <= 'z') ||
      (c >= '\u00C0' && c <= '\u024F')
    }

  private def shouldSkipPlaceToken(token: String): Boolean = {
    val t = token.trim
    if (t.isEmpty) return true
    val lower = t.toLowerCase(java.util.Locale.ROOT)
    if (GenericPlaceStopwords.contains(lower)) return true
    if (isPureLatin(t) && t.length < 3) return true
    val letters = t.filter(_.isLetter)
    if (letters.isEmpty) return true
    if (isPureLatin(letters) && letters.length < 3) return true
    false
  }

  private def computeGazetteerWeight(ev: PlaceEvidence, isNameLike: Boolean): Double = {
    val base = if (ev.isAdmin1) 4.0 else 2.0
    val popFactor =
      if (ev.population <= 0L) 1.0
      else 1.0 + math.min(1.5, math.max(0.0, math.log10(ev.population.toDouble + 1.0) - 4.0))
    val k           = math.max(1, ev.countryAmbiguity)
    val ambPenalty  = 1.0 / (math.log(1.0 + k.toDouble) / Log2)
    val aliasFactor = if (ev.matchedViaAlias) 0.5 else 1.0
    val nameFactor  = if (isNameLike) 0.3 else 1.0
    base * popFactor * ambPenalty * aliasFactor * nameFactor
  }

  /**
   * Extract country candidates with evidence from deterministic signals +
   * weighted reverse-index gazetteer lookup.
   * Returns Top 5 candidates sorted by raw accumulated score descending (uncapped).
   */
  // Country names (English + endonyms) for the position-weighted mention pass
  // in extractCandidates. Deliberately excluded as too ambiguous: bare "Korea",
  // "Georgia" (US state), bare "America", "Guinea", "Congo", "Mali", "Niger",
  // "Chad" (common first name), and "Island" for Iceland (common noun).
  private val CountryNameTable: List[(String, List[String])] = List(
    "CN" -> List("china", "中国", "中國"),
    "HK" -> List("hong kong", "香港"),
    "MO" -> List("macau", "macao", "澳门", "澳門"),
    "TW" -> List("taiwan", "台湾", "台灣"),
    "SG" -> List("singapore", "新加坡"),
    "JP" -> List("japan", "日本"),
    "KR" -> List("south korea", "韩国", "韓國", "대한민국"),
    "KP" -> List("north korea"),
    "US" -> List("united states of america", "united states", "usa", "u.s.a."),
    "CA" -> List("canada"),
    "MX" -> List("mexico", "méxico"),
    "BR" -> List("brazil", "brasil"),
    "AR" -> List("argentina"),
    "CL" -> List("chile"),
    "CO" -> List("colombia"),
    "PE" -> List("peru", "perú"),
    "VE" -> List("venezuela"),
    "DO" -> List("dominican republic", "república dominicana", "republica dominicana"),
    "CU" -> List("cuba"),
    "PA" -> List("panama", "panamá"),
    "GB" -> List("united kingdom", "great britain", "england", "scotland", "wales"),
    "IE" -> List("ireland", "éire", "eire"),
    "FR" -> List("france"),
    "DE" -> List("germany", "deutschland"),
    "ES" -> List("spain", "españa", "espana"),
    "PT" -> List("portugal"),
    "IT" -> List("italy", "italia"),
    "NL" -> List("netherlands", "holland", "nederland"),
    "BE" -> List("belgium", "belgique", "belgië", "belgie", "belgien"),
    "CH" -> List("switzerland", "schweiz", "suisse", "svizzera"),
    "AT" -> List("austria", "österreich", "osterreich"),
    "SE" -> List("sweden", "sverige"),
    "NO" -> List("norway", "norge"),
    "DK" -> List("denmark", "danmark"),
    "FI" -> List("finland", "suomi"),
    "IS" -> List("iceland", "ísland"),
    "PL" -> List("poland", "polska"),
    "CZ" -> List("czech republic", "czechia", "česko", "cesko"),
    "SK" -> List("slovakia", "slovensko"),
    "HU" -> List("hungary", "magyarország", "magyarorszag"),
    "RO" -> List("romania", "românia"),
    "BG" -> List("bulgaria", "българия"),
    "GR" -> List("greece", "ελλάδα"),
    "HR" -> List("croatia", "hrvatska"),
    "SI" -> List("slovenia", "slovenija"),
    "EE" -> List("estonia", "eesti"),
    "LV" -> List("latvia", "latvija"),
    "LT" -> List("lithuania", "lietuva"),
    "RU" -> List("russia", "россия"),
    "UA" -> List("ukraine", "україна"),
    "BY" -> List("belarus", "беларусь"),
    "KZ" -> List("kazakhstan", "казахстан"),
    "UZ" -> List("uzbekistan"),
    "MN" -> List("mongolia"),
    "TR" -> List("turkey", "türkiye", "turkiye"),
    "IL" -> List("israel"),
    "SA" -> List("saudi arabia", "السعودية"),
    "AE" -> List("united arab emirates", "uae", "الإمارات"),
    "QA" -> List("qatar", "قطر"),
    "KW" -> List("kuwait", "الكويت"),
    "OM" -> List("oman", "عُمان"),
    "BH" -> List("bahrain", "البحرين"),
    "JO" -> List("jordan", "الأردن"),
    "LB" -> List("lebanon", "لبنان"),
    "IQ" -> List("iraq", "العراق"),
    "IR" -> List("iran", "ایران"),
    "EG" -> List("egypt", "مصر"),
    "MA" -> List("morocco", "maroc", "المغرب"),
    "DZ" -> List("algeria", "algérie", "algerie", "الجزائر"),
    "TN" -> List("tunisia", "tunisie", "تونس"),
    "NG" -> List("nigeria"),
    "GH" -> List("ghana"),
    "KE" -> List("kenya"),
    "TZ" -> List("tanzania"),
    "ZA" -> List("south africa"),
    "SN" -> List("senegal", "sénégal"),
    "CM" -> List("cameroon", "cameroun"),
    "CI" -> List("ivory coast", "côte d'ivoire", "cote d'ivoire"),
    "AO" -> List("angola"),
    "MZ" -> List("mozambique", "moçambique", "mocambique"),
    "MG" -> List("madagascar"),
    "ET" -> List("ethiopia"),
    "IN" -> List("india", "भारत"),
    "PK" -> List("pakistan", "پاکستان"),
    "BD" -> List("bangladesh", "বাংলাদেশ"),
    "LK" -> List("sri lanka"),
    "NP" -> List("nepal", "नेपाल"),
    "TH" -> List("thailand", "ประเทศไทย"),
    "VN" -> List("vietnam", "việt nam", "viet nam"),
    "ID" -> List("indonesia"),
    "MY" -> List("malaysia"),
    "PH" -> List("philippines", "pilipinas"),
    "MM" -> List("myanmar", "burma"),
    "KH" -> List("cambodia"),
    "LA" -> List("laos"),
    "AU" -> List("australia"),
    "NZ" -> List("new zealand"),
    "SD" -> List("sudan"),
    "SS" -> List("south sudan"),
    "BJ" -> List("benin", "bénin")
  )

  def extractCandidates(text: String, gazetteer: GeoGazetteer, explicitHint: Option[String] = None): CountryCandidates = {
    val s          = text.trim
    val lower      = s.toLowerCase(java.util.Locale.ROOT)
    val candidates = scala.collection.mutable.Map[String, (Double, List[CountryEvidence])]()

    val wordTokens = s.split("\\s+").filter(_.nonEmpty)
    val isNameLike =
      wordTokens.nonEmpty &&
      wordTokens.length <= 5 &&
      !s.exists(_.isDigit) &&
      !s.contains(',') &&
      !s.contains('，')

    // Account-like strings (bank account numbers): few letters, many digits.
    // In such strings a leading standalone country code is a deliberate country
    // prefix and digit groups are account numbers, never postcodes.
    val letterCount = s.count(_.isLetter)
    val digitCount  = s.count(_.isDigit)
    val accountLike = letterCount <= 8 && digitCount >= 8

    def addEvidence(cc: String, evidence: CountryEvidence): Unit = {
      val (score, evs) = candidates.getOrElse(cc, (0.0, Nil))
      candidates(cc) = (score + evidence.weight, evidence :: evs)
    }

    // 1. Explicit hint
    explicitHint.foreach { h =>
      val cc = h.trim.toUpperCase(java.util.Locale.ROOT)
      if (cc.length == 2) addEvidence(cc, CountryEvidence.ExplicitHint(cc, "user_hint"))
    }

    // 1b. Country-name mentions, weighted by position (reviewer addition, round 9).
    // A country name at the END of the string is the standard trailing address
    // component (strong, 9.0). At the START of a Latin-script string it is usually
    // a brand modifier ("America Coffee", "Japan Store" - weak, 2.0, and dropped
    // when a commercial noun follows); an East-Asian large-to-small address
    // legitimately STARTS with the country, so a beginning mention in CJK/Hangul/
    // Kana text is also strong. In the MIDDLE the name is ambiguous (it may be a
    // foreign state or city - New Mexico, Benin City - or part of a company name),
    // so it stays a weak hint (3.0) and other evidence arbitrates.
    val hasEastAsianText = s.exists(c =>
      (c >= '一' && c <= '鿿') || (c >= '가' && c <= '힯') || (c >= '぀' && c <= 'ヿ'))
    def mentionPosition(matchStart: Int, matchEnd: Int): String = {
      val segments = s.split("[,;\n]").toList
      if (segments.length <= 1) {
        if (matchEnd >= s.length - 2) "end"
        else {
          val frac = if (s.isEmpty) 0.0 else matchStart.toDouble / s.length
          if (frac < 0.34) "beginning" else if (frac > 0.66) "end" else "middle"
        }
      } else {
        var offset = 0
        var idx    = -1
        var i      = 0
        while (i < segments.length && idx < 0) {
          val segEnd = offset + segments(i).length
          if (matchStart >= offset && matchStart <= segEnd + 1) idx = i
          offset = segEnd + 1
          i += 1
        }
        if (idx <= 0) "beginning"
        else if (idx >= segments.length - 1 || matchEnd >= s.length - 2) "end"
        else "middle"
      }
    }
    val brandNouns = Set("coffee", "store", "shop", "mart", "restaurant", "cafe", "café",
      "bar", "grill", "kitchen", "bakery", "sushi", "ramen", "hotel", "inn", "motel",
      "salon", "studio", "gym", "pharmacy", "foods", "food", "market", "trading",
      "imports", "exports", "travel", "tours", "airlines", "airways")
    val placeNouns = Set("square", "street", "st", "road", "rd", "avenue", "ave",
      "boulevard", "blvd", "lane", "ln", "drive", "dr", "place", "pl", "park",
      "wharf", "tower", "towers", "building", "bldg", "bridge", "station",
      "airport", "harbor", "harbour", "bay", "beach", "island", "islands",
      "city", "town", "village", "creek", "river", "lake", "mount", "fort")
    val bestMention = scala.collection.mutable.Map[String, (String, String, Double)]()
    CountryNameTable.foreach { case (cc, names) =>
      names.foreach { name =>
        val suppressed =
          (cc == "MX" && (name == "mexico" || name == "méxico") && lower.contains("new mexico")) ||
          (cc == "GB" && name == "wales" && lower.contains("new south wales")) ||
          (cc == "BJ" && (name == "benin" || name == "bénin") && lower.contains("benin city"))
        if (!suppressed) {
          val isNonLatin = name.exists(_ > 'ɏ')
          val mOpt: Option[(Int, Int)] =
            if (isNonLatin) {
              val idx = s.indexOf(name)
              if (idx >= 0) Some((idx, idx + name.length)) else None
            } else {
              val pat = ("(?i)(?<![A-Za-zÀ-ÿ])" + java.util.regex.Pattern.quote(name) + "(?![A-Za-zÀ-ÿ])").r
              pat.findFirstMatchIn(s).map(m => (m.start, m.end))
            }
          mOpt.foreach { case (ms, me) =>
            val pos = mentionPosition(ms, me)
            val nextTok = s.substring(me).trim.toLowerCase(java.util.Locale.ROOT)
              .split("[^\\p{L}]+").filter(_.nonEmpty).headOption.getOrElse("")
            // A country name immediately followed by a place-type noun is a
            // place name, not a country mention ("Canada Square, London").
            val placeLike = placeNouns.contains(nextTok)
            // Followed by a commercial noun it is a brand fragment
            // ("America Coffee", "Japan Store") - except at the very end.
            val brandLike = pos != "end" && !isNonLatin && brandNouns.contains(nextTok)
            if (!placeLike && !brandLike) {
              val w = pos match {
                case "end"       => 9.0
                case "beginning" => if (hasEastAsianText) 9.0 else 2.0
                case _           => 3.0
              }
              bestMention.get(cc) match {
                case Some((_, _, best)) if best >= w => ()
                case _                               => bestMention(cc) = (name, pos, w)
              }
            }
          }
        }
      }
    }
    bestMention.foreach { case (cc, (name, pos, w)) =>
      addEvidence(cc, CountryEvidence.CountryNameMention(cc, name, pos, Some(w)))
    }

    // 2. Hard identifiers (IBAN, BIC, phone, ccTLD)
    // Spans are kept so weaker signals (e.g. postcode formats) are not extracted from inside an IBAN.
    val ibanMatches = IbanPattern.findAllMatchIn(s).toList
    ibanMatches.foreach { m =>
      val cc = m.group(1).toUpperCase(java.util.Locale.ROOT)
      addEvidence(cc, CountryEvidence.HardIdentifier(cc, "IBAN", m.group(0)))
    }
    val ibanSpans = ibanMatches.map(m => (m.start, m.end))

    // BIC country code (positions 5-6)
    BicPattern.findAllMatchIn(s).foreach { m =>
      val bic = m.group(0).toUpperCase(java.util.Locale.ROOT)
      if (bic.length >= 6) {
        val cc = bic.substring(4, 6)
        addEvidence(cc, CountryEvidence.HardIdentifier(cc, "BIC", bic))
      }
    }

    // Phone country code
    PhonePattern.findAllMatchIn(s).foreach { m =>
      val code = m.group(1)
      PhoneCountryCodes.get(code).foreach { cc =>
        addEvidence(cc, CountryEvidence.PhoneCountryCode(cc, s"+$code"))
      }
    }

    // ccTLD
    CctldPattern.findAllMatchIn(lower).foreach { m =>
      val tld = m.group(1)
      CctldToCountry.get(tld).foreach { cc =>
        addEvidence(cc, CountryEvidence.CcTLD(cc, tld))
      }
    }

    // 3. Script/Language detection
    if (HasHiragana.matches(s) || HasKatakana.matches(s)) {
      addEvidence("JP", CountryEvidence.ScriptLanguage("kana", Some("Japanese"), "JP"))
    }
    if (HasHangul.matches(s)) {
      addEvidence("KR", CountryEvidence.ScriptLanguage("hangul", Some("Korean"), "KR"))
    }
    if (HasCyrillic.matches(s)) {
      List("RU", "UA", "BY", "KZ").foreach { cc =>
        addEvidence(cc, CountryEvidence.ScriptLanguage("cyrillic", Some("Russian"), cc))
      }
    }
    if (HasArabic.matches(s)) {
      List("SA", "AE", "EG", "MA", "KW", "QA").foreach { cc =>
        addEvidence(cc, CountryEvidence.ScriptLanguage("arabic", Some("Arabic"), cc))
      }
    }
    if (HasDevanagari.matches(s)) {
      addEvidence("IN", CountryEvidence.ScriptLanguage("devanagari", Some("Hindi"), "IN"))
      addEvidence("NP", CountryEvidence.ScriptLanguage("devanagari", Some("Nepali"), "NP"))
    }
    if (HasThai.matches(s)) {
      addEvidence("TH", CountryEvidence.ScriptLanguage("thai", Some("Thai"), "TH"))
    }
    if (HasHan.matches(s)) {
      val hasHkMoTw = HkAnchor.findFirstIn(s).isDefined || MoAnchor.findFirstIn(s).isDefined || TwAnchor.findFirstIn(s).isDefined
      val hasSimplifiedMarkers = CnAnchor.findFirstIn(s).isDefined || lower.contains("china") || lower.contains("beijing") || lower.contains("shanghai")
      if (hasHkMoTw) {
        if (HkAnchor.findFirstIn(s).isDefined || lower.contains("hong kong")) addEvidence("HK", CountryEvidence.ScriptLanguage("han_traditional", Some("Cantonese"), "HK"))
        if (MoAnchor.findFirstIn(s).isDefined || lower.contains("macau")) addEvidence("MO", CountryEvidence.ScriptLanguage("han_traditional", Some("Cantonese"), "MO"))
        if (TwAnchor.findFirstIn(s).isDefined || lower.contains("taiwan")) addEvidence("TW", CountryEvidence.ScriptLanguage("han_traditional", Some("Mandarin"), "TW"))
      } else if (hasSimplifiedMarkers || !s.exists(c => c >= 'A' && c <= 'z')) {
        addEvidence("CN", CountryEvidence.ScriptLanguage("han_simplified", Some("Mandarin"), "CN"))
        addEvidence("SG", CountryEvidence.ScriptLanguage("han_simplified", Some("Mandarin"), "SG"))
      } else {
        addEvidence("HK", CountryEvidence.ScriptLanguage("han_traditional", Some("Cantonese/English"), "HK"))
        addEvidence("SG", CountryEvidence.ScriptLanguage("han_simplified", Some("Mandarin/English"), "SG"))
        addEvidence("TW", CountryEvidence.ScriptLanguage("han_traditional", Some("Mandarin"), "TW"))
      }
    }

    // 3b. Account-like strings: leading country-code prefix + bank brands.
    if (accountLike) {
      val tokens = s.toUpperCase(java.util.Locale.ROOT).split("[\\s\\-/]+").filter(_.nonEmpty)
      val prefixCc = tokens.headOption.filter(t => t.length == 2 && CountryNameTable.exists(_._1 == t))
      prefixCc.foreach { cc =>
        addEvidence(cc, CountryEvidence.ExplicitHint(cc, "account_prefix"))
      }
      if (!prefixCc.contains("SG") && tokens.exists(t => t == "DBS" || t == "OCBC" || t == "UOB")) {
        addEvidence("SG", CountryEvidence.ExplicitHint("SG", "bank_brand"))
      }
    }

    // 4. Postcode format — skip matches inside an IBAN span (see step 2).
    // Count at most once per country, keeping the single longest match.
    // Suppressed entirely for account-like strings (digit groups there are
    // account numbers, not postcodes).
    if (!accountLike) postcodePatterns.foreach { case (cc, regex) =>
      val nonIbanMatches = regex.findAllMatchIn(s).filterNot { m =>
        ibanSpans.exists { case (start, end) => m.start < end && start < m.end }
      }.map(_.group(0)).toList
      if (nonIbanMatches.nonEmpty) {
        val longest = nonIbanMatches.maxBy(_.length)
        addEvidence(cc, CountryEvidence.PostcodeFormat(cc, longest))
      }
    }

    // 5. Legal form dictionary hits
    LegalFormPatterns.foreach { case (cc, patterns) =>
      patterns.foreach { case (pattern, position) =>
        pattern.r.findFirstIn(s).foreach { matched =>
          addEvidence(cc, CountryEvidence.LegalFormHit(cc, matched, position))
        }
      }
    }

    // 6. Surname dictionary hits
    SurnamePatterns.foreach { case (cc, surnames) =>
      surnames.foreach { case (surname, culture) =>
        val pattern = if (culture == "chinese" || culture == "japanese" || culture == "korean") {
          s"$surname"
        } else {
          s"\\b$surname\\b"
        }
        pattern.r.findFirstIn(s).foreach { matched =>
          addEvidence(cc, CountryEvidence.SurnameHit(cc, matched, culture))
        }
      }
    }

    // 7. Geographic anchor patterns (dedicated GeoAnchor evidence, weight 3.0)
    val geoAnchors: List[(String, String, Double)] = List(
      ("VN", "VN", if (VnAnchor.findFirstIn(s).isDefined) 0.5 else 0.0),
      ("ID", "ID", if (IdAnchor.findFirstIn(s).isDefined) 0.5 else 0.0),
      ("US", "US", if (UsStateZip.findFirstIn(s).isDefined || lower.contains("united states") || lower.contains("usa")) 0.6 else 0.0),
      ("GB", "GB", if (UkPostcode.findFirstIn(s.toUpperCase(java.util.Locale.ROOT)).isDefined || lower.contains("united kingdom") || lower.contains("london")) 0.6 else 0.0),
      ("DE", "DE", if (DeStreet.findFirstIn(s).isDefined || lower.contains("germany") || lower.contains("deutschland")) 0.5 else 0.0),
      ("SG", "SG", if (SgAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("MY", "MY", if (MyAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("PH", "PH", if (PhAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("TH", "TH", if (ThAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("AE", "AE", if (AeAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("SA", "SA", if (SaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("QA", "QA", if (QaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("KW", "KW", if (KwAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("EG", "EG", if (EgAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("MA", "MA", if (MaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("NG", "NG", if (NgAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("KE", "KE", if (KeAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("ZA", "ZA", if (ZaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("BR", "BR", if (BrAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("AR", "AR", if (ArAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("CL", "CL", if (ClAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("CO", "CO", if (CoAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("PE", "PE", if (PeAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("MX", "MX", if (MxAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("CA", "CA", if (CaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("AU", "AU", if (AuAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("NZ", "NZ", if (NzAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("FR", "FR", if (FrAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("ES", "ES", if (EsAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("PT", "PT", if (PtAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("IT", "IT", if (ItAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("NL", "NL", if (NlAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("BE", "BE", if (BeAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("CH", "CH", if (ChAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("AT", "AT", if (AtAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("SE", "SE", if (SeAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("NO", "NO", if (NoAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("DK", "DK", if (DkAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("FI", "FI", if (FiAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("PL", "PL", if (PlAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("CZ", "CZ", if (CzAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("HU", "HU", if (HuAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("RO", "RO", if (RoAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("TR", "TR", if (TrAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("RU", "RU", if (RuAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("UA", "UA", if (UaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("BY", "BY", if (ByAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("KZ", "KZ", if (KzAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("IL", "IL", if (IlAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("IR", "IR", if (IrAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("PK", "PK", if (PkAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("BD", "BD", if (BdAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("LK", "LK", if (LkAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("MM", "MM", if (MmAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("KH", "KH", if (KhAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("LA", "LA", if (LaAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("MN", "MN", if (MnAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("NP", "NP", if (NpAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("AF", "AF", if (AfAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("IS", "IS", if (IsAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0),
      ("IE", "IE", if (IeAnchor.findFirstIn(lower).isDefined) 0.5 else 0.0)
    )
    geoAnchors.foreach { case (cc, _, weight) =>
      if (weight > 0) addEvidence(cc, CountryEvidence.GeoAnchor(cc, s"geo_anchor_$cc"))
    }

    // 7b. Tier-2 in-language-group discriminators (weight 3.5, at most once per country)
    SimpleDiscriminatorPatterns.foreach { case (cc, regex) =>
      regex.findFirstIn(s).foreach { matched =>
        addEvidence(cc, CountryEvidence.DiscriminatorHit(cc, matched))
      }
    }
    val auDiscriminatorMatch: Option[String] =
      AuSuburbPattern.findFirstIn(s).orElse {
        if (FourDigitNumberPattern.findFirstIn(s).isDefined) AuStateAbbrevPattern.findFirstIn(s)
        else None
      }
    auDiscriminatorMatch.foreach { matched =>
      addEvidence("AU", CountryEvidence.DiscriminatorHit("AU", matched))
    }

    // 8. Weighted gazetteer reverse lookup with stopword filtering + city/admin1 co-occurrence
    val allPlaceHits = collectPlaceEvidence(s, gazetteer)

    // Deduplicate scoring hits by (matchedKey, countryCode, isAdmin1) first, then by
    // (countryCode, canonicalName) across both city and admin1 so a city and its same-named
    // province/state don't double-count (keeping only the higher computed weight).
    val bestByPlace = scala.collection.mutable.LinkedHashMap.empty[(String, String), (PlaceEvidence, Double)]
    val bestByKey   = scala.collection.mutable.HashMap.empty[(String, String, Boolean), (PlaceEvidence, Double)]

    allPlaceHits.foreach { ev =>
      val w        = computeGazetteerWeight(ev, isNameLike)
      val cc       = ev.city.countryCode
      val keyTuple = (ev.matchedKey, cc, ev.isAdmin1)
      bestByKey.get(keyTuple) match {
        case Some((_, prevW)) if prevW >= w => ()
        case _                              => bestByKey.put(keyTuple, (ev, w))
      }
    }

    bestByKey.values.foreach { case (ev, w) =>
      val cc         = ev.city.countryCode
      val placeTuple = (cc, ev.city.canonicalName.toLowerCase(java.util.Locale.ROOT))
      bestByPlace.get(placeTuple) match {
        case Some((_, prevW)) if prevW >= w => ()
        case _                              => bestByPlace.put(placeTuple, (ev, w))
      }
    }

    bestByPlace.values.foreach { case (ev, w) =>
      val cc        = ev.city.countryCode
      val kind      = if (ev.isAdmin1) "admin1" else "city"
      val adminPath = s"$kind:${ev.city.admin1Code}/${ev.city.canonicalName}"
      addEvidence(
        cc,
        CountryEvidence.GazetteerHit(
          countryCode = cc,
          placeName = ev.city.canonicalName,
          adminPath = adminPath,
          matchedKey = ev.matchedKey,
          weightOverride = Some(w)
        )
      )
    }

    // Co-occurrence bonus: if a city hit and its matching (countryCode, admin1Code) admin1 hit
    // both appear in the same text (from distinct matchedKeys), award +3.0 once per country
    // (scaled by 0.3 for bare person-name-like inputs).
    val admin1HitsByKey = allPlaceHits
      .filter(ev => ev.isAdmin1 && ev.city.admin1Code.nonEmpty)
      .groupBy(ev => (ev.city.countryCode, ev.city.admin1Code))

    val coOccurrenceWeight  = if (isNameLike) 3.0 * 0.3 else 3.0
    val coOccurrenceAwarded = scala.collection.mutable.HashSet.empty[String]
    allPlaceHits.foreach { cityEv =>
      val cc = cityEv.city.countryCode
      if (!cityEv.isAdmin1 && cityEv.city.admin1Code.nonEmpty && !coOccurrenceAwarded.contains(cc)) {
        admin1HitsByKey.get((cc, cityEv.city.admin1Code)).foreach { adminHits =>
          adminHits.find(_.matchedKey != cityEv.matchedKey).foreach { adminEv =>
            coOccurrenceAwarded += cc
            addEvidence(
              cc,
              CountryEvidence.GazetteerHit(
                countryCode = cc,
                placeName = cityEv.city.canonicalName,
                adminPath = s"co-occurrence: ${cityEv.city.admin1Code}/${cityEv.city.canonicalName} + ${adminEv.city.canonicalName}",
                matchedKey = s"${cityEv.matchedKey}+${adminEv.matchedKey}",
                weightOverride = Some(coOccurrenceWeight)
              )
            )
          }
        }
      }
    }

    // Keep raw accumulated scores (no maxScore normalization), sort descending, take Top 5.
    val ranked = candidates.map { case (cc, (score, evs)) =>
      CountryCandidate(cc, score, evs.reverse)
    }.toList.sortBy(-_.score).take(5)

    val hasHardId     = ranked.exists(_.hasHardIdentifier)
    val hardIdCountry = ranked.find(_.hasHardIdentifier).flatMap(_.hardIdentifierCountry)

    CountryCandidates(ranked, s, hasHardId, hardIdCountry)
  }

  private val postcodePatterns: Map[String, scala.util.matching.Regex] = Map(
    "US" -> """\b\d{5}(-\d{4})?\b""".r,
    "CN" -> """\b\d{6}\b""".r,
    "DE" -> """\b\d{5}\b""".r,
    "GB" -> """\b[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}\b""".r,
    "JP" -> """\b\d{3}-?\d{4}\b""".r,
    "ID" -> """\b\d{5}\b""".r,
    "SG" -> """\b\d{6}\b""".r,
    "IN" -> """\b\d{6}\b""".r,
    "AU" -> """\b\d{4}\b""".r,
    "FR" -> """\b\d{5}\b""".r,
    "RU" -> """\b\d{6}\b""".r,
    "CA" -> """\b[A-Z]\d[A-Z]\s?\d[A-Z]\d\b""".r,
    "IT" -> """\b\d{5}\b""".r,
    "ES" -> """\b\d{5}\b""".r,
    "KR" -> """\b\d{5}\b""".r,
    "TW" -> """\b(?:\d{5}|\d{3}[-\s]\d{2})\b""".r,
    "HK" -> """\b999077\b""".r,
    "MY" -> """\b\d{5}\b""".r,
    "TH" -> """\b\d{5}\b""".r,
    "VN" -> """\b\d{5,6}\b""".r,
    "PH" -> """\b\d{4}\b""".r,
    "AE" -> """\b\d{5}\b""".r,
    "ZA" -> """\b\d{4}\b""".r,
    "BR" -> """\b\d{5}-?\d{3}\b""".r,
    "MX" -> """\b\d{5}\b""".r,
    "AR" -> """\b[A-Z]\d{4}[A-Z]{3}\b""".r,
    "CL" -> """\b\d{7}\b""".r
  )

  private val SegmentSplitRegex = """[,;/\n|()（）【】]+""".r
  private val CjkAdminRegex     = """([^\p{P}\s\d省市区區县縣州都道府県]{1,5})(特别行政区|特別行政區|自治区|自治區|특별자치도|특별시|광역시|省|市|区|區|县|縣|州|都|道|府|県|도|군|구)""".r

  private def hasCjk(s: String): Boolean =
    s.exists { c =>
      val b = Character.UnicodeBlock.of(c)
      b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
      b == Character.UnicodeBlock.HIRAGANA ||
      b == Character.UnicodeBlock.KATAKANA ||
      b == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

  /**
   * Extract place tokens from text across scripts, apply stopword & length filtering,
   * and retrieve matching PlaceEvidence from the global gazetteer.
   * Uses greedy longest-match (3 -> 2 -> 1 word n-grams) within each punctuation-delimited
   * segment so multi-word place names (e.g. "San Francisco", "Dar es Salaam") don't emit
   * noisy sub-token matches.
   */
  private def collectPlaceEvidence(text: String, gazetteer: GeoGazetteer): List[PlaceEvidence] = {
    val results     = scala.collection.mutable.ListBuffer.empty[PlaceEvidence]
    val queriedKeys = scala.collection.mutable.HashSet.empty[String]

    def queryToken(raw: String): List[PlaceEvidence] = {
      val trimmed = raw.trim
      if (shouldSkipPlaceToken(trimmed)) return Nil
      val norm = EntityNormalizer.keyToken(trimmed, germanUmlautExpand = false)
      if (norm.isEmpty || queriedKeys.contains(norm)) return Nil
      val hits = gazetteer.lookupPlace(trimmed)
      if (hits.nonEmpty) queriedKeys += norm
      hits
    }

    val segments = SegmentSplitRegex.split(text).map(_.trim).filter(_.nonEmpty)

    segments.foreach { seg =>
      // 1. CJK administrative suffix & prefix extraction
      if (hasCjk(seg)) {
        CjkAdminRegex.findAllMatchIn(seg).foreach { m =>
          val stem   = m.group(1).trim
          val suffix = m.group(2).trim
          val full   = stem + suffix
          val fullHits = queryToken(full)
          results ++= fullHits
          if (stem.length >= 2) {
            val stemHits = queryToken(stem)
            results ++= stemHits
          }
        }
        val cjkRun = seg.filter(c => Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_SYLLABLES)
        if (cjkRun.length >= 2 && cjkRun.length <= 6) {
          results ++= queryToken(cjkRun)
        } else if (cjkRun.length > 6) {
          results ++= queryToken(cjkRun.take(2))
        }
      }

      // 2. Word-level greedy longest-match (3-gram -> 2-gram -> 1-gram)
      val rawWords = seg.split("\\s+").map(_.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}.]+$", "")).filter(_.nonEmpty)
      var i = 0
      while (i < rawWords.length) {
        var matchedLen = 0
        val maxWin     = math.min(3, rawWords.length - i)
        var win        = maxWin
        while (win >= 1 && matchedLen == 0) {
          val phrase = rawWords.slice(i, i + win).mkString(" ")
          val hits   = queryToken(phrase)
          if (hits.nonEmpty) {
            results ++= hits
            matchedLen = win
          }
          win -= 1
        }
        i += (if (matchedLen > 0) matchedLen else 1)
      }
    }

    results.toList
  }
}
