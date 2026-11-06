/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Ax25Frame
import es.ea7jrs.aprslorap.core.Kiss
import es.ea7jrs.aprslorap.core.Receivers
import java.net.InetSocketAddress
import java.net.Socket

/**
 * More trackers as receivers (each in its own mode / frequency): Bluetooth LE KISS (no control service, no PIN) or the
 * tracker's KISS TCP server over WiFi. Their frames go to Stations / Messages with the receiver's name in the log.
 * The main tracker (Connect tab) still does the control and the transmitting.
 */
object ExtraReceivers {
    private interface Link { fun close() }
    private val links = HashMap<String, Link>()

    private fun status(key: String, s: String) { synchronized(this) { AppState.extraStatus.value = AppState.extraStatus.value + (key to s) } }

    private fun frames(e: Receivers.Entry): (ByteArray) -> Unit {
        val decoder = Kiss.Decoder()
        return { data -> synchronized(decoder) { for (f in decoder.feed(data)) Ax25Frame.decode(f)?.let { AppState.controller?.onPhoneRadioFrame(it, e.label) } } }
    }

    @Synchronized
    fun start(ctx: Context, s: Settings) {
        stop()
        for (e in s.extraReceivers.filter { it.enabled }) {
            if (e.kind == Receivers.Kind.BLE && e.address.equals(s.deviceAddress, true)) { status(e.key, "This is the main tracker"); continue }
            links[e.key] = when (e.kind) {
                Receivers.Kind.BLE -> {
                    val b = TrackerBle(ctx.applicationContext, onBytes = frames(e), onStatus = { status(e.key, it) }, onLog = { AppState.addLog("[${e.label}] $it") }, kissOnly = true)
                    b.connect(e.address)
                    object : Link { override fun close() = b.disconnect() }
                }
                Receivers.Kind.TCP -> TcpLink(e, frames(e))
            }
        }
    }

    @Synchronized
    fun stop() {
        for (l in links.values) try { l.close() } catch (_: Exception) {}
        links.clear()
        AppState.extraStatus.value = emptyMap()
    }

    private class TcpLink(val e: Receivers.Entry, val onBytes: (ByteArray) -> Unit) : Link {
        @Volatile private var running = true
        @Volatile private var sock: Socket? = null

        init { Thread({ run() }, "kiss-tcp").also { it.isDaemon = true; it.start() } }

        override fun close() { running = false; try { sock?.close() } catch (_: Exception) {} }

        private fun run() {
            val (host, port) = e.hostPort() ?: run { status(e.key, "Bad address"); return }
            while (running) {
                try {
                    status(e.key, "Connecting to $host:$port...")
                    Socket().use { s ->
                        sock = s
                        s.connect(InetSocketAddress(host, port), 5000)
                        s.soTimeout = 0
                        status(e.key, "Connected (KISS over WiFi)")
                        val inp = s.getInputStream()
                        val buf = ByteArray(2048)
                        while (running) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            onBytes(buf.copyOf(n))
                        }
                    }
                    if (running) status(e.key, "Closed by the tracker; retrying")
                } catch (ex: Exception) {
                    if (!running) break
                    status(e.key, "No connection (${ex.message}); retrying")
                }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
            status(e.key, "Off")
        }
    }
}
