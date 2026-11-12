/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** One satellite in view. [system]: G GPS, R GLONASS, E Galileo, C BeiDou, J QZSS, S SBAS, I NavIC, N other. */
data class SkySat(
    val system: Char, val svid: Int, val elevationDeg: Int?, val azimuthDeg: Int?, val snr: Int?, val used: Boolean,
    /** Signals received (tracker, NMEA 4.10+): bit 0 = L1 / E1 / B1 / G1, bit 1 = L5 / E5a / B2a, bit 2 = others (L2, E5b, E6, B3...). null = unknown. */
    val bands: Int? = null,
) {
    val l1: Boolean get() = bands != null && bands and 1 != 0
    val l5: Boolean get() = bands != null && bands and 2 != 0
}

/** Sky of a GNSS receiver (the tracker's, event `sky`, or the phone's): satellites, DOP and the estimated error. */
data class SkyView(
    val sats: List<SkySat>, val pdop: Double? = null, val hdop: Double? = null, val vdop: Double? = null, val errorM: Double? = null,
    val usedInFix: Int? = null,
) {
    val used: Int get() = usedInFix ?: sats.count { it.used }
    val inView: Int get() = sats.size
}

object GnssSky {
    /** Constellations in the order they are listed; colours as in common GNSS apps: GPS green, Galileo blue, BeiDou red, GLONASS grey. */
    data class System(val code: Char, val name: String, val prefix: String, val argb: Long)

    val SYSTEMS = listOf(
        System('G', "GPS", "", 0xFF00C853),
        System('E', "Galileo", "e", 0xFF448AFF),
        System('C', "BeiDou", "c", 0xFFFF5252),
        System('R', "GLONASS", "r", 0xFF9E9E9E),
        System('J', "QZSS", "q", 0xFFBDBDBD),
        System('I', "NavIC", "i", 0xFFBDBDBD),
        System('S', "SBAS", "s", 0xFFBDBDBD),
        System('N', "Other", "?", 0xFFBDBDBD),
    )

    fun system(code: Char): System = SYSTEMS.firstOrNull { it.code == code } ?: SYSTEMS.last()

    /** Number as GNSS apps show it: "16" GPS, "e16" Galileo, "r15" GLONASS slot (NMEA 65-96 -> 1-32), "c13" BeiDou... */
    fun label(s: SkySat): String {
        val n = if (s.system == 'R' && s.svid in 65..96) s.svid - 64 else s.svid
        return system(s.system).prefix + n
    }

    /** android.location.GnssStatus constellation type -> system code. */
    fun fromAndroid(constellation: Int): Char = when (constellation) {
        1 -> 'G'; 2 -> 'S'; 3 -> 'R'; 4 -> 'J'; 5 -> 'C'; 6 -> 'E'; 7 -> 'I'; else -> 'N'
    }

    /** Event `sky` of the tracker. */
    fun parse(o: Map<String, Any?>): SkyView {
        val sats = (o["s"] as? List<*>)?.mapNotNull { e ->
            val a = e as? List<*> ?: return@mapNotNull null
            if (a.size < 6) return@mapNotNull null
            val sys = (a[0] as? String)?.firstOrNull() ?: 'N'
            val svid = (a[1] as? Number)?.toInt() ?: return@mapNotNull null
            fun opt(i: Int) = (a[i] as? Number)?.toInt()?.takeIf { it >= 0 }
            SkySat(sys, svid, opt(2), opt(3), opt(4), ((a[5] as? Number)?.toInt() ?: 0) != 0, (a.getOrNull(6) as? Number)?.toInt())
        } ?: emptyList()
        return SkyView(sats, o.num("pd"), o.num("hd"), o.num("vd"), o.num("err"), o.int("used"))
    }

    /** PDOP / HDOP / VDOP of a GSA sentence (the phone gives them only through its NMEA), or null. */
    fun gsaDops(line: String): Triple<Double?, Double?, Double?>? {
        if (!Nmea.valid(line) || Nmea.type(line) != "GSA") return null
        val f = line.substringAfter('$').substringBefore('*').split(',')
        if (f.size < 18) return null
        return Triple(f[15].toDoubleOrNull(), f[16].toDoubleOrNull(), f[17].toDoubleOrNull())
    }

    /** Position of a satellite on a sky plot of radius 1 (centre = zenith, edge = horizon), with the top of the plot at [upDeg]. */
    fun plot(elevationDeg: Int, azimuthDeg: Int, upDeg: Double = 0.0): Pair<Double, Double> {
        val r = (90.0 - elevationDeg.coerceIn(0, 90)) / 90.0
        val a = Math.toRadians(azimuthDeg - upDeg)
        return r * sin(a) to -r * cos(a)
    }

    /** Quality of a signal for its colour on the sky plot: 2 strong (>= 30 dB-Hz), 1 medium (>= 20), 0 weak, -1 none. */
    fun strength(snr: Int?): Int = when {
        snr == null || snr <= 0 -> -1
        snr >= 30 -> 2
        snr >= 20 -> 1
        else -> 0
    }

    /** Satellites sorted as the bar chart shows them: by system (GPS, Galileo, BeiDou, GLONASS, others), then number. */
    fun sorted(sats: List<SkySat>): List<SkySat> = sats.sortedWith(compareBy({ SYSTEMS.indexOfFirst { s -> s.code == it.system }.let { i -> if (i < 0) 99 else i } }, { it.svid }))

    fun dopText(v: SkyView, fallbackHdop: Double?): String {
        fun f(d: Double?) = d?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        return if (v.pdop == null && v.vdop == null) f(v.hdop ?: fallbackHdop) else f(v.pdop) + "/" + f(v.hdop ?: fallbackHdop) + "/" + f(v.vdop)
    }
}
