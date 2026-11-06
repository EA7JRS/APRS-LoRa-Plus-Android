/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import es.ea7jrs.aprslorap.core.Control
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The satellite file (sats.txt) of the tracker's SAT mode: the phone downloads it from the internet (or reads it from a file)
 * and sends it to the tracker over BLE, so the tracker needs no WiFi to learn new satellites or fresh orbits.
 */
object SatData {
    private fun get(url: String, maxBytes: Int): ByteArray {
        var target = URL(url)
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
            val code = c.responseCode
            if (code in 300..399) { target = URL(target, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); throw IllegalStateException("HTTP $code from ${target.host}") }
            val out = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Control.SAT_DATA_MAX) throw IllegalStateException("the file is bigger than the tracker accepts (${Control.SAT_DATA_MAX} bytes)")
                }
            }
            c.disconnect()
            return out.toByteArray()
        }
        throw IllegalStateException("too many redirects")
    }

    /** Downloads [url] with the phone's internet and sends it to the tracker. */
    fun downloadAndSend(url: String) {
        if (AppState.satDataBusy.value) return
        if (!url.startsWith("https://")) { AppState.satDataMessage.value = "The address must start with https://"; return }
        AppState.satDataMessage.value = "Downloading..."
        Thread({
            try {
                val text = String(get(url, Control.SAT_DATA_MAX), Charsets.UTF_8)
                AppState.controller?.satDataWrite(text) ?: run { AppState.satDataMessage.value = "Start the link in the Connect tab" }
            } catch (e: Exception) {
                AppState.satDataMessage.value = "Download failed: ${e.message}"
            }
        }, "sat-data").start()
    }

    /** Reads a file the user picked and sends it to the tracker. */
    fun sendFile(ctx: Context, uri: Uri) {
        if (AppState.satDataBusy.value) return
        Thread({
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot open the file")
                if (bytes.size > Control.SAT_DATA_MAX) throw IllegalStateException("the file is bigger than the tracker accepts (${Control.SAT_DATA_MAX} bytes)")
                AppState.controller?.satDataWrite(String(bytes, Charsets.UTF_8)) ?: run { AppState.satDataMessage.value = "Start the link in the Connect tab" }
            } catch (e: Exception) {
                AppState.satDataMessage.value = "Cannot read the file: ${e.message}"
            }
        }, "sat-file").start()
    }
}

/**
 * The phone's own position, handed to the tracker's SAT mode (command `pos`) when the tracker has no GNSS fix of its own, or the
 * user prefers the phone's. Only while the app is in front: Android gives location to background apps only with a special permission.
 */
class PhonePosition(private val ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { report(l) }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun report(l: Location) {
        if (GpsAid.isMock(l)) return            // our own simulated location (the tracker's position): not the phone's
        AppState.controller?.phonePosition(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null, if (l.hasAccuracy()) l.accuracy else null)
        AppState.phonePosition.value = String.format(java.util.Locale.US, "%.5f, %.5f (±%.0f m, %s)", l.latitude, l.longitude, l.accuracy, l.provider ?: "?")
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        var any = false
        try {
            // fused (Android 12+, no Google Play services needed): GNSS, WiFi, cells and sensors together; quicker and steadier
            val names = if (android.os.Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in lm.allProviders)
                listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in names) {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, 5000L, 0f, listener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 60_000) report(it) }
                any = true
            }
        } catch (e: SecurityException) {
            AppState.phonePosition.value = "No location permission"
            return false
        }
        running = any
        AppState.phonePosition.value = if (any) "Waiting for a fix..." else "Location is off in the phone settings"
        return any
    }

    fun stop() {
        if (running) try { lm.removeUpdates(listener) } catch (_: SecurityException) {}
        running = false
        AppState.controller?.phonePositionOff()
        AppState.phonePosition.value = "Off"
    }
}
