// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Inmarsat-C / STD-C receiver DSP (L-band, ~1.5 GHz): BPSK at 1200 symbols/s, rate 1/2 K=7 convolutional code, scrambler and a
// unique word per frame. This is the signal of EGC / SafetyNET broadcasts (navigational and meteorological warnings, SAR). The
// chain here, from complex baseband to decoded bytes, is standard and tested end to end against its own encoder; the Inmarsat
// frame layout and the EGC message assembly are a second step and need a real signal to confirm. Reference: scytale-C / JAERO
// (behaviour and constants, no code copied).
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Inmarsat {

    const val SYMBOL_RATE = 1200.0
    // Rate 1/2, K=7 convolutional code (octal 0171 / 0133, the usual "Voyager" polynomials Inmarsat-C uses)
    private const val G0 = 0x79
    private const val G1 = 0x5B
    const val K = 7
    private const val STATES = 1 shl (K - 1)                        // 64

    private fun parity(x: Int): Int { var v = x; v = v xor (v ushr 4); v = v xor (v ushr 2); v = v xor (v ushr 1); return v and 1 }

    /** Convolutional encoder (for tests and to know the code): one input bit -> two output bits. */
    fun convEncode(bits: IntArray): IntArray {
        var sr = 0
        val out = IntArray(bits.size * 2)
        for (i in bits.indices) {
            sr = ((sr shl 1) or bits[i]) and ((1 shl K) - 1)
            out[2 * i] = parity(sr and G0)
            out[2 * i + 1] = parity(sr and G1)
        }
        return out
    }

    /** Soft-decision Viterbi decoder (input: soft pairs in -1..+1, +1 ~ bit 0). Returns the decoded bits. */
    fun viterbi(soft: FloatArray): IntArray {
        val n = soft.size / 2
        val INF = 1e9f
        var metric = FloatArray(STATES) { if (it == 0) 0f else INF }
        val tb = Array(n) { IntArray(STATES) }                     // traceback: previous state's low bit (the input bit)
        // precompute outputs for each (state, input)
        for (t in 0 until n) {
            val next = FloatArray(STATES) { INF }
            val s0 = soft[2 * t]; val s1 = soft[2 * t + 1]
            for (st in 0 until STATES) {
                if (metric[st] >= INF) continue
                for (inb in 0..1) {
                    val sr = (st shl 1) or inb
                    val o0 = parity(sr and G0); val o1 = parity(sr and G1)
                    // branch metric: correlation with soft (bit 0 -> +1, bit 1 -> -1)
                    val bm = -(s0 * (1 - 2 * o0) + s1 * (1 - 2 * o1))
                    val ns = sr and (STATES - 1)
                    val m = metric[st] + bm
                    if (m < next[ns]) { next[ns] = m; tb[t][ns] = (st shl 1) or inb }
                }
            }
            metric = next
        }
        // best end state, traceback
        var best = 0; for (st in 1 until STATES) if (metric[st] < metric[best]) best = st
        val bits = IntArray(n)
        var st = best
        for (t in n - 1 downTo 0) { val info = tb[t][st]; bits[t] = info and 1; st = info ushr 1 }
        return bits
    }

    /** Additive scrambler / descrambler (self-synchronising LFSR x^15 + x^14 + 1), applied to the coded bits. Symmetric. */
    fun scramble(bits: IntArray, seed: Int = 0x7FFF): IntArray {
        var lfsr = seed and 0x7FFF
        return IntArray(bits.size) { i ->
            val fb = ((lfsr ushr 14) xor (lfsr ushr 13)) and 1
            lfsr = ((lfsr shl 1) or fb) and 0x7FFF
            bits[i] xor fb
        }
    }

    /**
     * BPSK demodulator from complex baseband (interleaved I,Q at [sampleRate]). Decision-directed carrier (Costas-lite) and
     * Gardner timing recovery; outputs one soft symbol per BPSK symbol (sign = bit after differential/absolute resolution).
     */
    class Demod(private val sampleRate: Double) {
        private val sps = sampleRate / SYMBOL_RATE                  // samples per symbol
        private var phase = 0.0                                     // carrier NCO phase
        private var freq = 0.0
        private var acc = 0.0; private var accN = 0                 // integrate-and-dump (matched filter for rectangular BPSK)
        private var midAcc = 0.0; private var midN = 0; private var mid = 0.0   // mid-symbol value for Gardner
        private var timer = 0.0
        private var prevSym = 0.0
        private val out = ArrayList<Float>()

        fun process(iq: FloatArray, n: Int): FloatArray {
            out.clear()
            var k = 0
            while (k + 1 < n) {
                val c = cos(phase); val s = sin(phase)
                val i = iq[k] * c + iq[k + 1] * s
                val q = -iq[k] * s + iq[k + 1] * c
                // Costas PI loop (BPSK), decision-directed
                val err = (if (i >= 0) 1.0 else -1.0) * q
                freq += 2e-4 * err
                phase += freq + 0.05 * err
                if (phase > Math.PI) phase -= 2 * Math.PI else if (phase < -Math.PI) phase += 2 * Math.PI
                acc += i; accN++
                timer += 1.0
                if (timer >= sps / 2 && midN == 0) { mid = acc / accN.coerceAtLeast(1); midN = 1 }
                if (timer >= sps) {
                    timer -= sps
                    val sym = acc / accN.coerceAtLeast(1)
                    val ted = mid * (sym - prevSym)                // Gardner timing error on integrated symbols
                    timer += 0.1 * ted / (abs(sym) + 1e-6)
                    out.add(sym.toFloat())
                    prevSym = sym; acc = 0.0; accN = 0; midN = 0
                }
                k += 2
            }
            return out.toFloatArray()
        }
    }

    /** BPSK complex baseband of bits (±1 I, 0 Q), for tests. One sample per symbol scaled by sps. */
    fun modulate(bits: IntArray, sampleRate: Double, amp: Float = 0.8f, freqOffHz: Double = 0.0): FloatArray {
        val sps = sampleRate / SYMBOL_RATE
        val total = (bits.size * sps).toInt()
        val iq = FloatArray(2 * total)
        var phase = 0.0
        for (k in 0 until total) {
            val b = bits[(k / sps).toInt().coerceIn(0, bits.size - 1)]
            val sym = if (b == 0) 1.0 else -1.0                     // bit 0 -> +1
            phase += 2.0 * Math.PI * freqOffHz / sampleRate
            iq[2 * k] = (amp * sym * cos(phase)).toFloat()
            iq[2 * k + 1] = (amp * sym * sin(phase)).toFloat()
        }
        return iq
    }

    /** Find a known unique word (as ±1 symbols) in a soft symbol stream; returns the best offset or -1. */
    fun syncOffset(sym: FloatArray, uw: IntArray, from: Int = 0, to: Int = sym.size - 1): Int {
        var best = -1; var bestAcc = 0.0
        val lim = (to - uw.size).coerceAtMost(sym.size - uw.size)
        for (off in from..lim) {
            var acc = 0.0
            for (j in uw.indices) acc += sym[off + j] * uw[j]
            val a = abs(acc)
            if (a > bestAcc) { bestAcc = a; best = off }
        }
        return if (bestAcc > uw.size * 0.5) best else -1
    }
}
