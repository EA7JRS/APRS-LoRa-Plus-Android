/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * WMS services as map tiles (as `tiles` of the plotter core): the image of each 256 px tile asked by its box in Web Mercator
 * (EPSG:3857), and the layer names read from the service's GetCapabilities.
 */
object Wms {
    private const val SHIFT = 20037508.342789244

    /** Box of the XYZ tile in EPSG:3857 metres: minX, minY, maxX, maxY. */
    fun bounds3857(z: Int, x: Int, y: Int): DoubleArray {
        val size = 2 * SHIFT / (1 shl z)
        val minX = -SHIFT + x * size
        val maxY = SHIFT - y * size
        return doubleArrayOf(minX, maxY - size, minX + size, maxY)
    }

    fun tileUrl(base: String, layers: String, z: Int, x: Int, y: Int, transparent: Boolean = true, format: String = "image/png"): String {
        val b = bounds3857(z, x, y)
        return base + (if ('?' in base) "&" else "?") +
            "SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + java.net.URLEncoder.encode(layers, "UTF-8") + "&STYLES=&CRS=EPSG:3857" +
            String.format(Locale.US, "&BBOX=%.2f,%.2f,%.2f,%.2f", b[0], b[1], b[2], b[3]) +
            "&WIDTH=256&HEIGHT=256&FORMAT=" + format.replace("/", "%2F") + "&TRANSPARENT=" + if (transparent) "TRUE" else "FALSE"
    }

    /**
     * Layers to ask for, from a GetCapabilities answer: the root layer when it has a name (it draws everything), otherwise the
     * named layers without sub-layers (at most [max], joined with commas). Null if the answer has no layer.
     */
    fun layers(capabilities: String, max: Int = 30): String? {
        val cap = capabilities.indexOf("<Capability").takeIf { it >= 0 } ?: return null
        // style names are not layers
        val xml = capabilities.substring(cap).replace(Regex("<Style>.*?</Style>", RegexOption.DOT_MATCHES_ALL), "")
        val first = xml.indexOf("<Layer").takeIf { it >= 0 } ?: return null
        val nextLayer = xml.indexOf("<Layer", first + 6)
        val rootPart = if (nextLayer > 0) xml.substring(first, nextLayer) else xml.substring(first)
        Regex("<Name>\\s*([^<]+?)\\s*</Name>").find(rootPart)?.let { return it.groupValues[1] }
        val leaves = ArrayList<String>()
        val tag = Regex("<(/?)Layer\\b[^>]*>|<Name>\\s*([^<]+?)\\s*</Name>")
        var depth = 0; var pendingName: String? = null; var hasChild = BooleanArray(64)
        for (m in tag.findAll(xml.substring(first))) {
            when {
                m.value.startsWith("<Name") -> if (depth > 0) pendingName = pendingName ?: m.groupValues[2]
                m.groupValues[1] == "/" -> {
                    if (depth in 1..63 && !hasChild[depth] && pendingName != null) leaves.add(pendingName!!)
                    pendingName = null
                    depth--
                }
                else -> {
                    if (depth in 0..63) hasChild[depth] = true
                    depth++
                    if (depth in 0..63) hasChild[depth] = false
                    pendingName = null
                }
            }
        }
        return leaves.distinct().take(max).joinToString(",").ifEmpty { null }
    }
}

/**
 * Navigational warnings in force (NAVAREA III) from the public XML of the Instituto Hidrográfico de la Marina, and the
 * positions written inside their text (as `navwarn` of the plotter core).
 */
object NavWarn {
    const val IHM_URL = "https://armada.defensa.gob.es/ihm/XML/navareas_crudo.xml"

    data class Warning(
        val number: String, val date: String, val areaEs: String, val areaEn: String, val titleEs: String, val titleEn: String,
        val textEs: String, val textEn: String, val positions: List<Pair<Double, Double>>,
    ) {
        val title: String get() = titleEn.ifEmpty { titleEs }
        val text: String get() = textEn.ifEmpty { textEs }
        val area: String get() = areaEn.ifEmpty { areaEs }
    }

    private fun unescape(s: String): String {
        val o = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { o.append(c); i++; continue }
            val e = s.indexOf(';', i)
            if (e < 0 || e - i > 8) { o.append(c); i++; continue }
            val ent = s.substring(i + 1, e)
            when {
                ent == "amp" -> o.append('&'); ent == "lt" -> o.append('<'); ent == "gt" -> o.append('>')
                ent == "quot" -> o.append('"'); ent == "apos" -> o.append('\'')
                ent.startsWith("#") -> {
                    val cp = (if (ent.length > 1 && (ent[1] == 'x' || ent[1] == 'X')) ent.substring(2).toIntOrNull(16) else ent.substring(1).toIntOrNull()) ?: 0
                    if (cp == 10 || cp == 13) o.append('\n') else if (cp >= 32) o.appendCodePoint(cp)
                }
                else -> { o.append(c); i++; continue }
            }
            i = e + 1
        }
        return o.toString()
    }

    private fun child(xml: String, from: Int, to: Int, tag: String): String {
        val open = "<$tag>"; val close = "</$tag>"
        val a = xml.indexOf(open, from).takeIf { it in 0 until to } ?: return ""
        val b = xml.indexOf(close, a + open.length).takeIf { it in 0..to } ?: return ""
        return unescape(xml.substring(a + open.length, b)).trim()
    }

    /** The warnings of the IHM file, null if it is not that format. */
    fun parseIhm(xml: String): List<Warning>? {
        val root = xml.indexOf("<dataroot").takeIf { it >= 0 } ?: return null
        val out = ArrayList<Warning>()
        var pos = root
        while (true) {
            val a = xml.indexOf("<NAVAREASVIGOR>", pos).takeIf { it >= 0 } ?: break
            val b = xml.indexOf("</NAVAREASVIGOR>", a).takeIf { it >= 0 } ?: break
            val number = child(xml, a, b, "nnunaf").ifEmpty { child(xml, a, b, "nnuna") }
            val es = child(xml, a, b, "ntees"); val en = child(xml, a, b, "ntein")
            if (number.isNotEmpty()) out.add(Warning(number, child(xml, a, b, "nfemi").take(10), child(xml, a, b, "nlocae"), child(xml, a, b, "nlocai"),
                child(xml, a, b, "nasun"), child(xml, a, b, "nasuni"), es, en, positions(en.ifEmpty { es })))
            pos = b + 16
        }
        return out
    }

    // "GG-MM.mmm" or "GG MM,mmm" (or "GGG...") followed by the hemisphere; returns the value and the index after it
    private fun readCoord(s: String, start: Int, degDigits: Int, hemis: String): Pair<Double, Int>? {
        var p = start
        var deg = 0
        repeat(degDigits) { if (p >= s.length || !s[p].isDigit()) return null; deg = deg * 10 + (s[p++] - '0') }
        if (p < s.length && s[p].isDigit()) return null
        if (p >= s.length || (s[p] != '-' && s[p] != ' ')) return null
        p++
        if (p + 1 >= s.length || !s[p].isDigit() || !s[p + 1].isDigit()) return null
        var min = ((s[p] - '0') * 10 + (s[p + 1] - '0')).toDouble()
        p += 2
        if (p < s.length && (s[p] == '.' || s[p] == ',') && p + 1 < s.length && s[p + 1].isDigit()) {
            p++
            var scale = 0.1
            while (p < s.length && s[p].isDigit()) { min += (s[p++] - '0') * scale; scale /= 10 }
        }
        while (p < s.length && s[p] == ' ') p++
        if (p >= s.length || (s[p] != hemis[0] && s[p] != hemis[1])) return null
        if (p + 1 < s.length && s[p + 1].isLetter()) return null          // a lone "N", not "NE" or a word
        if (min >= 60.0) return null
        val v = deg + min / 60.0
        return (if (s[p] == hemis[1]) -v else v) to p + 1
    }

    /** Positions written as in warnings and Navtex: "46-04.0N 033-12.8E", "42 11,718N 009 59,778E", "36-31N 006-17W". */
    fun positions(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < s.length) {
            if (!s[i].isDigit() || (i > 0 && (s[i - 1].isDigit() || s[i - 1] == '.' || s[i - 1] == ','))) { i++; continue }
            val la = readCoord(s, i, 2, "NS")
            if (la == null) { i++; continue }
            var p = la.second
            while (p < s.length && s[p] in " ,-/\n\r") p++
            val lo = readCoord(s, p, 3, "EW")
            if (lo == null || la.first !in -90.0..90.0 || lo.first !in -180.0..180.0) { i++; continue }
            out.add(la.first to lo.first)
            i = lo.second
        }
        return out
    }
}

/** Day / night line: the point where the sun is overhead and the night side as a polygon for the map. */
object Terminator {
    /** Latitude and longitude where the sun is at the zenith (NOAA low-precision formulas, good to a few tenths of a degree). */
    fun subsolar(timeMs: Long): Pair<Double, Double> {
        val d = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val dec = Math.toDegrees(Math.asin(sin(e) * sin(l)))
        val ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l)))
        val gmst = (280.46061837 + 360.98564736629 * d) % 360
        var lon = ra - gmst
        lon = ((lon + 540) % 360 + 360) % 360 - 180
        return dec to lon
    }

    /** Night side as a closed ring of (lat, lon) points, from −180° to 180° and closed over the dark pole (Mercator limit ±85°). */
    fun nightPolygon(timeMs: Long, steps: Int = 180): List<Pair<Double, Double>> {
        val (dec0, sunLon) = subsolar(timeMs)
        val dec = if (kotlin.math.abs(dec0) < 0.01) 0.01 else dec0          // at an equinox the line is almost a meridian pair
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0..steps) {
            val lon = -180.0 + 360.0 * i / steps
            val lat = Math.toDegrees(atan(-cos(Math.toRadians(lon - sunLon)) / tan(Math.toRadians(dec))))
            pts.add(lat.coerceIn(-85.0, 85.0) to lon)
        }
        val pole = if (dec > 0) -85.0 else 85.0                               // the pole without sun
        pts.add(pole to 180.0); pts.add(pole to -180.0)
        return pts
    }

    /** True if the sun is below the horizon at that place (to check the polygon). */
    fun isNight(timeMs: Long, lat: Double, lon: Double): Boolean {
        val (dec, sunLon) = subsolar(timeMs)
        val h = Math.toRadians(lon - sunLon)
        val alt = Math.asin(sin(Math.toRadians(lat)) * sin(Math.toRadians(dec)) + cos(Math.toRadians(lat)) * cos(Math.toRadians(dec)) * cos(h))
        return alt < 0 && alt > -PI
    }
}

/** Magnitude of an earthquake alert ("M4.1 Earthquake"), null otherwise. */
fun OfficialAlert.magnitude(): Double? = if (!src.quake) null else Regex("M\\s?(\\d+(?:\\.\\d+)?)").find(event)?.groupValues?.get(1)?.toDoubleOrNull()
