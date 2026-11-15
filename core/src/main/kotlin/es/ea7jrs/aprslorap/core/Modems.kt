/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * Receive-only digital modes in the manner of fldigi (W1HKJ and contributors, GPL-3.0-or-later): the PSK varicode table,
 * the Baudot tables and the PSK symbol-timing method (syncbuf / bitclk) come from fldigi 4.1 (psk.cxx, pskvaricode.cxx,
 * rtty.cxx); the rest is written here. Input: audio (8 kHz) from the app's receiver (USB) or the phone's microphone.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Modems {
    const val RATE = 8000

    /** A receive-only modem: audio in, text out through [onText]. */
    abstract class Modem(var centerHz: Double) {
        var onText: (String) -> Unit = {}
        var afc = true
        /** Signal quality 0..1 (PSK phase quality, RTTY / CW contrast), for the screen and the squelch. */
        var quality = 0.0
            protected set
        abstract val label: String
        abstract fun process(x: FloatArray, n: Int)
    }

    // ------------------------------------------------------------------ helpers

    /** Complex mixer + decimating low-pass: audio at [rate] -> baseband at rate / [decim]. */
    class Downconverter(rate: Int, cutoff: Double, private val decim: Int, taps: Int = 0) {
        private val rate = rate.toDouble()
        private val fir = Dsp.CFir(Dsp.lowPass(if (taps > 0) taps else (4.0 * rate / cutoff).toInt().coerceIn(31, 511), cutoff, rate.toDouble()), decim)
        private var phase = 0.0
        private var re = FloatArray(0); private var im = FloatArray(0)
        var outRe = FloatArray(0); private set
        var outIm = FloatArray(0); private set
        fun process(x: FloatArray, n: Int, freqHz: Double): Int {
            if (re.size < n) { re = FloatArray(n); im = FloatArray(n); outRe = FloatArray(n / decim + 2); outIm = FloatArray(n / decim + 2) }
            val w = 2 * PI * freqHz / rate
            for (i in 0 until n) { re[i] = (x[i] * cos(phase)).toFloat(); im[i] = (-x[i] * sin(phase)).toFloat(); phase += w }
            phase %= (2 * PI)
            return fir.process(re, im, n, outRe, outIm)
        }
    }

    // ------------------------------------------------------------------ PSK (BPSK31, 63, 125, 250)

    private val VARICODE = arrayOf("1010101011", "1011011011", "1011101101", "1101110111", "1011101011", "1101011111", "1011101111", "1011111101", "1011111111", "11101111", "11101", "1101101111", "1011011101", "11111", "1101110101", "1110101011", "1011110111", "1011110101", "1110101101", "1110101111", "1101011011", "1101101011", "1101101101", "1101010111", "1101111011", "1101111101", "1110110111", "1101010101", "1101011101", "1110111011", "1011111011", "1101111111", "1", "111111111", "101011111", "111110101", "111011011", "1011010101", "1010111011", "101111111", "11111011", "11110111", "101101111", "111011111", "1110101", "110101", "1010111", "110101111", "10110111", "10111101", "11101101", "11111111", "101110111", "101011011", "101101011", "110101101", "110101011", "110110111", "11110101", "110111101", "111101101", "1010101", "111010111", "1010101111", "1010111101", "1111101", "11101011", "10101101", "10110101", "1110111", "11011011", "11111101", "101010101", "1111111", "111111101", "101111101", "11010111", "10111011", "11011101", "10101011", "11010101", "111011101", "10101111", "1101111", "1101101", "101010111", "110110101", "101011101", "101110101", "101111011", "1010101101", "111110111", "111101111", "111111011", "1010111111", "101101101", "1011011111", "1011", "1011111", "101111", "101101", "11", "111101", "1011011", "101011", "1101", "111101011", "10111111", "11011", "111011", "1111", "111", "111111", "110111111", "10101", "10111", "101", "110111", "1111011", "1101011", "11011111", "1011101", "111010101", "1010110111", "110111011", "1010110101", "1011010111", "1110110101")
    private val VARIDECODE: Map<Int, Char> = VARICODE.withIndex().associate { (i, s) -> s.toInt(2) to i.toChar() }

    /** PSK varicode of [c] ("1" first, no separator), for tests and transmitters. */
    fun varicode(c: Char): String = VARICODE[c.code and 0x7F]

    class Psk(center: Double, val baud: Double = 31.25) : Modem(center) {
        override val label = "BPSK" + baud.toInt()
        private val sps = RATE / baud                                        // samples per symbol (256 for PSK31)
        private val decim = (sps / 16).toInt().coerceAtLeast(1)              // 16 samples per symbol after the first filter
        private val dc = Downconverter(RATE, baud * 1.5, decim)
        private val matched = Dsp.CFir(Dsp.lowPass(65, baud * 0.65, RATE / decim.toDouble()), 1)
        private val steps = 16.0
        private val syncbuf = DoubleArray(16)
        private var bitclk = 0.0
        private var prevRe = 1.0; private var prevIm = 0.0
        private var shreg = 0
        private var mRe = FloatArray(0); private var mIm = FloatArray(0)
        private var qual = 0.0
        private var ampAvg = 0.0
        var squelch = 0.35

        override fun process(x: FloatArray, n: Int) {
            val m = dc.process(x, n, centerHz)
            if (mRe.size < m) { mRe = FloatArray(m); mIm = FloatArray(m) }
            val k = matched.process(dc.outRe, dc.outIm, m, mRe, mIm)
            for (i in 0 until k) {
                val re = mRe[i].toDouble(); val im = mIm[i].toDouble()
                val mag = sqrt(re * re + im * im)
                val idx = bitclk.toInt().coerceIn(0, 15)
                syncbuf[idx] = 0.8 * syncbuf[idx] + 0.2 * mag
                var sum = 0.0; var amp = 0.0
                for (j in 0 until 8) { sum += syncbuf[j] - syncbuf[j + 8]; amp += syncbuf[j] + syncbuf[j + 8] }
                if (amp > 0) sum /= amp
                bitclk -= sum / 5.0
                bitclk += 1.0
                if (bitclk < 0) bitclk += steps
                if (bitclk >= steps) { bitclk -= steps; symbol(re, im, mag) }
            }
        }

        private fun symbol(re: Double, im: Double, mag: Double) {
            // phase of this symbol against the previous one
            val dr = re * prevRe + im * prevIm
            val di = im * prevRe - re * prevIm
            prevRe = re; prevIm = im
            var ph = atan2(di, dr)                                             // -π..π
            val bit = if (abs(ph) < PI / 2) 1 else 0
            // quality: how close to 0 or π (cos 2φ = 1 at both)
            qual = 0.95 * qual + 0.05 * cos(2 * ph)
            quality = qual.coerceIn(0.0, 1.0)
            ampAvg = 0.98 * ampAvg + 0.02 * mag
            if (afc && quality > 0.3) {
                if (ph > PI / 2) ph -= PI else if (ph < -PI / 2) ph += PI     // error from the nearest of 0, π
                centerHz += ph * baud / (2 * PI) * 0.05
            }
            shreg = (shreg shl 1) or bit
            if (shreg and 3 == 0) {
                val code = shreg shr 2
                if (code != 0 && quality >= squelch) VARIDECODE[code]?.let { if (it.code >= 32 || it == '\n') onText(it.toString()) }
                shreg = 0
            }
        }
    }

    // ------------------------------------------------------------------ RTTY (Baudot ITA2, 1 start, 5 data, 1.5 stop)

    private const val LTRS = 0x1F
    private const val FIGS = 0x1B
    private val LETTERS = charArrayOf('\u0000', 'E', '\n', 'A', ' ', 'S', 'I', 'U', '\r', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
        'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q', 'O', 'B', 'G', ' ', 'M', 'X', 'V', ' ')
    private val FIGURES = charArrayOf('\u0000', '3', '\n', '-', ' ', '\u0007', '8', '7', '\r', '$', '4', '\'', ',', '!', ':', '(',
        '5', '"', ')', '2', '#', '6', '0', '1', '9', '?', '&', ' ', '.', '/', ';', ' ')

    /** Baudot code of [c] and whether it needs figures, for tests and transmitters. */
    fun baudot(c: Char): Pair<Int, Boolean>? {
        val u = c.uppercaseChar()
        LETTERS.indexOf(u).takeIf { it > 0 && it != LTRS && it != FIGS }?.let { return it to false }
        FIGURES.indexOf(u).takeIf { it > 0 && it != LTRS && it != FIGS }?.let { return it to true }
        return null
    }

    class Rtty(center: Double, val baud: Double = 45.45, val shift: Double = 170.0, var reverse: Boolean = false, var unshiftOnSpace: Boolean = true) : Modem(center) {
        override val label = "RTTY " + (if (baud < 46) "45" else baud.toInt().toString()) + "/" + shift.toInt()
        private val decim = 4                                               // 2 kHz inside
        private val inner = RATE / decim.toDouble()
        private val markDc = Downconverter(RATE, baud * 0.75, decim)
        private val spaceDc = Downconverter(RATE, baud * 0.75, decim)
        private val bitLen = inner / baud
        private var state = 0                                               // 0 idle, 1 start, 2 data, 3 stop
        private var counter = 0.0
        private var bits = 0; private var nbits = 0
        private var figures = false
        private var mAvg = 1e-6; private var sAvg = 1e-6
        private var d = FloatArray(0)

        override fun process(x: FloatArray, n: Int) {
            val markHz = centerHz + (if (reverse) -shift else shift) / 2
            val spaceHz = centerHz - (if (reverse) -shift else shift) / 2
            val m = markDc.process(x, n, markHz)
            spaceDc.process(x, n, spaceHz)
            if (d.size < m) d = FloatArray(m)
            for (i in 0 until m) {
                val mk = sqrt((markDc.outRe[i] * markDc.outRe[i] + markDc.outIm[i] * markDc.outIm[i]).toDouble())
                val sp = sqrt((spaceDc.outRe[i] * spaceDc.outRe[i] + spaceDc.outIm[i] * spaceDc.outIm[i]).toDouble())
                // slow peak tracking of each tone (selective fading): decision on the normalised difference
                mAvg = if (mk > mAvg) 0.9 * mAvg + 0.1 * mk else 0.999 * mAvg + 0.001 * mk
                sAvg = if (sp > sAvg) 0.9 * sAvg + 0.1 * sp else 0.999 * sAvg + 0.001 * sp
                val v = mk / mAvg - sp / sAvg
                quality = (0.995 * quality + 0.005 * min(1.0, abs(mk - sp) / (mk + sp + 1e-9))).coerceIn(0.0, 1.0)
                uart(v > 0)
            }
        }

        private fun uart(mark: Boolean) {
            when (state) {
                0 -> if (!mark) { state = 1; counter = bitLen / 2 }              // falling edge: start bit
                1 -> if (--counter <= 0) { if (!mark) { state = 2; counter = bitLen; bits = 0; nbits = 0 } else state = 0 }
                2 -> if (--counter <= 0) {
                    if (mark) bits = bits or (1 shl nbits)
                    nbits++; counter = bitLen
                    if (nbits == 5) state = 3
                }
                3 -> if (--counter <= 0) {
                    if (mark) char(bits)                                         // stop bit present: a good character
                    state = 0
                }
            }
        }

        private fun char(code: Int) {
            when (code) {
                LTRS -> { figures = false; return }
                FIGS -> { figures = true; return }
            }
            val c = if (figures) FIGURES[code] else LETTERS[code]
            if (c == ' ' && unshiftOnSpace) figures = false
            if (c == '\u0000' || c == '\r' || c == '\u0007') return
            if (afc) { /* RTTY keeps the user's centre: the shift is fixed */ }
            onText(c.toString())
        }
    }

    // ------------------------------------------------------------------ CW (on / off keyed tone)

    class Cw(center: Double, wpm: Int = 18, val bandwidthHz: Double = 120.0) : Modem(center) {
        override val label = "CW"
        private val decim = 16                                              // 500 Hz envelope: 2 ms steps
        private val dc = Downconverter(RATE, bandwidthHz / 2, decim)
        private val decoder = CwDecoder(wpm)
        private var t = 0L                                                  // ms
        private var noise = 1e-4; private var peak = 1e-3
        private var down = false
        val wpm: Int get() = decoder.speedWpm
        /** Tone over the floor in the CW filter (dB), as a skimmer reports it. */
        val snrDb: Double get() = 20 * kotlin.math.log10(peak / noise.coerceAtLeast(1e-9)).coerceIn(0.0, 60.0)

        override fun process(x: FloatArray, n: Int) {
            val m = dc.process(x, n, centerHz)
            for (i in 0 until m) {
                val e = sqrt((dc.outRe[i] * dc.outRe[i] + dc.outIm[i] * dc.outIm[i]).toDouble())
                peak = if (e > peak) 0.7 * peak + 0.3 * e else max(0.9995 * peak, 1e-6)
                noise = if (e < noise) 0.7 * noise + 0.3 * e else min(noise * 1.0005, peak)
                val span = peak - noise
                quality = (span / (peak + 1e-12)).coerceIn(0.0, 1.0)
                val hi = noise + 0.6 * span; val lo = noise + 0.4 * span
                t += 2
                if (!down && e > hi && quality > 0.5) { down = true; decoder.keyDown(t) }
                else if (down && e < lo) { down = false; decoder.keyUp(t) }
                if (!down && (t % 10L == 0L)) decoder.tick(t).takeIf { it.isNotEmpty() }?.let(onText)
            }
        }
    }

    // ------------------------------------------------------------------ FFT and resampler (waterfall, 12 -> 8 kHz)

    /** In-place radix-2 FFT; [re]/[im] length is a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val w = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val c = cos(w * k); val s = sin(w * k)
                val a = i + k; val b = a + len / 2
                val tr = re[b] * c - im[b] * s; val ti = re[b] * s + im[b] * c
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }

    /** Spectrum in dB (0..[RATE]/2) of the last [size] samples, Hann window. */
    class Spectrum(val size: Int = 1024) {
        private val buf = DoubleArray(size); private var pos = 0
        private val win = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
        fun push(x: FloatArray, n: Int) { for (i in 0 until n) { buf[pos] = x[i].toDouble(); pos = (pos + 1) % size } }
        fun db(): FloatArray {
            val re = DoubleArray(size) { buf[(pos + it) % size] * win[it] }; val im = DoubleArray(size)
            fft(re, im)
            return FloatArray(size / 2) { (10 * kotlin.math.log10(re[it] * re[it] + im[it] * im[it] + 1e-12)).toFloat() }
        }
    }

    /** 12 kHz -> 8 kHz (×2, low-pass, ÷3), for the app receiver's audio. */
    class Resampler12to8 {
        private val fir = Dsp.RFir(Dsp.lowPass(63, 3_600.0, 24_000.0), 3)
        private var up = FloatArray(0)
        fun process(x: FloatArray, n: Int, out: FloatArray): Int {
            if (up.size < 2 * n) up = FloatArray(2 * n)
            for (i in 0 until n) { up[2 * i] = 2 * x[i]; up[2 * i + 1] = 0f }
            return fir.process(up, 2 * n, out)
        }
    }

    /** The modes offered on screen. */
    val MODES = listOf("CW", "RTTY 45/170", "RTTY 50/170", "RTTY 75/170", "RTTY 50/450", "RTTY 50/850", "BPSK31", "BPSK63", "BPSK125", "BPSK250", "NAVTEX", "DSC VHF", "DSC HF")

    fun create(mode: String, centerHz: Double): Modem = when {
        mode == "CW" -> Cw(centerHz)
        mode == "NAVTEX" -> Navtex.Decoder(centerHz)
        mode == "DSC VHF" -> Dsc.Decoder(centerHz, vhf = true)
        mode == "DSC HF" -> Dsc.Decoder(centerHz, vhf = false)
        mode.startsWith("RTTY") -> {
            val (b, s) = mode.removePrefix("RTTY ").split('/')
            Rtty(centerHz, if (b == "45") 45.45 else b.toDouble(), s.toDouble())
        }
        mode.startsWith("BPSK") -> Psk(centerHz, mode.removePrefix("BPSK").toDouble().let { if (it == 31.0) 31.25 else if (it == 63.0) 62.5 else it })
        else -> Psk(centerHz)
    }
}
