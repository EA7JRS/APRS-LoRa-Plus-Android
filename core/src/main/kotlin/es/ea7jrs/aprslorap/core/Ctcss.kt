/*
 * Copyright (C) 2026 Manuel - EA7JRS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package es.ea7jrs.aprslorap.core

/**
 * CTCSS (sub-audible tone) detector for FM: a Goertzel bank over the 38 standard EIA tones. Feed it the demodulated
 * audio (where the sub-audible tone survives) and it reports the tone in use, 0 when none dominates. Pure Kotlin,
 * unit-tested with synthetic tones.
 */
object Ctcss {

    /** The 38 standard CTCSS tones, Hz. */
    val TONES = doubleArrayOf(
        67.0, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8,
        123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 162.2, 167.9, 173.8, 179.9, 186.2, 192.8, 203.5,
        210.7, 218.1, 225.7, 233.6, 241.8, 250.3,
    )

    /**
     * Running detector over [rate] audio. Every [windowSec] it scores the tone bank; [tone] is the dominant CTCSS tone
     * (Hz) or 0 when none stands out (it must carry a real share of the energy and beat the other bins).
     */
    class Detector(private val rate: Double, windowSec: Double = 0.3) {
        private val win = (rate * windowSec).toInt().coerceAtLeast(512)
        private val buf = FloatArray(win)
        private var pos = 0
        var tone = 0.0
            private set

        fun reset() { pos = 0; tone = 0.0 }

        fun feed(x: FloatArray, n: Int) {
            var i = 0
            while (i < n) {
                val take = minOf(n - i, win - pos)
                System.arraycopy(x, i, buf, pos, take); pos += take; i += take
                if (pos >= win) { evaluate(); pos = 0 }
            }
        }

        private fun evaluate() {
            var energy = 0.0
            for (v in buf) energy += (v * v).toDouble()
            var best = 0.0; var bestP = 0.0; var total = 0.0
            for (f in TONES) { val p = Dsp.goertzel(buf, win, f, rate); total += p; if (p > bestP) { bestP = p; best = f } }
            val others = (total - bestP) / (TONES.size - 1)
            // dominant: well above the other bins and a real share of the audio energy (not just noise picking a bin)
            tone = if (bestP > 6.0 * others && bestP > 0.01 * energy) best else 0.0
        }
    }
}
