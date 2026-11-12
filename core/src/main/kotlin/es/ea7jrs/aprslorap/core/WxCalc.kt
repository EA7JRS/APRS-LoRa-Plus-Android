/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

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
}
