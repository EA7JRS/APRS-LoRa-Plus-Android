/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Total correction and compass deviation, as `deviation` of the APRS LoRa+ plotter core (same tests). Ct = Zv - Zc (true azimuth of a body, from
 * [Celnav], minus its compass bearing); deviation = Ct - variation. Nautical sign: + = E (the compass reads low). The deviation card fits the
 * observations (compass heading, deviation) by least squares to the approximate coefficients A..E: d = A + B sin H + C cos H + D sin 2H + E cos 2H
 * (with fewer or badly spread headings, A + B sin + C cos, or A only).
 */
object Deviation {
    /** a - b in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }
    fun totalCorrection(trueAzDeg: Double, compassBearingDeg: Double) = angleDiff(trueAzDeg, compassBearingDeg)
    fun deviationFromCt(ctDeg: Double, variationDeg: Double) = angleDiff(ctDeg, variationDeg)

    data class Point(val compassHdg: Double, val dev: Double)

    class Card {
        val points = ArrayList<Point>()
        val coef = DoubleArray(5)
        /** 0 no data, 1 A only, 3 A B C, 5 all five. */
        var terms = 0; private set
        var rmsDeg = 0.0; private set

        /** Adds an observation; one within 5 degrees of the same heading is replaced; full, the oldest goes. */
        fun add(compassHdgDeg: Double, devDeg: Double) {
            val h = ((compassHdgDeg % 360.0) + 360.0) % 360.0
            points.indexOfFirst { abs(angleDiff(it.compassHdg, h)) < 5.0 }.let { if (it >= 0) points.removeAt(it) }
            if (points.size == MAX) points.removeAt(0)
            points.add(Point(h, devDeg))
            fit()
        }
        fun remove(i: Int) { if (i in points.indices) { points.removeAt(i); fit() } }
        fun clear() { points.clear(); fit() }

        fun at(compassHdgDeg: Double): Double {
            val r = Math.toRadians(compassHdgDeg)
            return coef[0] + coef[1] * sin(r) + coef[2] * cos(r) + coef[3] * sin(2 * r) + coef[4] * cos(2 * r)
        }
        /** Compass heading to steer for a magnetic heading (H + d(H) = magnetic). */
        fun compassFromMagnetic(magDeg: Double): Double {
            var h = magDeg
            repeat(6) { h = (((magDeg - at(h)) % 360.0) + 360.0) % 360.0 }
            return h
        }

        fun fit() {
            coef.fill(0.0); terms = 0; rmsDeg = 0.0
            if (points.isEmpty()) return
            for (m in intArrayOf(5, 3, 1)) {
                if (points.size < m) continue
                val c = solve(m) ?: continue
                if (c.any { abs(it) > 45.0 }) continue
                c.copyInto(coef); terms = m
                break
            }
            if (terms == 0) return
            rmsDeg = sqrt(points.sumOf { val e = at(it.compassHdg) - it.dev; e * e } / points.size)
        }

        private fun solve(m: Int): DoubleArray? {
            val a = Array(m) { DoubleArray(m + 1) }
            for (p in points) {
                val r = Math.toRadians(p.compassHdg)
                val f = doubleArrayOf(1.0, sin(r), cos(r), sin(2 * r), cos(2 * r))
                for (j in 0 until m) { for (k in 0 until m) a[j][k] += f[j] * f[k]; a[j][m] += f[j] * p.dev }
            }
            for (col in 0 until m) {
                var piv = col
                for (r in col + 1 until m) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 0.05 * points.size) return null      // a term with almost no information
                if (piv != col) { val t = a[col]; a[col] = a[piv]; a[piv] = t }
                for (r in 0 until m) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (k in col..m) a[r][k] -= f * a[col][k]
                }
            }
            return DoubleArray(m) { a[it][m] / a[it][it] }
        }

        /** "hdg10:dev10;..." in tenths of a degree, as the plotter keeps it. */
        fun encode(): String = points.joinToString(";") { "${Math.round(it.compassHdg * 10)}:${Math.round(it.dev * 10)}" }
        fun decode(s: String) {
            points.clear()
            for (part in s.split(';')) {
                val kv = part.split(':'); if (kv.size != 2) continue
                val h = kv[0].toIntOrNull() ?: continue; val d = kv[1].toIntOrNull() ?: continue
                if (h in 0 until 3600 && abs(d) <= 450) { if (points.size == MAX) points.removeAt(0); points.add(Point(h / 10.0, d / 10.0)) }
            }
            fit()
        }
        companion object { const val MAX = 24 }
    }
}
