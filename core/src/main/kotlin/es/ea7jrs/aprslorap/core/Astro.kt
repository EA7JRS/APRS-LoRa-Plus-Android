/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sun and moon, from `astro` of the core of APRS LoRa+ Plotter: NOAA's Fourier series, about a minute in mid latitudes;
 * the moon's age uses the mean synodic month (up to some 14 h off).
 */
object Astro {
    fun dayOfYear(y: Int, m: Int, d: Int): Int {
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        return cum[m - 1] + d + (if (leap && m > 2) 1 else 0)
    }

    /** Equation of time (minutes) and declination (radians). */
    private fun solar(y: Int, m: Int, d: Int, utcHours: Double): Pair<Double, Double> {
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val g = 2 * Math.PI / (if (leap) 366.0 else 365.0) * (dayOfYear(y, m, d) - 1 + (utcHours - 12) / 24.0)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val dec = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        return eq to dec
    }

    fun solarNoonUtcMin(y: Int, m: Int, d: Int, lon: Double): Double = 720 - 4 * lon - solar(y, m, d, 12.0).first

    /**
     * When the sun crosses the altitude [altDeg] (-0.833 rise / set, -6 civil, -12 nautical, -18 astronomical twilight):
     * minutes UTC of the day (can be below 0 or above 1440). Null: it does not cross it that day.
     */
    fun sunTimes(y: Int, m: Int, d: Int, lat: Double, lon: Double, altDeg: Double = -0.833): Pair<Double, Double>? {
        val (eq, dec) = solar(y, m, d, 12.0)
        val la = Math.toRadians(lat)
        val c = (sin(Math.toRadians(altDeg)) - sin(la) * sin(dec)) / (cos(la) * cos(dec))
        if (c < -1 || c > 1) return null
        val ha = Math.toDegrees(acos(c)); val noon = 720 - 4 * lon - eq
        return (noon - 4 * ha) to (noon + 4 * ha)
    }

    /** Altitude and azimuth (from true north) of the sun, degrees. */
    fun sunPosition(y: Int, m: Int, d: Int, utcHours: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val (eq, dec) = solar(y, m, d, utcHours)
        val ha = Math.toRadians((utcHours * 60 + eq + 4 * lon) / 4.0 - 180.0); val la = Math.toRadians(lat)
        val alt = Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
        var az = Math.toDegrees(atan2(-sin(ha) * cos(dec), sin(dec) * cos(la) - cos(dec) * sin(la) * cos(ha))) % 360.0
        if (az < 0) az += 360.0
        return alt to az
    }

    private fun julianDay(y0: Int, m0: Int, d: Int, utc: Double): Double {
        var y = y0; var m = m0
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100; val b = 2 - a + a / 4
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5 + utc / 24.0
    }

    const val SYNODIC = 29.530588853

    /** Age of the moon in days (0 = new). */
    fun moonAgeDays(y: Int, m: Int, d: Int, utcHours: Double): Double {
        val a = (julianDay(y, m, d, utcHours) - 2451550.09766) % SYNODIC        // new moon of 6 January 2000
        return if (a < 0) a + SYNODIC else a
    }

    /** Lit fraction 0..1. */
    fun moonIllumination(ageDays: Double): Double = (1 - cos(2 * Math.PI * ageDays / SYNODIC)) / 2

    fun moonPhaseName(ageDays: Double): String = when {
        ageDays < 1.85 -> "new"; ageDays < 5.54 -> "waxing crescent"; ageDays < 9.23 -> "first quarter"; ageDays < 12.92 -> "waxing gibbous"
        ageDays < 16.61 -> "full"; ageDays < 20.30 -> "waning gibbous"; ageDays < 23.99 -> "last quarter"; ageDays < 27.68 -> "waning crescent"
        else -> "new"
    }
}
