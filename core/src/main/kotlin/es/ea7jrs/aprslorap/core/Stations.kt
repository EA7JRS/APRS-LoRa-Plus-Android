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
)

/** Table of heard stations: one entry per callsign, updated with each frame. */
class StationTable(private val maxStations: Int = 500) {
    private val map = LinkedHashMap<String, Station>()

    fun onFrame(frame: Ax25Frame, nowMs: Long): Station {
        val call = frame.source.text()
        val old = map[call]
        val pos = Aprs.parse(frame.infoText) as? AprsPacket.Position
        val path = frame.path.joinToString(",") { it.toString() }
        val st = Station(
            call = call,
            lat = pos?.lat ?: old?.lat, lon = pos?.lon ?: old?.lon,
            symbolTable = pos?.symbolTable ?: old?.symbolTable, symbolCode = pos?.symbolCode ?: old?.symbolCode,
            comment = pos?.comment ?: old?.comment ?: "",
            lastHeardMs = nowMs,
            lastPositionMs = if (pos != null) nowMs else old?.lastPositionMs,
            packets = (old?.packets ?: 0) + 1,
            lastPath = path,
        )
        map.remove(call)
        map[call] = st
        while (map.size > maxStations) map.remove(map.keys.first())
        return st
    }

    fun get(call: String): Station? = map[call.uppercase()]

    /** Most recently heard first. */
    fun recent(): List<Station> = map.values.reversed()

    /** Stations with a position, nearest first. */
    fun nearest(lat: Double, lon: Double): List<Pair<Station, Double>> =
        map.values.filter { it.lat != null && it.lon != null }
            .map { it to Geo.distanceKm(lat, lon, it.lat!!, it.lon!!) }
            .sortedBy { it.second }
}
