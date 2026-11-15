/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.AlertKind
import es.ea7jrs.aprslorap.core.Dsc
import es.ea7jrs.aprslorap.core.EmergencyAlert
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DSC calls decoded in Modes (channel 70 or MF / HF): the last 200 valid calls, a repeat of the same call within 10 minutes only counts.
 * Each one goes to the NMEA server as the $CDDSC sentence a DSC radio would send (plotter, OpenCPN); distress alerts, relays and
 * acknowledgements open the DSC DISTRESS window.
 */
object DscHub {
    data class Entry(val call: Dsc.Call, val khz: Double, val repeats: Int = 1, val lastMs: Long = call.timeMs)

    val calls = MutableStateFlow<List<Entry>>(emptyList())
    private var ctx: Context? = null
    private const val MAX = 200
    private const val REPEAT_MS = 10 * 60_000L

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        load()
    }

    private fun file() = ctx?.let { File(it.filesDir, "dsc.json") }

    fun onCall(c: Dsc.Call, khz: Double) {
        val cx = ctx ?: return
        var repeat = false
        synchronized(this) {
            val old = calls.value
            val i = old.indexOfFirst { it.call.key == c.key && c.timeMs - it.lastMs < REPEAT_MS }
            calls.value = if (i >= 0) {
                repeat = true
                listOf(old[i].copy(repeats = old[i].repeats + 1, lastMs = c.timeMs)) + old.filterIndexed { k, _ -> k != i }
            } else (listOf(Entry(c, khz)) + old).take(MAX)
        }
        save()
        AppState.nmeaOut?.send(Dsc.nmea(c))
        if (!repeat && c.distress && Settings(cx).dscAlarm) AlertCenter.onDsc(c)
    }

    fun clear() { calls.value = emptyList(); save() }

    private fun save() {
        val f = file() ?: return
        val a = JSONArray()
        for (e in calls.value) a.put(JSONObject().apply {
            put("s", JSONArray(e.call.symbols.toList())); put("ms", e.call.timeMs); put("khz", e.khz); put("n", e.repeats); put("last", e.lastMs); put("e", e.call.errors)
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
                val s = o.getJSONArray("s")
                val c = Dsc.parse(IntArray(s.length()) { s.getInt(it) }, o.optInt("e"), o.optLong("ms"))
                out.add(Entry(c, o.optDouble("khz", 156525.0), o.optInt("n", 1), o.optLong("last", c.timeMs)))
            }
        } catch (_: Exception) {}
        calls.value = out
    }

    /** Title and text of the window for a distress call. */
    fun alertOf(c: Dsc.Call): EmergencyAlert {
        val ship = c.distressShip ?: c.selfId
        val what = when {
            c.format == Dsc.FMT_DISTRESS -> "Distress alert"
            c.tc1 == Dsc.TC_DISTRESS_ACK -> "Distress acknowledgement by ${c.selfId} (${Dsc.mmsiKind(c.selfId)})"
            c.tc1 == Dsc.TC_DISTRESS_RELAY -> "Distress relay by ${c.selfId} (${Dsc.mmsiKind(c.selfId)})"
            else -> "Distress call from ${c.selfId}"
        }
        val la = c.lat; val lo = c.lon
        val text = listOfNotNull(what, c.nature?.let { Dsc.natureName(it) },
            if (la != null && lo != null) "position " + Fmt.latLon(la, true) + " " + Fmt.latLon(lo, false) + (c.time?.let { " at ${it.take(2)}:${it.drop(2)} UTC" } ?: "") else "position not given",
            c.subsequent?.let { "then " + Dsc.tc1Name(it) }).joinToString(" · ")
        return EmergencyAlert("dsc:$ship", AlertKind.DSC, "MMSI $ship", text, false, ship.substringBefore('-').toLongOrNull(), c.timeMs, false)
    }
}
