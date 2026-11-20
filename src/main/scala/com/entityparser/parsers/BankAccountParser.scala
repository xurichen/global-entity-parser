package com.entityparser.parsers

import com.entityparser.model._
import com.entityparser.norm.EntityNormalizer

/**
 * 3.3 Bank Account & Routing Identifier Parser + Checksum Validator.
 *
 * Purely deterministic national standards:
 * - IBAN (ISO 13616 + ISO 7064 Mod-97-10 + per-country BBAN split)
 * - US ABA Routing Transit Number (9 digits, 3-7-1 weighted mod-10 checksum)
 * - UK Sort Code (6 digits) + 8-digit Account Number
 * - China CNAPS (12 digits: 3 bank + 4 city + 4 branch + 1 check) + optional account
 * - SWIFT/BIC (ISO 9362: 8 or 11 alphanumeric chars)
 * - Indian IFSC (11 chars: 4 bank + '0' + 6 branch)
 * - Australian BSB (6 digits: 2 bank + 1 state + 3 branch) + optional account
 * - Japanese Bank (4) + Branch (3) + Account (7)
 */
final class BankAccountParser {

  /** Per-country IBAN length and (bankCodeSlice, branchCodeSlice, accountSlice) within the BBAN (0-indexed). */
  private final case class IbanSpec(
    length: Int,
    bankRange: (Int, Int),
    branchRange: Option[(Int, Int)],
    accountStart: Int
  )

  private val IbanSpecs: Map[String, IbanSpec] = Map(
    "DE" -> IbanSpec(22, (0, 8),  None,         8),
    "GB" -> IbanSpec(22, (0, 4),  Some((4, 10)), 10),
    "FR" -> IbanSpec(27, (0, 5),  Some((5, 10)), 10),
    "NL" -> IbanSpec(18, (0, 4),  None,         4),
    "ES" -> IbanSpec(24, (0, 4),  Some((4, 8)),  10),
    "IT" -> IbanSpec(27, (1, 6),  Some((6, 11)), 11),
    "CH" -> IbanSpec(21, (0, 5),  None,         5),
    "AT" -> IbanSpec(20, (0, 5),  None,         5),
    "BE" -> IbanSpec(16, (0, 3),  None,         3),
    "AE" -> IbanSpec(23, (0, 3),  None,         3),
    "SA" -> IbanSpec(24, (0, 2),  None,         2),
    "PL" -> IbanSpec(28, (0, 8),  None,         8),
    "SE" -> IbanSpec(24, (0, 3),  None,         3),
    "IE" -> IbanSpec(22, (0, 4),  Some((4, 10)), 10),
    // Additional IBAN countries from iban.com registry
    "TR" -> IbanSpec(26, (0, 5),  None,         5),
    "QA" -> IbanSpec(29, (0, 4),  None,         4),
    "KW" -> IbanSpec(30, (0, 4),  None,         4),
    "PT" -> IbanSpec(25, (0, 4),  None,         4),
    "EG" -> IbanSpec(29, (0, 4),  None,         4),
    "MA" -> IbanSpec(28, (0, 4),  None,         4),
    "NO" -> IbanSpec(15, (0, 4),  None,         4),
    "DK" -> IbanSpec(18, (0, 4),  None,         4),
    "CZ" -> IbanSpec(24, (0, 4),  None,         4),
    "HU" -> IbanSpec(28, (0, 4),  None,         4),
    "RO" -> IbanSpec(24, (0, 4),  None,         4),
    "GR" -> IbanSpec(27, (0, 3),  None,         3),
    "FI" -> IbanSpec(18, (0, 6),  None,         6),
    "BG" -> IbanSpec(22, (0, 4),  None,         4),
    "HR" -> IbanSpec(21, (0, 7),  None,         7),
    "SI" -> IbanSpec(19, (0, 5),  None,         5),
    "SK" -> IbanSpec(24, (0, 4),  None,         4),
    "LT" -> IbanSpec(20, (0, 5),  None,         5),
    "LV" -> IbanSpec(21, (0, 4),  None,         4),
    "EE" -> IbanSpec(20, (0, 2),  None,         2),
    "MT" -> IbanSpec(31, (0, 4),  None,         4),
    "CY" -> IbanSpec(28, (0, 3),  None,         3),
    "LU" -> IbanSpec(20, (0, 3),  None,         3),
    "IS" -> IbanSpec(26, (0, 4),  None,         4),
    "FO" -> IbanSpec(18, (0, 4),  None,         4),
    "GI" -> IbanSpec(23, (0, 4),  None,         4),
    "MC" -> IbanSpec(27, (0, 5),  None,         5),
    "SM" -> IbanSpec(27, (1, 6),  None,         6),
    "VA" -> IbanSpec(22, (0, 3),  None,         3),
    "LI" -> IbanSpec(21, (0, 5),  None,         5),
    "AD" -> IbanSpec(24, (0, 4),  None,         4),
    "AL" -> IbanSpec(28, (0, 8),  None,         8),
    "AZ" -> IbanSpec(28, (0, 4),  None,         4),
    "BH" -> IbanSpec(22, (0, 4),  None,         4),
    "GE" -> IbanSpec(22, (0, 2),  None,         2),
    "JO" -> IbanSpec(30, (0, 4),  None,         4),
    "KZ" -> IbanSpec(20, (0, 3),  None,         3),
    "KH" -> IbanSpec(30, (0, 4),  None,         4),
    "LB" -> IbanSpec(28, (0, 4),  None,         4),
    "MK" -> IbanSpec(19, (0, 3),  None,         3),
    "MD" -> IbanSpec(24, (0, 2),  None,         2),
    "ME" -> IbanSpec(22, (0, 3),  None,         3),
    "MR" -> IbanSpec(27, (0, 5),  None,         5),
    "MU" -> IbanSpec(30, (0, 4),  None,         4),
    "PS" -> IbanSpec(29, (0, 4),  None,         4),
    "QA" -> IbanSpec(29, (0, 4),  None,         4),
    "RS" -> IbanSpec(22, (0, 3),  None,         3),
    "ST" -> IbanSpec(25, (0, 8),  None,         8),
    "SV" -> IbanSpec(28, (0, 4),  None,         4),
    "TL" -> IbanSpec(23, (0, 3),  None,         3),
    "TN" -> IbanSpec(24, (0, 2),  None,         2),
    "VG" -> IbanSpec(24, (0, 4),  None,         4),
    "XK" -> IbanSpec(20, (0, 2),  None,         2)
  )

  private val IbanCandidate   = """^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$""".r
  private val IbanLikeShort     = """^[A-Z]{2}\d{2}[A-Z0-9]{0,10}$""".r // Too short for valid IBAN but looks like one
  private val SwiftBicRegex   = """^([A-Z]{4})([A-Z]{2})([A-Z0-9]{2})([A-Z0-9]{3})?$""".r
  private val IfscRegex       = """^([A-Z]{4})0([A-Z0-9]{6})$""".r
  private val UkSortAccRegex  = """^(?:SORT\s*CODE\s*[:\-]?\s*)?(\d{2})[\-\s]?(\d{2})[\-\s]?(\d{2})[\s,/\-]+(?:ACC(?:OUNT)?\s*[:\-]?\s*)?(\d{8})$""".r
  private val AuBsbRegex      = """^(?:BSB\s*[:\-]?\s*)?(\d{3})[\-\s]?(\d{3})(?:[\s,/\-]+(?:ACC(?:OUNT)?\s*[:\-]?\s*)?(\d{6,10}))?$""".r
  private val UsAbaRegex      = """^(?:ABA|RTN|ROUTING)?\s*[:\-]?\s*(\d{9})(?:[\s,/\-]+(?:ACC(?:OUNT)?\s*[:\-]?\s*)?(\d{6,17}))?$""".r
  private val CnapsRegex      = """^(?:CNAPS|联行号)?\s*[:\-]?\s*(\d{12})(?:[\s,/\-]+(?:ACC(?:OUNT)?|账号)?\s*[:\-]?\s*(\d{12,19}))?$""".r
  private val JpBankRegex     = """^(?:JP|BANK)?\s*[:\-]?\s*(\d{4})[\-\s](\d{3})(?:[\-\s](\d{7}))?$""".r

  def parse(cleaned: CleanedInput, countryHint: Option[String] = None): ParseResult[ParsedBankAccount] = {
    val raw     = cleaned.cleanedText.trim
    val upper   = raw.toUpperCase(java.util.Locale.ROOT)
    val compact = upper.replaceAll("[^A-Z0-9]", "")
    val ccHint  = countryHint.map(_.toUpperCase(java.util.Locale.ROOT))

    val (acct, conf, warnings) =
      if (IbanCandidate.matches(compact) && IbanSpecs.contains(compact.take(2))) {
        parseIban(compact)
      } else if (IbanLikeShort.matches(compact) && IbanSpecs.contains(compact.take(2))) {
        // IBAN-like but too short - parse as invalid IBAN
        val cc = compact.take(2)
        val parsed = ParsedBankAccount(
          format = "IBAN",
          country = Some(cc),
          normalizedCompact = compact,
          bankCode = None,
          branchCode = None,
          accountNumber = None,
          checkDigits = if (compact.length >= 4) Some(compact.substring(2, 4)) else None,
          checksumValid = Some(false)
        )
        (parsed, 0.25, List(s"IBAN too short: ${compact.length} chars, minimum 15"))
      } else if (IfscRegex.matches(compact)) {
        parseIfsc(compact)
      } else if (SwiftBicRegex.matches(compact) && !compact.forall(_.isDigit)) {
        parseSwiftBic(compact)
      } else {
        upper match {
          case UkSortAccRegex(s1, s2, s3, acc) =>
            val sortCode = s"$s1$s2$s3"
            val parsed = ParsedBankAccount(
              format = "UK_SORT_ACCOUNT",
              country = Some("GB"),
              normalizedCompact = s"$sortCode$acc",
              bankCode = Some(sortCode.take(2)),
              branchCode = Some(sortCode),
              accountNumber = Some(acc),
              checkDigits = None,
              checksumValid = None
            )
            (parsed, 0.95, Nil)

          case AuBsbRegex(b1, b2, accOpt) if ccHint.contains("AU") || upper.contains("BSB") =>
            val bsb = s"$b1$b2"
            val acc = Option(accOpt)
            val parsed = ParsedBankAccount(
              format = "AU_BSB",
              country = Some("AU"),
              normalizedCompact = bsb + acc.getOrElse(""),
              bankCode = Some(bsb.take(2)),
              branchCode = Some(bsb.drop(2)),
              accountNumber = acc,
              checkDigits = None,
              checksumValid = None
            )
            (parsed, 0.94, Nil)

          case UsAbaRegex(aba, accOpt) if ccHint.contains("US") || upper.contains("ABA") || upper.contains("ROUTING") || validateAbaChecksum(aba) =>
            val valid = validateAbaChecksum(aba)
            val acc   = Option(accOpt)
            val parsed = ParsedBankAccount(
              format = "US_ABA",
              country = Some("US"),
              normalizedCompact = aba + acc.getOrElse(""),
              bankCode = Some(aba.substring(0, 8)),
              branchCode = None,
              accountNumber = acc,
              checkDigits = Some(aba.substring(8, 9)),
              checksumValid = Some(valid)
            )
            val warns = if (valid) Nil else List(s"US ABA routing number '$aba' failed 3-7-1 mod-10 checksum")
            (parsed, if (valid) 0.98 else 0.30, warns)

          case CnapsRegex(cnaps, accOpt) =>
            val acc = Option(accOpt)
            val parsed = ParsedBankAccount(
              format = "CN_CNAPS",
              country = Some("CN"),
              normalizedCompact = cnaps + acc.getOrElse(""),
              bankCode = Some(cnaps.substring(0, 3)),
              branchCode = Some(cnaps.substring(3, 11)),
              accountNumber = acc,
              checkDigits = Some(cnaps.substring(11, 12)),
              checksumValid = None
            )
            (parsed, 0.94, Nil)

          case JpBankRegex(bank, branch, accOpt) if ccHint.contains("JP") || upper.startsWith("JP") =>
            val acc = Option(accOpt)
            val parsed = ParsedBankAccount(
              format = "JP_BANK",
              country = Some("JP"),
              normalizedCompact = s"$bank$branch${acc.getOrElse("")}",
              bankCode = Some(bank),
              branchCode = Some(branch),
              accountNumber = acc,
              checkDigits = None,
              checksumValid = None
            )
            (parsed, 0.92, Nil)

          case _ =>
            val fallback = ParsedBankAccount(
              format = "UNKNOWN",
              country = ccHint,
              normalizedCompact = compact,
              bankCode = None,
              branchCode = None,
              accountNumber = Some(compact).filter(_.nonEmpty),
              checkDigits = None,
              checksumValid = None
            )
            (fallback, 0.25, List("Unrecognized bank account / routing format"))
        }
      }

    val prov = if (cleaned.usedModel && cleaned.changes.nonEmpty) Provenance.LlmCleanedThenRule else Provenance.RuleBased
    ParseResult(
      rawInput = cleaned.rawText,
      cleanedInput = cleaned,
      parsed = acct,
      matchingKey = EntityNormalizer.bankMatchingKey(acct),
      confidence = conf,
      provenance = prov,
      warnings = warnings,
      needsHumanReview = conf < 0.70 || acct.checksumValid.contains(false)
    )
  }

  /** ISO 13616 + ISO 7064 Mod-97-10 IBAN validation & BBAN decomposition. */
  def validateIbanMod97(compact: String): Boolean = {
    if (compact.length < 15 || compact.length > 34) return false
    val rearranged = compact.drop(4) + compact.take(4)
    val numericStr = rearranged.flatMap { c =>
      if (c.isDigit) c.toString
      else if (c >= 'A' && c <= 'Z') (c.toInt - 'A'.toInt + 10).toString
      else return false
    }
    BigInt(numericStr) % 97 == 1
  }

  private def parseIban(compact: String): (ParsedBankAccount, Double, List[String]) = {
    val cc   = compact.take(2)
    val chk  = compact.substring(2, 4)
    val bban = compact.drop(4)
    val spec = IbanSpecs(cc)

    val lengthOk = compact.length == spec.length
    val mod97Ok  = lengthOk && validateIbanMod97(compact)

    val bankCode   = if (bban.length >= spec.bankRange._2) Some(bban.substring(spec.bankRange._1, spec.bankRange._2)) else None
    val branchCode = spec.branchRange.flatMap { case (s, e) => if (bban.length >= e) Some(bban.substring(s, e)) else None }
    val accountNo  = if (bban.length > spec.accountStart) Some(bban.substring(spec.accountStart)) else None

    val warns = scala.collection.mutable.ListBuffer.empty[String]
    if (!lengthOk) warns += s"IBAN length ${compact.length} does not match $cc standard (${spec.length})"
    if (lengthOk && !mod97Ok) warns += s"IBAN failed ISO 7064 Mod-97-10 check digit validation"

    val parsed = ParsedBankAccount(
      format = "IBAN",
      country = Some(cc),
      normalizedCompact = compact,
      bankCode = bankCode,
      branchCode = branchCode,
      accountNumber = accountNo,
      checkDigits = Some(chk),
      checksumValid = Some(mod97Ok)
    )
    (parsed, if (mod97Ok) 0.99 else 0.25, warns.toList)
  }

  /** US ABA Routing Transit Number checksum: (3(d1+d4+d7) + 7(d2+d5+d8) + (d3+d6+d9)) mod 10 == 0. */
  def validateAbaChecksum(aba: String): Boolean = {
    if (aba.length != 9 || !aba.forall(_.isDigit)) return false
    val d = aba.map(_.asDigit)
    val sum = 3 * (d(0) + d(3) + d(6)) + 7 * (d(1) + d(4) + d(7)) + (d(2) + d(5) + d(8))
    sum % 10 == 0
  }

  private def parseSwiftBic(compact: String): (ParsedBankAccount, Double, List[String]) = {
    compact match {
      case SwiftBicRegex(bank, cc, loc, branch) =>
        val parsed = ParsedBankAccount(
          format = "SWIFT_BIC",
          country = Some(cc),
          normalizedCompact = compact,
          bankCode = Some(bank + cc + loc),
          branchCode = Option(branch).orElse(Some("XXX")),
          accountNumber = None,
          checkDigits = None,
          checksumValid = None
        )
        (parsed, 0.96, Nil)
      case _ =>
        (ParsedBankAccount("UNKNOWN", None, compact, None, None, None, None, None), 0.25, List("Invalid SWIFT/BIC"))
    }
  }

  private def parseIfsc(compact: String): (ParsedBankAccount, Double, List[String]) = {
    compact match {
      case IfscRegex(bank, branch) =>
        val parsed = ParsedBankAccount(
          format = "IN_IFSC",
          country = Some("IN"),
          normalizedCompact = compact,
          bankCode = Some(bank),
          branchCode = Some(branch),
          accountNumber = None,
          checkDigits = None,
          checksumValid = Some(compact.charAt(4) == '0')
        )
        (parsed, 0.97, Nil)
      case _ =>
        (ParsedBankAccount("UNKNOWN", None, compact, None, None, None, None, None), 0.25, List("Invalid IFSC"))
    }
  }
}
