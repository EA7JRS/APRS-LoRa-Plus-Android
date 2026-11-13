/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Adif
import es.ea7jrs.aprslorap.core.Cty
import es.ea7jrs.aprslorap.core.DxCluster
import es.ea7jrs.aprslorap.core.Qso
import es.ea7jrs.aprslorap.core.RotorProto
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

/**
 * DX cluster over telnet: logs in with our callsign, keeps the last spots and reconnects if the node drops us. TUNE sends a
 * spot to the SDR (SdrControl) and / or to the radio on the rotor box (CAT); ROTOR points the box with the beam heading
 * from cty.dat (AD1C). AUTO does both with each new spot that passes the filters.
 */
object DxLink {
    data class State(
        val connected: Boolean = false, val status: String = "Off", val node: String = "", val spots: List<DxCluster.Spot> = emptyList(),
        val console: List<String> = emptyList(), val ctyStatus: String = "",
    )

    val state = MutableStateFlow(State())
    /** Every skimmer reception (RBN through the cluster), newest first, the last 60 min and at most 3000: the RBN map. */
    val rbn = MutableStateFlow<List<es.ea7jrs.aprslorap.core.Rbn.Hear>>(emptyList())
    @Volatile var cty: Cty.Table? = null
        private set

    // AUTO: tune / aim at every new spot that passes [autoFilter]
    @Volatile var auto = false
    @Volatile var autoSdr = true
    @Volatile var autoCat = true
    @Volatile var autoFilter = DxCluster.Filter()
    @Volatile var autoLongPath = false

    // RBN alerts: skimmer spots on VHF / UHF as notifications (also read aloud by Android Auto)
    @Volatile var rbnAlert = es.ea7jrs.aprslorap.core.Rbn.AlertRange.OFF
    @Volatile var rbnAlertMe = false
    @Volatile private var myCall = ""
    @Volatile private var appCtx: Context? = null
    private val alerter = es.ea7jrs.aprslorap.core.Rbn.Alerter()

    /** Reads the RBN alert settings (at connect and when they change on the RBN screen). */
    fun loadRbnAlert(settings: Settings) {
        rbnAlert = runCatching { es.ea7jrs.aprslorap.core.Rbn.AlertRange.valueOf(settings.rbnAlert) }.getOrDefault(es.ea7jrs.aprslorap.core.Rbn.AlertRange.OFF)
        rbnAlertMe = settings.rbnAlertMe
    }

    @Volatile private var wanted = false
    @Volatile private var sock: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var thread: Thread? = null
    private val lock = Any()

    private fun set(f: (State) -> State) = synchronized(lock) { state.value = f(state.value) }
    private fun console(line: String) = set { s -> s.copy(console = (s.console + line).takeLast(200)) }

    fun connect(ctx: Context, node: DxCluster.Node, call: String) {
        disconnect()
        loadCty(ctx)
        appCtx = ctx.applicationContext
        myCall = call.substringBefore('-').uppercase()
        loadRbnAlert(Settings(ctx))
        wanted = true
        set { it.copy(node = node.name + " · " + node.address, status = "Connecting...") }
        thread = Thread({ run(node, call) }, "dx-cluster").apply { isDaemon = true; start() }
    }

    fun disconnect() {
        wanted = false
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
        thread?.interrupt(); thread = null
        set { it.copy(connected = false, status = "Off") }
    }

    /** A command line to the node (console, "sh/dx 30", a spot...). */
    fun send(line: String): Boolean {
        val o = out ?: run { AppState.error.value = "Not connected to the DX cluster"; return false }
        Thread {
            try { synchronized(lock) { o.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1)); o.flush() } } catch (_: Exception) {}
        }.start()
        console("> $line")
        return true
    }

    private fun run(node: DxCluster.Node, call: String) {
        var wait = 5_000L
        while (wanted) {
            try {
                val s = Socket()
                s.keepAlive = true
                s.connect(InetSocketAddress(node.host, node.port), 15_000)
                sock = s; out = s.getOutputStream()
                set { it.copy(connected = true, status = "Connected to ${node.name}") }
                console("— connected to ${node.address}")
                wait = 5_000L
                read(s.getInputStream(), call)
            } catch (e: Exception) {
                if (wanted) console("— " + (e.message ?: e.javaClass.simpleName))
            }
            try { sock?.close() } catch (_: Exception) {}
            sock = null; out = null
            if (!wanted) break
            set { it.copy(connected = false, status = "Disconnected · retrying in ${wait / 1000} s") }
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            wait = (wait * 2).coerceAtMost(120_000L)
        }
        set { it.copy(connected = false, status = if (wanted) "Disconnected" else "Off") }
    }

    private fun read(inp: InputStream, call: String) {
        val buf = ByteArray(2048)
        val line = StringBuilder()
        var loggedIn = false
        var askedHistory = false
        var iac = 0                       // telnet negotiation bytes still to skip
        while (wanted) {
            val n = inp.read(buf)
            if (n < 0) return
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                if (iac > 0) { iac--; continue }
                if (b == 0xFF) { iac = 2; continue }     // IAC DO/DONT/WILL/WONT x: no options are accepted, the node goes on in plain text
                when (b) {
                    0x0A -> { onLine(line.toString(), call); line.setLength(0) }
                    0x0D, 0x07, 0x00 -> {}
                    else -> line.append(b.toChar())
                }
            }
            val partial = line.toString()
            if (!loggedIn && DxCluster.isLoginPrompt(partial)) {
                console(partial.trim())
                line.setLength(0)
                send(call)
                loggedIn = true
            } else if (!askedHistory && partial.trimEnd().endsWith(">") && partial.contains(call.substringBefore('-'), ignoreCase = true)) {
                // DX Spider prompt ("EA7JRS de EA4URE-2 9-Nov-2026 1900Z dxspider >"): ask for the last spots once
                askedHistory = true
                send("sh/dx 30")
            }
        }
    }

    private fun onLine(raw: String, call: String) {
        val l = raw.trimEnd()
        if (l.isEmpty()) return
        val spot = DxCluster.parse(l, call, System.currentTimeMillis())
        if (spot == null) { console(l); return }
        es.ea7jrs.aprslorap.core.Rbn.of(spot)?.let { h ->
            val old = spot.receivedMs - 60 * 60_000L
            rbn.value = (listOf(h) + rbn.value.filter { it.receivedMs > old && !(it.skimmer == h.skimmer && it.dx == h.dx && Math.abs(it.freqKhz - h.freqKhz) < 1) }).take(3000)
            if (alerter.check(h, rbnAlert, rbnAlertMe, myCall, spot.receivedMs)) {
                val n = AppState.notifier ?: appCtx?.let { Notifier(it) }
                val range = if (h.freqKhz >= 300_000.0) "UHF" else "VHF"
                n?.alert("RBN:${h.dx}:${h.band}", "RBN $range · ${h.band ?: ""}".trimEnd(' ', '·'), h.skimmer.removeSuffix("-#"),
                    es.ea7jrs.aprslorap.core.Rbn.alertText(h), null, null, null)
            }
        }
        var fresh = false
        set { s ->
            val old = s.spots.firstOrNull { it.key == spot.key }
            fresh = old == null || spot.receivedMs - old.receivedMs > 10 * 60_000
            s.copy(spots = (listOf(spot) + s.spots.filter { it.key != spot.key }).take(300))
        }
        if (fresh && auto && autoFilter.passes(spot)) {
            tune(spot, autoSdr, autoCat)
            aim(spot, autoLongPath)
        }
    }

    // ------------------------------------------------------------------ actions on a spot

    /** Tunes the SDR and / or the radio of the rotor box to the spot. Returns what was done. */
    fun tune(spot: DxCluster.Spot, sdr: Boolean, cat: Boolean): String {
        val mode = DxCluster.rigMode(spot)
        val done = ArrayList<String>()
        if (sdr) {
            if (AppState.sdr.value.connected) {
                SdrControl.tune(spot.freqHz)
                SdrControl.mode(mode, when (mode) { "CW" -> 500; "FM" -> 12_500; "AM" -> 6_000; else -> 2_700 })
                done.add("SDR")
            }
        }
        if (cat) {
            if (AppState.rotorCat.value?.link == true) {
                RotorLink.send(RotorProto.catFreq(spot.freqHz))
                RotorLink.send(RotorProto.catMode(if (spot.group == DxCluster.Group.DIGITAL) "PKTUSB" else mode))
                done.add("CAT radio")
            }
        }
        val text = if (done.isEmpty()) "Nothing tuned: connect an SDR (SDR) or the radio on the rotor box (Rotor > Radio)"
            else "Tuned " + DxCluster.formatKhz(spot.freqKhz) + " kHz $mode → " + done.joinToString(" + ")
        set { it.copy(status = text) }
        return text
    }

    /** Points the rotor box at the DX entity (short or long path). */
    fun aim(spot: DxCluster.Spot, longPath: Boolean): String {
        val me = Own.position()
        val p = Cty.path(cty, spot.dx, me?.first, me?.second)
        val text = when {
            me == null -> "No own position (tracker GNSS or the locator in Spotting)"
            p == null -> "Unknown entity for ${spot.dx} (cty.dat ${if (cty == null) "not loaded" else "has no prefix"})"
            else -> {
                val az = if (longPath) p.longDeg else p.shortDeg
                if (RotorLink.command(RotorProto.pos(az.toDouble(), 0.0))) "Rotor box → $az° (${if (longPath) "long" else "short"} path, ${p.entity.name})" else "Rotor box not connected"
            }
        }
        set { it.copy(status = text) }
        return text
    }

    /** Opens the Log form filled with the spot (call, frequency, mode, now). */
    fun logDraft(ctx: Context, spot: DxCluster.Spot): Qso {
        val s = Settings(ctx)
        val label = when (spot.mode) { "SSB" -> DxCluster.rigMode(spot); else -> spot.mode }
        val (m, sub) = Adif.MODES.firstOrNull { it.first == label }?.second ?: ((if (spot.group == DxCluster.Group.CW) "CW" else "SSB") to "")
        val rst = if (spot.group == DxCluster.Group.CW) "599" else if (spot.group == DxCluster.Group.PHONE) "59" else ""
        return Qso.create(LogStore.newId(), spot.dx, System.currentTimeMillis(), spot.freqHz, m, sub, rst, rst, s.callsign, Spotter.locator(), txPwr = s.logPower)
    }

    // ------------------------------------------------------------------ cty.dat

    /** Loads cty.dat from the app folder; downloads it (www.country-files.com) when missing or older than 14 days. */
    fun loadCty(ctx: Context) {
        val f = File(ctx.filesDir, "cty.dat")
        if (cty == null && f.exists()) parseCty(f)
        val old = !f.exists() || System.currentTimeMillis() - f.lastModified() > 14L * 24 * 3600 * 1000
        if (old) Thread({ download(f) }, "cty-download").apply { isDaemon = true; start() }
    }

    private fun parseCty(f: File) {
        try {
            val t = Cty.parse(f.readText(Charsets.ISO_8859_1))
            cty = t
            set { it.copy(ctyStatus = "cty.dat: ${t.entities} entities") }
        } catch (e: Exception) { set { it.copy(ctyStatus = "cty.dat unreadable") } }
    }

    private fun download(f: File) {
        var why = ""
        for (url in Cty.URLS) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "APRSLoRaPlus/${BuildConfig.VERSION_NAME} (Android)")
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val text = Cty.textOf(c.inputStream.use { it.readBytes() }) ?: throw Exception("no cty.dat inside")
                if (Cty.parse(text).entities < 300) throw Exception("not a cty.dat")
                val tmp = File(f.parentFile, "cty.dat.tmp")
                tmp.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
                tmp.renameTo(f)
                parseCty(f)
                return
            } catch (e: Exception) {
                why = (java.net.URI(url).host ?: url) + ": " + (e.message ?: "error")
            }
        }
        set { it.copy(ctyStatus = if (cty != null) it.ctyStatus + " (update failed)" else "cty.dat not downloaded ($why): choose the file") }
    }

    /** Import of a country file chosen by the user (no internet, or a newer one): cty.dat, CTY_WT.DAT, CTY_WT_MOD.DAT or the zip of country-files.com. */
    fun importCty(ctx: Context, bytes: ByteArray): Boolean {
        val text = try { Cty.textOf(bytes) } catch (_: Exception) { null } ?: return false
        if (Cty.parse(text).entities < 300) return false
        val f = File(ctx.filesDir, "cty.dat")
        f.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
        parseCty(f)
        return true
    }

    fun nodeFrom(addr: String, name: String): DxCluster.Node? {
        val a = addr.trim()
        val host = a.substringBefore(':').trim()
        val port = a.substringAfter(':', "7300").trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return DxCluster.Node(name.trim().ifEmpty { host }.uppercase(Locale.US), host, port, "added by hand")
    }
}
