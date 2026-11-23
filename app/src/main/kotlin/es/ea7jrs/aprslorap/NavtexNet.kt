/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Navtex
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * NAVTEX messages from a receiver on the network: the Navtex program (EA7JRS/Navtex) or any receiver that sends NRX sentences
 * of NMEA 0183. Two ways, as that program offers them: TCP (the phone connects to the receiver's NMEA port; the search and
 * rescue alarm acknowledged here is acknowledged there too, with an ACK sentence) or UDP (the phone listens on a port; one
 * way only). The messages go to [NavtexHub] with the same rules as the ones the phone decodes itself.
 *
 * Only receives: nothing that comes this way is transmitted. The receiver's NMEA output has no password: local network only.
 */
object NavtexNet {
    const val OFF = 0
    const val TCP = 1
    const val UDP = 2

    val status = MutableStateFlow("Off")
    /** The receiver's masks (NRM), by frequency index 1 = 490, 2 = 518, 3 = 4209.5 kHz, as it last answered. TCP only. */
    val masks = MutableStateFlow<Map<Int, Navtex.Mask>>(emptyMap())
    private var link: Link? = null

    @Synchronized
    fun apply(s: Settings) {
        val mode = s.navtexNetMode
        val host = s.navtexNetHost.trim()
        val port = s.navtexNetPort
        val want = when {
            port !in 1..65535 -> OFF
            mode == TCP && host.isNotEmpty() -> TCP
            mode == UDP -> UDP
            else -> OFF
        }
        if (link?.matches(want, host, port) == true) return
        link?.stop(); link = null
        if (want == OFF) { status.value = if (mode == TCP && host.isEmpty()) "Off (no address)" else "Off"; return }
        link = Link(want, host, port).also { it.start() }
    }

    @Synchronized
    fun stop() { link?.stop(); link = null; status.value = "Off"; masks.value = emptyMap() }

    /**
     * The alarm was acknowledged on the phone: the receiver is told (TCP only), with ACN when it speaks bridge alert
     * management (an ALF sentence in the last two minutes), else with ACK.
     */
    fun acknowledge() { link?.let { l -> l.send((if (l.alfRecent()) Navtex.acnSentence(l.alfInstance) else Navtex.ackSentence()) + "\r\n") } }

    /** Asks the receiver for its masks again (TCP only). False when there is no connection. */
    fun askMasks(): Boolean = link?.send(Navtex.nrmQuery() + "\r\n") ?: false

    /** Changes what the receiver stores on one frequency; it answers with what is left (A, B, D and L always). */
    fun setMask(m: Navtex.Mask): Boolean = link?.send(Navtex.nrmSentence(m.copy(function = 1)) + "\r\n") ?: false

    private class Link(val mode: Int, val host: String, val port: Int) {
        @Volatile private var running = false
        @Volatile private var socket: Socket? = null
        @Volatile private var out: OutputStream? = null
        @Volatile private var udp: DatagramSocket? = null
        private val asm = Navtex.NrxAssembler()
        private var count = 0

        fun matches(m: Int, h: String, p: Int) = running && m == mode && p == port && (mode == UDP || h == host)

        fun start() {
            running = true
            Thread({ if (mode == TCP) tcpLoop() else udpLoop() }, "navtex-net").also { it.isDaemon = true; it.start() }
        }

        fun stop() {
            running = false
            try { socket?.close() } catch (_: Exception) {}
            try { udp?.close() } catch (_: Exception) {}
        }

        @Volatile private var alfMs = 0L
        @Volatile var alfInstance = 1
            private set
        fun alfRecent() = System.currentTimeMillis() - alfMs < 120_000L

        fun send(text: String): Boolean {
            val o = out ?: return false
            Thread({ try { synchronized(o) { o.write(text.toByteArray(Charsets.US_ASCII)); o.flush() } } catch (_: Exception) {} }, "navtex-out").start()
            return true
        }

        private fun onLine(line: String) {
            if (line.length < 8 || line.length > 200) return
            val alr = Navtex.parseAlr(line)
            if (alr != null) {
                // acknowledged on the receiver (or on other equipment): here too, but not in the first moments of a new alarm
                if (!alr.active || alr.acknowledged) NavtexHub.acknowledgeRemote(olderThanMs = 35_000L, tellReceiver = false)
                return
            }
            val alf = Navtex.parseAlf(line)
            if (alf != null) {                                          // bridge alert management: the same news as ALR
                alfMs = System.currentTimeMillis(); alfInstance = alf.instance
                if (!alf.unacknowledged) NavtexHub.acknowledgeRemote(olderThanMs = 35_000L, tellReceiver = false)
                return
            }
            val nrm = Navtex.parseNrm(line)
            if (nrm != null) { if (nrm.function == 1) masks.value = masks.value + (Navtex.freqIndex(nrm.khz) to nrm); return }
            val m = try { asm.feed(line, System.currentTimeMillis()) } catch (_: Exception) { null } ?: return
            count++
            NavtexHub.onMessage(m.msg, if (m.khz > 0) m.khz else 518.0)
            status.value = where() + ", $count message(s)"
        }

        private fun where() = if (mode == TCP) "Connected to $host:$port" else "Listening on UDP $port"

        private fun lines(buf: StringBuilder, data: ByteArray, n: Int) {
            for (i in 0 until n) {
                val c = data[i].toInt().toChar()
                if (c == '\n' || c == '\r') { if (buf.isNotEmpty()) onLine(buf.toString()); buf.setLength(0) }
                else if (buf.length < 250) buf.append(c)
            }
        }

        private fun tcpLoop() {
            val data = ByteArray(2048)
            while (running) {
                try {
                    status.value = "Connecting to $host:$port..."
                    val s = Socket()
                    socket = s
                    s.connect(InetSocketAddress(host, port), 8000)
                    s.keepAlive = true
                    out = s.getOutputStream()
                    status.value = where()
                    send(Navtex.nrmQuery() + "\r\n")                    // its masks, if it understands the question
                    val input = s.getInputStream()
                    val buf = StringBuilder()
                    while (running) {
                        val n = input.read(data)
                        if (n < 0) break
                        lines(buf, data, n)
                    }
                } catch (e: Exception) {
                    if (running) status.value = "No connection to $host:$port (${e.message ?: e.javaClass.simpleName}); trying again"
                }
                out = null
                masks.value = emptyMap()
                try { socket?.close() } catch (_: Exception) {}
                if (running) try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
            }
        }

        private fun udpLoop() {
            val data = ByteArray(2048)
            while (running) {
                try {
                    val s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    udp = s
                    status.value = where()
                    val p = DatagramPacket(data, data.size)
                    while (running) {
                        s.receive(p)
                        val buf = StringBuilder()
                        lines(buf, p.data, p.length)
                        if (buf.isNotEmpty()) onLine(buf.toString())      // a datagram without a line end
                    }
                } catch (e: Exception) {
                    if (running) status.value = "UDP $port: ${e.message ?: e.javaClass.simpleName}; trying again"
                }
                try { udp?.close() } catch (_: Exception) {}
                if (running) try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
            }
        }
    }
}
