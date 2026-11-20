package com.entityparser.parsers

import com.entityparser.llm._
import com.entityparser.model._
import com.entityparser.norm.EntityNormalizer

/**
 * 3.2 Culturally-Aware Person Name Parser.
 *
 * Supports:
 * - Diaspora Chinese (HK/SG/overseas): e.g. "Jimmy Shuhao 林" -> englishName="Jimmy", givenName="Shuhao", familyName="林"
 *   and surname-first variants like "林书豪 Jimmy" -> familyName="林", givenName="书豪", englishName="Jimmy"
 * - Mainland Chinese (including 2-character compound surnames: 欧阳, 司马, 诸葛, 上官, 东方, 慕容, etc.)
 * - Japanese & Korean (family-first)
 * - Spanish (double surnames: paternal + maternal, with particles de/del/de la)
 * - Arabic (patronymic chains: bin/binti/ibn/bint, Al-/El- prefixes)
 * - Russian (patronymics: -ovich/-evich/-ovna/-evna / -ович/-овна)
 * - Icelandic (patronymics: -son/-dóttir, NO family name)
 * - Indonesian (mononyms: e.g. "Sukarno", "Suharto", flagged as mononym rather than forced into Western schema)
 * - Western (honorifics, given, middle, family, suffixes)
 * - South Asian & edge cases (flagged with confidence & LLM fallback)
 */
final class PersonNameParser(
  llm: LlmBackend = NoOpLlmBackend,
  fallbackThreshold: Double = 0.72
) {

  private val HanRegex      = """[\p{InCJK_Unified_Ideographs}]+""".r
  private val PureHanRegex  = """^[\p{InCJK_Unified_Ideographs}]{2,4}$""".r
  private val HangulRegex   = """^[\p{InHangul_Syllables}]{2,4}$""".r
  private val CyrillicRegex = """.*\p{InCyrillic}.*""".r
  private val LatinWord     = """^[A-Za-z][A-Za-z'\-]+$""".r

  private val ChineseCompoundSurnames: Set[String] = Set(
    "欧阳", "歐陽", "司马", "司馬", "诸葛", "諸葛", "上官", "东方", "東方",
    "皇甫", "慕容", "端木", "南宫", "南宮", "夏侯", "公孙", "公孫", "尉迟", "长孙", "司徒", "司空"
  )

  private val CommonChineseSingleSurnames: Set[String] = Set(
    "王", "李", "张", "張", "刘", "劉", "陈", "陳", "杨", "楊", "黄", "黃", "赵", "趙",
    "周", "吴", "吳", "徐", "孙", "孫", "马", "馬", "朱", "胡", "郭", "何", "高", "林",
    "罗", "羅", "郑", "鄭", "梁", "谢", "謝", "宋", "唐", "许", "許", "韩", "韓", "邓", "鄧",
    "曹", "彭", "曾", "萧", "蕭", "田", "董", "袁", "潘", "于", "蒋", "蔣", "蔡", "余", "杜"
  )

  private val CommonJapaneseSurnames: Set[String] = Set(
    "佐藤", "鈴木", "高橋", "田中", "伊藤", "渡辺", "山本", "中村", "小林", "加藤",
    "吉田", "山田", "佐々木", "山口", "松本", "井上", "木村", "林", "斎藤", "清水"
  )

  private val CommonSpanishSurnames: Set[String] = Set(
    "garcia", "garcía", "rodriguez", "rodríguez", "martinez", "martínez", "hernandez", "hernández",
    "lopez", "lópez", "gonzalez", "gonzález", "perez", "pérez", "sanchez", "sánchez", "ramirez",
    "torres", "flores", "rivera", "gomez", "gómez", "diaz", "díaz", "morales", "ortiz", "ruiz",
    "mendoza", "castillo", "vargas", "romero", "herrera", "marquez", "márquez"
  )

  private val Honorifics: Set[String] = Set(
    "mr", "mr.", "mrs", "mrs.", "ms", "ms.", "miss", "dr", "dr.", "prof", "prof.", "sir", "dame", "lord", "lady"
  )

  private val NameSuffixes: Set[String] = Set(
    "jr", "jr.", "sr", "sr.", "ii", "iii", "iv", "phd", "ph.d.", "md", "m.d.", "esq", "esq."
  )

  private val IndonesianMononyms: Set[String] = Set(
    "sukarno", "soekarno", "suharto", "soeharto", "sudirman", "boediono", "wiranto", "megawati", "hartono", "sutrisno"
  )

  private val PersonSchema: JsObject = JsObject(List(
    "type" -> JsString("object"),
    "properties" -> JsObject(List(
      "culture"     -> JsObject(List("type" -> JsString("string"))),
      "given_name"  -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "patronymic"  -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "family_name" -> JsObject(List("type" -> JsArray(List(JsString("string"), JsString("null"))))),
      "confidence"  -> JsObject(List("type" -> JsString("number")))
    )),
    "required" -> JsArray(List(JsString("culture"), JsString("confidence")))
  ))

  private val FallbackPrompt: String =
    """PERSON_FALLBACK: Parse this person name according to its cultural naming convention.
      |Do NOT force patronymic or mononym names into a Western first/middle/last schema. Return strict JSON.""".stripMargin

  def parse(cleaned: CleanedInput, countryHint: Option[String] = None): ParseResult[ParsedPersonName] = {
    val raw = cleaned.cleanedText.trim
    val cc  = countryHint.map(_.toUpperCase(java.util.Locale.ROOT))

    val (ruleParsed, ruleConf, ruleWarnings) = parseDeterministic(raw, cc)

    val (finalParsed, finalConf, provenance, extraWarnings) =
      if (ruleConf < fallbackThreshold && llm.isAvailable) {
        llm.completeJson(FallbackPrompt, raw, PersonSchema, maxTokens = 256) match {
          case Right(obj) =>
            val llmConf = obj.field("confidence").flatMap(_.asOptDouble).getOrElse(0.5)
            val merged = ruleParsed.copy(
              culture    = parseCultureCode(obj.fieldString("culture")).getOrElse(ruleParsed.culture),
              givenName  = obj.fieldString("given_name").orElse(ruleParsed.givenName),
              patronymic = obj.fieldString("patronymic").orElse(ruleParsed.patronymic),
              familyName = obj.fieldString("family_name").orElse(ruleParsed.familyName),
              flaggedEdgeCase = llmConf < 0.70
            )
            (merged, math.max(ruleConf, llmConf), Provenance.RuleMergedWithLlm, Nil)
          case Left(err) =>
            (ruleParsed, ruleConf, Provenance.RuleBased, List(s"LLM fallback unavailable: $err"))
        }
      } else {
        val prov = if (cleaned.usedModel && cleaned.changes.nonEmpty) Provenance.LlmCleanedThenRule else Provenance.RuleBased
        (ruleParsed, ruleConf, prov, Nil)
      }

    ParseResult(
      rawInput = cleaned.rawText,
      cleanedInput = cleaned,
      parsed = finalParsed,
      matchingKey = EntityNormalizer.personMatchingKey(finalParsed),
      confidence = finalConf,
      provenance = provenance,
      warnings = ruleWarnings ++ extraWarnings,
      needsHumanReview = finalConf < 0.70 || finalParsed.flaggedEdgeCase
    )
  }

  private def parseDeterministic(raw: String, cc: Option[String]): (ParsedPersonName, Double, List[String]) = {
    val tokens = raw.split("\\s+").filter(_.nonEmpty).toList
    if (tokens.isEmpty) {
      return (ParsedPersonName(NameCulture.Unknown, flaggedEdgeCase = true), 0.0, List("Empty name input"))
    }

    // 1. Diaspora Chinese pattern (§3.2): Mixed Latin + Han characters
    val hasHan   = HanRegex.findFirstIn(raw).isDefined
    val hasLatin = tokens.exists(t => LatinWord.matches(t))
    if (hasHan && hasLatin) {
      return parseDiasporaChinese(tokens)
    }

    // 2. Pure Han (Chinese or Japanese)
    if (PureHanRegex.matches(raw.replaceAll("\\s+", ""))) {
      val compact = raw.replaceAll("\\s+", "")
      if (cc.contains("JP") || CommonJapaneseSurnames.exists(compact.startsWith)) {
        val fam = CommonJapaneseSurnames.find(compact.startsWith).getOrElse(compact.take(2))
        val giv = compact.drop(fam.length)
        return (ParsedPersonName(NameCulture.Japanese, givenName = Option(giv).filter(_.nonEmpty), familyName = Some(fam)), 0.90, Nil)
      } else {
        return parseMainlandChinese(compact)
      }
    }

    // 3. Pure Hangul (Korean)
    if (HangulRegex.matches(raw.replaceAll("\\s+", ""))) {
      val compact = raw.replaceAll("\\s+", "")
      val fam = compact.take(1)
      val giv = compact.drop(1)
      return (ParsedPersonName(NameCulture.Korean, givenName = Some(giv), familyName = Some(fam)), 0.91, Nil)
    }

    // 4. Strip honorifics & suffixes for alphabetic names
    val (honOpt, afterHon) =
      if (tokens.size > 1 && Honorifics.contains(tokens.head.toLowerCase(java.util.Locale.ROOT)))
        (Some(tokens.head), tokens.tail)
      else (None, tokens)

    val (sufOpt, core) =
      if (afterHon.size > 1 && NameSuffixes.contains(afterHon.last.toLowerCase(java.util.Locale.ROOT)))
        (Some(afterHon.last), afterHon.init)
      else (None, afterHon)

    // 5. Indonesian mononym (§3.2)
    if (core.size == 1) {
      val single = core.head
      val isKnownMono = cc.contains("ID") || IndonesianMononyms.contains(single.toLowerCase(java.util.Locale.ROOT))
      if (isKnownMono) {
        return (
          ParsedPersonName(
            culture = NameCulture.Indonesian,
            honorific = honOpt,
            givenName = Some(single),
            isMononym = true
          ),
          0.92,
          Nil
        )
      } else {
        return (
          ParsedPersonName(
            culture = NameCulture.Unknown,
            honorific = honOpt,
            givenName = Some(single),
            isMononym = true,
            flaggedEdgeCase = true
          ),
          0.45,
          List("Single-token name without Indonesian/mononym region hint; flagged for review")
        )
      }
    }

    // 6. Arabic patronymic chain (bin / binti / ibn / bint) or Al- surname
    val lowerTokens = core.map(_.toLowerCase(java.util.Locale.ROOT))
    val arabicMarkers = Set("bin", "binti", "ibn", "bint", "binte")
    val markerIdx = lowerTokens.indexWhere(arabicMarkers.contains)
    if (markerIdx > 0 && markerIdx < core.size - 1) {
      val given = core.take(markerIdx).mkString(" ")
      val rest  = core.drop(markerIdx)
      val alIdx = rest.lastIndexWhere(t => t.toLowerCase(java.util.Locale.ROOT).startsWith("al-") || t.toLowerCase(java.util.Locale.ROOT).startsWith("el-"))
      val (pat, famOpt) =
        if (alIdx > 1) (rest.take(alIdx).mkString(" "), Some(rest.drop(alIdx).mkString(" ")))
        else (rest.mkString(" "), None)
      return (
        ParsedPersonName(
          culture = NameCulture.Arabic,
          honorific = honOpt,
          givenName = Some(given),
          patronymic = Some(pat),
          familyName = famOpt
        ),
        0.92,
        Nil
      )
    }

    // 7. Icelandic patronymic system (-son / -dóttir / -dottir, NO family name!)
    val lastLow = lowerTokens.last
    if (cc.contains("IS") || lastLow.endsWith("dóttir") || lastLow.endsWith("dottir") || (lastLow.endsWith("sson") && core.size == 2)) {
      return (
        ParsedPersonName(
          culture = NameCulture.Icelandic,
          honorific = honOpt,
          givenName = Some(core.init.mkString(" ")),
          patronymic = Some(core.last),
          familyName = None
        ),
        0.93,
        Nil
      )
    }

    // 8. Russian patronymics (Cyrillic or Latin -ovich/-evich/-ovna/-evna)
    val isRuPatronymic: String => Boolean = { s =>
      val l = s.toLowerCase(java.util.Locale.ROOT)
      l.endsWith("ович") || l.endsWith("евич") || l.endsWith("овна") || l.endsWith("евна") ||
      l.endsWith("ична") || l.endsWith("ovich") || l.endsWith("evich") || l.endsWith("ovna") || l.endsWith("evna")
    }
    if (CyrillicRegex.matches(raw) || core.exists(isRuPatronymic)) {
      val patIdx = core.indexWhere(isRuPatronymic)
      if (core.size == 3 && patIdx == 1) {
        // Given Patronymic Family (e.g. "Иван Сергеевич Петров")
        return (
          ParsedPersonName(
            culture = NameCulture.Russian,
            honorific = honOpt,
            givenName = Some(core(0)),
            patronymic = Some(core(1)),
            familyName = Some(core(2))
          ),
          0.95,
          Nil
        )
      } else if (core.size == 3 && patIdx == 2) {
        // Family Given Patronymic (official Russian order: "Петров Иван Сергеевич")
        return (
          ParsedPersonName(
            culture = NameCulture.Russian,
            honorific = honOpt,
            familyName = Some(core(0)),
            givenName = Some(core(1)),
            patronymic = Some(core(2))
          ),
          0.95,
          Nil
        )
      }
    }

    // 9. Spanish double surnames (3-4 tokens where last 2 are surnames or countryHint is ES/MX/CO/AR)
    val hasSpanishSurname = lowerTokens.takeRight(2).exists(CommonSpanishSurnames.contains)
    if (core.size >= 3 && (cc.exists(Set("ES", "MX", "CO", "PE", "CL", "AR").contains) || hasSpanishSurname)) {
      val fam2 = core.last
      val fam1 = core(core.size - 2)
      val giv  = core.dropRight(2).mkString(" ")
      return (
        ParsedPersonName(
          culture = NameCulture.Spanish,
          honorific = honOpt,
          givenName = Some(giv),
          familyName = Some(fam1),
          secondFamilyName = Some(fam2),
          suffix = sufOpt
        ),
        0.90,
        Nil
      )
    }

    // 10. Nobiliary particles (e.g. "van der Berg", "de la Cruz") -> lower confidence if multi-particle
    val particles = Set("van", "von", "der", "den", "de", "la", "del", "di", "da")
    if (core.size >= 3 && core.drop(1).init.exists(t => particles.contains(t.toLowerCase(java.util.Locale.ROOT)))) {
      val pIdx = core.indexWhere(t => particles.contains(t.toLowerCase(java.util.Locale.ROOT)), 1)
      return (
        ParsedPersonName(
          culture = NameCulture.Western,
          honorific = honOpt,
          givenName = Some(core.take(pIdx).mkString(" ")),
          familyName = Some(core.drop(pIdx).mkString(" ")),
          suffix = sufOpt
        ),
        0.86,
        Nil
      )
    }

    // 11. South Asian patronymic/multi-part flag if country hint is IN/LK
    if (cc.contains("IN") || cc.contains("LK")) {
      return (
        ParsedPersonName(
          culture = NameCulture.SouthAsian,
          honorific = honOpt,
          givenName = Some(core.head),
          familyName = Some(core.tail.mkString(" ")),
          flaggedEdgeCase = true
        ),
        0.58,
        List("South Asian naming conventions vary by region (patronymic vs surname); routed to LLM fallback/review")
      )
    }

    // 12. Standard Western name (Given [Middle] Family)
    core match {
      case g :: f :: Nil =>
        (ParsedPersonName(NameCulture.Western, honOpt, givenName = Some(g), familyName = Some(f), suffix = sufOpt), 0.90, Nil)
      case g :: m :: f :: Nil =>
        (ParsedPersonName(NameCulture.Western, honOpt, givenName = Some(g), middleName = Some(m), familyName = Some(f), suffix = sufOpt), 0.88, Nil)
      case _ =>
        (ParsedPersonName(NameCulture.Western, honOpt, givenName = Some(core.head), middleName = Some(core.tail.init.mkString(" ")), familyName = Some(core.last), suffix = sufOpt, flaggedEdgeCase = true),
          0.62, List("Unusual multi-token Western name; flagged for verification"))
    }
  }

  private def parseMainlandChinese(compact: String): (ParsedPersonName, Double, List[String]) = {
    val compoundOpt = ChineseCompoundSurnames.find(compact.startsWith)
    compoundOpt match {
      case Some(comp) =>
        val giv = compact.drop(comp.length)
        (ParsedPersonName(NameCulture.ChineseMainland, givenName = Option(giv).filter(_.nonEmpty), familyName = Some(comp)), 0.96, Nil)
      case None =>
        val fam = compact.take(1)
        val giv = compact.drop(1)
        val conf = if (CommonChineseSingleSurnames.contains(fam)) 0.95 else 0.82
        (ParsedPersonName(NameCulture.ChineseMainland, givenName = Option(giv).filter(_.nonEmpty), familyName = Some(fam)), conf, Nil)
    }
  }

  /**
   * Diaspora Chinese pattern (§3.2):
   * - "Jimmy Shuhao 林" -> englishName="Jimmy", givenName="Shuhao", familyName="林" (NOT middleName!)
   * - "林书豪 Jimmy" -> familyName="林", givenName="书豪", englishName="Jimmy"
   * - "林 Jimmy 书豪" -> familyName="林", englishName="Jimmy", givenName="书豪"
   */
  private def parseDiasporaChinese(tokens: List[String]): (ParsedPersonName, Double, List[String]) = {
    val hanTokens   = tokens.filter(t => HanRegex.findFirstIn(t).isDefined)
    val latinTokens = tokens.filter(t => LatinWord.matches(t))

    // Case A: "Jimmy Shuhao 林" (2+ Latin tokens + 1-2 char Han surname at end)
    if (tokens.last == hanTokens.last && hanTokens.size == 1 && hanTokens.head.length <= 2 && latinTokens.size >= 2) {
      val eng = latinTokens.head
      val giv = latinTokens.tail.mkString(" ")
      val fam = hanTokens.head
      return (
        ParsedPersonName(
          culture = NameCulture.ChineseDiaspora,
          englishName = Some(eng),
          givenName = Some(giv),
          middleName = None, // Explicitly NOT a Western middle name!
          familyName = Some(fam)
        ),
        0.95,
        Nil
      )
    }

    // Case B: Surname-first variant "林书豪 Jimmy" (full Han name + English name)
    if (hanTokens.size == 1 && hanTokens.head.length >= 2 && latinTokens.size == 1) {
      val (cnParsed, _, _) = parseMainlandChinese(hanTokens.head)
      return (
        ParsedPersonName(
          culture = NameCulture.ChineseDiaspora,
          englishName = Some(latinTokens.head),
          givenName = cnParsed.givenName,
          middleName = None,
          familyName = cnParsed.familyName
        ),
        0.95,
        Nil
      )
    }

    // Case C: "林 Jimmy" (Han surname + English name)
    if (hanTokens.size == 1 && hanTokens.head.length <= 2 && latinTokens.size == 1) {
      return (
        ParsedPersonName(
          culture = NameCulture.ChineseDiaspora,
          englishName = Some(latinTokens.head),
          familyName = Some(hanTokens.head)
        ),
        0.88,
        Nil
      )
    }

    (
      ParsedPersonName(
        culture = NameCulture.ChineseDiaspora,
        englishName = latinTokens.headOption,
        givenName = latinTokens.drop(1).headOption,
        familyName = hanTokens.headOption,
        flaggedEdgeCase = true
      ),
      0.65,
      List("Non-standard mixed-script Diaspora Chinese pattern")
    )
  }

  private def parseCultureCode(codeOpt: Option[String]): Option[NameCulture] = codeOpt.map(_.toLowerCase(java.util.Locale.ROOT)).collect {
    case "chinese_mainland" => NameCulture.ChineseMainland
    case "chinese_diaspora" => NameCulture.ChineseDiaspora
    case "japanese"         => NameCulture.Japanese
    case "korean"           => NameCulture.Korean
    case "western"          => NameCulture.Western
    case "spanish"          => NameCulture.Spanish
    case "arabic"           => NameCulture.Arabic
    case "russian"          => NameCulture.Russian
    case "south_asian"      => NameCulture.SouthAsian
    case "indonesian"       => NameCulture.Indonesian
    case "icelandic"        => NameCulture.Icelandic
  }
}
