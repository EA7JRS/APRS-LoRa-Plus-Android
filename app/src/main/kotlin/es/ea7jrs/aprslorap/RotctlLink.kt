/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.RotCtl
import es.ea7jrs.aprslorap.core.RotorPointing
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone and Hamlib rotctld:
 * - client: the pointing the tracker computes (event `rot`, with the offsets, the vehicle heading and the mount tilt) goes to a
 *   rotctld on the network (rotor controller, Raspberry Pi with rotctld, PstRotator...);
 * - server (port 4533): Look4Sat, GPredict... on the phone or on the same network point the tracker's rotor (`rotext`).
 * Started by the service and again when the settings change ([apply]).
 */
object RotctlLink {
    private var client: Client? = null
    private var server: Server? = null

    @Synchronized
    fun apply(s: Settings) {
        val wantClient = s.rotClient && s.rotClientHost.isNotBlank()
        if (client?.matches(s) != true) { client?.stop(); client = null }
        if (wantClient && client == null) client = Client(s.rotClientHost.trim(), s.rotClientPort, s.rotClientStep).also { it.start() }
        if (!wantClient) AppState.rotClientStatus.value = "Off"
        if (server?.port != s.rotServerPort || !s.rotServer) { server?.stop(); server = null }
        if (s.rotServer && server == null) server = Server(s.rotServerPort).also { it.start() }
        if (!s.rotServer) AppState.rotServerStatus.value = "Off"
    }

    @Synchronized
    fun stopAll() {
        client?.stop(); client = null
        server?.stop(); server = null
        AppState.rotClientStatus.value = "Off"; AppState.rotServerStatus.value = "Off"
    }

    /** New pointing from the tracker (any thread). */
    fun onPointing(p: RotorPointing) { client?.target(p.azDeg, p.elDeg) }

    // ------------------------------------------------------------------ client

    private class Client(val host: String, val port: Int, val step: Double) {
        @Volatile private var running = false
        @Volatile private var wantAz: Double? = null
        @Volatile private var wantEl: Double? = null
        private val lock = Object()
        private var thread: Thread? = null

        fun matches(s: Settings) = s.rotClient && host == s.rotClientHost.trim() && port == s.rotClientPort && step == s.rotClientStep

        fun target(az: Double, el: Double) { synchronized(lock) { wantAz = az; wantEl = el; lock.notifyAll() } }

        fun start() { running = true; thread = Thread({ loop() }, "rotctl-client").also { it.isDaemon = true; it.start() } }

        fun stop() { running = false; synchronized(lock) { lock.notifyAll() }; thread?.interrupt() }

        private fun loop() {
            while (running) {
                try {
                    AppState.rotClientStatus.value = "Connecting to $host:$port..."
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, port), 5000)
                        sock.soTimeout = 3000
                        sock.tcpNoDelay = true
                        val out = sock.getOutputStream()
                        val inp = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                        var sentAz: Double? = null; var sentEl: Double? = null
                        var lastSendMs = 0L; var lastReadMs = 0L
                        AppState.rotClientStatus.value = "Connected to $host:$port"
                        while (running) {
                            val az: Double?; val el: Double?
                            synchronized(lock) { lock.wait(1000); az = wantAz; el = wantEl }
                            val now = System.currentTimeMillis()
                            if (az != null && el != null && now - lastSendMs >= 1000 && RotCtl.moved(sentAz, sentEl, az, el, step)) {
                                out.write(RotCtl.setPosLine(az, el).toByteArray(Charsets.US_ASCII)); out.flush()
                                val r = RotCtl.parseReply(inp.readLine() ?: throw java.io.IOException("closed"))
                                sentAz = az; sentEl = el; lastSendMs = now
                                AppState.rotClientStatus.value = String.format(java.util.Locale.US, "Connected: sent %.1f° / %.1f°%s", az, el, if (r != null && r != 0) " (rotor answered error $r)" else "")
                            }
                            if (now - lastReadMs >= 5000) {
                                lastReadMs = now
                                out.write("p\n".toByteArray()); out.flush()
                                val a = inp.readLine()?.trim()?.toDoubleOrNull()
                                val e = inp.readLine()?.trim()?.toDoubleOrNull()
                                if (a != null && e != null) AppState.rotClientActual.value = a to e
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!running) break
                    AppState.rotClientStatus.value = "No connection with $host:$port (${e.message}); retrying"
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
            AppState.rotClientStatus.value = "Off"
        }
    }

    // ------------------------------------------------------------------ server

    private class Server(val port: Int) {
        @Volatile private var running = false
        @Volatile private var socket: ServerSocket? = null
        private val clients = CopyOnWriteArrayList<Socket>()
        @Volatile private var lastAz = 0.0
        @Volatile private var lastEl = 0.0

        private val rot = object : RotCtl.Rot {
            override fun setPos(azDeg: Double, elDeg: Double): Boolean {
                val c = AppState.controller ?: return false
                if (!AppState.controlReady.value || AppState.trackerInfo.value?.hasRotor != true) return false
                lastAz = azDeg; lastEl = elDeg
                c.control("rotext", "az" to Math.round(azDeg * 10) / 10.0, "el" to Math.round(elDeg * 10) / 10.0)
                AppState.rotServerLast.value = azDeg to elDeg
                return true
            }
            override fun getPos(): Pair<Double, Double> {
                val p = AppState.rotor.value
                return when {
                    p?.actualAzDeg != null && p.actualElDeg != null -> p.actualAzDeg to p.actualElDeg
                    else -> lastAz to lastEl
                }
            }
            override fun park(): Boolean {
                val c = AppState.controller ?: return false
                if (!AppState.controlReady.value || AppState.trackerInfo.value?.hasRotor != true) return false
                c.control("rotext", "park" to true)
                return true
            }
            override fun info() = "APRS LoRa+ for Android: rotor of the tracker over Bluetooth"
        }

        fun start() { running = true; Thread({ acceptLoop() }, "rotctl-server").also { it.isDaemon = true; it.start() } }

        private fun status() = run { AppState.rotServerStatus.value = "Port $port, ${clients.size} program(s) connected" }

        private fun acceptLoop() {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                socket = s
                status()
                while (running) {
                    val c = s.accept()
                    if (clients.size >= 3) { c.close(); continue }
                    clients.add(c); status()
                    Thread({ serve(c) }, "rotctl-session").also { it.isDaemon = true; it.start() }
                }
            } catch (e: Exception) {
                if (running) AppState.rotServerStatus.value = "Error on port $port: ${e.message}"
            }
        }

        private fun serve(c: Socket) {
            val session = RotCtl.Session(rot)
            try {
                c.tcpNoDelay = true
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                val buf = ByteArray(256)
                val reply = StringBuilder()
                while (running) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    reply.setLength(0)
                    val close = session.feed(String(buf, 0, n, Charsets.US_ASCII), reply)
                    if (reply.isNotEmpty()) { out.write(reply.toString().toByteArray(Charsets.US_ASCII)); out.flush() }
                    if (close) break
                }
            } catch (_: Exception) {
            } finally {
                try { c.close() } catch (_: Exception) {}
                clients.remove(c)
                if (running) status()
            }
        }

        fun stop() {
            running = false
            try { socket?.close() } catch (_: Exception) {}
            for (c in clients) try { c.close() } catch (_: Exception) {}
            clients.clear()
        }
    }
}
