/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import es.ea7jrs.aprslorap.core.Gnss

/**
 * The tracker's GNSS as the phone's location ("mock location"), so maps and navigation apps use the tracker's receiver.
 * Android only allows it to the app chosen in Developer options > Select mock location app.
 */
class MockLocation(ctx: Context, private val onStatus: (String) -> Unit) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var active = false

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        return try {
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
            )
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            active = true
            onStatus("Waiting for the tracker's GNSS")
            true
        } catch (e: SecurityException) {
            onStatus("Not allowed: choose APRS LoRa+ in Developer options > Select mock location app")
            false
        } catch (e: Exception) {
            onStatus("Error: ${e.message}")
            false
        }
    }

    fun onGnss(g: Gnss) {
        if (!active || !g.fix) return
        val lat = g.lat ?: return
        val lon = g.lon ?: return
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            g.altitudeM?.let { altitude = it }
            accuracy = ((g.hdop ?: 2.0) * 5.0).toFloat().coerceAtLeast(3f)       // rough: HDOP x 5 m
            g.speedKmh?.let { speed = (it / 3.6).toFloat() }
            g.courseDeg?.let { bearing = it.toFloat() }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            onStatus(String.format(java.util.Locale.US, "Active: %.5f, %.5f", lat, lon))
        } catch (e: Exception) {
            active = false
            onStatus("Stopped: ${e.message}")
        }
    }

    fun stop() {
        if (active) try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        active = false
        onStatus("Off")
    }
}
