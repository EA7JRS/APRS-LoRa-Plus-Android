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
 * NAVTEX messages decoded in Modes, kept on the phone with the station they came from (fldigi's station list, by letter,
 * frequency and distance) and the positions in the text.
 *
 * The rules are the ones of a NAVTEX receiver (IMO MSC.508(105), NAVTEX Manual 2023), as in the Navtex program (EA7JRS/Navtex):
 * a repeat of a satisfactorily received message is not shown again for 72 hours, only noted as heard again; a better copy
 * replaces a worse one; B3B4 = 00 is always shown; a message can be tagged to be kept (up to a quarter of the memory); a deleted
 * message is remembered for 72 hours so that its repeat does not bring it back; a search and rescue message (D) raises an alarm
 * that is only reset by hand, and goes out as an ALR sentence every 30 s until then.
 */
object NavtexHub {
    data class Entry(val msg: Navtex.Message, val khz: Double, val station: Navtex.Station?, val positions: List<Pair<Double, Double>>,
                     val heardMs: Long = msg.timeMs, val kept: Boolean = false, val hidden: Boolean = false, val alarm: Boolean = false) {
        val stationName get() = station?.let { "${it.name} (${it.country})" } ?: if (msg.lostHeader) "Unknown (no header)" else "Station ${msg.station}"
        /** Received within the ten minutes of its letter (null: no header, or a letter without a slot). */
        val inSlot get() = if (msg.lostHeader) null else Navtex.inOwnSlot(msg.station, msg.timeMs)
    }

    /** What is shown: everything but what the user deleted. */
    val messages = MutableStateFlow<List<Entry>>(emptyList())
    private var all: List<Entry> = emptyList()                         // newest first, with the deleted ones still remembered
    var stations: List<Navtex.Station> = emptyList()
        private set
    private var ctx: Context? = null
    private const val MAX = 300
    const val MAX_KEPT = MAX / 4                                        // IMO MSC.508(105), 6.1.2: up to 25 % of the memory
    private var seq = 0
    private var lastAlrMs = 0L

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        stations = try { c.assets.open("navtex/NAVTEX_Stations.csv").bufferedReader().use { Navtex.parseStations(it.readText()) } } catch (_: Exception) { emptyList() }
        load()
    }

    private fun file() = ctx?.let { File(it.filesDir, "navtex.json") }

    private fun publish() {
        val now = System.currentTimeMillis()
        // what was deleted is forgotten once its identity no longer has to be remembered
        all = all.filterNot { it.hidden && now - it.msg.timeMs >= Navtex.IDENTITY_MS }
        var room = MAX
        all = all.filter { it.kept || it.hidden || room-- > 0 }
        messages.value = all.filterNot { it.hidden }
        save()
    }

    fun onMessage(m: Navtex.Message) {
        val c = ctx ?: return
        val settings = Settings(c)
        val khz = settings.navtexKhz
        val me = Own.position()
        var e = Entry(m, khz, if (m.lostHeader) null else Navtex.findStation(stations, m.station, khz, me?.first, me?.second), Navtex.positions(m.text),
            alarm = m.urgent)
        val now = System.currentTimeMillis()
        synchronized(this) {
            val old = if (m.lostHeader) null else all.firstOrNull { !it.msg.lostHeader && it.msg.id == m.id && it.khz == khz }
            when (Navtex.verdict(m, old?.msg, old?.msg?.timeMs ?: 0L, now)) {
                Navtex.Verdict.REPEAT -> {                              // still being broadcast: nothing new to show or to send
                    all = all.map { if (it === old) it.copy(heardMs = now) else it }
                    publish()
                    return
                }
                Navtex.Verdict.BETTER -> {
                    // the alarm of a message already acknowledged is not raised again by a better copy
                    e = e.copy(kept = old?.kept == true, alarm = m.urgent && (old?.alarm == true || m.alwaysShown || old?.hidden == true))
                    all = listOf(e) + all.filterNot { it === old }
                }
                Navtex.Verdict.NEW -> all = listOf(e) + all.filterNot { it === old && !it.kept }        // what was tagged to keep stays
            }
            publish()
        }
        AppState.nmeaOut?.let { srv ->                                 // to the plotter and other programs
            for (l in if (settings.navtexNrxIec) Navtex.nrxIec(m, khz, seq++) else Navtex.nrx(m, khz)) srv.send(l)
        }
        if (e.alarm) { lastAlrMs = 0L; tick() }
        if (e.alarm && settings.navtexAlarm) AlertCenter.onNavtex(e)
    }

    /** Called every second or so: while a search and rescue message waits to be acknowledged, its ALR sentence every 30 s. */
    fun tick() {
        val srv = AppState.nmeaOut ?: return
        if (all.none { it.alarm && !it.hidden }) return
        val now = System.currentTimeMillis()
        if (now - lastAlrMs < 30_000L) return
        lastAlrMs = now
        srv.send(Navtex.alr(now, active = true, acknowledged = false))
    }

    /** The operator has seen the search and rescue messages: the alarm stops (it is only reset by hand). */
    fun acknowledge() {
        synchronized(this) {
            if (all.none { it.alarm }) return
            all = all.map { if (it.alarm) it.copy(alarm = false) else it }
            publish()
        }
        AppState.nmeaOut?.send(Navtex.alr(System.currentTimeMillis(), active = false, acknowledged = true))
    }

    /**
     * The alarm was acknowledged on other equipment (an ACK sentence from the plotter through the NMEA server): the same as
     * closing the alert window here.
     */
    fun acknowledgeRemote() {
        val keys = synchronized(this) { all.filter { it.alarm && !it.hidden }.map { alertOf(it).key } }
        if (keys.isEmpty()) return
        for (k in keys) AlertCenter.close(k)                            // closes the window and calls acknowledge()
        acknowledge()
    }

    /** Tags a message to be kept, or removes the tag. False when a quarter of the memory is already tagged. */
    fun keep(e: Entry, on: Boolean): Boolean {
        synchronized(this) {
            if (on && all.count { it.kept && !it.hidden } >= MAX_KEPT) return false
            all = all.map { if (it === e || (it.msg == e.msg && it.khz == e.khz)) it.copy(kept = on) else it }
            publish()
        }
        return true
    }

    /** Hides a message. It is still remembered for 72 hours, so the repeat of its broadcast does not bring it back. */
    fun delete(e: Entry) {
        synchronized(this) {
            all = all.map { if (it === e || (it.msg == e.msg && it.khz == e.khz)) it.copy(hidden = true, kept = false, alarm = false) else it }
            publish()
        }
    }

    /** Deletes every message but the ones tagged to be kept. */
    fun clear() {
        synchronized(this) {
            all = all.map { if (it.kept) it else it.copy(hidden = true, alarm = false) }
            publish()
        }
    }

    /** The messages shown, as text to save or share. */
    fun export(): String = Navtex.export(messages.value.map { it.msg to it.khz })

    private fun save() {
        val f = file() ?: return
        val a = JSONArray()
        for (e in all) a.put(JSONObject().apply {
            put("st", e.msg.station.toString()); put("su", e.msg.subject.toString()); put("n", e.msg.number); put("t", e.msg.text)
            put("lh", e.msg.lostHeader); put("lt", e.msg.lostTrailer); put("ms", e.msg.timeMs); put("khz", e.khz)
            put("hd", e.heardMs); put("k", e.kept); put("h", e.hidden); put("al", e.alarm)
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
                out.add(Entry(m, khz, if (m.lostHeader) null else Navtex.findStation(stations, m.station, khz, null, null), Navtex.positions(m.text),
                    o.optLong("hd", m.timeMs), o.optBoolean("k"), o.optBoolean("h"), o.optBoolean("al")))
            }
        } catch (_: Exception) {}
        all = out
        messages.value = out.filterNot { it.hidden }
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
