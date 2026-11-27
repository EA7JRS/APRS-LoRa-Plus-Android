/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * HF propagation from the space weather of now, with the formulas of EA7JRS/sat-aprs (server.ts calculateMufAndBands /
 * calculateLuf, server/propagacion.ts estadoBandas) and the same as the tracker's TELEMETRY > HF (pages_core.cpp): MUF (3000 km)
 * and LUF from the 10.7 cm flux, the sun's elevation here and the X-rays (D-layer absorption on the day side only, scaled with cos(zenith)^0.75 as SWPC's D-RAP; none at night). A band is
 * open up to 85 % of the MUF, fair up to the MUF, shut above and absorbed below the LUF; with a storm (Kp >= 4.5) what is open is
 * poor. The L1 solar-wind probe (DSCOVR / ACE: southward Bz and wind speed) raises the LUF (auroral / polar absorption) and the
 * effective storm level, often before the 3-hour Kp index catches up. Without an ionosonde the MUF is an estimate.
 */
object HfProp {
    data class Estimate(val muf: Double, val luf: Double)
    enum class State(val short: String) { SHUT("shut"), FAIR("fair"), OPEN("open"), ABSORBED("abs"), POOR("poor") }
    val BANDS = listOf("160m" to 1.85, "80m" to 3.65, "60m" to 5.36, "40m" to 7.1, "30m" to 10.12,
        "20m" to 14.15, "17m" to 18.1, "15m" to 21.2, "12m" to 24.93, "10m" to 28.5)

    fun estimate(sfi: Double, sunElevDeg: Double, xrayWm2: Double, bzNt: Double? = null, windKms: Double? = null): Estimate {
        val s = if (sunElevDeg > 0) sin(Math.toRadians(sunElevDeg)) else 0.0
        val mufBase = 7.0 + (sfi - 65) * 0.06
        val muf = if (sunElevDeg > 0) mufBase + s * (12.0 + (sfi - 70) * 0.12) else max(4.5, mufBase * 0.85)
        val lufBase = 1.5 + (sfi - 65) * 0.005
        var luf = if (sunElevDeg > 0) lufBase + s * (2.0 + (sfi - 70) * 0.015) else max(1.0, lufBase * 0.7)
        val abs = when { xrayWm2 > 1e-4 -> 8.0; xrayWm2 > 1e-5 -> 4.0; xrayWm2 > 1e-6 -> 1.5; xrayWm2 > 1e-7 -> 0.5; else -> 0.0 }
        luf += abs * s.pow(0.75)                  // D-RAP: cos(zenith)^0.75 on the day side, nothing at night
        val storm = windStorm(bzNt, windKms)      // L1 solar-wind disturbance: auroral / polar absorption raises the LUF
        if (storm > 4.0) luf += 0.3 * (storm - 4.0)
        return Estimate(muf, max(0.5, luf))
    }

    /**
     * Estimated geomagnetic storm level (0..9, Kp-like) from the L1 solar-wind probe (DSCOVR / ACE at the Sun-Earth L1 point):
     * a southward Bz with a fast wind drives the disturbance and the auroral / polar HF absorption, often before the 3-hour Kp
     * index catches up. 0 when Bz is northward or missing. Uses the rectified solar-wind electric field Ey = -Bz*V (mV/m).
     */
    fun windStorm(bzNt: Double?, windKms: Double?): Double {
        val bz = bzNt ?: return 0.0
        if (bz >= 0.0) return 0.0
        val v = windKms ?: 400.0
        return (-bz * v * 1e-3).coerceIn(0.0, 9.0)
    }

    /** Effective geomagnetic level for the band states: the worse of the measured index (Hp30 / Kp) and the L1 wind estimate. */
    fun effectiveKp(geomagKp: Double, bzNt: Double?, windKms: Double?): Double = max(geomagKp, windStorm(bzNt, windKms))

    fun state(mhz: Double, e: Estimate, kp: Double): State = when {
        mhz < e.luf -> State.ABSORBED
        mhz > e.muf -> State.SHUT
        kp >= 4.5 -> State.POOR
        mhz <= e.muf * 0.85 -> State.OPEN
        else -> State.FAIR
    }

    /** "C3.1" -> 3.1e-6 W/m2; 0 if it is not an X-ray class. */
    fun xrayFlux(cls: String): Double {
        val base = when (cls.firstOrNull()?.uppercaseChar()) { 'A' -> 1e-8; 'B' -> 1e-7; 'C' -> 1e-6; 'M' -> 1e-5; 'X' -> 1e-4; else -> return 0.0 }
        return base * (cls.drop(1).toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0)
    }

    /** The highest band open (or poor, in a storm) for DX; null if none. */
    fun bestDx(e: Estimate, kp: Double): String? = BANDS.lastOrNull { state(it.second, e, kp).let { s -> s == State.OPEN || s == State.POOR } }?.first
}
