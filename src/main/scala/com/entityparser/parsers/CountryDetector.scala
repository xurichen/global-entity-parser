package com.entityparser.parsers

/**
 * Local-Conventions-First region/country detector (§2).
 * Inspects Unicode scripts, legal-form prefixes/suffixes, postal formats,
 * and geographic anchors before dispatching to country-specific parsers.
 */
object CountryDetector {

  private val HasHan      = ".*\\p{InCJK_Unified_Ideographs}.*".r
  private val HasHiragana = ".*\\p{InHiragana}.*".r
  private val HasKatakana = ".*\\p{InKatakana}.*".r
  private val HasHangul   = ".*\\p{InHangul_Syllables}.*".r
  private val HasCyrillic = ".*\\p{InCyrillic}.*".r
  private val HasArabic   = ".*\\p{InArabic}.*".r

  private val UsStateZip  = """\b(AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)\s+\d{5}(-\d{4})?\b""".r
  private val UkPostcode  = """\b[A-Z]{1,2}\d[A-Z\d]?\s+\d[A-Z]{2}\b""".r
  private val DeStreet    = """(?i)(straße|strasse|str\.|platz|allee|gmbh|e\.k\.|\bag\b|\bkg\b|\bmünchen\b|\bberlin\b|\bhamburg\b|\bköln\b)""".r
  private val IdAnchor    = """(?i)(\bpt\.?\s+|\bcv\.?\s+|\bud\.?\s+|\btbk\b|\bjakarta\b|\bbandung\b|\bjl\.\s*|\bjalan\s+)""".r
  private val VnAnchor    = """(?i)(công ty|tnhh|cổ phần|việt nam|hà nội|hồ chí minh)""".r
  private val JpAnchor    = """(株式会社|有限会社|合同会社|東京都|大阪府|渋谷区|〒\d{3}-\d{4})""".r
  private val CnAnchor    = """(有限公司|有限责任公司|股份有限公司|个体工商户|省|市|区|县|路|街|号)""".r

  def detectCountry(text: String, explicitHint: Option[String] = None): Option[String] = {
    explicitHint.map(_.trim.toUpperCase(java.util.Locale.ROOT)).filter(_.length == 2).orElse {
      val s     = text.trim
      val lower = s.toLowerCase(java.util.Locale.ROOT)

      if (HasHiragana.matches(s) || HasKatakana.matches(s) || JpAnchor.findFirstIn(s).isDefined) Some("JP")
      else if (HasHangul.matches(s)) Some("KR")
      else if (HasCyrillic.matches(s)) Some("RU")
      else if (HasArabic.matches(s)) Some("SA")
      else if (HasHan.matches(s)) {
        if (lower.contains("hong kong") || s.contains("香港")) Some("HK")
        else if (lower.contains("singapore") || s.contains("新加坡")) Some("SG")
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
}
