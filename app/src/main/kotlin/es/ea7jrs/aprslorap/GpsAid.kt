/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import es.ea7jrs.aprslorap.core.Ubx
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A-GPS, with the phone's internet and the phone's own GNSS:
 * - for the phone: asks Android to refresh its time and its A-GPS (XTRA) data, so the SAT tab gets a fix sooner;
 * - for the tracker's u-blox receiver (command `gpsaid`): the phone's time and last real position (MGA-INI) and, with a u-blox
 *   AssistNow Predictive Orbits address (Thingstream account), the orbits downloaded with the phone's internet.
 * u-blox allows 2 downloads a day per device: the file is kept and used again for [REUSE_MS].
 */
object GpsAid {
    private const val REUSE_MS = 12 * 3600_000L          // download again after 12 h (2 a day at most)
    private const val KEEP_MS = 7 * 24 * 3600_000L       // orbits are good for up to 14 days; old copies are used up to 7
    private const val CACHE = "agps_orbits.ubx"

    fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider

    /** Asks Android to fetch fresh time and A-GPS data for the phone's own GNSS. */
    fun refreshPhone(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ok = try {
            val t = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null)
            val x = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null)
            t || x
        } catch (e: SecurityException) { false }
        AppState.phoneAgpsMessage.value =
            if (ok) "Asked the phone to refresh its time and A-GPS data (it downloads them by itself; a fix comes sooner)"
            else "The phone did not take the request (some phones refresh A-GPS only by themselves)"
    }

    /** The newest real (not simulated) position the phone knows, or null. */
    @SuppressLint("MissingPermission")
    fun lastRealLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val names = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER)
        return names.mapNotNull { p ->
            try { if (p in lm.allProviders) lm.getLastKnownLocation(p) else null } catch (_: Exception) { null }
        }.filter { !isMock(it) }.maxByOrNull { it.time }
    }

    /** MGA-INI messages with the phone's time and, if it knows one, its position (the error grows with the age of the fix). */
    private fun timeAndPosition(ctx: Context): Pair<ByteArray, String> {
        val time = Ubx.iniTimeUtc(System.currentTimeMillis())
        val l = lastRealLocation(ctx) ?: return time to "time"
        val ageS = ((System.currentTimeMillis() - l.time) / 1000).coerceAtLeast(0)
        val acc = (if (l.hasAccuracy()) l.accuracy.toDouble() else 1000.0) + ageS * 30.0         // up to 108 km/h since the fix
        if (acc > 300_000) return time to "time"
        return (time + Ubx.iniPosLlh(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else 0.0, acc)) to "time and position"
    }

    /** Sends the phone's time and position to the tracker. */
    fun sendTimeAndPosition(ctx: Context) {
        if (AppState.gpsAidBusy.value) return
        val c = AppState.controller ?: run { AppState.gpsAidMessage.value = "Start the link in the Connect tab"; return }
        val (data, what) = timeAndPosition(ctx)
        c.gpsAidWrite(data, what)
    }

    /** Time, position and Predictive Orbits (downloaded, or the copy kept from the last 12 h) to the tracker. */
    fun sendOrbits(ctx: Context, url: String) {
        if (AppState.gpsAidBusy.value) return
        if (url.isBlank()) { AppState.gpsAidMessage.value = "Put your AssistNow address (u-blox Thingstream) in the Connect tab"; return }
        if (!url.startsWith("https://")) { AppState.gpsAidMessage.value = "The AssistNow address must start with https://"; return }
        val c = AppState.controller ?: run { AppState.gpsAidMessage.value = "Start the link in the Connect tab"; return }
        val app = ctx.applicationContext
        Thread({
            try {
                val file = File(app.filesDir, CACHE)
                val tag = File(app.filesDir, "$CACHE.url")
                val sameUrl = tag.exists() && tag.readText() == url
                val age = System.currentTimeMillis() - file.lastModified()
                var note = ""
                val orbits: ByteArray = if (file.exists() && sameUrl && age < REUSE_MS) {
                    note = ", kept from ${age / 3600_000} h ago"
                    file.readBytes()
                } else try {
                    AppState.gpsAidMessage.value = "Downloading the orbits..."
                    val d = get(url)
                    val why = StringBuilder()
                    val n = Ubx.countFrames(d, why) ?: throw IllegalStateException(why.toString())
                    file.writeBytes(d); tag.writeText(url)
                    note = ", $n messages"
                    d
                } catch (e: Exception) {
                    if (file.exists() && sameUrl && age < KEEP_MS) { note = ", download failed (${e.message}): copy from ${age / 3600_000} h ago"; file.readBytes() }
                    else throw e
                }
                val (tp, what) = timeAndPosition(app)
                c.gpsAidWrite(tp + orbits, "$what and orbits$note")
            } catch (e: Exception) {
                AppState.gpsAidMessage.value = "A-GPS download failed: ${e.message}"
            }
        }, "agps").start()
    }

    private fun get(url: String): ByteArray {
        var target = URL(url)
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
            val code = c.responseCode
            if (code in 300..399) { target = URL(target, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200) {
                val body = try { c.errorStream?.use { String(it.readBytes().take(120).toByteArray()) } } catch (_: Exception) { null }
                c.disconnect(); throw IllegalStateException("HTTP $code" + (body?.let { ": ${it.trim()}" } ?: ""))
            }
            val out = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Ubx.MAX_AID_BYTES) throw IllegalStateException("file too big")
                }
            }
            c.disconnect()
            return out.toByteArray()
        }
        throw IllegalStateException("too many redirects")
    }
}
