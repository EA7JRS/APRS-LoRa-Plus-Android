/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * The phone's GNSS for the position beacons the app sends through the tracker (Connect > Position beacons from the phone).
 * Runs with the link service; every fix goes to the Controller, which decides when to beacon.
 */
class PhoneBeaconSource(private val ctx: Context) {
    companion object {
        fun permitted(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private val listener = LocationListener { l -> report(l) }

    private fun report(l: Location) {
        if (GpsAid.isMock(l)) return            // our own simulated location is the tracker's position, not the phone's
        AppState.controller?.beaconFix(
            l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null,
            if (l.hasSpeed()) l.speed * 3.6 else null, if (l.hasBearing()) Math.round(l.bearing) % 360 else null,
            if (l.hasAccuracy()) l.accuracy else null,
        )
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!permitted(ctx)) { AppState.phoneBeacon.value = "No location permission"; return false }
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { AppState.phoneBeacon.value = "Location is off in the phone settings"; return false }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            AppState.phoneBeacon.value = "No location: ${e.message}"
            return false
        }
        running = true
        AppState.phoneBeacon.value = "Waiting for a fix..."
        return true
    }

    fun stop() {
        if (running) try { lm.removeUpdates(listener) } catch (_: Exception) {}
        running = false
        AppState.phoneBeacon.value = "Off"
    }
}
