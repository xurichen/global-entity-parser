package com.entityparser.norm

import java.text.Normalizer
import com.entityparser.model._

/** Deterministic normalization and canonical matching-key generators (§4). */
object EntityNormalizer {

  private val CombiningMarks = "\\p{InCombiningDiacriticalMarks}+".r
  private val MultiSpace     = "\\s+".r
  private val NonAlphaNum    = "[^\\p{L}\\p{N}\\s]+".r

  /** NFKC Unicode normalization + whitespace collapse. */
  def unicodeNormalize(s: String): String = {
    val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
    MultiSpace.replaceAllIn(nfkc.trim, " ")
  }

  /** Fold Latin diacritics (e.g. "München" -> "muenchen" or "munchen", "São" -> "sao") and lowercase. */
  def foldLatin(s: String, germanUmlautExpand: Boolean = false): String = {
    val base = unicodeNormalize(s)
    val umlautHandled =
      if (germanUmlautExpand)
        base
          .replace("ä", "ae").replace("Ä", "ae")
          .replace("ö", "oe").replace("Ö", "oe")
          .replace("ü", "ue").replace("Ü", "ue")
          .replace("ß", "ss")
      else
        base.replace("ß", "ss")
    val nfd      = Normalizer.normalize(umlautHandled, Normalizer.Form.NFD)
    val stripped = CombiningMarks.replaceAllIn(nfd, "")
    stripped.toLowerCase(java.util.Locale.ROOT)
  }

  /**
   * Normalize Arabic script variants:
   * - أ إ آ ٱ -> ا
   * - ة -> ه
   * - ى -> ي
   * - strip tatweel (ـ, U+0640) and Arabic vowel diacritics (U+064B–U+0652)
   */
  private def normalizeArabic(s: String): String = {
    var hasArabic = false
    var i = 0
    while (i < s.length && !hasArabic) {
      val c = s.charAt(i)
      if (c >= '\u0622' && c <= '\u0671') hasArabic = true
      i += 1
    }
    if (!hasArabic) return s

    val sb = new StringBuilder(s.length)
    i = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '\u0623' | '\u0625' | '\u0622' | '\u0671' => sb.append('\u0627') // أ إ آ ٱ -> ا
        case '\u0629' => sb.append('\u0647') // ة -> ه
        case '\u0649' => sb.append('\u064A') // ى -> ي
        case '\u0640' => ()                  // strip tatweel ـ
        case c if c >= '\u064B' && c <= '\u0652' => () // strip Arabic vowels U+064B-U+0652
        case c => sb.append(c)
      }
      i += 1
    }
    sb.toString()
  }

  /** Clean token string for matching keys: Arabic normalization, lowercase, strip punctuation, collapse spaces. */
  def keyToken(s: String, germanUmlautExpand: Boolean = false): String = {
    val arabicNorm = normalizeArabic(unicodeNormalize(s))
    val folded     = foldLatin(arabicNorm, germanUmlautExpand)
    val cleaned    = NonAlphaNum.replaceAllIn(folded, " ")
    MultiSpace.replaceAllIn(cleaned.trim, " ")
  }

  /** Basic Cyrillic-to-Latin transliteration (GOST/ISO-9 inspired) for cross-script matching. */
  private val CyrillicMap: Map[Char, String] = Map(
    'а' -> "a",  'б' -> "b",  'в' -> "v",  'г' -> "g",  'д' -> "d",
    'е' -> "e",  'ё' -> "yo", 'ж' -> "zh", 'з' -> "z",  'и' -> "i",
    'й' -> "y",  'к' -> "k",  'л' -> "l",  'м' -> "m",  'н' -> "n",
    'о' -> "o",  'п' -> "p",  'р' -> "r",  'с' -> "s",  'т' -> "t",
    'у' -> "u",  'ф' -> "f",  'х' -> "kh", 'ц' -> "ts", 'ч' -> "ch",
    'ш' -> "sh", 'щ' -> "shch",'ъ' -> "",  'ы' -> "y",  'ь' -> "",
    'э' -> "e",  'ю' -> "yu", 'я' -> "ya"
  )

  /** Minimal high-frequency CJK surname Pinyin table for cross-script matching keys. */
  private val CjkSurnamePinyin: Map[String, String] = Map(
    "林" -> "lin", "陈" -> "chen", "陳" -> "chen", "王" -> "wang", "李" -> "li",
    "张" -> "zhang", "張" -> "zhang", "刘" -> "liu", "劉" -> "liu", "杨" -> "yang",
    "楊" -> "yang", "黄" -> "huang", "黃" -> "huang", "赵" -> "zhao", "趙" -> "zhao",
    "周" -> "zhou", "吴" -> "wu", "吳" -> "wu", "徐" -> "xu", "孙" -> "sun",
    "孫" -> "sun", "马" -> "ma", "馬" -> "ma", "朱" -> "zhu", "胡" -> "hu",
    "郭" -> "guo", "何" -> "he", "高" -> "gao", "郑" -> "zheng", "鄭" -> "zheng",
    "欧阳" -> "ouyang", "歐陽" -> "ouyang", "司马" -> "sima", "司馬" -> "sima",
    "诸葛" -> "zhuge", "諸葛" -> "zhuge", "上官" -> "shangguan", "东方" -> "dongfang",
    "书豪" -> "shuhao", "書豪" -> "shuhao", "明" -> "ming", "伟" -> "wei", "芳" -> "fang"
  )

  def transliterateToAscii(s: String): String = {
    val norm = unicodeNormalize(s)
    // Check exact CJK dictionary hits first (longest match)
    if (CjkSurnamePinyin.contains(norm)) return CjkSurnamePinyin(norm)

    val sb = new StringBuilder
    var i  = 0
    while (i < norm.length) {
      val twoChar = if (i + 2 <= norm.length) norm.substring(i, i + 2) else ""
      val oneChar = norm.substring(i, i + 1)
      if (twoChar.nonEmpty && CjkSurnamePinyin.contains(twoChar)) {
        sb.append(CjkSurnamePinyin(twoChar))
        i += 2
      } else if (CjkSurnamePinyin.contains(oneChar)) {
        sb.append(CjkSurnamePinyin(oneChar))
        i += 1
      } else {
        val ch = norm.charAt(i).toLower
        CyrillicMap.get(ch) match {
          case Some(lat) => sb.append(lat)
          case None      => sb.append(norm.charAt(i))
        }
        i += 1
      }
    }
    keyToken(sb.toString())
  }

  /** Expand common street abbreviations per country convention. */
  private val StreetAbbrevEn: Map[String, String] = Map(
    "st" -> "street", "rd" -> "road", "ave" -> "avenue", "blvd" -> "boulevard",
    "dr" -> "drive", "ln" -> "lane", "ct" -> "court", "pl" -> "place",
    "pkwy" -> "parkway", "hwy" -> "highway", "sq" -> "square", "ter" -> "terrace",
    "n" -> "north", "s" -> "south", "e" -> "east", "w" -> "west"
  )

  private val StreetAbbrevDe: Map[String, String] = Map(
    "str" -> "strasse", "pl" -> "platz", "al" -> "allee"
  )

  def normalizeStreet(street: String, country: Option[String]): String = {
    val isDe = country.contains("DE") || country.contains("AT") || country.contains("CH")
    val base = keyToken(street, germanUmlautExpand = isDe)
    // Handle German fused suffix "str" (e.g. "friedrichstr" -> "friedrichstrasse")
    val tokens = base.split(" ").filter(_.nonEmpty).map { tok =>
      if (isDe && tok.endsWith("str") && tok.length > 4) tok.dropRight(3) + "strasse"
      else if (isDe) StreetAbbrevDe.getOrElse(tok, tok)
      else StreetAbbrevEn.getOrElse(tok, tok)
    }
    tokens.mkString(" ")
  }

  /** Canonical matching key for Address: ADDR|COUNTRY|POSTCODE|ADMIN1|CITY|STREET|HOUSE_NO */
  def addressMatchingKey(a: ParsedAddress): String = {
    val cc = a.country.map(_.toUpperCase(java.util.Locale.ROOT)).getOrElse("XX")
    val pc = a.postcode.map(p => p.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT)).getOrElse("")
    val p1 = a.provinceState.map(s => keyToken(s, germanUmlautExpand = cc == "DE")).getOrElse("")
    val ct = a.city.map(s => keyToken(s, germanUmlautExpand = cc == "DE")).getOrElse("")
    val st = a.street.map(s => normalizeStreet(s, a.country)).getOrElse("")
    val hn = a.houseNumber.map(h => h.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT)).getOrElse("")
    s"ADDR|$cc|$pc|$p1|$ct|$st|$hn"
  }

  /** Canonical matching key for PersonName: PERSON|FAMILY|GIVEN|PATRONYMIC|ENGLISH */
  def personMatchingKey(p: ParsedPersonName): String = {
    val fam  = p.familyName.map(transliterateToAscii).getOrElse("")
    val fam2 = p.secondFamilyName.map(transliterateToAscii).filter(_.nonEmpty).map(" " + _).getOrElse("")
    val giv  = p.givenName.map(transliterateToAscii).getOrElse("")
    val pat  = p.patronymic.map(transliterateToAscii).getOrElse("")
    val eng  = p.englishName.map(transliterateToAscii).getOrElse("")
    if (p.isMononym) s"PERSON|MONO|$giv||"
    else s"PERSON|${fam + fam2}|$giv|$pat|$eng"
  }

  /** Canonical matching key for BankAccount: BANK|FORMAT|COUNTRY|COMPACT */
  def bankMatchingKey(b: ParsedBankAccount): String = {
    val cc = b.country.getOrElse("XX").toUpperCase(java.util.Locale.ROOT)
    s"BANK|${b.format}|$cc|${b.normalizedCompact}"
  }

  /** Canonical matching key for CompanyName: ORG|COUNTRY|NORMALIZED_CORE|LEGAL_FORM */
  def companyMatchingKey(c: ParsedCompanyName): String = {
    val cc = c.country.getOrElse("XX").toUpperCase(java.util.Locale.ROOT)
    val lf = c.legalFormCanonical.getOrElse("")
    s"ORG|$cc|${c.normalizedCoreName}|$lf"
  }
}