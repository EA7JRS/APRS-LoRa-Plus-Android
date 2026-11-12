/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.cos
import kotlin.math.sqrt

/** One official weather warning, as read from a CAP message of AEMET or from an entry of the Meteoalarm feed. */
data class WxWarning(
    val id: String, val event: String, val area: String, val description: String, val instruction: String, val probability: String,
    val phenomenon: String, val source: String,
    val onset: String, val expires: String,             // as they come (ISO 8601 with their offset)
    val onsetS: Long, val expiresS: Long,               // seconds UTC since 1970; 0 = unknown
    val level: Int,                                     // 1 yellow, 2 orange, 3 red
    val hasArea: Boolean, val inside: Boolean, val distanceKm: Double?,
) {
    /** One window per warning: the same event for the same area and start is the same warning. */
    val key: String get() = "$event|$area|$onset"
    val levelName: String get() = WxWarn.levelName(level)
    fun expired(nowS: Long): Boolean = expiresS > 0 && nowS > expiresS
}

/**
 * Official weather warnings: the same reader as `wxwarn` of APRS LoRa+ Display. AEMET (Spain) sends CAP messages with the outline
 * of each zone; the Meteoalarm feed of each European country has entries with cap: tags and no outline (matched by zone name).
 */
object WxWarn {
    const val YELLOW = 1; const val ORANGE = 2; const val RED = 3
    fun levelName(l: Int): String = when (l) { RED -> "red"; ORANGE -> "orange"; YELLOW -> "yellow"; else -> "-" }
    /** Meteoalarm's colours, 0xRRGGBB. */
    fun levelColor(l: Int): Int = when (l) { RED -> 0xFF5A5A; ORANGE -> 0xFF9A3C; else -> 0xFFD84D }

    fun aemetUrl(key: String): String = "https://opendata.aemet.es/opendata/api/avisos_cap/ultimoelaborado/area/esp?api_key=$key"
    fun meteoalarmUrl(country: String): String = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-" + country.trim().lowercase().replace(' ', '-')

    /** Content of the first <name> or <cap:name> from [from]; null when there is none. */
    private fun tagAt(x: String, name: String, from: Int = 0): String? {
        var p = from
        while (true) {
            p = x.indexOf('<', p)
            if (p < 0) return null
            var q = p + 1
            if (x.startsWith("cap:", q)) q += 4
            if (x.startsWith(name, q) && q + name.length < x.length && (x[q + name.length] == '>' || x[q + name.length] == ' ')) {
                val gt = x.indexOf('>', q + name.length); if (gt < 0) return null
                val end = x.indexOf("</", gt); if (end < 0) return null
                return x.substring(gt + 1, end)
            }
            p++
        }
    }

    private fun clean(s: String): String {
        var t = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        t = Regex("&#(x?)([0-9A-Fa-f]{1,6});").replace(t) { m ->
            val cp = m.groupValues[2].toIntOrNull(if (m.groupValues[1] == "x") 16 else 10)
            if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        return t.trim()
    }

    private fun tag(x: String, name: String): String = tagAt(x, name)?.let { clean(it) } ?: ""
    private fun named(x: String, valueName: String): String {
        val p = x.indexOf(valueName); if (p < 0) return ""
        return tagAt(x, "value", p)?.let { clean(it) } ?: ""
    }

    /** "2026-09-10T00:00:00+02:00" → seconds UTC; 0 when it is not a date. */
    fun isoToEpoch(s: String): Long {
        if (s.length < 19 || s[4] != '-' || s[10] != 'T') return 0
        fun n(i: Int, len: Int) = s.substring(i, i + len).toIntOrNull() ?: -1
        var y = n(0, 4); val m = n(5, 2); val d = n(8, 2); val hh = n(11, 2); val mi = n(14, 2); val ss = n(17, 2)
        if (y < 0 || m !in 1..12 || d !in 1..31 || hh < 0 || mi < 0 || ss < 0) return 0
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        var t = (era.toLong() * 146097 + doe - 719468) * 86400 + hh * 3600 + mi * 60 + ss
        var z = 19
        while (z < s.length && s[z] != '+' && s[z] != '-' && s[z] != 'Z') z++
        if (z < s.length && s[z] != 'Z' && z + 3 <= s.length) {
            val off = (s.substring(z + 1, z + 3).toIntOrNull() ?: 0) * 3600 + (if (s.length >= z + 6) (s.substring(z + 4, z + 6).toIntOrNull() ?: 0) * 60 else 0)
            t += if (s[z] == '+') -off else off
        }
        return t
    }

    /** "10 00:00": day and local time of the warning. */
    fun shortTime(iso: String): String = if (iso.length >= 16) iso.substring(8, 10) + " " + iso.substring(11, 16) else iso

    private fun flatKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dy = (la2 - la1) * 111.2; val dx = (lo2 - lo1) * 111.2 * cos(Math.toRadians((la1 + la2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** CAP polygon "lat,lon lat,lon ...": inside (ray crossings) and distance to the nearest vertex. Null: not a polygon. */
    fun polygon(poly: String, lat: Double, lon: Double): Pair<Boolean, Double>? {
        val pts = poly.trim().split(Regex("\\s+")).mapNotNull { p ->
            val c = p.split(','); val la = c.getOrNull(0)?.toDoubleOrNull(); val lo = c.getOrNull(1)?.toDoubleOrNull()
            if (la != null && lo != null) la to lo else null
        }
        if (pts.size < 3) return null
        var inside = false; var best = Double.MAX_VALUE
        var j = pts.size - 1
        for (i in pts.indices) {
            val (yi, xi) = pts[i]; val (yj, xj) = pts[j]
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) inside = !inside
            best = minOf(best, flatKm(lat, lon, yi, xi))
            j = i
        }
        return inside to (if (inside) 0.0 else best)
    }

    /** A CAP <alert> or a Meteoalarm <entry>. Null when it is not a warning in force that matters (green level, cancelled, test). */
    fun parse(xml: String, pos: Pair<Double, Double>?): WxWarning? {
        val atom = xml.startsWith("<entry")
        val status = tag(xml, "status").lowercase(); val type = tag(xml, if (atom) "message_type" else "msgType").lowercase()
        if (status.isNotEmpty() && status != "actual") return null
        if (type == "cancel") return null
        var info = xml                                          // AEMET repeats the warning in several languages: the first <info>
        if (!atom) { val a = xml.indexOf("<info"); val b = xml.indexOf("</info>"); if (a >= 0 && b > a) info = xml.substring(a, b) }
        val event = tag(info, "event")
        val nivel = named(info, "AEMET-Meteoalerta nivel").lowercase(); val sev = tag(info, "severity").lowercase()
        val level = if (nivel.isNotEmpty()) when (nivel) { "rojo" -> RED; "naranja" -> ORANGE; "amarillo" -> YELLOW; else -> 0 }
            else when (sev) { "extreme" -> RED; "severe" -> ORANGE; "moderate" -> YELLOW; else -> 0 }
        if (level == 0 || event.isEmpty()) return null
        var desc = tag(info, "description")
        if (desc.isEmpty()) named(info, "AEMET-Meteoalerta parametro").let { p -> val s = p.indexOf(';'); if (s >= 0) desc = p.substring(s + 1).replace(';', ' ') }
        val onset = tag(info, "onset").ifEmpty { tag(info, "effective") }; val expires = tag(info, "expires")
        val poly = tag(info, "polygon")
        val hit = if (pos != null && poly.isNotEmpty()) polygon(poly, pos.first, pos.second) else null
        return WxWarning(
            id = tag(xml, "identifier").ifEmpty { tag(xml, "id") }, event = event, area = tag(info, "areaDesc"), description = desc,
            instruction = tag(info, "instruction"), probability = named(info, "AEMET-Meteoalerta probabilidad"),
            phenomenon = named(info, "AEMET-Meteoalerta fenomeno").substringAfter(';'), source = if (atom) "Meteoalarm" else "AEMET",
            onset = onset, expires = expires, onsetS = isoToEpoch(onset), expiresS = isoToEpoch(expires), level = level,
            hasArea = hit != null, inside = hit?.first ?: false, distanceKm = hit?.second,
        )
    }

    private fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    /** The zone name has one of the words of [filter] (commas; case and accents do not matter). An empty filter takes everything. */
    fun areaMatches(area: String, filter: String): Boolean {
        val words = filter.split(',').map { fold(it.trim()) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val a = fold(area)
        return words.any { a.contains(it) }
    }

    /** List order: inside the zone first, higher level first, what starts sooner first. */
    val ORDER: Comparator<WxWarning> = compareBy<WxWarning> { !it.inside }.thenByDescending { it.level }.thenBy { it.onsetS }.thenBy { it.area }

    /** Does this warning concern us: inside its outline or within [nearKm]; without outline, by the zone names. */
    fun mine(w: WxWarning, nearKm: Double, areaFilter: String, needFilter: Boolean): Boolean =
        if (w.hasArea) w.inside || (w.distanceKm ?: Double.MAX_VALUE) <= nearKm else (!needFilter || areaFilter.isNotBlank()) && areaMatches(w.area, areaFilter)

    /** Cuts the warnings out of a stream (AEMET's archive or Meteoalarm's Atom feed) as the bytes arrive. */
    class Scanner {
        private val buf = StringBuilder()
        private var inBlock = false
        private var kind = 0
        private var first = true
        var gzip = false; private set
        var blocks = 0; private set

        /** Feed bytes (ISO-8859-1 keeps every byte); returns the complete blocks found so far, as UTF-8 text. */
        fun feed(data: ByteArray, n: Int): List<String> {
            if (first && n >= 2) { first = false; if (data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) gzip = true }
            if (gzip) return emptyList()
            buf.append(String(data, 0, n, Charsets.ISO_8859_1))
            val out = ArrayList<String>()
            while (true) {
                if (!inBlock) {
                    val a = buf.indexOf("<alert"); val e = buf.indexOf("<entry")
                    val s = when { a >= 0 && (e < 0 || a < e) -> { kind = 1; a }; e >= 0 -> { kind = 2; e }; else -> -1 }
                    if (s < 0) { if (buf.length > 8) buf.delete(0, buf.length - 8); break }
                    buf.delete(0, s); inBlock = true
                }
                val endTag = if (kind == 1) "</alert>" else "</entry>"
                val e = buf.indexOf(endTag)
                val cut = when { e >= 0 -> e + endTag.length; buf.length > MAX -> MAX; else -> -1 }
                if (cut < 0) break
                out.add(String(buf.substring(0, cut).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
                buf.delete(0, cut); inBlock = false; blocks++
            }
            return out
        }

        private companion object { const val MAX = 24000 }
    }
}
