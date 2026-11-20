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
 * + reverse-index gazetteer evidence → CountryCandidates (Top 3-5 with scores and evidence).
 */
object CountryDetector {

  private val HasHan      = ".*\\p{InCJK_Unified_Ideographs}.*".r
  private val HasHiragana = ".*\\p{InHiragana}.*".r
  private val HasKatakana = ".*\\p{InKatakana}.*".r
  private val HasHangul   = ".*\\p{InHangul_Syllables}.*".r
  private val HasCyrillic = ".*\\p{InCyrillic}.*".r
  private val HasArabic   = ".*\\p{InArabic}.*".r
  private val HasDevanagari = ".*\\p{InDevanagari}.*".r
  private val HasThai     = ".*\\p{InThai}.*".r

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

  // --- Hard identifier patterns ---
  // Matches both compact IBANs (DE89370400440532013000) and the standard
  // printed spaced form in groups of 4 (DE89 3704 0044 0532 0130 00).
  // Previously the spaced form never matched, so the IBAN country code
  // (a hard identifier) was missed for every spaced IBAN.
  private val IbanPattern = """\b([A-Z]{2})\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\b""".r
  private val BicPattern  = """\b[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?\b""".r
  private val PhonePattern = """\+(\d{1,3})\s*\d""".r
  private val CctldPattern = """\b[a-z0-9-]+\.([a-z]{2})\b""".r

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
    "hiragana" -> List("JP"),
    "katakana" -> List("JP"),
    "hangul"   -> List("KR"),
    "cyrillic" -> List("RU", "UA", "BY", "KZ", "BG", "RS", "MK"),
    "arabic"   -> List("SA", "AE", "EG", "MA", "DZ", "TN", "LY", "IQ", "SY", "JO", "LB", "PS", "KW", "QA", "BH", "OM", "YE"),
    "devanagari" -> List("IN", "NP"),
    "thai"     -> List("TH"),
    "han_simplified" -> List("CN", "SG", "MY"),
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
  // Stage 1: RAG Candidate Extraction
  // ========================================================================

  /**
   * Extract country candidates with evidence from deterministic signals +
   * reverse-index gazetteer lookup.
   * Returns Top 3-5 candidates with normalized scores and evidence.
   */
  def extractCandidates(text: String, gazetteer: GeoGazetteer, explicitHint: Option[String] = None): CountryCandidates = {
    val s = text.trim
    val lower = s.toLowerCase(java.util.Locale.ROOT)
    val candidates = scala.collection.mutable.Map[String, (Double, List[CountryEvidence])]()

    def addEvidence(cc: String, evidence: CountryEvidence): Unit = {
      val (score, evs) = candidates.getOrElse(cc, (0.0, Nil))
      candidates(cc) = (score + evidence.weight, evidence :: evs)
    }

    // 1. Explicit hint (highest weight among non-hard signals)
    explicitHint.foreach { h =>
      val cc = h.trim.toUpperCase(java.util.Locale.ROOT)
      if (cc.length == 2) addEvidence(cc, CountryEvidence.ExplicitHint(cc, "user_hint"))
    }

    // 2. Hard identifiers (IBAN, BIC, phone, ccTLD) - weight 1.0 (but handled specially)
    // IBAN country code. Spans are kept so later, weaker signals (e.g. postcode
    // formats) are not extracted from inside an IBAN: a spaced IBAN's digit
    // groups would otherwise each match bare 4-digit postcode patterns and
    // outvote the hard identifier.
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
      // Distinguish simplified vs traditional by checking for HK/MO/TW markers
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
        // Mixed Latin + Han - likely diaspora
        addEvidence("HK", CountryEvidence.ScriptLanguage("han_traditional", Some("Cantonese/English"), "HK"))
        addEvidence("SG", CountryEvidence.ScriptLanguage("han_simplified", Some("Mandarin/English"), "SG"))
        addEvidence("TW", CountryEvidence.ScriptLanguage("han_traditional", Some("Mandarin"), "TW"))
      }
    }

    // 4. Postcode format — skip matches inside an IBAN span (see step 2).
    postcodePatterns.foreach { case (cc, regex) =>
      regex.findAllMatchIn(s).filterNot { m =>
        ibanSpans.exists { case (start, end) => m.start < end && start < m.end }
      }.foreach { m =>
        addEvidence(cc, CountryEvidence.PostcodeFormat(cc, m.group(0)))
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
        // Match surname as whole word or at start of Chinese name
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

    // 7. Geographic anchor patterns (existing detectCountry logic converted to evidence)
    // These are lower-weight contextual signals
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
      if (weight > 0) addEvidence(cc, CountryEvidence.ExplicitHint(cc, s"geo_anchor_$cc"))
    }

    // 8. Gazetteer reverse lookup (place name -> candidate countries)
    // Extract potential place name tokens from text
    val tokens = extractPlaceTokens(s)
    tokens.foreach { token =>
      val matches = gazetteer.lookupPlace(token)
      matches.foreach { evidence =>
        val cc = evidence.city.countryCode
        val adminPath = s"${evidence.city.admin1Code}/${evidence.city.canonicalName}"
        addEvidence(cc, CountryEvidence.GazetteerHit(cc, evidence.city.canonicalName, adminPath, evidence.matchedKey))
      }
    }

    // Normalize scores to 0-1 range and sort
    val maxScore = candidates.values.map(_._1).maxOption.getOrElse(1.0)
    val normalized = candidates.map { case (cc, (score, evs)) =>
      CountryCandidate(cc, if (maxScore > 0) score / maxScore else 0.0, evs.reverse)
    }.toList.sortBy(-_.score).take(5)

    val hasHardId = normalized.exists(_.hasHardIdentifier)
    val hardIdCountry = normalized.find(_.hasHardIdentifier).flatMap(_.hardIdentifierCountry)

    CountryCandidates(normalized, s, hasHardId, hardIdCountry)
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
    "TW" -> """\b\d{3,5}\b""".r,
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

  /**
   * Extract potential place name tokens from text for gazetteer lookup.
   * Uses simple heuristics: capitalized words, known admin suffixes, etc.
   */
  private def extractPlaceTokens(text: String): List[String] = {
    val tokens = scala.collection.mutable.Set[String]()

    // Chinese administrative divisions
    "(省|市|区|县|州|自治区|特别行政区)".r.findAllMatchIn(text).foreach { m =>
      val start = math.max(0, m.start - 6)
      val end = m.end
      tokens += text.substring(start, end).trim
    }

    // Japanese prefectures
    "(都|道|府|県)".r.findAllMatchIn(text).foreach { m =>
      val start = math.max(0, m.start - 4)
      val end = m.end
      tokens += text.substring(start, end).trim
    }

    // Korean provinces
    "(특별시|광역시|도|시|군|구)".r.findAllMatchIn(text).foreach { m =>
      val start = math.max(0, m.start - 4)
      val end = m.end
      tokens += text.substring(start, end).trim
    }

    // Latin script: Capitalized words that could be place names
    // Look for patterns like "City, State" or "Street, City"
    "([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\s*,\\s*([A-Z][a-z]+)".r.findAllMatchIn(text).foreach { m =>
      tokens += m.group(1).trim
      tokens += m.group(2).trim
    }
    "([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\s+[A-Z]{2}\\s+\\d{5}".r.findAllMatchIn(text).foreach { m =>
      tokens += m.group(1).trim
    }

    // Common city names in Latin script (from gazetteer coverage)
    val knownCities = Set(
      "Beijing", "Shanghai", "Guangzhou", "Shenzhen", "Hangzhou", "Nanjing", "Suzhou", "Chengdu",
      "San Francisco", "Los Angeles", "New York", "Mountain View", "Seattle", "Chicago",
      "Arlington", "Alexandria", "Baltimore", "Atlanta", "Miami", "Charlotte", "Nashville",
      "Denver", "Phoenix", "Boston", "Newark", "Philadelphia", "Columbus", "Detroit",
      "Indianapolis", "Kansas City", "Milwaukee", "Minneapolis",
      "Berlin", "Munich", "Muenchen", "Cologne", "Koeln", "Hamburg",
      "Tokyo", "Shibuya", "Osaka",
      "London", "Manchester", "Birmingham", "York",
      "Paris", "Lyon", "Marseille",
      "Madrid", "Barcelona",
      "Rome", "Milan", "Milano",
      "Amsterdam", "Rotterdam",
      "Brussels", "Antwerp",
      "Zurich", "Geneva",
      "Vienna", "Wien",
      "Stockholm", "Oslo", "Copenhagen", "Helsinki",
      "Warsaw", "Prague", "Budapest", "Bucharest",
      "Istanbul", "Ankara",
      "Moscow", "Kyiv", "Minsk", "Almaty",
      "Tel Aviv", "Jerusalem",
      "Dubai", "Abu Dhabi", "Riyadh", "Jeddah", "Doha", "Kuwait City",
      "Cairo", "Casablanca", "Rabat",
      "Lagos", "Abuja", "Nairobi",
      "Johannesburg", "Cape Town",
      "Sao Paulo", "Rio de Janeiro",
      "Buenos Aires", "Santiago", "Bogota", "Lima", "Mexico City",
      "Toronto", "Vancouver", "Montreal",
      "Sydney", "Melbourne", "Brisbane",
      "Auckland", "Wellington",
      "Singapore", "Kuala Lumpur", "Jakarta", "Bandung", "Bangkok", "Ho Chi Minh", "Hanoi",
      "Manila", "Makati", "Seoul", "Busan", "Taipei", "Hong Kong", "Macau"
    )
    knownCities.foreach { city =>
      if (text.toLowerCase(java.util.Locale.ROOT).contains(city.toLowerCase(java.util.Locale.ROOT))) {
        tokens += city
      }
    }

    tokens.toList
  }
}