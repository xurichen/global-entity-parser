package com.entityparser.eval

import com.entityparser.model._
import com.entityparser.pipeline.EntityPipeline

/**
 * Executable verification suite & hard-case evaluation comparing:
 *   1. Rules-Only (graceful degradation, no LLM)
 *   2. Rules + Gemma (llama.cpp hybrid pipeline)
 *
 * Run with: `sbt run`
 * Or against a live llama.cpp server: `LLAMA_SERVER_URL=http://127.0.0.1:8080 sbt run`
 */
object HardCaseEvaluation extends App {

  final case class EvalCase(
    id: String,
    entityType: String,
    input: String,
    countryHint: Option[String],
    addressHint: Option[String],
    check: EntityPipeline => (Boolean, String, Double, String)
  )

  val rulesPipeline = EntityPipeline.rulesOnly()
  val hybridPipeline = sys.env.get("LLAMA_SERVER_URL") match {
    case Some(url) =>
      val model = sys.env.getOrElse("GEMMA_MODEL", "gemma-3-1b-it-Q4_K_M.gguf")
      println(s"[INFO] Connecting to live llama-server at $url ($model)")
      EntityPipeline.withLlamaServer(url, model)
    case None =>
      println("[INFO] LLAMA_SERVER_URL not set; using SimulatedGemmaBackend for reproducible offline benchmark.")
      EntityPipeline.withSimulatedGemma()
  }

  // --- Part 1: Core Convention & Guardrail Unit Checks (Rules-Only) ---
  println("=== PART 1: CORE LOCAL-CONVENTIONS & GUARDRAIL CHECKS (RULES-ONLY) ===")

  def assertCheck(name: String, cond: Boolean, detail: String): Unit = {
    val status = if (cond) "PASS" else "FAIL"
    println(f"  [$status] $name%-48s | $detail")
    require(cond, s"Check failed: $name ($detail)")
  }

  // 1. Diaspora Chinese ("Jimmy Shuhao 林" & "林书豪 Jimmy")
  val d1 = rulesPipeline.parsePersonName("Jimmy Shuhao 林")
  val d2 = rulesPipeline.parsePersonName("林书豪 Jimmy")
  assertCheck(
    "Diaspora CN (Jimmy Shuhao 林)",
    d1.parsed.culture == NameCulture.ChineseDiaspora &&
      d1.parsed.englishName.contains("Jimmy") &&
      d1.parsed.givenName.contains("Shuhao") &&
      d1.parsed.middleName.isEmpty &&
      d1.parsed.familyName.contains("林"),
    s"key=${d1.matchingKey}"
  )
  assertCheck(
    "Diaspora CN Surname-First (林书豪 Jimmy)",
    d2.parsed.culture == NameCulture.ChineseDiaspora &&
      d2.parsed.englishName.contains("Jimmy") &&
      d2.parsed.familyName.contains("林") &&
      d2.matchingKey == d1.matchingKey,
    s"key=${d2.matchingKey} (matches d1=${d2.matchingKey == d1.matchingKey})"
  )

  // 2. Mainland Chinese compound surname
  val cnComp = rulesPipeline.parsePersonName("欧阳修")
  assertCheck(
    "CN Compound Surname (欧阳修)",
    cnComp.parsed.familyName.contains("欧阳") && cnComp.parsed.givenName.contains("修"),
    s"fam=${cnComp.parsed.familyName}, giv=${cnComp.parsed.givenName}"
  )

  // 3. Indonesian mononym & Icelandic patronymic & Arabic chain & Spanish double surname
  val mono = rulesPipeline.parsePersonName("Sukarno", Some("ID"))
  assertCheck("Indonesian Mononym (Sukarno)", mono.parsed.isMononym && mono.parsed.familyName.isEmpty, s"key=${mono.matchingKey}")

  val ice = rulesPipeline.parsePersonName("Katrín Jakobsdóttir", Some("IS"))
  assertCheck("Icelandic Patronymic (Katrín Jakobsdóttir)", ice.parsed.culture == NameCulture.Icelandic && ice.parsed.patronymic.contains("Jakobsdóttir") && ice.parsed.familyName.isEmpty, s"pat=${ice.parsed.patronymic}")

  val ar = rulesPipeline.parsePersonName("Muhammad bin Abdullah Al-Mansoor")
  assertCheck("Arabic Patronymic Chain", ar.parsed.culture == NameCulture.Arabic && ar.parsed.patronymic.contains("bin Abdullah") && ar.parsed.familyName.contains("Al-Mansoor"), s"key=${ar.matchingKey}")

  val es = rulesPipeline.parsePersonName("Gabriel García Márquez", Some("ES"))
  assertCheck("Spanish Double Surname", es.parsed.familyName.contains("García") && es.parsed.secondFamilyName.contains("Márquez"), s"key=${es.matchingKey}")

  // 4. Geographic hierarchy mismatch detection (Guangzhou is in Guangdong, NOT Zhejiang!)
  val badGeo = rulesPipeline.parseAddress("浙江省广州市天河区体育西路103号")
  assertCheck(
    "Geo Hierarchy Mismatch Detection",
    badGeo.parsed.geoValidation.exists(!_.isValid) && badGeo.warnings.exists(_.contains("Hierarchy mismatch")),
    s"warnings=${badGeo.warnings.mkString("; ")}"
  )

  // 5. Company legal forms (Prefix/Suffix/Either + Separate Listing Marker)
  val ptTbk = rulesPipeline.parseCompanyName("PT Astra International Tbk")
  assertCheck(
    "Indonesia Prefix PT + Listing Tbk",
    ptTbk.parsed.legalFormCanonical.contains("PT") &&
      ptTbk.parsed.legalFormPosition.contains(LegalFormPosition.Prefix) &&
      ptTbk.parsed.listingMarkerCanonical.contains("Tbk") &&
      ptTbk.parsed.coreName == "Astra International",
    s"key=${ptTbk.matchingKey}"
  )

  val jpPre = rulesPipeline.parseCompanyName("株式会社ソニー")
  val jpSuf = rulesPipeline.parseCompanyName("ソニー株式会社")
  assertCheck(
    "Japan Either Position (株式会社) Same Key",
    jpPre.parsed.legalFormCanonical.contains("KK") && jpPre.matchingKey == jpSuf.matchingKey,
    s"key=${jpPre.matchingKey}"
  )

  val ruComp = rulesPipeline.parseCompanyName("ООО \"Яндекс\"")
  assertCheck("Russia Prefix ООО", ruComp.parsed.legalFormCanonical.contains("OOO") && ruComp.parsed.coreName == "Яндекс", s"key=${ruComp.matchingKey}")

  val vnComp = rulesPipeline.parseCompanyName("Công ty TNHH FPT Software")
  assertCheck("Vietnam Prefix Công ty TNHH", vnComp.parsed.legalFormCanonical.contains("TNHH") && vnComp.parsed.coreName == "FPT Software", s"key=${vnComp.matchingKey}")

  // 6. Bank account checksums (IBAN Mod-97, US ABA 3-7-1, UK Sort Code, CNAPS, IFSC)
  val ibanOk  = rulesPipeline.parseBankAccount("DE89 3704 0044 0532 0130 00")
  val ibanBad = rulesPipeline.parseBankAccount("DE89 3704 0044 0532 0130 01")
  assertCheck("IBAN Valid Mod-97 (DE)", ibanOk.parsed.checksumValid.contains(true) && ibanOk.parsed.bankCode.contains("37040044"), s"key=${ibanOk.matchingKey}")
  assertCheck("IBAN Invalid Mod-97 Rejected", ibanBad.parsed.checksumValid.contains(false) && ibanBad.needsHumanReview, s"warnings=${ibanBad.warnings.mkString}")

  val abaOk = rulesPipeline.parseBankAccount("ABA 111000025 / 9876543210")
  assertCheck("US ABA Valid 3-7-1 Checksum", abaOk.parsed.checksumValid.contains(true) && abaOk.parsed.accountNumber.contains("9876543210"), s"key=${abaOk.matchingKey}")

  val ukBank = rulesPipeline.parseBankAccount("20-00-00 55779911")
  assertCheck("UK Sort Code + Account", ukBank.parsed.format == "UK_SORT_ACCOUNT" && ukBank.parsed.branchCode.contains("200000"), s"key=${ukBank.matchingKey}")

  val cnaps = rulesPipeline.parseBankAccount("CNAPS 102100099996 / 6222020200012345678")
  assertCheck("China CNAPS 12-Digit + Account", cnaps.parsed.format == "CN_CNAPS" && cnaps.parsed.bankCode.contains("102"), s"key=${cnaps.matchingKey}")

  // 7. Entity-type classification
  val clsComp = rulesPipeline.classifyEntity("Siemens AG", Some("Werner-von-Siemens-Straße 1, 80333 München"))
  val clsInd  = rulesPipeline.classifyEntity("Jimmy Shuhao 林", Some("Apt 12B, 500 Main St, San Francisco, CA 94105"))
  val clsSole = rulesPipeline.classifyEntity("Hans Müller e.K.", Some("Friedrichstraße 45, 10117 Berlin"))
  assertCheck("Classify Company (Siemens AG)", clsComp.label == EntityTypeLabel.Company, s"conf=${f"${clsComp.confidence}%.2f"}")
  assertCheck("Classify Individual (Diaspora CN)", clsInd.label == EntityTypeLabel.Individual, s"conf=${f"${clsInd.confidence}%.2f"}")
  assertCheck("Classify Sole Trader (German e.K.)", clsSole.label == EntityTypeLabel.SoleTraderOrUnknown, s"signals=${clsSole.signals.map(_.name).mkString(",")}")

  // --- Part 2: Hard-Case Benchmark (Rules-Only vs Rules+Gemma) ---
  println("\n=== PART 2: HARD-CASE BENCHMARK (RULES-ONLY vs RULES + GEMMA) ===")

  val hardCases: List[EvalCase] = List(
    EvalCase(
      "HC-01", "Address",
      "Fwd: 1600 Amphitheatre Pkwy, San Francsico, CA 94105 (Please deliver to reception)",
      Some("US"), None,
      p => {
        val r = p.parseAddress("Fwd: 1600 Amphitheatre Pkwy, San Francsico, CA 94105 (Please deliver to reception)", Some("US"))
        val ok = r.parsed.city.contains("San Francisco") && r.parsed.houseNumber.contains("1600") && r.parsed.postcode.contains("94105")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-02", "Address",
      "Attn: Leopoldstraße 28, 80802 MÃ¼nch3n (Urgent)",
      Some("DE"), None,
      p => {
        val r = p.parseAddress("Attn: Leopoldstraße 28, 80802 MÃ¼nch3n (Urgent)", Some("DE"))
        val ok = r.parsed.city.contains("München") && r.parsed.houseNumber.contains("28") && r.parsed.postcode.contains("80802")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-03", "Address",
      "广东省广洲市天河区体育西路103号 510620",
      Some("CN"), None,
      p => {
        val r = p.parseAddress("广东省广洲市天河区体育西路103号 510620", Some("CN"))
        val ok = r.parsed.city.contains("Guangzhou") && r.parsed.houseNumber.contains("103") && r.parsed.geoValidation.exists(_.isValid)
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-04", "Address",
      "Via Roma 42, 20121 Milano, Italy",
      Some("IT"), None,
      p => {
        val r = p.parseAddress("Via Roma 42, 20121 Milano, Italy", Some("IT"))
        val ok = r.parsed.city.contains("Milano") && r.parsed.street.contains("Via Roma") && r.parsed.houseNumber.contains("42")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-05", "Address",
      "Jl. Jend. Sudirman Kav. 52-53, Kebayoran Baru, Jakarta Selatan 12190",
      Some("ID"), None,
      p => {
        val r = p.parseAddress("Jl. Jend. Sudirman Kav. 52-53, Kebayoran Baru, Jakarta Selatan 12190", Some("ID"))
        val ok = r.parsed.city.contains("Jakarta Selatan") && r.parsed.houseNumber.contains("52-53") && r.parsed.postcode.contains("12190")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-06", "Address",
      "152 Teheran-ro, Gangnam-gu, Seoul 06236, South Korea",
      Some("KR"), None,
      p => {
        val r = p.parseAddress("152 Teheran-ro, Gangnam-gu, Seoul 06236, South Korea", Some("KR"))
        val ok = r.parsed.city.contains("Seoul") && r.parsed.street.contains("Teheran-ro") && r.parsed.houseNumber.contains("152")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-07", "PersonName",
      "Subramanian Venkataraman",
      Some("IN"), None,
      p => {
        val r = p.parsePersonName("Subramanian Venkataraman", Some("IN"))
        val ok = r.parsed.culture == NameCulture.SouthAsian && r.parsed.patronymic.contains("Venkataraman") && r.confidence >= 0.75
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-08", "CompanyName",
      "Finance Dept: Koninklijke Philips N.V. (Accounts Payable)",
      Some("NL"), None,
      p => {
        val r = p.parseCompanyName("Finance Dept: Koninklijke Philips N.V. (Accounts Payable)", Some("NL"))
        val ok = r.parsed.coreName == "Koninklijke Philips" && r.parsed.legalFormCanonical.contains("NV")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-09", "PersonName",
      "Attn: Jimmy Shuhao 林",
      Some("HK"), None,
      p => {
        val r = p.parsePersonName("Attn: Jimmy Shuhao 林", Some("HK"))
        val ok = r.parsed.englishName.contains("Jimmy") && r.parsed.givenName.contains("Shuhao") && r.parsed.familyName.contains("林")
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    ),
    EvalCase(
      "HC-10", "BankAccount",
      "DE89 3704 0044 0532 0130 00",
      Some("DE"), None,
      p => {
        val r = p.parseBankAccount("DE89 3704 0044 0532 0130 00", Some("DE"))
        val ok = r.parsed.checksumValid.contains(true) && r.provenance == Provenance.RuleBased
        (ok, r.matchingKey, r.confidence, r.provenance.code)
      }
    )
  )

  var rulesPass  = 0
  var hybridPass = 0

  println(f"${"ID"}%-6s | ${"Type"}%-11s | ${"Rules-Only"}%-10s | ${"Rules+Gemma"}%-11s | ${"Hybrid Provenance"}%-22s | Input")
  println("-" * 115)
  hardCases.foreach { c =>
    val (rOk, _, _, _)        = c.check(rulesPipeline)
    val (hOk, _, _, hProv)    = c.check(hybridPipeline)
    if (rOk) rulesPass += 1
    if (hOk) hybridPass += 1
    val rTag = if (rOk) "PASS" else "FAIL"
    val hTag = if (hOk) "PASS" else "FAIL"
    println(f"${c.id}%-6s | ${c.entityType}%-11s | $rTag%-10s | $hTag%-11s | $hProv%-22s | ${c.input.take(48)}")
  }

  println("-" * 115)
  println(f"SUMMARY: Rules-Only = $rulesPass/${hardCases.size} (${rulesPass * 100.0 / hardCases.size}%.1f%%) | Rules+Gemma = $hybridPass/${hardCases.size} (${hybridPass * 100.0 / hardCases.size}%.1f%%)")
}
