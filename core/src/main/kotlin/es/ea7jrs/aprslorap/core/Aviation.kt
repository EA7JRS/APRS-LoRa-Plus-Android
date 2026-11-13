/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Aviation weather from the NOAA Aviation Weather Center data API (aviationweather.gov, public domain, 100 requests a
 * minute): METAR and TAF of the airports in a box and international SIGMETs, for the map layers. Same rules and test cases
 * as `aviation` of the plotter core.
 */
object Aviation {
    enum class FlightCat(val label: String, val color: Long) {
        UNKNOWN("", 0xFF9DB2C7), VFR("VFR", 0xFF2DBE6C), MVFR("MVFR", 0xFF2F7DE1), IFR("IFR", 0xFFE53935), LIFR("LIFR", 0xFFB03CD8)
    }

    /** FAA flight category from the ceiling (BKN / OVC / OVX or vertical visibility, feet; < 0 none) and visibility (statute miles; < 0 unknown). */
    fun flightCategory(ceilingFt: Int, visibSm: Double): FlightCat {
        if (ceilingFt < 0 && visibSm < 0) return FlightCat.UNKNOWN
        val c = when {
            ceilingFt < 0 -> FlightCat.VFR; ceilingFt < 500 -> FlightCat.LIFR; ceilingFt < 1000 -> FlightCat.IFR
            ceilingFt <= 3000 -> FlightCat.MVFR; else -> FlightCat.VFR
        }
        val v = when {
            visibSm < 0 -> FlightCat.VFR; visibSm < 1 -> FlightCat.LIFR; visibSm < 3 -> FlightCat.IFR
            visibSm <= 5 -> FlightCat.MVFR; else -> FlightCat.VFR
        }
        return if (c.ordinal > v.ordinal) c else v
    }

    data class Cloud(val cover: String, val baseFt: Int)

    data class Metar(
        val icao: String, val name: String, val raw: String, val lat: Double, val lon: Double, val obsTime: Long,
        val wdir: Int,                  // degrees; -1 none, -2 variable
        val wspdKt: Int, val wgstKt: Int,
        val tempC: Double?, val dewpC: Double?, val qnhHpa: Double?,
        val visibSm: Double, val visibPlus: Boolean, val wx: String, val clouds: List<Cloud>, val vertVisFt: Int,
        val ceilingFt: Int, val cat: FlightCat,
    )

    data class Taf(val icao: String, val raw: String, val issueTime: Long)

    data class Sigmet(
        val id: String, val fir: String, val firName: String, val hazard: String, val qualifier: String, val raw: String,
        val baseFt: Int, val topFt: Int, val from: Long, val to: Long, val dir: String, val spdKt: Int,
        val points: List<Pair<Double, Double>>,
    )

    const val BASE = "https://aviationweather.gov/api/data/"

    private fun box(what: String, a: Double, b: Double, c: Double, d: Double) =
        BASE + what + String.format(Locale.US, "?bbox=%.2f,%.2f,%.2f,%.2f&format=json", a, b, c, d)

    /** Box of the API: latMin, lonMin, latMax, lonMax. */
    fun metarUrl(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double) = box("metar", latMin, lonMin, latMax, lonMax)
    fun tafUrl(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double) = box("taf", latMin, lonMin, latMax, lonMax)
    fun sigmetUrl(loc: String = "all") = BASE + "isigmet?format=geojson&loc=" + loc

    /** Region of the API for a position. */
    fun sigmetRegion(lat: Double, lon: Double) = when {
        lat > 25 && lon > -40 && lon < 50 -> "eur"
        lat <= 25 && lat > -40 && lon > -25 && lon < 55 -> "afr"
        lat > 10 && lon <= -40 && lon > -170 -> "namer"
        else -> "all"
    }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.trim()?.takeIf { it.isNotEmpty() && (it[0].isDigit() || it[0] == '-') }?.toDoubleOrNull()
    private fun int(v: Any?, fb: Int) = num(v)?.roundToInt() ?: fb
    private fun str(v: Any?) = when (v) { null -> ""; is String -> v; is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString(); else -> v.toString() }
    private fun time(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> if (v.isNotEmpty() && v.all { it.isDigit() }) v.toLong() else OfficialAlerts.parseIso(v)
        else -> 0
    }

    /** Visibility group of the raw report (4 digits in metres, or CAVOK): more exact than the API's miles in Europe. */
    fun rawVisibilityM(raw: String): Int {
        val t = raw.trim().split(Regex("\\s+"))
        for (i in 2 until t.size) {
            val w = t[i]
            if (w == "CAVOK") return 9999
            if (w == "RMK" || w == "TEMPO" || w == "BECMG" || w == "NOSIG") break
            if (w.length == 4 && w.all { it.isDigit() }) return w.toInt()
            if (w.endsWith("SM")) return -1
        }
        return -1
    }

    fun parseMetars(json: String): List<Metar>? {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return null
        return arr.mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            val icao = str(o["icaoId"]); val lat = num(o["lat"]); val lon = num(o["lon"])
            if (icao.isEmpty() || lat == null || lon == null || o["lat"] !is Number) return@mapNotNull null
            val raw = str(o["rawOb"])
            val wd = o["wdir"]
            val vis = o["visib"]
            val visSm = when (vis) { is Number -> vis.toDouble(); is String -> vis.trimEnd('+').toDoubleOrNull() ?: -1.0; else -> -1.0 }
            val clouds = (o["clouds"] as? List<*>)?.mapNotNull { c ->
                val m = c as? Map<*, *> ?: return@mapNotNull null
                str(m["cover"]).takeIf { it.isNotEmpty() }?.let { Cloud(it, int(m["base"], -1)) }
            } ?: emptyList()
            val vv = int(o["vertVis"], -1)
            var ceil = clouds.filter { (it.cover == "BKN" || it.cover == "OVC" || it.cover == "OVX") && it.baseFt >= 0 }.minOfOrNull { it.baseFt } ?: -1
            if (vv >= 0 && (ceil < 0 || vv < ceil)) ceil = vv
            val cat = when (str(o["fltCat"])) {
                "VFR" -> FlightCat.VFR; "MVFR" -> FlightCat.MVFR; "IFR" -> FlightCat.IFR; "LIFR" -> FlightCat.LIFR
                else -> {
                    val vm = rawVisibilityM(raw)
                    flightCategory(ceil, if (vm >= 0) (if (vm >= 9999) 10.0 else vm / 1609.344) else visSm)
                }
            }
            Metar(icao, str(o["name"]), raw, lat, lon, time(o["obsTime"]).takeIf { it > 0 } ?: time(o["reportTime"]),
                if (wd == "VRB") -2 else int(wd, -1), int(o["wspd"], -1), int(o["wgst"], -1),
                (o["temp"] as? Number)?.toDouble(), (o["dewp"] as? Number)?.toDouble(), (o["altim"] as? Number)?.toDouble(),
                visSm, vis is String && vis.endsWith("+"), str(o["wxString"]).let { if (it == "null") "" else it }, clouds, vv, ceil, cat)
        }
    }

    fun parseTafs(json: String): List<Taf>? {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return null
        return arr.mapNotNull { e ->
            val o = e as? Map<*, *> ?: return@mapNotNull null
            val icao = str(o["icaoId"]); val raw = str(o["rawTAF"]).ifEmpty { str(o["rawOb"]) }
            if (icao.isEmpty() || raw.isEmpty()) null else Taf(icao, raw, time(o["issueTime"]))
        }
    }

    fun parseSigmets(json: String): List<Sigmet>? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val feats = o["features"] as? List<*> ?: return null
        return feats.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val p = m["properties"] as? Map<*, *> ?: return@mapNotNull null
            val g = m["geometry"] as? Map<*, *>
            val c = g?.get("coordinates") as? List<*>
            fun pt(v: Any?): Pair<Double, Double>? { val l = v as? List<*> ?: return null; val lo = num(l.getOrNull(0)) ?: return null; val la = num(l.getOrNull(1)) ?: return null; return la to lo }
            val pts = when (g?.get("type")) {
                "Polygon" -> (c?.firstOrNull() as? List<*>)?.mapNotNull { pt(it) }
                "MultiPolygon" -> ((c?.firstOrNull() as? List<*>)?.firstOrNull() as? List<*>)?.mapNotNull { pt(it) }
                "LineString" -> c?.mapNotNull { pt(it) }
                "Point" -> listOfNotNull(pt(c))
                else -> null
            } ?: emptyList()
            val hazard = str(p["hazard"])
            if (pts.isEmpty() || hazard.isEmpty()) return@mapNotNull null
            val fir = str(p["firId"])
            Sigmet(fir + " " + str(p["seriesId"]).ifEmpty { str(p["isigmetId"]) }, fir, str(p["firName"]), hazard, str(p["qualifier"]),
                str(p["rawSigmet"]), int(p["base"], -1), int(p["top"], -1), time(p["validTimeFrom"]), time(p["validTimeTo"]),
                str(p["dir"]), int(p["spd"], -1), pts)
        }
    }

    // ------------------------------------------------------------------ texts of the card (English, the app's language)

    fun windText(m: Metar): String = when {
        m.wspdKt < 0 -> "Wind: no data"
        m.wspdKt == 0 -> "Calm"
        else -> {
            val dir = if (m.wdir == -2) "variable" else String.format(Locale.US, "%03d°", m.wdir.coerceAtLeast(0))
            if (m.wgstKt > 0) "Wind $dir ${m.wspdKt} kn, gusts ${m.wgstKt} kn" else "Wind $dir ${m.wspdKt} kn"
        }
    }

    private fun coverName(c: String) = when (c) { "FEW" -> "few"; "SCT" -> "scattered"; "BKN" -> "broken"; "OVC" -> "overcast"; "OVX" -> "sky obscured"; else -> null }

    fun skyText(m: Metar): String {
        val parts = ArrayList<String>()
        val vm = rawVisibilityM(m.raw)
        when {
            vm >= 9999 || (vm < 0 && m.visibPlus && m.visibSm >= 6) -> parts.add("Visibility 10 km or more")
            vm >= 0 -> parts.add(if (vm < 5000) "Visibility $vm m" else "Visibility ${vm / 1000} km")
            m.visibSm >= 0 -> parts.add(String.format(Locale.US, "Visibility %.0f km", m.visibSm * 1.609344))
        }
        if (m.wx.isNotEmpty()) parts.add(m.wx)
        var clouds = ""
        for (l in m.clouds) {
            if (l.cover == "CAVOK") { clouds = "CAVOK"; break }
            if (l.cover in setOf("CLR", "SKC", "NSC", "NCD")) { clouds = "no clouds"; break }
            val n = coverName(l.cover) ?: continue
            clouds += (if (clouds.isEmpty()) "" else ", ") + "$n at ${l.baseFt} ft"
        }
        if (m.vertVisFt >= 0) clouds += (if (clouds.isEmpty()) "" else ", ") + "vertical visibility ${m.vertVisFt} ft"
        if (clouds.isNotEmpty()) parts.add("Clouds: $clouds")
        return parts.joinToString(" · ")
    }

    fun tempText(m: Metar): String = listOfNotNull(
        m.tempC?.let { String.format(Locale.US, "Temperature %.0f °C", it) },
        m.dewpC?.let { String.format(Locale.US, "Dew point %.0f °C", it) },
        m.qnhHpa?.let { String.format(Locale.US, "QNH %.0f hPa", it) },
    ).joinToString(" · ")

    fun hazardName(h: String) = when (h) {
        "CONV", "TS" -> "Thunderstorms"; "TURB" -> "Turbulence"; "ICE" -> "Icing"; "MTW" -> "Mountain waves"
        "VA" -> "Volcanic ash"; "TC" -> "Tropical cyclone"; "DS", "SS" -> "Dust or sand storm"; "RDOACT" -> "Radioactive cloud"
        else -> h
    }

    /** Nautical miles from a point to the SIGMET (0 inside its polygon). */
    fun sigmetDistanceNm(s: Sigmet, lat: Double, lon: Double): Double {
        if (s.points.size >= 3 && OfficialAlerts.pointInPolygon(lat, lon, s.points)) return 0.0
        return s.points.minOfOrNull { Geo.distanceKm(lat, lon, it.first, it.second) / 1.852 } ?: Double.MAX_VALUE
    }
}
