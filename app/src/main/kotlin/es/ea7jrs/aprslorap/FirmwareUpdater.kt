/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import es.ea7jrs.aprslorap.core.FirmwareSite
import es.ea7jrs.aprslorap.core.OtaWindow
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firmware of the tracker from the app:
 * - the web installer (USB cable, any board) opens in Chrome: it needs Web Serial, which an in-app web view does not have;
 * - over Bluetooth (boards with a second firmware slot): the phone downloads the published image, checks size and SHA-256,
 *   and sends it to the tracker, which checks it again (SHA-256 and board identity), activates it and restarts;
 * - over the tracker's own WiFi (its "Firmware update" network in the WebUI).
 */
object FirmwareUpdater {
    private val lock = Object()
    @Volatile private var worker: Thread? = null
    @Volatile private var window: OtaWindow? = null
    @Volatile private var started = false
    @Volatile private var finished = false
    @Volatile private var failure: String? = null
    @Volatile private var cancelled = false

    private fun status(msg: String) { AppState.fwMessage.value = msg }

    private fun httpGet(url: String, maxBytes: Int, progress: ((Int) -> Unit)? = null): ByteArray {
        var target = URL(url)
        repeat(5) {                                                  // GitHub may redirect
            val c = target.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
            val code = c.responseCode
            if (code in 300..399) { target = URL(target, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); throw IllegalStateException("HTTP $code from ${target.host}") }
            val total = c.contentLengthLong
            val out = ByteArrayOutputStream(if (total in 1..maxBytes.toLong()) total.toInt() else 65536)
            c.inputStream.use { inp ->
                val buf = ByteArray(16384)
                while (true) {
                    if (cancelled) throw IllegalStateException("cancelled")
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IllegalStateException("file too big")
                    if (total > 0) progress?.invoke((out.size() * 100L / total).toInt())
                }
            }
            c.disconnect()
            return out.toByteArray()
        }
        throw IllegalStateException("too many redirects")
    }

    /** Looks the latest published version for the tracker's board up (phone internet). */
    fun check(board: String) {
        if (AppState.fwBusy.value) return
        status("Looking for the latest version...")
        Thread({
            try {
                val r = FirmwareSite.parse(String(httpGet(FirmwareSite.manifestUrl(board), 8192), Charsets.UTF_8))
                    ?: throw IllegalStateException("unexpected answer from the release site")
                AppState.fwLatest.value = r
                status("Latest version: ${r.version} (${r.size / 1024} KB)")
            } catch (e: Exception) {
                status("Could not read the release site: ${e.message}")
            }
        }, "fw-check").start()
    }

    fun openInstaller(ctx: Context) {
        val uri = Uri.parse(FirmwareSite.INSTALLER)
        val chrome = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(chrome) } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                AppState.notice.value = "The installer needs Chrome (Web Serial); other browsers may not see the USB port"
            } catch (e2: ActivityNotFoundException) {
                AppState.error.value = "No web browser found"
            }
        }
    }

    // ------------------------------------------------------------------ over Bluetooth

    fun startBle() {
        val release = AppState.fwLatest.value ?: run { AppState.error.value = "Look for the latest version first"; return }
        val info = AppState.trackerInfo.value
        if (info == null || !AppState.controlReady.value) { AppState.error.value = "Start the link with the tracker first"; return }
        if (!info.hasSecondSlot) { AppState.error.value = "This board updates only with the web installer (USB)"; return }
        if (AppState.fwBusy.value) return
        AppState.fwBusy.value = true
        AppState.fwProgress.value = 0
        cancelled = false
        worker = Thread({ runBle(release) }, "fw-ble").also { it.start() }
    }

    private fun runBle(r: FirmwareSite.Release) {
        val c = AppState.controller
        val ble = AppState.ble
        try {
            if (c == null || ble == null) throw IllegalStateException("no link with the tracker")
            status("Downloading ${r.version}...")
            val image = httpGet(r.url, 16 * 1024 * 1024) { AppState.fwProgress.value = it }
            if (image.size.toLong() != r.size) throw IllegalStateException("downloaded ${image.size} bytes, announced ${r.size}")
            if (FirmwareSite.sha256Hex(image) != r.sha256) throw IllegalStateException("the download does not match its SHA-256")
            synchronized(lock) { started = false; finished = false; failure = null; window = OtaWindow(image.size) }
            AppState.fwProgress.value = 0
            status("Preparing the tracker...")
            c.control("ota", "size" to image.size, "sha" to r.sha256)
            waitFor(15_000) { started }
            ble.setHighPriority(true)
            status("Sending ${r.version} to the tracker (keep the phone near it)...")
            val w = window!!
            var lastConfirm = System.currentTimeMillis()
            var lastConfirmed = 0
            while (!w.complete) {
                if (cancelled) throw IllegalStateException("cancelled")
                failure?.let { throw IllegalStateException(it) }
                val piece = synchronized(lock) { w.next(1024) }
                if (piece != null) {
                    if (!ble.writeFirmware(image.copyOfRange(piece.first, piece.last + 1))) throw IllegalStateException("link lost")
                    continue
                }
                synchronized(lock) { lock.wait(200) }
                if (w.confirmed != lastConfirmed) { lastConfirmed = w.confirmed; lastConfirm = System.currentTimeMillis() }
                if (System.currentTimeMillis() - lastConfirm > 25_000) throw IllegalStateException("the tracker stopped answering")
            }
            waitFor(15_000) { finished }
            status("Installed ${r.version}. The tracker restarts and the link comes back by itself.")
            AppState.notice.value = "Firmware ${r.version} installed"
        } catch (e: Exception) {
            if (started && !finished) try { c?.control("ota_abort") } catch (_: Exception) {}
            status("Firmware not installed: ${e.message}. The tracker keeps its current firmware.")
        } finally {
            ble?.setHighPriority(false)
            window = null
            AppState.fwBusy.value = false
        }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        synchronized(lock) {
            while (!cond()) {
                failure?.let { throw IllegalStateException(it) }
                if (cancelled) throw IllegalStateException("cancelled")
                val left = end - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("no answer from the tracker")
                lock.wait(minOf(left, 200))
            }
        }
    }

    fun cancel() {
        cancelled = true
        synchronized(lock) { lock.notifyAll() }
    }

    fun onStarted() = synchronized(lock) { started = true; lock.notifyAll() }

    fun onProgress(offset: Long, done: Boolean) = synchronized(lock) {
        window?.let { it.confirm(offset.toInt()); AppState.fwProgress.value = it.percent }
        if (done) finished = true
        lock.notifyAll()
    }

    fun onError(msg: String) = synchronized(lock) { failure = msg; lock.notifyAll() }
}
