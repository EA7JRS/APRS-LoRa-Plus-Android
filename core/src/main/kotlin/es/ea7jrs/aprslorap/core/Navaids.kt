/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * VOR stations (108–118 MHz, always on, fixed and published position) to aim the rotor and to calibrate an SDR: the carrier measured
 * against the published frequency gives the receiver's error in ppm. List from OurAirports (public domain); positions and frequencies
 * may lag behind the national AIP.
 */
object Navaids {
    data class Vor(val ident: String, val name: String, val type: String, val khz: Int, val lat: Double, val lon: Double, val elevFt: Int?, val country: String) {
        val hz: Long get() = khz * 1000L
    }

    /** The app's list (tools/navaids/make_vor.py): ident;name;type;kHz;lat;lon;elevation ft;country. Lines with # are comments. */
    fun parse(text: String): List<Vor> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val f = line.split(';')
        if (f.size < 8) return@mapNotNull null
        val khz = f[3].trim().toIntOrNull() ?: return@mapNotNull null
        val lat = f[4].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[5].toDoubleOrNull() ?: return@mapNotNull null
        if (khz !in 108_000..118_000) return@mapNotNull null
        Vor(f[0].trim(), f[1].trim(), f[2].trim(), khz, lat, lon, f[6].trim().toIntOrNull(), f[7].trim())
    }.toList()

    data class Near(val vor: Vor, val km: Double, val bearing: Double, val elevationDeg: Double, val lineOfSightKm: Double) {
        /** Inside the radio horizon of both antennas (4/3 earth): it can be heard. */
        val inRange get() = km <= lineOfSightKm
    }

    /**
     * VORs within [maxKm], nearest first, with the bearing, the elevation angle from here (4/3 earth) and the radio horizon for an antenna
     * [antennaM] above the ground at [altM].
     */
    fun nearest(list: List<Vor>, lat: Double, lon: Double, altM: Double = 0.0, antennaM: Double = 5.0, maxKm: Double = 400.0, max: Int = 20): List<Near> =
        list.asSequence().map { v ->
            val d = Geo.distanceKm(lat, lon, v.lat, v.lon)
            val hv = (v.elevFt ?: 0) * 0.3048 + 5.0                                       // the antenna sits a few metres above the site
            val h = altM + antennaM
            val k = 4.0 / 3.0 * 6371.0
            val los = sqrt(2 * k * maxOf(h, 0.0) / 1000.0) + sqrt(2 * k * maxOf(hv, 0.0) / 1000.0)
            val el = Math.toDegrees(kotlin.math.atan2((hv - h) / 1000.0 - d * d / (2 * k), maxOf(d, 0.001)))
            Near(v, d, Geo.bearingDeg(lat, lon, v.lat, v.lon), el, los)
        }.filter { it.km <= maxKm }.sortedBy { it.km }.take(max).toList()

    /** Error of the receiver in ppm: + = the receiver tunes high (the carrier appears below where it should). */
    fun ppm(offsetHz: Double, freqHz: Double) = -offsetHz / freqHz * 1e6

    /**
     * Carrier finder for calibration: complex baseband centred on the tuned frequency (the app receiver gives 48 kS/s) in, FFT of [size]
     * points averaged over the frames, the strongest bin within ±[spanHz] refined between bins. [result] after a few seconds.
     */
    class PpmMeter(val rate: Double = 48_000.0, val size: Int = 16_384, val spanHz: Double = 5_000.0) {
        private val re = DoubleArray(size); private val im = DoubleArray(size)
        private val acc = DoubleArray(size)
        private val win = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
        private var fill = 0
        var frames = 0; private set

        fun reset() { acc.fill(0.0); fill = 0; frames = 0 }

        fun feed(xr: FloatArray, xi: FloatArray, n: Int) {
            for (i in 0 until n) {
                re[fill] = xr[i] * win[fill]; im[fill] = xi[i] * win[fill]
                if (++fill == size) {
                    Modems.fft(re, im)
                    for (k in 0 until size) acc[k] += re[k] * re[k] + im[k] * im[k]
                    frames++; fill = 0
                }
            }
        }

        data class Result(val offsetHz: Double, val snrDb: Double, val frames: Int)

        fun result(): Result? {
            if (frames == 0) return null
            val bin = rate / size
            val span = (spanHz / bin).toInt()
            fun at(k: Int) = acc[((k % size) + size) % size]
            var best = 0; var bestP = -1.0
            for (k in -span..span) if (at(k) > bestP) { bestP = at(k); best = k }
            // noise: median of the span
            val all = (-span..span).map { at(it) }.sorted()
            val noise = all[all.size / 2].coerceAtLeast(1e-30)
            val a = at(best - 1); val b = at(best); val c = at(best + 1)
            val den = a - 2 * b + c
            val frac = if (den != 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            return Result((best + frac) * bin, 10 * log10(bestP / noise), frames)
        }
    }
}
