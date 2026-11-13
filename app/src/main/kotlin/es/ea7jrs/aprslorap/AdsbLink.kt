/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Adsb
import es.ea7jrs.aprslorap.core.Sdr
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Aircraft received by the app itself (ADS-B / Mode S, 1090 MHz), nothing from the internet. Two sources: an RTL-SDR
 * through rtl_tcp at 2 MS/s (the dongle on this phone with the "RTL-SDR driver" app, or an rtl_tcp on the network), which
 * the app demodulates ([Adsb.Demod]); or a dump1090 / readsb on the network (Beast, raw or BaseStation port). The
 * aircraft go to [AppState.aircraft] once a second: the Aircraft tool and the aircraft layer of the map.
 */
object AdsbLink {
    data class State(val running: Boolean = false, val status: String = "Off", val format: String = "", val frames: Long = 0, val positions: Long = 0,
        val candidates: Long = 0, val rx: Pair<Double, Double>? = null)

    val state = MutableStateFlow(State())
    private val table = Adsb.Table()
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var closeDriver = false

    /** The RTL-SDR is busy with ADS-B (the SDR tab cannot use it meanwhile). */
    @Volatile var usesRtl = false; private set

    private fun set(f: (State) -> State) { state.value = f(state.value) }

    fun stop() {
        val was = running
        running = false
        try { sock?.close() } catch (_: Exception) {}
        thread?.let { t -> if (t !== Thread.currentThread()) try { t.join(600) } catch (_: Exception) {} }
        thread = null; usesRtl = false
        if (was) set { State(status = "Off") }
        AppState.aircraft.value = emptyList()
        table.clear()
    }

    /** rtl_tcp at [host]:[port] (127.0.0.1 = the driver app on this phone, already started). */
    fun startRtl(ctx: Context, host: String, port: Int, ppm: Int, gainPercent: Int) {
        stop()
        SdrControl.disconnect()                                          // rtl_tcp takes one client only
        if (LrFhssLink.usesRtl) LrFhssLink.stop()
        running = true; usesRtl = true
        closeDriver = host == "127.0.0.1" || host == "localhost"
        set { State(running = true, status = "Connecting to the RTL-SDR ($host:$port)...") }
        thread = Thread({ rtlLoop(ctx.applicationContext, host, port, ppm, gainPercent) }, "adsb-rtl").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
    }

    /** A dump1090 / readsb on the network: port 30005 (Beast), 30002 (raw) or 30003 (BaseStation). */
    fun startNet(ctx: Context, host: String, port: Int) {
        stop()
        running = true
        set { State(running = true, status = "Connecting to $host:$port...") }
        thread = Thread({ netLoop(ctx.applicationContext, host, port) }, "adsb-net").apply { isDaemon = true; start() }
    }

    private class Ticker(val ctx: Context) {
        var last = 0L; var lastPos = 0L
        var rx: Pair<Double, Double>? = null
        fun tick(extra: (State) -> State = { it }) {
            val now = System.currentTimeMillis()
            if (now - lastPos > 30_000L) { lastPos = now; rx = NetWeatherFetcher.position(ctx) }
            if (now - last < 1000L) return
            last = now
            AppState.aircraft.value = table.list(now)
            set { extra(it.copy(frames = table.frames, positions = table.positions, rx = rx)) }
        }
    }

    private fun rtlLoop(ctx: Context, host: String, port: Int, ppm: Int, gainPercent: Int) {
        val tk = Ticker(ctx)
        while (running) {
            try {
                Socket().use { s ->
                    sock = s
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.soTimeout = 5000
                    s.receiveBufferSize = 1 shl 19
                    val inp = BufferedInputStream(s.getInputStream(), 1 shl 17)
                    val out = s.getOutputStream()
                    val hello = ByteArray(12)
                    var got = 0
                    while (got < 12) { val n = inp.read(hello, got, 12 - got); if (n < 0) throw java.io.EOFException(); got += n }
                    val g = Sdr.RtlTcp.greeting(hello) ?: throw java.io.IOException("not an rtl_tcp server")
                    out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_SAMPLE_RATE, Adsb.SAMPLE_RATE))
                    if (ppm != 0) out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ_CORRECTION, ppm))
                    out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ, Adsb.FREQ_HZ.toInt()))
                    if (gainPercent < 0) { out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_GAIN_MODE, 0)); out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_AGC_MODE, 1)) }
                    else {
                        out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_AGC_MODE, 0)); out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_GAIN_MODE, 1))
                        out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.ANDROID_GAIN_PERCENT, gainPercent.coerceIn(0, 100)))
                    }
                    out.flush()
                    set { it.copy(status = "Receiving 1090 MHz (RTL-SDR, tuner ${g.tuner})", format = "RTL-SDR 2 MS/s") }
                    val demod = Adsb.Demod { f -> table.onFrame(f, System.currentTimeMillis(), tk.rx?.first, tk.rx?.second) }
                    val buf = ByteArray(1 shl 16)
                    var have = 0
                    while (running) {
                        val n = inp.read(buf, have, buf.size - have)
                        if (n < 0) throw java.io.EOFException("rtl_tcp closed")
                        have += n
                        val even = have and 1.inv()
                        if (even >= 4096) {
                            demod.process(buf, even)
                            if (have > even) buf[0] = buf[even]
                            have -= even
                        }
                        tk.tick { it.copy(candidates = demod.candidates) }
                    }
                    if (closeDriver) try { out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.ANDROID_EXIT, 0)); out.flush() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                if (!running) break
                set { it.copy(status = "RTL-SDR: ${e.message ?: e.javaClass.simpleName}; retrying") }
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun netLoop(ctx: Context, host: String, port: Int) {
        val tk = Ticker(ctx)
        while (running) {
            try {
                Socket().use { s ->
                    sock = s
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.soTimeout = 1000
                    val inp = s.getInputStream()
                    val parser = Adsb.NetParser({ f -> table.onFrame(f, System.currentTimeMillis(), tk.rx?.first, tk.rx?.second) }, { l -> table.onSbs(l, System.currentTimeMillis()) })
                    set { it.copy(status = "Connected to $host:$port") }
                    val buf = ByteArray(8192)
                    var quiet = 0
                    while (running) {
                        val n = try { inp.read(buf) } catch (_: java.net.SocketTimeoutException) { 0 }
                        if (n < 0) throw java.io.EOFException("closed by the receiver")
                        if (n > 0) { parser.feed(buf, n); quiet = 0 } else if (++quiet == 60) set { it.copy(status = "Connected to $host:$port, but nothing arrives") }
                        tk.tick { it.copy(format = parser.format) }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                set { it.copy(status = "No connection with $host:$port (${e.message ?: e.javaClass.simpleName}); retrying") }
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
            }
        }
    }
}
