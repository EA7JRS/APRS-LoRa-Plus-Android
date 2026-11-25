// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// AFSK 1200 (Bell 202) demodulator: audio tones 1200 Hz (mark) / 2200 Hz (space) at 1200 baud -> NRZI -> HDLC -> FCS -> AX.25.
// This is the modulation of APRS on FM and of many cubesats' 1k2 packet telemetry (the format SatNOGS / gr-satellites decode).
// Input is the NFM audio of the RTL-SDR receiver (any sample rate); output is AX.25 frames without the FCS, like G3ruh.Decoder
// (which handles the 9600 baud direct-FSK case). The HDLC/FCS conventions are shared with G3ruh; AFSK carries no G3RUH scrambler.
package es.ea7jrs.aprslorap.core

import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

object Afsk1200 {

    fun crc16(data: ByteArray, len: Int = data.size): Int = G3ruh.crc16(data, len)

    /**
     * Non-coherent AFSK demodulator. A sliding correlator over one bit against the mark and space tones gives, each sample, which
     * tone is stronger; a digital PLL (pulled to transitions) samples at the bit centre; NRZI (no change = 1) then HDLC framing.
     */
    class Decoder(
        sampleRate: Double,
        markHz: Double = 1200.0,
        spaceHz: Double = 2200.0,
        private val baud: Double = 1200.0,
        private val onFrame: (ByteArray) -> Unit,
    ) {
        private val n = round(sampleRate / baud).toInt().coerceAtLeast(4)   // samples per bit = correlator window
        private val cm = DoubleArray(n) { cos(2.0 * Math.PI * markHz * it / sampleRate) }
        private val sm = DoubleArray(n) { sin(2.0 * Math.PI * markHz * it / sampleRate) }
        private val cs = DoubleArray(n) { cos(2.0 * Math.PI * spaceHz * it / sampleRate) }
        private val ss = DoubleArray(n) { sin(2.0 * Math.PI * spaceHz * it / sampleRate) }
        private val win = DoubleArray(n)
        private var wp = 0
        private var filled = 0
        private val step = baud / sampleRate

        // clock recovery
        private var phase = 0.0
        private var last = false
        private var dc = 0.0
        // NRZI
        private var prevLevel = 0
        // HDLC
        private var shreg = 0
        private var ones = 0
        private var inFrame = false
        private var byte = 0
        private var nbits = 0
        private val buf = java.io.ByteArrayOutputStream()

        var frames = 0
            private set
        var badFcs = 0
            private set

        /** NFM audio samples (any scale). */
        fun process(x: FloatArray, count: Int) {
            for (i in 0 until count) {
                dc += (x[i] - dc) * 0.0005
                win[wp] = (x[i] - dc).toDouble()
                wp = (wp + 1) % n
                if (filled < n) { filled++; continue }
                // correlate the last n samples (chronological: oldest at wp) against each tone
                var mI = 0.0; var mQ = 0.0; var sI = 0.0; var sQ = 0.0
                var idx = wp
                for (k in 0 until n) {
                    val v = win[idx]
                    mI += v * cm[k]; mQ += v * sm[k]
                    sI += v * cs[k]; sQ += v * ss[k]
                    idx = (idx + 1) % n
                }
                val level = (mI * mI + mQ * mQ) > (sI * sI + sQ * sQ)        // mark stronger than space
                if (level != last) {                                        // transition: pull the clock to the bit boundary
                    val e = if (phase < 0.5) phase else phase - 1.0
                    phase -= 0.25 * e
                    last = level
                }
                val before = phase
                phase += step
                if (before < 0.5 && phase >= 0.5) lineBit(if (level) 1 else 0)
                if (phase >= 1.0) phase -= 1.0
            }
        }

        private fun lineBit(markBit: Int) {
            val bit = if (markBit == prevLevel) 1 else 0                     // NRZI: no change = 1
            prevLevel = markBit
            hdlc(bit)
        }

        private fun hdlc(bit: Int) {
            shreg = ((shreg ushr 1) or (bit shl 7)) and 0xFF
            if (shreg == 0x7E) {
                if (inFrame && buf.size() >= 17) finish()
                inFrame = true; buf.reset(); byte = 0; nbits = 0; ones = 0
                return
            }
            if (!inFrame) return
            if (bit == 1) {
                ones++
                if (ones >= 7) { inFrame = false; buf.reset(); return }      // abort (7+ ones)
            } else {
                if (ones == 5) { ones = 0; return }                         // stuffed zero
                ones = 0
            }
            byte = (byte ushr 1) or (bit shl 7); nbits++
            if (nbits == 8) {
                buf.write(byte); nbits = 0; byte = 0
                if (buf.size() > 400) { inFrame = false; buf.reset() }
            }
        }

        private fun finish() {
            val f = buf.toByteArray()
            val len = f.size
            if (len < 17) return
            val fcs = (f[len - 2].toInt() and 0xFF) or ((f[len - 1].toInt() and 0xFF) shl 8)
            if (crc16(f, len - 2) == fcs) { frames++; onFrame(f.copyOf(len - 2)) } else badFcs++
        }
    }

    /** AFSK 1200 samples of an AX.25 frame (without FCS), for tests and to know what the air looks like. Continuous-phase. */
    fun modulate(
        frame: ByteArray, sampleRate: Double, markHz: Double = 1200.0, spaceHz: Double = 2200.0,
        baud: Double = 1200.0, amp: Float = 0.7f, leadFlags: Int = 40, tailFlags: Int = 4,
    ): FloatArray {
        val bits = G3ruh.hdlcBits(frame, leadFlags, tailFlags)
        var level = 0                                                       // NRZI: 0 = change of tone
        val spb = sampleRate / baud
        val out = FloatArray(round(bits.size * spb).toInt())
        var phase = 0.0
        var pos = 0
        for (b in bits.indices) {
            if (bits[b] == 0) level = level xor 1
            val f = if (level == 1) markHz else spaceHz
            val end = round((b + 1) * spb).toInt().coerceAtMost(out.size)
            while (pos < end) {
                phase += 2.0 * Math.PI * f / sampleRate
                out[pos++] = (amp * sin(phase)).toFloat()
            }
        }
        return out
    }
}
