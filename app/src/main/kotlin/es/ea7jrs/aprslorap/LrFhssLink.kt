/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.net.Uri
import es.ea7jrs.aprslorap.core.Beacons
import es.ea7jrs.aprslorap.core.DxCluster
import es.ea7jrs.aprslorap.core.IqWav
import es.ea7jrs.aprslorap.core.LrFhss
import es.ea7jrs.aprslorap.core.Sdr
import es.ea7jrs.aprslorap.core.Spots
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * The stations the app's own receivers hear in the beacon modes (LR-FHSS here; WSPR, FST4W, FT8, FT4 and CW from Modes):
 * one list ([AppState.beacons]) for the Beacons tool and the map, and the spots to the DX cluster (by hand, or by
 * themselves if the user turns that on: validated callsign, cluster connected, never our own callsign, each station once
 * per mode and band every 30 minutes and one spot every 20 s at most).
 */
object BeaconHub {
    private val log = Beacons.Log()
    @Volatile private var app: Context? = null
    @Volatile private var lastAuto = 0L

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    fun add(h: Beacons.Heard) {
        val m = log.add(h)
        AppState.beacons.value = log.list()
        val ctx = app ?: return
        val s = Settings(ctx)
        val auto = s.beaconAutoSpot
        if (auto == "off" || (auto == "lrfhss" && !Beacons.isTrackerMode(m.mode))) return
        val now = System.currentTimeMillis()
        if (now - lastAuto < 20_000L || !log.due(m, now)) return
        if (!Licence.isValid(s) || !DxLink.state.value.connected) return
        if (Spots.stationCall(m.call) == Spots.stationCall(s.callsign)) return
        if (DxLink.send(Beacons.spotCommand(m))) { lastAuto = now; log.spotted(m.key, now); AppState.beacons.value = log.list() }
    }

    /** SPOT key: announces [h] to the cluster now. Returns what happened, for the screen. */
    fun spot(ctx: Context, h: Beacons.Heard): String {
        val s = Settings(ctx)
        if (!Licence.isValid(s)) return "Spots need a validated callsign"
        if (!DxLink.state.value.connected) return "Not connected to the DX cluster (open DX and connect)"
        if (!DxLink.send(Beacons.spotCommand(h))) return "The cluster did not take the spot"
        log.spotted(h.key, System.currentTimeMillis()); AppState.beacons.value = log.list()
        return "Sent: " + Beacons.spotCommand(h)
    }

    fun clear() { log.clear(); AppState.beacons.value = emptyList() }
}

/**
 * LR-FHSS receiver of the app: the propagation beacon of the trackers ("PB1 <callsign> <locator> <dBm>", SX1262 through
 * RadioLib). I / Q from an RTL-SDR through rtl_tcp (the dongle on this phone with the "RTL-SDR driver" app, or an rtl_tcp
 * on the network) at 1 MS/s, tuned 250 kHz below so that the DC spike stays out of the band, or from an I / Q recording
 * (WAV of two channels: SDRconnect, SDR++, SDR#...). The decoding is [LrFhss.Receiver].
 */
object LrFhssLink {
    const val RTL_RATE = 1_000_000
    const val RTL_SHIFT = 250_000

    data class Rx(val timeMs: Long, val text: String, val info: String, val beacon: Boolean)
    data class State(val running: Boolean = false, val status: String = "Off", val freqHz: Long = 0, val headers: Int = 0, val errors: Int = 0,
        val packets: List<Rx> = emptyList(), val progress: Int = -1)

    val state = MutableStateFlow(State())
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var thread: Thread? = null
    @Volatile var usesRtl = false; private set

    private fun set(f: (State) -> State) { synchronized(this) { state.value = f(state.value) } }

    fun stop() {
        val was = running
        running = false
        try { sock?.close() } catch (_: Exception) {}
        thread?.let { t -> if (t !== Thread.currentThread()) try { t.join(800) } catch (_: Exception) {} }
        thread = null; usesRtl = false
        if (was) set { it.copy(running = false, status = "Off", progress = -1) }
    }

    private fun onPacket(p: LrFhss.Packet, freqHz: Long, live: Boolean) {
        val now = System.currentTimeMillis()
        val rf = freqHz + Math.round(p.centreHz)
        val h = p.header
        val info = String.format(Locale.US, "%.4f MHz · %+.0f Hz · %.0f kHz · CR %s · %d bytes · quality %.0f dB", rf / 1e6, p.centreHz, h.bwKhz, LrFhss.CR_NAMES[h.cr], h.payloadLen, p.snrDb) +
            (if (p.fragmentsLost > 0) " · ${p.fragmentsLost} fragments lost" else "")
        val b = p.beacon
        set { it.copy(packets = (listOf(Rx(now, p.text, info, b != null)) + it.packets).take(50)) }
        if (b != null) BeaconHub.add(Beacons.fromLrFhss(b, freqHz, Math.round(p.snrDb).toInt(), now, p.text))
    }

    /** rtl_tcp at [host]:[port] (127.0.0.1 = the driver app on this phone, already started with [RTL_RATE] and the frequency minus [RTL_SHIFT]). */
    fun startRtl(ctx: Context, host: String, port: Int, ppm: Int, gainPercent: Int, freqHz: Long) {
        stop()
        SdrControl.disconnect()                                          // rtl_tcp takes one client only
        if (AdsbLink.usesRtl) AdsbLink.stop()
        BeaconHub.init(ctx)
        running = true; usesRtl = true
        val close = host == "127.0.0.1" || host == "localhost"
        set { State(running = true, status = "Connecting to the RTL-SDR ($host:$port)...", freqHz = freqHz, packets = it.packets) }
        thread = Thread({ rtlLoop(host, port, ppm, gainPercent, freqHz, close) }, "lrfhss-rtl").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
    }

    private fun rtlLoop(host: String, port: Int, ppm: Int, gainPercent: Int, freqHz: Long, closeDriver: Boolean) {
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
                    out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_SAMPLE_RATE, RTL_RATE))
                    if (ppm != 0) out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ_CORRECTION, ppm))
                    out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ, (freqHz - RTL_SHIFT).toInt()))
                    if (gainPercent < 0) { out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_GAIN_MODE, 0)); out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_AGC_MODE, 1)) }
                    else {
                        out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_AGC_MODE, 0)); out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_GAIN_MODE, 1))
                        out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.ANDROID_GAIN_PERCENT, gainPercent.coerceIn(0, 100)))
                    }
                    out.flush()
                    set { it.copy(status = String.format(Locale.US, "Listening on %.4f MHz (RTL-SDR, tuner %s)", freqHz / 1e6, g.tuner)) }
                    val rx = LrFhss.Receiver { p -> onPacket(p, freqHz, true) }
                    val rs = LrFhss.Resampler(RTL_RATE.toDouble(), RTL_SHIFT.toDouble()) { i, q -> rx.feed(i, q) }
                    val buf = ByteArray(1 shl 16)
                    var have = 0
                    var last = 0L
                    while (running) {
                        val n = inp.read(buf, have, buf.size - have)
                        if (n < 0) throw java.io.EOFException("rtl_tcp closed")
                        have += n
                        val even = have and 1.inv()
                        if (even >= 4096) {
                            rs.feedU8(buf, even)
                            if (have > even) buf[0] = buf[even]
                            have -= even
                        }
                        val now = System.currentTimeMillis()
                        if (now - last > 1000) { last = now; set { it.copy(headers = rx.headersSeen, errors = rx.payloadErrors) } }
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

    /**
     * Decodes an I / Q recording. [freqHz]: the frequency of the beacon; if the file says where it was centred, the
     * difference is taken into account, else the recording is taken as centred on the beacon.
     */
    fun decodeFile(ctx: Context, uri: Uri, freqHz: Long) {
        stop()
        BeaconHub.init(ctx)
        running = true
        val app = ctx.applicationContext
        set { State(running = true, status = "Reading the recording...", freqHz = freqHz, packets = it.packets, progress = 0) }
        thread = Thread({
            var found = 0
            var result = ""
            try {
                val total = try { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
                val inp = BufferedInputStream(app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open the file"), 1 shl 16)
                inp.use {
                    val head = ByteArray(8192)
                    var n = 0
                    while (n < head.size) { val r = inp.read(head, n, head.size - n); if (r < 0) break; n += r }
                    val info = IqWav.parse(head, n) ?: throw java.io.IOException("not an I / Q WAV file (two channels, 8 / 16 / 24 / 32 bits or float)")
                    if (info.sampleRate < 48_000) throw java.io.IOException("${info.sampleRate} S/s is too little: the beacon needs 62.5 kS/s or more")
                    val name = try { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } catch (_: Exception) { null }
                    val centre = info.centreHz ?: name?.let { IqWav.centreFromName(it) }     // SDRconnect writes it in the name of the file
                    val (shift, base) = IqWav.plan(info.sampleRate, centre, freqHz)
                    val rx = LrFhss.Receiver { p -> found++; onPacket(p, base, false) }
                    val rs = LrFhss.Resampler(info.sampleRate.toDouble(), shift) { i, q -> rx.feed(i, q) }
                    val fb = info.frameBytes
                    val buf = ByteArray(fb * 8192)
                    val fl = FloatArray(8192 * 2)
                    var have = n - info.dataOffset
                    if (have < 0) throw java.io.IOException("WAV header too long")
                    System.arraycopy(head, info.dataOffset, buf, 0, have)
                    var done = 0L
                    while (running) {
                        val frames = have / fb
                        if (frames > 0) {
                            IqWav.toFloat(info, buf, 0, frames, fl)
                            rs.feed(fl, frames)
                            done += frames.toLong() * fb
                            val rest = have - frames * fb
                            if (rest > 0) System.arraycopy(buf, frames * fb, buf, 0, rest)
                            have = rest
                            if (total > 0) set { it.copy(progress = (done * 100 / total).toInt().coerceIn(0, 100), headers = rx.headersSeen, errors = rx.payloadErrors) }
                        }
                        val r = inp.read(buf, have, buf.size - have)
                        if (r < 0) break
                        have += r
                    }
                    repeat(LrFhss.SAMPLE_RATE) { rx.feed(0f, 0f) }                 // lets the last packet finish
                    result = String.format(Locale.US, "Recording done: %d packets, %d headers, %d with errors · %d S/s%s", found, rx.headersSeen, rx.payloadErrors, info.sampleRate,
                        if (centre != null) String.format(Locale.US, ", centred on %.4f MHz", centre / 1e6) else "")
                    set { it.copy(headers = rx.headersSeen, errors = rx.payloadErrors) }
                }
            } catch (e: Exception) { result = "Recording: " + (e.message ?: e.javaClass.simpleName) }
            running = false
            set { it.copy(running = false, status = result, progress = -1) }
        }, "lrfhss-file").apply { isDaemon = true; start() }
    }
}
