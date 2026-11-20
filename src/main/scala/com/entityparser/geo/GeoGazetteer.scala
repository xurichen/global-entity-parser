package com.entityparser.geo

import java.io.File
import scala.io.Source
import com.entityparser.model.GeoValidation
import com.entityparser.norm.EntityNormalizer

/**
 * Global geographic reference hierarchy (Country -> Admin1 -> City) (§3.1).
 *
 * Ships with an embedded multi-region reference gazetteer for zero-setup execution
 * AND loads global GeoNames data from classpath (`/geonames.tsv`) or disk at runtime.
 */
final case class Admin1Record(
  countryCode: String,
  admin1Code: String,
  canonicalName: String,
  aliases: Set[String]
)

final case class CityRecord(
  countryCode: String,
  admin1Code: String,
  canonicalName: String,
  aliases: Set[String],
  population: Long = 0L
)

/**
 * Gazetteer retrieval evidence: a matched city/admin1 record with its administrative path,
 * hit type (city vs admin1), population, cross-country ambiguity count (K), and whether
 * the match occurred via an alias rather than the canonical name.
 * Used as evidence in Stage 1 country candidate extraction.
 */
final case class PlaceEvidence(
  city: CityRecord,
  matchedName: String,
  matchedKey: String,
  isAdmin1: Boolean = false,
  population: Long = 0L,
  countryAmbiguity: Int = 1,
  matchedViaAlias: Boolean = false
)

class GeoGazetteer(
  val admin1s: List[Admin1Record],
  val cities: List[CityRecord],
  validationAdmin1s: List[Admin1Record] = null,
  validationCities: List[CityRecord] = null
) {

  private val admin1ByCountryAndKey: Map[(String, String), Admin1Record] =
    admin1s.flatMap { r =>
      val keys = (r.aliases + r.canonicalName + r.admin1Code)
        .map(k => EntityNormalizer.keyToken(k, r.countryCode == "DE"))
        .filter(_.nonEmpty)
      keys.map(k => (r.countryCode, k) -> r)
    }.toMap

  private val admin1ByCode: Map[(String, String), Admin1Record] =
    admin1s.map(r => (r.countryCode, r.admin1Code) -> r).toMap

  private val (
    citiesByCountryAndKey: Map[(String, String), List[CityRecord]],
    reversePlaceIndex: Map[String, List[CityRecord]]
  ) = {
    val byCountryKey = scala.collection.mutable.HashMap.empty[(String, String), scala.collection.mutable.ListBuffer[CityRecord]]
    val revIndex     = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ListBuffer[CityRecord]]

    cities.foreach { c =>
      val isDe = c.countryCode == "DE"
      val keys = (c.aliases + c.canonicalName)
        .map(k => EntityNormalizer.keyToken(k, isDe))
        .filter(_.nonEmpty)
      keys.foreach { k =>
        byCountryKey.getOrElseUpdate((c.countryCode, k), scala.collection.mutable.ListBuffer.empty) += c
        revIndex.getOrElseUpdate(k, scala.collection.mutable.ListBuffer.empty) += c
      }
    }

    (
      byCountryKey.view.mapValues(_.toList.distinct).toMap,
      revIndex.view.mapValues(_.toList.distinct).toMap
    )
  }

  // Validation view (reviewer salvage, round 8): validate() canonicalizes
  // against the curated embedded gazetteer only. The merged global GeoNames
  // data feeds candidate evidence (lookupPlace) but is not curated enough
  // for canonical substitution (numeric FIPS admin1 codes, "Makati City"
  // style canonical names would overwrite correct parser output).
  private val valAdmin1s: List[Admin1Record] = Option(validationAdmin1s).getOrElse(admin1s)
  private val valCities: List[CityRecord] = Option(validationCities).getOrElse(cities)

  private val valAdmin1ByCountryAndKey: Map[(String, String), Admin1Record] =
    valAdmin1s.flatMap { r =>
      val keys = (r.aliases + r.canonicalName + r.admin1Code)
        .map(k => EntityNormalizer.keyToken(k, r.countryCode == "DE"))
        .filter(_.nonEmpty)
      keys.map(k => (r.countryCode, k) -> r)
    }.toMap

  private val valAdmin1ByCode: Map[(String, String), Admin1Record] =
    valAdmin1s.map(r => (r.countryCode, r.admin1Code) -> r).toMap

  private val valCitiesByCountryAndKey: Map[(String, String), List[CityRecord]] = {
    val m = scala.collection.mutable.HashMap.empty[(String, String), scala.collection.mutable.ListBuffer[CityRecord]]
    valCities.foreach { c =>
      val keys = (c.aliases + c.canonicalName)
        .map(k => EntityNormalizer.keyToken(k, c.countryCode == "DE"))
        .filter(_.nonEmpty)
      keys.foreach { k =>
        m.getOrElseUpdate((c.countryCode, k), scala.collection.mutable.ListBuffer.empty) += c
      }
    }
    m.view.mapValues(_.toList.distinct).toMap
  }

  private def vLookupAdmin1(country: String, raw: String): Option[Admin1Record] = {
    val cc  = country.toUpperCase(java.util.Locale.ROOT)
    val key = EntityNormalizer.keyToken(raw, cc == "DE")
    if (key.isEmpty) None else valAdmin1ByCountryAndKey.get((cc, key))
  }

  private def vLookupCity(country: String, raw: String): List[CityRecord] = {
    val cc  = country.toUpperCase(java.util.Locale.ROOT)
    val key = EntityNormalizer.keyToken(raw, cc == "DE")
    if (key.isEmpty) Nil else valCitiesByCountryAndKey.getOrElse((cc, key), Nil)
  }

  /**
   * Reverse index for admin1 names (province/state level matches).
   * Purely numeric admin1 codes (e.g. "01", "13") are excluded from the global reverse index
   * to avoid false matches against street numbers and postcodes.
   */
  private val reverseAdmin1Index: Map[String, List[Admin1Record]] = {
    val revIndex = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ListBuffer[Admin1Record]]
    admin1s.foreach { a =>
      val isDe = a.countryCode == "DE"
      val rawNames =
        if (a.admin1Code.nonEmpty && !a.admin1Code.forall(_.isDigit)) a.aliases + a.canonicalName + a.admin1Code
        else a.aliases + a.canonicalName
      val keys = rawNames
        .map(k => EntityNormalizer.keyToken(k, isDe))
        .filter(_.nonEmpty)
      keys.foreach { k =>
        revIndex.getOrElseUpdate(k, scala.collection.mutable.ListBuffer.empty) += a
      }
    }
    revIndex.view.mapValues(_.toList.distinct).toMap
  }

  private val postcodePatterns: Map[String, scala.util.matching.Regex] = Map(
    "US" -> """^\d{5}(-\d{4})?$""".r,
    "CN" -> """^\d{6}$""".r,
    "DE" -> """^\d{5}$""".r,
    "GB" -> """^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$""".r,
    "JP" -> """^\d{3}-?\d{4}$""".r,
    "ID" -> """^\d{5}$""".r,
    "SG" -> """^\d{6}$""".r,
    "IN" -> """^\d{6}$""".r,
    "AU" -> """^\d{4}$""".r,
    "FR" -> """^\d{5}$""".r,
    "RU" -> """^\d{6}$""".r
  )

  def lookupAdmin1(country: String, raw: String): Option[Admin1Record] = {
    val cc  = country.toUpperCase(java.util.Locale.ROOT)
    val key = EntityNormalizer.keyToken(raw, cc == "DE")
    admin1ByCountryAndKey.get((cc, key))
  }

  def lookupCity(country: String, raw: String): List[CityRecord] = {
    val cc  = country.toUpperCase(java.util.Locale.ROOT)
    val key = EntityNormalizer.keyToken(raw, cc == "DE")
    citiesByCountryAndKey.getOrElse((cc, key), Nil)
  }

  private def buildPlaceEvidence(raw: String, key: String): List[PlaceEvidence] = {
    if (key.isEmpty) return Nil
    val cityMatches   = reversePlaceIndex.getOrElse(key, Nil)
    val admin1Matches = reverseAdmin1Index.getOrElse(key, Nil)
    if (cityMatches.isEmpty && admin1Matches.isEmpty) return Nil

    val distinctCountries =
      (cityMatches.iterator.map(_.countryCode) ++ admin1Matches.iterator.map(_.countryCode)).toSet
    val k = math.max(1, distinctCountries.size)

    val cityEvs = cityMatches.map { c =>
      val canonicalKey = EntityNormalizer.keyToken(c.canonicalName, c.countryCode == "DE")
      PlaceEvidence(
        city = c,
        matchedName = raw,
        matchedKey = key,
        isAdmin1 = false,
        population = c.population,
        countryAmbiguity = k,
        matchedViaAlias = key != canonicalKey
      )
    }
    val admin1Evs = admin1Matches.map { a1 =>
      val canonicalKey = EntityNormalizer.keyToken(a1.canonicalName, a1.countryCode == "DE")
      val asCity = CityRecord(a1.countryCode, a1.admin1Code, a1.canonicalName, a1.aliases, population = 0L)
      PlaceEvidence(
        city = asCity,
        matchedName = raw,
        matchedKey = key,
        isAdmin1 = true,
        population = 0L,
        countryAmbiguity = k,
        matchedViaAlias = key != canonicalKey
      )
    }
    cityEvs ++ admin1Evs
  }

  /**
   * RAG RETRIEVAL: Reverse lookup — place name → candidate PlaceEvidence across ALL countries.
   * Returns both city and admin1 hits with hit type, population, and cross-country ambiguity K.
   */
  def lookupPlace(raw: String): List[PlaceEvidence] = {
    val key   = EntityNormalizer.keyToken(raw, germanUmlautExpand = false)
    val keyDe = EntityNormalizer.keyToken(raw, germanUmlautExpand = true)
    if (keyDe != key) (buildPlaceEvidence(raw, key) ++ buildPlaceEvidence(raw, keyDe)).distinct
    else buildPlaceEvidence(raw, key)
  }

  /**
   * Lookup place by normalized key directly (for internal use).
   */
  def lookupPlaceByKey(key: String): List[PlaceEvidence] = {
    if (key.isEmpty) return Nil
    val cityMatches   = reversePlaceIndex.getOrElse(key, Nil)
    val admin1Matches = reverseAdmin1Index.getOrElse(key, Nil)
    if (cityMatches.isEmpty && admin1Matches.isEmpty) return Nil

    val distinctCountries =
      (cityMatches.iterator.map(_.countryCode) ++ admin1Matches.iterator.map(_.countryCode)).toSet
    val k = math.max(1, distinctCountries.size)

    val cityEvs = cityMatches.map { c =>
      val canonicalKey = EntityNormalizer.keyToken(c.canonicalName, c.countryCode == "DE")
      PlaceEvidence(
        city = c,
        matchedName = c.canonicalName,
        matchedKey = key,
        isAdmin1 = false,
        population = c.population,
        countryAmbiguity = k,
        matchedViaAlias = key != canonicalKey
      )
    }
    val admin1Evs = admin1Matches.map { a1 =>
      val canonicalKey = EntityNormalizer.keyToken(a1.canonicalName, a1.countryCode == "DE")
      val asCity = CityRecord(a1.countryCode, a1.admin1Code, a1.canonicalName, a1.aliases, population = 0L)
      PlaceEvidence(
        city = asCity,
        matchedName = a1.canonicalName,
        matchedKey = key,
        isAdmin1 = true,
        population = 0L,
        countryAmbiguity = k,
        matchedViaAlias = key != canonicalKey
      )
    }
    cityEvs ++ admin1Evs
  }

  /**
   * Validate parsed address administrative hierarchy:
   * - Resolves canonical Admin1 and City names.
   * - Detects when a known city does NOT belong to the stated province/state.
   * - Checks national postal code format where defined.
   */
  def validate(
    countryOpt: Option[String],
    provinceOpt: Option[String],
    cityOpt: Option[String],
    postcodeOpt: Option[String]
  ): GeoValidation = {
    countryOpt match {
      case None =>
        GeoValidation(isValid = false, None, None, None, List("Missing ISO country code"))

      case Some(rawCc) =>
        val cc     = rawCc.toUpperCase(java.util.Locale.ROOT)
        val issues = scala.collection.mutable.ListBuffer.empty[String]

        // 1. Validate postcode syntax if present
        postcodeOpt.foreach { pc =>
          val normPc = pc.trim.toUpperCase(java.util.Locale.ROOT)
          postcodePatterns.get(cc).foreach { regex =>
            if (regex.findFirstIn(normPc).isEmpty) {
              issues += s"Postcode '$pc' does not match $cc national format"
            }
          }
        }

        // 2. Resolve Admin1
        val resolvedAdmin1 = provinceOpt.flatMap(vLookupAdmin1(cc, _))
        if (provinceOpt.isDefined && resolvedAdmin1.isEmpty) {
          issues += s"Unrecognized province/state '${provinceOpt.get}' for country $cc"
        }

        // 3. Resolve City & check hierarchy consistency
        val matchedCities = cityOpt.map(vLookupCity(cc, _)).getOrElse(Nil)
        val canonicalCity = (resolvedAdmin1, matchedCities) match {
          case (Some(a1), cs) if cs.nonEmpty =>
            cs.find(_.admin1Code == a1.admin1Code) match {
              case Some(validCity) => Some(validCity.canonicalName)
              case None =>
                val expectedAdmins = cs
                  .flatMap(c => valAdmin1ByCode.get((cc, c.admin1Code)).map(_.canonicalName))
                  .distinct
                  .mkString(", ")
                issues += s"Hierarchy mismatch: city '${cityOpt.get}' does not belong to '${a1.canonicalName}' (expected: $expectedAdmins)"
                Some(cs.head.canonicalName)
            }
          case (None, cs) if cs.nonEmpty =>
            Some(cs.head.canonicalName)
          case _ =>
            cityOpt
        }

        // Infer canonical Admin1 from unambiguous city if province was omitted
        val inferredAdmin1 = resolvedAdmin1.map(_.canonicalName).orElse {
          if (matchedCities.map(_.admin1Code).distinct.size == 1)
            valAdmin1ByCode.get((cc, matchedCities.head.admin1Code)).map(_.canonicalName)
          else None
        }

        GeoValidation(
          isValid = issues.isEmpty,
          countryCode = Some(cc),
          canonicalAdmin1 = inferredAdmin1.orElse(provinceOpt),
          canonicalCity = canonicalCity,
          issues = issues.toList
        )
    }
  }

  /**
   * Get all unique country codes present in the gazetteer.
   */
  def allCountryCodes: Set[String] = {
    (admin1s.map(_.countryCode) ++ cities.map(_.countryCode)).toSet
  }
}

object GeoGazetteer {

  private val embeddedAdmin1s: List[Admin1Record] = List(
    // China
    Admin1Record("CN", "GD", "Guangdong",  Set("广东省", "广东", "Guangdong Province")),
    Admin1Record("CN", "BJ", "Beijing",    Set("北京市", "北京")),
    Admin1Record("CN", "SH", "Shanghai",   Set("上海市", "上海")),
    Admin1Record("CN", "ZJ", "Zhejiang",   Set("浙江省", "浙江", "Zhejiang Province")),
    Admin1Record("CN", "JS", "Jiangsu",    Set("江苏省", "江苏", "Jiangsu Province")),
    Admin1Record("CN", "SC", "Sichuan",    Set("四川省", "四川", "Sichuan Province")),
    Admin1Record("CN", "HB", "Hubei",      Set("湖北省", "湖北", "Hubei Province")),
    Admin1Record("CN", "HN", "Hunan",      Set("湖南省", "湖南", "Hunan Province")),
    Admin1Record("CN", "HA", "Henan",      Set("河南省", "河南", "Henan Province")),
    Admin1Record("CN", "SN", "Shaanxi",    Set("陕西省", "陕西", "Shaanxi Province")),
    Admin1Record("CN", "SX", "Shanxi",     Set("山西省", "山西", "Shanxi Province")),
    Admin1Record("CN", "SD", "Shandong",   Set("山东省", "山东", "Shandong Province")),
    Admin1Record("CN", "LN", "Liaoning",   Set("辽宁省", "辽宁", "Liaoning Province")),
    Admin1Record("CN", "JL", "Jilin",      Set("吉林省", "吉林", "Jilin Province")),
    Admin1Record("CN", "HL", "Heilongjiang", Set("黑龙江省", "黑龙江", "Heilongjiang Province")),
    Admin1Record("CN", "NM", "Inner Mongolia", Set("内蒙古自治区", "内蒙古", "Inner Mongolia")),
    Admin1Record("CN", "GX", "Guangxi",    Set("广西壮族自治区", "广西", "Guangxi")),
    Admin1Record("CN", "YN", "Yunnan",     Set("云南省", "云南", "Yunnan Province")),
    Admin1Record("CN", "GZ", "Guizhou",    Set("贵州省", "贵州", "Guizhou Province")),
    Admin1Record("CN", "FJ", "Fujian",     Set("福建省", "福建", "Fujian Province")),
    Admin1Record("CN", "AH", "Anhui",      Set("安徽省", "安徽", "Anhui Province")),
    Admin1Record("CN", "JX", "Jiangxi",    Set("江西省", "江西", "Jiangxi Province")),
    Admin1Record("CN", "HI", "Hainan",     Set("海南省", "海南", "Hainan Province")),
    Admin1Record("CN", "XJ", "Xinjiang",   Set("新疆维吾尔自治区", "新疆", "Xinjiang")),
    Admin1Record("CN", "XZ", "Tibet",      Set("西藏自治区", "西藏", "Tibet")),
    Admin1Record("CN", "QH", "Qinghai",    Set("青海省", "青海", "Qinghai Province")),
    Admin1Record("CN", "GS", "Gansu",      Set("甘肃省", "甘肃", "Gansu Province")),
    Admin1Record("CN", "NX", "Ningxia",    Set("宁夏回族自治区", "宁夏", "Ningxia")),
    Admin1Record("CN", "TW", "Taiwan",     Set("台湾省", "台湾", "Taiwan Province")),
    Admin1Record("CN", "HK", "Hong Kong",  Set("香港特别行政区", "香港", "Hong Kong SAR")),
    Admin1Record("CN", "MO", "Macau",      Set("澳门特别行政区", "澳门", "Macau SAR")),
    // United States
    Admin1Record("US", "CA", "California", Set("CA", "Calif")),
    Admin1Record("US", "NY", "New York",   Set("NY")),
    Admin1Record("US", "TX", "Texas",      Set("TX")),
    Admin1Record("US", "WA", "Washington", Set("WA")),
    Admin1Record("US", "IL", "Illinois",   Set("IL")),
    Admin1Record("US", "VA", "virginia",   Set("VA", "Virg", "virginia")),
    Admin1Record("US", "MD", "maryland",   Set("MD", "maryland")),
    Admin1Record("US", "GA", "georgia",    Set("GA", "georgia")),
    Admin1Record("US", "FL", "florida",    Set("FL", "florida")),
    Admin1Record("US", "NC", "north carolina", Set("NC", "north carolina")),
    Admin1Record("US", "TN", "tennessee",  Set("TN", "tennessee")),
    Admin1Record("US", "CO", "colorado",   Set("CO", "colorado")),
    Admin1Record("US", "AZ", "arizona",    Set("AZ", "arizona")),
    Admin1Record("US", "MA", "massachusetts", Set("MA", "massachusetts")),
    Admin1Record("US", "NJ", "new jersey", Set("NJ", "new jersey")),
    Admin1Record("US", "PA", "pennsylvania", Set("PA", "pennsylvania")),
    Admin1Record("US", "OH", "ohio",       Set("OH", "ohio")),
    Admin1Record("US", "MI", "michigan",   Set("MI", "michigan")),
    Admin1Record("US", "IN", "indiana",    Set("IN", "indiana")),
    Admin1Record("US", "MO", "missouri",   Set("MO", "missouri")),
    Admin1Record("US", "WI", "wisconsin",  Set("WI", "wisconsin")),
    Admin1Record("US", "MN", "minnesota",  Set("MN", "minnesota")),
    // Germany
    Admin1Record("DE", "BE", "Berlin",              Set("Berlin")),
    Admin1Record("DE", "BY", "Bayern",              Set("Bavaria", "BY")),
    Admin1Record("DE", "NW", "Nordrhein-Westfalen", Set("NRW", "North Rhine-Westphalia")),
    Admin1Record("DE", "HH", "Hamburg",             Set("HH")),
    // Japan
    Admin1Record("JP", "13", "Tokyo",   Set("東京都", "東京", "Tokyo-to")),
    Admin1Record("JP", "27", "Osaka",   Set("大阪府", "大阪", "Osaka-fu")),
    // India
    Admin1Record("IN", "UP", "Uttar Pradesh", Set("Uttar Pradesh", "UP")),
    Admin1Record("IN", "MH", "Maharashtra", Set("Maharashtra", "MH")),
    Admin1Record("IN", "TN", "Tamil Nadu", Set("Tamil Nadu", "TN")),
    Admin1Record("IN", "KA", "Karnataka", Set("Karnataka", "KA")),
    Admin1Record("IN", "GJ", "Gujarat", Set("Gujarat", "GJ")),
    Admin1Record("IN", "WB", "West Bengal", Set("West Bengal", "WB")),
    Admin1Record("IN", "RJ", "Rajasthan", Set("Rajasthan", "RJ")),
    Admin1Record("IN", "AP", "Andhra Pradesh", Set("Andhra Pradesh", "AP")),
    Admin1Record("IN", "TG", "Telangana", Set("Telangana", "TG")),
    Admin1Record("IN", "KL", "Kerala", Set("Kerala", "KL")),
    Admin1Record("IN", "MP", "Madhya Pradesh", Set("Madhya Pradesh", "MP")),
    Admin1Record("IN", "PB", "Punjab", Set("Punjab", "PB")),
    Admin1Record("IN", "HR", "Haryana", Set("Haryana", "HR")),
    Admin1Record("IN", "BR", "Bihar", Set("Bihar", "BR")),
    Admin1Record("IN", "OR", "Odisha", Set("Odisha", "OR")),
    Admin1Record("IN", "JH", "Jharkhand", Set("Jharkhand", "JH")),
    Admin1Record("IN", "AS", "Assam", Set("Assam", "AS")),
    Admin1Record("IN", "CG", "Chhattisgarh", Set("Chhattisgarh", "CG")),
    Admin1Record("IN", "UT", "Uttarakhand", Set("Uttarakhand", "UT")),
    Admin1Record("IN", "HP", "Himachal Pradesh", Set("Himachal Pradesh", "HP")),
    Admin1Record("IN", "GA", "Goa", Set("Goa", "GA")),
    Admin1Record("IN", "DL", "Delhi", Set("Delhi", "DL")),
    Admin1Record("IN", "JK", "Jammu and Kashmir", Set("Jammu and Kashmir", "JK")),
    Admin1Record("IN", "LA", "Ladakh", Set("Ladakh", "LA")),
    // Indonesia
    Admin1Record("ID", "JK", "DKI Jakarta", Set("Jakarta", "DKI Jakarta", "Jakarta Raya")),
    Admin1Record("ID", "JB", "Jawa Barat",  Set("West Java")),
    // United Kingdom
    Admin1Record("GB", "ENG", "England", Set("England", "Greater London")),
    // Singapore / Hong Kong
    Admin1Record("SG", "SG", "Singapore", Set("Singapore")),
    Admin1Record("HK", "HK", "Hong Kong", Set("香港", "Hong Kong SAR"))
  )

  private val embeddedCities: List[CityRecord] = List(
    // China
    CityRecord("CN", "GD", "Guangzhou", Set("广州市", "广州", "Canton")),
    CityRecord("CN", "GD", "Shenzhen",  Set("深圳市", "深圳")),
    CityRecord("CN", "ZJ", "Hangzhou",  Set("杭州市", "杭州")),
    CityRecord("CN", "JS", "Nanjing",   Set("南京市", "南京")),
    CityRecord("CN", "JS", "Suzhou",    Set("苏州市", "苏州")),
    CityRecord("CN", "SC", "Chengdu",   Set("成都市", "成都")),
    CityRecord("CN", "BJ", "Beijing",   Set("北京市", "北京")),
    CityRecord("CN", "SH", "Shanghai",  Set("上海市", "上海")),
    // US
    CityRecord("US", "CA", "San Francisco", Set("SF")),
    CityRecord("US", "CA", "Los Angeles",   Set("LA")),
    CityRecord("US", "CA", "Mountain View", Set("Mountain View")),
    CityRecord("US", "NY", "New York",      Set("NYC", "New York City", "Manhattan")),
    CityRecord("US", "TX", "Austin",        Set("Austin")),
    CityRecord("US", "WA", "Seattle",       Set("Seattle")),
    CityRecord("US", "IL", "Chicago",       Set("Chicago")),
    CityRecord("US", "VA", "Arlington",     Set("Arlington")),
    CityRecord("US", "VA", "Alexandria",    Set("Alexandria")),
    CityRecord("US", "MD", "Baltimore",     Set("Baltimore")),
    CityRecord("US", "GA", "Atlanta",       Set("Atlanta")),
    CityRecord("US", "FL", "Miami",         Set("Miami")),
    CityRecord("US", "NC", "Charlotte",     Set("Charlotte")),
    CityRecord("US", "TN", "Nashville",     Set("Nashville")),
    CityRecord("US", "CO", "Denver",        Set("Denver")),
    CityRecord("US", "AZ", "Phoenix",       Set("Phoenix")),
    CityRecord("US", "MA", "Boston",        Set("Boston")),
    CityRecord("US", "NJ", "Newark",        Set("Newark")),
    CityRecord("US", "PA", "Philadelphia",  Set("Philadelphia")),
    CityRecord("US", "OH", "Columbus",      Set("Columbus")),
    CityRecord("US", "MI", "Detroit",       Set("Detroit")),
    CityRecord("US", "IN", "Indianapolis",  Set("Indianapolis")),
    CityRecord("US", "MO", "Kansas City",   Set("Kansas City")),
    CityRecord("US", "WI", "Milwaukee",     Set("Milwaukee")),
    CityRecord("US", "MN", "Minneapolis",   Set("Minneapolis")),
    // Germany
    CityRecord("DE", "BE", "Berlin",  Set("Berlin")),
    CityRecord("DE", "BY", "München", Set("Munich", "Muenchen")),
    CityRecord("DE", "NW", "Köln",    Set("Cologne", "Koeln")),
    CityRecord("DE", "HH", "Hamburg", Set("Hamburg")),
    // Japan
    CityRecord("JP", "13", "Shibuya-ku", Set("渋谷区", "Shibuya", "Tokyo")),
    CityRecord("JP", "27", "Osaka",      Set("大阪市")),
    // India
    CityRecord("IN", "UP", "Noida",         Set("Noida")),
    CityRecord("IN", "UP", "Lucknow",       Set("Lucknow")),
    CityRecord("IN", "UP", "Kanpur",        Set("Kanpur")),
    CityRecord("IN", "UP", "Agra",          Set("Agra")),
    CityRecord("IN", "UP", "Varanasi",      Set("Varanasi")),
    CityRecord("IN", "MH", "Mumbai",        Set("Bombay", "Mumbai")),
    CityRecord("IN", "MH", "Pune",          Set("Pune")),
    CityRecord("IN", "MH", "Nagpur",        Set("Nagpur")),
    CityRecord("IN", "TN", "Chennai",       Set("Madras", "Chennai")),
    CityRecord("IN", "TN", "Coimbatore",    Set("Coimbatore")),
    CityRecord("IN", "KA", "Bengaluru",     Set("Bangalore", "Bengaluru")),
    CityRecord("IN", "KA", "Mysuru",        Set("Mysore", "Mysuru")),
    CityRecord("IN", "GJ", "Ahmedabad",     Set("Ahmedabad")),
    CityRecord("IN", "GJ", "Surat",         Set("Surat")),
    CityRecord("IN", "WB", "Kolkata",       Set("Calcutta", "Kolkata")),
    CityRecord("IN", "RJ", "Jaipur",        Set("Jaipur")),
    CityRecord("IN", "AP", "Visakhapatnam", Set("Visakhapatnam")),
    CityRecord("IN", "TG", "Hyderabad",     Set("Hyderabad")),
    CityRecord("IN", "KL", "Thiruvananthapuram", Set("Trivandrum", "Thiruvananthapuram")),
    CityRecord("IN", "MP", "Bhopal",        Set("Bhopal")),
    CityRecord("IN", "MP", "Indore",        Set("Indore")),
    CityRecord("IN", "PB", "Ludhiana",      Set("Ludhiana")),
    CityRecord("IN", "HR", "Gurugram",      Set("Gurgaon", "Gurugram")),
    CityRecord("IN", "HR", "Faridabad",     Set("Faridabad")),
    CityRecord("IN", "BR", "Patna",         Set("Patna")),
    CityRecord("IN", "OR", "Bhubaneswar",   Set("Bhubaneswar")),
    CityRecord("IN", "JH", "Ranchi",        Set("Ranchi")),
    CityRecord("IN", "AS", "Guwahati",      Set("Guwahati")),
    CityRecord("IN", "CG", "Raipur",        Set("Raipur")),
    CityRecord("IN", "UT", "Dehradun",      Set("Dehradun")),
    CityRecord("IN", "HP", "Shimla",        Set("Shimla")),
    CityRecord("IN", "GA", "Panaji",        Set("Panaji")),
    CityRecord("IN", "DL", "New Delhi",     Set("New Delhi", "Delhi")),
    CityRecord("IN", "JK", "Srinagar",      Set("Srinagar")),
    CityRecord("IN", "LA", "Leh",           Set("Leh")),
    // Indonesia
    CityRecord("ID", "JK", "Jakarta Selatan", Set("South Jakarta", "Jakarta")),
    CityRecord("ID", "JB", "Bandung",         Set("Bandung")),
    // UK
    CityRecord("GB", "ENG", "London", Set("London")),
    // SG / HK
    CityRecord("SG", "SG", "Singapore", Set("Singapore")),
    CityRecord("HK", "HK", "Hong Kong", Set("香港", "Central"))
  )

  /**
   * Load compact GeoNames TSV from classpath (`/geonames.tsv`).
   * Formats:
   * - A1<TAB>countryCode<TAB>admin1Code<TAB>canonicalName<TAB>alias1|alias2
   * - CT<TAB>countryCode<TAB>admin1Code<TAB>canonicalName<TAB>population<TAB>alias1|alias2
   * Fault-tolerant: malformed lines are skipped; missing resource degrades gracefully to Nil.
   */
  private def loadFromClasspath(): (List[Admin1Record], List[CityRecord]) = {
    val stream = getClass.getResourceAsStream("/geonames.tsv")
    if (stream == null) return (Nil, Nil)

    val a1Buf = scala.collection.mutable.ListBuffer.empty[Admin1Record]
    val ctBuf = scala.collection.mutable.ListBuffer.empty[CityRecord]
    val src   = Source.fromInputStream(stream, "UTF-8")
    try {
      src.getLines().foreach { rawLine =>
        try {
          if (rawLine.trim.nonEmpty) {
            val cols = rawLine.split("\t", -1)
            cols.headOption.map(_.trim) match {
              case Some("A1") if cols.length >= 4 =>
                val cc   = cols(1).trim.toUpperCase(java.util.Locale.ROOT)
                val code = cols(2).trim
                val name = cols(3).trim
                if (cc.length == 2 && name.nonEmpty) {
                  val aliases =
                    if (cols.length >= 5 && cols(4).trim.nonEmpty)
                      cols(4).split("\\|").iterator.map(_.trim).filter(_.nonEmpty).toSet
                    else Set.empty[String]
                  a1Buf += Admin1Record(cc, code, name, aliases)
                }

              case Some("CT") if cols.length >= 5 =>
                val cc     = cols(1).trim.toUpperCase(java.util.Locale.ROOT)
                val code   = cols(2).trim
                val name   = cols(3).trim
                val popOpt = cols(4).trim.toLongOption
                if (cc.length == 2 && name.nonEmpty && popOpt.isDefined) {
                  val aliases =
                    if (cols.length >= 6 && cols(5).trim.nonEmpty)
                      cols(5).split("\\|").iterator.map(_.trim).filter(_.nonEmpty).toSet
                    else Set.empty[String]
                  ctBuf += CityRecord(cc, code, name, aliases, popOpt.get)
                }

              case _ => () // skip malformed or unknown rows
            }
          }
        } catch {
          case _: Throwable => () // skip bad line
        }
      }
    } catch {
      case _: Throwable => ()
    } finally {
      src.close()
    }
    (a1Buf.toList, ctBuf.toList)
  }

  /**
   * Default gazetteer: loads `/geonames.tsv` from classpath if present and merges with
   * embedded records (deduplicated by (countryCode, canonicalName)); degrades to embedded-only
   * when the classpath resource is unavailable.
   */
  lazy val default: GeoGazetteer = {
    val (tsvAdmin1s, tsvCities) = loadFromClasspath()

    // Deduplicate Admin1 by (countryCode, canonicalName), keeping embedded definitions intact
    val seenA1 = scala.collection.mutable.HashSet.from(
      embeddedAdmin1s.map(r => (r.countryCode, r.canonicalName.toLowerCase(java.util.Locale.ROOT)))
    )
    val extraA1s = tsvAdmin1s.filter { r =>
      val key = (r.countryCode, r.canonicalName.toLowerCase(java.util.Locale.ROOT))
      if (seenA1.contains(key)) false
      else {
        seenA1 += key
        true
      }
    }

    // Index TSV cities by (countryCode, canonicalName) to enrich embedded cities with population & aliases
    val tsvCityBestByKey = scala.collection.mutable.HashMap.empty[(String, String), CityRecord]
    tsvCities.foreach { c =>
      val key = (c.countryCode, c.canonicalName.toLowerCase(java.util.Locale.ROOT))
      tsvCityBestByKey.get(key) match {
        case Some(existing) if existing.population >= c.population => ()
        case _ => tsvCityBestByKey.put(key, c)
      }
    }

    val enrichedEmbeddedCities = embeddedCities.map { c =>
      val key = (c.countryCode, c.canonicalName.toLowerCase(java.util.Locale.ROOT))
      tsvCityBestByKey.get(key) match {
        case Some(tsv) =>
          c.copy(
            aliases = c.aliases ++ tsv.aliases,
            population = math.max(c.population, tsv.population)
          )
        case None => c
      }
    }

    val seenCity = scala.collection.mutable.HashSet.from(
      embeddedCities.map(c => (c.countryCode, c.canonicalName.toLowerCase(java.util.Locale.ROOT)))
    )
    val extraCities = tsvCities.filter { c =>
      val key = (c.countryCode, c.canonicalName.toLowerCase(java.util.Locale.ROOT))
      if (seenCity.contains(key)) false
      else {
        seenCity += key
        true
      }
    }

    // Place extraA1s before embeddedAdmin1s so embedded records win in admin1ByCountryAndKey/admin1ByCode;
    // place enrichedEmbeddedCities before extraCities so embedded records remain head in citiesByCountryAndKey.
    new GeoGazetteer(extraA1s ++ embeddedAdmin1s, enrichedEmbeddedCities ++ extraCities, embeddedAdmin1s, embeddedCities)
  }

  /**
   * Load full GeoNames dumps (`admin1CodesASCII.txt` and `cities15000.txt`) from disk
   * and merge them with the embedded aliases.
   */
  def loadFromGeoNames(admin1File: File, citiesFile: File): GeoGazetteer = {
    val loadedAdmin1 = if (admin1File.exists()) {
      val src = Source.fromFile(admin1File, "UTF-8")
      try {
        src.getLines().flatMap { line =>
          val cols = line.split("\t")
          if (cols.length >= 3 && cols(0).contains(".")) {
            val parts = cols(0).split("\\.", 2)
            Some(Admin1Record(parts(0), parts(1), cols(2), Set(cols(1), cols(2))))
          } else None
        }.toList
      } finally src.close()
    } else Nil

    val loadedCities = if (citiesFile.exists()) {
      val src = Source.fromFile(citiesFile, "UTF-8")
      try {
        src.getLines().flatMap { line =>
          val cols = line.split("\t")
          if (cols.length >= 11) {
            val cc     = cols(8)
            val a1     = cols(10)
            val name   = cols(1)
            val ascii  = cols(2)
            val alts   = cols(3).split(",").filter(_.nonEmpty).take(8).toSet
            val pop    = if (cols.length >= 15) cols(14).trim.toLongOption.getOrElse(0L) else 0L
            Some(CityRecord(cc, a1, name, alts + ascii, pop))
          } else None
        }.toList
      } finally src.close()
    } else Nil

    new GeoGazetteer(default.admin1s ++ loadedAdmin1, default.cities ++ loadedCities)
  }
}
