package com.entityparser.pipeline

import com.entityparser.geo.GeoGazetteer
import com.entityparser.llm._
import com.entityparser.model._
import com.entityparser.parsers._

/**
 * End-to-end Hybrid Global Entity Parsing & Normalization Pipeline (§5).
 *
 * Execution flow:
 *   Stage 0: InputCleaner (conservative small-LLM cleaning + hard digit-invariance guardrail)
 *   Stage 1: Local-Conventions-First deterministic rule/dictionary parser
 *   Stage 2: Confidence-gated Gemma/llama.cpp fallback (ONLY when rules are low-confidence; never overrides high-conf rules)
 *   Stage 3: Geographic validation / checksum validation + deterministic normalization & matching key
 *
 * Degrades gracefully when `llm = NoOpLlmBackend` (rules-only mode).
 */
final class EntityPipeline(
  val llm: LlmBackend,
  val gazetteer: GeoGazetteer = GeoGazetteer.default
) {
  val cleaner: InputCleaner          = new InputCleaner(llm)
  val addressParser: AddressParser   = new AddressParser(gazetteer, llm)
  val personParser: PersonNameParser = new PersonNameParser(llm)
  val bankParser: BankAccountParser  = new BankAccountParser()
  val companyParser: CompanyNameParser = new CompanyNameParser(llm)
  val classifier: EntityClassifier   = new EntityClassifier(companyParser, personParser)

  def parseAddress(raw: String, countryHint: Option[String] = None): ParseResult[ParsedAddress] = {
    val cleaned = cleaner.clean(raw)
    addressParser.parse(cleaned, countryHint)
  }

  def parsePersonName(raw: String, countryHint: Option[String] = None): ParseResult[ParsedPersonName] = {
    val cleaned = cleaner.clean(raw)
    personParser.parse(cleaned, countryHint)
  }

  def parseBankAccount(raw: String, countryHint: Option[String] = None): ParseResult[ParsedBankAccount] = {
    val cleaned = cleaner.clean(raw)
    bankParser.parse(cleaned, countryHint)
  }

  def parseCompanyName(raw: String, countryHint: Option[String] = None): ParseResult[ParsedCompanyName] = {
    val cleaned = cleaner.clean(raw)
    companyParser.parse(cleaned, countryHint)
  }

  def classifyEntity(
    rawName: String,
    rawAddress: Option[String] = None,
    countryHint: Option[String] = None
  ): EntityClassification = {
    val cleanedName = cleaner.clean(rawName)
    val cleanedAddr = rawAddress.map(a => cleaner.clean(a).cleanedText)
    classifier.classify(cleanedName, cleanedAddr, countryHint)
  }
}

object EntityPipeline {
  /** Graceful degradation mode: zero model present, 100% deterministic rules + dictionaries. */
  def rulesOnly(gazetteer: GeoGazetteer = GeoGazetteer.default): EntityPipeline =
    new EntityPipeline(NoOpLlmBackend, gazetteer)

  /** Production mode: connects to local llama.cpp HTTP server running a Gemma GGUF model. */
  def withLlamaServer(
    baseUrl: String = "http://127.0.0.1:8080",
    modelName: String = "gemma-3-1b-it-Q4_K_M.gguf",
    gazetteer: GeoGazetteer = GeoGazetteer.default
  ): EntityPipeline =
    new EntityPipeline(new LlamaServerClient(baseUrl, modelName), gazetteer)

  /** CLI mode: invokes local llama-cli binary with a GGUF model file. */
  def withLlamaCli(
    modelPath: String,
    binaryPath: String = "llama-cli",
    gazetteer: GeoGazetteer = GeoGazetteer.default
  ): EntityPipeline =
    new EntityPipeline(new LlamaCliClient(binaryPath, modelPath), gazetteer)

  /** Offline evaluation mode: uses deterministic SimulatedGemmaBackend for reproducible benchmarks. */
  def withSimulatedGemma(gazetteer: GeoGazetteer = GeoGazetteer.default): EntityPipeline =
    new EntityPipeline(new SimulatedGemmaBackend(), gazetteer)
}
