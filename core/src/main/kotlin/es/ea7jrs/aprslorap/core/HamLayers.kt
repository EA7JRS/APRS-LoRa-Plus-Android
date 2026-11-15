/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Map layers whose idea and data sources come from OpenHamClock (accius/openhamclock, MIT licence): readers of the public
 * feeds and the computed layers. Sources: NOAA SWPC (aurora OVATION, D-RAP), NOAA NHC (tropical cyclones), NOAA NDBC
 * (buoys), NASA EONET (wildfires, floods), SondeHub (balloons), wspr.live and PSK Reporter (spots); the NCDXF / IARU beacon schedule, Maidenhead grid and great circles are
 * computed. Formats as OpenHamClock reads them (2026).
 */
object HamLayers {
    private fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.trim()?.toDoubleOrNull()

    // ------------------------------------------------------------------ aurora (NOAA SWPC OVATION, 1° grid)

    data class Aurora(val forecastTime: String, val cells: List<Triple<Double, Double, Int>>)   // lat, lon (-180..180), %

    const val OVATION_URL = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json"

    /** Keeps the cells of at least [minPct] % ({"Forecast Time", "coordinates": [[lon 0..359, lat, %], ...]}). */
    fun parseOvation(json: String, minPct: Int = 3): Aurora? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val c = o["coordinates"] as? List<*> ?: return null
        val cells = c.mapNotNull { e ->
            val l = e as? List<*> ?: return@mapNotNull null
            val lon = num(l.getOrNull(0)) ?: return@mapNotNull null; val lat = num(l.getOrNull(1)) ?: return@mapNotNull null
            val p = num(l.getOrNull(2))?.toInt() ?: return@mapNotNull null
            if (p < minPct) null else Triple(lat, if (lon > 180) lon - 360 else lon, p)
        }
        return Aurora(o["Forecast Time"] as? String ?: "", cells)
    }

    // ------------------------------------------------------------------ D-RAP (NOAA SWPC HF absorption)

    data class Drap(val validAt: String, val lats: List<Double>, val lons: List<Double>, val freqs: List<List<Double>>) {
        val maxFreq: Double get() = freqs.maxOfOrNull { r -> r.maxOrNull() ?: 0.0 } ?: 0.0
    }

    const val DRAP_URL = "https://services.swpc.noaa.gov/text/drap_global_frequencies.txt"

    /** "#" comments (Product Valid At), a longitude row, then "<lat> | <MHz> <MHz> ..." rows (highest affected frequency). */
    fun parseDrap(text: String): Drap? {
        var valid = ""; var lons: List<Double>? = null
        val lats = ArrayList<Double>(); val rows = ArrayList<List<Double>>()
        for (raw in text.lines()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#")) { Regex("Product Valid At\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { valid = it.groupValues[1].trim() }; continue }
            if (t.all { it == '-' }) continue
            if ('|' in t) {
                val lat = t.substringBefore('|').trim().toDoubleOrNull() ?: continue
                val row = t.substringAfter('|').trim().split(Regex("\\s+")).map { it.toDoubleOrNull() ?: 0.0 }
                if (lons != null && row.size != lons.size) continue
                lats.add(lat); rows.add(row)
            } else if (lons == null) {
                val v = t.split(Regex("\\s+")).map { it.toDoubleOrNull() }
                if (v.size > 10 && v.all { it != null }) lons = v.map { it!! }
            }
        }
        val l = lons ?: return null
        return if (lats.isEmpty()) null else Drap(valid, lats, l, rows)
    }

    // ------------------------------------------------------------------ tropical cyclones (NOAA NHC)

    data class Storm(
        val id: String, val name: String, val classification: String, val windKt: Int?, val pressureMb: Int?,
        val lat: Double, val lon: Double, val movementDeg: Int?, val movementKt: Int?, val lastUpdate: String,
        val trackKmz: String?, val coneKmz: String?, val advisoryUrl: String?,
        val forecast: List<ForecastPoint> = emptyList(), val track: List<Pair<Double, Double>> = emptyList(), val cone: List<Pair<Double, Double>> = emptyList(),
    ) {
        /** Saffir-Simpson category from the wind (0 = tropical storm or less). */
        val category: Int get() = when (windKt ?: 0) { in 137..999 -> 5; in 113..136 -> 4; in 96..112 -> 3; in 83..95 -> 2; in 64..82 -> 1; else -> 0 }
        val label: String get() = when (classification) {
            "HU" -> "Hurricane"; "MH" -> "Major hurricane"; "TS" -> "Tropical storm"; "TD" -> "Tropical depression"
            "STS", "SS" -> "Subtropical storm"; "STD", "SD" -> "Subtropical depression"; "PTC" -> "Potential tropical cyclone"
            "PC", "PTS" -> "Post-tropical cyclone"; else -> classification
        }
    }

    data class ForecastPoint(val hour: Int?, val validAt: String, val lat: Double, val lon: Double, val windKt: Int?)

    const val NHC_URL = "https://www.nhc.noaa.gov/CurrentStorms.json"

    fun parseNhc(json: String): List<Storm>? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val a = o["activeStorms"] as? List<*> ?: return null
        return a.mapNotNull { e ->
            val s = e as? Map<*, *> ?: return@mapNotNull null
            val lat = num(s["latitudeNumeric"]) ?: return@mapNotNull null; val lon = num(s["longitudeNumeric"]) ?: return@mapNotNull null
            fun kmz(k: String) = ((s[k] as? Map<*, *>)?.get("kmzFile") as? String)?.takeIf { it.startsWith("https://www.nhc.noaa.gov/") }
            Storm((s["id"] as? String ?: "").lowercase(), s["name"] as? String ?: "Unnamed", s["classification"] as? String ?: "",
                num(s["intensity"])?.toInt(), num(s["pressure"])?.toInt(), lat, lon, num(s["movementDir"])?.toInt(), num(s["movementSpeed"])?.toInt(),
                s["lastUpdate"] as? String ?: "", kmz("forecastTrack"), kmz("trackCone"),
                ((s["publicAdvisory"] as? Map<*, *>)?.get("url") as? String))
        }
    }

    data class Placemark(val description: String, val point: Pair<Double, Double>?, val line: List<Pair<Double, Double>>, val polygon: List<Pair<Double, Double>>)

    /** "lon,lat,alt lon,lat,alt ..." → (lat, lon). */
    fun kmlCoordinates(text: String) = text.trim().split(Regex("\\s+")).mapNotNull { p ->
        val c = p.split(','); if (c.size < 2) null else c[1].toDoubleOrNull()?.let { la -> c[0].toDoubleOrNull()?.let { lo -> la to lo } }
    }

    private fun tag(xml: String, name: String): String? {
        val m = Regex("<(?:\\w+:)?$name\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE).find(xml) ?: return null
        return m.groupValues[1].replace(Regex("^\\s*<!\\[CDATA\\[|\\]\\]>\\s*$"), "")
    }

    fun kmlPlacemarks(kml: String): List<Placemark> = Regex("<Placemark(?:\\s[^>]*)?>([\\s\\S]*?)</Placemark>", RegexOption.IGNORE_CASE).findAll(kml).map { m ->
        val b = m.groupValues[1]
        val desc = (tag(b, "description") ?: "").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
        val point = tag(b, "Point")?.let { tag(it, "coordinates") }?.let { kmlCoordinates(it).firstOrNull() }
        val line = tag(b, "LineString")?.let { tag(it, "coordinates") }?.let { kmlCoordinates(it) } ?: emptyList()
        val poly = tag(b, "outerBoundaryIs")?.let { tag(it, "coordinates") }?.let { kmlCoordinates(it) } ?: emptyList()
        Placemark(desc, point, line, poly)
    }.toList()

    /** NHC forecast track KML: the line and the forecast points ("NN hr Forecast", "Valid at:", "Maximum Wind: NN knots"). */
    fun nhcTrack(kml: String): Pair<List<Pair<Double, Double>>, List<ForecastPoint>> {
        var line = emptyList<Pair<Double, Double>>()
        val pts = ArrayList<ForecastPoint>()
        for (p in kmlPlacemarks(kml)) {
            if (p.line.size > line.size) line = p.line
            val pt = p.point ?: continue
            val d = p.description
            val hour = Regex("(\\d+)\\s*hr\\s*Forecast", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.toInt()
                ?: if (Regex("Advisory Information|Initial|Current", RegexOption.IGNORE_CASE).containsMatchIn(d)) 0 else null
            val valid = Regex("Valid at:\\s*(.*?)(?:\\s+Location:|$)", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.trim() ?: ""
            val wind = Regex("Maximum Wind:\\s*(\\d+)\\s*knots", RegexOption.IGNORE_CASE).find(d)?.groupValues?.get(1)?.toInt()
            pts.add(ForecastPoint(hour, valid, pt.first, pt.second, wind))
        }
        return line to pts.sortedBy { it.hour ?: 999 }
    }

    /** NHC cone KML: the outer ring of the largest polygon. */
    fun nhcCone(kml: String) = kmlPlacemarks(kml).map { it.polygon }.maxByOrNull { it.size } ?: emptyList()

    // ------------------------------------------------------------------ NCDXF / IARU International Beacon Project

    /** A beacon of the project: slot order, entity, location, grid and sponsor as www.ncdxf.org/beacon/beaconlocations.html lists them. */
    data class Beacon(val call: String, val entity: String, val location: String, val grid: String, val sponsor: String) {
        private val pos = RotorFollow.locatorToLatLon(grid)
        /** Centre of the 6-character grid square (the page gives no coordinates). */
        val lat: Double get() = pos?.first ?: 0.0
        val lon: Double get() = pos?.second ?: 0.0
    }
    data class IbpBand(val mhz: Double, val label: String, val offset: Int)

    val BEACONS = listOf(
        Beacon("4U1UN", "United Nations", "New York City", "FN30as", "UNRC"), Beacon("VE8AT", "Canada", "Inuvik, NT", "CP38gh", "RAC / NARC"),
        Beacon("W6WX", "United States", "Mt. Umunhum", "CM97bd", "NCDXF"), Beacon("KH6RS", "Hawaii", "Maui", "BL10ts", "Maui ARC"),
        Beacon("ZL6B", "New Zealand", "Masterton", "RE78tw", "NZART"), Beacon("VK6RBP", "Australia", "Rolystone", "OF87av", "WIA"),
        Beacon("JA2IGY", "Japan", "Mt. Asama", "PM84jk", "JARL"), Beacon("RR9O", "Russia", "Novosibirsk", "NO14kx", "SRR"),
        Beacon("VR2B", "Hong Kong", "Hong Kong", "OL72bg", "HARTS"), Beacon("4S7B", "Sri Lanka", "Colombo", "MJ96wv", "RSSL"),
        Beacon("ZS6DN", "South Africa", "Pretoria", "KG33xi", "SARL (operated by ZS6YI)"), Beacon("5Z4B", "Kenya", "Kikuyu", "KI88hr", "ARSK"),
        Beacon("4X6TU", "Israel", "Tel Aviv", "KM72jb", "IARC"), Beacon("OH2B", "Finland", "Lohja", "KP20eh", "SRAL"),
        Beacon("CS3B", "Madeira", "São Jorge", "IM12jt", "Delegação Madeira"), Beacon("LU4AA", "Argentina", "Buenos Aires", "GF05tj", "RCA"),
        Beacon("OA4B", "Peru", "Lima", "FH17mw", "RCP"), Beacon("YV5B", "Venezuela", "Caracas", "FK60nd", "RCV"),
    )
    /** Each beacon starts on 14.100 and steps up one band every 10 s: the beacon on band N started N slots earlier. */
    val IBP_BANDS = listOf(IbpBand(14.100, "20m", 0), IbpBand(18.110, "17m", 17), IbpBand(21.150, "15m", 16), IbpBand(24.930, "12m", 15), IbpBand(28.200, "10m", 14))

    /** The 10-second slot (0..17) of the 3-minute cycle, aligned to UTC midnight. */
    fun ibpSlot(utcMs: Long) = ((Math.floorMod(utcMs / 1000, 86_400L) % 180) / 10).toInt()
    /** The beacon on the air on each band now. */
    fun ibpNow(utcMs: Long): List<Pair<IbpBand, Beacon>> { val s = ibpSlot(utcMs); return IBP_BANDS.map { it to BEACONS[(s + it.offset) % BEACONS.size] } }
    fun ibpSecondsLeft(utcMs: Long) = 10 - (Math.floorMod(utcMs / 1000, 86_400L) % 10).toInt()
    /** Seconds until [beacon] (index in [BEACONS]) starts on [band] (0 = on the air now). */
    fun ibpSecondsUntil(utcMs: Long, beacon: Int, band: IbpBand): Int {
        val s = ibpSlot(utcMs)
        val slot = Math.floorMod(beacon - band.offset, BEACONS.size)                // slot in which it is on that band
        if (slot == s) return 0
        val sec = (Math.floorMod(utcMs / 1000, 86_400L) % 180).toInt()
        return Math.floorMod(slot * 10 - sec, 180)
    }

    // ------------------------------------------------------------------ great circle and Maidenhead

    /** Points of the great circle from 1 to 2 (short path; [longPath] = the other way round), split where it crosses ±180°. */
    fun greatCircle(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 120, longPath: Boolean = false): List<List<Pair<Double, Double>>> {
        val la1 = Math.toRadians(lat1); val lo1 = Math.toRadians(lon1); val la2 = Math.toRadians(lat2); val lo2 = Math.toRadians(lon2)
        val d = acos((sin(la1) * sin(la2) + cos(la1) * cos(la2) * cos(lo2 - lo1)).coerceIn(-1.0, 1.0))
        if (d < 1e-9) return listOf(listOf(lat1 to lon1))
        val pts = ArrayList<Pair<Double, Double>>()
        val total = if (longPath) 2 * Math.PI - d else d
        // a point at angle t along the circle from 1 towards 2 (or away from it for the long path)
        val x1 = cos(la1) * cos(lo1); val y1 = cos(la1) * sin(lo1); val z1 = sin(la1)
        val x2 = cos(la2) * cos(lo2); val y2 = cos(la2) * sin(lo2); val z2 = sin(la2)
        // unit vector in the plane, perpendicular to 1, towards 2
        var ux = x2 - cos(d) * x1; var uy = y2 - cos(d) * y1; var uz = z2 - cos(d) * z1
        val un = Math.sqrt(ux * ux + uy * uy + uz * uz); ux /= un; uy /= un; uz /= un
        val dir = if (longPath) -1 else 1
        for (i in 0..n) {
            val t = dir * total * i / n
            val x = cos(t) * x1 + sin(t) * ux; val y = cos(t) * y1 + sin(t) * uy; val z = cos(t) * z1 + sin(t) * uz
            pts.add(Math.toDegrees(atan2(z, hypot(x, y))) to Math.toDegrees(atan2(y, x)))
        }
        val out = ArrayList<List<Pair<Double, Double>>>(); var cur = ArrayList<Pair<Double, Double>>()
        for (p in pts) {
            if (cur.isNotEmpty() && abs(p.second - cur.last().second) > 180) { out.add(cur); cur = ArrayList() }
            cur.add(p)
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** Maidenhead step for the zoom level: fields (20°×10°), squares (2°×1°) or subsquares (5'×2.5'). */
    fun gridStep(zoom: Double): Pair<Double, Double> = when { zoom < 5.5 -> 20.0 to 10.0; zoom < 10.5 -> 2.0 to 1.0; else -> 2.0 / 24 to 1.0 / 24 }

    /** Bounds (south, west, north, east) of a 4- or 6-character locator. */
    fun gridBounds(loc: String): DoubleArray? {
        val l = loc.trim().uppercase(Locale.US)
        if (l.length < 4 || !Spots.isGrid(l.take(if (l.length >= 6) 6 else 4))) return null
        var w = (l[0] - 'A') * 20.0 - 180 + (l[2] - '0') * 2.0; var s = (l[1] - 'A') * 10.0 - 90 + (l[3] - '0')
        var dw = 2.0; var ds = 1.0
        if (l.length >= 6) { dw = 2.0 / 24; ds = 1.0 / 24; w += (l[4] - 'A') * dw; s += (l[5] - 'A') * ds }
        return doubleArrayOf(s, w, s + ds, w + dw)
    }

    // ------------------------------------------------------------------ NASA EONET (wildfires, floods, storms, volcanoes...)

    data class Event(val id: String, val title: String, val category: String, val lat: Double, val lon: Double, val timeMs: Long, val magnitude: String)

    fun eonetUrl(category: String, days: Int = 20) = "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=$days&category=$category"

    /** The last point (or polygon centre) of each open event. */
    fun parseEonet(json: String): List<Event>? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val ev = o["events"] as? List<*> ?: return null
        return ev.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val g = (m["geometry"] as? List<*>)?.lastOrNull() as? Map<*, *> ?: return@mapNotNull null
            val c = g["coordinates"] as? List<*> ?: return@mapNotNull null
            val (lat, lon) = when (g["type"]) {
                "Point" -> (num(c.getOrNull(1)) ?: return@mapNotNull null) to (num(c.getOrNull(0)) ?: return@mapNotNull null)
                "Polygon" -> { val r = (c.firstOrNull() as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.let { num(it.getOrNull(1))?.let { la -> num(it.getOrNull(0))?.let { lo -> la to lo } } } } ?: return@mapNotNull null
                    if (r.isEmpty()) return@mapNotNull null; r.map { it.first }.average() to r.map { it.second }.average() }
                else -> return@mapNotNull null
            }
            val cat = ((m["categories"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("title") as? String ?: ""
            val mag = num(g["magnitudeValue"])?.let { v -> String.format(Locale.US, "%.0f %s", v, g["magnitudeUnit"] as? String ?: "") }?.trim() ?: ""
            Event(m["id"] as? String ?: "", m["title"] as? String ?: "", cat, lat, lon, OfficialAlerts.parseIso(g["date"] as? String) * 1000, mag)
        }
    }

    // ------------------------------------------------------------------ SondeHub amateur balloons

    data class Balloon(val call: String, val lat: Double, val lon: Double, val altM: Double?, val timeMs: Long, val track: List<Pair<Double, Double>>, val comment: String)

    const val SONDEHUB_URL = "https://api.v2.sondehub.org/amateur/telemetry?duration=6h"

    /** {"<payload_callsign>": {"<ISO time>": {lat, lon, alt, datetime, comment...}}}: the last position and the track of each. */
    fun parseSondehub(json: String): List<Balloon>? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        return o.mapNotNull { (call, v) ->
            val frames = (v as? Map<*, *>)?.mapNotNull { (k, f) ->
                val m = f as? Map<*, *> ?: return@mapNotNull null
                val lat = num(m["lat"]) ?: return@mapNotNull null; val lon = num(m["lon"]) ?: return@mapNotNull null
                if (abs(lat) > 90 || abs(lon) > 180 || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
                val t = OfficialAlerts.parseIso(m["datetime"] as? String ?: k as? String) * 1000
                if (t <= 0) null else Triple(t, lat to lon, m)
            }?.sortedBy { it.first } ?: return@mapNotNull null
            val last = frames.lastOrNull() ?: return@mapNotNull null
            Balloon(call, last.second.first, last.second.second, num(last.third["alt"]), last.first, frames.map { it.second }, last.third["comment"] as? String ?: "")
        }
    }

    // ------------------------------------------------------------------ NOAA NDBC buoys (latest observation of every station)

    data class Buoy(val id: String, val lat: Double, val lon: Double, val timeMs: Long, val windDeg: Int?, val windMs: Double?, val gustMs: Double?,
                    val waveM: Double?, val periodS: Double?, val pressureHpa: Double?, val airC: Double?, val waterC: Double?)

    const val NDBC_URL = "https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt"

    /** Columns by the header names ("#STN LAT LON YYYY MM DD hh mm WDIR WSPD GST WVHT DPD ... PRES ... ATMP WTMP ..."); "MM" = missing. */
    fun parseNdbc(text: String): List<Buoy>? {
        val lines = text.lines()
        val head = lines.firstOrNull { it.startsWith("#") && it.contains("LAT") }?.trimStart('#')?.trim()?.split(Regex("\\s+")) ?: return null
        fun col(n: String) = head.indexOf(n)
        val iLat = col("LAT"); val iLon = col("LON"); if (iLat < 0 || iLon < 0) return null
        return lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
            val f = l.trim().split(Regex("\\s+"))
            fun d(n: String): Double? { val i = col(n); return if (i < 0 || i >= f.size) null else f[i].toDoubleOrNull() }
            val lat = f.getOrNull(iLat)?.toDoubleOrNull() ?: return@mapNotNull null; val lon = f.getOrNull(iLon)?.toDoubleOrNull() ?: return@mapNotNull null
            val t = d("YYYY")?.let { y -> OfficialAlerts.unixFromUtc(y.toInt(), (d("MM") ?: 1.0).toInt(), (d("DD") ?: 1.0).toInt(), (d("hh") ?: 0.0).toInt(), (d("mm") ?: 0.0).toInt(), 0) * 1000 } ?: 0L
            Buoy(f[0], lat, lon, t, d("WDIR")?.toInt(), d("WSPD"), d("GST"), d("WVHT"), d("DPD"), d("PRES"), d("ATMP"), d("WTMP"))
        }
    }

    // ------------------------------------------------------------------ spots of the user's own signal: wspr.live and PSK Reporter

    data class Spot(val rxCall: String, val rxGrid: String, val lat: Double, val lon: Double, val band: String, val mode: String, val snr: Int?, val timeMs: Long)

    /** wspr.live (public ClickHouse over HTTP): who heard [call] in the last [hours] hours. */
    fun wsprLiveUrl(call: String, hours: Int = 6, limit: Int = 300): String {
        val c = call.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '/' }
        val q = "SELECT time, band, rx_sign, rx_loc, snr, frequency FROM wspr.rx WHERE time > subtractHours(now(), $hours) AND tx_sign = '$c' ORDER BY time DESC LIMIT $limit FORMAT JSON"
        return "https://db1.wspr.live/?query=" + URLEncoder.encode(q, "UTF-8")
    }

    fun parseWsprLive(json: String): List<Spot>? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val d = o["data"] as? List<*> ?: return null
        return d.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val grid = m["rx_loc"] as? String ?: return@mapNotNull null
            val pos = RotorFollow.locatorToLatLon(grid) ?: return@mapNotNull null
            val f = num(m["frequency"])
            Spot(m["rx_sign"] as? String ?: "", grid, pos.first, pos.second, f?.let { bandOf(it) } ?: ((num(m["band"])?.toInt())?.let { "$it MHz" } ?: ""), "WSPR",
                num(m["snr"])?.toInt(), OfficialAlerts.parseIso((m["time"] as? String)?.replace(' ', 'T')) * 1000)
        }
    }

    /** PSK Reporter: receptions of [call] in the last [seconds] (ask at most every 5 min, their rule). */
    fun pskReporterUrl(call: String, seconds: Int = 3600) =
        "https://retrieve.pskreporter.info/query?senderCallsign=" + URLEncoder.encode(call.uppercase(Locale.US), "UTF-8") + "&flowStartSeconds=-$seconds&rronly=1&appcontact=ea7jrs.manuel%40gmail.com"

    fun parsePskReporter(xml: String): List<Spot> = Regex("<receptionReport\\b([^>]*)/?>").findAll(xml).mapNotNull { m ->
        val a = Regex("(\\w+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        val grid = a["receiverLocator"] ?: return@mapNotNull null
        val pos = RotorFollow.locatorToLatLon(grid) ?: return@mapNotNull null
        Spot(a["receiverCallsign"] ?: "", grid, pos.first, pos.second, a["frequency"]?.toDoubleOrNull()?.let { bandOf(it) } ?: "", a["mode"] ?: "",
            a["sNR"]?.toIntOrNull(), (a["flowStartSeconds"]?.toLongOrNull() ?: 0L) * 1000)
    }.toList()

    /** Amateur band of a frequency in Hz. */
    fun bandOf(hz: Double): String {
        val mhz = hz / 1e6
        val b = listOf(0.137 to "2200m", 0.475 to "630m", 1.8 to "160m", 3.5 to "80m", 5.3 to "60m", 7.0 to "40m", 10.1 to "30m", 14.0 to "20m",
            18.068 to "17m", 21.0 to "15m", 24.89 to "12m", 28.0 to "10m", 50.0 to "6m", 70.0 to "4m", 144.0 to "2m", 430.0 to "70cm")
        return b.lastOrNull { mhz >= it.first * 0.98 }?.second ?: ""
    }

    // ------------------------------------------------------------------ CQ / ITU zones (HB9HIL hamradio-zones-geojson, MIT)

    data class Zone(val number: Int, val name: String, val labelLat: Double, val labelLon: Double, val rings: List<List<Pair<Double, Double>>>)

    /** GeoJSON with cq_zone_number / itu_zone_number, *_name and *_name_loc ([lat, lon]). */
    fun parseZones(json: String, itu: Boolean): List<Zone>? {
        val o = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val f = o["features"] as? List<*> ?: return null
        val pre = if (itu) "itu_zone_" else "cq_zone_"
        return f.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val p = m["properties"] as? Map<*, *> ?: return@mapNotNull null
            val n = num(p[pre + "number"])?.toInt() ?: return@mapNotNull null
            val loc = (p[pre + "name_loc"] as? List<*>)?.mapNotNull { num(it) }
            val g = m["geometry"] as? Map<*, *> ?: return@mapNotNull null
            val c = g["coordinates"] as? List<*> ?: return@mapNotNull null
            fun ring(r: Any?) = (r as? List<*>)?.mapNotNull { q -> (q as? List<*>)?.let { num(it.getOrNull(1))?.let { la -> num(it.getOrNull(0))?.let { lo -> la to lo } } } }
            val rings = when (g["type"]) {
                "Polygon" -> listOfNotNull(ring(c.firstOrNull()))
                "MultiPolygon" -> c.mapNotNull { ring((it as? List<*>)?.firstOrNull()) }
                else -> emptyList()
            }
            val name = (p[pre + "name"] as? String ?: "").let { s -> val k = s.lastIndexOf("**"); (if (k >= 0) s.substring(k + 2) else s).trim('!', '#', ':', '*', ' ') }
            Zone(n, name, loc?.getOrNull(0) ?: 0.0, loc?.getOrNull(1) ?: 0.0, rings)
        }
    }

    // ------------------------------------------------------------------ colour scales

    /** Aurora probability (%) → ARGB: green, then yellow and red as it grows. */
    fun auroraColor(pct: Int, alpha: Int): Int {
        val (r, g, b) = when { pct >= 70 -> Triple(229, 57, 53); pct >= 40 -> Triple(253, 216, 53); pct >= 15 -> Triple(60, 230, 138); else -> Triple(60, 200, 120) }
        val a = (alpha * (0.25 + 0.75 * (pct.coerceAtMost(80) / 80.0))).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** D-RAP highest affected frequency (MHz) → ARGB (none below 1 MHz). */
    fun drapColor(mhz: Double, alpha: Int): Int {
        if (mhz < 1) return 0
        val (r, g, b) = when { mhz >= 25 -> Triple(229, 57, 53); mhz >= 15 -> Triple(255, 140, 26); mhz >= 8 -> Triple(253, 216, 53); else -> Triple(79, 195, 247) }
        val a = (alpha * (0.35 + 0.65 * (mhz.coerceAtMost(30.0) / 30.0))).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Westernmost cell of a regular grid (helper to draw the D-RAP grid). */
    fun cellWest(lon: Double, step: Double) = floor(lon / step) * step
}
