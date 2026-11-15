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
        val st = Station(
            call = call,
            lat = pos?.lat ?: old?.lat, lon = pos?.lon ?: old?.lon,
            symbolTable = pos?.symbolTable ?: old?.symbolTable, symbolCode = pos?.symbolCode ?: old?.symbolCode,
            comment = pos?.comment ?: report?.comment?.takeIf { old?.comment.isNullOrEmpty() } ?: old?.comment ?: "",
            lastHeardMs = nowMs,
            lastPositionMs = if (pos != null) nowMs else old?.lastPositionMs,
            packets = (old?.packets ?: 0) + 1,
            lastPath = path,
            courseDeg = if (pos != null) pos.courseDeg else old?.courseDeg,
            speedKmh = if (pos != null) pos.speedKmh else old?.speedKmh,
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
