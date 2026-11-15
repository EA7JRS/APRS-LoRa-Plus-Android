/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * Digital Selective Calling (ITU-R M.493): VHF channel 70 (1200 baud, 1300 / 2100 Hz on FM) and MF / HF (100 baud, 170 Hz shift).
 * The symbol layout, the phasing patterns, the time-diversity selection with soft decisions and the call fields follow the DSC
 * decoder of SDRangel (sdrbase/util/dsc.cpp and plugins/channelrx/demoddsc, Copyright (C) 2023 Jon Beniston M7RCE,
 * GPL-3.0-or-later), written again here; the fields of relays and acknowledgements and the coordinates are read as M.493 gives them.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

object Dsc {
    const val FMT_AREA = 102
    const val FMT_DISTRESS = 112
    const val FMT_GROUP = 114
    const val FMT_ALL_SHIPS = 116
    const val FMT_INDIVIDUAL = 120
    const val FMT_AUTOMATIC = 123
    const val CAT_ROUTINE = 100
    const val CAT_SAFETY = 108
    const val CAT_URGENCY = 110
    const val CAT_DISTRESS = 112
    const val TC_DISTRESS_ACK = 110
    const val TC_DISTRESS_RELAY = 112
    const val EOS_REQ = 117
    const val EOS_ACK = 122
    const val EOS = 127
    /** Channel 70. */
    const val CH70_HZ = 156_525_000L
    const val VHF_BAUD = 1200.0
    /** VHF tones: binary 1 (Y) is the lower one. */
    const val VHF_F1 = 1300.0
    const val VHF_F0 = 2100.0
    const val HF_BAUD = 100.0
    const val HF_SHIFT = 170.0
    /** MF / HF DSC channels (kHz). */
    val HF_KHZ = listOf(2187.5, 4207.5, 6312.0, 8414.5, 12577.0, 16804.5)

    // ------------------------------------------------------------------ 10-unit symbols

    /** The 10 bits of [sym] in transmission order (bit 9 of the result goes first): 7 information bits, least significant first,
     *  then the number of 0 bits among them, most significant first. */
    fun codeword(sym: Int): Int {
        var w = 0
        for (i in 0 until 7) w = (w shl 1) or ((sym shr i) and 1)
        val zeros = 7 - Integer.bitCount(sym and 0x7F)
        for (i in 2 downTo 0) w = (w shl 1) or ((zeros shr i) and 1)
        return w
    }

    /** The symbol of a 10-bit word, or -1 if its check bits do not match. */
    fun symbolOf(word: Int): Int {
        var s = 0
        for (i in 0 until 7) if ((word shr (9 - i)) and 1 == 1) s = s or (1 shl i)
        return if (7 - Integer.bitCount(s) == (word and 7)) s else -1
    }

    private fun metric(sym: Int, soft: FloatArray): Float {
        val zeros = 7 - Integer.bitCount(sym)
        var m = 0f
        for (i in 0 until 7) m += if ((sym shr i) and 1 == 1) soft[i] else -soft[i]
        for (i in 0 until 3) m += if ((zeros shr (2 - i)) and 1 == 1) soft[7 + i] else -soft[7 + i]
        return m
    }

    // ------------------------------------------------------------------ the call

    data class Call(
        val symbols: IntArray,
        val format: Int,
        /** Station called (MMSI, 9 digits) for individual, group and automatic calls. */
        val address: String?,
        /** Area of an area call: NW corner and size in degrees. */
        val area: Area?,
        val category: Int,
        /** The calling station (for a distress alert, the ship in distress). */
        val selfId: String,
        val tc1: Int?, val tc2: Int?,
        /** Ship in distress named by a relay or an acknowledgement. */
        val distressId: String?,
        val nature: Int?,
        val lat: Double?, val lon: Double?,
        /** The 10 position digits as sent (quadrant, latitude, longitude), or null. */
        val positionDigits: String?,
        /** UTC of the position, "hhmm", or null. */
        val time: String?,
        val subsequent: Int?,
        val rxHz: Long?, val rxChannel: String?, val txHz: Long?, val txChannel: String?,
        /** The frequency digits as sent (for the NMEA sentence). */
        val freqDigits: String?,
        val number: String?,
        val eos: Int,
        val eccOk: Boolean,
        val valid: Boolean,
        /** Copies that disagreed or had detectable errors. */
        val errors: Int = 0,
        val timeMs: Long = 0,
    ) {
        val distress get() = format == FMT_DISTRESS || category == CAT_DISTRESS
        /** The ship the alert is about: the sender of a distress alert, the one named by a relay or an acknowledgement. */
        val distressShip get() = if (format == FMT_DISTRESS) selfId else distressId
        /** Joins the repeats of one call. */
        val key get() = symbols.joinToString(",")
        override fun equals(other: Any?) = other is Call && other.symbols.contentEquals(symbols) && other.timeMs == timeMs
        override fun hashCode() = symbols.contentHashCode() * 31 + timeMs.hashCode()

        /** One line for the screen and the log. */
        fun summary(): String {
            val s = ArrayList<String>()
            s.add(formatName(format) + (if (format == FMT_DISTRESS) "" else " · " + categoryName(category)))
            s.add("from " + selfId)
            address?.let { s.add("to $it") }
            area?.let { s.add("area " + it.text()) }
            tc1?.let { s.add(tc1Name(it)) }
            tc2?.takeIf { it != 126 }?.let { s.add(tc2Name(it)) }
            distressId?.let { s.add("distress $it") }
            nature?.let { s.add(natureName(it)) }
            if (lat != null && lon != null) s.add(Fmt2.pos(lat, lon) + (time?.let { " at ${it.take(2)}:${it.drop(2)} UTC" } ?: ""))
            else if (positionDigits != null || distress) s.add("position not given")
            rxChannel?.let { s.add("ch $it") } ?: rxHz?.let { s.add(Fmt2.khz(it) + " kHz") }
            if (txChannel != null && txChannel != rxChannel) s.add("tx ch $txChannel") else txHz?.takeIf { it != rxHz }?.let { s.add("tx " + Fmt2.khz(it) + " kHz") }
            number?.let { s.add("tel $it") }
            subsequent?.let { s.add("then " + tc1Name(it)) }
            if (eos != EOS) s.add(eosName(eos))
            if (!valid) s.add("INVALID")
            return s.joinToString(" · ")
        }
    }

    data class Area(val lat: Int, val lon: Int, val dLat: Int, val dLon: Int) {
        fun text() = Fmt2.deg(lat, true) + " " + Fmt2.deg(lon, false) + " Δ${dLat}° × ${dLon}°"
        /** Whether a position is inside (the area extends south and east of the NW corner). */
        fun contains(la: Double, lo: Double) = la <= lat && la >= lat - dLat && lo >= lon && lo <= lon + dLon
    }

    private object Fmt2 {
        fun pos(lat: Double, lon: Double): String {
            fun one(v: Double, d: Int, p: Char, n: Char): String {
                val a = abs(v); val dd = a.toInt(); val mm = ((a - dd) * 60).roundToInt()
                return String.format(java.util.Locale.US, "%0${d}d°%02d'%c", dd, mm, if (v >= 0) p else n)
            }
            return one(lat, 2, 'N', 'S') + " " + one(lon, 3, 'E', 'W')
        }
        fun deg(v: Int, lat: Boolean) = String.format(java.util.Locale.US, if (lat) "%02d°%c" else "%03d°%c", abs(v), if (lat) (if (v >= 0) 'N' else 'S') else (if (v >= 0) 'E' else 'W'))
        fun khz(hz: Long) = if (hz % 1000 == 0L) (hz / 1000).toString() else String.format(java.util.Locale.US, "%.1f", hz / 1000.0)
    }

    fun formatName(f: Int) = when (f) {
        FMT_AREA -> "Area call"; FMT_DISTRESS -> "DISTRESS ALERT"; FMT_GROUP -> "Group call"; FMT_ALL_SHIPS -> "All ships"
        FMT_INDIVIDUAL -> "Individual call"; FMT_AUTOMATIC -> "Automatic call"; else -> "Format $f"
    }
    fun categoryName(c: Int) = when (c) { CAT_ROUTINE -> "Routine"; CAT_SAFETY -> "Safety"; CAT_URGENCY -> "Urgency"; CAT_DISTRESS -> "Distress"; else -> "Category $c" }
    fun natureName(n: Int) = when (n) {
        100 -> "Fire, explosion"; 101 -> "Flooding"; 102 -> "Collision"; 103 -> "Grounding"; 104 -> "Listing, danger of capsizing"; 105 -> "Sinking"
        106 -> "Disabled and adrift"; 107 -> "Undesignated distress"; 108 -> "Abandoning ship"; 109 -> "Piracy / armed attack"; 110 -> "Man overboard"
        112 -> "EPIRB emission"; else -> "Nature $n"
    }
    fun tc1Name(t: Int) = when (t) {
        100 -> "FM voice (simplex)"; 101 -> "FM voice (duplex)"; 103 -> "Polling"; 104 -> "Unable to comply"; 105 -> "End of call"; 106 -> "Data"
        109 -> "SSB voice"; 110 -> "Distress acknowledgement"; 112 -> "Distress relay"; 113 -> "TTY FEC"; 115 -> "TTY ARQ"; 118 -> "Test"
        121 -> "Position update"; 126 -> "No information"; else -> "Telecommand $t"
    }
    fun tc2Name(t: Int) = when (t) {
        100 -> "No reason"; 101 -> "Congestion"; 102 -> "Busy"; 103 -> "Queue"; 104 -> "Station barred"; 105 -> "No operator"; 106 -> "Operator unavailable"
        107 -> "Equipment disabled"; 108 -> "Unable to use channel"; 109 -> "Unable to use mode"; 110 -> "Not party to a conflict"; 111 -> "Medical transport"
        112 -> "Pay-phone"; 113 -> "Fax / data"; 126 -> "No information"; else -> "Telecommand $t"
    }
    fun eosName(e: Int) = when (e) { EOS_REQ -> "acknowledgement requested"; EOS_ACK -> "acknowledgement"; EOS -> "end"; else -> "EOS $e" }

    /** What kind of station an MMSI is (ITU-R M.585). */
    fun mmsiKind(m: String) = when {
        m.startsWith("00") -> "coast station"
        m.startsWith("0") -> "group"
        m.startsWith("111") -> "SAR aircraft"
        m.startsWith("970") -> "AIS-SART"
        m.startsWith("972") -> "MOB device"
        m.startsWith("974") -> "EPIRB-AIS"
        m.startsWith("98") -> "craft with a parent ship"
        m.startsWith("99") -> "aid to navigation"
        else -> "ship"
    }

    /** The country code (MID) of an MMSI, or null. */
    fun mid(m: String): Int? = when {
        m.startsWith("00") -> m.drop(2).take(3)
        m.startsWith("0") -> m.drop(1).take(3)
        m.startsWith("111") || m.startsWith("98") || m.startsWith("99") -> m.drop(if (m.startsWith("111")) 3 else 2).take(3)
        m.startsWith("97") -> null
        else -> m.take(3)
    }?.toIntOrNull()

    // ------------------------------------------------------------------ reading the symbols

    private class Reader(val s: IntArray) {
        var i = 0
        fun next(): Int = if (i < s.size) s[i++] else { i++; -1 }
        fun peek(k: Int = 0): Int = if (i + k < s.size) s[i + k] else -1
        fun left() = s.size - i
        fun digits(n: Int): String? {
            val b = StringBuilder()
            repeat(n) { val v = next(); if (v !in 0..99) return null; if (v < 10) b.append('0'); b.append(v) }
            return b.toString()
        }
    }

    private fun mmsiOf(d: String?): String? = d?.let { if (it.length == 10) it.take(9) + (if (it[9] == '0') "" else "-" + it[9]) else null }

    /** Latitude and longitude of the 10 position digits, or null (9999999999 = not given). */
    fun position(d: String?): Pair<Double, Double>? {
        if (d == null || d.length != 10 || d == "9999999999") return null
        val q = d[0]
        val la = d.substring(1, 3).toInt() + d.substring(3, 5).toInt() / 60.0
        val lo = d.substring(5, 8).toInt() + d.substring(8, 10).toInt() / 60.0
        if (d.substring(3, 5).toInt() >= 60 || d.substring(8, 10).toInt() >= 60 || la > 90 || lo > 180) return null
        return when (q) { '0' -> la to lo; '1' -> la to -lo; '2' -> -la to lo; '3' -> -la to -lo; else -> null }
    }

    private fun area(d: String?): Area? {
        if (d == null || d.length != 10) return null
        var la = d.substring(1, 3).toInt(); var lo = d.substring(3, 6).toInt()
        when (d[0]) { '1' -> lo = -lo; '2' -> la = -la; '3' -> { la = -la; lo = -lo } }
        return Area(la, lo, d.substring(6, 8).toInt(), d.substring(8, 10).toInt())
    }

    private class Freq(val hz: Long?, val channel: String?, val digits: String?)

    private fun freq(r: Reader): Freq {
        if (r.peek() == 126 && r.peek(1) == 126 && r.peek(2) == 126) { r.i += 3; return Freq(null, null, null) }
        var d = r.digits(3) ?: return Freq(null, null, null)
        if (d[0] == '4') d += r.digits(1) ?: return Freq(null, null, d)
        return when (d[0]) {
            '0', '1', '2' -> Freq(d.toLong() * 100, null, d)
            '3' -> Freq(null, d.substring(1).trimStart('0').ifEmpty { "0" }, d)
            '4' -> Freq(d.substring(1).toLong() * 10, null, d)
            '9' -> Freq(null, d.substring(2).trimStart('0').ifEmpty { "0" }, d)
            else -> Freq(null, null, d)
        }
    }

    /** The call in [symbols] (both format specifiers first, the end-of-sequence symbol and the ECC last). */
    fun parse(symbols: IntArray, errors: Int = 0, timeMs: Long = 0): Call {
        val r = Reader(symbols)
        val fmt = r.next()
        val fmtMatch = r.next() == fmt
        var address: String? = null
        var area: Area? = null
        val cat: Int
        if (fmt != FMT_DISTRESS) {
            if (fmt != FMT_ALL_SHIPS) {
                val d = r.digits(5)
                if (fmt == FMT_AREA) area = area(d) else address = mmsiOf(d)
            }
            cat = r.next()
        } else cat = CAT_DISTRESS
        val self = mmsiOf(r.digits(5)) ?: ""
        var tc1: Int? = null; var tc2: Int? = null
        if (fmt != FMT_DISTRESS) { tc1 = r.next(); if (cat != CAT_DISTRESS) tc2 = r.next() }
        var distressId: String? = null
        var nature: Int? = null; var posD: String? = null; var time: String? = null; var sub: Int? = null
        var rx: Freq? = null; var tx: Freq? = null; var number: String? = null
        if (fmt != FMT_DISTRESS && cat == CAT_DISTRESS) distressId = mmsiOf(r.digits(5))
        if (fmt == FMT_DISTRESS || (cat == CAT_DISTRESS && r.left() >= 11)) {
            nature = r.next(); posD = r.digits(5)
            time = r.digits(2)?.takeIf { it != "8888" }
            sub = r.next()
        } else if (cat != CAT_DISTRESS) {
            if (r.peek() == 55) posD = r.digits(5)
            else {
                rx = freq(r)
                if (fmt != FMT_AUTOMATIC) tx = freq(r)
                else {
                    val odd = r.next()
                    number = r.digits((r.left() - 2).coerceAtLeast(0))?.let { if (odd == 105) it.drop(1) else it }
                }
            }
        }
        val consumed = r.i
        val eos = if (symbols.size >= 2) symbols[symbols.size - 2] else -1
        val ecc = symbols.lastOrNull() ?: -1
        var x = 0
        for (k in 1 until symbols.size - 1) x = x xor symbols[k]
        val eccOk = symbols.size >= 4 && x == ecc
        val p = position(posD)
        val valid = fmtMatch && fmt in listOf(FMT_AREA, FMT_DISTRESS, FMT_GROUP, FMT_ALL_SHIPS, FMT_INDIVIDUAL, FMT_AUTOMATIC) &&
            cat in listOf(CAT_ROUTINE, CAT_SAFETY, CAT_URGENCY, CAT_DISTRESS) && eos in listOf(EOS_REQ, EOS_ACK, EOS) &&
            symbols.none { it < 0 } && eccOk && consumed == symbols.size - 2 && self.isNotEmpty() &&
            (nature == null || nature in 100..112) && (posD == null || posD == "9999999999" || p != null)
        return Call(symbols.copyOf(), fmt, address, area, cat, self, tc1, tc2, distressId, nature, p?.first, p?.second, posD, time, sub,
            rx?.hz, rx?.channel, tx?.hz, tx?.channel, rx?.digits, number, eos, eccOk, valid, errors, timeMs)
    }

    // ------------------------------------------------------------------ building calls (tests, simulators)

    fun mmsiSymbols(mmsi: String, ext: Int = 0): List<Int> {
        val d = mmsi.filter { it.isDigit() }.padStart(9, '0').take(9) + ext
        return (0 until 5).map { d.substring(2 * it, 2 * it + 2).toInt() }
    }

    fun positionSymbols(lat: Double?, lon: Double?): List<Int> {
        if (lat == null || lon == null) return List(5) { 99 }
        val q = if (lat >= 0) (if (lon >= 0) 0 else 1) else (if (lon >= 0) 2 else 3)
        fun dm(v: Double): Pair<Int, Int> { var d = abs(v).toInt(); var m = ((abs(v) - d) * 60).roundToInt(); if (m == 60) { d++; m = 0 }; return d to m }
        val (ad, am) = dm(lat); val (od, om) = dm(lon)
        val s = String.format(java.util.Locale.US, "%d%02d%02d%03d%02d", q, ad, am, od, om)
        return (0 until 5).map { s.substring(2 * it, 2 * it + 2).toInt() }
    }

    fun timeSymbols(hhmm: String?): List<Int> = if (hhmm == null || hhmm.length != 4) listOf(88, 88) else listOf(hhmm.take(2).toInt(), hhmm.drop(2).toInt())

    /** VHF channel [ch] as the 3 frequency symbols (90 0n nn). */
    fun channelSymbols(ch: Int?): List<Int> = if (ch == null) listOf(126, 126, 126) else listOf(90, ch / 100, ch % 100)

    /** Adds the ECC to a call that ends with its end-of-sequence symbol. */
    fun withEcc(msg: List<Int>): IntArray {
        var x = 0
        for (k in 1 until msg.size) x = x xor msg[k]
        return (msg + x).toIntArray()
    }

    fun distressAlert(self: String, nature: Int, lat: Double?, lon: Double?, hhmm: String?, subsequent: Int = 100): IntArray =
        withEcc(listOf(FMT_DISTRESS, FMT_DISTRESS) + mmsiSymbols(self) + nature + positionSymbols(lat, lon) + timeSymbols(hhmm) + subsequent + EOS)

    /** Relay (tc1 112) or acknowledgement (tc1 110) of a distress alert, to all ships or to [to]. */
    fun distressRelay(self: String, distressId: String, nature: Int, lat: Double?, lon: Double?, hhmm: String?, tc1: Int = TC_DISTRESS_RELAY,
                      to: String? = null, subsequent: Int = 100, eos: Int = EOS): IntArray {
        val fmt = if (to == null) FMT_ALL_SHIPS else FMT_INDIVIDUAL
        return withEcc(listOf(fmt, fmt) + (to?.let { mmsiSymbols(it) } ?: emptyList()) + CAT_DISTRESS + mmsiSymbols(self) + tc1 +
            mmsiSymbols(distressId) + nature + positionSymbols(lat, lon) + timeSymbols(hhmm) + subsequent + eos)
    }

    /** Individual (with [to]) or all-ships call proposing VHF channel [ch]. */
    fun call(self: String, to: String?, category: Int, ch: Int?, tc1: Int = 100, tc2: Int = 126, eos: Int = if (to == null) EOS else EOS_REQ): IntArray {
        val fmt = if (to == null) FMT_ALL_SHIPS else FMT_INDIVIDUAL
        return withEcc(listOf(fmt, fmt) + (to?.let { mmsiSymbols(it) } ?: emptyList()) + category + mmsiSymbols(self) + tc1 + tc2 +
            channelSymbols(ch) + listOf(126, 126, 126) + eos)
    }

    /** The bits on the air: dot pattern, then DX / RX with phasing and time diversity, the end-of-sequence symbol three times in DX. */
    fun transmission(msg: IntArray, dotBits: Int = 20): IntArray {
        val eos = msg[msg.size - 2]
        val dx = List(6) { 125 } + msg.toList() + listOf(eos, eos)
        val rx = (111 downTo 104).toList() + msg.toList()
        val bits = ArrayList<Int>()
        repeat(dotBits) { bits.add(if (it % 2 == 0) 1 else 0) }
        for (k in dx.indices) for (sym in listOf(dx[k], rx[k])) { val w = codeword(sym); for (b in 9 downTo 0) bits.add((w shr b) and 1) }
        return bits.toIntArray()
    }

    /** Continuous-phase FSK of [bits] at [rate]: binary 1 on [f1], 0 on [f0]; [lead] seconds of silence before and after. */
    fun modulate(bits: IntArray, rate: Int, baud: Double, f1: Double, f0: Double, amp: Double = 0.5, lead: Double = 0.1): FloatArray {
        val spb = rate / baud
        val total = (bits.size * spb).toInt()
        val pad = (lead * rate).toInt()
        val out = FloatArray(total + 2 * pad)
        var ph = 0.0
        for (n in 0 until total) {
            val b = bits[(n / spb).toInt().coerceAtMost(bits.size - 1)]
            ph += 2 * PI * (if (b == 1) f1 else f0) / rate
            out[pad + n] = (amp * sin(ph)).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------------ the receiver

    /** The 10 phasing patterns (three symbols) and how many phasing symbols are still to come after each. */
    private val PHASING: List<Pair<Int, Int>> = run {
        val seq = ArrayList<Int>()
        for (k in 0 until 6) { seq.add(125); seq.add(111 - k) }
        seq.add(105)       // the RX after the last DX phasing; only the first 10 windows are used
        (0 until 10).map { k ->
            var w = 0
            for (j in 0 until 3) w = (w shl 10) or codeword(seq[k + j])
            w to (9 - k)
        }
    }
    private val EXPECTED = intArrayOf(110, 125, 109, 125, 108, 125, 107, 125, 106)

    /** Symbols to a call: phasing check, time diversity (DX and the RX copy 5 symbols later), end of sequence. */
    private class Symbols {
        private enum class S { PHASING, FILL_DX, FILL_RX, DX, RX, DX_EOS, RX_EOS, DONE }
        private var state = S.FILL_DX
        private var phaseIdx = 0
        private var idx = 0
        var errors = 0; private set
        private val buf = IntArray(3)
        private val softBuf = Array(3) { FloatArray(10) }
        private val bytes = ArrayList<Int>()
        private val hard = ArrayList<Int>()
        private val margins = ArrayList<Float>()
        val phasing get() = state == S.PHASING

        fun init(offset: Int) {
            state = if (offset == 0) S.FILL_DX else S.PHASING
            phaseIdx = offset; idx = 0; errors = 0; bytes.clear(); hard.clear(); margins.clear()
        }

        /** True when the call is complete (or too long). */
        fun feed(soft: FloatArray): Boolean {
            var w = 0
            for (i in 0 until 10) w = (w shl 1) or (if (soft[i] > 0) 1 else 0)
            val sym = symbolOf(w)
            when (state) {
                S.PHASING -> { if (sym != EXPECTED[9 - phaseIdx]) errors++; phaseIdx--; if (phaseIdx == 0) state = S.FILL_DX }
                S.FILL_DX -> {
                    soft.copyInto(softBuf[idx]); buf[idx++] = sym
                    if (idx == 3) { state = S.RX; idx = 0 } else state = S.FILL_RX
                }
                S.FILL_RX -> { if ((idx == 1 && sym != 105) || (idx == 2 && sym != 104)) errors++; state = S.FILL_DX }
                S.RX -> {
                    val a = select(buf[idx], sym, softBuf[idx], soft)
                    state = if (a == EOS || a == EOS_REQ || a == EOS_ACK) S.DX_EOS else S.DX
                    if (bytes.size > MAX_SYMBOLS) { state = S.DONE; eraseWeak(); return true }
                }
                S.DX, S.DX_EOS -> {
                    soft.copyInto(softBuf[idx]); buf[idx] = sym; idx = (idx + 1) % 3
                    state = if (state == S.DX) S.RX else S.RX_EOS
                }
                S.RX_EOS -> { select(buf[idx], sym, softBuf[idx], soft); state = S.DONE; eraseWeak(); return true }
                S.DONE -> {}
            }
            return false
        }

        /** The copy without detectable errors if both agree; otherwise the symbol that best matches both copies together. */
        private fun select(dx: Int, rx: Int, dxSoft: FloatArray, rxSoft: FloatArray): Int {
            var s: Int
            if (dx != -1) { s = dx; if (dx != rx) errors++ } else if (rx != -1) { s = rx; errors++ } else { s = -1; errors += 2 }
            hard.add(s)
            val comb = FloatArray(10) { dxSoft[it] + rxSoft[it] }
            val scale = comb.sumOf { abs(it).toDouble() }.toFloat()
            var best = 0; var bm = -1e30f; var nm = -1e30f
            for (c in 0 until 128) { val m = metric(c, comb); if (m > bm) { nm = bm; bm = m; best = c } else if (m > nm) nm = m }
            if (dx == -1 || dx != rx) s = best
            val margin = when { scale <= 0f -> 0f; s == best -> (bm - nm) / scale; else -> (metric(s, comb) - bm) / scale }
            bytes.add(s); margins.add(margin)
            return s
        }

        /** With more than one weak symbol a wrong combination could pass the ECC: mark them unreadable. */
        private fun eraseWeak() {
            if (margins.count { it < WEAK } > 1) for (i in bytes.indices) if (margins[i] < WEAK) bytes[i] = -1
        }

        /** The hard-decision copy when it gives a valid call or the soft one does not; otherwise the soft one. */
        fun message(): IntArray {
            val h = hard.toIntArray(); val b = bytes.toIntArray()
            return if (parse(h).valid || !parse(b).valid) h else b
        }
    }

    private const val MAX_SYMBOLS = 40
    private const val WEAK = 0.1f
    private const val MAX_PHASING_ERRORS = 2

    /**
     * FSK demodulator and call decoder for one channel. Audio at [rate]; binary 1 on [f1] (VHF 1300 Hz, MF / HF the lower tone),
     * 0 on [f0]. Each tone is correlated over one bit (the matched filter); each level against its own envelope (W7AY's automatic
     * threshold correction, which also takes away the FM pre-emphasis); the bit clock follows the transitions.
     */
    class Receiver(val rate: Int, val baud: Double, f1: Double, f0: Double) {
        var onCall: (Call) -> Unit = {}
        var clock: () -> Long = { System.currentTimeMillis() }
        var f1 = f1; private set
        var f0 = f0; private set
        /** Calls seen (valid and not). */
        var heard = 0; private set
        var invalid = 0; private set
        /** Tone contrast 0..1. */
        var quality = 0.0; private set
        val receiving get() = gotSop

        private val spb = rate / baud
        private val n = spb.roundToInt().coerceAtLeast(2)
        private val re1 = DoubleArray(n); private val im1 = DoubleArray(n); private val re0 = DoubleArray(n); private val im0 = DoubleArray(n)
        private var sr1 = 0.0; private var si1 = 0.0; private var sr0 = 0.0; private var si0 = 0.0
        private var pos = 0
        private var ph1 = 0.0; private var ph0 = 0.0
        private var w1 = 2 * PI * f1 / rate; private var w0 = 2 * PI * f0 / rate
        private var env1 = 0.0; private var env0 = 0.0
        private var count = 0L
        private var clockCount = -spb / 2
        private var data = false
        private var bits = 0
        private var bitCount = 0
        private var gotSop = false
        private var phasingErrors = 0
        private val soft = FloatArray(10)
        private val sym = Symbols()

        fun tones(a: Double, b: Double) { f1 = a; f0 = b; w1 = 2 * PI * a / rate; w0 = 2 * PI * b / rate }

        fun process(x: FloatArray, len: Int = x.size) { for (i in 0 until len) sample(x[i].toDouble()) }

        private fun sample(v: Double) {
            val c1 = cos(ph1); val s1 = sin(ph1); val c0 = cos(ph0); val s0 = sin(ph0)
            ph1 += w1; ph0 += w0
            if (ph1 > 2 * PI) ph1 -= 2 * PI
            if (ph0 > 2 * PI) ph0 -= 2 * PI
            val a1 = v * c1; val b1 = -v * s1; val a0 = v * c0; val b0 = -v * s0
            sr1 += a1 - re1[pos]; si1 += b1 - im1[pos]; sr0 += a0 - re0[pos]; si0 += b0 - im0[pos]
            re1[pos] = a1; im1[pos] = b1; re0[pos] = a0; im0[pos] = b0
            pos = (pos + 1) % n
            if (pos == 0) { sr1 = re1.sum(); si1 = im1.sum(); sr0 = re0.sum(); si0 = im0.sum() }   // no drift of the running sums
            if (++count < n) return
            val l1 = hypot(sr1, si1) / n; val l0 = hypot(sr0, si0) / n
            env1 = if (l1 > env1) env1 + (l1 - env1) / (spb / 4) else env1 + (l1 - env1) / (spb * 16)
            env0 = if (l0 > env0) env0 + (l0 - env0) / (spb / 4) else env0 + (l0 - env0) / (spb * 16)
            val d = (l1 - 0.5 * env1) - (l0 - 0.5 * env0)
            val sv = d / (env1 + env0 + 1e-9)
            quality = 0.999 * quality + 0.001 * (abs(l1 - l0) / (l1 + l0 + 1e-12))
            val prev = data
            data = d > 0
            if (data != prev) clockCount -= clockCount * (if (gotSop) 0.05 else 0.2)
            clockCount += 1.0
            if (clockCount >= spb / 2 - 1) { bit(sv.toFloat()); clockCount -= spb }
        }

        private fun bit(s: Float) {
            val b = if (s > 0) 1 else 0
            bits = (bits shl 1) or b
            if (gotSop && bitCount < 10) soft[bitCount] = s
            bitCount++
            if (gotSop && (!sym.phasing || phasingErrors > 0)) {
                val pat = bits and 0x3FFFFFFF
                for ((p, off) in PHASING) if (pat == p) { sym.init(off); phasingErrors = 0; bitCount = 0; return }
            }
            if (!gotSop) {
                if (bitCount == 30) {
                    bitCount--
                    val pat = bits and 0x3FFFFFFF
                    var best = -1; var bestErr = MAX_PHASING_ERRORS + 1
                    for ((k, p) in PHASING.withIndex()) { val e = Integer.bitCount(pat xor p.first); if (e < bestErr) { bestErr = e; best = k } }
                    if (best >= 0) { sym.init(PHASING[best].second); phasingErrors = bestErr; gotSop = true; bitCount = 0 }
                }
            } else if (bitCount == 10) {
                bitCount = 0
                if (sym.feed(soft)) {
                    val c = parse(sym.message(), sym.errors, clock())
                    heard++
                    if (!c.valid) invalid++
                    gotSop = false; bits = 0; bitCount = 0; phasingErrors = 0
                    onCall(c)
                }
            }
        }
    }

    /** VHF (channel 70) or MF / HF DSC as one of the Modes: audio at [Modems.RATE]; [centerHz] is the middle of the two MF / HF tones. */
    class Decoder(center: Double, val vhf: Boolean) : Modems.Modem(center) {
        override val label = if (vhf) "DSC VHF" else "DSC HF"
        var onCall: (Call) -> Unit = {}
        val rx = if (vhf) Receiver(Modems.RATE, VHF_BAUD, VHF_F1, VHF_F0) else Receiver(Modems.RATE, HF_BAUD, center - HF_SHIFT / 2, center + HF_SHIFT / 2)
        init {
            rx.onCall = { c ->
                // noise can look like a phasing sequence: show an invalid call only if its two format specifiers agree on a known one
                val known = c.symbols.size > 1 && c.symbols[0] == c.symbols[1] && c.format in listOf(FMT_AREA, FMT_DISTRESS, FMT_GROUP, FMT_ALL_SHIPS, FMT_INDIVIDUAL, FMT_AUTOMATIC)
                if (c.valid || known) onText(timeOf(c.timeMs) + " " + c.summary() + "\n")
                if (c.valid) onCall(c)
            }
        }
        override fun process(x: FloatArray, n: Int) {
            if (!vhf && abs(rx.f1 - (centerHz - HF_SHIFT / 2)) > 0.5) rx.tones(centerHz - HF_SHIFT / 2, centerHz + HF_SHIFT / 2)
            rx.process(x, n)
            quality = rx.quality
        }
        private fun timeOf(ms: Long) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
    }

    // ------------------------------------------------------------------ NMEA 0183 (IEC 61162-1 DSC)

    private fun two(v: Int?) = v?.let { String.format(java.util.Locale.US, "%02d", (it - 100).coerceIn(0, 99)) } ?: ""
    private fun ten(m: String?) = m?.let { it.substringBefore('-').padStart(9, '0') + (it.substringAfter('-', "0")) } ?: ""

    /**
     * The DSC sentence a DSC radio sends when it receives [c] ($CDDSC): format, calling station, category, nature of distress or first
     * telecommand, subsequent communication or second telecommand, position (or frequency), time, ship in distress and nature (relays and
     * acknowledgements), acknowledgement (R requested, B acknowledgement, S end).
     */
    fun nmea(c: Call): String {
        val relay = c.format != FMT_DISTRESS && c.category == CAT_DISTRESS
        val f4 = if (c.format == FMT_DISTRESS) two(c.nature) else two(c.tc1)
        val f5 = if (c.format == FMT_DISTRESS || c.tc2 == null) two(c.subsequent) else two(c.tc2)
        val f6 = c.positionDigits ?: c.freqDigits ?: ""
        val ack = when (c.eos) { EOS_REQ -> "R"; EOS_ACK -> "B"; else -> "S" }
        val body = listOf("CDDSC", two(c.format), ten(c.selfId), two(c.category), f4, f5, f6, c.time ?: "",
            if (relay) ten(c.distressId) else "", if (relay) two(c.nature) else "", ack, "").joinToString(",")
        return "$" + body + "*" + String.format("%02X", Nmea.checksum(body))
    }
}
