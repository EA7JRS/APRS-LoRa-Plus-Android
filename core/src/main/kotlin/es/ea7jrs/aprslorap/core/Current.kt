/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Current and leeway, ported from `current` (and `rhumbDestination` of `geo`) of the plotter core, with the same tests.
 * True courses in degrees, speeds in knots. Water track = true heading + leeway (leeway + = to starboard).
 * A current is given by its set (where it goes) and drift.
 */
object Current {
    data class Vec(val setDeg: Double, val driftKn: Double)

    private const val RNM = 6371008.8 / 1852.0          // same Earth radius as the plotter's geo (kEarthRadiusM / kMetersPerNm)
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun n360(a: Double): Double { val r = a % 360.0; return if (r < 0) r + 360.0 else r }
    private fun n180(a: Double): Double { var r = n360(a); if (r > 180) r -= 360.0; return r }
    private fun fromNE(n: Double, e: Double): Vec { val d = sqrt(n * n + e * e); return Vec(if (d > 1e-9) n360(deg(atan2(e, n))) else 0.0, d) }

    fun rhumbDistanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dp = p2 - p1; val dl = rad(n180(lon2 - lon1))
        val dpsi = ln(tan(Math.PI / 4 + p2 / 2) / tan(Math.PI / 4 + p1 / 2))
        val q = if (abs(dpsi) > 1e-12) dp / dpsi else cos(p1)
        return RNM * sqrt(dp * dp + q * q * dl * dl)
    }

    fun rhumbBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(n180(lon2 - lon1))
        val dpsi = ln(tan(Math.PI / 4 + p2 / 2) / tan(Math.PI / 4 + p1 / 2))
        return n360(deg(atan2(dl, dpsi)))
    }

    fun rhumbDestination(lat: Double, lon: Double, bearingDeg: Double, distNm: Double): Pair<Double, Double> {
        val d = distNm / RNM; val t = rad(bearingDeg); val p1 = rad(lat)
        val p2 = (p1 + d * cos(t)).coerceIn(-Math.PI / 2, Math.PI / 2)
        val dpsi = ln(tan(Math.PI / 4 + p2 / 2) / tan(Math.PI / 4 + p1 / 2))
        val q = if (abs(dpsi) > 1e-12) (p2 - p1) / dpsi else cos(p1)
        val dl = if (q != 0.0) d * sin(t) / q else 0.0
        return deg(p2) to n180(lon + deg(dl))
    }

    /** Measured current = motion over the ground (GPS) - motion through the water (heading + leeway, log). */
    fun fromVectors(headingDeg: Double, stwKn: Double, leewayDeg: Double, cogDeg: Double, sogKn: Double): Vec {
        val rs = rad(headingDeg + leewayDeg); val cg = rad(cogDeg)
        return fromNE(sogKn * cos(cg) - stwKn * cos(rs), sogKn * sin(cg) - stwKn * sin(rs))
    }

    data class DrResult(val drLat: Double, val drLon: Double, val current: Vec)

    /** Current from the DR (departure, course, speed, leeway, hours) and the fix. null if hours <= 0. */
    fun fromDr(lat: Double, lon: Double, courseDeg: Double, speedKn: Double, leewayDeg: Double, hours: Double, fixLat: Double, fixLon: Double): DrResult? {
        if (hours <= 0) return null
        val dr = rhumbDestination(lat, lon, n360(courseDeg + leewayDeg), speedKn * hours)
        val d = rhumbDistanceNm(dr.first, dr.second, fixLat, fixLon)
        return DrResult(dr.first, dr.second, Vec(if (d > 1e-9) rhumbBearing(dr.first, dr.second, fixLat, fixLon) else 0.0, d / hours))
    }

    /** Course and speed made good with a current: Pair(COG, SOG). */
    fun groundTrack(headingDeg: Double, stwKn: Double, leewayDeg: Double, c: Vec): Pair<Double, Double> {
        val rs = rad(headingDeg + leewayDeg); val st = rad(c.setDeg)
        val g = fromNE(stwKn * cos(rs) + c.driftKn * cos(st), stwKn * sin(rs) + c.driftKn * sin(st))
        return g.setDeg to g.driftKn
    }

    /** True course to steer to make good cogDeg: Pair(heading, SOG); null if the current crosses faster than the boat or stops it. */
    fun courseToSteer(cogDeg: Double, stwKn: Double, leewayDeg: Double, c: Vec): Pair<Double, Double>? {
        if (stwKn <= 0) return null
        val rel = rad(c.setDeg - cogDeg)
        val s = -c.driftKn * sin(rel) / stwKn
        if (abs(s) > 1) return null
        val a = asin(s)
        val sog = stwKn * cos(a) + c.driftKn * cos(rel)
        if (sog <= 0) return null
        return n360(cogDeg + deg(a) - leewayDeg) to sog
    }
}
