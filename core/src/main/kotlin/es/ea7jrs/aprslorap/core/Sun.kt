/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Position of the Sun (accuracy ~0.1°, enough for day / night): used by the automatic light / dark theme. */
object Sun {
    /** Elevation of the Sun in degrees seen from (lat, lon) at [timeMs] (UTC epoch ms). */
    fun elevationDeg(lat: Double, lon: Double, timeMs: Long): Double {
        val d = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0              // days since J2000.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24 * 15
        val ha = Math.toRadians(gmst + lon - ra)
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
    }

    /** Day = the Sun above the horizon (−0.833°: upper limb with refraction, the sunrise / sunset of the almanacs). */
    fun isDay(lat: Double, lon: Double, timeMs: Long): Boolean = elevationDeg(lat, lon, timeMs) > -0.833
}
