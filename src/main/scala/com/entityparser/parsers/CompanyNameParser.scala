package com.entityparser.parsers

import com.entityparser.llm._
import com.entityparser.model._
import com.entityparser.norm.EntityNormalizer

/**
 * 3.4 Country-Aware Company Name Parser.
 *
 * Separates legal-form designators (respecting prefix/suffix/either position rules)
 * and listing markers (e.g. Tbk, plc, Bhd) from the normalized core company name.
 */
final class CompanyNameParser(
  llm: LlmBackend = NoOpLlmBackend,
  fallbackThreshold: Double = 0.72
) {

  final case class LegalFormRule(
    country: String,
    pattern: String,          // Case-insensitive regex matching the raw designator
    canonical: String,
    position: LegalFormPosition
  )

  final case class ListingMarkerRule(
    country: String,
    pattern: String,
    canonical: String
  )

  /** Separate listing markers (§3.4): Tbk (ID) - distinct from legal form. */
  private val ListingMarkers: List[ListingMarkerRule] = List(
    ListingMarkerRule("ID", """(?i)(?:,?\s+|\s+)Tbk\.?$""", "Tbk"),
    ListingMarkerRule("ID", """(?i)(?:,?\s+|\s+)Tbk\.?$""", "Tbk")
    // Note: plc (GB) and Bhd (MY) are legal forms, not separate listing markers
    // Note: (Persero) is part of the company name (state-owned marker), not a listing marker
  )

  /** Department/prefix noise to strip before parsing (e.g. "Finance Dept:", "Accounts Payable") */
  private val DepartmentPrefixes: List[String] = List(
    """(?i)^(?:Finance\s+Dept\.?|Accounts?\s+Payable|Dept\.?|Department)\s*[:-]?\s*""",
    """(?i)^(?:Attn\.?|Attention|C/O|Care\s+Of)\s*[:-]?\s*"""
  )

  /** Trailing parenthetical remarks to strip (e.g. "(Accounts Payable)", "(Head Office)")
   * But preserve (Persero) for ID companies as it's a state-owned marker */
  private val TrailingParentheticals: String = """(?i)\s*(?!\(persero\))(?!\(PERSERO\))\([^)]*\)\s*$"""

  /** Per-country legal-form table ordered longest-match first (§2 & §3.4). */
  private val LegalForms: List[LegalFormRule] = List(
    // Indonesia (Prefix!)
    LegalFormRule("ID", """(?i)^(?:Perseroan Terbatas|P\.?T\.?)\s+""", "PT", LegalFormPosition.Prefix),
    LegalFormRule("ID", """(?i)^(?:Perseroan Terbatas|PT)\s+""", "PT", LegalFormPosition.Prefix),
    LegalFormRule("ID", """(?i)^(?:Commanditaire Vennootschap|C\.?V\.?)\s+""", "CV", LegalFormPosition.Prefix),
    LegalFormRule("ID", """(?i)^U\.?D\.?\s+""", "UD", LegalFormPosition.Prefix),

    // Vietnam (Prefix!)
    LegalFormRule("VN", """(?i)^Công ty TNHH(?:\s+MTV)?\s+""", "TNHH", LegalFormPosition.Prefix),
    LegalFormRule("VN", """(?i)^Công ty Cổ phần\s+""", "CTCP", LegalFormPosition.Prefix),
    LegalFormRule("VN", """(?i)^CTCP\s+""", "CTCP", LegalFormPosition.Prefix),

    // Russia (Prefix!)
    LegalFormRule("RU", """^(?:ООО|OOO)\s+""", "OOO", LegalFormPosition.Prefix),
    LegalFormRule("RU", """^(?:ПАО|PAO)\s+""", "PAO", LegalFormPosition.Prefix),
    LegalFormRule("RU", """^(?:ОАО|OAO)\s+""", "OAO", LegalFormPosition.Prefix),
    LegalFormRule("RU", """^(?:ЗАО|ZAO)\s+""", "ZAO", LegalFormPosition.Prefix),
    LegalFormRule("RU", """^(?:АО|AO)\s+""", "AO", LegalFormPosition.Prefix),

    // Japan (Either prefix OR suffix!)
    LegalFormRule("JP", """^株式会社\s*""", "KK", LegalFormPosition.Either),
    LegalFormRule("JP", """\s*株式会社$""", "KK", LegalFormPosition.Either),
    LegalFormRule("JP", """^有限会社\s*""", "YK", LegalFormPosition.Either),
    LegalFormRule("JP", """\s*有限会社$""", "YK", LegalFormPosition.Either),
    LegalFormRule("JP", """^合同会社\s*""", "GK", LegalFormPosition.Either),
    LegalFormRule("JP", """\s*合同会社$""", "GK", LegalFormPosition.Either),

    // China (Suffix)
    LegalFormRule("CN", """股份有限公司$""", "CO_LTD_STOCK", LegalFormPosition.Suffix),
    LegalFormRule("CN", """有限责任公司$""", "LLC_CN", LegalFormPosition.Suffix),
    LegalFormRule("CN", """集团有限公司$""", "GROUP_LTD_CN", LegalFormPosition.Suffix),
    LegalFormRule("CN", """有限公司$""", "LTD_CN", LegalFormPosition.Suffix),

    // Macau (Suffix) - prioritize English forms for bilingual names
    LegalFormRule("MO", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("MO", """(?i),?\s+Co\.?\s+Ltd\.?$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("MO", """股份有限公司$""", "LTD_CN", LegalFormPosition.Suffix),
    LegalFormRule("MO", """有限责任公司$""", "LLC_CN", LegalFormPosition.Suffix),
    LegalFormRule("MO", """集团有限公司$""", "GROUP_LTD_CN", LegalFormPosition.Suffix),
    LegalFormRule("MO", """有限公司$""", "LTD_CN", LegalFormPosition.Suffix),
    LegalFormRule("MO", """(?i)\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("MO", """(?i)\s+Limited$""", "LTD", LegalFormPosition.Suffix),

    // Taiwan (Suffix) - uses LTD_CN for 股份有限公司 (common in TW)
    LegalFormRule("TW", """股份有限公司$""", "LTD_CN", LegalFormPosition.Suffix),
    LegalFormRule("TW", """有限责任公司$""", "LLC_CN", LegalFormPosition.Suffix),
    LegalFormRule("TW", """集团有限公司$""", "GROUP_LTD_CN", LegalFormPosition.Suffix),
    LegalFormRule("TW", """有限公司$""", "LTD_CN", LegalFormPosition.Suffix),

    // Germany / DACH (Suffix)
    LegalFormRule("DE", """(?i),?\s+GmbH\s*&\s*Co\.?\s*KG$""", "GMBH_CO_KG", LegalFormPosition.Suffix),
    LegalFormRule("DE", """(?i),?\s+GmbH$""", "GMBH", LegalFormPosition.Suffix),
    LegalFormRule("DE", """(?i),?\s+Aktiengesellschaft$""", "AG", LegalFormPosition.Suffix),
    LegalFormRule("DE", """(?i),?\s+AG$""", "AG", LegalFormPosition.Suffix),
    LegalFormRule("DE", """(?i),?\s+SE$""", "SE", LegalFormPosition.Suffix),
    LegalFormRule("DE", """(?i),?\s+e\.?K\.?$""", "EK", LegalFormPosition.Suffix),

    // Singapore / Malaysia (Suffix)
    LegalFormRule("SG", """(?i),?\s+Pte\.?\s*Ltd\.?$""", "PTE_LTD", LegalFormPosition.Suffix),
    LegalFormRule("MY", """(?i),?\s+Sdn\.?\s*Bhd\.?$""", "SDN_BHD", LegalFormPosition.Suffix),

    // Australia (Suffix)
    LegalFormRule("AU", """(?i),?\s+Pty\.?\s*Ltd\.?$""", "PTY_LTD", LegalFormPosition.Suffix),
    LegalFormRule("AU", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("AU", """(?i),?\s+Ltd\.?$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("AU", """(?i),?\s+P\.?L\.?C\.?$""", "PLC", LegalFormPosition.Suffix),

    // UK / US / Commonwealth (Suffix)
    LegalFormRule("US", """(?i),?\s+Incorporated$""", "INC", LegalFormPosition.Suffix),
    LegalFormRule("US", """(?i),?\s+Inc\.?$""", "INC", LegalFormPosition.Suffix),
    LegalFormRule("US", """(?i),?\s+Corporation$""", "CORP", LegalFormPosition.Suffix),
    LegalFormRule("US", """(?i),?\s+Corp\.?$""", "CORP", LegalFormPosition.Suffix),
    LegalFormRule("US", """(?i),?\s+L\.?L\.?C\.?$""", "LLC", LegalFormPosition.Suffix),
    LegalFormRule("US", """(?i),?\s+&\s*Co\.?$""", "CO", LegalFormPosition.Suffix),
    LegalFormRule("GB", """(?i),?\s+Co\.?\s+Ltd\.?$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GB", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GB", """(?i),?\s+Ltd\.?$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GB", """(?i),?\s+L\.?L\.?P\.?$""", "LLP", LegalFormPosition.Suffix),
    LegalFormRule("GB", """(?i),?\s+P\.?L\.?C\.?$""", "PLC", LegalFormPosition.Suffix),

    // Malaysia (Suffix) - Bhd/Berhad as legal form
    LegalFormRule("MY", """(?i),?\s+Berhad$""", "BHD", LegalFormPosition.Suffix),
    LegalFormRule("MY", """(?i),?\s+Bhd\.?$""", "BHD", LegalFormPosition.Suffix),

    // France / Spain / Netherlands / Italy (Suffix)
    LegalFormRule("FR", """(?i),?\s+S\.?A\.?R\.?L\.?$""", "SARL", LegalFormPosition.Suffix),
    LegalFormRule("FR", """(?i),?\s+S\.?A\.?S\.?$""", "SAS", LegalFormPosition.Suffix),
    LegalFormRule("ES", """(?i),?\s+S\.?L\.?U?\.?$""", "SL", LegalFormPosition.Suffix),
    LegalFormRule("NL", """(?i),?\s+B\.?V\.?$""", "BV", LegalFormPosition.Suffix),
    LegalFormRule("NL", """(?i),?\s+N\.?V\.?$""", "NV", LegalFormPosition.Suffix),
    LegalFormRule("IT", """(?i),?\s+S\.?p\.?A\.?$""", "SPA", LegalFormPosition.Suffix),
    LegalFormRule("FR", """(?i),?\s+S\.?A\.?$""", "SA", LegalFormPosition.Suffix),

    // Turkey (Suffix)
    LegalFormRule("TR", """(?i),?\s+A\.?\s*\.?\s*Ş\.?$""", "AS", LegalFormPosition.Suffix),
    LegalFormRule("TR", """(?i),?\s+Anonim\s+Şirket$""", "AS", LegalFormPosition.Suffix),
    LegalFormRule("TR", """(?i),?\s+Ltd\.?\s*Şti\.?$""", "LTD_STI", LegalFormPosition.Suffix),
    LegalFormRule("TR", """(?i),?\s+Limited\s+Şirket$""", "LTD_STI", LegalFormPosition.Suffix),

    // UAE (Suffix)
    LegalFormRule("AE", """(?i),?\s+PJSC$""", "PJSC", LegalFormPosition.Suffix),
    LegalFormRule("AE", """(?i),?\s+P\.?J\.?S\.?C\.?$""", "PJSC", LegalFormPosition.Suffix),
    LegalFormRule("AE", """(?i),?\s+L\.?L\.?C\.?$""", "LLC", LegalFormPosition.Suffix),
    LegalFormRule("AE", """(?i),?\s+PrJSC$""", "PRJSC", LegalFormPosition.Suffix),

    // Qatar (Suffix)
    LegalFormRule("QA", """(?i),?\s+Q\.?P\.?S\.?C\.?$""", "QPSC", LegalFormPosition.Suffix),
    LegalFormRule("QA", """(?i),?\s+QPSC$""", "QPSC", LegalFormPosition.Suffix),
    LegalFormRule("QA", """(?i),?\s+W\.?L\.?L\.?$""", "WLL", LegalFormPosition.Suffix),
    LegalFormRule("QA", """(?i),?\s+WLL$""", "WLL", LegalFormPosition.Suffix),

    // Portugal (Suffix)
    LegalFormRule("PT", """(?i),?\s+L\.?d\.?a\.?$""", "LDA", LegalFormPosition.Suffix),
    LegalFormRule("PT", """(?i),?\s+Limitada$""", "LDA", LegalFormPosition.Suffix),
    LegalFormRule("PT", """(?i),?\s+S\.?A\.?$""", "SA", LegalFormPosition.Suffix),

    // Brazil (Suffix)
    LegalFormRule("BR", """(?i),?\s+Ltda\.?$""", "LTDA", LegalFormPosition.Suffix),
    LegalFormRule("BR", """(?i),?\s+Limitada$""", "LTDA", LegalFormPosition.Suffix),
    LegalFormRule("BR", """(?i),?\s+S\.?A\.?(?:\s*[-–—].*)?$""", "SA", LegalFormPosition.Suffix),
    LegalFormRule("BR", """(?i),?\s+S\.?A\.?\s*[-–—]""", "SA", LegalFormPosition.Suffix),
    LegalFormRule("BR", """(?i),?\s+S\/A(?:\s*[-–—].*)?$""", "SA", LegalFormPosition.Suffix),

    // Argentina (Suffix)
    LegalFormRule("AR", """(?i),?\s+S\.?R\.?L\.?(?:\s*[-–—].*)?$""", "SRL", LegalFormPosition.Suffix),
    LegalFormRule("AR", """(?i),?\s+Sociedad\s+de\s+Responsabilidad\s+Limitada(?:\s*[-–—].*)?$""", "SRL", LegalFormPosition.Suffix),
    LegalFormRule("AR", """(?i),?\s+S\.?A\.?(?:\s*[-–—].*)?$""", "SA", LegalFormPosition.Suffix),
    LegalFormRule("AR", """(?i),?\s+Sociedad\s+An[oó]nima(?:\s*[-–—].*)?$""", "SA", LegalFormPosition.Suffix),

    // South Africa (Suffix)
    LegalFormRule("ZA", """(?i),?\s+\(Pty\)\s*Ltd\.?$""", "PTY_LTD", LegalFormPosition.Suffix),
    LegalFormRule("ZA", """(?i),?\s+Proprietary\s+Limited$""", "PTY_LTD", LegalFormPosition.Suffix),
    LegalFormRule("ZA", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("ZA", """(?i),?\s+Ltd\.?$""", "LTD", LegalFormPosition.Suffix),

    // Egypt (Suffix)
    LegalFormRule("EG", """(?i),?\s+S\.?A\.?E\.?$""", "SAE", LegalFormPosition.Suffix),
    LegalFormRule("EG", """(?i),?\s+Soci[ée]t[ée]\s+Anonyme\s+[EÉ]gyptienne$""", "SAE", LegalFormPosition.Suffix),
    LegalFormRule("EG", """(?i),?\s+S\.?A\.?$""", "SA", LegalFormPosition.Suffix),

    // Morocco (Suffix) - already covered by FR SA/SARL

    // Hong Kong (Suffix) - uses GB/UK rules

    // Macau (Suffix) - uses PT/FR rules

    // Taiwan (Suffix) - uses CN rules

    // Crown Dependencies / Overseas Territories (VG, KY, JE, GG, IM, GI, BM) - use GB rules
    LegalFormRule("JE", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("JE", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GG", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GG", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("IM", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("IM", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GI", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("GI", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("BM", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("BM", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("VG", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("VG", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("KY", """(?i),?\s+Company\s+Limited$""", "LTD", LegalFormPosition.Suffix),
    LegalFormRule("KY", """(?i),?\s+Limited$""", "LTD", LegalFormPosition.Suffix),

    // Philippines (Suffix) - uses US rules

    // Malaysia additional - already covered

    // Indonesia additional - already covered

    // Vietnam (Suffix) - JSC/Corporation map to CTCP (Công ty Cổ Phần)
    LegalFormRule("VN", """(?i),?\s+JSC$""", "CTCP", LegalFormPosition.Suffix),
    LegalFormRule("VN", """(?i),?\s+Joint\s+Stock\s+Company$""", "CTCP", LegalFormPosition.Suffix),
    LegalFormRule("VN", """(?i),?\s+Corporation$""", "CTCP", LegalFormPosition.Suffix),
    LegalFormRule("VN", """(?i),?\s+Corp\.?$""", "CTCP", LegalFormPosition.Suffix),

    // Korea (Suffix) - handle both spaced and no-space patterns
    LegalFormRule("KR", """(?i),?\s*주식회사$""", "KK", LegalFormPosition.Suffix),
    LegalFormRule("KR", """(?i),?\s*\(주\)$""", "KK", LegalFormPosition.Suffix),
    LegalFormRule("KR", """(?i),?\s*유한회사$""", "YH", LegalFormPosition.Suffix),
    LegalFormRule("KR", """(?i),?\s*\(유\)$""", "YH", LegalFormPosition.Suffix),
    // Also match (주) without country hint for Korean-only text - MOVED UP for priority
    LegalFormRule("", """(?i)\(주\)$""", "KK", LegalFormPosition.Suffix),
    LegalFormRule("", """(?i)\s*\(주\)$""", "KK", LegalFormPosition.Suffix),

    // Indonesia additional - handle (Persero) and Tbk
    LegalFormRule("ID", """(?i),?\s+Tbk\.?$""", "Tbk", LegalFormPosition.Suffix),
    LegalFormRule("ID", """(?i),?\s+\(Persero\)\s*Tbk\.?$""", "Tbk", LegalFormPosition.Suffix),
    LegalFormRule("ID", """(?i)\(Persero\)""", "PT", LegalFormPosition.Suffix),
    // Also recognize PT prefix without dots
    LegalFormRule("ID", """(?i)^PT\s+""", "PT", LegalFormPosition.Prefix),

    // Nigeria/Kenya/Ghana/Tanzania - use GB rules

    // Ethiopia - no standard suffix in common usage

    // Japan additional - already covered

    // Korea additional - uses JP KK pattern
  )

  private val CompanySchema: JsObject = JsObject(List(
    "type" -> JsString("object"),
    "properties" -> JsObject(List(
      "country"              -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "core_name"            -> JsObject(List("type" -> JsString("string"))),
      "legal_form_raw"       -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "legal_form_canonical" -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "legal_form_position"  -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "confidence"           -> JsObject(List("type" -> JsString("number")))
    )),
    "required" -> JsArray(List(JsString("core_name"), JsString("confidence")))
  ))

  private val FallbackPrompt: String =
    """COMPANY_FALLBACK: Extract the core company name, ISO-2 country, legal form (raw & canonical), and position (prefix/suffix/either).
      |Separate listing markers (e.g. Tbk, PLC) from the legal form. Return strict JSON.""".stripMargin

  def parse(cleaned: CleanedInput, countryHint: Option[String] = None): ParseResult[ParsedCompanyName] = {
    var working = cleaned.cleanedText.trim

    // Strip department prefixes first (e.g. "Finance Dept: ", "Accounts Payable: ")
    DepartmentPrefixes.foreach { prefixRegex =>
      prefixRegex.r.findFirstMatchIn(working).foreach { m =>
        working = (working.substring(0, m.start) + working.substring(m.end)).trim
      }
    }

    // Strip trailing parenthetical remarks (e.g. "(Accounts Payable)")
    working = TrailingParentheticals.r.replaceAllIn(working, "").trim

    val detectedCountry = CountryDetector.detectCountry(working, countryHint)

    // 1. Strip listing marker first (e.g. "Tbk" in "PT Astra International Tbk")
    var listingRaw: Option[String] = None
    var listingCan: Option[String] = None
    var inferredCountry: Option[String] = detectedCountry

    // Prioritize rules matching detectedCountry, then check global list
    val orderedListing = detectedCountry match {
      case Some(cc) => ListingMarkers.filter(_.country == cc) ++ ListingMarkers.filterNot(_.country == cc)
      case None     => ListingMarkers
    }
    orderedListing.find { r =>
      r.pattern.r.findFirstMatchIn(working).exists { m =>
        listingRaw = Some(m.matched.trim.stripPrefix(",").trim)
        listingCan = Some(r.canonical)
        if (inferredCountry.isEmpty) inferredCountry = Some(r.country)
        working = (working.substring(0, m.start) + working.substring(m.end)).trim
        true
      }
    }

    // 2. Match legal form according to position rules
    var lfRaw: Option[String]             = None
    var lfCan: Option[String]             = None
    var lfPos: Option[LegalFormPosition]  = None

    val orderedLegal = inferredCountry match {
      case Some(cc) => LegalForms.filter(_.country == cc) ++ LegalForms.filterNot(_.country == cc)
      case None     => LegalForms
    }
    orderedLegal.find { r =>
      r.pattern.r.findFirstMatchIn(working).exists { m =>
        lfRaw = Some(m.matched.trim.stripPrefix(",").trim)
        lfCan = Some(r.canonical)
        lfPos = Some(r.position)
        if (inferredCountry.isEmpty) inferredCountry = Some(r.country)
        working = (working.substring(0, m.start) + working.substring(m.end)).trim
        true
      }
    }

    // Strip wrapping Russian/Latin quotes around core name (e.g. ООО "Яндекс" -> Яндекс)
    val coreClean = working.replaceAll("""^[«"„']+|[»"“']+$""", "").trim
    val normCore  = EntityNormalizer.transliterateToAscii(coreClean)

    val ruleParsed = ParsedCompanyName(
      country = inferredCountry,
      coreName = coreClean,
      normalizedCoreName = normCore,
      legalFormRaw = lfRaw,
      legalFormCanonical = lfCan,
      legalFormPosition = lfPos,
      listingMarkerRaw = listingRaw,
      listingMarkerCanonical = listingCan
    )

    val ruleConf =
      if (lfCan.isDefined && listingCan.isDefined) 0.97
      else if (lfCan.isDefined) 0.95
      else if (listingCan.isDefined) 0.90
      else 0.50

    // Confidence-gated LLM fallback (§5)
    val (finalParsed, finalConf, provenance, warnings) =
      if (ruleConf < fallbackThreshold && llm.isAvailable) {
        llm.completeJson(FallbackPrompt, cleaned.cleanedText, CompanySchema, maxTokens = 256) match {
          case Right(obj) =>
            val llmConf = obj.field("confidence").flatMap(_.asOptDouble).getOrElse(0.5)
            val llmCore = obj.fieldString("core_name").getOrElse(coreClean)
            val posOpt = obj.fieldString("legal_form_position").collect {
              case "prefix" => LegalFormPosition.Prefix
              case "suffix" => LegalFormPosition.Suffix
              case "either" => LegalFormPosition.Either
            }
            val merged = ruleParsed.copy(
              country = ruleParsed.country.orElse(obj.fieldString("country")),
              coreName = llmCore,
              normalizedCoreName = EntityNormalizer.transliterateToAscii(llmCore),
              legalFormRaw = ruleParsed.legalFormRaw.orElse(obj.fieldString("legal_form_raw")),
              legalFormCanonical = ruleParsed.legalFormCanonical.orElse(obj.fieldString("legal_form_canonical")),
              legalFormPosition = ruleParsed.legalFormPosition.orElse(posOpt)
            )
            (merged, math.max(ruleConf, llmConf), Provenance.RuleMergedWithLlm, Nil)
          case Left(err) =>
            (ruleParsed, ruleConf, Provenance.RuleBased, List(s"LLM fallback unavailable: $err"))
        }
      } else {
        val prov = if (cleaned.usedModel && cleaned.changes.nonEmpty) Provenance.LlmCleanedThenRule else Provenance.RuleBased
        val warns = if (lfCan.isEmpty && listingCan.isEmpty) List("No recognized legal-form designator or listing marker") else Nil
        (ruleParsed, ruleConf, prov, warns)
      }

    ParseResult(
      rawInput = cleaned.rawText,
      cleanedInput = cleaned,
      parsed = finalParsed,
      matchingKey = EntityNormalizer.companyMatchingKey(finalParsed),
      confidence = finalConf,
      provenance = provenance,
      warnings = warnings,
      needsHumanReview = finalConf < 0.70
    )
  }
}