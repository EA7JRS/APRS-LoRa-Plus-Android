/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun and moon, ported from `lib/plotter_core/src/astro.cpp` of the plotter so every APRS LoRa+ device gives the same numbers:
 * sun position from Meeus (ch. 25, ~0.01°), sunrise / sunset from the NOAA series (~1 min), moon from Meeus (ch. 47, main
 * terms, topocentric with the parallax that reaches 1°, ~0.03°), moon rise / set, phase and lit fraction, mean refraction.
 * Altitudes are geometric (no refraction); add [refractionDeg] for what an antenna sees. Times are Unix ms (UTC).
 */
object Astro {
    data class AltAz(val azDeg: Double, val altDeg: Double, val distKm: Double = 0.0)

    /** Minutes after 00:00 UTC of that UTC day; null = no such event that day. */
    data class RiseSet(val riseMin: Double?, val setMin: Double?)

    private const val SYNODIC = 29.530588853

    fun julianDay(timeMs: Long): Double = timeMs / 86_400_000.0 + 2440587.5

    private fun nrm(a: Double): Double { val r = a % 360.0; return if (r < 0) r + 360 else r }
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    private fun gmst(jd: Double): Double {
        val tu = (jd - 2451545.0) / 36525.0
        return nrm(280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * tu * tu)
    }

    private fun altAz(lat: Double, ha: Double, dec: Double, dist: Double = 0.0): AltAz {
        val la = rad(lat)
        val alt = deg(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
        val az = deg(atan2(-sin(ha) * cos(dec), sin(dec) * cos(la) - cos(dec) * sin(la) * cos(ha)))
        return AltAz(nrm(az), alt, dist)
    }

    fun sunPosition(timeMs: Long, lat: Double, lon: Double): AltAz {
        // apparent place to almanac precision (Celnav: truncated VSOP87, nutation, aberration), as in the plotter core
        val g = Celnav.sunGhaDec(julianDay(timeMs))
        return altAz(lat, rad(g.gha + lon), rad(g.dec))
    }

    private fun dayOfYear(y: Int, m: Int, d: Int): Int {
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        return cum[m - 1] + d + if (leap && m > 2) 1 else 0
    }

    /** Sun crossing [altDeg] (−0.833 sunrise / sunset, −6 civil, −12 nautical, −18 astronomical twilight); NOAA series. */
    fun sunTimes(y: Int, m: Int, d: Int, lat: Double, lon: Double, altDeg: Double = -0.833): RiseSet {
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val g = 2 * Math.PI / (if (leap) 366.0 else 365.0) * (dayOfYear(y, m, d) - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val dec = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = rad(lat)
        val c = (sin(rad(altDeg)) - sin(la) * sin(dec)) / (cos(la) * cos(dec))
        if (c < -1 || c > 1) return RiseSet(null, null)
        val ha = deg(acos(c)); val noon = 720 - 4 * lon - eq
        return RiseSet(noon - 4 * ha, noon + 4 * ha)
    }

    // Meeus, table 47.A (D, M, M', F, longitude 1e-6°, distance m) and 47.B (latitude 1e-6°), main terms
    private val LR = arrayOf(
        intArrayOf(0, 0, 1, 0, 6288774, -20905355), intArrayOf(2, 0, -1, 0, 1274027, -3699111), intArrayOf(2, 0, 0, 0, 658314, -2955968),
        intArrayOf(0, 0, 2, 0, 213618, -569925), intArrayOf(0, 1, 0, 0, -185116, 48888), intArrayOf(0, 0, 0, 2, -114332, -3149),
        intArrayOf(2, 0, -2, 0, 58793, 246158), intArrayOf(2, -1, -1, 0, 57066, -152138), intArrayOf(2, 0, 1, 0, 53322, -170733),
        intArrayOf(2, -1, 0, 0, 45758, -204586), intArrayOf(0, 1, -1, 0, -40923, -129620), intArrayOf(1, 0, 0, 0, -34720, 108743),
        intArrayOf(0, 1, 1, 0, -30383, 104755), intArrayOf(2, 0, 0, -2, 15327, 10321), intArrayOf(0, 0, 1, 2, -12528, 0),
        intArrayOf(0, 0, 1, -2, 10980, 79661), intArrayOf(4, 0, -1, 0, 10675, -34782), intArrayOf(0, 0, 3, 0, 10034, -23210),
        intArrayOf(4, 0, -2, 0, 8548, -21636), intArrayOf(2, 1, -1, 0, -7888, 24208), intArrayOf(2, 1, 0, 0, -6766, 30824),
        intArrayOf(1, 0, -1, 0, -5163, -8379), intArrayOf(1, 1, 0, 0, 4987, -16675), intArrayOf(2, -1, 1, 0, 4036, -12831),
        intArrayOf(2, 0, 2, 0, 3994, -10445), intArrayOf(4, 0, 0, 0, 3861, -11650), intArrayOf(2, 0, -3, 0, 3665, 14403),
        intArrayOf(0, 1, -2, 0, -2689, -7003), intArrayOf(2, 0, -1, 2, -2602, 0), intArrayOf(2, -1, -2, 0, 2390, 10056),
        intArrayOf(1, 0, 1, 0, -2348, 6322), intArrayOf(2, -2, 0, 0, 2236, -9884),
    )
    private val LB = arrayOf(
        intArrayOf(0, 0, 0, 1, 5128122), intArrayOf(0, 0, 1, 1, 280602), intArrayOf(0, 0, 1, -1, 277693), intArrayOf(2, 0, 0, -1, 173237),
        intArrayOf(2, 0, -1, 1, 55413), intArrayOf(2, 0, -1, -1, 46271), intArrayOf(2, 0, 0, 1, 32573), intArrayOf(0, 0, 2, 1, 17198),
        intArrayOf(2, 0, 1, -1, 9266), intArrayOf(0, 0, 2, -1, 8822), intArrayOf(2, -1, 0, -1, 8216), intArrayOf(2, 0, -2, -1, 4324),
        intArrayOf(2, 0, 1, 1, 4200), intArrayOf(2, 1, 0, -1, -3359), intArrayOf(2, -1, -1, 1, 2463), intArrayOf(2, -1, 0, 1, 2211),
        intArrayOf(2, -1, -1, -1, 2065), intArrayOf(0, 1, -1, -1, -1870), intArrayOf(4, 0, -1, -1, 1828), intArrayOf(0, 1, 0, 1, -1794),
    )

    /** Topocentric moon (azimuth, geometric altitude, distance in km). */
    fun moonPosition(timeMs: Long, lat: Double, lon: Double): AltAz {
        val jd = julianDay(timeMs) + 69.0 / 86400.0
        val t = (jd - 2451545.0) / 36525.0
        val lp = nrm(218.3164477 + 481267.88123421 * t); val dd = nrm(297.8501921 + 445267.1114034 * t)
        val mm = nrm(357.5291092 + 35999.0502909 * t); val mp = nrm(134.9633964 + 477198.8675055 * t)
        val ff = nrm(93.2720950 + 483202.0175233 * t); val e = 1 - 0.002516 * t - 0.0000074 * t * t
        val a1 = nrm(119.75 + 131.849 * t); val a2 = nrm(53.09 + 479264.290 * t); val a3 = nrm(313.45 + 481266.484 * t)
        var sl = 0.0; var sr = 0.0; var sb = 0.0
        for (k in LR) {
            val arg = rad(k[0] * dd + k[1] * mm + k[2] * mp + k[3] * ff)
            val ef = if (k[1] == 0) 1.0 else if (abs(k[1]) == 1) e else e * e
            sl += ef * k[4] * sin(arg); sr += ef * k[5] * cos(arg)
        }
        for (k in LB) {
            val ef = if (k[1] == 0) 1.0 else if (abs(k[1]) == 1) e else e * e
            sb += ef * k[4] * sin(rad(k[0] * dd + k[1] * mm + k[2] * mp + k[3] * ff))
        }
        sl += 3958 * sin(rad(a1)) + 1962 * sin(rad(lp - ff)) + 318 * sin(rad(a2))
        sb += -2235 * sin(rad(lp)) + 382 * sin(rad(a3)) + 175 * sin(rad(a1 - ff)) + 175 * sin(rad(a1 + ff)) +
            127 * sin(rad(lp - mp)) - 115 * sin(rad(lp + mp))
        val om = nrm(125.04452 - 1934.136261 * t); val ls = nrm(280.4665 + 36000.7698 * t)
        val dpsi = -17.20 * sin(rad(om)) - 1.32 * sin(rad(2 * ls)) - 0.23 * sin(rad(2 * lp)) + 0.21 * sin(rad(2 * om))
        val deps = 9.20 * cos(rad(om)) + 0.57 * cos(rad(2 * ls)) + 0.10 * cos(rad(2 * lp)) - 0.09 * cos(rad(2 * om))
        val lam = rad(lp + sl / 1e6 + dpsi / 3600.0); val bet = rad(sb / 1e6)
        val dist = 385000.56 + sr / 1000.0
        val eps = rad(23.4392911 - 0.0130042 * t + deps / 3600.0)
        val ra = atan2(sin(lam) * cos(eps) - tan(bet) * sin(eps), cos(lam))
        val dec = asin(sin(bet) * cos(eps) + cos(bet) * sin(eps) * sin(lam))
        val h = rad(nrm(gmst(jd - 69.0 / 86400.0) + dpsi / 3600.0 * cos(eps)) + lon) - ra
        val la = rad(lat)
        val sinPi = 6378.14 / dist
        val u = atan(0.99664719 * tan(la))
        val rs = 0.99664719 * sin(u); val rc = cos(u)
        val dra = atan2(-rc * sinPi * sin(h), cos(dec) - rc * sinPi * cos(h))
        val dec2 = atan2((sin(dec) - rs * sinPi) * cos(dra), cos(dec) - rc * sinPi * cos(h))
        return altAz(lat, h - dra, dec2, dist)
    }

    /** Moon rise / set during the UTC day that starts at [dayStartMs] (00:00 UTC). */
    fun moonTimes(dayStartMs: Long, lat: Double, lon: Double): RiseSet {
        val h0 = -0.833
        var rise: Double? = null; var set: Double? = null
        var prev = moonPosition(dayStartMs, lat, lon).altDeg - h0
        for (i in 1..144) {
            val a = moonPosition(dayStartMs + i * 600_000L, lat, lon).altDeg - h0
            if ((prev < 0) != (a < 0)) {
                val tt = (i - 1 + prev / (prev - a)) * 10.0
                if (prev < 0 && rise == null) rise = tt else if (prev >= 0 && set == null) set = tt
            }
            prev = a
        }
        return RiseSet(rise, set)
    }

    /** Moon age in days (0 = new) with the mean synodic month. */
    fun moonAgeDays(timeMs: Long): Double {
        val a = (julianDay(timeMs) - 2451550.09766) % SYNODIC
        return if (a < 0) a + SYNODIC else a
    }

    fun moonIllumination(ageDays: Double): Double = (1 - cos(2 * Math.PI * ageDays / SYNODIC)) / 2

    /** 0 new, 2 first quarter, 4 full, 6 last quarter. */
    fun moonPhase8(ageDays: Double): Int = ((floor(ageDays / SYNODIC * 8 + 0.5).toInt() % 8) + 8) % 8

    private val PHASES = arrayOf("New moon", "Waxing crescent", "First quarter", "Waxing gibbous", "Full moon", "Waning gibbous", "Last quarter", "Waning crescent")
    fun moonPhaseName(phase8: Int): String = PHASES[((phase8 % 8) + 8) % 8]

    /** Mean refraction (Saemundsson, degrees): apparent altitude = true altitude + this. */
    fun refractionDeg(trueAltDeg: Double): Double {
        val h = maxOf(trueAltDeg, -1.9)
        return 1.02 / tan(rad(h + 10.3 / (h + 5.11))) / 60.0
    }

    /** "06:59" from minutes after 00:00 (of any day), shifted by [offsetMin]. */
    fun hhmm(minutes: Double?, offsetMin: Int = 0): String {
        if (minutes == null) return "--:--"
        val m = ((Math.round(minutes).toInt() + offsetMin) % 1440 + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
