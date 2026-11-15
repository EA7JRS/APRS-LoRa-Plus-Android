/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Celestial navigation to nautical-almanac precision (0.1'), ported from lib/plotter_core/src/celnav.cpp of the APRS LoRa+
 * plotter with the same reference values: GHA and declination of the Sun (truncated VSOP87, nutation, aberration), GHA of
 * Aries, SHA and declination of the 57 navigational stars and Polaris (proper motion, precession, nutation, annual
 * aberration; PyEphem / Hipparcos catalogue), sextant altitude corrections, sight reduction and fix. Tables GENERATED from
 * the plotter core: do not edit them by hand.
 */
object Celnav {
    data class NavStar(val name: String, val mag: Double, val raHours: Double, val decDeg: Double, val pmRaMas: Double, val pmDecMas: Double)
    data class GhaDec(val gha: Double, val dec: Double)
    data class ShaDec(val sha: Double, val dec: Double)
    data class AltAz(val alt: Double, val az: Double)
    /** hc / zn from the assumed position; intercept in nm (+ towards the body); lat / lon of the intercept point. */
    data class Sight(val hc: Double, val zn: Double, val interceptNm: Double, val lat: Double, val lon: Double)
    enum class Limb { CENTER, LOWER, UPPER }

    private const val AS = Math.PI / (180.0 * 3600.0)
    private const val DELTA_T = 69.2

    val STARS: List<NavStar> = listOf(
        NavStar("Acamar", 2.88, 2.97102074, -40.30467239, -53.53, 25.71),
        NavStar("Achernar", 0.45, 1.62856849, -57.23675744, 88.02, -40.08),
        NavStar("Acrux", 0.77, 12.44330439, -63.09909168, -35.37, -14.73),
        NavStar("Adhara", 1.50, 6.97709679, -28.97208374, 2.63, 2.29),
        NavStar("Aldebaran", 0.87, 4.59867740, 16.50930138, 62.78, -189.36),
        NavStar("Alioth", 1.76, 12.90048595, 55.95982123, 111.74, -8.99),
        NavStar("Alkaid", 1.85, 13.79234379, 49.31326512, -121.23, -15.56),
        NavStar("Alnair", 1.73, 22.13721819, -46.96097539, 127.60, -147.91),
        NavStar("Alnilam", 1.69, 5.60355929, -1.20191983, 1.49, -1.06),
        NavStar("Alphard", 1.99, 9.45978980, -8.65860253, -14.49, 33.25),
        NavStar("Alphecca", 2.22, 15.57813004, 26.71469307, 120.38, -89.44),
        NavStar("Alpheratz", 2.07, 0.13979405, 29.09043197, 135.68, -162.95),
        NavStar("Altair", 0.76, 19.84638864, 8.86832203, 536.82, 385.54),
        NavStar("Ankaa", 2.40, 0.43806972, -42.30598144, 232.76, -353.64),
        NavStar("Antares", 1.06, 16.49012803, -26.43200250, -10.16, -23.21),
        NavStar("Arcturus", -0.05, 14.26102001, 19.18241038, -1093.45, -1999.40),
        NavStar("Atria", 1.91, 16.81108191, -69.02771505, 17.85, -32.92),
        NavStar("Avior", 1.86, 8.37523211, -59.50948307, -25.34, 22.72),
        NavStar("Bellatrix", 1.64, 5.41885085, 6.34970223, -8.75, -13.28),
        NavStar("Betelgeuse", 0.45, 5.91952924, 7.40706274, 27.33, 10.86),
        NavStar("Canopus", -0.62, 6.39919718, -52.69566045, 19.99, 23.67),
        NavStar("Capella", 0.08, 5.27815528, 45.99799106, 75.52, -427.13),
        NavStar("Deneb", 1.25, 20.69053187, 45.28033800, 1.56, 1.55),
        NavStar("Denebola", 2.14, 11.81766043, 14.57206038, -499.02, -113.78),
        NavStar("Diphda", 2.04, 0.72649196, -17.98660457, 232.79, 32.71),
        NavStar("Dubhe", 1.81, 11.06213019, 61.75103324, -136.46, -35.25),
        NavStar("Elnath", 1.65, 5.43819816, 28.60745000, 23.28, -174.22),
        NavStar("Eltanin", 2.24, 17.94343608, 51.48889500, -8.52, -23.05),
        NavStar("Enif", 2.38, 21.73643281, 9.87501126, 30.02, 1.38),
        NavStar("Fomalhaut", 1.17, 22.96084626, -29.62223601, 329.22, -164.22),
        NavStar("Gacrux", 1.59, 12.51943314, -57.11321175, 27.94, -264.33),
        NavStar("Gienah", 2.58, 12.26343617, -17.54192948, -159.58, 22.31),
        NavStar("Hadar", 0.61, 14.06372347, -60.37303932, -33.96, -25.06),
        NavStar("Hamal", 2.01, 2.11955753, 23.46242310, 190.73, -145.77),
        NavStar("Kaus Australis", 1.79, 18.40286620, -34.38461611, -39.61, -124.05),
        NavStar("Kochab", 2.07, 14.84509068, 74.15550496, -32.29, 11.91),
        NavStar("Markab", 2.49, 23.07934827, 15.20526441, 61.10, -42.56),
        NavStar("Menkar", 2.54, 3.03799227, 4.08973396, -11.81, -78.76),
        NavStar("Menkent", 2.06, 14.11137457, -36.36995451, -519.29, -517.87),
        NavStar("Miaplacidus", 1.67, 9.21999318, -69.71720776, -157.66, 108.91),
        NavStar("Mirfak", 1.79, 3.40538065, 49.86117958, 24.11, -26.01),
        NavStar("Nunki", 2.05, 18.92109048, -26.29672225, 13.87, -52.65),
        NavStar("Peacock", 1.94, 20.42746051, -56.73509009, 7.71, -86.15),
        NavStar("Pollux", 1.16, 7.75526397, 28.02619865, -625.69, -45.95),
        NavStar("Procyon", 0.40, 7.65503283, 5.22499314, -716.57, -1034.58),
        NavStar("Rasalhague", 2.08, 17.58224183, 12.56003481, 110.08, -222.61),
        NavStar("Regulus", 1.36, 10.13953074, 11.96720709, -249.40, 4.91),
        NavStar("Rigel", 0.18, 5.24229787, -8.20164055, 1.87, -0.56),
        NavStar("Rigil Kentaurus", -0.01, 14.66013779, -60.83397588, -3678.19, 481.84),
        NavStar("Sabik", 2.43, 17.17296871, -15.72491023, 41.16, 97.65),
        NavStar("Schedar", 2.24, 0.67512237, 56.53733107, 50.36, -32.17),
        NavStar("Shaula", 1.62, 17.56014444, -37.10382115, -8.90, -29.95),
        NavStar("Sirius", -1.44, 6.75247697, -16.71611569, -546.01, -1223.08),
        NavStar("Spica", 0.98, 13.41988313, -11.16132203, -42.50, -31.73),
        NavStar("Suhail", 2.23, 9.13326624, -43.43258935, -23.21, 14.28),
        NavStar("Vega", 0.03, 18.61564903, 38.78369185, 201.02, 287.46),
        NavStar("Zubenelgenubi", 2.75, 14.84797587, -16.04177819, -105.69, -69.00),
        NavStar("Polaris", 1.97, 2.53030100, 89.26410949, 44.22, -11.74),
    )

    private val L0 = arrayOf(doubleArrayOf(175347046.0, 0.0, 0.0), doubleArrayOf(3341656.0, 4.6692568, 6283.07585), doubleArrayOf(34894.0, 4.6261, 12566.1517), doubleArrayOf(3497.0, 2.7441, 5753.3849), doubleArrayOf(3418.0, 2.8289, 3.5231), doubleArrayOf(3136.0, 3.6277, 77713.7715), doubleArrayOf(2676.0, 4.4181, 7860.4194), doubleArrayOf(2343.0, 6.1352, 3930.2097), doubleArrayOf(1324.0, 0.7425, 11506.7698), doubleArrayOf(1273.0, 2.0371, 529.691), doubleArrayOf(1199.0, 1.1096, 1577.3435), doubleArrayOf(990.0, 5.233, 5884.927), doubleArrayOf(902.0, 2.045, 26.298), doubleArrayOf(857.0, 3.508, 398.149), doubleArrayOf(780.0, 1.179, 5223.694), doubleArrayOf(753.0, 2.533, 5507.553), doubleArrayOf(505.0, 4.583, 18849.228), doubleArrayOf(492.0, 4.205, 775.523), doubleArrayOf(357.0, 2.92, 0.067), doubleArrayOf(317.0, 5.849, 11790.629), doubleArrayOf(284.0, 1.899, 796.298), doubleArrayOf(271.0, 0.315, 10977.079), doubleArrayOf(243.0, 0.345, 5486.778), doubleArrayOf(206.0, 4.806, 2544.314), doubleArrayOf(205.0, 1.869, 5573.143), doubleArrayOf(202.0, 2.458, 6069.777), doubleArrayOf(156.0, 0.833, 213.299), doubleArrayOf(132.0, 3.411, 2942.463), doubleArrayOf(126.0, 1.083, 20.775), doubleArrayOf(115.0, 0.645, 0.98), doubleArrayOf(103.0, 0.636, 4694.003), doubleArrayOf(102.0, 0.976, 15720.839), doubleArrayOf(102.0, 4.267, 7.114), doubleArrayOf(99.0, 6.21, 2146.17), doubleArrayOf(98.0, 0.68, 155.42), doubleArrayOf(86.0, 5.98, 161000.69), doubleArrayOf(85.0, 1.3, 6275.96), doubleArrayOf(85.0, 3.67, 71430.7), doubleArrayOf(80.0, 1.81, 17260.15))
    private val L1 = arrayOf(doubleArrayOf(628331966747.0, 0.0, 0.0), doubleArrayOf(206059.0, 2.678235, 6283.07585), doubleArrayOf(4303.0, 2.6351, 12566.1517), doubleArrayOf(425.0, 1.59, 3.523), doubleArrayOf(119.0, 5.796, 26.298), doubleArrayOf(109.0, 2.966, 1577.344), doubleArrayOf(93.0, 2.59, 18849.23), doubleArrayOf(72.0, 1.14, 529.69), doubleArrayOf(68.0, 1.87, 398.15), doubleArrayOf(67.0, 4.41, 5507.55), doubleArrayOf(59.0, 2.89, 5223.69), doubleArrayOf(56.0, 2.17, 155.42), doubleArrayOf(45.0, 0.4, 796.3), doubleArrayOf(36.0, 0.47, 775.52), doubleArrayOf(29.0, 2.65, 7.11), doubleArrayOf(21.0, 5.34, 0.98), doubleArrayOf(19.0, 1.85, 5486.78), doubleArrayOf(19.0, 4.97, 213.3), doubleArrayOf(17.0, 2.99, 6275.96), doubleArrayOf(16.0, 0.03, 2544.31))
    private val L2 = arrayOf(doubleArrayOf(52919.0, 0.0, 0.0), doubleArrayOf(8720.0, 1.0721, 6283.0758), doubleArrayOf(309.0, 0.867, 12566.152), doubleArrayOf(27.0, 0.05, 3.52), doubleArrayOf(16.0, 5.19, 26.3), doubleArrayOf(16.0, 3.68, 155.42), doubleArrayOf(10.0, 0.76, 18849.23), doubleArrayOf(9.0, 2.06, 77713.77), doubleArrayOf(7.0, 0.83, 775.52), doubleArrayOf(5.0, 4.66, 1577.34))
    private val L3 = arrayOf(doubleArrayOf(289.0, 5.844, 6283.076), doubleArrayOf(35.0, 0.0, 0.0), doubleArrayOf(17.0, 5.49, 12566.15))

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun n2pi(a: Double): Double { val r = a % (2 * Math.PI); return if (r < 0) r + 2 * Math.PI else r }
    private fun n360(a: Double): Double { val r = a % 360.0; return if (r < 0) r + 360 else r }
    private fun series(t: Array<DoubleArray>, tau: Double): Double { var s = 0.0; for (x in t) s += x[0] * cos(x[1] + x[2] * tau); return s }

    fun julianDay(timeMs: Long): Double = timeMs / 86_400_000.0 + 2440587.5
    private fun tt(jd: Double) = (jd + DELTA_T / 86400.0 - 2451545.0) / 36525.0

    private class Nut(val dpsi: Double, val deps: Double, val eps: Double)
    private fun nutation(t: Double): Nut {
        val om = rad(125.04452 - 1934.136261 * t); val l = rad(280.4665 + 36000.7698 * t); val lm = rad(218.3165 + 481267.8813 * t)
        val dpsi = (-17.20 * sin(om) - 1.32 * sin(2 * l) - 0.23 * sin(2 * lm) + 0.21 * sin(2 * om)) * AS
        val deps = (9.20 * cos(om) + 0.57 * cos(2 * l) + 0.10 * cos(2 * lm) - 0.09 * cos(2 * om)) * AS
        return Nut(dpsi, deps, (84381.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) * AS + deps)
    }

    /** Geometric longitude of the Sun (rad) and distance (AU). */
    private fun sunGeometric(t: Double): DoubleArray {
        val tau = t / 10.0
        val l = (series(L0, tau) + tau * (series(L1, tau) + tau * (series(L2, tau) + tau * (series(L3, tau) + tau * -114.0)))) * 1e-8
        val lon = n2pi(l + Math.PI) - 0.09033 * AS
        val m = rad(357.52911 + 35999.05029 * t); val e = 0.016708634 - 0.000042037 * t
        val c = rad((1.914602 - 0.004817 * t) * sin(m) + 0.019993 * sin(2 * m) + 0.000289 * sin(3 * m))
        return doubleArrayOf(lon, 1.000001018 * (1 - e * e) / (1 + e * cos(m + c)))
    }

    private fun gast(jd: Double): Double {
        val d = jd - 2451545.0; val tu = d / 36525.0
        val gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * tu * tu - tu * tu * tu / 38710000.0
        val n = nutation(tt(jd))
        return n2pi(rad(gmst % 360.0) + n.dpsi * cos(n.eps))
    }

    /** GHA of Aries in degrees (jd: Julian day, UTC). */
    fun ariesGha(jd: Double): Double = deg(gast(jd))

    fun sunGhaDec(jd: Double): GhaDec {
        val t = tt(jd); val g = sunGeometric(t); val n = nutation(t)
        val lam = g[0] + n.dpsi - 20.4898 * AS / g[1]
        val ra = atan2(cos(n.eps) * sin(lam), cos(lam))
        return GhaDec(deg(n2pi(gast(jd) - ra)), deg(asin(sin(n.eps) * sin(lam))))
    }

    fun sunSemiDiameterArcmin(jd: Double): Double = 959.63 / 60.0 / sunGeometric(tt(jd))[1]

    fun findStar(name: String): NavStar? = STARS.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Apparent place of the date. */
    fun starShaDec(s: NavStar, jd: Double): ShaDec {
        val t = tt(jd); val years = t * 100.0
        val d0 = rad(s.decDeg + s.pmDecMas / 3.6e6 * years)
        val a0 = rad(s.raHours * 15.0) + rad(s.pmRaMas / 3.6e6 * years) / cos(rad(s.decDeg))
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * AS
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * AS
        val th = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * AS
        val aa = cos(d0) * sin(a0 + zeta)
        val bb = cos(th) * cos(d0) * cos(a0 + zeta) - sin(th) * sin(d0)
        val cc = sin(th) * cos(d0) * cos(a0 + zeta) + cos(th) * sin(d0)
        val a = atan2(aa, bb) + z; val d = asin(cc)
        val n = nutation(t); val sun = sunGeometric(t)[0]
        val e = 0.016708634 - 0.000042037 * t; val peri = rad(102.93735 + 1.71946 * t); val k = 20.49552 * AS
        val ca = cos(a); val sa = sin(a); val cd = cos(d); val sd = sin(d); val ce = cos(n.eps); val te = tan(n.eps)
        val da = (ce + sin(n.eps) * sa * sd / cd) * n.dpsi - ca * sd / cd * n.deps -
            k * (ca * cos(sun) * ce + sa * sin(sun)) / cd + e * k * (ca * cos(peri) * ce + sa * sin(peri)) / cd
        val dd = sin(n.eps) * ca * n.dpsi + sa * n.deps -
            k * (cos(sun) * ce * (te * cd - sa * sd) + ca * sd * sin(sun)) +
            e * k * (cos(peri) * ce * (te * cd - sa * sd) + ca * sd * sin(peri))
        return ShaDec(deg(n2pi(-(a + da))), deg(d + dd))
    }

    /** True altitude and azimuth of a body from a position (lon + east). */
    fun bodyAltAz(gha: Double, dec: Double, lat: Double, lon: Double): AltAz {
        val ha = rad(gha + lon); val de = rad(dec); val la = rad(lat)
        val alt = deg(asin(sin(la) * sin(de) + cos(la) * cos(de) * cos(ha)))
        return AltAz(alt, n360(deg(atan2(-sin(ha) * cos(de), sin(de) * cos(la) - cos(de) * sin(la) * cos(ha)))))
    }

    fun dipArcmin(eyeHeightM: Double): Double = if (eyeHeightM > 0) 1.76 * sqrt(eyeHeightM) else 0.0

    /** Mean refraction (Bennett) for the apparent altitude. */
    fun refractionArcmin(apparentAltDeg: Double): Double { val h = maxOf(apparentAltDeg, -1.0); return 1.0 / tan(rad(h + 7.31 / (h + 4.4))) }

    /** Observed altitude from the sextant altitude; index error + when the sextant reads high. */
    fun observedAltitude(sextantAltDeg: Double, indexErrArcmin: Double, eyeHeightM: Double, limb: Limb, semiDiameterArcmin: Double, parallaxArcmin: Double): Double {
        val ha = sextantAltDeg - indexErrArcmin / 60.0 - dipArcmin(eyeHeightM) / 60.0
        val ho = ha - refractionArcmin(ha) / 60.0 + parallaxArcmin * cos(rad(ha)) / 60.0
        return when (limb) { Limb.LOWER -> ho + semiDiameterArcmin / 60.0; Limb.UPPER -> ho - semiDiameterArcmin / 60.0; Limb.CENTER -> ho }
    }

    fun reduceSight(estLat: Double, estLon: Double, gha: Double, dec: Double, observedAltDeg: Double): Sight {
        val c = bodyAltAz(gha, dec, estLat, estLon)
        val a = (observedAltDeg - c.alt) * 60.0
        // great-circle destination from the assumed position along the azimuth (or its reciprocal)
        val brg = rad(if (a >= 0) c.zn() else n360(c.zn() + 180.0)); val dist = rad(abs(a) / 60.0); val la = rad(estLat)
        val lat2 = asin(sin(la) * cos(dist) + cos(la) * sin(dist) * cos(brg))
        val lon2 = rad(estLon) + atan2(sin(brg) * sin(dist) * cos(la), cos(dist) - sin(la) * sin(lat2))
        return Sight(c.alt, c.az, a, deg(lat2), ((deg(lon2) + 540.0) % 360.0) - 180.0)
    }
    private fun AltAz.zn() = az

    /** Least-squares fix from two or more sights around the assumed position; null when they are nearly parallel. */
    fun fixFromSights(estLat: Double, estLon: Double, sights: List<Sight>): Pair<Double, Double>? {
        if (sights.size < 2) return null
        var a = 0.0; var b = 0.0; var c = 0.0; var u = 0.0; var v = 0.0
        for (s in sights) { val cz = cos(rad(s.zn)); val sz = sin(rad(s.zn)); a += cz * cz; b += cz * sz; c += sz * sz; u += cz * s.interceptNm; v += sz * s.interceptNm }
        val det = a * c - b * b
        if (det < 0.03) return null
        val dn = (u * c - v * b) / det; val de = (v * a - u * b) / det
        return Pair(estLat + dn / 60.0, estLon + de / 60.0 / cos(rad(estLat)))
    }
}
