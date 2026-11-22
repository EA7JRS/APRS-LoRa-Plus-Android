/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * NMEA 0183 over TCP for other apps on the phone or on the same network (OpenCPN, Navionics, SeaPilot...): they connect to
 * the phone's IP (or 127.0.0.1) on [port] and receive every sentence: the tracker's own GNSS, MDA/XDR and the APRS stations
 * as AIS targets (!AIVDM). What a client writes goes, line by line, to [onLine] (the plotter acknowledges the NAVTEX search and
 * rescue alarm with an ACK sentence); nothing a client writes is ever passed on to the tracker or transmitted.
 */
class NmeaServer(private val port: Int, private val onStatus: (String) -> Unit) {
    /** A line written by a client (at most 120 characters, without its line end). Called on that client's reading thread. */
    @Volatile var onLine: ((String) -> Unit)? = null

    private class Client(val socket: Socket, val out: OutputStream)

    private val clients = CopyOnWriteArrayList<Client>()
    private val writer = Executors.newSingleThreadExecutor()            // one thread writes, so a slow client never blocks BLE
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "nmea-server").also { it.isDaemon = true; it.start() }
    }

    private fun acceptLoop() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            server = s
            status()
            while (running) {
                val c = s.accept()
                c.tcpNoDelay = true
                c.soTimeout = 0
                val client = Client(c, c.getOutputStream())
                clients.add(client)
                status()
                Thread({ readLoop(client) }, "nmea-client").also { it.isDaemon = true; it.start() }
            }
        } catch (e: Exception) {
            if (running) onStatus("Error on port $port: ${e.message}")
        }
    }

    /** Reads what one client writes, a line at a time; when it hangs up, the client is dropped. */
    private fun readLoop(c: Client) {
        try {
            val input = c.socket.getInputStream()
            val line = StringBuilder()
            var skip = false                                            // a line that is too long is not a sentence: ignored whole
            while (running) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code || b == '\r'.code) {
                    if (!skip && line.isNotEmpty()) try { onLine?.invoke(line.toString()) } catch (_: Exception) {}
                    line.setLength(0); skip = false
                } else if (line.length >= 120) skip = true
                else if (!skip) line.append(b.toChar())
            }
        } catch (_: Exception) {}
        if (clients.remove(c)) { try { c.socket.close() } catch (_: Exception) {}; if (running) status() }
    }

    private fun status() = onStatus("Port $port, ${clients.size} client(s)")

    /** Sends one complete sentence (with or without CRLF) to every client. */
    fun send(sentence: String) {
        if (clients.isEmpty()) return
        val data = (sentence.trimEnd('\r', '\n') + "\r\n").toByteArray(Charsets.US_ASCII)
        writer.execute {
            for (c in clients) {
                try { c.out.write(data); c.out.flush() } catch (e: Exception) {
                    clients.remove(c)
                    try { c.socket.close() } catch (_: Exception) {}
                    status()
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        for (c in clients) try { c.socket.close() } catch (_: Exception) {}
        clients.clear()
        writer.shutdownNow()
        onStatus("Off")
    }
}
