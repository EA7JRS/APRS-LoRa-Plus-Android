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
 * replaces a worse one; B3B4 = 00 is always shown; a message can be tagged to be kept (up to a quarter of the memory); the
 * user cannot delete messages (MSC.508(105), 6.1.1): with the memory full the oldest one not kept goes; a search and rescue
 * message (D) raises an alarm that is only reset by hand, and goes out as an ALR sentence every 30 s until then (and, when
 * it is switched on, with the bridge alert management sentences ALF, ALC and HBT). The stations and subjects that the phone's
 * own decoder stores can be chosen (the masks of the NRM sentence); A, B, D and L are always stored.
 */
object NavtexHub {
    data class Entry(val msg: Navtex.Message, val khz: Double, val station: Navtex.Station?, val positions: List<Pair<Double, Double>>,
                     val heardMs: Long = msg.timeMs, val kept: Boolean = false, val alarm: Boolean = false) {
        /** The header was lost: the station is the one whose time slot the message came in, not a certainty. */
        val guessed get() = msg.lostHeader && station != null
        val stationName get() = station?.let { (if (guessed) "Probably " else "") + "${it.name} (${it.country})" + (if (guessed) ", by its time slot" else "") }
            ?: if (msg.lostHeader) "Unknown (no header)" else "Station ${msg.station}"
        /** Received within the ten minutes of its letter (null: no header, or a letter without a slot). */
        val inSlot get() = if (msg.lostHeader) null else Navtex.inOwnSlot(msg.station, msg.timeMs)
    }

    val messages = MutableStateFlow<List<Entry>>(emptyList())
    private var all: List<Entry> = emptyList()                         // newest first
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
        var room = MAX
        all = all.filter { it.kept || room-- > 0 }                      // full: the oldest ones not kept go
        messages.value = all
        save()
    }

    /** The stations and subjects the phone's own decoder stores (every frequency the same). */
    fun ownMask(s: Settings, khz: Double = s.navtexKhz) = Navtex.Mask(1, khz,
        if (s.navtexStations.isBlank()) Navtex.ALL_LETTERS else Navtex.lettersToMask(s.navtexStations.uppercase()),
        (if (s.navtexSubjects.isBlank()) Navtex.ALL_LETTERS else Navtex.lettersToMask(s.navtexSubjects.uppercase())) or Navtex.FIXED_SUBJECTS)

    /** A message from the phone's own decoder (on the frequency chosen in Modes) or, with [fromKhz], from a receiver on the network. */
    fun onMessage(m: Navtex.Message, fromKhz: Double? = null) {
        val c = ctx ?: return
        val settings = Settings(c)
        val khz = fromKhz ?: settings.navtexKhz
        // what the phone decodes itself passes its masks (a receiver on the network has applied its own)
        if (fromKhz == null && !m.lostHeader && !ownMask(settings).accepts(m.station, m.subject)) return
        val me = Own.position()
        var e = Entry(m, khz, if (m.lostHeader) Navtex.stationBySlot(stations, khz, m.timeMs, me?.first, me?.second)
            else Navtex.findStation(stations, m.station, khz, me?.first, me?.second), Navtex.positions(m.text), alarm = m.urgent)
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
                    e = e.copy(kept = old?.kept == true, alarm = m.urgent && (old?.alarm == true || m.alwaysShown))
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

    // bridge alert management (ALF, ALC, HBT), when it is switched on
    private var bamRevision = 1
    private var bamSeq = 0
    private var bamWas: Boolean? = null
    private var lastBamMs = 0L
    @Volatile private var bamRepeat = false

    /** Called every second or so: while a search and rescue message waits to be acknowledged, its ALR sentence every 30 s. */
    fun tick() {
        val srv = AppState.nmeaOut ?: return
        val active = all.any { it.alarm }
        val now = System.currentTimeMillis()
        if (active && now - lastAlrMs >= 30_000L) { lastAlrMs = now; srv.send(Navtex.alr(now, active = true, acknowledged = false)) }
        val c = ctx ?: return
        if (!Settings(c).navtexBam) { bamWas = null; bamRepeat = false; return }
        val was = bamWas
        if (was != null && was != active) bamRevision = bamRevision % 99 + 1
        if ((was != null && was != active) || (was == null && active) || bamRepeat) srv.send(Navtex.alf(now, if (active) 'V' else 'N', bamRevision))
        bamWas = active; bamRepeat = false
        if (now - lastBamMs >= 30_000L) { lastBamMs = now; srv.send(Navtex.hbt(30, bamSeq++)); srv.send(Navtex.alc(active, bamRevision)) }
    }

    /**
     * A line written by a client of the NMEA server (the plotter, bridge equipment): the alarm acknowledged (ACK, or ACN with
     * command A), the alert asked for again (ACN Q), a command this phone does not carry out (ACN S or O: answered with ARC),
     * the masks asked for (CRQ) or changed (NRM). Nothing of this reaches the tracker or is transmitted.
     */
    fun onClientLine(line: String) {
        val c = ctx ?: return
        val srv = AppState.nmeaOut
        if (Navtex.isAlarmAck(line)) { acknowledgeRemote(); return }
        Navtex.parseAcn(line)?.let { a ->
            if (a.identifier == 3 || (a.identifier == 0 && a.command == 'Q')) when (a.command) {
                'A' -> acknowledgeRemote()
                'Q' -> bamRepeat = true
                else -> srv?.send(Navtex.arc(System.currentTimeMillis(), a.identifier, a.instance, a.command))
            }
            return
        }
        val settings = Settings(c)
        if (Navtex.isNrmQuery(line)) { for (f in listOf(490.0, 518.0, 4209.5)) srv?.send(Navtex.nrmSentence(ownMask(settings, f), "CR")); return }
        Navtex.parseNrm(line)?.let { m ->
            if (m.function != 1) return
            settings.navtexStations = if (m.stations == Navtex.ALL_LETTERS) "" else Navtex.maskToLetters(m.stations)
            val su = m.subjects or Navtex.FIXED_SUBJECTS
            settings.navtexSubjects = if (su == Navtex.ALL_LETTERS) "" else Navtex.maskToLetters(su)
            ownMaskChanged.value++
            srv?.send(Navtex.nrmSentence(ownMask(settings, m.khz), "CR"))
        }
    }

    /** Counts the changes of the phone's masks made from other equipment, so the screen reads them again. */
    val ownMaskChanged = MutableStateFlow(0)

    /** The operator has seen the search and rescue messages: the alarm stops (it is only reset by hand). */
    fun acknowledge(tellReceiver: Boolean = true) {
        synchronized(this) {
            if (all.none { it.alarm }) return
            all = all.map { if (it.alarm) it.copy(alarm = false) else it }
            publish()
        }
        AppState.nmeaOut?.send(Navtex.alr(System.currentTimeMillis(), active = false, acknowledged = true))
        if (tellReceiver) NavtexNet.acknowledge()                       // and on the receiver the message came from, if it is connected
    }

    /**
     * The alarm was acknowledged on other equipment (an ACK sentence from the plotter through the NMEA server): the same as
     * closing the alert window here.
     */
    fun acknowledgeRemote(olderThanMs: Long = 0L, tellReceiver: Boolean = true) {
        val now = System.currentTimeMillis()
        val keys = synchronized(this) {
            if (all.any { it.alarm && now - it.heardMs < olderThanMs }) return      // a new alarm: the news is older than it
            all.filter { it.alarm }.map { alertOf(it).key }
        }
        if (keys.isEmpty()) return
        acknowledge(tellReceiver)
        for (k in keys) AlertCenter.close(k)                            // closes the window (its own acknowledge finds nothing left)
    }

    /** Tags a message to be kept, or removes the tag. False when a quarter of the memory is already tagged. */
    fun keep(e: Entry, on: Boolean): Boolean {
        synchronized(this) {
            if (on && all.count { it.kept } >= MAX_KEPT) return false
            all = all.map { if (it === e || (it.msg == e.msg && it.khz == e.khz)) it.copy(kept = on) else it }
            publish()
        }
        return true
    }

    /** The messages shown, as text to save or share. */
    fun export(): String = Navtex.export(messages.value.map { it.msg to it.khz })

    private fun save() {
        val f = file() ?: return
        val a = JSONArray()
        for (e in all) a.put(JSONObject().apply {
            put("st", e.msg.station.toString()); put("su", e.msg.subject.toString()); put("n", e.msg.number); put("t", e.msg.text)
            put("lh", e.msg.lostHeader); put("lt", e.msg.lostTrailer); put("ms", e.msg.timeMs); put("khz", e.khz)
            put("hd", e.heardMs); put("k", e.kept); put("al", e.alarm)
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
                // what an earlier version let the user hide ("h") is shown again: messages cannot be deleted
                out.add(Entry(m, khz, if (m.lostHeader) Navtex.stationBySlot(stations, khz, m.timeMs, null, null) else Navtex.findStation(stations, m.station, khz, null, null),
                    Navtex.positions(m.text), o.optLong("hd", m.timeMs), o.optBoolean("k"), o.optBoolean("al")))
            }
        } catch (_: Exception) {}
        all = out
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
