/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Official alerts for the whole APRS LoRa+ ecosystem, the same model and readers as the plotter's core (lib/plotter_core: alerts,
 * same), with the same test values:
 * - weather: AEMET (public Meteoalerta RSS and the .tar.gz of every CAP, with polygons), MeteoAlarm (Atom feed per country, CC BY 4.0)
 *   and the US NWS (api.weather.gov, GeoJSON, per point);
 * - tsunamis: tsunami.gov (PTWC PHEB and NTWC PAAQ Atom feeds);
 * - earthquakes: USGS (GeoJSON, worldwide), EMSC (FDSN, Europe and the Mediterranean, with IGN data) and ShakeNet (Raspberry Shake
 *   QuakeLink, FDSN text, automatic locations of the Raspberry Shake network; terms of use not published);
 * - space weather: NOAA SWPC (G / R / S scales, Kp, 10.7 cm flux, solar wind, alerts);
 * - NOAA Weather Radio (SAME): [SameDecoder].
 * Every alert gets a common level 0 (information), 1 yellow, 2 orange, 3 red.
 */
enum class AlertSrc(val code: String, val title: String) {
    AEMET("aemet", "AEMET"), METEOALARM("ma", "MeteoAlarm"), NWS("nws", "NWS"), TSUNAMI("tsu", "TSUNAMI"), SWPC("swpc", "SWPC"),
    SAME("same", "SAME"), USGS("usgs", "USGS"), EMSC("emsc", "EMSC"), SHAKE("shake", "ShakeNet"), OTHER("other", "ALERT");

    val quake: Boolean get() = this == USGS || this == EMSC || this == SHAKE

    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } ?: OTHER }
}

data class OfficialAlert(
    val id: String, val src: AlertSrc, val level: Int, val event: String, val area: String, val text: String = "",
    val instruction: String = "", val sent: Long = 0, val onset: Long = 0, val expires: Long = 0, val lat: Double? = null, val lon: Double? = null,
) {
    fun activeAt(nowS: Long) = expires == 0L || nowS < expires
    val title: String get() = when {
        src.quake -> "EARTHQUAKE"
        src == AlertSrc.TSUNAMI -> when (level) { 3 -> "TSUNAMI WARNING"; 2 -> "TSUNAMI ADVISORY"; 1 -> "TSUNAMI WATCH"; else -> "TSUNAMI" }
        src == AlertSrc.SWPC -> "SPACE WEATHER"
        src == AlertSrc.SAME -> "NOAA WEATHER RADIO"
        else -> when (level) { 3 -> "RED WARNING"; 2 -> "ORANGE WARNING"; 1 -> "YELLOW WARNING"; else -> "WARNING" }
    }
    val levelName: String get() = OfficialAlerts.LEVELS[level.coerceIn(0, 3)]
}

/** Space weather from the SWPC: today's scales (-1 = no data), the last 8 three-hour Kp values, flux, solar wind and X-ray class. */
data class SpaceWx(
    val g: Int = -1, val r: Int = -1, val s: Int = -1, val gMax: Int = -1, val kp: List<Double> = emptyList(), val kpTime: Long = 0,
    val sfi: Double? = null, val bz: Double? = null, val bt: Double? = null, val windKms: Double? = null, val xray: String = "",
    val updatedMs: Long = 0,
    val ssn: Double? = null,                                      // sunspot number (SWPC, daily; monthly as a fallback)
    val density: Double? = null, val tempK: Double? = null,       // solar wind: protons / cm3 and K (RTSW, 1 min)
    val windTime: Long = 0,                                       // time of the solar wind reading (Unix s)
) {
    val kpNow: Double? get() = kp.lastOrNull()
    val valid: Boolean get() = g >= 0 || kp.isNotEmpty() || sfi != null
}

object OfficialAlerts {
    val LEVELS = listOf("INFO", "YELLOW", "ORANGE", "RED")

    const val METEOALARM_FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-"
    const val AEMET_RSS_PENINSULA = "https://www.aemet.es/documentos_d/eltiempo/prediccion/avisos/rss/CAP_AFAE_RSS.xml"
    const val AEMET_RSS_CANARIAS = "https://www.aemet.es/documentos_d/eltiempo/prediccion/avisos/rss/CAP_AFAC65_RSS.xml"
    const val NWS_ACTIVE = "https://api.weather.gov/alerts/active?point="
    const val TSUNAMI_PTWC = "https://www.tsunami.gov/events/xml/PHEBAtom.xml"
    const val TSUNAMI_NTWC = "https://www.tsunami.gov/events/xml/PAAQAtom.xml"
    const val USGS_45_DAY = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson"
    const val SWPC_SCALES = "https://services.swpc.noaa.gov/products/noaa-scales.json"
    const val SWPC_KP = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"
    const val SWPC_FLUX = "https://services.swpc.noaa.gov/products/summary/10cm-flux.json"
    const val SWPC_MAG = "https://services.swpc.noaa.gov/products/summary/solar-wind-mag-field.json"
    const val SWPC_WIND = "https://services.swpc.noaa.gov/products/summary/solar-wind-speed.json"
    const val SWPC_ALERTS = "https://services.swpc.noaa.gov/products/alerts.json"
    const val SWPC_XRAY = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json"
    // Sunspot number and 10.7 cm flux of the day: SWPC's daily solar indices (a small text file, last 30 days; the source sat-aprs
    // reads, once an hour) and, if it fails, the monthly observed solar-cycle indices ("time-tag", "ssn")
    // Real-time solar wind at L1 (DSCOVR / ACE), 1-minute values, newest first: speed, density and temperature of the protons and
    // the magnetic field (Bt, Bz GSM). The same feeds sat-aprs reads; the summaries above remain as a fallback.
    const val SWPC_RTSW_WIND = "https://services.swpc.noaa.gov/json/rtsw/rtsw_wind_1m.json"
    const val SWPC_RTSW_MAG = "https://services.swpc.noaa.gov/json/rtsw/rtsw_mag_1m.json"
    const val SWPC_DAILY_INDICES = "https://services.swpc.noaa.gov/text/daily-solar-indices.txt"
    const val SWPC_SSN_MONTHLY = "https://services.swpc.noaa.gov/json/solar-cycle/observed-solar-cycle-indices.json"

    // ---------------------------------------------------------------- XML
    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos|nbsp);")

    private fun decodeXml(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '<') {
                if (s.startsWith("<![CDATA[", i)) {
                    val e = s.indexOf("]]>", i + 9).let { if (it < 0) s.length else it }
                    sb.append(s, i + 9, e); i = e + 3; continue
                }
                val e = s.indexOf('>', i)
                if (e < 0) break
                sb.append(' '); i = e + 1; continue
            }
            sb.append(c); i++
        }
        return ENTITY.replace(sb.toString()) { m ->
            when (val v = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> {
                    val cp = if (v[1] == 'x' || v[1] == 'X') v.substring(2).toIntOrNull(16) else v.substring(1).toIntOrNull()
                    cp?.let { String(Character.toChars(it)) } ?: m.value
                }
            }
        }
    }

    private fun squeeze(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /** Text of the first [name] element (with prefix, "cap:event") inside [xml]; "" when missing. */
    fun xmlText(xml: String, name: String, from: Int = 0): String {
        val open = "<$name"
        var a = from
        while (true) {
            a = xml.indexOf(open, a)
            if (a < 0) return ""
            val next = xml.getOrNull(a + open.length)
            if (next == '>' || next == ' ' || next == '/' || next == '\t' || next == '\n' || next == '\r') break
            a += open.length
        }
        val gt = xml.indexOf('>', a)
        if (gt < 0 || xml[gt - 1] == '/') return ""
        val e = xml.indexOf("</$name>", gt + 1)
        if (e < 0) return ""
        return squeeze(decodeXml(xml.substring(gt + 1, e)))
    }

    fun xmlBlocks(doc: String, tag: String): List<String> {
        val out = ArrayList<String>()
        val open = "<$tag"; val close = "</$tag>"
        var a = 0
        while (true) {
            a = doc.indexOf(open, a)
            if (a < 0) break
            val next = doc.getOrNull(a + open.length)
            if (next != '>' && next != ' ' && next != '\n' && next != '\r' && next != '\t') { a += open.length; continue }
            val e = doc.indexOf(close, a)
            if (e < 0) break
            out.add(doc.substring(a, e + close.length))
            a = e + close.length
        }
        return out
    }

    // ---------------------------------------------------------------- time
    fun unixFromUtc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long {
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468
        return days * 86400 + h * 3600 + mi * 60 + s
    }

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?\s*(Z|[+-]\d{2}:?\d{2})?""")

    /** ISO 8601 / RFC 3339 to Unix seconds; 0 when it does not parse. */
    fun parseIso(s: String?): Long {
        val m = ISO.find(s?.trim() ?: return 0) ?: return 0
        val g = m.groupValues
        var t = unixFromUtc(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0)
        val z = g[7]
        if (z.isNotEmpty() && z != "Z") {
            val digits = z.substring(1).replace(":", "")
            val off = (digits.substring(0, 2).toInt() * 60 + digits.substring(2).toInt()) * 60
            t -= if (z[0] == '+') off else -off
        }
        return t
    }

    // ---------------------------------------------------------------- levels
    fun levelFromSeverity(s: String): Int = when {
        s.contains("extreme", true) -> 3; s.contains("severe", true) -> 2; s.contains("moderate", true) -> 1; else -> 0
    }

    private val RED = Regex("""(?i)(^|[^a-z])(red|rojo)([^a-z]|$)""")

    /** Colour word in a text ("Orange ...", "nivel amarillo"); -1 when there is none. */
    fun levelFromColour(t: String): Int = when {
        RED.containsMatchIn(t) -> 3
        t.contains("orange", true) || t.contains("naranja", true) -> 2
        t.contains("yellow", true) || t.contains("amarillo", true) -> 1
        t.contains("green", true) || t.contains("verde", true) -> 0
        else -> -1
    }

    /** Text without accents, for screens with a 7-bit font (the tracker). */
    fun asciiFold(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('ß', 's').replace(Regex("[^\\x20-\\x7E]"), "?")

    /** Zone filter for the sources without polygons: comma separated words, any of them in the area (no case, no accents); empty = all. */
    fun zoneMatch(area: String, zones: String): Boolean {
        if (zones.isBlank()) return true
        val a = asciiFold(area).lowercase(Locale.ROOT)
        return zones.split(',').map { asciiFold(it.trim()).lowercase(Locale.ROOT) }.any { it.isNotEmpty() && a.contains(it) }
    }

    // ---------------------------------------------------------------- MeteoAlarm
    fun meteoAlarmEntry(e: String): OfficialAlert? {
        val event = xmlText(e, "cap:event"); val title = xmlText(e, "title")
        if (event.isEmpty() && title.isEmpty()) return null
        val status = xmlText(e, "cap:status")
        if (status.isNotEmpty() && status != "Actual") return null
        if (xmlText(e, "cap:message_type") == "Cancel") return null
        val colour = levelFromColour(title)
        val ident = xmlText(e, "cap:identifier").ifEmpty { xmlText(e, "id") }
        return OfficialAlert(
            "$ident/${xmlText(e, "value")}", AlertSrc.METEOALARM, if (colour >= 0) colour else levelFromSeverity(xmlText(e, "cap:severity")),
            event.ifEmpty { title }, xmlText(e, "cap:areaDesc"), title, "", parseIso(xmlText(e, "cap:sent")),
            parseIso(xmlText(e, "cap:onset")).takeIf { it != 0L } ?: parseIso(xmlText(e, "cap:effective")), parseIso(xmlText(e, "cap:expires")),
        )
    }

    private val FEEDS = mapOf(
        "AT" to "austria", "BE" to "belgium", "BA" to "bosnia-herzegovina", "BG" to "bulgaria", "HR" to "croatia", "CY" to "cyprus",
        "CZ" to "czechia", "DK" to "denmark", "EE" to "estonia", "FI" to "finland", "FR" to "france", "DE" to "germany", "GR" to "greece",
        "HU" to "hungary", "IS" to "iceland", "IE" to "ireland", "IL" to "israel", "IT" to "italy", "LV" to "latvia", "LT" to "lithuania",
        "LU" to "luxembourg", "MT" to "malta", "MD" to "moldova", "ME" to "montenegro", "NL" to "netherlands",
        "MK" to "republic-of-north-macedonia", "NO" to "norway", "PL" to "poland", "PT" to "portugal", "RO" to "romania", "RS" to "serbia",
        "SK" to "slovakia", "SI" to "slovenia", "ES" to "spain", "SE" to "sweden", "CH" to "switzerland", "UA" to "ukraine", "GB" to "united-kingdom",
    )

    fun meteoAlarmFeedName(iso2: String?): String? = iso2?.uppercase(Locale.ROOT)?.let { FEEDS[it] }

    // ---------------------------------------------------------------- AEMET
    private val PHEN = mapOf(
        "Lluvias" to "Rain", "Tormentas" to "Thunderstorms", "Nieblas" to "Fog", "Vientos" to "Wind", "Costeros" to "Coastal events",
        "Fenómenos costeros" to "Coastal events", "Nevadas" to "Snow", "Temperaturas máximas" to "High temperatures",
        "Temperaturas mínimas" to "Low temperatures", "Polvo en suspensión" to "Dust", "Aludes" to "Avalanches", "Galernas" to "Galerna",
        "Rissagas" to "Rissaga", "Deshielos" to "Thaw", "Olas de calor" to "Heat wave", "Olas de frío" to "Cold wave",
    )

    fun aemetPhenomenonEn(es: String) = PHEN[es] ?: es

    private val AEMET_TIME = Regex("""(\d{1,2}):(\d{2}) (\d{1,2})-(\d{1,2})-(\d{4})[^(]{0,30}\(UTC([+-]\d+)?\)""")

    /** One <item> of the Meteoalerta RSS ("Aviso. Nivel amarillo. Lluvias. Costa granadina"); null for the .tar.gz item. */
    fun aemetRssItem(item: String): OfficialAlert? {
        val title = xmlText(item, "title")
        if (!title.startsWith("Aviso.")) return null
        val parts = title.split(". ").map { it.trim() }
        if (parts.size < 4) return null
        val lv = levelFromColour(parts[1])
        if (lv < 0) return null
        val desc = xmlText(item, "description")
        val lvl = desc.indexOf(" de nivel ")
        val text = if (desc.startsWith("Aviso de ") && lvl >= 0) desc.substring(9, lvl) else desc
        val times = AEMET_TIME.findAll(desc, if (lvl >= 0) lvl + 10 else 0).map { m ->
            val g = m.groupValues
            unixFromUtc(g[5].toInt(), g[4].toInt(), g[3].toInt(), g[1].toInt(), g[2].toInt()) - (g[6].toIntOrNull() ?: 0) * 3600L
        }.toList()
        val guid = xmlText(item, "guid")
        return OfficialAlert(guid.ifEmpty { title }, AlertSrc.AEMET, lv, aemetPhenomenonEn(parts[2]), parts.drop(3).joinToString(". "), text,
            onset = times.getOrElse(0) { 0 }, expires = times.getOrElse(1) { 0 })
    }

    /** CAP 1.2 (an AEMET file of the bundle, a MeteoAlarm CAP, tsunami.gov): the <info> in [lang] (else the first), one alert per <area>, with its polygon. */
    fun capParse(xml: String, lang: String = "en", src: AlertSrc = AlertSrc.AEMET): List<Pair<OfficialAlert, List<Pair<Double, Double>>>> {
        if (!xml.contains("<alert")) return emptyList()
        val status = xmlText(xml, "status")
        if (status.isNotEmpty() && status != "Actual") return emptyList()
        if (xmlText(xml, "msgType") == "Cancel") return emptyList()
        val ident = xmlText(xml, "identifier")
        val sent = parseIso(xmlText(xml, "sent"))
        val infos = xmlBlocks(xml, "info")
        if (infos.isEmpty()) return emptyList()
        val info = infos.firstOrNull { xmlText(it, "language").startsWith(lang, true) } ?: infos[0]
        var lv = -1
        for (p in xmlBlocks(info, "parameter")) {
            val name = xmlText(p, "valueName")
            if (name.contains("nivel", true) || name.contains("awareness_level", true)) {
                val v = xmlText(p, "value")
                lv = levelFromColour(v).takeIf { it >= 0 } ?: v.takeWhile { it.isDigit() }.toIntOrNull()?.let { (it - 1).coerceAtLeast(0) } ?: -1
            }
        }
        val event = xmlText(info, "event")
        if (lv < 0) lv = levelFromColour(event)
        if (lv < 0) lv = levelFromSeverity(xmlText(info, "severity"))
        val desc = xmlText(info, "description").ifEmpty { xmlText(info, "headline") }
        val onset = parseIso(xmlText(info, "onset")).takeIf { it != 0L } ?: parseIso(xmlText(info, "effective"))
        val expires = parseIso(xmlText(info, "expires"))
        val areas = xmlBlocks(info, "area").ifEmpty { listOf("") }
        return areas.mapIndexed { k, ar ->
            val poly = xmlBlocks(ar, "polygon").firstOrNull()?.let { polygon(xmlText(it, "polygon")) } ?: emptyList()
            val lat = poly.takeIf { it.isNotEmpty() }?.map { it.first }?.average()
            val lon = poly.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
            OfficialAlert(ident + if (areas.size > 1) "/$k" else "", src, lv.coerceIn(0, 3), event, xmlText(ar, "areaDesc"), desc,
                xmlText(info, "instruction"), sent, onset, expires, lat, lon) to poly
        }
    }

    fun polygon(t: String): List<Pair<Double, Double>> = t.trim().split(Regex("\\s+")).mapNotNull { p ->
        val c = p.split(','); if (c.size < 2) null else c[0].toDoubleOrNull()?.let { la -> c[1].toDoubleOrNull()?.let { lo -> la to lo } }
    }

    fun pointInPolygon(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (ai, ao) = poly[i]; val (bi, bo) = poly[j]
            if ((ai > lat) != (bi > lat) && lon < (bo - ao) * (lat - ai) / (bi - ai) + ao) inside = !inside
            j = i
        }
        return inside
    }

    /** Files of a .tar archive (ustar / pax): name -> content, only the ones [want] accepts. */
    fun tarFiles(tar: ByteArray, want: (String) -> Boolean = { true }): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var p = 0
        while (p + 512 <= tar.size) {
            if (tar[p].toInt() == 0) break
            val name = String(tar, p, 100, Charsets.US_ASCII).substringBefore('\u0000')
            val size = String(tar, p + 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }.toLongOrNull(8) ?: 0
            val type = tar[p + 156].toInt().toChar()
            val data = p + 512
            if ((type == '0' || type == '\u0000') && data + size <= tar.size && want(name)) out.add(name to tar.copyOfRange(data, (data + size).toInt()))
            p = data + ((size + 511) / 512 * 512).toInt()
        }
        return out
    }

    // ---------------------------------------------------------------- NWS
    fun nws(json: String): List<OfficialAlert> {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return emptyList()
        return o.objList("features").mapNotNull { f ->
            @Suppress("UNCHECKED_CAST") val p = f["properties"] as? Map<String, Any?> ?: return@mapNotNull null
            if ((p.str("status") ?: "Actual") != "Actual" || p.str("messageType") == "Cancel") return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST") val g = f["geometry"] as? Map<String, Any?>
            var lat: Double? = null; var lon: Double? = null
            if (g?.str("type") == "Polygon") {
                val ring = ((g["coordinates"] as? List<*>)?.firstOrNull() as? List<*>)?.mapNotNull { (it as? List<*>)?.mapNotNull { n -> (n as? Number)?.toDouble() } }
                    ?.filter { it.size >= 2 }
                if (!ring.isNullOrEmpty()) { lat = ring.map { it[1] }.average(); lon = ring.map { it[0] }.average() }
            }
            OfficialAlert(id, AlertSrc.NWS, levelFromSeverity(p.str("severity") ?: ""), p.str("event") ?: "", p.str("areaDesc") ?: "",
                squeeze(p.str("headline") ?: ""), squeeze(p.str("instruction") ?: ""), parseIso(p.str("sent")),
                parseIso(p.str("onset")).takeIf { it != 0L } ?: parseIso(p.str("effective")),
                parseIso(p.str("ends")).takeIf { it != 0L } ?: parseIso(p.str("expires")), lat, lon)
        }
    }

    // ---------------------------------------------------------------- tsunami.gov
    private val TSU_FIELDS = listOf("Category", "Bulletin Issue Time", "Preliminary Magnitude", "Lat/Lon", "Affected Region", "Note", "Definition")

    fun tsunamiEntry(e: String): OfficialAlert? {
        val region = xmlText(e, "title"); val sum = xmlText(e, "summary")
        if (region.isEmpty() && sum.isEmpty()) return null
        fun field(name: String): String {
            var p = sum.indexOf(name); if (p < 0) return ""
            p += name.length
            while (p < sum.length && (sum[p] == ':' || sum[p] == ' ')) p++
            val end = TSU_FIELDS.map { sum.indexOf(it, p) }.filter { it >= 0 }.minOrNull() ?: sum.length
            return sum.substring(p, end).trim()
        }
        val cat = field("Category")
        val lv = when { cat.contains("cancel", true) -> 0; cat.contains("warning", true) -> 3; cat.contains("advisory", true) -> 2; cat.contains("watch", true) -> 1; else -> 0 }
        val mag = field("Preliminary Magnitude")
        val bt = Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2}) (\d{1,2}):(\d{2}):(\d{2})""").find(field("Bulletin Issue Time"))
        val sent = bt?.groupValues?.let { unixFromUtc(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toInt(), it[6].toInt()) } ?: parseIso(xmlText(e, "updated"))
        val lat = xmlText(e, "geo:lat").toDoubleOrNull(); val lon = xmlText(e, "geo:long").toDoubleOrNull()
        return OfficialAlert(xmlText(e, "id").ifEmpty { "$region@$sent" }, AlertSrc.TSUNAMI, lv, "Tsunami " + cat.ifEmpty { "message" }, region,
            (if (mag.isEmpty()) "" else "M$mag ") + region, "", sent, sent, if (sent != 0L) sent + 86400 else 0, lat, lon)
    }

    // ---------------------------------------------------------------- earthquakes
    fun emscUrl(lat: Double, lon: Double, sinceS: Long, minMag: Double = 3.5, radiusDeg: Double = 15.0): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return String.format(Locale.US, "https://www.seismicportal.eu/fdsnws/event/1/query?format=json&limit=50&minmag=%.1f&latitude=%.2f&longitude=%.2f&maxradius=%.0f&start=%s",
            minMag, lat, lon, radiusDeg, f.format(java.util.Date(sinceS * 1000)))
    }

    /** ShakeNet (Raspberry Shake, QuakeLink): FDSN event service in the FDSN text format, near a position. */
    fun shakeUrl(lat: Double, lon: Double, sinceS: Long, minMag: Double = 3.5, radiusDeg: Double = 15.0): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return String.format(Locale.US, "https://quakelink.raspberryshake.org/fdsnws/event/1/query?format=text&orderby=time&limit=50&minmagnitude=%.1f" +
            "&latitude=%.2f&longitude=%.2f&maxradius=%.0f&starttime=%s", minMag, lat, lon, radiusDeg, f.format(java.util.Date(sinceS * 1000)))
    }

    /**
     * FDSN-WS 1.2 text ("#EventID|Time|Latitude|Longitude|Depth/km|Author|Catalog|Contributor|ContributorID|MagType|Magnitude|
     * MagAuthor|EventLocationName|EventType"): columns by their header names, the standard order without a header; events that
     * are not earthquakes (quarry blasts...) are left out. Same as quakeParseFdsnText of the plotter core.
     */
    fun fdsnText(text: String, src: AlertSrc): List<OfficialAlert> {
        var cId = -1; var cTime = -1; var cLat = -1; var cLon = -1; var cDep = -1; var cMag = -1; var cPlace = -1; var cType = -1
        var header = false
        val out = ArrayList<OfficialAlert>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val f = line.split('|').map { it.trim() }
            if (line.startsWith("#")) {
                f.forEachIndexed { i, n0 ->
                    val n = n0.removePrefix("#").lowercase(Locale.US)
                    when {
                        n.contains("eventid") -> cId = i
                        n.contains("time") -> if (cTime < 0) cTime = i
                        n.contains("latitude") -> cLat = i
                        n.contains("longitude") -> cLon = i
                        n.contains("depth") -> cDep = i
                        n.contains("magtype") || n.contains("magauthor") -> {}
                        n.contains("magnitude") -> cMag = i
                        n.contains("locationname") -> cPlace = i
                        n.contains("eventtype") -> cType = i
                    }
                }
                header = cTime >= 0 && cLat >= 0 && cLon >= 0 && cMag >= 0
                continue
            }
            if (!header) { cId = 0; cTime = 1; cLat = 2; cLon = 3; cDep = 4; cMag = 10; cPlace = 12; cType = 13; header = true }
            fun at(i: Int) = f.getOrNull(i) ?: ""
            val lat = at(cLat).toDoubleOrNull() ?: continue
            val lon = at(cLon).toDoubleOrNull() ?: continue
            val mag = at(cMag).toDoubleOrNull() ?: continue
            if (at(cType).isNotEmpty() && !at(cType).contains("earthquake", true)) continue
            val t = parseIso(at(cTime)).takeIf { it != 0L } ?: continue
            out.add(quake(src.code + "/" + at(cId).ifEmpty { at(cTime) }, src, mag, at(cPlace), lat, lon, at(cDep).toDoubleOrNull() ?: 0.0, t))
        }
        return out
    }

    private fun quake(id: String, src: AlertSrc, mag: Double, place: String, lat: Double, lon: Double, depth: Double, t: Long) =
        OfficialAlert(id, src, 0, String.format(Locale.US, "M%.1f Earthquake", mag), place, String.format(Locale.US, "Depth %.0f km", abs(depth)),
            "", t, t, if (t != 0L) t + 86400 else 0, lat, lon)

    fun usgs(json: String): List<OfficialAlert> {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return emptyList()
        return o.objList("features").mapNotNull { f ->
            @Suppress("UNCHECKED_CAST") val p = f["properties"] as? Map<String, Any?> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST") val c = ((f["geometry"] as? Map<String, Any?>)?.get("coordinates") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
            if ((p.str("type") ?: "earthquake") != "earthquake" || c == null || c.size < 2) return@mapNotNull null
            quake("usgs/" + (f.str("id") ?: ""), AlertSrc.USGS, p.num("mag") ?: 0.0, p.str("place") ?: "", c[1], c[0], c.getOrElse(2) { 0.0 },
                ((p.num("time") ?: 0.0) / 1000).toLong())
        }
    }

    fun emsc(json: String): List<OfficialAlert> {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return emptyList()
        return o.objList("features").mapNotNull { f ->
            @Suppress("UNCHECKED_CAST") val p = f["properties"] as? Map<String, Any?> ?: return@mapNotNull null
            quake("emsc/" + (p.str("unid") ?: f.str("id") ?: ""), AlertSrc.EMSC, p.num("mag") ?: 0.0, p.str("flynn_region") ?: "",
                p.num("lat") ?: return@mapNotNull null, p.num("lon") ?: return@mapNotNull null, p.num("depth") ?: 0.0, parseIso(p.str("time")))
        }
    }

    /** 3 red: M6 < 300 km or M7 < 1000 km; 2 orange: M5 < 300, M6 < 1000, M7.5 < 3000; 1 yellow: M4 < 100, M5 < 600, M6 < 2000; else 0. */
    fun quakeLevel(m: Double, km: Double): Int = when {
        (m >= 6 && km < 300) || (m >= 7 && km < 1000) -> 3
        (m >= 5 && km < 300) || (m >= 6 && km < 1000) || (m >= 7.5 && km < 3000) -> 2
        (m >= 4 && km < 100) || (m >= 5 && km < 600) || (m >= 6 && km < 2000) -> 1
        else -> 0
    }

    fun quakeMag(a: OfficialAlert): Double = if (a.event.startsWith("M")) a.event.substring(1).substringBefore(' ').toDoubleOrNull() ?: 0.0 else 0.0

    fun quakeSame(a: OfficialAlert, b: OfficialAlert): Boolean {
        val la = a.lat ?: return false; val lo = a.lon ?: return false
        val lb = b.lat ?: return false; val ob = b.lon ?: return false
        return Geo.distanceKm(la, lo, lb, ob) < 60 && abs(a.onset - b.onset) < 90 && abs(quakeMag(a) - quakeMag(b)) < 0.6
    }

    private val CARD = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    private fun rank(s: AlertSrc) = when (s) { AlertSrc.EMSC -> 2; AlertSrc.USGS -> 1; else -> 0 }

    /** Levels and "Depth 10 km, 245 km NE" from the own position; drops the duplicates (EMSC first: more local data; then USGS, then ShakeNet). */
    fun quakeLevels(list: List<OfficialAlert>, lat: Double?, lon: Double?): List<OfficialAlert> {
        val q = ArrayList<OfficialAlert>()
        for (a in list) {
            val k = q.indexOfFirst { quakeSame(it, a) }
            if (k < 0) q.add(a) else if (rank(a.src) > rank(q[k].src)) q[k] = a
        }
        return q.map { a ->
            val depth = a.text.substringBefore(",")
            if (lat == null || lon == null || a.lat == null || a.lon == null) a.copy(level = quakeLevel(quakeMag(a), 1e9))
            else {
                val km = Geo.distanceKm(lat, lon, a.lat, a.lon)
                val brg = Geo.bearingDeg(lat, lon, a.lat, a.lon)
                a.copy(level = quakeLevel(quakeMag(a), km), text = String.format(Locale.US, "%s, %.0f km %s", depth, km, CARD[(floor((brg + 22.5) / 45.0).toInt()) % 8]))
            }
        }
    }

    // ---------------------------------------------------------------- SWPC
    fun scaleName(b: Char) = when (b) { 'G' -> "Geomagnetic storm"; 'R' -> "Radio blackout"; 'S' -> "Radiation storm"; else -> "Space weather" }

    fun kpToG(kp: Double) = when { kp >= 9 -> 5; kp >= 8 -> 4; kp >= 7 -> 3; kp >= 6 -> 2; kp >= 5 -> 1; else -> 0 }

    /** R scale of an X-ray flux (W/m2): M1 R1, M5 R2, X1 R3, X10 R4, X20 R5. Same as xrayToR of the plotter core. */
    fun xrayToR(f: Double) = when { f >= 2e-3 -> 5; f >= 1e-3 -> 4; f >= 1e-4 -> 3; f >= 5e-5 -> 2; f >= 1e-5 -> 1; else -> 0 }

    /**
     * NOAA (SWPC) scale colours as on its website, 0xRRGGBB: 1 yellow, 2 light orange, 3 orange, 4 red, 5 dark red; 0 for level 0
     * (NOAA gives it no colour: each screen uses its own). Same as noaaScaleRgb of the plotter core.
     */
    fun noaaScaleRgb(level: Int): Int = NOAA_RGB[level.coerceIn(0, 5)]
    private val NOAA_RGB = intArrayOf(0, 0xFFFF00, 0xFFC800, 0xFF9600, 0xFF0000, 0xC80000)

    fun xrayClass(w: Double): String {
        if (!(w > 0)) return ""
        val k = (floor(log10(w)).toInt() + 8).coerceIn(0, 4)
        return String.format(Locale.US, "%c%.1f", "ABCMX"[k], w / 10.0.pow(k - 8))
    }

    private fun numOf(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.trim()?.toDoubleOrNull()

    @Suppress("UNCHECKED_CAST")
    fun swpcScales(json: String, w: SpaceWx): SpaceWx? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val t = o["0"] as? Map<String, Any?> ?: return null
        fun sc(d: Map<String, Any?>?, k: String) = numOf((d?.get(k) as? Map<String, Any?>)?.get("Scale"))?.toInt() ?: -1
        val gMax = listOf("1", "2", "3").maxOf { sc(o[it] as? Map<String, Any?>, "G") }
        return w.copy(g = sc(t, "G"), r = sc(t, "R"), s = sc(t, "S"), gMax = gMax)
    }

    fun swpcKp(json: String, w: SpaceWx): SpaceWx? {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return null
        val rows = arr.mapNotNull { r ->
            when (r) {
                is List<*> -> { val t = r.getOrNull(0) as? String; if (t == null || !t[0].isDigit()) null else parseIso(t) to (numOf(r.getOrNull(1)) ?: -1.0) }
                is Map<*, *> -> parseIso(r["time_tag"] as? String) to (numOf(r["Kp"] ?: r["kp"]) ?: -1.0)
                else -> null
            }
        }.filter { it.second >= 0 && it.first != 0L }.sortedBy { it.first }
        if (rows.isEmpty()) return null
        return w.copy(kp = rows.takeLast(8).map { it.second }, kpTime = rows.last().first)
    }

    fun swpcSummary(json: String, w: SpaceWx): SpaceWx? {
        val v = runCatching { Json.parse(json) }.getOrNull()
        @Suppress("UNCHECKED_CAST") val o = (if (v is List<*>) v.firstOrNull() else v) as? Map<String, Any?> ?: return null
        var r = w
        var any = false
        numOf(o["Flux"])?.let { r = r.copy(sfi = it); any = true }
        numOf(o["Bz"])?.let { r = r.copy(bz = it); any = true }
        numOf(o["Bt"])?.let { r = r.copy(bt = it); any = true }
        numOf(o["WindSpeed"])?.let { r = r.copy(windKms = it); any = true }
        return if (any) r else null
    }

    /** Newest RTSW 1-min row with a value (the active spacecraft first, as SWPC marks it). Objects or rows with a header. */
    private fun rtswRows(json: String): List<Map<String, Any?>> {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return emptyList()
        val first = arr.firstOrNull()
        val rows: List<Map<String, Any?>> = if (first is List<*>) {
            val head = first.map { it?.toString()?.lowercase() ?: "" }
            arr.drop(1).mapNotNull { r -> (r as? List<*>)?.let { row -> head.indices.associate { head[it] to row.getOrNull(it) } } }
        } else arr.mapNotNull { r -> @Suppress("UNCHECKED_CAST") (r as? Map<String, Any?>) }
        val newest = rows.sortedByDescending { it["time_tag"]?.toString() ?: "" }
        return newest.filter { it["active"] != false } + newest.filter { it["active"] == false }
    }

    fun rtswWind(json: String, w: SpaceWx): SpaceWx? {
        for (r in rtswRows(json)) {
            val v = numOf(r["proton_speed"] ?: r["speed"]) ?: continue
            val n = numOf(r["proton_density"] ?: r["density"])
            if (v <= 0) continue
            return w.copy(windKms = v, density = n ?: w.density, tempK = numOf(r["proton_temperature"] ?: r["temperature"]) ?: w.tempK,
                windTime = parseIso(r["time_tag"]?.toString()).takeIf { it > 0 } ?: w.windTime)
        }
        return null
    }

    fun rtswMag(json: String, w: SpaceWx): SpaceWx? {
        for (r in rtswRows(json)) {
            val bz = numOf(r["bz_gsm"] ?: r["bz_gse"] ?: r["bz"]) ?: continue
            return w.copy(bz = bz, bt = numOf(r["bt"]) ?: w.bt)
        }
        return null
    }

    /** Solar wind dynamic pressure (nPa) = m_p * n * v^2 = 1.6726e-6 * n (cm-3) * v^2 (km/s); null without both. */
    fun windPressureNpa(w: SpaceWx): Double? {
        val n = w.density ?: return null
        val v = w.windKms ?: return null
        return 1.6726e-6 * n * v * v
    }

    /** Last data line of SWPC's daily-solar-indices.txt: "yyyy mm dd flux ssn ..." -> (10.7 cm flux, sunspot number), as sat-aprs. */
    fun swpcDailyIndices(text: String): Pair<Double?, Double?>? {
        for (line in text.lines().asReversed()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(":")) continue
            val p = t.split(Regex("\\s+"))
            if (p.size < 5 || p[0].length != 4 || p[0].toIntOrNull() == null) continue
            val flux = p[3].toDoubleOrNull()?.takeIf { it > 0 }
            val ssn = p[4].toDoubleOrNull()?.takeIf { it >= 0 }
            if (flux == null && ssn == null) continue
            return flux to ssn
        }
        return null
    }

    /** The latest sunspot number of either SWPC solar-cycle feed (daily "swpc_ssn" or monthly "ssn"); null if none. */
    fun swpcSsn(json: String): Double? {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return null
        for (e in arr.asReversed()) {
            val o = e as? Map<*, *> ?: continue
            val v = numOf(o["swpc_ssn"]) ?: numOf(o["ssn"]) ?: continue
            if (v >= 0) return v
        }
        return null
    }

    fun swpcXray(json: String, w: SpaceWx): SpaceWx? {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return null
        val last = arr.lastOrNull { (it as? Map<*, *>)?.get("energy") == "0.1-0.8nm" && (numOf((it as Map<*, *>)["flux"]) ?: 0.0) > 0 } as? Map<*, *> ?: return null
        return w.copy(xray = xrayClass(numOf(last["flux"]) ?: 0.0))
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun swpcTime(s: String): Long {
        val m = Regex("""(\d{4}) (\w{3}) (\d{1,2}) (\d{4})""").find(s) ?: return 0
        val mo = MONTHS.indexOf(m.groupValues[2]); if (mo < 0) return 0
        val hm = m.groupValues[4].toInt()
        return unixFromUtc(m.groupValues[1].toInt(), mo + 1, m.groupValues[3].toInt(), hm / 100, hm % 100)
    }

    private fun lineAfter(msg: String, tag: String): String {
        val p = msg.indexOf(tag); if (p < 0) return ""
        return msg.substring(p + tag.length).substringBefore('\n').substringBefore('\r').trim()
    }

    /** SWPC ALERT / WARNING / WATCH of the last 48 h (cancelled ones and replaced warnings out). Level by scale: 1-2 yellow, 3 orange, 4-5 red. */
    fun swpcAlerts(json: String, nowS: Long): List<OfficialAlert> {
        val arr = runCatching { Json.parse(json) as? List<*> }.getOrNull() ?: return emptyList()
        val cancelled = HashSet<String>()
        val found = ArrayList<OfficialAlert>()
        for (it in arr) {
            val p = it as? Map<*, *> ?: continue
            val msg = p["message"] as? String ?: continue
            val code = lineAfter(msg, "Space Weather Message Code:")
            val issued = parseIso(p["issue_datetime"] as? String)
            if (nowS != 0L && issued != 0L && nowS - issued > 48 * 3600) continue
            var type = ""; var title = ""
            for (t in listOf("EXTENDED WARNING:", "CANCEL WARNING:", "CANCEL WATCH:", "CONTINUED ALERT:", "ALERT:", "WARNING:", "WATCH:", "SUMMARY:")) {
                val at = Regex("(^|\\n)" + Regex.escape(t)).find(msg)
                if (at != null) { type = t; title = lineAfter(msg.substring(at.range.first), t); break }
            }
            if (type.isEmpty() || type == "SUMMARY:") continue
            if (type.startsWith("CANCEL")) { lineAfter(msg, "Cancel Serial Number:").takeIf { it.isNotEmpty() }?.let { s -> cancelled.add(s) }; continue }
            if (type == "EXTENDED WARNING:") lineAfter(msg, "Extension to Serial Number:").takeIf { it.isNotEmpty() }?.let { s -> cancelled.add(s) }
            var scale = lineAfter(msg, "NOAA Scale:")
            if (scale.isEmpty() && type == "WATCH:") Regex("Category ([GRS]\\d)").find(msg)?.let { m -> scale = m.groupValues[1] }
            val sv = if (scale.length >= 2 && scale[0] in "GRS" && scale[1].isDigit()) scale[1] - '0' else 0
            val area = if (sv > 0) scale.substring(0, 2) + " " + scaleName(scale[0]) else "Space weather"
            val onset = swpcTime(lineAfter(msg, "Valid From:")).takeIf { it != 0L } ?: issued
            var exp = swpcTime(lineAfter(msg, "Valid To:")).takeIf { it != 0L } ?: swpcTime(lineAfter(msg, "Now Valid Until:"))
            if (exp == 0L && issued != 0L) exp = issued + (if (type == "ALERT:" || type == "CONTINUED ALERT:") 6 else 24) * 3600L
            if (nowS != 0L && exp != 0L && exp < nowS) continue
            val label = type.dropLast(1).lowercase(Locale.ROOT).replaceFirstChar { c -> c.uppercase() }
            found.add(OfficialAlert("swpc/$code/" + lineAfter(msg, "Serial Number:"), AlertSrc.SWPC, if (sv >= 4) 3 else if (sv == 3) 2 else if (sv >= 1) 1 else 0,
                title, area, label, "", issued, onset, exp))
        }
        return found.filter { a -> cancelled.none { a.id.endsWith("/$it") } }
    }

    // ---------------------------------------------------------------- tracker commands
    /** The "alert" command for the tracker (ASCII, cut to its sizes; the id is shortened to fit the 256-byte line). */
    fun command(a: OfficialAlert): Map<String, Any?> {
        val id = if (a.id.length <= 40) a.id else a.src.code + "/" + String.format("%08x", a.id.hashCode())
        val m = linkedMapOf<String, Any?>("id" to id, "s" to a.src.code, "lv" to a.level, "ev" to asciiFold(a.event).take(32),
            "ar" to asciiFold(a.area).take(40), "tx" to asciiFold(a.text).take(60), "on" to a.onset, "ex" to a.expires)
        if (a.lat != null && a.lon != null) { m["la"] = (a.lat * 1e4).roundToLong() / 1e4; m["lo"] = (a.lon * 1e4).roundToLong() / 1e4 }
        return m
    }

    fun trackerId(a: OfficialAlert): String = command(a)["id"] as String

    fun spaceCommand(w: SpaceWx): Map<String, Any?> = linkedMapOf<String, Any?>("g" to w.g, "r" to w.r, "s" to w.s, "gm" to w.gMax,
        "kp" to w.kp.map { (it * 100).roundToLong() / 100.0 }, "kt" to w.kpTime).apply {
        w.sfi?.let { put("sfi", it.roundToLong()) }
        w.bz?.let { put("bz", (it * 10).roundToLong() / 10.0) }
        w.bt?.let { put("bt", (it * 10).roundToLong() / 10.0) }
        w.windKms?.let { put("ws", it.roundToLong()) }
        if (w.xray.isNotEmpty()) put("xr", w.xray)
        w.ssn?.let { put("sn", it.roundToLong()) }
    }

    /** An alert sent by the tracker (event "alert": SAME decoded by the T-TWR) or in its "alerts" list. */
    fun fromEvent(o: Map<String, Any?>): OfficialAlert? {
        val id = o.str("id") ?: return null
        return OfficialAlert(id, AlertSrc.of(o.str("s")), (o.int("lv") ?: 0).coerceIn(0, 3), o.str("ev") ?: "", o.str("ar") ?: "", o.str("tx") ?: "",
            "", o.num("on")?.toLong() ?: 0, o.num("on")?.toLong() ?: 0, o.num("ex")?.toLong() ?: 0, o.num("la"), o.num("lo"))
    }
}

// ==================================================================== SAME (NOAA Weather Radio)

data class SameMsg(val raw: String, val org: String, val event: String, val fips: List<String>, val durationMin: Int,
                   val julianDay: Int, val hour: Int, val minute: Int, val sender: String)

object Same {
    /** NOAA Weather Radio channels: WX1 162.550 ... WX7 162.525 MHz. */
    val NWR_MHZ = doubleArrayOf(162.550, 162.400, 162.475, 162.425, 162.450, 162.500, 162.525)

    fun nwrChannel(mhz: Double): Int = NWR_MHZ.indexOfFirst { abs(it - mhz) < 0.003 } + 1

    private val EVENTS = mapOf(
        "EAN" to ("Emergency Action Notification" to 3), "EAT" to ("Emergency Action Termination" to 0), "NIC" to ("National Information Center" to 0),
        "NPT" to ("National Periodic Test" to 0), "RMT" to ("Required Monthly Test" to 0), "RWT" to ("Required Weekly Test" to 0),
        "ADR" to ("Administrative Message" to 0), "DMO" to ("Practice/Demo Warning" to 0), "NMN" to ("Network Message Notification" to 0),
        "AVW" to ("Avalanche Warning" to 3), "AVA" to ("Avalanche Watch" to 1), "BZW" to ("Blizzard Warning" to 3), "BLU" to ("Blue Alert" to 1),
        "CAE" to ("Child Abduction Emergency" to 2), "CDW" to ("Civil Danger Warning" to 3), "CEM" to ("Civil Emergency Message" to 3),
        "CFW" to ("Coastal Flood Warning" to 2), "CFA" to ("Coastal Flood Watch" to 1), "DSW" to ("Dust Storm Warning" to 2),
        "EQW" to ("Earthquake Warning" to 3), "EVI" to ("Evacuation Immediate" to 3), "EWW" to ("Extreme Wind Warning" to 3),
        "FRW" to ("Fire Warning" to 3), "FFW" to ("Flash Flood Warning" to 3), "FFA" to ("Flash Flood Watch" to 1), "FFS" to ("Flash Flood Statement" to 1),
        "FLW" to ("Flood Warning" to 2), "FLA" to ("Flood Watch" to 1), "FLS" to ("Flood Statement" to 1), "HMW" to ("Hazardous Materials Warning" to 3),
        "HWW" to ("High Wind Warning" to 2), "HWA" to ("High Wind Watch" to 1), "HUW" to ("Hurricane Warning" to 3), "HUA" to ("Hurricane Watch" to 2),
        "HLS" to ("Hurricane Statement" to 1), "LEW" to ("Law Enforcement Warning" to 2), "LAE" to ("Local Area Emergency" to 2),
        "MEP" to ("Missing and Endangered Persons" to 1), "TOE" to ("911 Telephone Outage Emergency" to 2), "NUW" to ("Nuclear Power Plant Warning" to 3),
        "RHW" to ("Radiological Hazard Warning" to 3), "SVR" to ("Severe Thunderstorm Warning" to 2), "SVA" to ("Severe Thunderstorm Watch" to 1),
        "SVS" to ("Severe Weather Statement" to 1), "SPW" to ("Shelter in Place Warning" to 3), "SMW" to ("Special Marine Warning" to 2),
        "SPS" to ("Special Weather Statement" to 1), "SSA" to ("Storm Surge Watch" to 2), "SSW" to ("Storm Surge Warning" to 3),
        "TOR" to ("Tornado Warning" to 3), "TOA" to ("Tornado Watch" to 2), "TRW" to ("Tropical Storm Warning" to 2), "TRA" to ("Tropical Storm Watch" to 1),
        "TSW" to ("Tsunami Warning" to 3), "TSA" to ("Tsunami Watch" to 2), "VOW" to ("Volcano Warning" to 3), "WSW" to ("Winter Storm Warning" to 2),
        "WSA" to ("Winter Storm Watch" to 1), "EWA" to ("Extreme Wind Watch" to 1), "FSW" to ("Flash Freeze Warning" to 2), "FZW" to ("Freeze Warning" to 1),
        "SQW" to ("Snow Squall Warning" to 2), "WFW" to ("Wildfire Warning" to 3), "WFA" to ("Wildfire Watch" to 1),
    )

    fun eventName(code: String): String? = EVENTS[code]?.first

    fun eventLevel(code: String): Int = EVENTS[code]?.second ?: when (code.getOrNull(2)) { 'W', 'E' -> 2; 'A', 'S' -> 1; else -> 0 }

    fun orgName(o: String) = when (o) { "WXR" -> "National Weather Service"; "CIV" -> "Civil authorities"; "EAS" -> "Broadcast station"; "PEP" -> "Primary Entry Point"; else -> "" }

    /** Length of a complete header at the start of [s], 0 when there is none. */
    fun headerEnd(s: String): Int {
        if (!s.startsWith("ZCZC-")) return 0
        val plus = s.indexOf('+'); if (plus < 0) return 0
        fun digits(at: Int, n: Int) = at + n <= s.length && (0 until n).all { s[at + it].isDigit() }
        if (!digits(plus + 1, 4) || s.getOrNull(plus + 5) != '-') return 0
        if (!digits(plus + 6, 7) || s.getOrNull(plus + 13) != '-') return 0
        if (s.getOrNull(plus + 22) != '-') return 0
        return plus + 23
    }

    fun parse(h: String): SameMsg? {
        val end = headerEnd(h); if (end == 0) return null
        val raw = h.substring(0, end)
        if (raw.length < 13 || raw[8] != '-' || raw[12] != '-') return null
        val plus = raw.indexOf('+')
        val fips = raw.substring(13, plus).split('-').filter { it.isNotEmpty() }
        if (fips.isEmpty() || fips.size > 31 || fips.any { it.length != 6 || !it.all(Char::isDigit) }) return null
        val org = raw.substring(5, 8); val ev = raw.substring(9, 12)
        if (!(org + ev).all { it in 'A'..'Z' }) return null
        val jd = raw.substring(plus + 6, plus + 9).toInt(); val hh = raw.substring(plus + 9, plus + 11).toInt(); val mm = raw.substring(plus + 11, plus + 13).toInt()
        if (jd !in 1..366 || hh > 23 || mm > 59) return null
        return SameMsg(raw, org, ev, fips, raw.substring(plus + 1, plus + 3).toInt() * 60 + raw.substring(plus + 3, plus + 5).toInt(), jd, hh, mm,
            raw.substring(plus + 14, plus + 22).trimEnd())
    }

    fun toAlert(m: SameMsg, nowS: Long): OfficialAlert {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowS * 1000 }
        var y = cal.get(java.util.Calendar.YEAR)
        fun at(y: Int) = OfficialAlerts.unixFromUtc(y, 1, 1) + (m.julianDay - 1) * 86400L + m.hour * 3600 + m.minute * 60
        var t = at(y)
        if (nowS != 0L && t > nowS + 183L * 86400) { y--; t = at(y) }
        val area = m.fips.take(6).joinToString(" ") + if (m.fips.size > 6) " +${m.fips.size - 6}" else ""
        val org = orgName(m.org)
        return OfficialAlert("same/" + m.raw, AlertSrc.SAME, eventLevel(m.event), eventName(m.event) ?: "SAME ${m.event}", area,
            m.sender + if (org.isNotEmpty()) " ($org)" else "", "", t, t, t + m.durationMin * 60L)
    }

    /** Test signal: preamble, text, [repeats] times with 1 s between. */
    fun synth(text: String, fs: Double, repeats: Int = 3): FloatArray {
        val out = ArrayList<Float>()
        val spb = fs / BAUD
        var ph = 0.0
        val frame = ByteArray(16) { 0xAB.toByte() } + text.toByteArray(Charsets.US_ASCII)
        repeat(repeats) {
            var acc = 0.0
            for (b in frame) for (k in 0 until 8) {
                val f = if ((b.toInt() shr k) and 1 == 1) MARK else SPACE
                acc += spb
                val n = floor(acc).toInt(); acc -= n
                repeat(n) { ph += 2 * Math.PI * f / fs; out.add((0.5 * kotlin.math.sin(ph)).toFloat()) }
            }
            repeat(fs.toInt()) { out.add(0f) }
        }
        return out.toFloatArray()
    }

    const val BAUD = 520.0 + 5.0 / 6.0
    const val MARK = 2083.0 + 1.0 / 3.0
    const val SPACE = 1562.5
}

/** SAME demodulator, the same as the plotter's core (same.cpp): one-bit correlators, a DPLL, 0xAB sync, 2-of-3 / majority / timeout. */
class SameDecoder(private val fs: Double) {
    private val n = Math.round(fs / Same.BAUD).toInt().coerceAtLeast(4)
    private val step = (Same.BAUD / fs).toFloat()
    private val mwr = kotlin.math.cos(2 * Math.PI * Same.MARK / fs).toFloat(); private val mwi = (-kotlin.math.sin(2 * Math.PI * Same.MARK / fs)).toFloat()
    private val swr = kotlin.math.cos(2 * Math.PI * Same.SPACE / fs).toFloat(); private val swi = (-kotlin.math.sin(2 * Math.PI * Same.SPACE / fs)).toFloat()
    private val ring = FloatArray(n * 4)
    private var idx = 0
    private var mr = 1f; private var mi = 0f; private var sr = 1f; private var si = 0f
    private var aMI = 0f; private var aMQ = 0f; private var aSI = 0f; private var aSQ = 0f
    private var phase = 0f; private var prevD = 0f
    private var shift = 0; private var synced = false; private var bitCount = 0
    private val burst = StringBuilder()
    private var t = 0L
    private var renorm = 0
    private data class Burst(val text: String, val t: Long)
    private val recent = ArrayList<Burst>()
    private var emitted = false
    private var lastEmit = ""; private var lastEmitT = 0L
    private val out = ArrayList<String>()
    var bursts = 0; private set

    fun feed(x: FloatArray, from: Int = 0, count: Int = x.size - from) { for (i in from until from + count) sample(x[i]); timeouts() }

    fun feed(x: ShortArray, count: Int = x.size) { for (i in 0 until count) sample(x[i] / 32768f); timeouts() }

    fun poll(): String? { timeouts(); return if (out.isEmpty()) null else out.removeAt(0) }

    fun sample(x: Float) {
        t++
        var r = mr * mwr - mi * mwi; mi = mr * mwi + mi * mwr; mr = r
        r = sr * swr - si * swi; si = sr * swi + si * swr; sr = r
        if (++renorm >= 256) {
            renorm = 0
            var k = 1f / kotlin.math.sqrt(mr * mr + mi * mi); mr *= k; mi *= k
            k = 1f / kotlin.math.sqrt(sr * sr + si * si); sr *= k; si *= k
        }
        val b = idx * 4
        val vMI = x * mr; val vMQ = x * mi; val vSI = x * sr; val vSQ = x * si
        aMI += vMI - ring[b]; aMQ += vMQ - ring[b + 1]; aSI += vSI - ring[b + 2]; aSQ += vSQ - ring[b + 3]
        ring[b] = vMI; ring[b + 1] = vMQ; ring[b + 2] = vSI; ring[b + 3] = vSQ
        if (++idx >= n) idx = 0
        val em = aMI * aMI + aMQ * aMQ; val es = aSI * aSI + aSQ * aSQ
        val d = (em - es) / (em + es + 1e-12f)
        if ((d > 0) != (prevD > 0)) phase -= (phase - 0.5f) * 0.3f
        prevD = d
        phase += step
        if (phase >= 1f) { phase -= 1f; bit(if (d > 0) 1 else 0) }
    }

    private fun bit(b: Int) {
        shift = (shift shr 1) or (if (b != 0) 0x80 else 0)
        if (!synced) { if (shift == 0xAB) { synced = true; bitCount = 0; burst.setLength(0) }; return }
        if (++bitCount < 8) return
        bitCount = 0
        val c = shift
        if (c == 0xAB && burst.isEmpty()) return
        if (c < 0x20 || c > 0x7E || burst.length > 280) { endBurst(); return }
        burst.append(c.toChar())
        if (burst.startsWith("NNNN") || Same.headerEnd(burst.toString()) > 0) endBurst()
    }

    private fun endBurst() {
        synced = false
        val s = burst.toString(); burst.setLength(0)
        if (s.startsWith("NNNN")) { bursts++; if (!(lastEmit == "NNNN" && t - lastEmitT < fs * 10)) emit("NNNN"); return }
        if (!s.startsWith("ZCZC")) return
        bursts++
        val end = Same.headerEnd(s)
        addBurst(if (end > 0) s.substring(0, end) else s)
    }

    private fun addBurst(s: String) {
        recent.removeAll { t - it.t > fs * 12 }
        if (recent.isEmpty()) emitted = false
        if (emitted) { recent.add(Burst(s, t)); return }
        if (recent.any { it.text == s } && Same.headerEnd(s) > 0) { emit(s); emitted = true; recent.add(Burst(s, t)); return }
        recent.add(Burst(s, t))
        if (recent.size >= 3) {
            val (a, b, c) = recent.takeLast(3).map { it.text }
            val sb = StringBuilder()
            for (i in 0 until maxOf(a.length, b.length, c.length)) {
                val x = a.getOrNull(i); val y = b.getOrNull(i); val z = c.getOrNull(i)
                val m = if (x == y || x == z) x else if (y == z) y else x
                sb.append(m ?: break)
            }
            val v = sb.toString()
            Same.parse(v)?.let { emit(it.raw); emitted = true }
        }
    }

    private fun timeouts() {
        if (emitted || recent.isEmpty() || t - recent[0].t < fs * 6) return
        recent.firstNotNullOfOrNull { Same.parse(it.text) }?.let { emit(it.raw); emitted = true; return }
        recent.clear()
    }

    private fun emit(s: String) {
        if (s == lastEmit && t - lastEmitT < fs * 120) return
        lastEmit = s; lastEmitT = t
        out.add(s)
    }
}
