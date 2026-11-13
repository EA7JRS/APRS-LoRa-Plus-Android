/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import es.ea7jrs.aprslorap.core.Spots
import es.ea7jrs.aprslorap.core.Wsjt
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Sends what the app decodes to the networks and to other programs (each one switched on in Modes > Spotting):
 *  - PSK Reporter: FT8, FT4 and CW (UDP, a datagram every ~5 minutes).
 *  - WSPRnet: WSPR and FST4W (HTTP, after each slot).
 *  - WSJT-X UDP (GridTracker, JTAlert, N1MM, RBN Aggregator for FT8 / FT4 to the RBN): every decode, as WSJT-X sends it.
 *  - CW Skimmer style telnet server (RBN Aggregator for CW to the RBN, loggers): one "DX de" line per CW station.
 * Sources: the app's decoders (RTL-SDR receiver or microphone) and the tracker's CW listening. PSK Reporter and WSPRnet
 * carry our callsign to public sites: only with the callsign validated.
 */
object Spotter {
    data class State(val psk: String = "Off", val wsprnet: String = "Off", val udp: String = "Off", val telnet: String = "Off",
                     val locator: String = "", val lastCw: String = "")

    private var app: Context? = null
    private var settings: Settings? = null
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "spotter").apply { isDaemon = true } }
    private val http = Executors.newSingleThreadExecutor { Thread(it, "spotter-http").apply { isDaemon = true } }
    private var udp: DatagramSocket? = null
    private var pskQueue: Spots.PskQueue? = null
    private var pskSent = 0; private var wsprSent = 0; private var udpSent = 0; private var cwSpots = 0
    private var phoneCw: Spots.CwSpotter? = null
    private var trackerCw: Spots.CwSpotter? = null
    @Volatile private var trackerCwHz: Long? = null
    @Volatile private var trackerCwSnr = 0; @Volatile private var trackerCwWpm = 0
    private var telnet: ServerSocket? = null
    private val clients = java.util.Collections.synchronizedList(ArrayList<OutputStream>())
    private var started = false
    const val UDP_ID = "APRS-LoRa+"

    private fun set(f: (State) -> State) { synchronized(this) { AppState.spotter.value = f(AppState.spotter.value) } }

    @Synchronized
    fun init(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        BeaconHub.init(ctx)
        settings = Settings(ctx)
        exec.scheduleWithFixedDelay({ heartbeat() }, 2, Spots.WsjtxUdp.HEARTBEAT_S.toLong(), TimeUnit.SECONDS)
        schedulePsk()
        exec.execute { applySettings() }
    }

    /** After a change in Modes > Spotting. */
    fun reconfigure() { exec.execute { applySettings() } }

    private fun version(): String = try {
        val c = app!!; c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun software() = "APRS LoRa+ Android ${version()}"

    private fun validated(): Boolean { val s = settings ?: return false; return Licence.isValid(s) }

    /** Our locator: the one set by hand, else the tracker's GNSS, else the phone's last position. */
    fun locator(): String {
        val s = settings ?: return ""
        if (s.spotLocator.length >= 4 && Spots.isGrid(s.spotLocator)) return s.spotLocator
        AppState.gnss.value?.let { g -> if (g.fix && g.lat != null && g.lon != null) return Spots.locator(g.lat!!, g.lon!!) }
        val c = app ?: return ""
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .filter { !it.isFromMockProvider }.maxByOrNull { it.time }
            if (l != null) Spots.locator(l.latitude, l.longitude) else ""
        } catch (_: SecurityException) { "" }
    }

    /** Frequency the tracker was last listening to CW on (event "cwrx"), or null. */
    fun trackerCwFreqHz(): Long? = trackerCwHz

    /** Dial frequency of the radio when the audio comes from the microphone: the CAT's if a radio answers, else the one set by hand. */
    fun micDialHz(): Long? = Rig.state?.freqHz?.takeIf { it > 0 } ?: settings?.spotDialHz?.takeIf { it > 0 }

    private fun applySettings() {
        val s = settings ?: return
        val loc = locator()
        val ok = validated()
        set {
            it.copy(locator = loc,
                psk = when { !s.spotPsk -> "Off"; !ok -> "Needs the callsign validated"; loc.isEmpty() -> "Needs a locator"; else -> "On, $pskSent spots sent" },
                wsprnet = when { !s.spotWsprnet -> "Off"; !ok -> "Needs the callsign validated"; loc.isEmpty() -> "Needs a locator"; else -> "On, $wsprSent spots sent" },
                udp = if (!s.spotUdp) "Off" else if (s.spotUdpHost.isBlank()) "Needs the address of the computer" else "On, to ${s.spotUdpHost}:${s.spotUdpPort}",
            )
        }
        val wantTelnet = s.spotTelnet
        if (wantTelnet && (telnet == null || telnet!!.localPort != s.spotTelnetPort)) startTelnet(s.spotTelnetPort)
        if (!wantTelnet && telnet != null) stopTelnet()
        if (!wantTelnet) set { it.copy(telnet = "Off") }
        if (phoneCw == null) phoneCw = Spots.CwSpotter(s.callsign)
        if (trackerCw == null) trackerCw = Spots.CwSpotter(s.callsign)
    }

    // ------------------------------------------------------------------ hooks

    /** After each FT8 / FT4 / WSPR / FST4W slot ([dialHz] null: unknown, nothing goes out that needs it). */
    fun onWsjtSlot(mode: Wsjt.Mode, slotMs: Long, decodes: List<Wsjt.Decode>, dialHz: Long?) {
        for (d in decodes) es.ea7jrs.aprslorap.core.Beacons.fromWsjt(d, mode, dialHz)?.let { BeaconHub.add(it) }
        if (!started) return
        exec.execute {
            val s = settings ?: return@execute
            if (s.spotUdp && s.spotUdpHost.isNotBlank()) {
                val loc = locator()
                sendUdp(Spots.WsjtxUdp.status(UDP_ID, dialHz ?: 0L, mode, Spots.stationCall(s.callsign), loc.take(4), false, AppState.wsjt.value.rxHz))
                for (d in decodes) Spots.WsjtxUdp.forDecode(UDP_ID, d, mode, dialHz)?.let { sendUdp(it) }
            }
            if (dialHz == null || dialHz <= 0 || !validated()) return@execute
            val loc = locator()
            if (loc.isEmpty()) return@execute
            if (mode.wsprLike) {
                if (!s.spotWsprnet) return@execute
                val bodies = decodes.mapNotNull { Spots.WsprNet.spot(it, mode, s.callsign, loc, dialHz, "ALP ${version()}") }
                http.execute { postWsprnet(if (bodies.isEmpty()) listOf(Spots.WsprNet.status(mode, s.callsign, loc, dialHz, "ALP ${version()}")) else bodies, bodies.size) }
            } else if (s.spotPsk) {
                val q = queue(s, loc)
                for (d in decodes) Spots.fromWsjt(d, mode, dialHz, s.callsign, loc)?.let { q.add(it, System.currentTimeMillis()) }
            }
        }
    }

    /** Text of the phone's CW decoder ([centerHz]: audio tone). */
    fun onPhoneCw(text: String, centerHz: Double, snrDb: Double, wpm: Int, src: ModemRunner.Source) {
        if (!started) return
        val now = System.currentTimeMillis()
        exec.execute {
            val sp = phoneCw ?: return@execute
            val hits = sp.feed(text, now)
            if (hits.isEmpty()) return@execute
            val rf = if (src == ModemRunner.Source.RECEIVER) {
                val st = AppState.sdr.value
                st.freqHz?.let { Spots.rfHz(it, st.mode, centerHz) }
            } else micDialHz()?.let { Spots.rfHz(it, "USB", centerHz) }
            for (h in hits) cwSpot(h, rf, snrDb.toInt(), wpm, now, "phone")
        }
    }

    /** One event "cwrx" of the tracker. */
    fun onTrackerCw(on: Boolean, letter: String?, snr: Double?, wpm: Int?, freqHz: Long?) {
        if (!started) return
        if (freqHz != null && freqHz > 0) trackerCwHz = freqHz
        if (snr != null && letter == null) trackerCwSnr = snr.toInt()
        if (wpm != null && wpm > 0) trackerCwWpm = wpm
        if (!on) { exec.execute { trackerCw?.reset() }; return }
        val t = letter ?: return
        val now = System.currentTimeMillis()
        exec.execute {
            val hits = trackerCw?.feed(t, now) ?: return@execute
            for (h in hits) cwSpot(h, trackerCwHz, trackerCwSnr, trackerCwWpm, now, "tracker")
        }
    }

    private fun cwSpot(h: Spots.CwHit, rfHz: Long?, snr: Int, wpm: Int, now: Long, from: String) {
        val s = settings ?: return
        cwSpots++
        val f = rfHz?.let { String.format(java.util.Locale.US, "%.1f kHz", it / 1000.0) } ?: "frequency unknown"
        set { it.copy(lastCw = "${h.call} (${h.comment}) $f, $snr dB, $wpm wpm, from the $from · $cwSpots CW spots") }
        if (rfHz == null || rfHz <= 0) return
        BeaconHub.add(es.ea7jrs.aprslorap.core.Beacons.fromCw(h.call, h.comment, "", rfHz, snr, now))
        if (telnet != null) broadcast(Spots.skimmerLine(s.callsign, rfHz, h.call, snr, wpm, h.comment, now))
        if (s.spotPsk && validated()) {
            val loc = locator()
            if (loc.isNotEmpty()) queue(s, loc).add(Spots.Spot(h.call, "", rfHz, "CW", snr, now), now)
        }
    }

    // ------------------------------------------------------------------ PSK Reporter

    private fun queue(s: Settings, loc: String): Spots.PskQueue {
        val rx = Spots.Receiver(Spots.stationCall(s.callsign), loc, software(), s.spotAntenna)
        val q = pskQueue
        if (q != null && q.rx == rx) return q
        if (q != null) sendPsk(q)                                     // what was waiting goes with the old receiver data
        return Spots.PskQueue(rx).also { pskQueue = it }
    }

    private fun schedulePsk() {
        val delay = 300L + (Math.random() * 30).toLong()               // PSK Reporter: not more often than 5 min, not on the clock
        exec.schedule({ try { pskQueue?.let { sendPsk(it) } } finally { schedulePsk() } }, delay, TimeUnit.SECONDS)
    }

    private fun sendPsk(q: Spots.PskQueue) {
        val s = settings ?: return
        if (!s.spotPsk || !validated()) return
        val pending = q.size
        val packets = q.take(System.currentTimeMillis())
        if (packets.isEmpty()) return
        try {
            val addr = InetAddress.getByName(Spots.Ipfix.HOST)
            DatagramSocket().use { sock -> for (p in packets) sock.send(DatagramPacket(p, p.size, addr, Spots.Ipfix.PORT)) }
            pskSent += pending
            set { it.copy(psk = "On, $pskSent spots sent") }
        } catch (e: Exception) {
            set { it.copy(psk = "Error: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ WSPRnet

    private fun postWsprnet(bodies: List<String>, spots: Int) {
        var added = 0
        for (b in bodies) {
            try {
                val c = URL(Spots.WsprNet.URL).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(b.toByteArray()) }
                val reply = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                if (Spots.WsprNet.accepted(reply)) added++
            } catch (e: Exception) {
                set { it.copy(wsprnet = "Error: ${e.message}") }
                return
            }
        }
        wsprSent += added
        set { it.copy(wsprnet = if (spots == 0) "On, $wsprSent spots sent (last slot: nothing heard)" else "On, $wsprSent spots sent (last slot: $added of $spots taken)") }
    }

    // ------------------------------------------------------------------ WSJT-X UDP

    private fun heartbeat() {
        val s = settings ?: return
        if (!s.spotUdp || s.spotUdpHost.isBlank()) return
        sendUdp(Spots.WsjtxUdp.heartbeat(UDP_ID, version()))
    }

    private fun sendUdp(b: ByteArray) {
        val s = settings ?: return
        try {
            val sock = udp ?: DatagramSocket().also { udp = it }
            sock.send(DatagramPacket(b, b.size, InetAddress.getByName(s.spotUdpHost.trim()), s.spotUdpPort))
            udpSent++
            set { it.copy(udp = "On, to ${s.spotUdpHost}:${s.spotUdpPort} ($udpSent messages)") }
        } catch (e: Exception) {
            set { it.copy(udp = "Error: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ telnet (CW Skimmer style)

    private fun startTelnet(port: Int) {
        stopTelnet()
        try {
            val ss = ServerSocket(port)
            telnet = ss
            set { it.copy(telnet = "Listening on port $port") }
            Thread({
                while (!ss.isClosed) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    if (clients.size >= 5) { try { sock.close() } catch (_: Exception) {}; continue }
                    Thread({ client(sock) }, "skimmer-client").apply { isDaemon = true; start() }
                }
            }, "skimmer-server").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            telnet = null
            set { it.copy(telnet = "Port $port: ${e.message}") }
        }
    }

    private fun stopTelnet() {
        try { telnet?.close() } catch (_: Exception) {}
        telnet = null
        synchronized(clients) { clients.forEach { try { it.close() } catch (_: Exception) {} }; clients.clear() }
    }

    /** As CW Skimmer: asks for the callsign, then sends the spots. */
    private fun client(sock: Socket) {
        val out = sock.getOutputStream()
        try {
            sock.soTimeout = 60_000
            out.write("Please enter your callsign: ".toByteArray()); out.flush()
            val call = try { BufferedReader(InputStreamReader(sock.getInputStream())).readLine()?.trim() ?: "" } catch (_: Exception) { "" }
            out.write("\r\nHello $call, this is the CW skimmer of APRS LoRa+ (${Spots.stationCall(settings?.callsign ?: "")})\r\n\r\n".toByteArray()); out.flush()
            sock.soTimeout = 0
            clients.add(out)
            set { it.copy(telnet = "Port ${telnet?.localPort}: ${clients.size} connected") }
            val input = sock.getInputStream()
            while (input.read() >= 0) { /* ignore commands */ }
        } catch (_: Exception) {
        } finally {
            clients.remove(out)
            try { sock.close() } catch (_: Exception) {}
            set { it.copy(telnet = if (telnet != null) "Port ${telnet?.localPort}: ${clients.size} connected" else "Off") }
        }
    }

    private fun broadcast(line: String) {
        val b = (line + "\r\n").toByteArray()
        synchronized(clients) {
            val dead = ArrayList<OutputStream>()
            for (o in clients) try { o.write(b); o.flush() } catch (_: Exception) { dead.add(o) }
            clients.removeAll(dead)
        }
    }
}
