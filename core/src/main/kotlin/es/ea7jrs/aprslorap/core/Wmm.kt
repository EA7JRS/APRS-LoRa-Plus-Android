/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * World Magnetic Model (WMM2025 by NOAA NCEI / British Geological Survey, public-domain coefficients, valid 2025.0 to 2030.0):
 * magnetic declination, inclination and field strength. Same algorithm as `lib/plotter_core/src/wmm.cpp` of the plotter
 * (degree-12 spherical harmonic synthesis), so every APRS LoRa+ device gives the same numbers. [load] takes a new WMM.COF.
 */
class Wmm private constructor(val epoch: Double, val name: String, private val c: DoubleArray) {
    val validUntil: Double get() = epoch + 5
    fun expired(year: Double) = year >= validUntil

    data class Field(val decDeg: Double, val incDeg: Double, val hNt: Double, val fNt: Double, val xNt: Double, val yNt: Double, val zNt: Double)

    /** Geodetic WGS84 latitude / longitude, altitude above the ellipsoid in km, decimal year. Null outside −90..90. */
    fun field(lat: Double, lon: Double, altKm: Double, year: Double): Field? {
        if (lat !in -90.0..90.0 || !lon.isFinite() || !year.isFinite()) return null
        val e2 = F * (2 - F)
        val sl = sin(Math.toRadians(lat)); val cl = cos(Math.toRadians(lat))
        val rc = A / sqrt(1 - e2 * sl * sl)
        val p = (rc + altKm) * cl; val z = (rc * (1 - e2) + altKm) * sl
        val r = hypot(p, z)
        val phic = asin(z / r)
        val x = sin(phic); val cc = cos(phic)
        val dt = year - epoch
        val pp = Array(N + 1) { DoubleArray(N + 1) }
        val dp = Array(N + 1) { DoubleArray(N + 1) }
        pp[0][0] = 1.0; pp[1][0] = x; pp[1][1] = cc
        for (n in 2..N) pp[n][n] = cc * sqrt((2.0 * n - 1) / (2.0 * n)) * pp[n - 1][n - 1]
        for (m in 0..N) for (n in maxOf(m + 1, 2)..N) {
            val b = if (n - 1 >= m) sqrt(((n - 1) * (n - 1) - m * m).toDouble()) else 0.0
            pp[n][m] = ((2.0 * n - 1) * x * pp[n - 1][m] - (if (n - 2 >= m) b * pp[n - 2][m] else 0.0)) / sqrt((n * n - m * m).toDouble())
        }
        val s = maxOf(cc, 1e-12)
        for (n in 1..N) for (m in 0..n) {
            val prev = if (n - 1 >= m) sqrt((n * n - m * m).toDouble()) * pp[n - 1][m] else 0.0
            dp[n][m] = (n * x * pp[n][m] - prev) / s
        }
        var xx = 0.0; var yy = 0.0; var zz = 0.0
        val ar = REF / r
        var arn = ar * ar
        val lr = Math.toRadians(lon)
        var i = 0
        for (n in 1..N) {
            arn *= ar
            var xs = 0.0; var ys = 0.0; var zs = 0.0
            for (m in 0..n) {
                val g = c[i * 4] + dt * c[i * 4 + 2]; val h = c[i * 4 + 1] + dt * c[i * 4 + 3]
                i++
                val cm = cos(m * lr); val sm = sin(m * lr)
                val t1 = g * cm + h * sm
                xs += t1 * dp[n][m]; ys += m * (g * sm - h * cm) * pp[n][m]; zs += t1 * pp[n][m]
            }
            xx += arn * xs; yy += arn * ys; zz -= (n + 1) * arn * zs
        }
        yy /= s
        val psi = phic - Math.toRadians(lat)
        val xg = xx * cos(psi) - zz * sin(psi); val zg = xx * sin(psi) + zz * cos(psi)
        val hh = hypot(xg, yy)
        return Field(Math.toDegrees(atan2(yy, xg)), Math.toDegrees(atan2(zg, hh)), hh, hypot(hh, zg), xg, yy, zg)
    }

    fun declination(lat: Double, lon: Double, year: Double, altKm: Double = 0.0): Double = field(lat, lon, altKm, year)?.decDeg ?: 0.0

    companion object {
        private const val N = 12
        private const val A = 6378.137
        private const val F = 1 / 298.257223563
        private const val REF = 6371.2

        // WMM2025: g, h (nT), g', h' (nT/year) for n = 1..12, m = 0..n
        private val WMM2025 = doubleArrayOf(
        -29351.8, 0.0, 12.0, 0.0,  // 1 0
        -1410.8, 4545.4, 9.7, -21.5,  // 1 1
        -2556.6, 0.0, -11.6, 0.0,  // 2 0
        2951.1, -3133.6, -5.2, -27.7,  // 2 1
        1649.3, -815.1, -8.0, -12.1,  // 2 2
        1361.0, 0.0, -1.3, 0.0,  // 3 0
        -2404.1, -56.6, -4.2, 4.0,  // 3 1
        1243.8, 237.5, 0.4, -0.3,  // 3 2
        453.6, -549.5, -15.6, -4.1,  // 3 3
        895.0, 0.0, -1.6, 0.0,  // 4 0
        799.5, 278.6, -2.4, -1.1,  // 4 1
        55.7, -133.9, -6.0, 4.1,  // 4 2
        -281.1, 212.0, 5.6, 1.6,  // 4 3
        12.1, -375.6, -7.0, -4.4,  // 4 4
        -233.2, 0.0, 0.6, 0.0,  // 5 0
        368.9, 45.4, 1.4, -0.5,  // 5 1
        187.2, 220.2, 0.0, 2.2,  // 5 2
        -138.7, -122.9, 0.6, 0.4,  // 5 3
        -142.0, 43.0, 2.2, 1.7,  // 5 4
        20.9, 106.1, 0.9, 1.9,  // 5 5
        64.4, 0.0, -0.2, 0.0,  // 6 0
        63.8, -18.4, -0.4, 0.3,  // 6 1
        76.9, 16.8, 0.9, -1.6,  // 6 2
        -115.7, 48.8, 1.2, -0.4,  // 6 3
        -40.9, -59.8, -0.9, 0.9,  // 6 4
        14.9, 10.9, 0.3, 0.7,  // 6 5
        -60.7, 72.7, 0.9, 0.9,  // 6 6
        79.5, 0.0, -0.0, 0.0,  // 7 0
        -77.0, -48.9, -0.1, 0.6,  // 7 1
        -8.8, -14.4, -0.1, 0.5,  // 7 2
        59.3, -1.0, 0.5, -0.8,  // 7 3
        15.8, 23.4, -0.1, 0.0,  // 7 4
        2.5, -7.4, -0.8, -1.0,  // 7 5
        -11.1, -25.1, -0.8, 0.6,  // 7 6
        14.2, -2.3, 0.8, -0.2,  // 7 7
        23.2, 0.0, -0.1, 0.0,  // 8 0
        10.8, 7.1, 0.2, -0.2,  // 8 1
        -17.5, -12.6, 0.0, 0.5,  // 8 2
        2.0, 11.4, 0.5, -0.4,  // 8 3
        -21.7, -9.7, -0.1, 0.4,  // 8 4
        16.9, 12.7, 0.3, -0.5,  // 8 5
        15.0, 0.7, 0.2, -0.6,  // 8 6
        -16.8, -5.2, -0.0, 0.3,  // 8 7
        0.9, 3.9, 0.2, 0.2,  // 8 8
        4.6, 0.0, -0.0, 0.0,  // 9 0
        7.8, -24.8, -0.1, -0.3,  // 9 1
        3.0, 12.2, 0.1, 0.3,  // 9 2
        -0.2, 8.3, 0.3, -0.3,  // 9 3
        -2.5, -3.3, -0.3, 0.3,  // 9 4
        -13.1, -5.2, 0.0, 0.2,  // 9 5
        2.4, 7.2, 0.3, -0.1,  // 9 6
        8.6, -0.6, -0.1, -0.2,  // 9 7
        -8.7, 0.8, 0.1, 0.4,  // 9 8
        -12.9, 10.0, -0.1, 0.1,  // 9 9
        -1.3, 0.0, 0.1, 0.0,  // 10 0
        -6.4, 3.3, 0.0, 0.0,  // 10 1
        0.2, 0.0, 0.1, -0.0,  // 10 2
        2.0, 2.4, 0.1, -0.2,  // 10 3
        -1.0, 5.3, -0.0, 0.1,  // 10 4
        -0.6, -9.1, -0.3, -0.1,  // 10 5
        -0.9, 0.4, 0.0, 0.1,  // 10 6
        1.5, -4.2, -0.1, 0.0,  // 10 7
        0.9, -3.8, -0.1, -0.1,  // 10 8
        -2.7, 0.9, -0.0, 0.2,  // 10 9
        -3.9, -9.1, -0.0, -0.0,  // 10 10
        2.9, 0.0, 0.0, 0.0,  // 11 0
        -1.5, 0.0, -0.0, -0.0,  // 11 1
        -2.5, 2.9, 0.0, 0.1,  // 11 2
        2.4, -0.6, 0.0, -0.0,  // 11 3
        -0.6, 0.2, 0.0, 0.1,  // 11 4
        -0.1, 0.5, -0.1, -0.0,  // 11 5
        -0.6, -0.3, 0.0, -0.0,  // 11 6
        -0.1, -1.2, -0.0, 0.1,  // 11 7
        1.1, -1.7, -0.1, -0.0,  // 11 8
        -1.0, -2.9, -0.1, 0.0,  // 11 9
        -0.2, -1.8, -0.1, 0.0,  // 11 10
        2.6, -2.3, -0.1, 0.0,  // 11 11
        -2.0, 0.0, 0.0, 0.0,  // 12 0
        -0.2, -1.3, 0.0, -0.0,  // 12 1
        0.3, 0.7, -0.0, 0.0,  // 12 2
        1.2, 1.0, -0.0, -0.1,  // 12 3
        -1.3, -1.4, -0.0, 0.1,  // 12 4
        0.6, -0.0, -0.0, -0.0,  // 12 5
        0.6, 0.6, 0.1, -0.0,  // 12 6
        0.5, -0.1, -0.0, -0.0,  // 12 7
        -0.1, 0.8, 0.0, 0.0,  // 12 8
        -0.4, 0.1, 0.0, -0.0,  // 12 9
        -0.2, -1.0, -0.1, -0.0,  // 12 10
        -1.3, 0.1, -0.0, 0.0,  // 12 11
        -0.7, 0.2, -0.1, -0.1,  // 12 12
        )

        val DEFAULT = Wmm(2025.0, "WMM-2025", WMM2025)

        /** A WMM.COF text (as NOAA publishes it); null if it is not valid. */
        fun load(text: String): Wmm? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            val head = lines[0].trim().split(Regex("\\s+"))
            val ep = head.getOrNull(0)?.toDoubleOrNull() ?: return null
            if (ep < 1900 || ep > 2200) return null
            val out = DoubleArray(90 * 4)
            var k = 0
            for (l in lines.drop(1)) {
                if (l.startsWith("9999")) break
                val p = l.trim().split(Regex("\\s+"))
                if (p.size < 6) continue
                val n = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
                if (n !in 1..N || m !in 0..n || k >= 90) return null
                val idx = n * (n + 1) / 2 - 1 + m          // the same order as the table
                for (j in 0..3) out[idx * 4 + j] = p[2 + j].toDoubleOrNull() ?: return null
                k++
            }
            return if (k == 90) Wmm(ep, head.getOrNull(1) ?: "WMM", out) else null
        }

        /** Decimal year of a Unix time in ms (UTC). */
        fun decimalYear(timeMs: Long): Double {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
            val y = c.get(java.util.Calendar.YEAR)
            c.clear(); c.set(y, 0, 1); val a = c.timeInMillis
            c.clear(); c.set(y + 1, 0, 1); val b = c.timeInMillis
            return y + (timeMs - a).toDouble() / (b - a)
        }

        /** Declination (degrees, + east) with WMM2025 at (lat, lon) now (or at [timeMs]). */
        fun declination(lat: Double, lon: Double, timeMs: Long = System.currentTimeMillis(), altKm: Double = 0.0): Double =
            DEFAULT.declination(lat, lon, decimalYear(timeMs), altKm)
    }
}
