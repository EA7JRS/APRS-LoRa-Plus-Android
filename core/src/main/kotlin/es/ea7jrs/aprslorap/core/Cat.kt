/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * CAT of a radio plugged into the phone (USB serial): the same engines as the rotor box (`rc_cat.h` of APRS LoRa+ Rotor),
 * with the same test cases. Yaesu text (FT-991 / 991A, FT-891, FT-710, FTDX10, FTDX101), Yaesu 5 bytes (FT-817 / 817ND /
 * 818, FT-857, FT-897), Icom CI-V and Kenwood / Elecraft. No Android here: an engine says which bytes to send ([Engine.tick])
 * and is fed with what arrives ([Engine.feed]). Bytes travel as strings of chars 0-255 (ISO-8859-1), as in the C++ code.
 */
object Cat {
    enum class Proto(val label: String) { YAESU("Yaesu"), YAESU5("Yaesu 5 bytes"), CIV("Icom CI-V"), KENWOOD("Kenwood / Elecraft") }

    /** A radio model: its protocol and the serial settings it leaves the factory with (Hamlib uses the same). */
    data class Model(val name: String, val proto: Proto, val baud: Int, val stopBits: Int, val civAddr: Int = 0, val note: String = "")

    val MODELS = listOf(
        Model("FT-817 / FT-817ND", Proto.YAESU5, 4800, 2, note = "CAT cable on the ACC socket (CT-62 type). Menu 14 CAT RATE."),
        Model("FT-818", Proto.YAESU5, 4800, 2, note = "CAT cable on the ACC socket (CT-62 type). Menu 14 CAT RATE."),
        Model("FT-857 / FT-897", Proto.YAESU5, 4800, 2, note = "CAT cable on the CAT / LINEAR socket. Menu CAT RATE."),
        Model("FT-891", Proto.YAESU, 4800, 2, note = "USB cable: the first of its two ports (Enhanced) is the CAT. Menu 05-06 CAT RATE."),
        Model("FT-991 / FT-991A", Proto.YAESU, 4800, 2, note = "USB cable: the first of its two ports (Enhanced) is the CAT. Menu 031 CAT RATE."),
        Model("FT-710 / FTDX10 / FTDX101", Proto.YAESU, 38400, 2, note = "USB cable: the first of its two ports (Enhanced) is the CAT."),
        Model("Icom (CI-V)", Proto.CIV, 19200, 1, civAddr = 0x94, note = "Write the radio's CI-V address (IC-7300 94, IC-705 A4, IC-9700 A2)."),
        Model("Kenwood / Elecraft", Proto.KENWOOD, 9600, 1),
    )
    val BAUDS = listOf(4800, 9600, 19200, 38400, 57600, 115200)

    /** What the radio says. [smeter] 0-15 (S0-S9 = 0-9, S9+10..+60 = 10-15), -1 unknown. [tone] CTCSS in tenths of Hz. */
    class State {
        var link = false
        var freq = 0L
        var txFreq = 0L
        var mode = ""
        var vfo = 'A'
        var ptt = false
        var split = false
        var smeter = -1
        var tone = 0
    }

    // CTCSS tones in tenths of Hz: Yaesu's list of 50 (CN order) and Kenwood's of 42 (TN order)
    val CTCSS50 = intArrayOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273,
        1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995,
        2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541)
    val CTCSS42 = intArrayOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273,
        1318, 1365, 1413, 1462, 1514, 1567, 1622, 1679, 1738, 1799, 1862, 1928, 2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 17500)

    /** Position of the tone in the table (±0.2 Hz) or -1. */
    fun toneIndex(t: IntArray, n: Int, dHz: Int): Int { for (i in 0 until n) if (dHz >= t[i] - 2 && dHz <= t[i] + 2) return i; return -1 }

    fun smeterFrom255(v: Int): Int { if (v < 0) return -1; if (v <= 120) return (v * 9 + 60) / 120; val s = 9 + ((v - 120) * 6 + 60) / 121; return if (s > 15) 15 else s }
    fun smeterFrom30(v: Int): Int { if (v < 0) return -1; if (v <= 15) return (v * 9 + 7) / 15; val s = 9 + ((v - 15) * 6 + 7) / 15; return if (s > 15) 15 else s }

    /** Normalises a mode name: "DIG" / "DATA" -> PKTUSB, "FM-N" -> FM... (Hamlib names). */
    fun normMode(input: String): String {
        val m = input.filter { it != '-' && it != '_' && it != ' ' }.uppercase(Locale.US)
        return when (m) {
            "DIG", "DATA", "DATAU", "USBD", "PKT", "DIGU" -> "PKTUSB"
            "DATAL", "LSBD", "DIGL" -> "PKTLSB"
            "DATAFM", "FMD" -> "PKTFM"
            "FMN", "NFM" -> "FM"
            "AMN" -> "AM"
            "CWU" -> "CW"
            "CWL", "CWREV" -> "CWR"
            "FSK" -> "RTTY"
            "FSKR", "RTTYREV" -> "RTTYR"
            else -> m
        }
    }

    /** Doppler: [rrKmS] radial speed in km/s (+ = going away). The downlink that is received and the uplink to transmit. */
    fun dopplerRx(f: Long, rrKmS: Double): Long = Math.round(f.toDouble() * (1.0 - rrKmS / 299792.458))
    fun dopplerTx(f: Long, rrKmS: Double): Long = Math.round(f.toDouble() * (1.0 + rrKmS / 299792.458))

    fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
    fun text(b: ByteArray, n: Int = b.size): String = String(b, 0, n, Charsets.ISO_8859_1)
    private fun ch(v: Int): Char = (v and 0xFF).toChar()

    // ------------------------------------------------------------------ base

    abstract class Engine {
        var allowPtt = false
        var pollGapMs = 120L           // pause between queries
        var replyMs = 400L             // wait for the answer
        var cmdGapMs = 30L             // shortest pause between commands
        val state = State()

        protected class Item(val data: String, val reply: Boolean = false, val urgent: Boolean = false, val len: Int = 0, val kind: Int = 0)
        private val q = ArrayDeque<Item>()
        protected val rx = StringBuilder()
        protected var waiting = false
        private var sent = false
        private var sentAt = 0L
        private var misses = 0
        private var pollIdx = 0
        protected var expectLen = 0
        protected var expectKind = 0

        /** Bytes to send now (queued commands or the next query), "" for nothing. Call it on every turn of the loop. */
        fun tick(now: Long): String {
            if (waiting) {
                if (now - sentAt < replyMs) return ""
                waiting = false                                  // no answer
                expectLen = 0
                if (++misses >= 4 && state.link) { state.link = false; state.smeter = -1 }
            }
            if (sent && q.isNotEmpty() && now - sentAt < (if (q.first().urgent) cmdGapMs else pollGapMs)) return ""
            if (q.isEmpty()) {
                if (sent && now - sentAt < pollGapMs) return ""
                var it = poll(pollIdx++)
                if (it == null) { pollIdx = 0; it = poll(pollIdx++) ?: return "" }
                q.addLast(it)
            }
            val it = q.removeFirst()
            sentAt = now
            sent = true
            waiting = it.reply
            expectLen = it.len
            expectKind = it.kind
            if (it.reply) rx.setLength(0)
            return it.data
        }

        /** Bytes that arrive from the radio. */
        fun feed(d: ByteArray, n: Int = d.size) {
            for (i in 0 until n) {
                rx.append(ch(d[i].toInt()))
                if (rx.length > 256) rx.delete(0, rx.length - 64)
                parse()
            }
        }

        fun setPtt(on: Boolean): Boolean {
            if (on && !allowPtt) return false
            return cmdPtt(on)
        }

        fun reset() { q.clear(); rx.setLength(0); waiting = false; sent = false; expectLen = 0; pollIdx = 0; misses = 0 }
        fun disconnected() { reset(); state.link = false; state.smeter = -1 }
        fun setVfo(v: Char): Boolean = if (v == state.vfo) true else toggleVfo()

        protected fun queue(d: String, reply: Boolean = false, len: Int = 0, kind: Int = 0) {
            if (q.size > 16) q.removeFirst()
            q.addLast(Item(d, reply, true, len, kind))
        }
        protected fun query(d: String, len: Int = 0, kind: Int = 0) = Item(d, true, false, len, kind)
        protected fun gotReply() { waiting = false; misses = 0; state.link = true }

        abstract fun setFreq(hz: Long): Boolean
        abstract fun setMode(mode: String): Boolean
        abstract fun toggleVfo(): Boolean
        /** The other VFO (split, satellite uplink); false if the radio has no command for it. */
        abstract fun setTxFreq(hz: Long): Boolean
        abstract fun setSplit(on: Boolean): Boolean
        /** CTCSS on transmit, tenths of Hz, 0 = off; false if the radio cannot. */
        open fun setTone(dHz: Int): Boolean = false
        protected abstract fun poll(idx: Int): Item?           // null: no more queries in the round
        protected abstract fun parse()
        protected abstract fun cmdPtt(on: Boolean): Boolean
    }

    // ------------------------------------------------------------------ Icom CI-V

    class CivEngine(var addr: Int = 0xA2, var ctl: Int = 0xE0) : Engine() {
        private var dataMode = false
        private var noSub = false
        private var modeCode = 0xFF

        override fun setFreq(hz: Long): Boolean { queue(frame(0x05, -1, bcd(hz, 5)), true); state.freq = hz; return true }
        override fun setTxFreq(hz: Long): Boolean { queue(frame(0x25, 0x01, bcd(hz, 5)), true); state.txFreq = hz; return true }
        override fun setSplit(on: Boolean): Boolean { queue(frame(0x0F, if (on) 0x01 else 0x00, ""), true); state.split = on; return true }
        override fun toggleVfo(): Boolean { queue(frame(0x07, 0xB0, ""), true); state.vfo = if (state.vfo == 'A') 'B' else 'A'; return true }
        // 1B 00: repeater tone, 3 BCD bytes (00 08 85 = 88.5 Hz); 16 42: repeater tone on / off
        override fun setTone(dHz: Int): Boolean {
            if (dHz < 0 || dHz > 2600) return false
            if (dHz > 0) queue(frame(0x1B, 0x00, "" + ch(0) + ch(((dHz / 1000) % 10) shl 4 or ((dHz / 100) % 10)) + ch(((dHz / 10) % 10) shl 4 or (dHz % 10))), true)
            queue(frame(0x16, 0x42, "" + ch(if (dHz > 0) 1 else 0)), true)
            state.tone = dHz
            return true
        }
        override fun setMode(mode: String): Boolean {
            val m = normMode(mode)
            var data = -1
            val code = when (m) {
                "LSB" -> 0x00; "USB" -> 0x01; "AM" -> 0x02; "CW" -> 0x03; "RTTY" -> 0x04; "FM" -> 0x05; "WFM" -> 0x06; "CWR" -> 0x07; "RTTYR" -> 0x08; "DV" -> 0x17
                "PKTUSB" -> { data = 1; 0x01 }
                "PKTLSB" -> { data = 1; 0x00 }
                "PKTFM" -> { data = 1; 0x05 }
                else -> return false
            }
            queue(frame(0x06, -1, "" + ch(code)), true)
            if (code <= 0x05) queue(frame(0x1A, 0x06, if (data == 1) "\u0001\u0001" else "\u0000\u0000"), true)
            state.mode = m
            return true
        }

        fun frame(cmd: Int, sub: Int, data: String): String = "þþ" + ch(addr) + ch(ctl) + ch(cmd) + (if (sub >= 0) "" + ch(sub) else "") + data + 'ý'

        override fun cmdPtt(on: Boolean): Boolean { queue(frame(0x1C, 0x00, "" + ch(if (on) 1 else 0)), true); state.ptt = on; return true }

        override fun poll(idx: Int): Item? = when (idx) {
            0 -> query(frame(0x03, -1, ""))
            1 -> query(frame(0x04, -1, ""))
            2 -> query(frame(0x1C, 0x00, ""))
            3 -> query(frame(0x15, 0x02, ""))
            4 -> query(frame(0x1A, 0x06, ""))
            5 -> if (noSub) null else query(frame(0x25, 0x01, ""), kind = K_SUB)
            else -> null
        }

        override fun parse() {
            while (true) {                                       // whole frames FE FE ... FD
                val s = rx.indexOf("þþ")
                if (s < 0) { if (rx.length > 1) rx.delete(0, rx.length - 1); return }
                if (s > 0) rx.delete(0, s)
                val e = rx.indexOf("ý", 2)
                if (e < 0) return
                val f = rx.substring(0, e + 1)
                rx.delete(0, e + 1)
                handle(f)
            }
        }

        private fun handle(frame: String) {
            var f = frame
            while (f.length >= 3 && f[2].code == 0xFE) f = f.substring(1)       // extra FE
            if (f.length < 6) return
            val to = f[2].code; val from = f[3].code; val cmd = f[4].code
            if (from == ctl) return                               // echo of what we sent (one-wire bus)
            if (from != addr || (to != ctl && to != 0x00)) return
            val d = IntArray(f.length - 6) { f[5 + it].code }     // data after the command, without FD
            when (cmd) {
                0xFB -> gotReply()
                0xFA -> { if (expectKind == K_SUB) noSub = true; gotReply() }
                0x00, 0x03 -> { if (d.size >= 4) state.freq = unbcd(d, 0, minOf(d.size, 5)); if (cmd == 0x03) gotReply() }
                0x01, 0x04 -> { if (d.isNotEmpty()) { modeCode = d[0]; refreshMode() }; if (cmd == 0x04) gotReply() }
                0x1C -> if (d.size >= 2 && d[0] == 0x00) { state.ptt = d[1] != 0; gotReply() }
                0x15 -> if (d.size >= 3 && d[0] == 0x02) { state.smeter = smeterFrom255((d[1] shr 4) * 1000 + (d[1] and 15) * 100 + (d[2] shr 4) * 10 + (d[2] and 15)); gotReply() }
                0x1A -> if (d.size >= 2 && d[0] == 0x06) { dataMode = d[1] != 0; refreshMode(); gotReply() }
                0x25 -> if (d.size >= 5 && d[0] == 0x01) { state.txFreq = unbcd(d, 1, minOf(d.size - 1, 5)); gotReply() }
            }
        }

        private fun refreshMode() {
            state.mode = when (modeCode) {
                0x00 -> if (dataMode) "PKTLSB" else "LSB"
                0x01 -> if (dataMode) "PKTUSB" else "USB"
                0x02 -> "AM"; 0x03 -> "CW"; 0x04 -> "RTTY"
                0x05 -> if (dataMode) "PKTFM" else "FM"
                0x06 -> "WFM"; 0x07 -> "CWR"; 0x08 -> "RTTYR"; 0x17 -> "DV"
                else -> return
            }
        }

        companion object {
            private const val K_SUB = 5
            /** BCD, least significant byte first. */
            fun bcd(value: Long, bytes: Int): String {
                var v = value
                val s = StringBuilder()
                repeat(bytes) { val lo = (v % 10).toInt(); v /= 10; val hi = (v % 10).toInt(); v /= 10; s.append(ch((hi shl 4) or lo)) }
                return s.toString()
            }
            fun unbcd(p: IntArray, from: Int, n: Int): Long {
                var v = 0L; var m = 1L
                for (i in from until from + n) { v += (p[i] and 0x0F) * m; m *= 10; v += (p[i] shr 4) * m; m *= 10 }
                return v
            }
        }
    }

    // ------------------------------------------------------------------ text (Yaesu / Kenwood)

    abstract class AsciiEngine : Engine() {
        override fun parse() {
            while (true) {
                val e = rx.indexOf(";")
                if (e < 0) return
                val r = rx.substring(0, e).trimStart('\r', '\n', ' ')
                rx.delete(0, e + 1)
                if (r == "?" || r == "E" || r == "O") { gotReply(); continue }     // command not accepted
                if (r.length >= 2) answer(r)
            }
        }
        protected abstract fun answer(r: String)
        protected fun digits(s: String, from: Int, n: Int = s.length - from): Long? =
            if (n <= 0 || s.length < from + n) null else s.substring(from, from + n).takeIf { t -> t.all { it in '0'..'9' } }?.toLongOrNull()
    }

    /** Yaesu text: FT-991 / 991A, FT-891, FT-710, FTDX10, FTDX101. */
    class YaesuEngine : AsciiEngine() {
        var freqDigits = 9

        override fun setFreq(hz: Long): Boolean { queue(fmtF("FA", hz)); state.freq = hz; return true }
        override fun setTxFreq(hz: Long): Boolean { queue(fmtF("FB", hz)); state.txFreq = hz; return true }
        override fun setSplit(on: Boolean): Boolean { queue(if (on) "FT3;" else "FT2;"); state.split = on; return true }      // FT3 = TX on VFO B
        override fun toggleVfo(): Boolean { queue("SV;"); state.vfo = if (state.vfo == 'A') 'B' else 'A'; return true }
        // CN00nnn; = tone nnn of the table of 50; CT02; = CTCSS on transmit only; CT00; = off
        override fun setTone(dHz: Int): Boolean {
            if (dHz == 0) { queue("CT00;"); state.tone = 0; return true }
            val i = toneIndex(CTCSS50, 50, dHz)
            if (i < 0) return false
            queue(String.format(Locale.US, "CN00%03d;CT02;", i)); state.tone = CTCSS50[i]; return true
        }
        override fun setMode(mode: String): Boolean {
            val m = normMode(mode)
            val c = when (m) {
                "LSB" -> "1"; "USB" -> "2"; "CW" -> "3"; "FM" -> "4"; "AM" -> "5"; "RTTY" -> "6"; "CWR" -> "7"; "PKTLSB" -> "8"
                "RTTYR" -> "9"; "PKTFM" -> "A"; "PKTUSB" -> "C"; else -> return false
            }
            queue("MD0$c;")
            state.mode = m
            return true
        }

        private fun fmtF(p: String, hz: Long) = p + hz.toString().padStart(freqDigits, '0') + ";"
        override fun cmdPtt(on: Boolean): Boolean { queue(if (on) "TX1;" else "TX0;"); state.ptt = on; return true }
        override fun poll(idx: Int): Item? = listOf("FA;", "MD0;", "TX;", "SM0;", "FB;").getOrNull(idx)?.let { query(it) }
        override fun answer(r: String) {
            when {
                r.startsWith("FA") -> digits(r, 2)?.let { state.freq = it; freqDigits = r.length - 2; gotReply() }
                r.startsWith("FB") -> digits(r, 2)?.let { state.txFreq = it; gotReply() }
                r.startsWith("MD0") && r.length >= 4 -> { state.mode = modeName(r[3]); gotReply() }
                r.startsWith("TX") && r.length >= 3 -> { state.ptt = r[2] != '0'; gotReply() }
                r.startsWith("SM0") -> digits(r, 3)?.let { state.smeter = smeterFrom255(it.toInt()); gotReply() }
                r.startsWith("FT") && r.length >= 3 -> { state.split = r[2] == '1' || r[2] == '3'; gotReply() }
            }
        }
        private fun modeName(c: Char) = when (c) {
            '1' -> "LSB"; '2' -> "USB"; '3' -> "CW"; '4' -> "FM"; '5' -> "AM"; '6' -> "RTTY"; '7' -> "CWR"; '8' -> "PKTLSB"; '9' -> "RTTYR"
            'A' -> "PKTFM"; 'B' -> "FM"; 'C' -> "PKTUSB"; 'D' -> "AM"; 'E' -> "C4FM"; else -> ""
        }
    }

    /** Kenwood / Elecraft. [toneBase]: TN 01 = 67.0 Hz (TS-2000); 0 where the count starts at 00 (TS-480, TS-590, TS-890). */
    class KenwoodEngine(var toneBase: Int = 1) : AsciiEngine() {
        private var data = false
        private var modeCode = -1

        override fun setFreq(hz: Long): Boolean { queue(fmtF(if (state.vfo == 'B') "FB" else "FA", hz)); state.freq = hz; return true }
        override fun setTxFreq(hz: Long): Boolean { queue(fmtF(if (state.vfo == 'B') "FA" else "FB", hz)); state.txFreq = hz; return true }
        override fun setSplit(on: Boolean): Boolean {
            val rxv = if (state.vfo == 'B') 1 else 0; val txv = if (on) 1 - rxv else rxv
            queue("FR$rxv;FT$txv;"); state.split = on; return true
        }
        override fun setTone(dHz: Int): Boolean {
            if (dHz == 0) { queue("TO0;"); state.tone = 0; return true }
            val i = toneIndex(CTCSS42, 41, dHz)
            if (i < 0) return false
            queue(String.format(Locale.US, "TN%02d;TO1;", i + toneBase)); state.tone = CTCSS42[i]; return true
        }
        override fun toggleVfo(): Boolean {
            val v = if (state.vfo == 'B') 0 else 1
            queue("FR$v;FT$v;"); state.vfo = if (v == 1) 'B' else 'A'; return true
        }
        override fun setMode(mode: String): Boolean {
            val m = normMode(mode)
            var d = false
            val c = when (m) {
                "LSB" -> 1; "USB" -> 2; "CW" -> 3; "FM" -> 4; "AM" -> 5; "RTTY" -> 6; "CWR" -> 7; "RTTYR" -> 9
                "PKTUSB" -> { d = true; 2 }
                "PKTLSB" -> { d = true; 1 }
                "PKTFM" -> { d = true; 4 }
                else -> return false
            }
            queue("MD$c;")
            queue(if (d) "DA1;" else "DA0;")           // data mode (TS-590 / TS-890 / TS-990; the others answer '?')
            state.mode = m
            return true
        }

        private fun fmtF(p: String, hz: Long) = p + hz.toString().padStart(11, '0') + ";"
        override fun cmdPtt(on: Boolean): Boolean { queue(if (on) "TX;" else "RX;"); state.ptt = on; return true }
        override fun poll(idx: Int): Item? = when (idx) {
            0 -> query("IF;"); 1 -> query("SM0;"); 2 -> query(if (state.vfo == 'B') "FA;" else "FB;"); 3 -> query("DA;"); else -> null
        }
        override fun answer(r: String) {
            if (r.startsWith("IF") && r.length >= 2 + 31) {
                // IF: frequency (11), 5 blanks, RIT (5), RIT, XIT, bank, memory (2), TX/RX [26], mode [27], VFO [28], scan, split [30]
                val p = r.substring(2)
                digits(p, 0, 11)?.let { state.freq = it }
                state.ptt = p[26] == '1'
                if (p[27] in '0'..'9') { modeCode = p[27] - '0'; refreshMode() }
                state.vfo = if (p[28] == '1') 'B' else 'A'
                state.split = p[30] == '1'
                gotReply()
            } else if (r.startsWith("FA") || r.startsWith("FB")) {
                val v = digits(r, 2) ?: return
                val isA = r[1] == 'A'
                if ((isA && state.vfo == 'B') || (!isA && state.vfo != 'B')) state.txFreq = v else state.freq = v
                gotReply()
            } else if (r.startsWith("SM0")) { digits(r, 3)?.let { state.smeter = smeterFrom30(it.toInt()); gotReply() } }
            else if (r.startsWith("SM")) { digits(r, 2)?.let { state.smeter = smeterFrom30(it.toInt()); gotReply() } }
            else if (r.startsWith("DA") && r.length >= 3) { data = r[2] == '1'; refreshMode(); gotReply() }
            else if (r.startsWith("MD") && r.length >= 3) { modeCode = r[2] - '0'; refreshMode(); gotReply() }
        }
        private fun refreshMode() {
            when (modeCode) {
                1 -> state.mode = if (data) "PKTLSB" else "LSB"
                2 -> state.mode = if (data) "PKTUSB" else "USB"
                3 -> state.mode = "CW"
                4 -> state.mode = if (data) "PKTFM" else "FM"
                5 -> state.mode = "AM"; 6 -> state.mode = "RTTY"; 7 -> state.mode = "CWR"; 9 -> state.mode = "RTTYR"
            }
        }
    }

    // ------------------------------------------------------------------ Yaesu 5 bytes

    /** FT-817 / 817ND / 818, FT-857, FT-897: five bytes, the last one is the command. */
    class Yaesu5Engine : Engine() {
        init { pollGapMs = 150; cmdGapMs = 60 }

        override fun setFreq(hz: Long): Boolean {
            val v = (hz + 5) / 10                                 // units of 10 Hz, 8 BCD digits, most significant first
            val d = StringBuilder()
            var div = 1_000_000L
            repeat(4) { val p = (v / div % 100).toInt(); d.append(ch(((p / 10) shl 4) or (p % 10))); div /= 100 }
            d.append(ch(0x01))
            queue(d.toString()); state.freq = v * 10; return true
        }
        override fun setTxFreq(hz: Long): Boolean = false          // no command for the other VFO
        override fun setSplit(on: Boolean): Boolean { queue(cmd(if (on) 0x02 else 0x82)); state.split = on; return true }
        override fun toggleVfo(): Boolean { queue(cmd(0x81)); state.vfo = if (state.vfo == 'A') 'B' else 'A'; return true }
        // 0B: tone (4 BCD digits in tenths of Hz, transmit and receive); 0A with 4A = CTCSS on transmit, 8A = off
        override fun setTone(dHz: Int): Boolean {
            if (dHz < 0 || dHz > 2600) return false
            if (dHz > 0) {
                val hi = ch(((dHz / 1000) % 10) shl 4 or ((dHz / 100) % 10)); val lo = ch(((dHz / 10) % 10) shl 4 or (dHz % 10))
                queue("" + hi + lo + hi + lo + ch(0x0B))
            }
            queue("" + ch(if (dHz > 0) 0x4A else 0x8A) + "\u0000\u0000\u0000" + ch(0x0A))
            state.tone = dHz
            return true
        }
        override fun setMode(mode: String): Boolean {
            val m = normMode(mode)
            val c = when (m) {
                "LSB" -> 0x00; "USB" -> 0x01; "CW" -> 0x02; "CWR" -> 0x03; "AM" -> 0x04; "WFM" -> 0x06; "FM" -> 0x08
                "PKTUSB", "RTTY" -> 0x0A; "PKTFM" -> 0x0C; else -> return false
            }
            queue("" + ch(c) + "\u0000\u0000\u0000" + ch(0x07)); state.mode = m; return true
        }

        private fun cmd(c: Int) = "\u0000\u0000\u0000\u0000" + ch(c)
        override fun cmdPtt(on: Boolean): Boolean { queue(cmd(if (on) 0x08 else 0x88)); state.ptt = on; return true }
        override fun poll(idx: Int): Item? = when (idx) {
            0 -> query(cmd(0x03), 5, K_FREQ); 1 -> query(cmd(0xE7), 1, K_RX); 2 -> query(cmd(0xF7), 1, K_TX); else -> null
        }
        override fun parse() {
            if (!waiting || expectLen <= 0) { rx.setLength(0); return }          // commands do not answer
            if (rx.length < expectLen) return
            val p = IntArray(expectLen) { rx[it].code }
            when (expectKind) {
                K_FREQ -> {
                    var v = 0L
                    for (i in 0 until 4) v = v * 100 + ((p[i] shr 4) * 10 + (p[i] and 15))
                    state.freq = v * 10
                    when (p[4]) {
                        0x00 -> state.mode = "LSB"; 0x01 -> state.mode = "USB"
                        0x02, 0x82 -> state.mode = "CW"; 0x03, 0x83 -> state.mode = "CWR"
                        0x04, 0x84 -> state.mode = "AM"; 0x06 -> state.mode = "WFM"
                        0x08, 0x88 -> state.mode = "FM"; 0x0A, 0x8A -> state.mode = "PKTUSB"
                        0x0C, 0x8C -> state.mode = "PKTFM"
                    }
                }
                K_RX -> if (!state.ptt) state.smeter = p[0] and 0x0F
                K_TX -> state.ptt = (p[0] and 0x80) == 0
            }
            rx.delete(0, expectLen)
            expectLen = 0
            gotReply()
        }
        private companion object { const val K_FREQ = 1; const val K_RX = 2; const val K_TX = 3 }
    }

    fun make(proto: Proto, civAddr: Int = 0xA2): Engine = when (proto) {
        Proto.YAESU -> YaesuEngine()
        Proto.YAESU5 -> Yaesu5Engine()
        Proto.CIV -> CivEngine(civAddr)
        Proto.KENWOOD -> KenwoodEngine()
    }

    // ------------------------------------------------------------------ satellite Doppler

    /**
     * A satellite (or anything with Doppler): nominal downlink / uplink and the radial speed come in with [update]; [step]
     * retunes the radio when the change reaches [minStep] Hz, at most every [minMs]. It stops by itself after [timeoutMs]
     * without data. Without a command for the other VFO (FT-817) only the downlink follows.
     */
    class Tracker(var minStep: Long = 50, var minMs: Long = 500, var timeoutMs: Long = 15_000) {
        var active = false; private set
        var name = ""; private set
        var doppler = 0L; private set               // Hz applied to the downlink
        private var dl = 0L; private var ul = 0L; private var rr = 0.0; private var upd = 0L
        private var sentDl = 0L; private var sentUl = 0L; private var last = 0L

        fun update(n: String, downHz: Long, upHz: Long, rrKmS: Double, now: Long) {
            if (n != name || downHz != dl || upHz != ul) { sentDl = 0; sentUl = 0 }
            name = n; dl = downHz; ul = upHz; rr = rrKmS; upd = now; active = true
        }
        fun stop() { active = false; doppler = 0; sentDl = 0; sentUl = 0 }

        fun step(rig: Engine, now: Long) {
            if (active && now - upd > timeoutMs) { active = false; doppler = 0 }
            if (!active || !rig.state.link || now - last < minMs) return
            if (rig.state.ptt && ul == 0L) return
            if (dl > 0) {
                val f = dopplerRx(dl, rr)
                doppler = f - dl
                if (sentDl == 0L || Math.abs(f - sentDl) >= minStep) { rig.setFreq(f); sentDl = f; last = now }
            }
            if (ul > 0) {
                val f = dopplerTx(ul, rr)
                if (sentUl == 0L || Math.abs(f - sentUl) >= minStep) { if (rig.setTxFreq(f)) sentUl = f; last = now }
            }
        }
    }
}
