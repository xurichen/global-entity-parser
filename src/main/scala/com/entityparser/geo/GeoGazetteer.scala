package com.entityparser.geo

import java.io.File
import scala.io.Source
import com.entityparser.model.GeoValidation
import com.entityparser.norm.EntityNormalizer

/**
 * Global geographic reference hierarchy (Country -> Admin1 -> City) (§3.1).
 *
 * Ships with an embedded multi-region reference gazetteer for zero-setup execution
 * AND supports loading full GeoNames TSV dumps (`admin1CodesASCII.txt`, `cities15000.txt`)
 * at runtime for production global coverage.
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
  aliases: Set[String]
)

class GeoGazetteer(
  val admin1s: List[Admin1Record],
  val cities: List[CityRecord]
) {

  private val admin1ByCountryAndKey: Map[(String, String), Admin1Record] =
    admin1s.flatMap { r =>
      val keys = (r.aliases + r.canonicalName + r.admin1Code).map(k => EntityNormalizer.keyToken(k, r.countryCode == "DE"))
      keys.map(k => (r.countryCode, k) -> r)
    }.toMap

  private val admin1ByCode: Map[(String, String), Admin1Record] =
    admin1s.map(r => (r.countryCode, r.admin1Code) -> r).toMap

  private val citiesByCountryAndKey: Map[(String, String), List[CityRecord]] =
    cities.flatMap { c =>
      val keys = (c.aliases + c.canonicalName).map(k => EntityNormalizer.keyToken(k, c.countryCode == "DE"))
      keys.map(k => (c.countryCode, k) -> c)
    }.groupBy(_._1).view.mapValues(_.map(_._2).distinct).toMap

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
        val resolvedAdmin1 = provinceOpt.flatMap(lookupAdmin1(cc, _))
        if (provinceOpt.isDefined && resolvedAdmin1.isEmpty) {
          issues += s"Unrecognized province/state '${provinceOpt.get}' for country $cc"
        }

        // 3. Resolve City & check hierarchy consistency
        val matchedCities = cityOpt.map(lookupCity(cc, _)).getOrElse(Nil)
        val canonicalCity = (resolvedAdmin1, matchedCities) match {
          case (Some(a1), cs) if cs.nonEmpty =>
            cs.find(_.admin1Code == a1.admin1Code) match {
              case Some(validCity) => Some(validCity.canonicalName)
              case None =>
                val expectedAdmins = cs
                  .flatMap(c => admin1ByCode.get((cc, c.admin1Code)).map(_.canonicalName))
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
            admin1ByCode.get((cc, matchedCities.head.admin1Code)).map(_.canonicalName)
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
}

object GeoGazetteer {

  /** Embedded reference gazetteer covering major regions for offline prototype & unit testing. */
  lazy val default: GeoGazetteer = new GeoGazetteer(
    admin1s = List(
      // China
      Admin1Record("CN", "GD", "Guangdong",  Set("广东省", "广东", "Guangdong Province")),
      Admin1Record("CN", "BJ", "Beijing",    Set("北京市", "北京")),
      Admin1Record("CN", "SH", "Shanghai",   Set("上海市", "上海")),
      Admin1Record("CN", "ZJ", "Zhejiang",   Set("浙江省", "浙江", "Zhejiang Province")),
      Admin1Record("CN", "JS", "Jiangsu",    Set("江苏省", "江苏", "Jiangsu Province")),
      Admin1Record("CN", "SC", "Sichuan",    Set("四川省", "四川", "Sichuan Province")),
      // United States
      Admin1Record("US", "CA", "California", Set("CA", "Calif")),
      Admin1Record("US", "NY", "New York",   Set("NY")),
      Admin1Record("US", "TX", "Texas",      Set("TX")),
      Admin1Record("US", "WA", "Washington", Set("WA")),
      Admin1Record("US", "IL", "Illinois",   Set("IL")),
      // Germany
      Admin1Record("DE", "BE", "Berlin",              Set("Berlin")),
      Admin1Record("DE", "BY", "Bayern",              Set("Bavaria", "BY")),
      Admin1Record("DE", "NW", "Nordrhein-Westfalen", Set("NRW", "North Rhine-Westphalia")),
      Admin1Record("DE", "HH", "Hamburg",             Set("HH")),
      // Japan
      Admin1Record("JP", "13", "Tokyo",   Set("東京都", "東京", "Tokyo-to")),
      Admin1Record("JP", "27", "Osaka",   Set("大阪府", "大阪", "Osaka-fu")),
      // Indonesia
      Admin1Record("ID", "JK", "DKI Jakarta", Set("Jakarta", "DKI Jakarta", "Jakarta Raya")),
      Admin1Record("ID", "JB", "Jawa Barat",  Set("West Java")),
      // United Kingdom
      Admin1Record("GB", "ENG", "England", Set("England", "Greater London")),
      // Singapore / Hong Kong
      Admin1Record("SG", "SG", "Singapore", Set("Singapore")),
      Admin1Record("HK", "HK", "Hong Kong", Set("香港", "Hong Kong SAR"))
    ),
    cities = List(
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
      // Germany
      CityRecord("DE", "BE", "Berlin",  Set("Berlin")),
      CityRecord("DE", "BY", "München", Set("Munich", "Muenchen")),
      CityRecord("DE", "NW", "Köln",    Set("Cologne", "Koeln")),
      CityRecord("DE", "HH", "Hamburg", Set("Hamburg")),
      // Japan
      CityRecord("JP", "13", "Shibuya-ku", Set("渋谷区", "Shibuya", "Tokyo")),
      CityRecord("JP", "27", "Osaka",      Set("大阪市")),
      // Indonesia
      CityRecord("ID", "JK", "Jakarta Selatan", Set("South Jakarta", "Jakarta")),
      CityRecord("ID", "JB", "Bandung",         Set("Bandung")),
      // UK
      CityRecord("GB", "ENG", "London", Set("London")),
      // SG / HK
      CityRecord("SG", "SG", "Singapore", Set("Singapore")),
      CityRecord("HK", "HK", "Hong Kong", Set("香港", "Central"))
    )
  )

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
            Some(CityRecord(cc, a1, name, alts + ascii))
          } else None
        }.toList
      } finally src.close()
    } else Nil

    new GeoGazetteer(default.admin1s ++ loadedAdmin1, default.cities ++ loadedCities)
  }
}
