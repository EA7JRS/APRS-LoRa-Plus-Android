/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.AlertKind
import es.ea7jrs.aprslorap.core.EmergencyAlert
import es.ea7jrs.aprslorap.core.Navtex
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * NAVTEX messages decoded in Modes: kept on the phone (the last 300, one per station + subject + number: a repeat replaces the old copy),
 * with the station they came from (fldigi's station list, by letter, frequency and distance) and the positions in the text.
 */
object NavtexHub {
    data class Entry(val msg: Navtex.Message, val khz: Double, val station: Navtex.Station?, val positions: List<Pair<Double, Double>>) {
        val stationName get() = station?.let { "${it.name} (${it.country})" } ?: if (msg.lostHeader) "Unknown (no header)" else "Station ${msg.station}"
    }

    val messages = MutableStateFlow<List<Entry>>(emptyList())
    var stations: List<Navtex.Station> = emptyList()
        private set
    private var ctx: Context? = null
    private const val MAX = 300

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        stations = try { c.assets.open("navtex/NAVTEX_Stations.csv").bufferedReader().use { Navtex.parseStations(it.readText()) } } catch (_: Exception) { emptyList() }
        load()
    }

    private fun file() = ctx?.let { File(it.filesDir, "navtex.json") }

    fun onMessage(m: Navtex.Message) {
        val c = ctx ?: return
        val settings = Settings(c)
        val khz = settings.navtexKhz
        val me = Own.position()
        val e = Entry(m, khz, if (m.lostHeader) null else Navtex.findStation(stations, m.station, khz, me?.first, me?.second), Navtex.positions(m.text))
        synchronized(this) {
            val rest = messages.value.filterNot { !m.lostHeader && !it.msg.lostHeader && it.msg.id == m.id && it.station == e.station }
            messages.value = (listOf(e) + rest).take(MAX)
        }
        save()
        AppState.nmeaOut?.let { srv -> for (l in Navtex.nrx(m, khz)) srv.send(l) }      // to the plotter and other programs
        if (m.urgent && settings.navtexAlarm) AlertCenter.onNavtex(e)
    }

    fun clear() { messages.value = emptyList(); save() }

    private fun save() {
        val f = file() ?: return
        val a = JSONArray()
        for (e in messages.value) a.put(JSONObject().apply {
            put("st", e.msg.station.toString()); put("su", e.msg.subject.toString()); put("n", e.msg.number); put("t", e.msg.text)
            put("lh", e.msg.lostHeader); put("lt", e.msg.lostTrailer); put("ms", e.msg.timeMs); put("khz", e.khz)
        })
        try { f.writeText(a.toString()) } catch (_: Exception) {}
    }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val m = Navtex.Message(o.optString("st", "?").firstOrNull() ?: '?', o.optString("su", "?").firstOrNull() ?: '?', o.optInt("n"), o.optString("t"),
                    o.optBoolean("lh"), o.optBoolean("lt"), o.optLong("ms"))
                val khz = o.optDouble("khz", 518.0)
                out.add(Entry(m, khz, if (m.lostHeader) null else Navtex.findStation(stations, m.station, khz, null, null), Navtex.positions(m.text)))
            }
        } catch (_: Exception) {}
        messages.value = out
    }

    /** Stations on [khz] within [maxKm] of the position, nearest first, with their next broadcast. */
    fun nearby(khz: Double, maxKm: Double = 900.0): List<Pair<Navtex.Station, Double>> {
        val me = Own.position() ?: return emptyList()
        return stations.filter { kotlin.math.abs(it.khz - khz) < 1.0 }
            .map { it to es.ea7jrs.aprslorap.core.Geo.distanceKm(me.first, me.second, it.lat, it.lon) }
            .filter { it.second <= maxKm }.sortedBy { it.second }
    }

    /** The alert for a subject D message. */
    fun alertOf(e: Entry): EmergencyAlert =
        EmergencyAlert("navtex:" + e.msg.id + ":" + (e.station?.name ?: ""), AlertKind.NAVTEX, e.stationName, e.msg.text, false, null, e.msg.timeMs, false)
}
