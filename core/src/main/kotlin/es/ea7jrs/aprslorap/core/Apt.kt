// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// NOAA APT (Automatic Picture Transmission) image decoder, from the NFM audio of the RTL-SDR receiver. NOAA 15/18/19 send on
// 137 MHz an FM signal whose audio is a 2400 Hz subcarrier amplitude-modulated by the image: 2 lines per second, 2080 words
// (pixels) per line = 4160 words/s. Each line carries two video channels (A and B) with a sync and a telemetry wedge each.
// This recovers the AM envelope, resamples to the 4160 Hz word rate, finds the line start with the sync-A pattern and emits each
// 2080-pixel line (0..255). Building the picture and the false colour are done in the app. Reference: the APT format (no code copied).
package es.ea7jrs.aprslorap.core

import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

object Apt {

    const val WORDS_PER_LINE = 2080
    const val PIXEL_RATE = 4160.0                                   // words per second (2080 * 2 lines/s)
    const val SUBCARRIER = 2400.0

    // Channel A / B video spans (word indices within the 2080-word line)
    const val CH_A_START = 86; const val CH_A_END = 995
    const val CH_B_START = 1126; const val CH_B_END = 2035

    /** Sync A: 7 cycles of 1040 Hz (4 words/cycle: 2 low, 2 high) with lead/trail, as ±1 for the correlator (39 words). */
    private val SYNC_A: DoubleArray = DoubleArray(39).also { s ->
        for (i in 0 until 4) s[i] = -1.0
        for (c in 0 until 7) { s[4 + c * 4] = -1.0; s[4 + c * 4 + 1] = -1.0; s[4 + c * 4 + 2] = 1.0; s[4 + c * 4 + 3] = 1.0 }
        for (i in 32 until 39) s[i] = -1.0
    }

    /**
     * Streaming decoder. Feed NFM audio; it emits one [onLine] per video line, 2080 values 0..255 (A and B channels inside).
     */
    class Decoder(private val sampleRate: Double, private val onLine: (IntArray) -> Unit) {
        // 2400 Hz quadrature AM envelope, low-passed
        private var phase = 0.0
        private val dphi = 2.0 * Math.PI * SUBCARRIER / sampleRate
        private var lpI = 0.0; private var lpQ = 0.0
        private val lpA = (1.0 - Math.exp(-2.0 * Math.PI * (PIXEL_RATE / 2) / sampleRate))   // one-pole ~ pixel bandwidth
        // resample envelope to 4160 word/s
        private val wordStep = PIXEL_RATE / sampleRate
        private var wordPhase = 0.0
        // AGC: slow min/max tracking to map to 0..255
        private var lo = 0.0; private var hi = 1e-9; private var seeded = false
        // line buffer: a sliding window of words; find sync then cut a 2080-word line
        private val win = DoubleArray(WORDS_PER_LINE + SYNC_A.size)
        private var wn = 0
        private var sinceLine = 0

        var lines = 0
            private set

        fun process(x: FloatArray, count: Int) {
            for (i in 0 until count) {
                val s = x[i].toDouble()
                val c = cos(phase); val sn = sin(phase)
                phase += dphi; if (phase > 2 * Math.PI) phase -= 2 * Math.PI
                lpI += lpA * (s * c - lpI)
                lpQ += lpA * (s * sn - lpQ)
                val env = sqrt(lpI * lpI + lpQ * lpQ)
                wordPhase += wordStep
                if (wordPhase >= 1.0) { wordPhase -= 1.0; pushWord(env) }
            }
        }

        private fun pushWord(env: Double) {
            // AGC envelope -> 0..255
            if (!seeded) { lo = env; hi = env + 1e-9; seeded = true }
            hi += (if (env > hi) 0.02 else 0.0005) * (env - hi)
            lo += (if (env < lo) 0.02 else 0.0005) * (env - lo)
            val span = (hi - lo).coerceAtLeast(1e-9)
            val v = ((env - lo) / span * 255.0).coerceIn(0.0, 255.0)
            if (wn < win.size) win[wn++] = v else { System.arraycopy(win, 1, win, 0, win.size - 1); win[win.size - 1] = v }
            sinceLine++
            if (wn >= win.size && sinceLine >= WORDS_PER_LINE) tryLine()
        }

        private fun tryLine() {
            // correlate SYNC_A over the first part of the window to find the line start offset
            var bestOff = 0; var best = -1e18
            val maxOff = win.size - WORDS_PER_LINE
            for (off in 0..maxOff) {
                var acc = 0.0
                for (k in SYNC_A.indices) acc += SYNC_A[k] * (win[off + k] - 128.0)
                if (acc > best) { best = acc; bestOff = off }
            }
            val out = IntArray(WORDS_PER_LINE) { round(win[bestOff + it]).toInt().coerceIn(0, 255) }
            onLine(out); lines++
            // drop the consumed line, keep the tail for the next search
            val keep = win.size - (bestOff + WORDS_PER_LINE)
            if (keep > 0) System.arraycopy(win, bestOff + WORDS_PER_LINE, win, 0, keep)
            wn = keep.coerceAtLeast(0)
            sinceLine = 0
        }
    }

    /** APT audio of some lines (each 2080 words 0..255, sync already inside), for tests. Continuous-phase 2400 Hz AM. */
    fun modulate(lines: List<IntArray>, sampleRate: Double, amp: Float = 0.8f): FloatArray {
        val spw = sampleRate / PIXEL_RATE
        val total = round(lines.size * WORDS_PER_LINE * spw).toInt()
        val out = FloatArray(total)
        var phase = 0.0; var pos = 0; var w = 0
        for (line in lines) for (word in 0 until WORDS_PER_LINE) {
            val pn = 0.1 + 0.9 * (line[word].coerceIn(0, 255) / 255.0)       // AM depth, never fully zero
            val end = round((w + 1) * spw).toInt().coerceAtMost(total)
            while (pos < end) { phase += 2.0 * Math.PI * SUBCARRIER / sampleRate; out[pos++] = (amp * pn * cos(phase)).toFloat() }
            w++
        }
        return out
    }

    /** A test line: sync A (words 0..38) then a horizontal ramp, so a decode can be checked. */
    fun testLine(): IntArray = IntArray(WORDS_PER_LINE) { i ->
        if (i < SYNC_A.size) (if (SYNC_A[i] > 0) 255 else 0) else (i * 255 / WORDS_PER_LINE)
    }
}
