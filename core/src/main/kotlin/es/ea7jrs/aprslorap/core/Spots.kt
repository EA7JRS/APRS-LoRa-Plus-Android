/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Spots of what the app decodes, for the public networks and for other programs:
 *  - PSK Reporter (FT8, FT4, CW): IPFIX over UDP, as WSJT-X does (Network/PSKReporterIPFIX.cpp; https://pskreporter.info/pskdev.html).
 *  - WSPRnet (WSPR, FST4W): HTTP form, as WSJT-X does (Network/wsprnet.cpp).
 *  - The WSJT-X UDP protocol (Network/NetworkMessage.hpp): GridTracker, JTAlert, N1MM, RBN Aggregator (FT8 / FT4 to the RBN).
 *  - CW Skimmer style telnet lines ("DX de"): RBN Aggregator (CW to the RBN), loggers, DX cluster clients.
 * Pure Kotlin; the sockets are in the app (Spotter).
 */
object Spots {
    /** One station heard. [rfHz]: real frequency; [snr] dB; [timeMs] UTC. */
    data class Spot(val call: String, val locator: String, val rfHz: Long, val mode: String, val snr: Int, val timeMs: Long)

    private val GRID = Regex("^(?![Rr]{2}73)[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?$")
    fun isGrid(s: String) = GRID.matches(s)

    /** Maidenhead locator of 4, 6 or 8 characters. */
    fun locator(lat: Double, lon: Double, chars: Int = 6): String {
        var x = (lon + 180.0).coerceIn(0.0, 359.999999); var y = (lat + 90.0).coerceIn(0.0, 179.999999)
        val sb = StringBuilder()
        sb.append('A' + (x / 20).toInt()); sb.append('A' + (y / 10).toInt())
        x %= 20.0; y %= 10.0
        sb.append('0' + (x / 2).toInt()); sb.append('0' + (y / 1).toInt())
        x %= 2.0; y %= 1.0
        if (chars >= 6) { sb.append('a' + (x * 12).toInt()); sb.append('a' + (y * 24).toInt()) }
        if (chars >= 8) { x = (x * 12) % 1.0; y = (y * 24) % 1.0; sb.append('0' + (x * 10).toInt()); sb.append('0' + (y * 10).toInt()) }
        return sb.toString()
    }

    /** Callsign without the APRS SSID ("EA7JRS-7" -> "EA7JRS"); portable suffixes stay. */
    fun stationCall(call: String) = call.trim().uppercase().substringBefore('-')

    /** Real frequency of an audio tone: USB (and the FT8 / WSPR dial) add it, LSB subtracts it, CW counts from the 700 Hz pitch. */
    fun rfHz(dialHz: Long, demod: String?, audioHz: Double): Long = when (demod) {
        "LSB" -> dialHz - audioHz.toLong()
        "CW" -> dialHz + (audioHz - Dsp.CW_TONE).toLong()
        else -> dialHz + audioHz.toLong()
    }

    /** Amateur band of a frequency (for "one spot per call and band"); null outside the table. */
    fun band(hz: Long): String? {
        val khz = hz / 1000
        return BANDS.firstOrNull { khz in it.second }?.first
    }
    private val BANDS = listOf(
        "2200m" to 135L..138L, "630m" to 472L..479L, "160m" to 1800L..2000L, "80m" to 3500L..4000L, "60m" to 5060L..5450L,
        "40m" to 7000L..7300L, "30m" to 10100L..10150L, "20m" to 14000L..14350L, "17m" to 18068L..18168L, "15m" to 21000L..21450L,
        "12m" to 24890L..24990L, "10m" to 28000L..29700L, "6m" to 50000L..54000L, "4m" to 70000L..71000L, "2m" to 144000L..148000L,
        "1.25m" to 222000L..225000L, "70cm" to 420000L..450000L, "23cm" to 1240000L..1300000L,
    )

    // ------------------------------------------------------------------ FT8 / FT4 messages

    private val TOKENS = Regex(
        "^(?:(?:[A-Z0-9/]+|\\.{3}) RR73; )?" +
        "(?:((?:CQ|DE|QRZ)(?: ?DX| (?:[A-Z]{1,4}|\\d{3}))|[A-Z0-9/<>]+|\\.{3}) )" +
        "(?:([A-Z0-9/<>]+|\\.{3})(?: ([-+A-Z0-9]+)(?: ((?:OOO|(?!RR73)[A-R]{2}[0-9]{2}|5[0-9]{5}))(?: ([A-R]{2}[0-9]{2}[A-X]{2}))?)?)?)?")

    /** Sender and locator of an FT8 / FT4 message (DecodedText::deCallAndGrid of WSJT-X); null if there is no sender. */
    fun deCallAndGrid(message: String): Pair<String, String>? {
        var msg = message.trim()
        val p = msg.indexOf("; ")
        if (p >= 0) msg = msg.substring(p + 2)
        val m = TOKENS.find(msg) ?: return null
        val call = m.groupValues[2].trim('<', '>')
        var grid = m.groupValues[3]
        if (grid == "R") grid = m.groupValues[4]
        if (call.isEmpty() || call == "..." || !call.any(Char::isDigit) || !call.any(Char::isLetter)) return null
        return call to grid
    }

    /**
     * FT8 / FT4 decode -> PSK Reporter spot, with WSJT-X's rule: only messages that carry the sender's locator or are a CQ,
     * and never our own station (same call and locator).
     */
    fun fromWsjt(d: Wsjt.Decode, mode: Wsjt.Mode, dialHz: Long?, ownCall: String, ownGrid: String): Spot? {
        if (mode.wsprLike || dialHz == null || dialHz <= 0) return null
        val (call, grid) = deCallAndGrid(d.message) ?: return null
        val base = stationCall(ownCall)
        if (base.isNotEmpty() && d.message.contains(base) && ownGrid.length >= 4 && d.message.contains(ownGrid.take(4).uppercase())) return null
        val g = if (isGrid(grid)) grid else ""
        if (g.isEmpty() && !d.message.startsWith("CQ ")) return null
        return Spot(call, g, dialHz + d.audioHz, mode.label, Math.round(d.snr).toInt(), d.slotMs)
    }

    /**
     * Call, locator ("" if none) and power (null if the message has none: FST4W type 3) of a WSPR / FST4W message
     * (types 1, 2 and 3); null for "<...>" or anything else.
     */
    fun wsprFields(message: String): Triple<String, String, Int?>? {
        val w = message.trim().split(Regex("\\s+"))
        return when {
            w.size == 3 && Regex("^[A-Z0-9/]+$").matches(w[0]) && Regex("^[A-R]{2}[0-9]{2}$").matches(w[1]) && w[2].toIntOrNull() != null ->
                Triple(w[0], w[1], w[2].toInt())
            w.size == 2 && Regex("^[A-Z0-9]+/[A-Z0-9]+$").matches(w[0]) && w[1].toIntOrNull() != null ->
                Triple(w[0], "", w[1].toInt())
            w.size >= 2 && Regex("^<[A-Z0-9/]+>$").matches(w[0]) && Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$").matches(w[1]) ->
                Triple(w[0].trim('<', '>'), w[1], w.getOrNull(2)?.toIntOrNull())
            else -> null
        }
    }

    // ------------------------------------------------------------------ WSPRnet

    object WsprNet {
        const val URL = "http://wsprnet.org/post/"

        /** WSPRnet "mode": 2 = WSPR-2; FST4W = period in minutes, 3 for FST4W-120 (WSJT-X encode_mode). */
        fun modeCode(mode: Wsjt.Mode): String = when (mode) {
            Wsjt.Mode.WSPR -> "2"
            Wsjt.Mode.FST4W_120 -> "3"
            else -> (mode.periodMs / 60_000).toString()
        }

        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        private fun form(items: List<Pair<String, String>>) = items.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        private fun mhz(hz: Long) = String.format(Locale.US, "%.6f", hz / 1e6)

        /** Body of one spot (POST, application/x-www-form-urlencoded); null if it cannot be reported. */
        fun spot(d: Wsjt.Decode, mode: Wsjt.Mode, rcall: String, rgrid: String, dialHz: Long, version: String): String? {
            val rf = d.rfHz ?: return null
            val (call, grid, dbm) = wsprFields(d.message) ?: return null
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = d.slotMs }
            return form(listOf(
                "function" to "wspr",
                "date" to String.format(Locale.US, "%02d%02d%02d", c.get(Calendar.YEAR) % 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)),
                "time" to String.format(Locale.US, "%02d%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)),
                "sig" to Math.round(d.snr).toString(),
                "dt" to String.format(Locale.US, "%.1f", d.dt),
                "drift" to (d.drift ?: 0).toString(),
                "tqrg" to mhz(rf),
                "tcall" to call, "tgrid" to grid, "dbm" to (dbm?.toString() ?: ""),
                "version" to version, "rcall" to stationCall(rcall), "rgrid" to rgrid, "rqrg" to mhz(dialHz), "mode" to modeCode(mode),
            ))
        }

        /** "Listening, nothing heard" (after a slot without spots). */
        fun status(mode: Wsjt.Mode, rcall: String, rgrid: String, dialHz: Long, version: String): String = form(listOf(
            "function" to "wsprstat", "rcall" to stationCall(rcall), "rgrid" to rgrid, "rqrg" to mhz(dialHz), "tpct" to "0",
            "tqrg" to mhz(dialHz), "dbm" to "0", "version" to version, "mode" to modeCode(mode),
        ))

        /** WSPRnet answers "... spot(s) added" when it took a spot. */
        fun accepted(reply: String) = reply.contains("spot(s) added")
    }

    // ------------------------------------------------------------------ PSK Reporter

    /** Receiver record: our station. */
    data class Receiver(val call: String, val locator: String, val software: String, val antenna: String = "", val rig: String = "")

    object Ipfix {
        const val HOST = "report.pskreporter.info"
        const val PORT = 4739
        const val TEST_PORT = 14739
        const val MAX_UDP_PAYLOAD = 1000 - 40 - 8
        private const val PEN = 30351
        private const val SENDER_TEMPLATE = 0x50e3
        private const val RECEIVER_TEMPLATE = 0x50e2

        private fun pad(n: Int) = (4 - n % 4) % 4

        private class Buf {
            val b = ByteArrayOutputStream(); val o = DataOutputStream(b)
            fun u8(v: Int) = o.writeByte(v)
            fun u16(v: Int) = o.writeShort(v)
            fun u32(v: Long) = o.writeInt(v.toInt())
            fun str(v: String, max: Int) {
                var u = v.toByteArray(Charsets.UTF_8)
                if (u.size > max) {
                    var n = max
                    while (n > 0 && (u[n].toInt() and 0xc0) == 0x80) n--
                    u = u.copyOf(n)
                }
                o.writeByte(u.size); o.write(u)
            }
            fun raw(x: ByteArray) = o.write(x)
            /** Pads to 4 bytes and writes the length (padded) at [at]. */
            fun close(at: Int = 2): ByteArray {
                repeat(pad(b.size())) { o.writeByte(0) }
                val x = b.toByteArray()
                x[at] = (x.size shr 8).toByte(); x[at + 1] = x.size.toByte()
                return x
            }
        }

        fun descriptors(): ByteArray {
            val s = Buf()
            s.u16(2); s.u16(0); s.u16(SENDER_TEMPLATE); s.u16(7)
            s.u16(0x8000 + 1); s.u16(0xffff); s.u32(PEN.toLong())        // senderCallsign
            s.u16(0x8000 + 5); s.u16(5); s.u32(PEN.toLong())             // frequency (5 bytes)
            s.u16(0x8000 + 6); s.u16(1); s.u32(PEN.toLong())             // sNR
            s.u16(0x8000 + 10); s.u16(0xffff); s.u32(PEN.toLong())       // mode
            s.u16(0x8000 + 3); s.u16(0xffff); s.u32(PEN.toLong())        // senderLocator
            s.u16(0x8000 + 11); s.u16(1); s.u32(PEN.toLong())            // informationSource
            s.u16(150); s.u16(4)                                         // flowStartSeconds
            val a = s.close()
            val r = Buf()
            r.u16(3); r.u16(0); r.u16(RECEIVER_TEMPLATE); r.u16(5); r.u16(1)
            r.u16(0x8000 + 2); r.u16(0xffff); r.u32(PEN.toLong())        // receiverCallsign (scope)
            r.u16(0x8000 + 4); r.u16(0xffff); r.u32(PEN.toLong())        // receiverLocator
            r.u16(0x8000 + 8); r.u16(0xffff); r.u32(PEN.toLong())        // decodingSoftware
            r.u16(0x8000 + 9); r.u16(0xffff); r.u32(PEN.toLong())        // antennaInformation
            r.u16(0x8000 + 13); r.u16(0xffff); r.u32(PEN.toLong())       // rigInformation
            return a + r.close()
        }

        fun receiverSet(rx: Receiver): ByteArray {
            val s = Buf()
            s.u16(RECEIVER_TEMPLATE); s.u16(0)
            s.str(rx.call, 32); s.str(rx.locator, 16); s.str(rx.software, 80); s.str(rx.antenna, 128); s.str(rx.rig, 128)
            return s.close()
        }

        fun record(sp: Spot): ByteArray {
            val s = Buf()
            s.str(sp.call, 32)
            s.u8(((sp.rfHz shr 32) and 0xff).toInt()); s.u32(sp.rfHz and 0xffffffffL)
            s.u8(sp.snr.coerceIn(-128, 127))
            s.str(sp.mode, 16); s.str(sp.locator, 16)
            s.u8(1)                                                      // automatically extracted
            s.u32(sp.timeMs / 1000)
            return s.b.toByteArray()
        }

        private fun senderSet(records: List<ByteArray>): ByteArray {
            val s = Buf()
            s.u16(SENDER_TEMPLATE); s.u16(0)
            records.forEach { s.raw(it) }
            return s.close()
        }

        private fun message(sets: ByteArray, seq: Long, obs: Long, exportTime: Long): ByteArray {
            val s = Buf()
            s.u16(10); s.u16(0); s.u32(exportTime); s.u32(seq); s.u32(obs)
            s.raw(sets)
            return s.close()
        }

        /** Datagrams for [spots] (each with its number of spots); the sequence number counts the spots sent before. */
        fun packets(rx: Receiver, spots: List<Spot>, withDescriptors: Boolean, seq: Long, obs: Long, exportTime: Long,
                    maxBytes: Int = MAX_UDP_PAYLOAD): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            val rxSet = receiverSet(rx)
            val first = (if (withDescriptors) descriptors() else ByteArray(0)) + rxSet
            if (spots.isEmpty()) return listOf(message(first, seq, obs, exportTime) to 0)
            var base = first
            var s = seq
            val recs = ArrayList<ByteArray>()
            var bytes = 0
            for (sp in spots) {
                val r = record(sp)
                val setLen = 4 + bytes + r.size; val msgLen = 16 + base.size + setLen + pad(setLen)
                if (recs.isNotEmpty() && msgLen + pad(msgLen) > maxBytes) {
                    out.add(message(base + senderSet(recs), s, obs, exportTime) to recs.size)
                    s += recs.size; recs.clear(); bytes = 0; base = rxSet
                }
                recs.add(r); bytes += r.size
            }
            out.add(message(base + senderSet(recs), s, obs, exportTime) to recs.size)
            return out
        }
    }

    /**
     * What goes to PSK Reporter and when: each call once per band and mode per hour (PSK Reporter asks for "ideally once
     * per hour if it has not changed"), a datagram every 5 minutes plus some seconds at random, descriptors in the first
     * three datagrams and then hourly.
     */
    class PskQueue(val rx: Receiver, private val obs: Long = (Math.random() * 0xffffffffL).toLong()) {
        private val pending = ArrayList<Spot>()
        private val lastSent = HashMap<String, Long>()
        private var seq = 0L
        private var descriptorsLeft = 3
        private var lastDescriptorsMs = 0L

        @Synchronized
        fun add(sp: Spot, nowMs: Long): Boolean {
            if (sp.call.isBlank() || stationCall(sp.call) == stationCall(rx.call)) return false
            val key = sp.call + "|" + (band(sp.rfHz) ?: (sp.rfHz / 1_000_000).toString()) + "|" + sp.mode
            val last = lastSent[key]
            if (last != null && nowMs - last < 3_600_000L) return false
            lastSent[key] = nowMs
            lastSent.entries.removeAll { nowMs - it.value > 3_600_000L }
            pending.add(sp)
            while (pending.size > 2048) pending.removeAt(0)
            return true
        }

        @get:Synchronized val size get() = pending.size

        /** Datagrams to send now (empty if there is nothing to say). */
        @Synchronized
        fun take(nowMs: Long): List<ByteArray> {
            if (nowMs - lastDescriptorsMs > 3_600_000L) { descriptorsLeft = 3; lastDescriptorsMs = nowMs }
            if (pending.isEmpty() && descriptorsLeft == 0) return emptyList()
            val withD = descriptorsLeft > 0
            if (withD) descriptorsLeft--
            val p = Ipfix.packets(rx, pending.toList(), withD, seq, obs, nowMs / 1000)
            pending.clear()
            seq += p.sumOf { it.second }
            return p.map { it.first }
        }
    }

    // ------------------------------------------------------------------ WSJT-X UDP messages

    object WsjtxUdp {
        const val PORT = 2237
        const val MAGIC = 0xadbccbdaL
        const val SCHEMA = 3
        const val HEARTBEAT_S = 15

        private class Msg(type: Int, id: String) {
            val b = ByteArrayOutputStream(); val o = DataOutputStream(b)
            init { o.writeInt(MAGIC.toInt()); o.writeInt(SCHEMA); o.writeInt(type); utf8(id) }
            fun utf8(s: String) { val u = s.toByteArray(Charsets.UTF_8); o.writeInt(u.size); o.write(u) }
            fun bool(v: Boolean) = o.writeByte(if (v) 1 else 0)
            fun u32(v: Long) = o.writeInt(v.toInt())
            fun i32(v: Int) = o.writeInt(v)
            fun u64(v: Long) = o.writeLong(v)
            fun dbl(v: Double) = o.writeDouble(v)
            fun u8(v: Int) = o.writeByte(v)
            fun time(msOfDay: Long) = o.writeInt(msOfDay.toInt())             // QTime: ms since midnight
            fun bytes(): ByteArray = b.toByteArray()
        }

        fun msOfDay(utcMs: Long) = Math.floorMod(utcMs, 86_400_000L)

        /** Decoded-line marker of WSJT-X for the Decode message: "~" FT8, "+" FT4, "`" FST4 / FST4W. */
        fun marker(mode: Wsjt.Mode) = when (mode) { Wsjt.Mode.FT4 -> "+"; Wsjt.Mode.FT8 -> "~"; else -> "`" }

        fun statusMode(mode: Wsjt.Mode) = when { mode == Wsjt.Mode.WSPR -> "WSPR"; mode.isFst4w -> "FST4W"; else -> mode.label }

        fun heartbeat(id: String, version: String, revision: String = ""): ByteArray =
            Msg(0, id).apply { u32(SCHEMA.toLong()); utf8(version); utf8(revision) }.bytes()

        fun status(id: String, dialHz: Long, mode: Wsjt.Mode, deCall: String, deGrid: String, decoding: Boolean, rxDfHz: Int = 1500): ByteArray =
            Msg(1, id).apply {
                u64(dialHz); utf8(statusMode(mode)); utf8(""); utf8(""); utf8(statusMode(mode))
                bool(false); bool(false); bool(decoding)
                u32(rxDfHz.toLong()); u32(rxDfHz.toLong())
                utf8(deCall); utf8(deGrid); utf8("")
                bool(false); utf8(""); bool(false); u8(0)
                u32(0xffffffffL); u32(mode.periodMs / 1000); utf8("APRS LoRa+"); utf8("")
            }.bytes()

        fun decode(id: String, utcMs: Long, snr: Int, dt: Double, dfHz: Int, marker: String, message: String): ByteArray =
            Msg(2, id).apply { bool(true); time(msOfDay(utcMs)); i32(snr); dbl(dt); u32(dfHz.toLong()); utf8(marker); utf8(message); bool(false); bool(false) }.bytes()

        fun wsprDecode(id: String, utcMs: Long, snr: Int, dt: Double, freqHz: Long, drift: Int, call: String, grid: String, dbm: Int): ByteArray =
            Msg(10, id).apply { bool(true); time(msOfDay(utcMs)); i32(snr); dbl(dt); u64(freqHz); i32(drift); utf8(call); utf8(grid); i32(dbm); bool(false) }.bytes()

        fun clear(id: String): ByteArray = Msg(3, id).bytes()

        /** The UDP message for one decode, as WSJT-X would send it (WSPR: WSPRDecode; FT8 / FT4 / FST4W: Decode). */
        fun forDecode(id: String, d: Wsjt.Decode, mode: Wsjt.Mode, dialHz: Long?): ByteArray? {
            if (mode == Wsjt.Mode.WSPR) {
                val (call, grid, dbm) = wsprFields(d.message) ?: return null
                val f = d.rfHz ?: ((dialHz ?: 0L) + d.audioHz)
                return wsprDecode(id, d.slotMs, Math.round(d.snr).toInt(), d.dt, f, d.drift ?: 0, call, grid, dbm ?: 0)
            }
            return decode(id, d.slotMs, Math.round(d.snr).toInt(), d.dt, d.audioHz, marker(mode), d.message)
        }
    }

    // ------------------------------------------------------------------ CW: calls in the decoded text, skimmer lines

    data class CwHit(val call: String, val comment: String)

    /**
     * Finds the calls of the stations being heard in decoded CW text, as a skimmer does: a call right after CQ, DE, TEST or
     * QRZ (or "CQ DX CALL", "CQ TEST CALL"), seen at least twice within [windowMs]. Each call is given once every
     * [repeatMs]. Comment: "CQ" if it was calling CQ, "BEACON" after VVV / BCN / BEACON, else "DE".
     */
    class CwSpotter(private val ownCall: String = "", private val windowMs: Long = 120_000, private val repeatMs: Long = 600_000) {
        private val word = StringBuilder()
        private val recent = ArrayDeque<Pair<String, Long>>()                       // last words
        private val seen = HashMap<String, MutableList<Pair<Long, String>>>()       // call -> times and contexts
        private val given = HashMap<String, Long>()

        fun reset() { word.setLength(0); recent.clear(); seen.clear() }

        fun feed(text: String, nowMs: Long): List<CwHit> {
            val out = ArrayList<CwHit>()
            for (ch in text.uppercase()) {
                if (ch == ' ' || ch == '\n' || ch == '\r') { if (word.isNotEmpty()) { endWord(nowMs)?.let(out::add); word.setLength(0) } }
                else if (ch.isLetterOrDigit() || ch == '/') { if (word.length < 16) word.append(ch) }
                else if (word.isNotEmpty()) { endWord(nowMs)?.let(out::add); word.setLength(0) }
            }
            return out
        }

        private fun endWord(now: Long): CwHit? {
            val w = word.toString()
            val prev = recent.lastOrNull()?.first
            val prev2 = if (recent.size >= 2) recent[recent.size - 2].first else null
            recent.addLast(w to now)
            while (recent.size > 12) recent.removeFirst()
            if (!isCall(w) || stationCall(w) == stationCall(ownCall)) return null
            val ctx = when {
                prev == "CQ" || (prev2 == "CQ" && (prev == "DX" || prev == "TEST" || prev == "DE")) -> "CQ"
                prev == "TEST" -> "CQ"
                prev == "DE" || prev == "QRZ" -> if (recent.any { it.first == "VVV" || it.first == "BCN" || it.first == "BEACON" }) "BEACON" else "DE"
                prev != null && prev == w -> "DE"                                     // "EA7JRS EA7JRS"
                else -> null
            }
            val list = seen.getOrPut(w) { ArrayList() }
            list.add(now to (ctx ?: ""))
            list.removeAll { now - it.first > windowMs }
            seen.entries.removeAll { e -> e.value.all { now - it.first > windowMs } }
            if (list.size < 2 || list.none { it.second.isNotEmpty() }) return null
            val last = given[w]
            if (last != null && now - last < repeatMs) return null
            given[w] = now
            given.entries.removeAll { now - it.value > repeatMs }
            val c = when { list.any { it.second == "CQ" } -> "CQ"; list.any { it.second == "BEACON" } -> "BEACON"; else -> "DE" }
            return CwHit(w, c)
        }

        companion object {
            private val CALL = Regex("^(?:[A-Z0-9]{1,3}/)?(?:[A-Z]{1,2}|[A-Z][0-9]|[0-9][A-Z])[0-9][A-Z0-9]{0,3}[A-Z](?:/[A-Z0-9]{1,4})?$")
            fun isCall(w: String) = CALL.matches(w) && !w.startsWith("5NN")
        }
    }

    /** CW Skimmer / RBN style line: "DX de EA7JRS-#:  14025.1  K1JT         CW    15 dB  22 WPM  CQ      1234Z". */
    fun skimmerLine(spotter: String, rfHz: Long, call: String, snr: Int, wpm: Int, comment: String, utcMs: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
        return String.format(Locale.US, "DX de %-10s%9.1f  %-12s CW   %3d dB  %2d WPM  %-7s %02d%02dZ",
            stationCall(spotter) + "-#:", rfHz / 1000.0, call, snr, wpm, comment, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
