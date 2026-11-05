/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.*

object Geo {
    private const val R_KM = 6371.0088

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R_KM * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing 0..360 (north = 0) from point 1 to point 2. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cardinal16(deg: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((deg + 11.25) / 22.5).toInt() % 16]
    }
}

/** URIs to hand a station position to a map / navigation app (Google Maps). */
object Nav {
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.6f", v)

    /** Turn-by-turn navigation in Google Maps. */
    fun googleNavigationUri(lat: Double, lon: Double): String = "google.navigation:q=${fmt(lat)},${fmt(lon)}"

    /** Generic geo: URI for any maps app (fallback if Google Maps is not installed). */
    fun geoUri(lat: Double, lon: Double, label: String): String =
        "geo:${fmt(lat)},${fmt(lon)}?q=${fmt(lat)},${fmt(lon)}(${label.filter { it.isLetterOrDigit() || it == '-' }})"
}
