/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Weather arithmetic for what APRS weather stations, the tracker's sensors and the internet weather report.
 * Same as `wxcalc` of APRS LoRa+ Display; the Beaufort scale comes from the core of APRS LoRa+ Plotter.
 * Published formulas: dew point (Magnus, Alduchov and Eskridge 1996 coefficients), wind chill (JAG/TI 2001, the one used
 * in Canada and the USA), heat index (Rothfusz regression of the NWS with its two adjustments), pressure tendency over 3 h.
 */
object WxCalc {
    /** Beaufort force 0..12 from knots (wind rounded to the knot). */
    fun beaufort(knots: Double): Int {
        val top = intArrayOf(0, 3, 6, 10, 16, 21, 27, 33, 40, 47, 55, 63)
        val k = (if (knots < 0) 0.0 else knots).roundToLong()
        for (f in 0 until 12) if (k <= top[f]) return f
        return 12
    }

    private val NAMES = arrayOf("Calm", "Light air", "Light breeze", "Gentle breeze", "Moderate breeze", "Fresh breeze", "Strong breeze",
        "Near gale", "Gale", "Strong gale", "Storm", "Violent storm", "Hurricane force")
    fun beaufortName(force: Int): String = NAMES[force.coerceIn(0, 12)]
    fun kmhToKn(kmh: Double): Double = kmh / 1.852

    fun dewPointC(tC: Double?, rhPct: Double?): Double? {
        if (tC == null || rhPct == null || rhPct <= 0) return null
        val a = 17.625; val b = 243.04
        val g = ln(rhPct.coerceAtMost(100.0) / 100.0) + a * tC / (b + tC)
        return b * g / (a - g)
    }

    /** Only at 10 °C or less with more than 4.8 km/h of wind; else null. */
    fun windChillC(tC: Double?, windKmh: Double?): Double? {
        if (tC == null || windKmh == null || tC > 10 || windKmh <= 4.8) return null
        val p = windKmh.pow(0.16)
        return 13.12 + 0.6215 * tC - 11.37 * p + 0.3965 * tC * p
    }

    /** Only from 27 °C and 40 % of humidity; else null. */
    fun heatIndexC(tC: Double?, rhPct: Double?): Double? {
        if (tC == null || rhPct == null || tC < 27 || rhPct < 40) return null
        val t = tC * 9.0 / 5.0 + 32.0; val r = rhPct
        var hi = -42.379 + 2.04901523 * t + 10.14333127 * r - 0.22475541 * t * r - 0.00683783 * t * t - 0.05481717 * r * r +
            0.00122874 * t * t * r + 0.00085282 * t * r * r - 0.00000199 * t * t * r * r
        if (r < 13 && t in 80.0..112.0) hi -= (13 - r) / 4 * sqrt((17 - abs(t - 95)) / 17)
        else if (r > 85 && t in 80.0..87.0) hi += (r - 85) / 10 * (87 - t) / 5
        return (hi - 32.0) * 5.0 / 9.0
    }

    /** The one that applies, with its name ("wind chill" / "heat index"), or null when it feels like the temperature itself. */
    fun feelsLikeC(tC: Double?, rhPct: Double?, windKmh: Double?): Pair<Double, String>? =
        windChillC(tC, windKmh)?.let { it to "wind chill" } ?: heatIndexC(tC, rhPct)?.let { it to "heat index" }

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
