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

/** Connection to an APRS-IS server (radio -> internet). Reconnects with growing waits. */
class IsClient(
    private val host: String,
    private val port: Int,
    private val loginLine: () -> String,
    private val onStatus: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<String>(200)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "aprs-is").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        onStatus("Off")
    }

    fun send(line: String) {
        if (!queue.offer(line)) { queue.poll(); queue.offer(line) }       // full: drop the oldest
    }

    private fun loop() {
        var wait = 5
        while (running) {
            try {
                onStatus("Connecting to $host...")
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 10_000)
                    s.soTimeout = 1000
                    val out = s.getOutputStream()
                    val inp = s.getInputStream()
                    out.write((loginLine() + "\r\n").toByteArray())
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
                            if (text.contains("unverified")) onStatus("Login refused: check the callsign")
                            else if (text.contains("verified")) onStatus("Connected")
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
                return
            } catch (e: Exception) {
                if (!running) return
                onStatus("Retrying in ${wait}s (${e.message})")
                try { Thread.sleep(wait * 1000L) } catch (ie: InterruptedException) { return }
                wait = minOf(wait * 2, 120)
            }
        }
    }
}
