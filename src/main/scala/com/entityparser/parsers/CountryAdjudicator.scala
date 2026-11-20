package com.entityparser.parsers

import com.entityparser.llm.{LlmBackend, SimulatedGemmaBackend}
import com.entityparser.llm.{JsObject, JsArray, JsString, JsNumber, JsBoolean, JsonCodec}
import com.entityparser.model._
import scala.util.Try
import java.util.UUID

/**
 * Stage 2: LLM-based adjudication for ambiguous country detection cases.
 *
 * Gate: Only invoked when Stage 1 produces ambiguous results:
 *   - Top-1 minus Top-2 margin < 0.15, OR
 *   - Top-1 score < 0.6
 *   - BUT skipped if hard identifier present and consistent with Top-1
 *
 * Guardrails:
 *   1. Hard identifiers cannot be overturned
 *   2. Model must choose from Stage 1 candidate set
 *   3. Confidence < 0.5 → UNKNOWN
 *   4. Full audit trail (input, candidates, evidence, model output, decision)
 */
final class CountryAdjudicator(
  private val llm: LlmBackend
) {

  private val systemPrompt =
    """You are a country/region detection expert. Given input text and a set of candidate countries with evidence, select the most likely country.
       |
       |RULES (strict, no exceptions):
       |1. You MUST choose from the provided candidate set only.
       |2. If a hard identifier (IBAN, BIC, phone code, ccTLD) is present, you CANNOT overturn it.
       |3. If confidence < 0.5, output "UNKNOWN".
       |4. Output ONLY valid JSON matching the schema.
       |""".stripMargin

  private val jsonSchema = JsObject(List(
    "type" -> JsString("object"),
    "properties" -> JsObject(List(
      "countryCode" -> JsObject(List(
        "type" -> JsString("string"),
        "description" -> JsString("ISO 3166-1 alpha-2 country code, or 'UNKNOWN'")
      )),
      "confidence" -> JsObject(List(
        "type" -> JsString("number"),
        "minimum" -> JsNumber(0.0),
        "maximum" -> JsNumber(1.0),
        "description" -> JsString("Confidence in the decision (0.0-1.0)")
      )),
      "decisiveEvidence" -> JsObject(List(
        "type" -> JsString("string"),
        "description" -> JsString("Brief description of the key evidence that decided the outcome")
      )),
      "reasoning" -> JsObject(List(
        "type" -> JsString("string"),
        "description" -> JsString("Short reasoning for the decision")
      ))
    )),
    "required" -> JsArray(List(JsString("countryCode"), JsString("confidence"), JsString("decisiveEvidence"), JsString("reasoning"))),
    "additionalProperties" -> JsBoolean(false)
  ))

  /**
   * Adjudicate ambiguous country candidates using LLM.
   * Returns None if gate conditions not met (use Stage 1 result directly).
   */
  def adjudicate(candidates: CountryCandidates): Option[CountryAdjudication] = {
    // Gate check
    if (!candidates.needsAdjudication) return None

    val candidateSet = candidates.candidates.map(_.countryCode)
    val top1 = candidates.top1

    // Build evidence summary for LLM
    val evidenceSummary = candidates.candidates.flatMap { c =>
      c.evidence.map { e =>
        s"  - ${c.countryCode}: [${e.signalType}] ${e.description} (weight=${e.weight})"
      }
    }.mkString("\n")

    val hardIdInfo = candidates.candidates.find(_.hasHardIdentifier).map { c =>
      s"HARD IDENTIFIER PRESENT: ${c.hardIdentifierCountry.getOrElse("?")} (${c.evidence.find(_.signalType == "hard_identifier").map(_.description).getOrElse("")})"
    }.getOrElse("")

    val userPrompt =
      s"""INPUT TEXT:
         |${candidates.rawText}
         |
         |CANDIDATE COUNTRIES (with evidence):
         |$evidenceSummary
         |
         |$hardIdInfo
         |
         |CANDIDATE SET: ${candidateSet.mkString(", ")}
         |
         |Select the most likely country from the candidate set. If hard identifier is present, you MUST select that country.
         |If you cannot decide with confidence >= 0.5, output "UNKNOWN".
         |""".stripMargin

    val result = if (llm == null) Left("No LLM backend (rules-only mode)") else llm.completeJson(systemPrompt, userPrompt, jsonSchema, maxTokens = 512)

    result match {
      case Right(jsObj) =>
        val countryCodeOpt = jsObj.fieldString("countryCode").filter(_.nonEmpty)
        val confidence = jsObj.field("confidence").flatMap(_.asOptDouble).getOrElse(0.0)
        val decisiveEvidence = jsObj.fieldString("decisiveEvidence").getOrElse("")
        val reasoning = jsObj.fieldString("reasoning").getOrElse("")

        // Guardrail 1: Hard identifier cannot be overturned
        val finalCountry = (candidates.hasHardIdentifier, candidates.hardIdentifierCountry, countryCodeOpt) match {
          case (true, Some(hardCc), Some(modelCc)) if hardCc != modelCc =>
            // Model tried to overturn hard identifier - reject and use hard identifier country
            Some(CountryAdjudication(
              chosenCountry = Some(hardCc),
              confidence = 0.95,
              decisiveEvidence = s"Hard identifier overrides model: $hardCc",
              rawModelOutput = JsonCodec.stringify(jsObj),
              candidateSet = candidateSet,
              auditId = UUID.randomUUID().toString.take(8)
            ))
          case (_, _, Some("UNKNOWN")) =>
            // Guardrail 3 refined: abstention → keep Top-1 (see note below)
            // Refinement (2026-11-20, measured): an abstention must not discard a
            // Stage-1 decision. Keep Top-1, flagged with reduced confidence; the
            // model's UNKNOWN and its raw output remain in the audit trail.
            Some(CountryAdjudication(
              chosenCountry = top1.map(_.countryCode),
              confidence = top1.map(t => math.min(t.score, 0.5)).getOrElse(confidence),
              decisiveEvidence = s"Model abstained (UNKNOWN/low confidence $confidence); kept Stage-1 Top-1: $decisiveEvidence",
              rawModelOutput = JsonCodec.stringify(jsObj),
              candidateSet = candidateSet,
              auditId = UUID.randomUUID().toString.take(8)
            ))
          case (_, _, Some(_)) if confidence < 0.5 =>
            // Refinement (2026-11-20, measured): an abstention must not discard a
            // Stage-1 decision. Keep Top-1, flagged with reduced confidence; the
            // model's UNKNOWN and its raw output remain in the audit trail.
            Some(CountryAdjudication(
              chosenCountry = top1.map(_.countryCode),
              confidence = top1.map(t => math.min(t.score, 0.5)).getOrElse(confidence),
              decisiveEvidence = s"Model abstained (UNKNOWN/low confidence $confidence); kept Stage-1 Top-1: $decisiveEvidence",
              rawModelOutput = JsonCodec.stringify(jsObj),
              candidateSet = candidateSet,
              auditId = UUID.randomUUID().toString.take(8)
            ))
          case (_, _, Some(modelCc)) if candidateSet.contains(modelCc) =>
            // Guardrail 2: Must choose from candidate set
            Some(CountryAdjudication(
              chosenCountry = Some(modelCc),
              confidence = confidence,
              decisiveEvidence = decisiveEvidence,
              rawModelOutput = JsonCodec.stringify(jsObj),
              candidateSet = candidateSet,
              auditId = UUID.randomUUID().toString.take(8)
            ))
          case _ =>
            // Model chose outside candidate set or invalid output - fallback
            top1.map { t1 =>
              CountryAdjudication(
                chosenCountry = Some(t1.countryCode),
                confidence = math.min(t1.score, 0.5),
                decisiveEvidence = s"Model output invalid/outside candidates; fell back to top-1: ${t1.countryCode}",
                rawModelOutput = JsonCodec.stringify(jsObj),
                candidateSet = candidateSet,
                auditId = UUID.randomUUID().toString.take(8)
              )
            }
        }
        finalCountry

      case Left(err) =>
        // LLM call failed - fallback to top-1 with reduced confidence
        top1.map { t1 =>
          CountryAdjudication(
            chosenCountry = Some(t1.countryCode),
            confidence = math.min(t1.score, 0.5),
            decisiveEvidence = s"LLM adjudication failed ($err); fell back to top-1",
            rawModelOutput = s"ERROR: $err",
            candidateSet = candidateSet,
            auditId = UUID.randomUUID().toString.take(8)
          )
        }
    }
  }
}

object CountryAdjudicator {
  def apply(llm: LlmBackend): CountryAdjudicator = new CountryAdjudicator(llm)
  def withSimulatedGemma(): CountryAdjudicator = new CountryAdjudicator(new SimulatedGemmaBackend())
  def rulesOnly(): CountryAdjudicator = new CountryAdjudicator(null)
}