/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Receiver for IQ samples of an RTL-SDR (rtl_tcp: 8-bit unsigned I/Q), all in Kotlin:
 * 960 kS/s -> NCO (the hardware is tuned [IF_OFFSET] away, so the wanted signal is clear of the DC spike and small retunes,
 * such as Doppler, are done here without touching the tuner) -> FIR /5 (192 kS/s) ->
 * WFM: FM discriminator, 50 µs de-emphasis, FIR /4 -> 48 kHz audio;
 * narrow modes: FIR /4 (48 kS/s, channel filter) -> NFM / AM / USB / LSB / CW -> 12 kHz audio.
 */
object Dsp {
    const val INPUT_RATE = 960_000
    const val IF_OFFSET = 150_000L                 // tuner frequency = wanted frequency + IF_OFFSET
    const val NCO_RANGE = 380_000L                 // farther than this from the tuner frequency: retune the tuner
    const val NARROW_AUDIO_RATE = 12_000
    const val WIDE_AUDIO_RATE = 48_000
    const val CW_TONE = 700.0

    /** Windowed-sinc low-pass (Blackman), [cutoff] and [rate] in Hz, unity gain at DC. */
    fun lowPass(taps: Int, cutoff: Double, rate: Double): FloatArray {
        val n = if (taps % 2 == 0) taps + 1 else taps
        val fc = cutoff / rate
        val m = n - 1
        val h = DoubleArray(n) { i ->
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m)
            sinc * w
        }
        val sum = h.sum()
        return FloatArray(n) { (h[it] / sum).toFloat() }
    }

    /** Complex FIR with decimation: history kept between blocks. */
    class CFir(private val taps: FloatArray, private val decim: Int) {
        private val n = taps.size
        private val re = FloatArray(2 * n); private val im = FloatArray(2 * n)
        private var pos = 0; private var phase = 0

        /** Input [inRe]/[inIm] (count [len]); output appended into [outRe]/[outIm] from [outOff]; returns the outputs written. */
        fun process(inRe: FloatArray, inIm: FloatArray, len: Int, outRe: FloatArray, outIm: FloatArray, outOff: Int = 0): Int {
            var o = outOff
            for (i in 0 until len) {
                re[pos] = inRe[i]; re[pos + n] = inRe[i]; im[pos] = inIm[i]; im[pos + n] = inIm[i]
                pos++; if (pos == n) pos = 0
                if (++phase == decim) {
                    phase = 0
                    var sr = 0f; var si = 0f
                    var k = pos                                     // oldest sample
                    for (t in 0 until n) { val c = taps[t]; sr += c * re[k]; si += c * im[k]; k++ }
                    outRe[o] = sr; outIm[o] = si; o++
                }
            }
            return o - outOff
        }
    }

    /** Real FIR with decimation. */
    class RFir(private val taps: FloatArray, private val decim: Int) {
        private val n = taps.size
        private val buf = FloatArray(2 * n)
        private var pos = 0; private var phase = 0
        fun process(inp: FloatArray, len: Int, out: FloatArray, outOff: Int = 0): Int {
            var o = outOff
            for (i in 0 until len) {
                buf[pos] = inp[i]; buf[pos + n] = inp[i]
                pos++; if (pos == n) pos = 0
                if (++phase == decim) {
                    phase = 0
                    var s = 0f; var k = pos
                    for (t in 0 until n) { s += taps[t] * buf[k]; k++ }
                    out[o++] = s
                }
            }
            return o - outOff
        }
    }

    /** Numerically controlled oscillator: multiplies by e^(j 2π f n / rate). */
    class Nco(private val rate: Double) {
        private var cr = 1.0; private var ci = 0.0
        private var dr = 1.0; private var di = 0.0
        private var count = 0
        var freqHz = 0.0
            set(v) { field = v; val w = 2 * PI * v / rate; dr = cos(w); di = sin(w) }
        fun mix(re: FloatArray, im: FloatArray, len: Int) {
            for (i in 0 until len) {
                val a = re[i]; val b = im[i]
                re[i] = (a * cr - b * ci).toFloat(); im[i] = (a * ci + b * cr).toFloat()
                val nr = cr * dr - ci * di; ci = cr * di + ci * dr; cr = nr
                if (++count == 1024) { count = 0; val g = 1.0 / sqrt(cr * cr + ci * ci); cr *= g; ci *= g }
            }
        }
    }

    /** Simple audio AGC (peak follower). */
    class Agc(private val target: Float = 0.3f, private val rate: Double) {
        private var peak = 1e-4f
        private val decay = exp(-1.0 / (0.5 * rate)).toFloat()
        fun process(x: FloatArray, len: Int) {
            for (i in 0 until len) {
                val a = abs(x[i])
                peak = if (a > peak) a else max(peak * decay, 1e-5f)
                x[i] = x[i] * (target / peak)
            }
        }
    }

    class Receiver {
        var mode = "NFM"
            private set
        var bandwidthHz = 12_500
            private set
        var squelchDb = -200.0                         // NFM: below this channel level the audio is muted
        /** Channel level in dBFS (last block). */
        var levelDb = -120.0
            private set
        val audioRate: Int get() = if (mode == "WFM") WIDE_AUDIO_RATE else NARROW_AUDIO_RATE
        /** NFM only: the raw FM discriminator at 48 kHz (±5 kHz deviation = ±1), for digital voice decoders (DMR, D-STAR...). */
        var discriminatorTap: ((FloatArray, Int) -> Unit)? = null
        /** Complex channel at 48 kS/s centred on the tuned frequency (not in WFM): the VOR carrier finder (ppm calibration). */
        var basebandTap: ((FloatArray, FloatArray, Int) -> Unit)? = null

        private val nco = Nco(INPUT_RATE.toDouble())
        private val firA = CFir(lowPass(41, 80_000.0, INPUT_RATE.toDouble()), 5)
        private var firB = CFir(lowPass(65, 6_250.0, 192_000.0), 4)
        private var firC = CFir(lowPass(33, 5_000.0, 48_000.0), 4)                 // SSB / CW: 48 -> 12 kS/s
        private var sideband = CFir(lowPass(129, 1_350.0, 12_000.0), 1)
        private var audioNarrow = RFir(lowPass(33, 3_500.0, 48_000.0), 4)          // NFM / AM audio: 48 -> 12 kHz
        private val audioWide = RFir(lowPass(33, 15_000.0, 192_000.0), 4)
        private val agc = Agc(rate = NARROW_AUDIO_RATE.toDouble())
        private var lastRe = 1f; private var lastIm = 0f
        private var deemph = 0f
        private var dc = 0f
        private var bfoPhase = 0.0

        // work buffers
        private var bRe = FloatArray(0); private var bIm = FloatArray(0)
        private var aRe = FloatArray(0); private var aIm = FloatArray(0)
        private var cRe = FloatArray(0); private var cIm = FloatArray(0)
        private var dRe = FloatArray(0); private var dIm = FloatArray(0)
        private var r1 = FloatArray(0); private var r2 = FloatArray(0)

        /** Offset of the wanted frequency from the tuner frequency, Hz (normally -IF_OFFSET plus small retunes). */
        fun setOffset(hz: Double) { nco.freqHz = -hz }

        fun setMode(m: String, bwHz: Int) {
            mode = m
            bandwidthHz = when {
                bwHz > 0 -> bwHz
                m == "NFM" -> 12_500; m == "AM" -> 8_000; m == "WFM" -> 180_000; m == "CW" -> 500; else -> 2_700
            }
            when (m) {
                "NFM", "AM" -> firB = CFir(lowPass(65, (bandwidthHz / 2.0).coerceIn(1_500.0, 22_000.0), 192_000.0), 4)
                "USB", "LSB" -> { firB = CFir(lowPass(65, 12_000.0, 192_000.0), 4); sideband = CFir(lowPass(129, (bandwidthHz / 2.0).coerceIn(500.0, 2_500.0), 12_000.0), 1) }
                "CW" -> { firB = CFir(lowPass(65, 12_000.0, 192_000.0), 4); sideband = CFir(lowPass(129, (bandwidthHz / 2.0).coerceIn(50.0, 1_500.0), 12_000.0), 1) }
            }
            audioNarrow = RFir(lowPass(33, if (m == "AM") 4_500.0 else 3_500.0, 48_000.0), 4)
        }

        private fun ensure(n: Int) {
            if (bRe.size >= n) return
            bRe = FloatArray(n); bIm = FloatArray(n)
            aRe = FloatArray(n / 5 + 2); aIm = FloatArray(n / 5 + 2)
            cRe = FloatArray(n / 20 + 2); cIm = FloatArray(n / 20 + 2)
            dRe = FloatArray(n / 80 + 2); dIm = FloatArray(n / 80 + 2)
            r1 = FloatArray(n / 5 + 2); r2 = FloatArray(n / 5 + 2)
        }

        /** 8-bit unsigned IQ bytes -> audio samples (-1..1) at [audioRate]; returns how many were written into [out]. */
        /** Raw input IQ (960 kS/s, before the NCO mix), for the panadapter waterfall. */
        var specTap: ((FloatArray, FloatArray, Int) -> Unit)? = null

        fun process(iq: ByteArray, len: Int, out: FloatArray): Int {
            val n = len / 2
            ensure(n)
            for (i in 0 until n) {
                bRe[i] = ((iq[2 * i].toInt() and 0xFF) - 127.5f) / 127.5f
                bIm[i] = ((iq[2 * i + 1].toInt() and 0xFF) - 127.5f) / 127.5f
            }
            specTap?.invoke(bRe, bIm, n)
            nco.mix(bRe, bIm, n)
            val na = firA.process(bRe, bIm, n, aRe, aIm)                       // 192 kS/s
            if (mode == "WFM") {
                var p = 0.0
                val a = exp(-1.0 / (192_000.0 * 50e-6)).toFloat()
                for (i in 0 until na) {
                    val re = aRe[i]; val im = aIm[i]
                    p += re * re + im * im
                    val d = atan2(im * lastRe - re * lastIm, re * lastRe + im * lastIm)
                    lastRe = re; lastIm = im
                    deemph = a * deemph + (1 - a) * (d / PI.toFloat())
                    r1[i] = deemph
                }
                levelDb = 10 * log10(p / max(na, 1) + 1e-12)
                val m = audioWide.process(r1, na, out)
                for (i in 0 until m) out[i] = (out[i] * 2.5f).coerceIn(-1f, 1f)
                return m
            }
            val nb = firB.process(aRe, aIm, na, cRe, cIm)                     // 48 kS/s channel
            basebandTap?.invoke(cRe, cIm, nb)
            var p = 0.0
            for (i in 0 until nb) p += cRe[i] * cRe[i] + cIm[i] * cIm[i]
            levelDb = 10 * log10(p / max(nb, 1) + 1e-12)
            when (mode) {
                "NFM" -> {
                    for (i in 0 until nb) {
                        val re = cRe[i]; val im = cIm[i]
                        val d = atan2(im * lastRe - re * lastIm, re * lastRe + im * lastIm)
                        lastRe = re; lastIm = im
                        r1[i] = d * (48_000f / (2 * PI.toFloat() * 5_000f))           // ±5 kHz deviation = ±1
                    }
                    discriminatorTap?.invoke(r1, nb)
                    val m = audioNarrow.process(r1, nb, out)
                    val mute = levelDb < squelchDb
                    for (i in 0 until m) { dc += 0.002f * (out[i] - dc); out[i] = if (mute) 0f else ((out[i] - dc) * 0.8f).coerceIn(-1f, 1f) }
                    return m
                }
                "AM" -> {
                    for (i in 0 until nb) r1[i] = sqrt(cRe[i] * cRe[i] + cIm[i] * cIm[i])
                    val m = audioNarrow.process(r1, nb, out)
                    for (i in 0 until m) { dc += 0.002f * (out[i] - dc); out[i] = out[i] - dc }
                    agc.process(out, m)
                    for (i in 0 until m) out[i] = out[i].coerceIn(-1f, 1f)
                    return m
                }
                else -> {                                                          // USB, LSB, CW at 12 kS/s
                    val nc = firC.process(cRe, cIm, nb, dRe, dIm)
                    val shift = when (mode) { "USB" -> -1_500.0; "LSB" -> 1_500.0; else -> 0.0 }
                    if (shift != 0.0) rotate(dRe, dIm, nc, shift)
                    val nd = sideband.process(dRe, dIm, nc, dRe, dIm)
                    if (mode == "CW") rotate(dRe, dIm, nd, CW_TONE) else rotate(dRe, dIm, nd, -shift)
                    for (i in 0 until nd) out[i] = dRe[i]
                    agc.process(out, nd)
                    for (i in 0 until nd) out[i] = out[i].coerceIn(-1f, 1f)
                    return nd
                }
            }
        }

        private fun rotate(re: FloatArray, im: FloatArray, len: Int, hz: Double) {
            val w = 2 * PI * hz / NARROW_AUDIO_RATE
            for (i in 0 until len) {
                val c = cos(bfoPhase); val s = sin(bfoPhase)
                val a = re[i]; val b = im[i]
                re[i] = (a * c - b * s).toFloat(); im[i] = (a * s + b * c).toFloat()
                bfoPhase += w
            }
            bfoPhase %= (2 * PI)
        }
    }

    /** Power of [freq] in [x] (Goertzel), for tests and tone checks. */
    fun goertzel(x: FloatArray, len: Int, freq: Double, rate: Double): Double {
        val w = 2 * PI * freq / rate
        val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until len) { val s = x[i] + c * s1 - s2; s2 = s1; s1 = s }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    /** 16-bit mono PCM WAV header for [dataBytes] bytes of samples. */
    fun wavHeader(rate: Int, dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    fun toPcm16(x: FloatArray, len: Int, out: ShortArray) { for (i in 0 until len) out[i] = (min(1f, max(-1f, x[i])) * 32000f).toInt().toShort() }
}
