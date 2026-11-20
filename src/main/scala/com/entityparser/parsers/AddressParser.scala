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
  private val UsRegex =
    """^(\d+[A-Za-z]?(?:-\d+)?)\s+([^,]+?)(?:,\s*(?:Apt|Suite|Ste|Unit|#)\s*([^,]+))?,\s*([^,]+?),\s*([A-Z]{2})\s+(\d{5}(?:-\d{4})?)(?:,\s*(?:USA|US|United States))?$""".r

  // Germany: Friedrichstraße 123, 10117 Berlin (or with Bundesland / Germany)
  private val DeRegex =
    """^([^,\d]+?)\s+(\d+[a-zA-Z]?(?:-\d+[a-zA-Z]?)?),\s*(\d{5})\s+([^,]+?)(?:,\s*([^,]+?))?(?:,\s*(?:Germany|Deutschland|DE))?$""".r

  // UK: 221B Baker Street, London, NW1 6XE
  private val UkRegex =
    """^(\d+[A-Za-z]?)\s+([^,]+?),\s*([^,]+?)(?:,\s*([^,]+?))?,?\s*([A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2})(?:,\s*(?:UK|United Kingdom|GB))?$""".r

  // Japan: 〒150-0002 東京都渋谷区渋谷2-21-1
  private val JpRegex =
    """^(?:〒\s*(\d{3}-?\d{4})\s*)?(東京都|大阪府|京都府|北海道|[\p{InCJK_Unified_Ideographs}]{2,4}県)([\p{InCJK_Unified_Ideographs}]{1,8}(?:市|区|郡))([\p{InCJK_Unified_Ideographs}A-Za-zぁ-んァ-ヶ]+?)(\d+(?:-\d+)*)(.*)$""".r

  def parse(cleaned: CleanedInput, countryHint: Option[String] = None): ParseResult[ParsedAddress] = {
    val text    = cleaned.cleanedText
    val country = CountryDetector.detectCountry(text, countryHint)

    val (ruleParsed, ruleConf, ruleWarnings) = country match {
      case Some("CN") => parseChina(text)
      case Some("US") => parseUs(text)
      case Some("DE") => parseGermany(text)
      case Some("GB") => parseUk(text)
      case Some("JP") => parseJapan(text)
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
      provinceState = geoVal.canonicalAdmin1.orElse(merged.provinceState),
      city          = geoVal.canonicalCity.orElse(merged.city),
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
        val addr = ParsedAddress(
          country       = Some("US"),
          provinceState = Some(state),
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(zip.trim),
          subpremise    = Option(sub).map(_.trim).filter(_.nonEmpty)
        )
        (addr, 0.94, Nil)
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
      case UkRegex(houseNo, street, city, _, postcode) =>
        val addr = ParsedAddress(
          country       = Some("GB"),
          provinceState = None,
          city          = Some(city.trim),
          district      = None,
          street        = Some(street.trim),
          houseNumber   = Some(houseNo.trim),
          postcode      = Some(postcode.trim.toUpperCase(java.util.Locale.ROOT))
        )
        (addr, 0.92, Nil)
      case _ =>
        (ParsedAddress(Some("GB"), None, None, None, None, None, None), 0.35, List("UK address did not match standard pattern"))
    }
  }

  private def parseJapan(text: String): (ParsedAddress, Double, List[String]) = {
    val compact = text.replaceAll("\\s+", "")
    compact match {
      case JpRegex(pc, pref, city, street, houseNo, rest) =>
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
      case _ =>
        (ParsedAddress(Some("JP"), None, None, None, None, None, None), 0.35, List("Japanese address did not match standard large-to-small pattern"))
    }
  }
}
