/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.AlertKind
import es.ea7jrs.aprslorap.core.Beacon406
import es.ea7jrs.aprslorap.core.EmergencyAlert
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 406 MHz beacons heard by the app's RTL-SDR (complex 48 kS/s channel tuned to 406.031 MHz): the receiver, the last 100 beacons (one
 * per 15-hex id, a new burst within 10 minutes only counts and updates the position) and the alarm for a real alert.
 */
object Beacon406Hub {
    data class Entry(val msg: Beacon406.Message, val bursts: Int = 1, val firstMs: Long = msg.timeMs)
    data class Status(val listening: Boolean = false, val bursts: Int = 0, val decoded: Int = 0, val lastOffsetHz: Double = 0.0, val lastSnrDb: Double = 0.0)

    val beacons = MutableStateFlow<List<Entry>>(emptyList())
    val status = MutableStateFlow(Status())
    private var ctx: Context? = null
    private var rx = Beacon406.Receiver()
    private var tapped: RtlTcpLink? = null
    private const val MAX = 100

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        load()
    }

    private fun file() = ctx?.let { File(it.filesDir, "beacon406.json") }

    /** Starts listening on the app's RTL-SDR (call again after it reconnects); false if it is not connected. */
    @Synchronized
    fun start(): Boolean {
        val link = SdrControl.rtl() ?: run { status.value = status.value.copy(listening = false); return false }
        if (tapped !== link) {
            tapped?.baseband("406", null)
            rx = Beacon406.Receiver().also { it.onMessage = { m -> onMessage(m) } }
            link.baseband("406") { re, im, n -> synchronized(rx) { rx.feed(re, im, n) } }
            tapped = link
        }
        status.value = status.value.copy(listening = true)
        return true
    }

    @Synchronized
    fun stop() {
        tapped?.baseband("406", null)
        tapped = null
        status.value = status.value.copy(listening = false)
    }

    /** The link may have changed (reconnected): listen again on the new one. */
    fun ensure() {
        if (status.value.listening && SdrControl.rtl() !== tapped) start()
        if (status.value.listening) status.value = status.value.copy(bursts = rx.bursts, decoded = rx.decoded, lastOffsetHz = rx.lastOffsetHz, lastSnrDb = rx.lastSnrDb)
    }

    private fun onMessage(m: Beacon406.Message) {
        status.value = status.value.copy(bursts = rx.bursts, decoded = rx.decoded, lastOffsetHz = rx.lastOffsetHz, lastSnrDb = rx.lastSnrDb)
        if (!m.valid) return
        val c = ctx ?: return
        var repeat = false
        synchronized(this) {
            val old = beacons.value
            val i = old.indexOfFirst { it.msg.hexId == m.hexId && m.timeMs - it.msg.timeMs < 10 * 60_000L }
            beacons.value = if (i >= 0) {
                repeat = true
                listOf(Entry(m, old[i].bursts + 1, old[i].firstMs)) + old.filterIndexed { k, _ -> k != i }
            } else (listOf(Entry(m)) + old).take(MAX)
        }
        save()
        if (!repeat && m.distress && Settings(c).beacon406Alarm) AlertCenter.onBeacon406(m)
    }

    fun clear() { beacons.value = emptyList(); save() }

    private fun save() {
        val f = file() ?: return
        val a = JSONArray()
        for (e in beacons.value) a.put(JSONObject().apply {
            put("b", e.msg.bits.joinToString("")); put("ms", e.msg.timeMs); put("off", e.msg.offsetHz); put("snr", e.msg.snrDb); put("n", e.bursts); put("first", e.firstMs)
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
                val bits = o.getString("b").map { it - '0' }.toIntArray()
                val m = Beacon406.parse(bits, o.optLong("ms"), o.optDouble("off"), o.optDouble("snr"))
                out.add(Entry(m, o.optInt("n", 1), o.optLong("first", m.timeMs)))
            }
        } catch (_: Exception) {}
        beacons.value = out
    }

    fun alertOf(m: Beacon406.Message): EmergencyAlert {
        val text = listOfNotNull(m.beaconType.ifEmpty { "Beacon" } + " " + m.hexId, m.id.takeIf { it.isNotBlank() }, "country ${m.country}",
            if (m.lat != null && m.lon != null) "position " + Fmt.latLon(m.lat!!, true) + " " + Fmt.latLon(m.lon!!, false) else "no position in the message").joinToString(" · ")
        return EmergencyAlert("406:" + m.hexId, AlertKind.BEACON406, m.beaconType.ifEmpty { "406 MHz" } + " " + m.hexId, text, false, null, m.timeMs, false)
    }
}
