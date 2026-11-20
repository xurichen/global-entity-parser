package com.entityparser.model

/** Provenance indicates which pipeline stage produced or refined a parsed record. */
sealed trait Provenance {
  def code: String
}
object Provenance {
  case object RuleBased             extends Provenance { val code = "rule_based" }
  case object RuleWithGeoValidation extends Provenance { val code = "rule_with_geo_validation" }
  case object LlmCleanedThenRule    extends Provenance { val code = "llm_cleaned_then_rule" }
  case object LlmFallback           extends Provenance { val code = "llm_fallback" }
  case object RuleMergedWithLlm     extends Provenance { val code = "rule_merged_with_llm" }
}

/** Audit record for a single modification applied during Stage 0 input cleaning. */
final case class CleaningChange(
  category: String, // "stripped_noise" | "typo_fix" | "ocr_repair" | "guardrail_rejected"
  before: String,
  after: String,
  reason: String
)

/** Output of Stage 0 conservative input cleaning. */
final case class CleanedInput(
  rawText: String,
  cleanedText: String,
  changes: List[CleaningChange],
  usedModel: Boolean,
  guardrailPassed: Boolean
)

/** Generic envelope returned by every entity parser. */
final case class ParseResult[A](
  rawInput: String,
  cleanedInput: CleanedInput,
  parsed: A,
  matchingKey: String,
  confidence: Double, // 0.0 to 1.0
  provenance: Provenance,
  warnings: List[String] = Nil,
  needsHumanReview: Boolean = false
)

/** Geographic validation result from GeoGazetteer. */
final case class GeoValidation(
  isValid: Boolean,
  countryCode: Option[String],
  canonicalAdmin1: Option[String],
  canonicalCity: Option[String],
  issues: List[String]
)

/** 3.1 Structured postal address. */
final case class ParsedAddress(
  country: Option[String],       // ISO 3166-1 alpha-2 (e.g. "CN", "US", "DE", "ID")
  provinceState: Option[String], // Admin1 (province, state, prefecture, Bundesland)
  city: Option[String],
  district: Option[String],
  street: Option[String],
  houseNumber: Option[String],
  postcode: Option[String],
  subpremise: Option[String] = None,
  geoValidation: Option[GeoValidation] = None
)

/** 3.2 Cultural naming system taxonomy. */
sealed trait NameCulture {
  def code: String
}
object NameCulture {
  case object ChineseMainland extends NameCulture { val code = "chinese_mainland" }
  case object ChineseDiaspora extends NameCulture { val code = "chinese_diaspora" }
  case object Japanese        extends NameCulture { val code = "japanese" }
  case object Korean          extends NameCulture { val code = "korean" }
  case object Western         extends NameCulture { val code = "western" }
  case object Spanish         extends NameCulture { val code = "spanish" }
  case object Arabic          extends NameCulture { val code = "arabic" }
  case object Russian         extends NameCulture { val code = "russian" }
  case object SouthAsian      extends NameCulture { val code = "south_asian" }
  case object Indonesian      extends NameCulture { val code = "indonesian" }
  case object Icelandic       extends NameCulture { val code = "icelandic" }
  case object Unknown         extends NameCulture { val code = "unknown" }
}

/** 3.2 Structured person name with explicit Diaspora Chinese & cultural fields. */
final case class ParsedPersonName(
  culture: NameCulture,
  honorific: Option[String] = None,
  englishName: Option[String] = None,      // Diaspora Chinese (e.g. "Jimmy" in "Jimmy Shuhao 林")
  givenName: Option[String] = None,        // Primary given name (or romanized Chinese given name "Shuhao")
  middleName: Option[String] = None,       // Western middle name ONLY (never used for Diaspora Chinese given name)
  patronymic: Option[String] = None,       // Russian (-ovich/-ovna), Arabic (bin/binti chain), Icelandic (-son/-dóttir)
  familyName: Option[String] = None,       // Primary surname / original script family name (e.g. "林")
  secondFamilyName: Option[String] = None, // Spanish maternal surname (apellido materno)
  suffix: Option[String] = None,           // Jr., Sr., III, PhD
  isMononym: Boolean = false,              // Indonesian single-name convention (e.g. "Sukarno", "Suharto")
  flaggedEdgeCase: Boolean = false
)

/** 3.3 Structured bank account / routing identifier. */
final case class ParsedBankAccount(
  format: String,              // IBAN | US_ABA | UK_SORT_ACCOUNT | CN_CNAPS | SWIFT_BIC | IN_IFSC | AU_BSB | JP_BANK | UNKNOWN
  country: Option[String],
  normalizedCompact: String,   // Uppercased, spaces/separators stripped
  bankCode: Option[String],
  branchCode: Option[String],
  accountNumber: Option[String],
  checkDigits: Option[String],
  checksumValid: Option[Boolean] // None when standard defines no checksum
)

/** 3.4 Legal form position convention. */
sealed trait LegalFormPosition {
  def code: String
}
object LegalFormPosition {
  case object Prefix extends LegalFormPosition { val code = "prefix" }
  case object Suffix extends LegalFormPosition { val code = "suffix" }
  case object Either extends LegalFormPosition { val code = "either" }
}

/** 3.4 Structured company / business name. */
final case class ParsedCompanyName(
  country: Option[String],
  coreName: String,
  normalizedCoreName: String,
  legalFormRaw: Option[String],
  legalFormCanonical: Option[String],
  legalFormPosition: Option[LegalFormPosition],
  listingMarkerRaw: Option[String],       // e.g. "Tbk", "plc", "Bhd"
  listingMarkerCanonical: Option[String]
)

/** 3.5 Entity-type classification label. */
sealed trait EntityTypeLabel {
  def code: String
}
object EntityTypeLabel {
  case object Company             extends EntityTypeLabel { val code = "company" }
  case object Individual          extends EntityTypeLabel { val code = "individual" }
  case object SoleTraderOrUnknown extends EntityTypeLabel { val code = "sole_trader_or_unknown" }
}

/** 3.5 Evidence signal emitted by EntityClassifier. */
final case class EvidenceSignal(
  name: String,
  direction: String, // "company" | "individual" | "sole_trader" | "neutral"
  weight: Double,
  detail: String
)

/** 3.5 Structured entity-type classification output. */
final case class EntityClassification(
  label: EntityTypeLabel,
  confidence: Double,
  signals: List[EvidenceSignal],
  countryHint: Option[String],
  needsHumanReview: Boolean
)

// ========================================================================
// 3.6 RAG-Style Country/Region Detection (§6.3)
// ========================================================================

/**
 * Evidence item for a country candidate in Stage 1 extraction.
 * Each signal type contributes to the candidate's uncapped score using tiered weights.
 */
sealed trait CountryEvidence {
  def signalType: String
  def description: String
  def weight: Double
}
object CountryEvidence {
  final case class HardIdentifier(countryCode: String, identifierType: String, value: String) extends CountryEvidence {
    val signalType  = "hard_identifier"
    val description = s"$identifierType: $value"
    val weight      = 10.0
  }
  final case class ScriptLanguage(script: String, language: Option[String], countryCode: String) extends CountryEvidence {
    val signalType  = "script_language"
    val description = s"Script: $script${language.map(l => s", Lang: $l").getOrElse("")}"
    val weight      = 3.0
  }
  final case class PostcodeFormat(countryCode: String, postcode: String) extends CountryEvidence {
    val signalType  = "postcode_format"
    val description = s"Postcode format matches $countryCode: $postcode"
    val weight      = 5.0
  }
  final case class LegalFormHit(countryCode: String, legalForm: String, position: String) extends CountryEvidence {
    val signalType  = "legal_form"
    val description = s"Legal form '$legalForm' ($position) matches $countryCode"
    val weight      = 4.0
  }
  final case class SurnameHit(countryCode: String, surname: String, culture: String) extends CountryEvidence {
    val signalType  = "surname"
    val description = s"Surname '$surname' ($culture) matches $countryCode"
    val weight      = 2.0
  }
  final case class GazetteerHit(
    countryCode: String,
    placeName: String,
    adminPath: String,
    matchedKey: String,
    weightOverride: Option[Double] = None
  ) extends CountryEvidence {
    val signalType     = "gazetteer"
    val description    = s"Place '$placeName' found in $countryCode ($adminPath)"
    val weight: Double = weightOverride.getOrElse(2.0)
  }
  final case class PhoneCountryCode(countryCode: String, phoneCode: String) extends CountryEvidence {
    val signalType  = "phone_country_code"
    val description = s"Phone country code $phoneCode matches $countryCode"
    val weight      = 8.0
  }
  final case class CcTLD(countryCode: String, domain: String) extends CountryEvidence {
    val signalType  = "cctld"
    val description = s"ccTLD .$domain matches $countryCode"
    val weight      = 5.0
  }
  final case class ExplicitHint(countryCode: String, source: String) extends CountryEvidence {
    val signalType  = "explicit_hint"
    val description = s"Explicit hint from $source: $countryCode"
    val weight      = 9.0
  }
}

/**
 * A single country candidate with its aggregated raw score and evidence.
 */
final case class CountryCandidate(
  countryCode: String,
  score: Double,
  evidence: List[CountryEvidence]
) {
  def primaryEvidence: List[CountryEvidence] = evidence.filter(_.weight >= 2.0)
  def hasHardIdentifier: Boolean = evidence.exists(_.signalType == "hard_identifier")
  def hardIdentifierCountry: Option[String] =
    evidence.find(_.signalType == "hard_identifier").map(_.asInstanceOf[CountryEvidence.HardIdentifier].countryCode)
}

/**
 * Result of Stage 1 candidate extraction: Top 3-5 candidates with raw scores and evidence.
 */
final case class CountryCandidates(
  candidates: List[CountryCandidate],
  rawText: String,
  hasHardIdentifier: Boolean,
  hardIdentifierCountry: Option[String]
) {
  def top1: Option[CountryCandidate] = candidates.headOption
  def top2: Option[CountryCandidate] = candidates.lift(1)
  def margin: Double = (top1, top2) match {
    case (Some(t1), Some(t2)) => t1.score - t2.score
    case (Some(t1), None)     => t1.score
    case _                    => 0.0
  }
  def needsAdjudication: Boolean = {
    // Gate: Top1-Top2 margin < 4.0 OR Top1 score < 7.0
    // But skip if hard identifier is present and consistent with Top1
    if (hasHardIdentifier && hardIdentifierCountry == top1.map(_.countryCode)) false
    else margin < 4.0 || top1.exists(_.score < 7.0)
  }
}

/**
 * Result of Stage 2 LLM adjudication.
 */
final case class CountryAdjudication(
  chosenCountry: Option[String],
  confidence: Double,
  decisiveEvidence: String,
  rawModelOutput: String,
  candidateSet: List[String],
  auditId: String
)

/**
 * Complete country detection result combining both stages.
 */
final case class CountryDetectionResult(
  stage1: CountryCandidates,
  stage2: Option[CountryAdjudication],
  finalCountry: Option[String],
  finalConfidence: Double,
  isUnknown: Boolean,
  provenance: String
) {
  def countryCode: Option[String] = finalCountry
}