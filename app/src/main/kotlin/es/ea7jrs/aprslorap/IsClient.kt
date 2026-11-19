/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Connection to an APRS-IS server (radio -> internet). Reconnects with growing waits. As aprsc's IGATE-HINTS.md asks: one
 * connection only; the host name (rotate.aprs2.net by default) is looked up again on every connection, never kept; packets
 * are sent only while the login is verified, and nothing heard while the link is down is kept for later (another iGate has
 * already sent it, and late copies make moving stations jump back); lines go out as bytes (ISO-8859-1), never re-encoded.
 */
class IsClient(
    private val host: String,
    private val port: Int,
    private val loginLine: () -> String,
    private val onStatus: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<String>(200)
    @Volatile private var running = false
    @Volatile private var verified = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "aprs-is").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        verified = false
        queue.clear()
        thread?.interrupt()
        onStatus("Off")
    }

    fun send(line: String) {
        if (!verified) return                                             // link down or not verified: dropped, not kept
        if (!queue.offer(line)) { queue.poll(); queue.offer(line) }       // full: drop the oldest
    }

    private fun loop() {
        var wait = 5
        while (running) {
            try {
                verified = false
                queue.clear()
                onStatus("Connecting to $host...")
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 10_000)          // new DNS lookup each time (round-robin)
                    s.soTimeout = 1000
                    val out = s.getOutputStream()
                    val inp = s.getInputStream()
                    out.write((loginLine() + "\r\n").toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    onStatus("Logging in...")
                    val buf = ByteArray(512)
                    var lastKeepAlive = System.currentTimeMillis()
                    wait = 5
                    while (running) {
                        try {
                            val n = inp.read(buf)
                            if (n < 0) throw java.io.IOException("closed by the server")
                            val text = String(buf, 0, n, Charsets.ISO_8859_1)
                            if (text.contains("unverified")) { verified = false; onStatus("Login refused: check the callsign") }
                            else if (text.contains("verified")) { verified = true; onStatus("Connected") }
                        } catch (e: java.net.SocketTimeoutException) {
                            // nothing to read: normal
                        }
                        while (true) {
                            val line = queue.poll(0, TimeUnit.MILLISECONDS) ?: break
                            out.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                        }
                        if (System.currentTimeMillis() - lastKeepAlive > 240_000) {
                            out.write("#keepalive\r\n".toByteArray()); out.flush()
                            lastKeepAlive = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: InterruptedException) {
                verified = false
                return
            } catch (e: Exception) {
                verified = false
                queue.clear()
                if (!running) return
                onStatus("Retrying in ${wait}s (${e.message})")
                try { Thread.sleep(wait * 1000L) } catch (ie: InterruptedException) { return }
                wait = minOf(wait * 2, 120)
            }
        }
    }
}
