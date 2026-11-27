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

    // -----------------------------------------------------------------------
    // Band-conditions cards (13 bands), the same assessment as sat-aprs
    // (PropagacionMonitor.tsx calculateBandStatus). Texts in English for the app.
    // -----------------------------------------------------------------------
    enum class Quality { SLATE, AMBER, EMERALD, CYAN, RED }
    enum class BandIcon { CLOSED, WARN, OK, PEAK }

    /** One band's configuration: centre frequency (MHz), SFU needed and target, display name, category and suggested-flux note. */
    data class BandCfg(val id: String, val freq: Double, val sfiMin: Int, val targetSfi: Int,
                       val name: String, val category: String, val sfiLabel: String)

    /** Assessment of one band for the card grid. */
    data class BandCard(val cfg: BandCfg, val status: String, val quality: Quality, val icon: BandIcon,
                        val desc: String, val pct: Int) {
        val id get() = cfg.id
        val freq get() = cfg.freq
        val sfiMin get() = cfg.sfiMin
        val name get() = cfg.name
        val category get() = cfg.category
        val sfiLabel get() = cfg.sfiLabel
    }

    /** The 13 bands of the card grid, low to high. Categories: low / medium / high / vhf. */
    val BAND_GRID = listOf(
        BandCfg("160m", 1.8, 60, 100, "160 m (1.8 MHz)", "low", "Suggested SFU: 60."),
        BandCfg("80m", 3.5, 65, 110, "80 m (3.5 MHz)", "low", "Suggested SFU: 65."),
        BandCfg("60m", 5.3, 65, 120, "60 m (5.3 MHz)", "low", "Suggested SFU: 65. REMER national band."),
        BandCfg("40m", 7.05, 70, 120, "40 m (7 MHz)", "low", "Suggested SFU: 70. National links."),
        BandCfg("30m", 10.12, 75, 140, "30 m (10.1 MHz)", "medium", "Suggested SFU: 75. Great for CW."),
        BandCfg("20m", 14.15, 80, 150, "20 m (14 MHz)", "medium", "Suggested SFU: 80. Main daytime band."),
        BandCfg("17m", 18.12, 95, 160, "17 m (18.1 MHz)", "medium", "Suggested SFU: 95. Global coverage."),
        BandCfg("15m", 21.22, 105, 170, "15 m (21.0 MHz)", "medium", "Suggested SFU: 105."),
        BandCfg("12m", 24.94, 110, 175, "12 m (24.9 MHz)", "high", "Suggested SFU: 110. Experimental daytime band."),
        BandCfg("11m", 27.2, 115, 180, "11 m (CB / 27 MHz)", "high", "Suggested SFU: 115. Citizens Band (CB)."),
        BandCfg("10m", 28.5, 120, 180, "10 m (28 MHz)", "high", "Suggested SFU: 120. Great for daytime DX."),
        BandCfg("8m", 40.1, 130, 200, "8 m (experimental / 40 MHz)", "vhf", "Suggested SFU: 130. Experimental use."),
        BandCfg("6m", 50.1, 140, 220, "6 m (50.0 MHz)", "vhf", "Suggested SFU: 140. 'The Magic Band' of VHF."),
    )

    private fun d2(x: Double): String {
        val r = Math.round(x * 100).toInt()
        return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
    }

    /**
     * Status of one band for the card grid: the same window logic as [state] but with the richer wording and the storm /
     * sporadic-E / marginal-opening cases of sat-aprs. [muf] and [luf] are the single MUF/LUF (measured if an ionosonde is
     * near, otherwise estimated), [sfi] the 10.7 cm flux and [kp] the effective geomagnetic level (Hp30 / Kp or the L1 wind).
     */
    fun bandCard(cfg: BandCfg, sfi: Double, muf: Double, luf: Double, kp: Double): BandCard {
        val pct = minOf(100, Math.round(sfi / cfg.targetSfi * 100).toInt())
        val storm = kp >= 4.5
        val minor = kp >= 3.5 && kp < 4.5
        fun card(status: String, q: Quality, ic: BandIcon, desc: String) = BandCard(cfg, status, q, ic, desc, pct)

        if (cfg.freq > muf) {
            if (cfg.id == "6m" || cfg.id == "8m") {
                return if (sfi > 140 && !storm)
                    card("Sporadic-E opening", Quality.AMBER, BandIcon.WARN,
                        "Above the base MUF, but sporadic-E ionisation clouds allow quick contacts.")
                else card("Closed (above MUF)", Quality.SLATE, BandIcon.CLOSED,
                    "Frequency above the Maximum Usable Frequency. Signals pass through the ionosphere into space.")
            }
            if ((cfg.id == "10m" || cfg.id == "11m" || cfg.id == "12m") && sfi > 120 && !storm)
                return card("Marginal opening", Quality.AMBER, BandIcon.WARN,
                    "Slightly above the calculated MUF. Short sporadic contacts toward equatorial areas.")
            return card("Closed (above MUF)", Quality.SLATE, BandIcon.CLOSED,
                "Inactive. Above the Maximum Usable Frequency (now ${d2(muf)} MHz). Waves do not refract.")
        }
        if (cfg.freq < luf) {
            return if (storm)
                card("Blackout / absorbed", Quality.RED, BandIcon.CLOSED,
                    "Severe storm absorption in the D layer. Below the LUF (${d2(luf)} MHz). HF REMER nets degraded.")
            else card("Absorbed (below LUF)", Quality.SLATE, BandIcon.CLOSED,
                "Below the current LUF of ${d2(luf)} MHz. Waves are fully absorbed by collisions in the D layer.")
        }
        if (storm) return card("Open with severe noise", Quality.RED, BandIcon.WARN,
            "Refractive but unusable from extreme noise. Geomagnetic storm (Kp ${d2(kp)}) brings heavy QRN and fading.")
        if (minor) return card("Operational (severe fading)", Quality.AMBER, BandIcon.WARN,
            "Usable but unstable links from ionospheric scintillation and fast fading.")
        val window = muf - luf
        val pos = if (window > 0) (cfg.freq - luf) / window * 100 else 50.0
        return when {
            pos > 70 -> card("Excellent / optimum", Quality.CYAN, BandIcon.PEAK,
                "Optimum FOT. Very low path loss near the upper limit (MUF: ${d2(muf)} MHz). Strong, stable signals.")
            pos > 30 -> card("Fully operational", Quality.EMERALD, BandIcon.OK,
                "Very stable. The band sits well inside the refractive window. Low noise and good attenuation.")
            else -> card("Partial operation", Quality.AMBER, BandIcon.WARN,
                "Open but with moderate attenuation. Very close to the lower LUF limit (${d2(luf)} MHz). Needs more power.")
        }
    }

    /** All 13 band cards for the grid, with the given space weather and MUF/LUF. */
    fun bandCards(sfi: Double, muf: Double, luf: Double, kp: Double): List<BandCard> =
        BAND_GRID.map { bandCard(it, sfi, muf, luf, kp) }

    // -----------------------------------------------------------------------
    // Ionosondes (GIRO network, via the KC2G service). Measured foF2 / MUF.
    // -----------------------------------------------------------------------
    /** One ionosonde reading. [whenMs] is the sample time in epoch ms; [distanceKm] is filled by [nearestIonosonde]. */
    data class Ionosonde(val name: String, val code: String, val lat: Double, val lon: Double,
                         val fof2: Double, val muf3000: Double?, val hmf2: Double?, val cs: Double?,
                         val whenMs: Long, val distanceKm: Double? = null)

    const val IONOSONDE_URL = "https://prop.kc2g.com/api/stations.json"

    private fun numOf(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        else -> null
    }

    private fun isoToMs(t: String): Long? {
        val hasZone = Regex("(Z|[+-]\\d\\d:?\\d\\d)$").containsMatchIn(t)
        val s = (if (hasZone) t else "${t}Z").replace(' ', 'T')
        return try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        catch (_: Exception) { try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) { null } }
    }

    /** Parse the KC2G stations list; drops entries without position, time or foF2. Longitudes come 0..360 from GIRO. */
    fun readIonosondes(json: Any?): List<Ionosonde> {
        val arr = json as? List<*> ?: return emptyList()
        val out = ArrayList<Ionosonde>()
        for (e in arr) {
            val m = e as? Map<*, *> ?: continue
            val st = (m["station"] as? Map<*, *>) ?: m
            val lat = numOf(st["latitude"]) ?: continue
            var lon = numOf(st["longitude"]) ?: continue
            val whenMs = isoToMs((m["time"] ?: "").toString()) ?: continue
            val fof2 = numOf(m["fof2"]) ?: continue
            if (lon > 180) lon -= 360
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || fof2 <= 0 || fof2 > 30) continue
            val muf = numOf(m["mufd"])
            out.add(Ionosonde(
                name = (st["name"] ?: st["code"] ?: "Ionosonde").toString().take(40),
                code = (st["code"] ?: "").toString().take(10),
                lat = lat, lon = lon, fof2 = fof2,
                muf3000 = muf?.takeIf { it in 0.0..60.0 },
                hmf2 = numOf(m["hmf2"]), cs = numOf(m["cs"]), whenMs = whenMs,
            ))
        }
        return out
    }

    /** Convenience: parse the JSON text and read the stations. */
    fun readIonosondes(jsonText: String): List<Ionosonde> =
        try { readIonosondes(Json.parse(jsonText)) } catch (_: Exception) { emptyList() }

    /**
     * The nearest ionosonde with a recent, trustworthy reading. Beyond [maxKm] the measurement no longer represents the
     * station; older than [maxAgeMs] it is stale; a confidence score (cs) below 40 is a doubtful autoscale and is skipped.
     */
    fun nearestIonosonde(stations: List<Ionosonde>, lat: Double, lon: Double, nowMs: Long,
                         maxKm: Double = 1500.0, maxAgeMs: Long = 2 * 3600_000L): Ionosonde? {
        var best: Ionosonde? = null
        for (e in stations) {
            if (nowMs - e.whenMs > maxAgeMs || e.whenMs > nowMs + 15 * 60_000L) continue
            val cs = e.cs
            if (cs != null && cs >= 0 && cs < 40) continue
            val d = Geo.distanceKm(lat, lon, e.lat, e.lon)
            if (d > maxKm) continue
            if (best == null || d < (best!!.distanceKm ?: Double.MAX_VALUE)) best = e.copy(distanceKm = d)
        }
        return best
    }
}
