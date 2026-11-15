/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Weather formulas shared by every APRS LoRa+ device (ported from `lib/plotter_core/src/wxcalc.cpp` and `wind.cpp` of the
 * plotter): dew point (Magnus), wind chill (Environment Canada / NWS 2001), heat index (Rothfusz, NWS), feels-like,
 * sea-level pressure and the Beaufort force.
 */
object WxCalc {
    private const val A = 17.625
    private const val B = 243.04

    /** Dew point (°C); null outside 1..100 % humidity. */
    fun dewPointC(tempC: Double, rhPct: Double): Double? {
        if (rhPct !in 1.0..100.0 || !tempC.isFinite()) return null
        val g = ln(rhPct / 100.0) + A * tempC / (B + tempC)
        return B * g / (A - g)
    }

    fun relHumidityPct(tempC: Double, dewC: Double): Double =
        minOf(100.0, 100.0 * exp(A * dewC / (B + dewC)) / exp(A * tempC / (B + tempC)))

    /** Wind chill (°C); only with tempC ≤ 10 and wind > 4.8 km/h, otherwise tempC. */
    fun windChillC(tempC: Double, windKmh: Double): Double {
        if (tempC > 10 || windKmh <= 4.8) return tempC
        val p = windKmh.pow(0.16)
        return minOf(tempC, 13.12 + 0.6215 * tempC - 11.37 * p + 0.3965 * tempC * p)
    }

    /** Heat index (°C); only with tempC ≥ 27 and humidity ≥ 40 %, otherwise tempC. */
    fun heatIndexC(tempC: Double, rhPct: Double): Double {
        if (tempC < 27 || rhPct !in 40.0..100.0) return tempC
        val f = tempC * 9 / 5 + 32; val r = rhPct
        var hi = -42.379 + 2.04901523 * f + 10.14333127 * r - 0.22475541 * f * r - 6.83783e-3 * f * f - 5.481717e-2 * r * r +
            1.22874e-3 * f * f * r + 8.5282e-4 * f * r * r - 1.99e-6 * f * f * r * r
        if (r > 85 && f <= 87) hi += (r - 85) / 10.0 * (87 - f) / 5.0
        return maxOf(tempC, (hi - 32) * 5 / 9)
    }

    /** Wind chill when cold, heat index when hot; [rhPct] / [windKmh] null = unknown. */
    fun feelsLikeC(tempC: Double, rhPct: Double?, windKmh: Double?): Double = when {
        windKmh != null && tempC <= 10 -> windChillC(tempC, windKmh)
        rhPct != null && tempC >= 27 -> heatIndexC(tempC, rhPct)
        else -> tempC
    }

    fun seaLevelPressureHpa(stationHpa: Double, altM: Double, tempC: Double): Double =
        stationHpa * exp(9.80665 * 0.0289644 * altM / (8.31446 * (tempC + 273.15 + 0.0065 * altM / 2)))

    private val BF_TOP = intArrayOf(0, 3, 6, 10, 16, 21, 27, 33, 40, 47, 55, 63)   // upper limit of each force, knots (WMO)

    /** Beaufort force 0..12 with the wind rounded to the knot. */
    fun beaufort(knots: Double): Int {
        val k = maxOf(knots, 0.0).roundToLong()
        for (f in BF_TOP.indices) if (k <= BF_TOP[f]) return f
        return 12
    }

    fun beaufortMs(ms: Double): Int = beaufort(ms / 0.514444)

    private val BF_NAMES = arrayOf("Calm", "Light air", "Light breeze", "Gentle breeze", "Moderate breeze", "Fresh breeze",
        "Strong breeze", "Near gale", "Gale", "Strong gale", "Storm", "Violent storm", "Hurricane force")
    fun beaufortName(force: Int): String = BF_NAMES[force.coerceIn(0, 12)]

    fun kmhToKn(kmh: Double): Double = kmh / 1.852

    /** Height of the convective cloud base over the station, metres (125 m per degree of spread). */
    fun cloudBaseM(tC: Double?, dewC: Double?): Double? = if (tC == null || dewC == null || dewC > tC) null else (tC - dewC) * 125.0

    fun trendName(d3h: Double): String = when {
        abs(d3h) < 1.0 -> "steady"
        abs(d3h) < 3.5 -> if (d3h > 0) "rising" else "falling"
        else -> if (d3h > 0) "rising fast" else "falling fast"
    }

    /** Pressure readings of one station; answers the change over 3 h (extrapolated when there is at least 1 h). */
    class PressureTrend {
        private val t = LongArray(N)
        private val p = DoubleArray(N)
        private var n = 0
        private var head = 0

        fun add(seconds: Long, hpa: Double) {
            if (hpa <= 0) return
            if (n > 0) {
                val last = (head + n - 1) % N
                if (seconds - t[last] < 300) { p[last] = hpa; return }      // one every 5 min at most: the last one is updated
            }
            if (n == N) { head = (head + 1) % N; n-- }
            val i = (head + n) % N
            t[i] = seconds; p[i] = hpa; n++
        }

        fun delta3h(nowSeconds: Long): Double? {
            if (n < 2) return null
            val last = (head + n - 1) % N
            if (nowSeconds - t[last] > 1800) return null
            var ref = -1
            for (k in 0 until n - 1) {
                val i = (head + k) % N
                if (t[last] - t[i] > 12000) continue
                ref = i
                break
            }
            if (ref < 0) return null
            val span = t[last] - t[ref]
            if (span < 3600) return null
            return (p[last] - p[ref]) * 10800.0 / span
        }

        private companion object { const val N = 40 }
    }

    /** Units of the interface: 0 metric (km, km/h, °C), 1 nautical (nm, kn, °C), 2 imperial (mi, mph, °F). */
    fun distFactor(system: Int): Pair<Double, String> = when (system) { 1 -> 1 / 1.852 to "nm"; 2 -> 1 / 1.609344 to "mi"; else -> 1.0 to "km" }
    fun speedFactor(system: Int): Pair<Double, String> = when (system) { 1 -> 1 / 1.852 to "kn"; 2 -> 1 / 1.609344 to "mph"; else -> 1.0 to "km/h" }
    fun tempShown(c: Double, system: Int): Double = if (system == 2) c * 9.0 / 5.0 + 32.0 else c
    fun tempLabel(system: Int): String = if (system == 2) "°F" else "°C"
}
