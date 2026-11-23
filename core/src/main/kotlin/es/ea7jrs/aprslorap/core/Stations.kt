/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

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
    /** Last weather report and when it came. */
    val wx: AprsWx? = null, val wxMs: Long? = null,
    /** Change of the station's pressure over 3 h (hPa), once it has been heard for at least an hour. */
    val wxPressD3h: Double? = null,
    /** Object or item: the station that sends it. */
    val owner: String? = null,
    /** Mic-E status ("En route", "Emergency"...). */
    val micE: String? = null,
)

/** Table of heard stations: one entry per callsign, updated with each frame. */
class StationTable(private val maxStations: Int = 500) {
    private val map = LinkedHashMap<String, Station>()
    private val trends = HashMap<String, WxCalc.PressureTrend>()

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
            altitudeM = pos?.altitudeM ?: old?.altitudeM,
            wx = wx ?: old?.wx, wxMs = if (wx != null) nowMs else old?.wxMs,
            wxPressD3h = wx?.pressureHpa?.let { hpa -> trends.getOrPut(call) { WxCalc.PressureTrend() }.also { it.add(nowMs / 1000, hpa) }.delta3h(nowMs / 1000) } ?: old?.wxPressD3h,
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
