# Global Entity Parsing & Normalization (Scala 2.13 Prototype)

A zero-heavy-framework **Scala 2.13** hybrid prototype for parsing, validating, normalizing, and classifying global entities:

1. **Postal Addresses** (country-aware component order, house-number resolution, GeoNames-compatible administrative hierarchy validation)
2. **Person Names** (cultural conventions including Diaspora Chinese `english_name` / `given_name` / `family_name`, compound Chinese surnames, Japanese, Korean, Spanish double surnames, Arabic patronymic chains, Russian patronymics, Icelandic patronymics, Indonesian mononyms, Western)
3. **Bank Account Identifiers** (IBAN ISO 7064 Mod-97-10 + per-country BBAN split, US ABA 3-7-1 checksum, UK Sort Code + Account, China CNAPS, SWIFT/BIC ISO 9362, Indian IFSC, Australian BSB, Japanese bank/branch)
4. **Company / Business Names** (per-country prefix/suffix/either legal forms + separate listing markers such as `Tbk`, `PLC`, `Bhd`)
5. **Entity-Type Classification** (`company` / `individual` / `sole_trader_or_unknown` with auditable evidence signals)

## Requirements

- **JDK 11+** (tested against Java 17 standard library `java.net.http.HttpClient`)
- **sbt 1.10+** (compiles with **Scala 2.13.15**, zero external JAR dependencies)
- **Optional:** [`llama.cpp`](https://github.com/ggml-org/llama.cpp) (`llama-server` or `llama-cli`) with a local Gemma GGUF model (e.g. `gemma-3-1b-it-Q4_K_M.gguf` or `gemma-2-2b-it-Q4_K_M.gguf`). Without a model, the pipeline degrades gracefully to 100% deterministic rules + dictionaries.

## Quick Start

### 1. Run Offline Unit Checks & Hard-Case Evaluation (Simulated Gemma vs. Rules-Only)

```bash
sbt run
```

### 2. Run Against a Live Local `llama.cpp` Server (Gemma GGUF)

Start `llama-server` locally:

```bash
llama-server -m /path/to/gemma-3-1b-it-Q4_K_M.gguf --port 8080 --ctx-size 4096
```

Run the Scala 2.13 evaluation against the live server:

```bash
LLAMA_SERVER_URL=http://127.0.0.1:8080 GEMMA_MODEL=gemma-3-1b-it-Q4_K_M.gguf sbt run
```

### 3. Optional: Load Full GeoNames Administrative Reference Dumps

Download `admin1CodesASCII.txt` and `cities15000.zip` (unzipped) from [download.geonames.org/export/dump/](http://download.geonames.org/export/dump/) and initialize:

```scala
import java.io.File
import com.entityparser.geo.GeoGazetteer
import com.entityparser.pipeline.EntityPipeline

val gazetteer = GeoGazetteer.loadFromGeoNames(
  new File("/data/geonames/admin1CodesASCII.txt"),
  new File("/data/geonames/cities15000.txt")
)
val pipeline = EntityPipeline.withLlamaServer(gazetteer = gazetteer)
```
