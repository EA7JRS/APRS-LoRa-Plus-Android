/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** A point of the trail of a station. */
data class TrackPoint(val lat: Double, val lon: Double, val timeMs: Long)

data class Station(
    val call: String,
    val lat: Double?, val lon: Double?,
    val symbolTable: Char?, val symbolCode: Char?,
    val comment: String,
    val lastHeardMs: Long,
    val lastPositionMs: Long?,
    val packets: Int,
    val lastPath: String,
    val courseDeg: Int? = null, val speedKmh: Double? = null, val altitudeM: Double? = null,
    /** True when course and speed were worked out from two positions (the packet carried none: altitude in its cs bytes). */
    val motionDerived: Boolean = false,
    /** The last frame heard from it, as it came (a third-party frame still wrapped): for the packet card of its page. */
    val lastFrame: Ax25Frame? = null,
    /** Where it has been, oldest first (the last point is where it is): the trail of a moving station on the map. */
    val trail: List<TrackPoint> = emptyList(),
    /** Last weather report and when it came. */
    val wx: AprsWx? = null, val wxMs: Long? = null,
    /** Change of the station's pressure over 3 h (hPa), once it has been heard for at least an hour. */
    val wxPressD3h: Double? = null,
    /** Last status text (">...") and when. */
    val status: String? = null, val statusMs: Long? = null,
    /** Last telemetry report and what the station says its channels are. */
    val telemetry: TelemetryReading? = null, val telemetryDefs: TelemetryDefs? = null,
    /** Object or item: the station that sends it. */
    val owner: String? = null,
    /** Mic-E status ("En route", "Emergency"...). */
    val micE: String? = null,
)

/** Table of heard stations: one entry per callsign, updated with each frame. */
class StationTable(private val maxStations: Int = 500) {
    private val map = LinkedHashMap<String, Station>()
    private val trends = HashMap<String, WxCalc.PressureTrend>()
    private val defs = HashMap<String, TelemetryDefs>()            // channel definitions, by the station they describe

    /**
     * Updates the table with a frame. Third-party frames (from APRS-IS through an iGate) count for the station inside;
     * objects and items are stations of their own, named after the object; a killed object leaves the table.
     */
    fun onFrame(frame: Ax25Frame, nowMs: Long): Station {
        val f = Aprs.unwrapThirdParty(frame)
        val p = Aprs.parse(f.infoText, f.destination.call)
        val pos = p as? AprsPacket.Position
        val report = p as? AprsPacket.Weather
        val call = pos?.objectName ?: f.source.text()
        val old = map[call]
        val path = f.path.joinToString(",") { it.toString() }
        val wx = pos?.wx ?: report?.wx
        val status = p as? AprsPacket.Status
        val tele = p as? AprsPacket.Telemetry
        // PARM. / UNIT. / EQNS. / BITS.: a message, usually to itself, that names the telemetry channels of its addressee
        (p as? AprsPacket.Message)?.takeIf { Telemetry.isDefinition(it.text) }?.let { m ->
            val who = m.addressee.trim().uppercase()
            if (defs.size > 200 && !defs.containsKey(who)) defs.clear()
            Telemetry.merge(defs[who], m.text)?.let { d -> defs[who] = d; if (who != call) map[who]?.let { map[who] = it.copy(telemetryDefs = d) } }
        }
        if (trends.size > 64 && !trends.containsKey(call)) trends.clear()          // bounded: start again rather than grow
        // Course and speed: those of the packet; if it carries none (APRS LoRa+ and CA2RXU trackers put the altitude in the compressed
        // "cs" bytes), from the previous position: at least 10 s and 40 m apart, at most 30 min; closer than that = not moving
        var course = if (pos != null) pos.courseDeg else old?.courseDeg
        var speed = if (pos != null) pos.speedKmh else old?.speedKmh
        var derived = if (pos != null) false else old?.motionDerived ?: false
        if (pos != null && pos.courseDeg == null && pos.speedKmh == null && pos.wx == null) {
            val m = Motion.between(old?.lat, old?.lon, old?.lastPositionMs, pos.lat, pos.lon, nowMs)
            when {
                m != null -> { course = m.first; speed = m.second; derived = true }
                old?.motionDerived == true && old.lastPositionMs != null && nowMs - old.lastPositionMs < 10_000 -> { course = old.courseDeg; speed = old.speedKmh; derived = true }
            }
        }
        val st = Station(
            call = call,
            lat = pos?.lat ?: old?.lat, lon = pos?.lon ?: old?.lon,
            symbolTable = pos?.symbolTable ?: old?.symbolTable, symbolCode = pos?.symbolCode ?: old?.symbolCode,
            comment = pos?.comment ?: report?.comment?.takeIf { old?.comment.isNullOrEmpty() } ?: old?.comment ?: "",
            lastHeardMs = nowMs,
            lastPositionMs = if (pos != null) nowMs else old?.lastPositionMs,
            packets = (old?.packets ?: 0) + 1,
            lastPath = path,
            courseDeg = course, speedKmh = speed, motionDerived = derived,
            lastFrame = frame,
            trail = if (pos != null) Motion.trail(old?.trail ?: emptyList(), pos.lat, pos.lon, nowMs) else old?.trail ?: emptyList(),
            altitudeM = pos?.altitudeM ?: old?.altitudeM,
            wx = wx ?: old?.wx, wxMs = if (wx != null) nowMs else old?.wxMs,
            wxPressD3h = wx?.pressureHpa?.let { hpa -> trends.getOrPut(call) { WxCalc.PressureTrend() }.also { it.add(nowMs / 1000, hpa) }.delta3h(nowMs / 1000) } ?: old?.wxPressD3h,
            status = status?.text?.ifEmpty { null } ?: old?.status, statusMs = if (status != null) nowMs else old?.statusMs,
            telemetry = tele?.reading?.copy(timeMs = nowMs) ?: old?.telemetry, telemetryDefs = defs[call] ?: old?.telemetryDefs,
            owner = if (pos?.objectName != null) f.source.text() else old?.owner,
            micE = pos?.micE ?: old?.micE.takeIf { pos == null },
        )
        map.remove(call)
        if (pos?.killed == true) return st
        map[call] = st
        while (map.size > maxStations) map.remove(map.keys.first())
        return st
    }

    fun get(call: String): Station? = map[call.uppercase()]

    /**
     * The table as text, to keep it between sessions: what cannot be worked out again (position, trail, counters, the last
     * frame as TNC2, status, telemetry). The weather comes back from the last frame when it is read.
     */
    fun export(): String = Json.encode(mapOf("v" to 1, "stations" to map.values.map { s ->
        val m = LinkedHashMap<String, Any?>()
        m["c"] = s.call; m["la"] = s.lat; m["lo"] = s.lon; m["st"] = s.symbolTable?.toString(); m["sc"] = s.symbolCode?.toString()
        m["cm"] = s.comment; m["h"] = s.lastHeardMs; m["p"] = s.lastPositionMs; m["n"] = s.packets; m["pa"] = s.lastPath
        m["co"] = s.courseDeg; m["sp"] = s.speedKmh; m["al"] = s.altitudeM; m["md"] = s.motionDerived
        m["f"] = s.lastFrame?.toTnc2(); m["tr"] = s.trail.map { listOf(it.lat, it.lon, it.timeMs) }
        m["ow"] = s.owner; m["mi"] = s.micE; m["ss"] = s.status; m["sm"] = s.statusMs
        s.telemetry?.let { t -> m["t"] = mapOf("s" to t.seq, "a" to t.analog, "d" to t.digital, "m" to t.timeMs) }
        s.telemetryDefs?.let { d -> m["td"] = mapOf("p" to d.parm, "u" to d.unit, "e" to d.eqns, "b" to d.bits, "t" to d.title) }
        m.filterValues { it != null }
    }))

    /** Reads what [export] wrote; stations last heard more than [maxAgeMs] ago are left out. Returns how many came back. */
    fun restore(text: String, nowMs: Long, maxAgeMs: Long = 24 * 3600_000L): Int {
        val root = try { Json.parseObject(text) } catch (_: Exception) { null } ?: return 0
        var n = 0
        for (m in root.objList("stations").sortedBy { it.long("h") ?: 0 }) {
            val call = m.str("c") ?: continue
            val heard = m.long("h") ?: continue
            if (nowMs - heard > maxAgeMs || heard > nowMs + 600_000L || map.containsKey(call)) continue
            val frame = m.str("f")?.let { Tnc2.parse(it) }
            val parsed = frame?.let { Aprs.unwrapThirdParty(it) }?.let { Aprs.parse(it.infoText, it.destination.call) }
            val wx = (parsed as? AprsPacket.Position)?.wx ?: (parsed as? AprsPacket.Weather)?.wx
            val td = m.obj("td")?.let { d ->
                TelemetryDefs(d.strList("p"), d.strList("u"), (d["e"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList(), d.str("b") ?: "", d.str("t") ?: "")
            }
            td?.let { defs[call] = it }
            map[call] = Station(
                call = call, lat = m.num("la"), lon = m.num("lo"), symbolTable = m.str("st")?.firstOrNull(), symbolCode = m.str("sc")?.firstOrNull(),
                comment = m.str("cm") ?: "", lastHeardMs = heard, lastPositionMs = m.long("p"), packets = m.int("n") ?: 1, lastPath = m.str("pa") ?: "",
                courseDeg = m.int("co"), speedKmh = m.num("sp"), altitudeM = m.num("al"), motionDerived = m.bool("md") ?: false,
                lastFrame = frame,
                trail = ((m["tr"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { pt ->
                    val l = (pt as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: return@mapNotNull null
                    if (l.size == 3 && nowMs - l[2].toLong() <= Motion.TRAIL_MS) TrackPoint(l[0], l[1], l[2].toLong()) else null
                },
                wx = wx, wxMs = if (wx != null) heard else null,
                status = m.str("ss"), statusMs = m.long("sm"),
                telemetry = m.obj("t")?.let { t -> TelemetryReading(t.str("s") ?: "", (t["a"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList(), t.str("d") ?: "", t.long("m") ?: heard) },
                telemetryDefs = td, owner = m.str("ow"), micE = m.str("mi"),
            )
            n++
        }
        while (map.size > maxStations) map.remove(map.keys.first())
        return n
    }

    /** Most recently heard first. */
    fun recent(): List<Station> = map.values.reversed()

    /** Weather stations (with a report), nearest first when [lat] / [lon] are known, else the newest report first. */
    fun weather(lat: Double?, lon: Double?): List<Station> {
        val l = map.values.filter { it.wx != null }
        return if (lat == null || lon == null) l.sortedByDescending { it.wxMs ?: 0 }
        else l.sortedBy { if (it.lat == null || it.lon == null) Double.MAX_VALUE else Geo.distanceKm(lat, lon, it.lat, it.lon) }
    }

    /** Stations with a position, nearest first. */
    fun nearest(lat: Double, lon: Double): List<Pair<Station, Double>> =
        map.values.filter { it.lat != null && it.lon != null }
            .map { it to Geo.distanceKm(lat, lon, it.lat!!, it.lon!!) }
            .sortedBy { it.second }
}


/** Course and speed between two positions of a station that sends none. */
object Motion {
    const val MIN_S = 10
    const val MAX_S = 30 * 60
    const val MIN_M = 40.0
    const val TRAIL_POINTS = 80
    const val TRAIL_MS = 3 * 3600_000L

    /**
     * The trail with a new position: added if it is [MIN_M] from the last point (a parked station keeps one point), without the
     * points older than [TRAIL_MS] and with [TRAIL_POINTS] at most; a jump of more than 1200 km/h starts the trail again.
     */
    fun trail(old: List<TrackPoint>, lat: Double, lon: Double, nowMs: Long): List<TrackPoint> {
        val last = old.lastOrNull() ?: return listOf(TrackPoint(lat, lon, nowMs))
        val m = Geo.distanceKm(last.lat, last.lon, lat, lon) * 1000.0
        if (m < MIN_M) return old
        val dt = (nowMs - last.timeMs) / 1000.0
        if (dt > 0 && m / dt * 3.6 > 1200) return listOf(TrackPoint(lat, lon, nowMs))
        return (old.filter { nowMs - it.timeMs <= TRAIL_MS } + TrackPoint(lat, lon, nowMs)).takeLast(TRAIL_POINTS)
    }

    /**
     * (course in degrees, speed in km/h) from the previous position to the new one; (null course, 0) if it has not moved [MIN_M];
     * null when there is no previous position or the time between them is not within [MIN_S] .. [MAX_S].
     */
    fun between(lat0: Double?, lon0: Double?, t0Ms: Long?, lat1: Double, lon1: Double, t1Ms: Long): Pair<Int?, Double>? {
        if (lat0 == null || lon0 == null || t0Ms == null) return null
        val dt = (t1Ms - t0Ms) / 1000.0
        if (dt < MIN_S || dt > MAX_S) return null
        val m = Geo.distanceKm(lat0, lon0, lat1, lon1) * 1000.0
        if (m < MIN_M) return null to 0.0
        val kmh = m / dt * 3.6
        if (kmh > 1200) return null                                   // a jump (another station with the same call, a bad fix)
        return (Math.round(Geo.bearingDeg(lat0, lon0, lat1, lon1)).toInt() % 360) to kmh
    }
}
