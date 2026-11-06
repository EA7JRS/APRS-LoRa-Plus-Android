/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.ControlEvent
import es.ea7jrs.aprslorap.core.Sdr
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Control of an SDR program or server (see [Sdr]): tune, mode, filter, gain; and follow the tracker:
 * - a satellite transmitter with Doppler (range rate of the `sat` event, every 2 s, extrapolated every 0.5 s);
 * - the tracker's LoRa / APRS frequency;
 * - the frequency of the rotor's fixed target (repeater...).
 * Passes can start / stop the program's recorder (SDR++: AOS / LOS; SDRconnect: start_recording / stop_recording).
 * Independent of the tracker link: it runs while the app is alive, until [disconnect].
 */
object SdrControl {
    enum class Follow(val label: String) { MANUAL("Manual"), SATELLITE("Satellite (Doppler)"), LORA("Tracker LoRa"), TARGET("Rotor target") }

    data class State(
        val connected: Boolean = false, val status: String = "Off", val device: String = "", val canControl: Boolean = true,
        val freqHz: Long? = null, val mode: String? = null, val bandwidthHz: Int? = null,
        val gain: Int? = null, val gainMin: Int = 0, val gainMax: Int = 0,
        val signalDb: Double? = null, val snrDb: Double? = null, val recording: Boolean = false,
        val followHz: Long? = null, val dopplerHz: Long? = null,
    )

    internal interface LinkApi {
        fun tune(hz: Long)
        fun mode(mode: String, bwHz: Int)
        fun gain(g: Int)
        fun record(on: Boolean)
        fun close()
    }
    private interface Link : LinkApi

    private var link: LinkApi? = null
    private val tick = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var follow = Follow.MANUAL
    @Volatile private var stepHz = 100L
    @Volatile private var offsetHz = 0L
    @Volatile private var txIndex = -1
    @Volatile private var recordPasses = false
    private var lastSentHz: Long? = null
    private var rr1: Pair<Long, Double>? = null                       // (time ms, range rate km/s)
    private var rr2: Pair<Long, Double>? = null
    private var inView = false

    init { tick.scheduleWithFixedDelay({ try { followTick() } catch (_: Exception) {} }, 500, 500, TimeUnit.MILLISECONDS) }

    private fun set(f: (State) -> State) { synchronized(this) { AppState.sdr.value = f(AppState.sdr.value) } }
    internal fun update(f: (State) -> State) = set(f)

    // ------------------------------------------------------------------ public

    @Synchronized
    fun connect(ctx: android.content.Context, kind: Sdr.Kind, host: String, port: Int, s: Settings) {
        disconnect()
        set { State(status = "Connecting to $host:$port...", mode = if (kind == Sdr.Kind.RTLTCP) s.sdrMode else null) }
        val l = when (kind) {
            Sdr.Kind.RIGCTL -> RigctlLink(host, port)
            Sdr.Kind.SDRCONNECT -> SdrConnectLink(host, port)
            Sdr.Kind.SPYSERVER -> SpyLink(host, port)
            Sdr.Kind.RTLTCP -> RtlTcpLink(ctx.applicationContext, host, port, s.sdrPpm, s.sdrGainPercent, s.sdrSquelchDb.toDouble())
        }
        link = l
        if (l is RtlTcpLink) { l.mode(s.sdrMode, s.sdrBandwidth); l.tune(s.sdrLastHz) }
        lastSentHz = null
    }

    /** RTL-SDR receiver only: NFM squelch level, dBFS. */
    fun squelch(db: Double) { (link as? RtlTcpLink)?.squelch(db) }
    val isLocalReceiver: Boolean get() = link is RtlTcpLink

    @Synchronized
    fun disconnect() {
        DigitalVoiceRunner.stop()
        PacketRunner.stop()
        link?.close(); link = null
        set { State() }
    }

    fun configure(s: Settings) {
        follow = runCatching { Follow.valueOf(s.sdrFollow) }.getOrDefault(Follow.MANUAL)
        stepHz = s.sdrStepHz.toLong().coerceAtLeast(1)
        offsetHz = s.sdrOffsetHz.toLong()
        txIndex = s.sdrTx
        recordPasses = s.sdrRecordPasses
        lastSentHz = null
    }

    fun tune(hz: Long) { lastSentHz = hz; link?.tune(hz) }
    fun mode(mode: String, bwHz: Int) { link?.mode(mode, bwHz) }
    fun gain(g: Int) { link?.gain(g) }
    fun record(on: Boolean) { link?.record(on) }

    /** Events of the tracker (Controller thread). */
    fun onEvent(ev: ControlEvent) {
        if (ev is ControlEvent.Sat) {
            val rr = ev.state.rangeRateKmS
            val now = System.currentTimeMillis()
            if (rr != null) { rr1 = rr2; rr2 = now to rr }
            val el = ev.state.elDeg
            val visible = el != null && el > 0.0
            if (recordPasses && follow == Follow.SATELLITE && visible != inView) record(visible)
            inView = visible
        }
    }

    // ------------------------------------------------------------------ follow

    private fun satFrequency(): Pair<Long, Long>? {
        val txs = AppState.satTransmitters.value
        val t = txs.firstOrNull { it.index == txIndex } ?: AppState.satReceiver.value?.tx?.let { i -> txs.firstOrNull { it.index == i } } ?: txs.firstOrNull { it.downHz > 0 } ?: return null
        if (t.downHz <= 0) return null
        val b = rr2 ?: return null
        val now = System.currentTimeMillis()
        val a = rr1
        var rr = b.second
        if (a != null && b.first > a.first) {
            val dt = ((now - b.first).coerceIn(0, 3000)) / 1000.0
            rr += (b.second - a.second) / ((b.first - a.first) / 1000.0) * dt
        }
        val f = Sdr.dopplerRx(t.downHz, rr)
        return f to (f - t.downHz)
    }

    private fun followTick() {
        val l = link ?: return
        if (!AppState.sdr.value.connected) return
        val target: Long? = when (follow) {
            Follow.MANUAL -> null
            Follow.SATELLITE -> satFrequency()?.let { (f, d) -> set { it.copy(dopplerHz = d) }; f }
            Follow.LORA -> AppState.trackerInfo.value?.let { info -> info.loras.getOrNull(AppState.trackerStatus.value?.lora ?: info.lora)?.freqHz }
            Follow.TARGET -> AppState.rotorTargets.value.firstOrNull { it.slot == AppState.rotorTargetSel.value }?.freqHz?.takeIf { it > 0 }
        }
        if (target == null) { set { it.copy(followHz = null) }; return }
        val hz = target + offsetHz
        set { it.copy(followHz = hz) }
        val last = lastSentHz
        if (last == null || abs(hz - last) >= stepHz) { lastSentHz = hz; l.tune(hz) }
    }

    // ------------------------------------------------------------------ rigctl (SDR++, gqrx...)

    private class RigctlLink(val host: String, val port: Int) : Link {
        private val q = LinkedBlockingQueue<String>()
        @Volatile private var running = true
        private var sock: Socket? = null

        init { Thread({ run() }, "sdr-rigctl").also { it.isDaemon = true; it.start() } }

        override fun tune(hz: Long) { q.offer(Sdr.Rigctl.setFreq(hz)) }
        override fun mode(mode: String, bwHz: Int) { q.offer(Sdr.Rigctl.setMode(mode, bwHz)) }
        override fun gain(g: Int) {}
        override fun record(on: Boolean) { q.offer(if (on) Sdr.Rigctl.AOS else Sdr.Rigctl.LOS); SdrControl.set { it.copy(recording = on) } }
        override fun close() { running = false; try { sock?.close() } catch (_: Exception) {} }

        private fun run() {
            while (running) {
                try {
                    Socket().use { s ->
                        sock = s
                        s.connect(InetSocketAddress(host, port), 5000)
                        s.soTimeout = 3000
                        s.tcpNoDelay = true
                        val out = s.getOutputStream()
                        val inp = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                        SdrControl.set { it.copy(connected = true, status = "Connected to $host:$port (rigctl)", device = "rigctl") }
                        var lastPoll = 0L
                        while (running) {
                            val cmd = q.poll(500, TimeUnit.MILLISECONDS)
                            if (cmd != null) {
                                out.write(cmd.toByteArray(Charsets.US_ASCII)); out.flush()
                                val r = Sdr.Rigctl.reply(inp.readLine() ?: throw java.io.EOFException())
                                if (r != null && r != 0) SdrControl.set { it.copy(status = "The program answered error $r to ${cmd.trim()}") }
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastPoll >= 2000 && q.isEmpty()) {
                                lastPoll = now
                                out.write((Sdr.Rigctl.GET_FREQ + Sdr.Rigctl.GET_MODE).toByteArray()); out.flush()
                                val f = inp.readLine()?.trim()?.toDoubleOrNull()?.toLong()
                                val m = inp.readLine()?.trim()
                                val bw = if (m != null && !m.startsWith("RPRT")) inp.readLine()?.trim()?.toIntOrNull() else null   // Hamlib: mode, then passband
                                SdrControl.set { it.copy(freqHz = f ?: it.freqHz, mode = m?.takeIf { x -> !x.startsWith("RPRT") }?.let(Sdr.Rigctl::appMode) ?: it.mode, bandwidthHz = bw ?: it.bandwidthHz) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!running) break
                    SdrControl.set { it.copy(connected = false, status = "No connection with $host:$port (${e.message}); retrying") }
                    try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    // ------------------------------------------------------------------ SDRconnect (WebSocket)

    private class SdrConnectLink(val host: String, val port: Int) : Link {
        private val q = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        @Volatile private var running = true
        private var sock: Socket? = null
        private val rnd = SecureRandom()
        @Volatile private var centerHz: Long? = null
        @Volatile private var sampleRate: Double? = null

        init { Thread({ run() }, "sdr-sdrconnect").also { it.isDaemon = true; it.start() } }

        private fun text(s: String) { q.offer(1 to s.toByteArray(Charsets.UTF_8)) }

        override fun tune(hz: Long) {
            val c = centerHz; val sr = sampleRate
            if (c == null || sr == null || abs(hz - c) > sr * 0.4) text(Sdr.SdrConnect.setCenter(hz))   // outside the band shown: move the LO too
            text(Sdr.SdrConnect.setFreq(hz))
        }
        override fun mode(mode: String, bwHz: Int) { text(Sdr.SdrConnect.setMode(mode)); if (bwHz > 0) text(Sdr.SdrConnect.setBandwidth(bwHz)) }
        override fun gain(g: Int) { text(Sdr.SdrConnect.setGain(g)) }
        override fun record(on: Boolean) {
            text(if (on) Sdr.SdrConnect.event("start_recording", "audio") else Sdr.SdrConnect.event("stop_recording", ""))
            SdrControl.set { it.copy(recording = on) }
        }
        override fun close() { running = false; try { sock?.close() } catch (_: Exception) {} }

        private fun send(out: OutputStream, op: Int, payload: ByteArray) {
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            synchronized(out) { out.write(Sdr.WebSocket.frame(op, payload, mask)); out.flush() }
        }

        private fun readHead(inp: InputStream): String {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) throw java.io.EOFException(); sb.append(b.toChar()); if (sb.length > 4096) throw java.io.IOException("header too long") }
            return sb.toString()
        }

        private fun run() {
            while (running) {
                try {
                    Socket().use { s ->
                        sock = s
                        s.connect(InetSocketAddress(host, port), 5000)
                        s.tcpNoDelay = true
                        val out = s.getOutputStream()
                        val inp = BufferedInputStream(s.getInputStream())
                        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
                        out.write(Sdr.WebSocket.handshake(host, port, Sdr.SdrConnect.PATH, key).toByteArray(Charsets.US_ASCII)); out.flush()
                        s.soTimeout = 5000
                        Sdr.WebSocket.checkResponse(readHead(inp), key)?.let { throw java.io.IOException(it) }
                        s.soTimeout = 0
                        SdrControl.set { it.copy(connected = true, status = "Connected to SDRconnect $host:$port") }
                        val writer = Thread({
                            try {
                                for (p in Sdr.SdrConnect.READ) send(out, 1, Sdr.SdrConnect.getProperty(p).toByteArray())
                                while (running && !s.isClosed) {
                                    val (op, data) = q.poll(1, TimeUnit.SECONDS) ?: continue
                                    send(out, op, data)
                                }
                            } catch (_: Exception) { try { s.close() } catch (_: Exception) {} }
                        }, "sdr-sdrconnect-tx").also { it.isDaemon = true; it.start() }
                        val partial = java.io.ByteArrayOutputStream()
                        while (running) {
                            val f = Sdr.WebSocket.read(inp)
                            when (f.opcode) {
                                8 -> { send(out, 8, ByteArray(0)); throw java.io.EOFException("closed by SDRconnect") }
                                9 -> send(out, 10, f.payload)
                                1, 0 -> {
                                    partial.write(f.payload)
                                    if (f.fin) { onText(String(partial.toByteArray(), Charsets.UTF_8)); partial.reset() }
                                }
                                else -> {}                                     // binary (audio, IQ, spectrum): not asked for
                            }
                        }
                        writer.interrupt()
                    }
                } catch (e: Exception) {
                    if (!running) break
                    SdrControl.set { it.copy(connected = false, status = "No connection with SDRconnect $host:$port (${e.message}); retrying") }
                    try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
                }
            }
        }

        private fun onText(t: String) {
            val m = Sdr.SdrConnect.parse(t) ?: return
            if (m.device != null && m.device != "primary") return
            if (m.type != "property_changed" && m.type != "get_property_response") return
            val v = m.value
            when (m.property) {
                "device_center_frequency" -> centerHz = v.toDoubleOrNull()?.toLong()
                "device_sample_rate" -> sampleRate = v.toDoubleOrNull()
            }
            SdrControl.set { st ->
                when (m.property) {
                    "device_vfo_frequency" -> st.copy(freqHz = v.toDoubleOrNull()?.toLong() ?: st.freqHz)
                    "demodulator" -> st.copy(mode = v)
                    "filter_bandwidth" -> st.copy(bandwidthHz = v.toDoubleOrNull()?.toInt())
                    "lna_state" -> st.copy(gain = v.toDoubleOrNull()?.toInt())
                    "lna_state_min" -> st.copy(gainMin = v.toDoubleOrNull()?.toInt() ?: 0)
                    "lna_state_max" -> st.copy(gainMax = v.toDoubleOrNull()?.toInt() ?: 0)
                    "signal_power" -> st.copy(signalDb = v.toDoubleOrNull())
                    "signal_snr" -> st.copy(snrDb = v.toDoubleOrNull())
                    "active_device" -> st.copy(device = v)
                    "can_control" -> st.copy(canControl = v.equals("true", true))
                    else -> st
                }
            }
        }
    }

    // ------------------------------------------------------------------ SpyServer (Airspy)

    private class SpyLink(val host: String, val port: Int) : Link {
        private val q = LinkedBlockingQueue<ByteArray>()
        @Volatile private var running = true
        private var sock: Socket? = null

        init { Thread({ run() }, "sdr-spyserver").also { it.isDaemon = true; it.start() } }

        override fun tune(hz: Long) { for (b in Sdr.SpyServer.tune(hz)) q.offer(b); SdrControl.set { it.copy(freqHz = hz) } }
        override fun mode(mode: String, bwHz: Int) {}            // SpyServer sends IQ: the client that listens demodulates
        override fun gain(g: Int) { q.offer(Sdr.SpyServer.setting(Sdr.SpyServer.SET_GAIN, g)); SdrControl.set { it.copy(gain = g) } }
        override fun record(on: Boolean) {}
        override fun close() { running = false; try { sock?.close() } catch (_: Exception) {} }

        private fun readFully(inp: InputStream, b: ByteArray) {
            var o = 0
            while (o < b.size) { val n = inp.read(b, o, b.size - o); if (n < 0) throw java.io.EOFException(); o += n }
        }

        private fun run() {
            while (running) {
                try {
                    Socket().use { s ->
                        sock = s
                        s.connect(InetSocketAddress(host, port), 5000)
                        s.tcpNoDelay = true
                        val out = s.getOutputStream()
                        val inp = BufferedInputStream(s.getInputStream())
                        out.write(Sdr.SpyServer.hello("APRS LoRa+ Android")); out.flush()
                        val writer = Thread({
                            try {
                                for (b in Sdr.SpyServer.lightStream()) out.write(b)
                                out.flush()
                                while (running && !s.isClosed) { val b = q.poll(1, TimeUnit.SECONDS) ?: continue; out.write(b); out.flush() }
                            } catch (_: Exception) { try { s.close() } catch (_: Exception) {} }
                        }, "sdr-spyserver-tx").also { it.isDaemon = true; it.start() }
                        val head = ByteArray(Sdr.SpyServer.HEADER_BYTES)
                        while (running) {
                            readFully(inp, head)
                            val h = Sdr.SpyServer.header(head)
                            if (h.bodySize < 0 || h.bodySize > (1 shl 20)) throw java.io.IOException("bad SpyServer message")
                            val body = ByteArray(h.bodySize); readFully(inp, body)
                            when (h.type) {
                                Sdr.SpyServer.MSG_DEVICE_INFO -> Sdr.SpyServer.deviceInfo(body)?.let { d ->
                                    SdrControl.set { it.copy(connected = true, device = d.name, gainMin = 0, gainMax = d.maxGainIndex, status = "Connected to SpyServer $host:$port") }
                                }
                                Sdr.SpyServer.MSG_CLIENT_SYNC -> Sdr.SpyServer.sync(body)?.let { y ->
                                    SdrControl.set {
                                        it.copy(connected = true, canControl = y.canControl, gain = y.gain, freqHz = y.iqCenterHz,
                                            status = if (y.canControl) "Connected to SpyServer $host:$port" else "SpyServer: another client has the control (tuning is not allowed)")
                                    }
                                }
                                else -> {}                                       // spectrum: only keeps the device running
                            }
                        }
                        writer.interrupt()
                    }
                } catch (e: Exception) {
                    if (!running) break
                    SdrControl.set { it.copy(connected = false, status = "No connection with SpyServer $host:$port (${e.message}); retrying") }
                    try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }
}
