/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Sun, Moon and Earth: what follows from the [Celnav] ephemerides (Meeus) and the physical constants of the three bodies. Ported from
 * `lib/plotter_core/src/ephem.cpp` of the plotter with the same reference values, so every APRS LoRa+ device gives the same numbers:
 * true Moon phase (not the mean synodic month), next phases, perigee and apogee, eclipses, equilibrium tide index, moonbounce (EME),
 * Sun distance and irradiance, seasons, perihelion, Carrington rotation, clear-sky UV index, optical and radio horizon, normal gravity.
 * Times are Julian days in UTC ([Celnav.julianDay]).
 */
object Ephem {
    const val AU_KM = 149597870.7
    const val MOON_RADIUS_KM = 1737.4
    const val MOON_MEAN_KM = 384400.0
    const val SOLAR_CONSTANT = 1361.0
    const val SYNODIC_MONTH = 29.530588853
    const val LIGHT_KM_S = 299792.458
    private const val WGS_A = 6378137.0
    private const val WGS_F = 1.0 / 298.257223563
    private const val MOON_MASS_FRAC = 0.0121505843   // m_luna / (m_tierra + m_luna)

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun n360(a: Double): Double { val r = a % 360.0; return if (r < 0) r + 360 else r }
    private fun n180(a: Double): Double { val r = n360(a); return if (r > 180) r - 360 else r }

    private fun obliquity(jd: Double): Double = rad(23.4392911 - 0.0130042 * (jd - 2451545.0) / 36525.0)

    /** Ecliptic longitude and latitude of date (degrees) from the apparent GHA and declination. */
    private fun ecliptic(jd: Double, gha: Double, decDeg: Double): Pair<Double, Double> {
        val ra = rad(Celnav.ariesGha(jd) - gha); val dec = rad(decDeg); val eps = obliquity(jd)
        val lon = n360(deg(atan2(sin(ra) * cos(eps) + tan(dec) * sin(eps), cos(ra))))
        val lat = deg(asin(sin(dec) * cos(eps) - cos(dec) * sin(eps) * sin(ra)))
        return lon to lat
    }

    private class SunMoon(val sunLon: Double, val moonLon: Double, val moonLat: Double, val moonKm: Double, val sunAu: Double, val sep: Double)

    private fun sunMoon(jd: Double): SunMoon {
        val s = Celnav.sunGhaDec(jd); val m = Celnav.moonGhaDec(jd)
        val se = ecliptic(jd, s.gha, s.dec); val me = ecliptic(jd, m.gha, m.dec)
        val c = sin(rad(s.dec)) * sin(rad(m.dec)) + cos(rad(s.dec)) * cos(rad(m.dec)) * cos(rad(s.gha - m.gha))
        return SunMoon(se.first, me.first, me.second, m.dist, sunDistanceAu(jd), deg(acos(c.coerceIn(-1.0, 1.0))))
    }

    private fun elongation(jd: Double): Double { val s = sunMoon(jd); return n360(s.moonLon - s.sunLon) }

    /** First extremum (minimum if [minimum]) of [f] after [jd]: steps of [step] days, refined by golden section to ~1 minute. */
    // Extremo (minimo si [minimum]) de [f] despues de [jd]: cruce de cero de la derivada (f(t+h)-f(t-h), h = step/4) con
    // biseccion. Robusto en extremos casi planos (perihelio/afelio), donde la seccion aurea sobre el valor erraba el instante.
    private fun nextExtremum(jd: Double, step: Double, maxSteps: Int, minimum: Boolean, f: (Double) -> Double): Pair<Double, Double> {
        val sgn = if (minimum) 1.0 else -1.0
        val h = step * 0.25
        fun deriv(t: Double) = sgn * (f(t + h) - f(t - h))
        var a = jd; var da = deriv(a)
        for (i in 0 until maxSteps) {
            val b = a + step; val db = deriv(b)
            if (da < 0.0 && db >= 0.0) {
                var lo = a; var hi = b; var flo = da; var k = 0
                while (k < 50 && hi - lo > 1e-6) {
                    val mid = 0.5 * (lo + hi); val fm = deriv(mid)
                    if (flo * fm <= 0.0) hi = mid else { lo = mid; flo = fm }
                    k++
                }
                val x = 0.5 * (lo + hi)
                return x to f(x)
            }
            a = b; da = db
        }
        return 0.0 to 0.0
    }

    // ---------------------------------------------------------------- Moon

    /** [elongDeg] 0 new, 90 first quarter, 180 full, 270 last quarter; [illum] 0..1 with the true phase angle; [ageDays] since the last true new moon. */
    data class MoonPhase(val elongDeg: Double, val illum: Double, val ageDays: Double, val phase8: Int, val waxing: Boolean)

    fun moonPhaseTrue(jd: Double): MoonPhase {
        val s = sunMoon(jd)
        val elong = n360(s.moonLon - s.sunLon)
        val psi = rad(s.sep); val r = s.sunAu * AU_KM
        val i = atan2(r * sin(psi), s.moonKm - r * cos(psi))
        var t = jd - elong / 360.0 * SYNODIC_MONTH
        for (k in 0 until 6) {
            val d = n180(elongation(t))
            t -= d / 360.0 * SYNODIC_MONTH
            if (abs(d) < 1e-5) break
        }
        if (t > jd) t -= SYNODIC_MONTH
        return MoonPhase(elong, (1 + cos(i)) / 2, jd - t, ((floor(elong / 45.0 + 0.5).toInt() % 8) + 8) % 8, elong < 180)
    }

    /** Julian day of the next main phase after [jd]: [quarter] 0 new, 1 first quarter, 2 full, 3 last quarter. */
    fun nextMoonPhase(jd: Double, quarter: Int): Double {
        val target = 90.0 * (((quarter % 4) + 4) % 4)
        var ahead = n360(target - elongation(jd))
        if (ahead < 1e-6) ahead = 360.0
        var t = jd + ahead / 360.0 * SYNODIC_MONTH
        for (k in 0 until 8) {
            val d = n180(elongation(t) - target)
            t -= d / 360.0 * SYNODIC_MONTH
            if (abs(d) < 1e-6) break
        }
        return if (t <= jd + 1e-6) nextMoonPhase(jd + 1.0, quarter) else t
    }

    /** Next perigee ([perigee]) or apogee after [jd]: Julian day and geocentric distance in km. */
    fun nextMoonApsis(jd: Double, perigee: Boolean): Pair<Double, Double> = nextExtremum(jd, 1.0, 40, perigee) { Celnav.moonGhaDec(it).dist }

    fun moonAngularDiameterArcmin(distKm: Double): Double = 2 * deg(asin(MOON_RADIUS_KM / distKm)) * 60.0

    enum class EclipseKind(val label: String) {
        NONE("No eclipse"), LUNAR_PENUMBRAL("Penumbral lunar eclipse"), LUNAR_PARTIAL("Partial lunar eclipse"), LUNAR_TOTAL("Total lunar eclipse"),
        SOLAR_PARTIAL("Partial solar eclipse"), SOLAR_ANNULAR("Annular solar eclipse"), SOLAR_TOTAL("Total solar eclipse");
        val lunar: Boolean get() = this == LUNAR_PENUMBRAL || this == LUNAR_PARTIAL || this == LUNAR_TOTAL
    }

    /**
     * [jd] is the opposition or conjunction in longitude (within minutes of the maximum). [magnitude]: umbral magnitude for the Moon
     * (penumbral for a penumbral one); gamma for the Sun. A lunar eclipse is seen from wherever the Moon is up; for a solar one only its
     * existence somewhere on Earth and its type on the centre line are given, not the local circumstances.
     */
    data class Eclipse(val kind: EclipseKind, val jd: Double, val magnitude: Double)

    fun eclipseAtSyzygy(jdS: Double, fullMoon: Boolean): Eclipse {
        val s = sunMoon(jdS)
        val hp = Celnav.moonHpArcmin(s.moonKm); val sdm = Celnav.moonSdArcmin(s.moonKm); val sds = Celnav.sunSemiDiameterArcmin(jdS)
        val hps = 8.794 / 60.0 / s.sunAu
        val beta = abs(s.moonLat) * 60.0 * 0.9963
        if (fullMoon) {
            val umbra = 1.02 * (0.99834 * hp - sds + hps); val penumbra = 1.02 * (0.99834 * hp + sds + hps)
            val mu = (umbra + sdm - beta) / (2 * sdm); val mp = (penumbra + sdm - beta) / (2 * sdm)
            return when {
                mu >= 1 -> Eclipse(EclipseKind.LUNAR_TOTAL, jdS, mu)
                mu > 0 -> Eclipse(EclipseKind.LUNAR_PARTIAL, jdS, mu)
                mp > 0 -> Eclipse(EclipseKind.LUNAR_PENUMBRAL, jdS, mp)
                else -> Eclipse(EclipseKind.NONE, jdS, 0.0)
            }
        }
        val gamma = beta / (hp - hps)
        return when {
            gamma < 0.9972 -> Eclipse(if (sdm >= sds) EclipseKind.SOLAR_TOTAL else EclipseKind.SOLAR_ANNULAR, jdS, gamma)
            gamma < 0.9972 + (sds + sdm) / (hp - hps) -> Eclipse(EclipseKind.SOLAR_PARTIAL, jdS, gamma)
            else -> Eclipse(EclipseKind.NONE, jdS, 0.0)
        }
    }

    /** Next lunar ([lunar]) or solar eclipse after [jd], looking up to [maxLunations] lunations ahead; null if none. */
    fun nextEclipse(jd: Double, lunar: Boolean, maxLunations: Int = 14): Eclipse? {
        var t = jd
        repeat(maxLunations) {
            t = nextMoonPhase(t, if (lunar) 2 else 0)
            val e = eclipseAtSyzygy(t, lunar)
            if (e.kind != EclipseKind.NONE) return e
            t += 20
        }
        return null
    }

    /**
     * Equilibrium tide: tide-raising force of Moon and Sun together against that of a mean spring tide (= 100). It follows the phase
     * (springs at new and full moon, neaps at the quarters) and the distances (more at perigee). A guide to the day's coefficient, not
     * a height or time prediction for any port.
     */
    fun tideForceIndex(jd: Double): Double {
        val s = sunMoon(jd)
        val m = (MOON_MEAN_KM / s.moonKm).pow(3); val su = 0.4595 / s.sunAu.pow(3)
        return 100.0 * sqrt(m * m + su * su + 2 * m * su * cos(2 * rad(s.sep))) / 1.4595
    }

    /** Moonbounce: two-way path loss (dB, radar equation, albedo 0.065), echo delay (s), Doppler of one's own echo (Hz), topocentric range (km). */
    data class EmeInfo(val pathLossDb: Double, val echoDelayS: Double, val dopplerHz: Double, val rangeKm: Double)

    fun emeInfo(jd: Double, lat: Double, lon: Double, freqHz: Double): EmeInfo {
        fun range(t: Double): Double {
            val m = Celnav.moonGhaDec(t)
            val la = rad(lat); val e2 = WGS_F * (2 - WGS_F)
            val n = WGS_A / 1000.0 / sqrt(1 - e2 * sin(la) * sin(la))
            val ox = n * cos(la) * cos(rad(lon)); val oy = n * cos(la) * sin(rad(lon)); val oz = n * (1 - e2) * sin(la)
            val ml = rad(-m.gha); val md = rad(m.dec)
            val mx = m.dist * cos(md) * cos(ml); val my = m.dist * cos(md) * sin(ml); val mz = m.dist * sin(md)
            return sqrt((mx - ox) * (mx - ox) + (my - oy) * (my - oy) + (mz - oz) * (mz - oz))
        }
        val r = range(jd)
        val dt = 30.0 / 86400.0
        val rate = (range(jd + dt) - range(jd - dt)) / 60.0
        val lambda = LIGHT_KM_S * 1000.0 / freqHz; val d = r * 1000.0
        val sigma = 0.065 * Math.PI * (MOON_RADIUS_KM * 1000.0) * (MOON_RADIUS_KM * 1000.0)
        val loss = 10 * log10((4 * Math.PI).pow(3) * d * d * d * d / (sigma * lambda * lambda))
        return EmeInfo(loss, 2 * r / LIGHT_KM_S, -2 * rate / LIGHT_KM_S * freqHz, r)
    }

    // ---------------------------------------------------------------- Sun

    fun sunDistanceAu(jd: Double): Double = (959.63 / 60.0) / Celnav.sunSemiDiameterArcmin(jd)
    fun sunAngularDiameterArcmin(jd: Double): Double = 2 * Celnav.sunSemiDiameterArcmin(jd)

    // Distancia geocentrica Tierra-Sol (AU): celnav situa el Sol respecto al baricentro Tierra-Luna; el geocentro esta
    // desplazado f*R_luna hacia la Luna (f = m_luna/(m_tierra+m_luna)), bamboleo de ~±4700 km que decide el instante del
    // perihelio/afelio oficial (USNO/IMCCE/OAN); con la distancia al baricentro (lisa) se iba hasta ~1 dia.
    private fun sunDistanceGeocentricAu(jd: Double): Double {
        val s = Celnav.sunGhaDec(jd); val m = Celnav.moonGhaDec(jd)
        val rs = sunDistanceAu(jd); val rm = m.dist / AU_KM
        fun vec(ghaDeg: Double, decDeg: Double, r: Double): DoubleArray {
            val hh = rad(-ghaDeg); val dd = rad(decDeg)
            return doubleArrayOf(r * cos(dd) * cos(hh), r * cos(dd) * sin(hh), r * sin(dd))
        }
        val sv = vec(s.gha, s.dec, rs); val mv = vec(m.gha, m.dec, rm)
        val x = sv[0] + MOON_MASS_FRAC * mv[0]; val y = sv[1] + MOON_MASS_FRAC * mv[1]; val z = sv[2] + MOON_MASS_FRAC * mv[2]
        return sqrt(x * x + y * y + z * z)
    }
    /** W/m² outside the atmosphere, normal to the Sun. */
    fun solarIrradianceToa(jd: Double): Double { val r = sunDistanceAu(jd); return SOLAR_CONSTANT / (r * r) }

    /** Clear-sky UV index (Allaart et al., 2004, usual form): Sun altitude, distance in AU, ozone in Dobson units. */
    fun clearSkyUvIndex(sunAltDeg: Double, distAu: Double, ozoneDu: Double = 300.0): Double {
        if (sunAltDeg <= 0 || distAu <= 0 || ozoneDu <= 0) return 0.0
        return 12.5 * sin(rad(sunAltDeg)).pow(2.42) * (ozoneDu / 300.0).pow(-1.23) / (distAu * distAu)
    }

    /** Synodic rotation number of the Sun (27.2753 d), with decimals. */
    fun carringtonRotation(jd: Double): Double = (jd - 2398140.2270) / 27.2752316

    /** Next equinox or solstice after [jd]: Julian day and season (0 March, 1 June, 2 September, 3 December). */
    fun nextSeason(jd: Double): Pair<Double, Int> {
        fun sunLon(t: Double): Double { val s = Celnav.sunGhaDec(t); return ecliptic(t, s.gha, s.dec).first }
        val lon = sunLon(jd)
        val k = (floor(lon / 90.0).toInt() + 1) % 4
        var t = jd + n360(k * 90.0 - lon) / 360.0 * 365.2422
        for (i in 0 until 8) {
            val d = n180(sunLon(t) - k * 90.0)
            t -= d / 360.0 * 365.2422
            if (abs(d) < 1e-7) break
        }
        return t to k
    }

    /** Next perihelion ([perihelion]) or aphelion of the Earth: Julian day and distance in AU, geocentric (with the lunar
     *  wobble, like USNO / IMCCE / OAN; validated against astropy/ERFA to under 2.5 h). */
    fun nextEarthApsis(jd: Double, perihelion: Boolean): Pair<Double, Double> = nextExtremum(jd, 4.0, 120, perihelion) { sunDistanceGeocentricAu(it) }

    // ---------------------------------------------------------------- Earth

    /** Geometric horizon, no refraction (km). */
    fun horizonKm(heightM: Double): Double = if (heightM > 0) sqrt(2 * 6371.0088 * heightM / 1000.0 + (heightM / 1000.0) * (heightM / 1000.0)) else 0.0
    /** Radio horizon with the standard VHF / UHF refraction (4/3 Earth radius), km. */
    fun radioHorizonKm(heightM: Double): Double = if (heightM > 0) sqrt(2 * (4.0 / 3.0) * 6371.0088 * heightM / 1000.0) else 0.0
    fun radioRangeKm(h1M: Double, h2M: Double): Double = radioHorizonKm(h1M) + radioHorizonKm(h2M)
    /** Geographic range of a light (nautical miles). */
    fun opticalRangeNm(eyeM: Double, lightM: Double): Double = 2.03 * (sqrt(maxOf(eyeM, 0.0)) + sqrt(maxOf(lightM, 0.0)))

    /** Normal gravity, m/s² (WGS84 Somigliana + free air). */
    fun normalGravity(latDeg: Double, heightM: Double): Double {
        val s2 = sin(rad(latDeg)).pow(2)
        return 9.7803253359 * (1 + 0.00193185265241 * s2) / sqrt(1 - 0.00669437999013 * s2) - 3.086e-6 * heightM
    }

    /** Geocentric radius of the WGS84 ellipsoid at that latitude (m). */
    fun earthRadiusAtM(latDeg: Double): Double {
        val b = WGS_A * (1 - WGS_F); val c = cos(rad(latDeg)); val s = sin(rad(latDeg))
        val n = (WGS_A * WGS_A * c).pow(2) + (b * b * s).pow(2)
        val d = (WGS_A * c).pow(2) + (b * s).pow(2)
        return sqrt(n / d)
    }

    /** Julian day (UTC) → Unix ms. */
    fun jdToMs(jd: Double): Long = Math.round((jd - 2440587.5) * 86_400_000.0)
}
