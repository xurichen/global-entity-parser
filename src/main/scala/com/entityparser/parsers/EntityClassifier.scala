package com.entityparser.parsers

import com.entityparser.model._

/**
 * 3.5 Entity-Type Classification (Company vs. Individual vs. Sole Trader / Unknown).
 *
 * Combines explicit, auditable evidence signals:
 * - Corporate legal-form / listing-marker presence
 * - Country-specific sole-trader patterns (DE e.K., GB t/a / trading as, ID UD/CV,
 *   CN 个体工商户/经营部/商行/工作室, RU ИП, US DBA)
 * - Person-name parse confidence & cultural match
 * - Industry / trade keywords
 * - Address type (office tower / industrial park vs. residential apartment / compound)
 */
final class EntityClassifier(
  companyParser: CompanyNameParser,
  personParser: PersonNameParser
) {

  private val SoleTraderPatterns: List[(String, scala.util.matching.Regex, String)] = List(
    ("DE", """(?i)\b(e\.?k\.?|inh\.?\s+)""".r,                              "German sole proprietor (e.K. / Inhaber)"),
    ("GB", """(?i)\b(t/a|trading\s+as)\b""".r,                              "UK sole trader ('trading as' / 't/a')"),
    ("US", """(?i)\b(d/b/a|dba|doing\s+business\s+as)\b""".r,               "US sole proprietor / DBA"),
    ("ID", """(?i)^(u\.?d\.?|c\.?v\.?)\s+""".r,                             "Indonesian sole proprietorship / partnership (UD/CV)"),
    ("CN", """(个体工商户|经营部|商行|工作室|维修部|百货店|餐饮店)$""".r,            "Chinese individual business (个体工商户/经营部/商行)"),
    ("RU", """(?i)^(ип|ip)\s+""".r,                                         "Russian individual entrepreneur (ИП)")
  )

  private val IndustryKeywords: Set[String] = Set(
    "technologies", "technology", "tech", "holdings", "group", "international",
    "logistics", "trading", "bank", "capital", "ventures", "industries",
    "solutions", "systems", "consulting", "partners", "manufacturing",
    "pharmaceuticals", "energy", "motors", "airlines", "electronics",
    "plumbing", "bakery", "studio", "workshop", "pharmacy", "clinic",
    "科技", "贸易", "集团", "实业", "物流", "银行", "咨询", "电子", "制造"
  )

  private val CommercialAddressMarkers =
    """(?i)(industrial park|business park|tech park|tower|plaza|suite|ste\.?|floor|fl\.?|building|bldg|office|center|centre|科技园|工业园|大厦|广场|写字楼|园区)""".r

  private val ResidentialAddressMarkers =
    """(?i)(apt\.?|apartment|flat|residence|villa|cottage|house|小区|花园|公寓|住宅|宿舍|弄|村)""".r

  def classify(
    cleanedName: CleanedInput,
    addressOpt: Option[String] = None,
    countryHint: Option[String] = None
  ): EntityClassification = {
    val name    = cleanedName.cleanedText.trim
    val lower   = name.toLowerCase(java.util.Locale.ROOT)
    val country = CountryDetector.detectCountry(name + " " + addressOpt.getOrElse(""), countryHint)

    val signals = scala.collection.mutable.ListBuffer.empty[EvidenceSignal]

    // 1. Check country-specific sole-trader patterns FIRST (e.g. e.K., UD, CV, t/a, 个体工商户)
    val soleTraderHit = SoleTraderPatterns.find { case (_, rx, _) => rx.findFirstIn(name).isDefined }
    soleTraderHit.foreach { case (cc, _, desc) =>
      signals += EvidenceSignal("sole_trader_pattern", "sole_trader", 0.85, s"$desc ($cc)")
    }

    // 2. Run CompanyNameParser to detect corporate legal forms & listing markers
    val compRes = companyParser.parse(cleanedName, country)
    val hasCorporateLegalForm = compRes.parsed.legalFormCanonical.exists(lf => !Set("EK", "UD", "CV").contains(lf))
    val hasListingMarker      = compRes.parsed.listingMarkerCanonical.isDefined

    if (hasCorporateLegalForm) {
      signals += EvidenceSignal(
        "corporate_legal_form",
        "company",
        0.92,
        s"Matched corporate legal form '${compRes.parsed.legalFormRaw.getOrElse("")}' (${compRes.parsed.legalFormCanonical.get})"
      )
    }
    if (hasListingMarker) {
      signals += EvidenceSignal(
        "listing_marker",
        "company",
        0.85,
        s"Matched public listing marker '${compRes.parsed.listingMarkerRaw.getOrElse("")}'"
      )
    }

    // 3. Check industry / trade keywords
    val matchedKeywords = IndustryKeywords.filter(kw => lower.contains(kw))
    if (matchedKeywords.nonEmpty) {
      signals += EvidenceSignal(
        "industry_keywords",
        "company",
        0.55,
        s"Contains industry/trade keyword(s): ${matchedKeywords.take(3).mkString(", ")}"
      )
    }

    // 4. Run PersonNameParser (only meaningful if no corporate legal form was found)
    val personRes = personParser.parse(cleanedName, country)
    val strongPersonParse =
      !hasCorporateLegalForm && !hasListingMarker &&
      personRes.confidence >= 0.85 &&
      !personRes.parsed.flaggedEdgeCase &&
      matchedKeywords.isEmpty

    if (strongPersonParse) {
      signals += EvidenceSignal(
        "person_name_parse",
        "individual",
        0.82,
        s"Parsed cleanly as ${personRes.parsed.culture.code} person name (conf=${f"${personRes.confidence}%.2f"})"
      )
    } else if (!hasCorporateLegalForm && personRes.confidence >= 0.70 && matchedKeywords.nonEmpty) {
      // "Person name + trade" sole trader signal (e.g. "Müller Plumbing", "Zhang Wei Consulting")
      signals += EvidenceSignal(
        "person_plus_trade",
        "sole_trader",
        0.65,
        s"Combines person-like tokens with trade keyword(s): ${matchedKeywords.take(2).mkString(", ")}"
      )
    }

    // 5. Inspect optional address for commercial vs. residential cues
    addressOpt.foreach { addr =>
      if (CommercialAddressMarkers.findFirstIn(addr).isDefined) {
        signals += EvidenceSignal("address_commercial", "company", 0.35, "Address contains commercial/office/industrial park indicator")
      } else if (ResidentialAddressMarkers.findFirstIn(addr).isDefined) {
        signals += EvidenceSignal("address_residential", "individual", 0.30, "Address contains residential indicator")
      }
    }

    // 6. Aggregate weighted evidence into label & confidence
    val compScore = signals.filter(_.direction == "company").map(_.weight).sum
    val indScore  = signals.filter(_.direction == "individual").map(_.weight).sum
    val soleScore = signals.filter(_.direction == "sole_trader").map(_.weight).sum

    val (label, conf) =
      if (soleScore >= 0.65 && !hasCorporateLegalForm && !hasListingMarker) {
        (EntityTypeLabel.SoleTraderOrUnknown, math.min(0.95, 0.50 + soleScore * 0.45))
      } else if (compScore >= 0.80 && compScore > indScore) {
        (EntityTypeLabel.Company, math.min(0.99, 0.55 + compScore * 0.35))
      } else if (indScore >= 0.75 && indScore > compScore && soleScore == 0.0) {
        (EntityTypeLabel.Individual, math.min(0.96, 0.50 + indScore * 0.40))
      } else if (compScore > indScore && compScore >= 0.50) {
        (EntityTypeLabel.Company, math.min(0.82, 0.45 + compScore * 0.35))
      } else {
        (EntityTypeLabel.SoleTraderOrUnknown, 0.50)
      }

    EntityClassification(
      label = label,
      confidence = conf,
      signals = signals.toList,
      countryHint = country,
      needsHumanReview = conf < 0.70 || label == EntityTypeLabel.SoleTraderOrUnknown
    )
  }
}
