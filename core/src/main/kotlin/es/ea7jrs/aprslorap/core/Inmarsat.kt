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

    // ---------------------------------------------------------------------------------------------------------------------
    // Frame / EGC (SafetyNET) layer. The STD-C TDM carries fixed-length frames: a unique word, then the coded+scrambled
    // body with packets. The descriptor codes and the exact frame length below are a WORKING MODEL after the behaviour of
    // scytale-C / JAERO, self-consistent with buildFrame() so the chain is testable end to end, but the real values need a
    // genuine STD-C recording to confirm (no code copied). EGC text is IA5 (7-bit ASCII).
    // ---------------------------------------------------------------------------------------------------------------------

    /** Default unique word (±1 symbols). Placeholder pattern, to confirm against a real TDM. */
    val UW = intArrayOf(1, -1, 1, 1, -1, -1, 1, -1, -1, 1, -1, 1, 1, 1, -1, -1, 1, -1, 1, -1, -1, 1, 1, -1)

    const val FRAME_BYTES = 125                                     // info bytes per frame (placeholder; real TDM length TBD)
    private const val DESC_IDLE = 0x00
    private const val DESC_EGC_HDR = 0xAA                           // [0xAA][idLo][idHi][priority][service][parts]
    private const val DESC_EGC_PART = 0x27                          // [0x27][idLo][idHi][partNo][len][IA5 * len]

    private fun bytesToBits(b: ByteArray): IntArray {
        val out = IntArray(b.size * 8)
        for (i in b.indices) for (k in 0 until 8) out[i * 8 + k] = (b[i].toInt() ushr (7 - k)) and 1
        return out
    }
    private fun bitsToBytes(bits: IntArray): ByteArray {
        val out = ByteArray(bits.size / 8)
        for (i in out.indices) { var v = 0; for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]; out[i] = v.toByte() }
        return out
    }

    /** Build one frame as ±1 symbols: UW ++ convEncode(scramble(bits(info) padded to FRAME_BYTES) + flush). For tests / modulate. */
    fun buildFrame(payload: ByteArray): IntArray {
        val info = ByteArray(FRAME_BYTES)
        for (i in 0 until minOf(payload.size, FRAME_BYTES)) info[i] = payload[i]
        val bits = scramble(bytesToBits(info)) + IntArray(K - 1)    // flush the encoder
        val coded = convEncode(bits)
        val out = IntArray(UW.size + coded.size)
        for (j in UW.indices) out[j] = UW[j]
        for (j in coded.indices) out[UW.size + j] = if (coded[j] == 0) 1 else -1
        return out
    }

    /** Decode the info bytes of a frame whose body starts at [bodyOff] (just past the UW) in a soft symbol stream. */
    fun decodeFrame(sym: FloatArray, bodyOff: Int): ByteArray? {
        val codedSyms = (FRAME_BYTES * 8 + (K - 1)) * 2
        if (bodyOff + codedSyms > sym.size) return null
        val soft = FloatArray(codedSyms) { sym[bodyOff + it] }
        val bits = viterbi(soft)                                   // length = FRAME_BYTES*8 + K-1
        val info = descrambleInfo(bits.copyOf(FRAME_BYTES * 8))
        return bitsToBytes(info)
    }
    private fun descrambleInfo(bits: IntArray) = scramble(bits)     // scramble() is symmetric

    /** A fully-received EGC / SafetyNET message. */
    data class Egc(val id: Int, val priority: Int, val service: Int, val text: String)

    /** Parse the packets of one frame's info bytes and feed an [EgcAssembler]. */
    fun parseFrame(info: ByteArray, asm: EgcAssembler) {
        var p = 0
        while (p < info.size) {
            val desc = info[p].toInt() and 0xFF
            when (desc) {
                DESC_IDLE -> return
                DESC_EGC_HDR -> {
                    if (p + 6 > info.size) return
                    val id = (info[p + 1].toInt() and 0xFF) or ((info[p + 2].toInt() and 0xFF) shl 8)
                    asm.header(id, info[p + 3].toInt() and 0xFF, info[p + 4].toInt() and 0xFF, info[p + 5].toInt() and 0xFF)
                    p += 6
                }
                DESC_EGC_PART -> {
                    if (p + 5 > info.size) return
                    val id = (info[p + 1].toInt() and 0xFF) or ((info[p + 2].toInt() and 0xFF) shl 8)
                    val part = info[p + 3].toInt() and 0xFF
                    val len = info[p + 4].toInt() and 0xFF
                    if (p + 5 + len > info.size) return
                    val sb = StringBuilder()
                    for (j in 0 until len) { val c = info[p + 5 + j].toInt() and 0x7F; sb.append(if (c in 32..126) c.toChar() else ' ') }
                    asm.part(id, part, sb.toString())
                    p += 5 + len
                }
                else -> return                                     // unknown descriptor: stop (frame alignment not trusted)
            }
        }
    }

    /** Collects EGC header + parts by message id and emits a message once all parts are in. */
    class EgcAssembler(private val onMessage: (Egc) -> Unit) {
        private class Msg(var priority: Int, var service: Int, var parts: Int) { val text = HashMap<Int, String>(); var done = false }
        private val msgs = HashMap<Int, Msg>()
        fun header(id: Int, priority: Int, service: Int, parts: Int) {
            val m = msgs.getOrPut(id) { Msg(priority, service, parts) }
            m.priority = priority; m.service = service; m.parts = parts; flush(id, m)
        }
        fun part(id: Int, no: Int, s: String) {
            val m = msgs.getOrPut(id) { Msg(0, 0, 0) }
            m.text[no] = s; flush(id, m)
        }
        private fun flush(id: Int, m: Msg) {
            if (m.done || m.parts <= 0 || m.text.size < m.parts) return
            val sb = StringBuilder()
            for (i in 0 until m.parts) sb.append(m.text[i] ?: return)
            m.done = true
            onMessage(Egc(id, m.priority, m.service, sb.toString()))
        }
    }

    /** Streaming reader: complex-to-symbol demod, UW sync, frame decode and EGC assembly. One per receive session. */
    class FrameReader(sampleRate: Double, onMessage: (Egc) -> Unit) {
        private val demod = Demod(sampleRate)
        private val asm = EgcAssembler(onMessage)
        private val buf = ArrayList<Float>()
        private var il = FloatArray(0)
        var frames = 0; private set

        /** Separate I/Q arrays (the receiver's baseband tap). */
        fun feed(re: FloatArray, im: FloatArray, n: Int) {
            if (il.size < 2 * n) il = FloatArray(2 * n)
            for (k in 0 until n) { il[2 * k] = re[k]; il[2 * k + 1] = im[k] }
            process(il, 2 * n)
        }

        /** Interleaved complex baseband samples (I,Q). */
        fun process(iq: FloatArray, n: Int) {
            val sym = demod.process(iq, n)
            for (s in sym) buf.add(s)
            drain()
        }
        private fun drain() {
            val need = UW.size + (FRAME_BYTES * 8 + (K - 1)) * 2
            while (buf.size >= need) {
                val arr = FloatArray(buf.size) { buf[it] }
                val off = syncOffset(arr, UW, 0, UW.size + 8)       // UW near the start of the window
                if (off < 0) { repeat(minOf(UW.size, buf.size)) { buf.removeAt(0) }; continue }
                val bodyOff = off + UW.size
                val info = decodeFrame(arr, bodyOff) ?: break
                frames++
                parseFrame(info, asm)
                repeat(off + need) { if (buf.isNotEmpty()) buf.removeAt(0) }
            }
            if (buf.size > need * 4) repeat(buf.size - need * 2) { buf.removeAt(0) }   // bound memory on noise
        }
    }
}
