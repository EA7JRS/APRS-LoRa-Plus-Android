/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Hab
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * High-altitude balloon (HAB) telemetry captured by the app: UKHAS "$$CALL,...*CRC" sentences decoded from RTTY (the
 * app's RTL-SDR receiver through [ModemRunner]) or carried as ASCII in a LoRa packet. Keeps the latest telemetry per
 * balloon callsign; only CRC-valid sentences are kept.
 */
object HabHub {
    data class Entry(val tlm: Hab.Telemetry, val khz: Double, val count: Int, val lastMs: Long)

    val balloons = MutableStateFlow<List<Entry>>(emptyList())
    private var ctx: Context? = null
    private const val MAX = 100

    @Volatile private var lastKhz = 0.0
    private val asm = Hab.Assembler { onSentence(it, lastKhz) }

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        load()
    }

    private fun file() = ctx?.let { File(it.filesDir, "hab.json") }

    /** Characters from the RTTY modem ([Modems.Rtty.onText]); [khz] is the receiver dial, for the record. */
    fun feedRtty(text: String, khz: Double) {
        lastKhz = khz
        synchronized(asm) { asm.feed(text) }
    }

    /** A whole ASCII packet (e.g. HAB over LoRa): parse it directly. */
    fun onPacket(text: String, khz: Double) { Hab.parse(text)?.let { onSentence(it, khz) } }

    fun onSentence(t: Hab.Telemetry, khz: Double) {
        if (!t.crcOk) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            val old = balloons.value
            val i = old.indexOfFirst { it.tlm.callsign == t.callsign }
            balloons.value = if (i >= 0)
                listOf(old[i].copy(tlm = t, count = old[i].count + 1, lastMs = now)) + old.filterIndexed { k, _ -> k != i }
            else (listOf(Entry(t, khz, 1, now)) + old).take(MAX)
        }
        save()
    }

    fun clear() { balloons.value = emptyList(); save() }

    private fun save() {
        val f = file() ?: return
        val a = JSONArray()
        for (e in balloons.value) a.put(JSONObject().apply {
            put("raw", e.tlm.raw); put("khz", e.khz); put("n", e.count); put("ms", e.lastMs)
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
                val t = Hab.parse(o.getString("raw")) ?: continue
                out.add(Entry(t, o.optDouble("khz", 0.0), o.optInt("n", 1), o.optLong("ms", 0L)))
            }
        } catch (_: Exception) {}
        balloons.value = out
    }
}
