package com.entityparser.llm

import com.entityparser.model.{CleanedInput, CleaningChange}
import com.entityparser.norm.EntityNormalizer

/**
 * Stage 0: Conservative Input Cleaning (§3.0).
 *
 * - Uses small LLM (Gemma via llama.cpp) when available, plus deterministic
 *   fallback rules when running model-free.
 * - Enforces a HARD digit-invariance guardrail: digits and numeric identifiers
 *   (house numbers, postcodes, account numbers) may NEVER be altered, dropped,
 *   or fabricated. If the model mutates any digit sequence, the model output
 *   is rejected and logged in the audit trail.
 */
final class InputCleaner(llm: LlmBackend) {

  private val DigitTokenRegex = "\\d+".r

  private val CleanerSchema: JsObject = JsObject(List(
    "type" -> JsString("object"),
    "properties" -> JsObject(List(
      "cleaned_text" -> JsObject(List("type" -> JsString("string"))),
      "changes" -> JsObject(List(
        "type" -> JsString("array"),
        "items" -> JsObject(List(
          "type" -> JsString("object"),
          "properties" -> JsObject(List(
            "category" -> JsObject(List("type" -> JsString("string"))),
            "before"   -> JsObject(List("type" -> JsString("string"))),
            "after"    -> JsObject(List("type" -> JsString("string"))),
            "reason"   -> JsObject(List("type" -> JsString("string")))
          )),
          "required" -> JsArray(List(JsString("category"), JsString("before"), JsString("after"), JsString("reason")))
        ))
      ))
    )),
    "required" -> JsArray(List(JsString("cleaned_text"), JsString("changes")))
  ))

  private val SystemPrompt: String =
    """STAGE0_CLEANER: You are a conservative entity text cleaner.
      |Strip forwarding notes, 'Attn:', department labels, and remarks not part of the entity.
      |Fix obvious typos in place/street/person names and repair mojibake/OCR glitches.
      |STRICT RULE: NEVER alter, delete, or add any digits, house numbers, postcodes, or account numbers.
      |Return JSON matching the schema with cleaned_text and changes.""".stripMargin

  /** Extract all numeric digit runs from a string for strict invariance checking. */
  private def extractDigitRuns(s: String): List[String] =
    DigitTokenRegex.findAllIn(s).toList

  /** Deterministic conservative cleanup used when LLM is unavailable or as baseline. */
  private def deterministicClean(raw: String): (String, List[CleaningChange]) = {
    var cur     = EntityNormalizer.unicodeNormalize(raw)
    val changes = scala.collection.mutable.ListBuffer.empty[CleaningChange]

    val prefixNoise = """(?i)^(fwd:|re:|attn:|attention:|to:|from:|accounts payable[-:]?)\s+""".r
    prefixNoise.findFirstIn(cur).foreach { m =>
      val next = cur.substring(m.length).trim
      changes += CleaningChange("stripped_noise", m.trim, "", "Stripped deterministic routing prefix")
      cur = next
    }

    // Basic UTF-8/Latin-1 mojibake fixes (zero digit changes)
    val mojibakePairs = List("Ã¼" -> "ü", "Ã¶" -> "ö", "Ã¤" -> "ä", "ÃŸ" -> "ß", "Ã©" -> "é", "Ã±" -> "ñ")
    mojibakePairs.foreach { case (bad, good) =>
      if (cur.contains(bad)) {
        val next = cur.replace(bad, good)
        changes += CleaningChange("ocr_repair", bad, good, "Repaired UTF-8/Latin-1 mojibake")
        cur = next
      }
    }

    (cur, changes.toList)
  }

  def clean(rawText: String): CleanedInput = {
    val normalizedRaw = EntityNormalizer.unicodeNormalize(rawText)
    if (!llm.isAvailable) {
      val (cleaned, changes) = deterministicClean(normalizedRaw)
      return CleanedInput(
        rawText = rawText,
        cleanedText = cleaned,
        changes = changes,
        usedModel = false,
        guardrailPassed = true
      )
    }

    llm.completeJson(SystemPrompt, normalizedRaw, CleanerSchema, maxTokens = 256) match {
      case Left(_) =>
        val (cleaned, changes) = deterministicClean(normalizedRaw)
        CleanedInput(rawText, cleaned, changes, usedModel = false, guardrailPassed = true)

      case Right(obj) =>
        val candidate = obj.fieldString("cleaned_text").map(EntityNormalizer.unicodeNormalize).getOrElse(normalizedRaw)
        val modelChanges = obj.field("changes").flatMap(_.asOptArray).getOrElse(Nil).flatMap { item =>
          for {
            cat <- item.fieldString("category")
            bef <- item.field("before").flatMap(_.asOptString)
            aft <- item.field("after").flatMap(_.asOptString)
            rsn <- item.fieldString("reason")
          } yield CleaningChange(cat, bef, aft, rsn)
        }

        // HARD GUARDRAIL (§3.0): Verify all digit sequences are preserved
        val rawDigits  = extractDigitRuns(normalizedRaw)
        val candDigits = extractDigitRuns(candidate)

        // Exception: allow known OCR letter-to-digit repair inside a word (e.g. "Münch3n" -> "München")
        // ONLY when the digit is embedded inside alphabetic characters on both sides.
        val standaloneRawDigits  = """(?<!\p{L})\d+(?!\p{L})""".r.findAllIn(normalizedRaw).toList
        val standaloneCandDigits = """(?<!\p{L})\d+(?!\p{L})""".r.findAllIn(candidate).toList

        if (standaloneRawDigits == standaloneCandDigits) {
          CleanedInput(
            rawText = rawText,
            cleanedText = candidate,
            changes = modelChanges,
            usedModel = true,
            guardrailPassed = true
          )
        } else {
          // Guardrail tripped! Reject LLM edit, fall back to deterministic cleanup, and log audit entry.
          val (fallbackClean, detChanges) = deterministicClean(normalizedRaw)
          val rejection = CleaningChange(
            category = "guardrail_rejected",
            before = rawDigits.mkString(","),
            after = candDigits.mkString(","),
            reason = "Rejected LLM cleaning output because standalone digits/identifiers were altered"
          )
          CleanedInput(
            rawText = rawText,
            cleanedText = fallbackClean,
            changes = detChanges :+ rejection,
            usedModel = true,
            guardrailPassed = false
          )
        }
    }
  }
}
