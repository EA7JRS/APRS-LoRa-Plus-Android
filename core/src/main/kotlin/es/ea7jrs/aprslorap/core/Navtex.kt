/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NAVTEX receiver (SITOR collective B mode, CCIR 476, 100 baud, 170 Hz shift) on audio from a radio in USB, ported from fldigi's
 * navtex.cxx (GPL-3, Rémi Chateauneu F4ECW and Rik van Riel AB1KW, after JNX by Paul Lutus): mark and space mixers, the "automatic
 * threshold correction" discriminator, early / prompt / late bit sync (multicorrelator), the search for the alpha / rep interleave and
 * the FEC repair from the repeated copy. The messages are split on "ZCZC B1B2B3B4" and "NNNN".
 *
 * Also: the broadcast slots of each station letter (every 4 hours, letter × 10 min), the subject letters, the station table (fldigi's
 * NAVTEX_Stations.csv) and the positions written in the text, for the map.
 */
object Navtex {
    const val DEVIATION = 85.0
    const val BAUD = 100.0

    // ------------------------------------------------------------------ CCIR 476 (7 bits, four marks)

    private const val L = "_______________________J___F_CK________W___Y_PQ______G___MX_V__________A___S_IU____D_RE__N__ ______Z_L___H__\n____OB_T___\r_______"
    private const val F = "_______________________'___!_:(________2___6_01______&___./_;__________-___\u0007_87____\$_43__,__ ______\"_)___#__\n____9?_5___\r_______"
    const val CODE_LTRS = 0x5a
    const val CODE_FIGS = 0x36
    const val CODE_ALPHA = 0x0f
    const val CODE_BETA = 0x33
    const val CODE_CHAR32 = 0x6a
    const val CODE_REP = 0x66

    fun validCode(code: Int) = Integer.bitCount(code and 0x7f) == 4

    /** Character of a code in letters or figures shift; null if the code has no character there. */
    fun codeToChar(code: Int, figs: Boolean): Char? = (if (figs) F else L)[code and 0x7f].takeIf { it != '_' }

    private val ltrsCode = IntArray(128); private val figsCode = IntArray(128)
    init {
        for (c in 0 until 128) if (validCode(c)) {
            L[c].let { if (it != '_') ltrsCode[it.code] = c }
            F[c].let { if (it != '_') figsCode[it.code] = c }
        }
    }

    /** Codes for [text] with the shifts it needs (upper case; characters without a code are dropped). */
    fun textToCodes(text: String): IntArray {
        val out = ArrayList<Int>()
        var figs = false
        for (raw in text.uppercase()) {
            val ch = raw.code
            if (ch >= 128) continue
            when {
                figs && figsCode[ch] != 0 -> out.add(figsCode[ch])
                !figs && ltrsCode[ch] != 0 -> out.add(ltrsCode[ch])
                figsCode[ch] != 0 -> { figs = true; out.add(CODE_FIGS); out.add(figsCode[ch]) }
                ltrsCode[ch] != 0 -> { figs = false; out.add(CODE_LTRS); out.add(ltrsCode[ch]) }
            }
        }
        return out.toIntArray()
    }

    /**
     * Interleaved SITOR-B stream: the first copy of each character goes in the even slots and its repeat 5 slots (35 bits) later in an odd
     * slot; phasing (rep / alpha) before, alpha after. One code per slot.
     */
    fun interleave(codes: IntArray, phasingPairs: Int = 20, tailPairs: Int = 8): IntArray {
        val pairs = phasingPairs + codes.size + 2 + tailPairs
        val out = IntArray(pairs * 2)
        for (j in 0 until pairs) {
            val k = j - phasingPairs
            out[2 * j] = when { k < 0 -> CODE_REP; k < codes.size -> codes[k]; else -> CODE_ALPHA }
            val r = j - 2 - phasingPairs
            out[2 * j + 1] = if (r in codes.indices) codes[r] else CODE_ALPHA
        }
        return out
    }

    /** Audio of a code stream (7 bits per code, least significant first, mark = centre + 85 Hz), continuous phase. For tests. */
    fun modulate(stream: IntArray, rate: Int = Modems.RATE, centerHz: Double = 1000.0, amplitude: Double = 0.5): FloatArray {
        val spb = rate / BAUD
        val n = (stream.size * 7 * spb).toInt()
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val bit = (i / spb).toInt()
            val code = stream[bit / 7]
            val mark = (code shr (bit % 7)) and 1 == 1
            ph += 2 * PI * (centerHz + if (mark) DEVIATION else -DEVIATION) / rate
            out[i] = (amplitude * sin(ph)).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------------ messages

    data class Message(val station: Char, val subject: Char, val number: Int, val text: String, val lostHeader: Boolean, val lostTrailer: Boolean,
                       val timeMs: Long) {
        val id get() = "$station$subject" + String.format(java.util.Locale.US, "%02d", number)
        val subjectName get() = subjectName(subject)
        /** Distress / SAR (D) or a search and rescue word: the alert window should open. */
        val urgent get() = subject == 'D'
    }

    fun subjectName(s: Char): String = when (s) {
        'A' -> "Navigational warning"; 'B' -> "Meteorological warning"; 'C' -> "Ice report"; 'D' -> "Search and rescue, piracy"
        'E' -> "Meteorological forecast"; 'F' -> "Pilot service"; 'G' -> "AIS"; 'H' -> "LORAN"; 'J' -> "Satellite navigation"
        'K' -> "Other navaids"; 'L' -> "Navigational warning (extra)"; 'T' -> "Test"; 'V' -> "Notice to fishermen"; 'W' -> "Environmental"
        'X', 'Y' -> "Special service"; 'Z' -> "No messages on hand"; else -> "Unknown subject"
    }

    /** Splits the character stream into messages ("ZCZC B1B2B3B4" ... "NNNN"), as fldigi does; a message is kept only if it has text. */
    class Assembler(private val onMessage: (Message) -> Unit, private val clock: () -> Long = { System.currentTimeMillis() }) {
        private val buf = StringBuilder()
        private var header = false
        private var station = '?'; private var subject = '?'; private var number = 0
        private var lastMs = 0L

        fun push(c: Char) {
            buf.append(c); lastMs = clock()
            if (buf.length > 6000) { flush(false, true); return }
            val n = buf.length
            if (n >= 10) {
                val h = buf.substring(n - 10)
                if (h.startsWith("ZCZC ") && h[5].isLetterOrDigit() && h[6].isLetterOrDigit() && h[7].isDigit() && h[8].isDigit() && (h[9] == '\n' || h[9] == '\r')) {
                    buf.setLength(n - 10)
                    if (buf.isNotBlank()) emit(buf.toString(), !header, true)
                    buf.setLength(0)
                    header = true; station = h[5]; subject = h[6]; number = (h[7] - '0') * 10 + (h[8] - '0')
                    return
                }
            }
            if (n >= 4 && buf.endsWith("NNNN")) { buf.setLength(n - 4); flush(!header, false) }
        }

        /** Ten minutes without characters ends a message without its trailer. */
        fun tick() { if (buf.isNotBlank() && clock() - lastMs > 600_000) flush(!header, true) }

        private fun flush(lostHeader: Boolean, lostTrailer: Boolean) {
            if (buf.isNotBlank()) emit(buf.toString(), lostHeader, lostTrailer)
            buf.setLength(0); header = false; station = '?'; subject = '?'; number = 0
        }

        private fun emit(raw: String, lostHeader: Boolean, lostTrailer: Boolean) {
            val text = clean(raw)
            if (text.length < 3) return
            onMessage(Message(if (lostHeader) '?' else station, if (lostHeader) '?' else subject, if (lostHeader) 0 else number, text, lostHeader, lostTrailer, clock()))
        }
    }

    /** Line breaks and spaces tidied up, as fldigi's cleanup. */
    fun clean(s: String): String {
        val o = StringBuilder()
        var delim = false; var space = false; var seen = false
        for (c in s) when (c) {
            '\n', '\r' -> delim = true
            ' ', '\t' -> space = true
            else -> {
                if (seen) { if (delim) o.append('\n') else if (space) o.append(' ') }
                delim = false; space = false; seen = true; o.append(c)
            }
        }
        return o.toString()
    }

    // ------------------------------------------------------------------ the receiver

    /**
     * Audio at [Modems.RATE] in, characters through [onText] and whole messages through [onMessage]. [reverse] for LSB. [centerHz] is the
     * middle of the two tones (1000 Hz by default); the AFC pulls it to the signal.
     */
    class Decoder(center: Double = 1000.0, var reverse: Boolean = false) : Modems.Modem(center) {
        override val label = "NAVTEX"
        var onMessage: (Message) -> Unit = {}
        /** 0 sync setup, 1 sync, 2 reading. */
        var state = 0; private set
        private val assembler = Assembler({ onMessage(it) })

        private val decim = 4
        private val inner = Modems.RATE / decim                               // 2 kHz inside
        private val markDc = Modems.Downconverter(Modems.RATE, BAUD * 0.65, decim)
        private val spaceDc = Modems.Downconverter(Modems.RATE, BAUD * 0.65, decim)
        private val bitSamples = inner / BAUD                               // 20
        private var sample = 0L
        private var nextEarly = 0.0; private var nextPrompt = bitSamples / 5; private var nextLate = bitSamples * 2 / 5
        private var accEarly = 0.0; private var accPrompt = 0.0; private var accLate = 0.0
        private var avgEarly = 0.0; private var avgPrompt = 0.0; private var avgLate = 0.0
        private var markEnv = 0.0; private var spaceEnv = 0.0; private var markNoise = 0.0; private var spaceNoise = 0.0
        private val bits = DoubleArray(BAUD.toInt())                        // one second of soft bits
        private var cursor = 0
        private var alphaPhase = false
        private var shift = false
        private var errors = 0
        private var lastChar = 0
        // AFC: spectrum of the last 1024 samples (7.8 Hz bins) every half second
        private val spec = Modems.Spectrum(1024)
        private var afcCount = 0

        private fun decay(avg: Double, v: Double, w: Double) = avg + (v - avg) / w

        override fun process(x: FloatArray, n: Int) {
            spec.push(x, n)
            afcCount += n
            if (afcCount >= Modems.RATE / 2) { afcCount = 0; if (afc) afc(); assembler.tick() }
            val m = markDc.process(x, n, centerHz + DEVIATION)
            spaceDc.process(x, n, centerHz - DEVIATION)
            for (i in 0 until m) {
                val mk = sqrt((markDc.outRe[i] * markDc.outRe[i] + markDc.outIm[i] * markDc.outIm[i]).toDouble())
                val sp = sqrt((spaceDc.outRe[i] * spaceDc.outRe[i] + spaceDc.outIm[i] * spaceDc.outIm[i]).toDouble())
                sampleIn(mk, sp)
            }
        }

        private fun sampleIn(markAbs0: Double, spaceAbs0: Double) {
            multicorrelator()
            markEnv = if (markAbs0 > markEnv) decay(markEnv, markAbs0, bitSamples / 4) else decay(markEnv, markAbs0, bitSamples * 16)
            spaceEnv = if (spaceAbs0 > spaceEnv) decay(spaceEnv, spaceAbs0, bitSamples / 4) else decay(spaceEnv, spaceAbs0, bitSamples * 16)
            markNoise = if (markAbs0 < markNoise) decay(markNoise, markAbs0, bitSamples / 4) else decay(markNoise, markAbs0, bitSamples * 48)
            spaceNoise = if (spaceAbs0 < spaceNoise) decay(spaceNoise, spaceAbs0, bitSamples / 4) else decay(spaceNoise, spaceAbs0, bitSamples * 48)
            val floor = (markNoise + spaceNoise) / 2
            val mk = markAbs0.coerceAtMost(markEnv).coerceAtLeast(floor)
            val sp = spaceAbs0.coerceAtMost(spaceEnv).coerceAtLeast(floor)
            // mark-space discriminator with automatic threshold correction (W7AY), normalised to about -1..1
            val me = markEnv - floor; val se = spaceEnv - floor
            val logic = (mk - floor) * me - (sp - floor) * se - 0.5 * (me * me - se * se)
            val v = logic / (me * me + se * se + 1e-12)
            accEarly += v; accPrompt += v; accLate += v
            quality = (0.998 * quality + 0.002 * (abs(me - se) / (me + se + 1e-12)).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
            if (sample >= nextEarly) { avgEarly = decay(avgEarly, abs(accEarly), 64.0); nextEarly += bitSamples; accEarly = 0.0 }
            if (sample >= nextLate) { avgLate = decay(avgLate, abs(accLate), 64.0); nextLate += bitSamples; accLate = 0.0 }
            if (sample >= nextPrompt) {
                avgPrompt = decay(avgPrompt, abs(accPrompt), 64.0)
                nextPrompt += bitSamples
                val b = if (reverse) -accPrompt else accPrompt
                accPrompt = 0.0
                if (state == 0) { errors = 0; shift = false; state = 1 }
                bit(b)
            }
            sample++
        }

        /** Moves the sampling instant to where the accumulated bit is largest (every 8 bits). */
        private fun multicorrelator() {
            if (sample % (bitSamples * 8).toLong() != 0L) return
            var slope = avgLate - avgEarly
            if (avgPrompt * 1.05 < avgEarly && avgPrompt * 1.05 < avgLate) {
                if (avgEarly > avgLate) {
                    slope = (nextEarly - nextPrompt - bitSamples) % bitSamples
                    avgLate = avgPrompt; avgPrompt = avgEarly
                } else {
                    slope = (nextLate - nextPrompt + bitSamples) % bitSamples
                    avgEarly = avgPrompt; avgPrompt = avgLate
                }
            } else slope = slope / (avgPrompt + 1e-9) * bitSamples / 40.0     // fldigi: /1024 on its scale; here at most ~half a sample
            if (slope != 0.0) { nextEarly += slope; nextPrompt += slope; nextLate += slope }
        }

        private fun bit(value: Double) {
            System.arraycopy(bits, 1, bits, 0, bits.size - 1)
            bits[bits.size - 1] = value
            if (cursor > 0) cursor--
            if (state == 1) {
                val off = findAlpha()
                if (off >= 0) { state = 2; cursor = off; alphaPhase = true } else state = 0
            }
            if (state == 2 && cursor < bits.size - 7) {
                if (alphaPhase) {
                    errors -= bytes(cursor)
                    if (errors > 5) state = 0
                    if (errors < 0) errors = 0
                }
                alphaPhase = !alphaPhase
                cursor += 7
            }
        }

        private fun codeAt(a: DoubleArray, p: Int): Int { var c = 0; for (i in 0 until 7) if (a[p + i] > 0) c = c or (1 shl i); return c }
        private fun validAt(p: Int): Boolean { var k = 0; for (i in 0 until 7) if (bits[p + i] > 0) k++; return k == 4 }

        /** Where in the last second the characters line up best with their repeats (fldigi find_alpha_characters). */
        private fun findAlpha(): Int {
            var bestOff = 0; var bestScore = 0
            for (off in 35 until 35 + 14) {
                var score = 0; var reps = 0
                var i = off
                while (i < bits.size - 7) {
                    if (validAt(i)) {
                        val code = codeAt(bits, i); val rep = codeAt(bits, i - 35)
                        score++
                        if (code == rep) {
                            if (code == CODE_ALPHA || code == CODE_REP) { score = 0; i += 7; continue }
                            reps++
                        } else if (code == CODE_ALPHA && codeAt(bits, i - 7) == CODE_REP) reps++
                    }
                    i += 7
                }
                if (reps >= 3 && score + reps > bestScore) { bestScore = score + reps; bestOff = off }
            }
            return if (bestScore > 8) bestOff else -1
        }

        private fun flipSmallest(a: DoubleArray, p: Int) {
            var minZero = Double.NEGATIVE_INFINITY; var minOne = Double.POSITIVE_INFINITY
            var zPos = -1; var oPos = -1; var zeros = 0; var ones = 1                 // fldigi starts the ones at 1: kept as it decodes
            for (i in 0 until 7) {
                val v = a[p + i]
                if (v < 0) { zeros++; if (v > minZero) { minZero = v; zPos = i } } else { ones++; if (v < minOne) { minOne = v; oPos = i } }
            }
            if (zeros == 4 && zPos >= 0) a[p + zPos] = -a[p + zPos] else if (ones == 5 && oPos >= 0) a[p + oPos] = -a[p + oPos]
        }

        /** 1 good alpha, 0 rep used as is, -1 repaired, -2 lost (fldigi process_bytes). */
        private fun bytes(p: Int): Int {
            var code = codeAt(bits, p)
            if (validCode(code)) { char(code); return 1 }
            val rp = p - 35
            if (rp < 0) return -1
            val rep = codeAt(bits, rp)
            if (validCode(rep)) { if (rep == CODE_REP) return 0; char(rep); return 0 }
            val avg = DoubleArray(7) { bits[p + it] + bits[rp + it] }
            code = codeAt(avg, 0); if (validCode(code)) { char(code); return -1 }
            flipSmallest(bits, p); code = codeAt(bits, p); if (validCode(code)) { char(code); return -1 }
            flipSmallest(bits, rp); code = codeAt(bits, rp); if (validCode(code)) { char(code); return -1 }
            flipSmallest(avg, 0); code = codeAt(avg, 0); if (validCode(code)) { char(code); return -1 }
            return -2
        }

        private fun char(code: Int) {
            when (code) {
                CODE_REP -> if (lastChar == CODE_REP) alphaPhase = false                  // two reps: rep / alpha phase was wrong
                CODE_ALPHA, CODE_BETA, CODE_CHAR32 -> {}
                CODE_LTRS -> shift = false
                CODE_FIGS -> shift = true
                else -> codeToChar(code, shift)?.let { c ->
                    val out = if (c == '\u0007') '\'' else c
                    if (out != '\r') onText(out.toString())
                    assembler.push(c)
                }
            }
            lastChar = code
        }

        /** Pulls the centre to the strongest pair of tones 170 Hz apart within ±150 Hz (fast while searching, 3 Hz a step while reading). */
        private fun afc() {
            val db = spec.db()
            val hz = Modems.RATE.toDouble() / 1024
            fun p(f: Double): Double { val k = (f / hz).toInt(); if (k < 1 || k >= db.size - 1) return 0.0; return Math.pow(10.0, maxOf(db[k - 1], db[k], db[k + 1]) / 10.0) }
            var best = centerHz; var bestP = 0.0
            var f = centerHz - 150
            while (f <= centerHz + 150) { val s = p(f - DEVIATION) + p(f + DEVIATION); if (s > bestP) { bestP = s; best = f }; f += hz / 2 }
            val next = if (state == 2) centerHz + (best - centerHz).coerceIn(-3.0, 3.0) else best
            if (abs(next - centerHz) > 1.0) centerHz = next
        }
    }

    // ------------------------------------------------------------------ stations and schedule

    data class Station(val country: String, val khz: Double, val id: Char, val callsign: String, val name: String, val lat: Double, val lon: Double,
                       val navarea: String, val language: String)

    /** fldigi's NAVTEX_Stations.csv: country;code;kHz;id;callsign;name;"38 32 N";"28 38 W";NAVAREA;language. */
    fun parseStations(csv: String): List<Station> = csv.lineSequence().mapNotNull { line ->
        val f = line.split(';')
        if (f.size < 10) return@mapNotNull null
        fun ang(s: String): Double? {
            val p = s.trim().split(Regex("\\s+")); if (p.size != 3) return null
            val v = (p[0].toDoubleOrNull() ?: return null) + (p[1].toDoubleOrNull() ?: return null) / 60.0
            return if (p[2] == "S" || p[2] == "W") -v else v
        }
        val lat = ang(f[6]) ?: return@mapNotNull null; val lon = ang(f[7]) ?: return@mapNotNull null
        val khz = f[2].toDoubleOrNull() ?: return@mapNotNull null
        val id = f[3].trim().firstOrNull() ?: return@mapNotNull null
        Station(f[0].trim(), khz, id, f[4].trim(), f[5].trim(), lat, lon, f[8].trim(), f[9].trim())
    }.toList()

    /** The station a message with [id] most likely came from: that letter on that frequency, nearest to [lat] / [lon]. */
    fun findStation(stations: List<Station>, id: Char, khz: Double, lat: Double?, lon: Double?): Station? {
        val c = stations.filter { it.id == id && abs(it.khz - khz) < 1.0 }
        if (c.isEmpty()) return null
        if (lat == null || lon == null) return c.first()
        return c.minByOrNull { Geo.distanceKm(lat, lon, it.lat, it.lon) }
    }

    /** Minutes after midnight UTC of the first slot of a station letter (A = 00:00, B = 00:10 ... X = 03:50; every 4 hours). */
    fun firstSlotMin(id: Char): Int? = (id.uppercaseChar() - 'A').takeIf { it in 0..23 }?.let { it * 10 }

    /** Unix time in ms of the next scheduled broadcast of [id] from [nowMs] (the slot in progress counts for 10 minutes). */
    fun nextSlotMs(id: Char, nowMs: Long): Long? {
        val first = firstSlotMin(id) ?: return null
        val day = nowMs - nowMs % 86_400_000L
        for (k in -1..6) {
            val t = day + (first + k * 240) * 60_000L
            if (t + 600_000L > nowMs) return t
        }
        return null
    }

    // ------------------------------------------------------------------ positions in the text

    /**
     * Positions written as in navigational warnings: "36-07.5N 005-21.2W", "42 11,718N 009 59,778E", "36-31N 006-17W" (as the plotter's
     * navwarn) and the compact NAVTEX form "3607.5N 00521.2W" / "3631N 00617W".
     */
    fun positions(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < s.length) {
            if (!s[i].isDigit() || (i > 0 && (s[i - 1].isDigit() || s[i - 1] == '.' || s[i - 1] == ','))) { i++; continue }
            val la = coord(s, i, 2, 'N', 'S')
            if (la == null) { i++; continue }
            var p = la.second
            while (p < s.length && (s[p] == ' ' || s[p] == ',' || s[p] == '-' || s[p] == '/' || s[p] == '\n' || s[p] == '\r')) p++
            val lo = coord(s, p, 3, 'E', 'W')
            if (lo == null || abs(la.first) > 90 || abs(lo.first) > 180) { i++; continue }
            out.add(la.first to lo.first)
            i = lo.second
        }
        return out
    }

    private fun coord(s: String, i: Int, degDigits: Int, pos: Char, neg: Char): Pair<Double, Int>? {
        var p = i
        var deg = 0
        repeat(degDigits) { if (p >= s.length || !s[p].isDigit()) return null; deg = deg * 10 + (s[p++] - '0') }
        val compact = p + 1 < s.length && s[p].isDigit() && s[p + 1].isDigit()
        if (!compact) {
            if (p < s.length && s[p].isDigit()) return null
            if (p >= s.length || (s[p] != '-' && s[p] != ' ')) return null
            p++
        }
        if (p + 1 >= s.length || !s[p].isDigit() || !s[p + 1].isDigit()) return null
        var min = ((s[p] - '0') * 10 + (s[p + 1] - '0')).toDouble()
        p += 2
        if (compact && p < s.length && s[p].isDigit()) return null                  // too many digits: not a position
        if (p + 1 < s.length && (s[p] == '.' || s[p] == ',') && s[p + 1].isDigit()) {
            p++; var scale = 0.1
            while (p < s.length && s[p].isDigit()) { min += (s[p++] - '0') * scale; scale /= 10 }
        }
        while (p < s.length && s[p] == ' ') p++
        if (p >= s.length || (s[p] != pos && s[p] != neg)) return null
        if (p + 1 < s.length && s[p + 1].isLetter()) return null                      // "N" alone, not "NE" or a word
        if (min >= 60.0) return null
        val v = deg + min / 60.0
        return (if (s[p] == neg) -v else v) to p + 1
    }
}
