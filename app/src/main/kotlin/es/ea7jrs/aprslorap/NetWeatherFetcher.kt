// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Weather and air quality from the internet for the TELEMETRY page of the tracker (OpenWeatherMap and / or IQAir AirVisual, with the
// user's own free keys). The phone downloads at the tracker's GNSS position (or the phone's last one) every few minutes while the link
// is up, shows it in the Sensors tab and hands it to the tracker with the "wxnet" command.
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import es.ea7jrs.aprslorap.core.NetWeather
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object NetWeatherFetcher {
    @Volatile private var busy = false
    @Volatile private var lastMs = 0L

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
        try {
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
                while (true) { val n = inp.read(buf); if (n < 0 || out.size() > 200_000) break; out.write(buf, 0, n) }
                String(out.toByteArray(), Charsets.UTF_8)
            } ?: ""
            if (code == 401 || code == 403) throw IllegalStateException("key refused (HTTP $code)")
            if (code == 429) throw IllegalStateException("too many calls (HTTP 429): raise the interval")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return s
        } finally { c.disconnect() }
    }

    /** Tracker GNSS fix first; otherwise the phone's last known position (no new fix is asked for). */
    @SuppressLint("MissingPermission")
    private fun position(ctx: Context): Pair<Double, Double>? {
        AppState.gnss.value?.let { g -> val la = g.lat; val lo = g.lon; if (g.fix && la != null && lo != null) return la to lo }
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { !GpsAid.isMock(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) { null }
    }

    /** Called often (Controller tick): downloads when the interval is due. */
    fun maybeFetch(ctx: Context, settings: Settings) {
        if (!settings.netWxOn || busy) return
        val every = settings.netWxMinutes.coerceAtLeast(NetWeather.MIN_MINUTES) * 60_000L
        if (lastMs != 0L && System.currentTimeMillis() - lastMs < every) return
        fetchNow(ctx, settings)
    }

    fun fetchNow(ctx: Context, settings: Settings) {
        if (busy) return
        val owmKey = settings.owmKey.trim()
        val iqKey = settings.iqairKey.trim()
        if (owmKey.isEmpty() && iqKey.isEmpty()) { AppState.netWxMessage.value = "Enter an OpenWeatherMap or IQAir key"; return }
        busy = true
        lastMs = System.currentTimeMillis()
        Thread({
            try {
                val pos = position(ctx) ?: throw IllegalStateException("no position (tracker without fix and no phone location)")
                var d: NetWeather.Data? = null
                val errors = ArrayList<String>()
                if (owmKey.isNotEmpty()) {
                    try {
                        d = NetWeather.parseOwmWeather(get(NetWeather.owmWeatherUrl(pos.first, pos.second, owmKey))) ?: throw IllegalStateException("unexpected answer")
                        d = NetWeather.parseOwmAir(get(NetWeather.owmAirUrl(pos.first, pos.second, owmKey)), d)
                    } catch (e: Exception) { errors.add("OpenWeatherMap: ${e.message}") }
                }
                if (iqKey.isNotEmpty()) {
                    try {
                        val r = NetWeather.parseIqair(get(NetWeather.iqairUrl(pos.first, pos.second, iqKey)), d)
                        if (r == null || r === d) throw IllegalStateException("unexpected answer (key?)")
                        d = r
                    } catch (e: Exception) { errors.add("IQAir: ${e.message}") }
                }
                val data = d ?: throw IllegalStateException(errors.joinToString("; "))
                AppState.netWx.value = data
                val nowS = System.currentTimeMillis() / 1000
                AppState.controller?.sendWxNet(NetWeather.command(data, nowS))
                AppState.netWxMessage.value = (if (errors.isEmpty()) "Updated" else errors.joinToString("; ")) +
                    " at " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
            } catch (e: Exception) {
                AppState.netWxMessage.value = "Internet weather failed: ${e.message}"
            } finally { busy = false }
        }, "net-wx").start()
    }
}
