/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * ADS-B / Mode S on 1090 MHz, received by the app itself: the demodulator for the RTL-SDR at 2 MS/s ([Demod]), the
 * messages (identification, position with CPR, altitude, speed, squawk), the table of aircraft ([Table]) and the formats
 * of a receiver on the network (dump1090 / readsb: Beast, raw AVR and BaseStation, [NetParser]).
 *
 * Message formats from ICAO Annex 10 as explained in "The 1090 MHz Riddle" (J. Sun, TU Delft), whose worked examples are
 * the test cases; the pulse detector follows the method of dump1090 (S. Sanfilippo, BSD), written anew.
 */
object Adsb {
    const val FREQ_HZ = 1_090_000_000L
    const val SAMPLE_RATE = 2_000_000

    // ------------------------------------------------------------------ CRC (24 bits, generator 0xFFF409)

    private val CRC_TABLE = IntArray(256) { i ->
        var c = i shl 16
        repeat(8) { c = if (c and 0x800000 != 0) (c shl 1) xor 0xFFF409 else c shl 1 }
        c and 0xFFFFFF
    }

    /** Mode S parity (24 bits) of [len] bytes. */
    fun crc(msg: ByteArray, len: Int = msg.size): Int {
        var rem = 0
        for (i in 0 until len) rem = ((rem shl 8) xor CRC_TABLE[((msg[i].toInt() and 0xFF) xor (rem shr 16)) and 0xFF]) and 0xFFFFFF
        return rem
    }

    /**
     * Parity computed over the data, mixed with the last three bytes of the message: 0 in a good DF17 / DF18, the
     * interrogator in a DF11 and the address of the aircraft in the address-parity formats.
     */
    fun residual(msg: ByteArray, len: Int = msg.size): Int =
        crc(msg, len - 3) xor (((msg[len - 3].toInt() and 0xFF) shl 16) or ((msg[len - 2].toInt() and 0xFF) shl 8) or (msg[len - 1].toInt() and 0xFF))

    /** Residual that a single wrong bit leaves, by bit position, for 112-bit messages: lets one bit be repaired. */
    private val SYNDROME_112: Map<Int, Int> = HashMap<Int, Int>().also { m ->
        for (bit in 0 until 112) { val b = ByteArray(14); b[bit / 8] = (0x80 shr (bit % 8)).toByte(); m[residual(b)] = bit }
    }

    fun hex(b: ByteArray, len: Int = b.size): String = buildString { for (i in 0 until len) append(String.format("%02X", b[i].toInt() and 0xFF)) }
    fun unhex(s: String): ByteArray? {
        val t = s.trim()
        if (t.length % 2 != 0 || t.isEmpty() || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun bits(m: ByteArray, from: Int, n: Int): Int {            // bits of the message, 0 = first, most significant first
        var v = 0
        for (i in from until from + n) v = (v shl 1) or ((m[i / 8].toInt() shr (7 - i % 8)) and 1)
        return v
    }

    // ------------------------------------------------------------------ messages

    /** What one message says. Only the fields it carries are set. */
    class Message(val df: Int, val icao: Int) {
        var typeCode = 0
        var callsign: String? = null
        var category: Int? = null
        var altFt: Int? = null
        var gnssAlt = false
        var onGround: Boolean? = null
        var cprOdd: Boolean? = null
        var cprLat = 0
        var cprLon = 0
        var gsKt: Double? = null
        var trackDeg: Double? = null
        var vrFpm: Int? = null
        var squawk: Int? = null
        var fixedBit = -1
    }

    private const val CHARSET = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

    /** 13-bit altitude code of DF0 / 4 / 16 / 20, in feet; null in metres or in the 100 ft Gillham code. */
    private fun alt13(x: Int): Int? {
        if (x == 0 || x and 0x40 != 0 || x and 0x10 == 0) return null
        val n = ((x and 0x1F80) shr 2) or ((x and 0x20) shr 1) or (x and 0xF)
        return n * 25 - 1000
    }
    /** 12-bit altitude code of the airborne position, in feet. */
    private fun alt12(x: Int): Int? {
        if (x == 0 || x and 0x10 == 0) return null
        return (((x and 0xFE0) shr 1) or (x and 0xF)) * 25 - 1000
    }
    /** Identity code (squawk) of DF5 / 21: the 13 bits C1 A1 C2 A2 C4 A4 X B1 D1 B2 D2 B4 D4, as four octal digits. */
    private fun squawk(id: Int): Int {
        fun b(n: Int) = (id shr (12 - n)) and 1                          // n = 0: first bit (C1)
        val a = b(5) * 4 + b(3) * 2 + b(1)
        val bb = b(11) * 4 + b(9) * 2 + b(7)
        val c = b(4) * 4 + b(2) * 2 + b(0)
        val d = b(12) * 4 + b(10) * 2 + b(8)
        return a * 1000 + bb * 100 + c * 10 + d
    }

    /**
     * Checks and reads a message of 7 or 14 bytes. [known] tells whether an address has been heard before: the formats that
     * carry the address mixed with the parity (DF0, 4, 5, 16, 20, 21) are only believed for those. With [fix], one wrong
     * bit of a DF17 / DF18 is repaired (in [msg] too). Null if it does not pass.
     */
    fun decode(msg: ByteArray, known: (Int) -> Boolean = { false }, fix: Boolean = true): Message? {
        if (msg.size < 7) return null
        val df = (msg[0].toInt() and 0xFF) shr 3
        val len = if (df >= 16) 14 else 7
        if (msg.size < len) return null
        val rem = residual(msg, len)
        when (df) {
            17, 18 -> {
                var fixed = -1
                if (rem != 0) {
                    val bit = if (fix) SYNDROME_112[rem] else null
                    if (bit == null || bit < 5) return null              // never repair the format itself
                    msg[bit / 8] = (msg[bit / 8].toInt() xor (0x80 shr (bit % 8))).toByte()
                    fixed = bit
                }
                val m = Message(df, bits(msg, 8, 24))
                m.fixedBit = fixed
                extended(msg, m)
                return m
            }
            11 -> {
                if (rem and 0xFFFF80 != 0) return null                   // the last 7 bits carry the interrogator
                return Message(df, bits(msg, 8, 24))
            }
            0, 4, 5, 16, 20, 21 -> {
                if (!known(rem)) return null
                val m = Message(df, rem)
                when (df) {
                    0, 16, 4, 20 -> m.altFt = alt13(bits(msg, 19, 13))
                    else -> m.squawk = squawk(bits(msg, 19, 13))
                }
                if (df == 4 || df == 5 || df == 20 || df == 21) {
                    val fs = bits(msg, 5, 3)
                    if (fs == 0 || fs == 2) m.onGround = false else if (fs == 1 || fs == 3) m.onGround = true
                } else if (df == 0 || df == 16) m.onGround = bits(msg, 5, 1) == 1
                return m
            }
            else -> return null
        }
    }

    private fun extended(msg: ByteArray, m: Message) {
        val me = 32                                                      // first bit of the 56-bit ME field
        val tc = bits(msg, me, 5)
        m.typeCode = tc
        when {
            tc in 1..4 -> {
                m.category = (tc shl 4) or bits(msg, me + 5, 3)
                val sb = StringBuilder()
                for (i in 0 until 8) sb.append(CHARSET[bits(msg, me + 8 + i * 6, 6)])
                m.callsign = sb.toString().replace('#', ' ').trim().ifEmpty { null }
            }
            tc in 5..8 -> {                                              // surface position
                m.onGround = true
                m.cprOdd = bits(msg, me + 21, 1) == 1
                m.cprLat = bits(msg, me + 22, 17); m.cprLon = bits(msg, me + 39, 17)
            }
            tc in 9..18 || tc in 20..22 -> {                             // airborne position (barometric / GNSS height)
                m.onGround = false
                m.gnssAlt = tc >= 20
                m.altFt = alt12(bits(msg, me + 8, 12))
                m.cprOdd = bits(msg, me + 21, 1) == 1
                m.cprLat = bits(msg, me + 22, 17); m.cprLon = bits(msg, me + 39, 17)
            }
            tc == 19 -> {
                val st = bits(msg, me + 5, 3)
                if (st == 1 || st == 2) {                                // speed over the ground
                    val k = if (st == 2) 4 else 1
                    val vew = bits(msg, me + 14, 10); val vns = bits(msg, me + 25, 10)
                    if (vew != 0 && vns != 0) {
                        val vx = (vew - 1) * k * (if (bits(msg, me + 13, 1) == 1) -1 else 1)       // 1 = towards the west
                        val vy = (vns - 1) * k * (if (bits(msg, me + 24, 1) == 1) -1 else 1)       // 1 = towards the south
                        m.gsKt = Math.sqrt((vx * vx + vy * vy).toDouble())
                        m.trackDeg = (Math.toDegrees(Math.atan2(vx.toDouble(), vy.toDouble())) + 360.0) % 360.0
                    }
                } else if (st == 3 || st == 4) {                         // airspeed and heading
                    if (bits(msg, me + 13, 1) == 1) m.trackDeg = bits(msg, me + 14, 10) * 360.0 / 1024.0
                }
                val vr = bits(msg, me + 37, 9)
                if (vr != 0) m.vrFpm = (vr - 1) * 64 * (if (bits(msg, me + 36, 1) == 1) -1 else 1)
            }
            tc == 28 -> if (bits(msg, me + 5, 3) == 1) m.squawk = squawk(bits(msg, me + 11, 13))
        }
    }

    // ------------------------------------------------------------------ CPR positions

    private fun mod(a: Double, b: Double): Double { val r = a % b; return if (r < 0) r + b else r }

    /** Number of longitude zones at a latitude. */
    fun nl(lat: Double): Int {
        val a = Math.abs(lat)
        if (a < 1e-9) return 59
        if (a >= 87.0) return if (a == 87.0) 2 else 1
        val c = Math.cos(Math.PI / 180.0 * a)
        return Math.floor(2 * Math.PI / Math.acos(1 - (1 - Math.cos(Math.PI / 30.0)) / (c * c))).toInt()
    }

    /** Position from an even and an odd airborne message close in time; [latestOdd] says which one came last. */
    fun cprGlobal(latE: Int, lonE: Int, latO: Int, lonO: Int, latestOdd: Boolean): Pair<Double, Double>? {
        val le = latE / 131072.0; val lo = latO / 131072.0
        val j = Math.floor(59 * le - 60 * lo + 0.5)
        var rE = 360.0 / 60 * (mod(j, 60.0) + le)
        var rO = 360.0 / 59 * (mod(j, 59.0) + lo)
        if (rE >= 270) rE -= 360
        if (rO >= 270) rO -= 360
        if (rE < -90 || rE > 90 || rO < -90 || rO > 90 || nl(rE) != nl(rO)) return null
        val lat = if (latestOdd) rO else rE
        val n = nl(lat)
        val ni = maxOf(if (latestOdd) n - 1 else n, 1)
        val m = Math.floor(lonE / 131072.0 * (n - 1) - lonO / 131072.0 * n + 0.5)
        var lon = 360.0 / ni * (mod(m, ni.toDouble()) + (if (latestOdd) lonO else lonE) / 131072.0)
        if (lon >= 180) lon -= 360
        return lat to lon
    }

    /** Position from one airborne message and a reference less than 180 NM away (the last position, or the receiver). */
    fun cprLocal(cprLat: Int, cprLon: Int, odd: Boolean, refLat: Double, refLon: Double): Pair<Double, Double> {
        val dLat = 360.0 / (if (odd) 59 else 60)
        val yz = cprLat / 131072.0
        val j = Math.floor(refLat / dLat) + Math.floor(0.5 + mod(refLat, dLat) / dLat - yz)
        val lat = dLat * (j + yz)
        val dLon = 360.0 / maxOf(nl(lat) - (if (odd) 1 else 0), 1)
        val xz = cprLon / 131072.0
        val m = Math.floor(refLon / dLon) + Math.floor(0.5 + mod(refLon, dLon) / dLon - xz)
        return lat to dLon * (m + xz)
    }

    // ------------------------------------------------------------------ aircraft

    data class Aircraft(
        val icao: Int, val callsign: String? = null, val lat: Double? = null, val lon: Double? = null, val altFt: Int? = null,
        val gsKt: Double? = null, val trackDeg: Double? = null, val vrFpm: Int? = null, val squawk: Int? = null,
        val onGround: Boolean = false, val seenMs: Long = 0, val posMs: Long = 0, val messages: Int = 0,
    ) {
        val hex: String get() = String.format("%06X", icao)
        val name: String get() = callsign ?: hex
        /** 7500 unlawful interference, 7600 radio failure, 7700 emergency. */
        val emergency: String? get() = when (squawk) { 7500 -> "HIJACK 7500"; 7600 -> "RADIO FAILURE 7600"; 7700 -> "EMERGENCY 7700"; else -> null }
        fun flightLevel(): String? = altFt?.let { if (it >= 6000) String.format(Locale.US, "FL%03d", Math.round(it / 100.0)) else "$it ft" }
    }

    private class Track(val icao: Int) {
        var callsign: String? = null
        var lat: Double? = null; var lon: Double? = null; var posMs = 0L
        var altFt: Int? = null; var gs: Double? = null; var trk: Double? = null; var vr: Int? = null; var squawk: Int? = null
        var ground = false
        var seen = 0L; var n = 0
        var evLat = 0; var evLon = 0; var evMs = 0L
        var odLat = 0; var odLon = 0; var odMs = 0L
    }

    /**
     * The aircraft heard. Messages come in with [onFrame] (raw Mode S) or [onSbs] (BaseStation line); [list] gives those
     * heard at least twice in the last [keepMs]. The receiver position, when known, places an aircraft from its first
     * message and rejects positions that cannot be (more than [maxKm] away).
     */
    class Table(val keepMs: Long = 300_000L, val maxKm: Double = 600.0) {
        private val tracks = HashMap<Int, Track>()
        var frames = 0L; private set
        var positions = 0L; private set

        @Synchronized fun clear() { tracks.clear(); frames = 0; positions = 0 }

        @Synchronized
        fun onFrame(msg: ByteArray, nowMs: Long, rxLat: Double? = null, rxLon: Double? = null): Message? {
            val m = decode(msg, known = { a -> tracks[a]?.let { nowMs - it.seen < 60_000L } == true }) ?: return null
            frames++
            val t = tracks.getOrPut(m.icao) { Track(m.icao) }
            t.seen = nowMs; t.n++
            m.callsign?.let { t.callsign = it }
            m.altFt?.let { t.altFt = it }
            m.gsKt?.let { t.gs = it }
            m.trackDeg?.let { t.trk = it }
            m.vrFpm?.let { t.vr = it }
            m.squawk?.let { t.squawk = it }
            m.onGround?.let { t.ground = it }
            val odd = m.cprOdd
            if (odd != null && m.typeCode >= 9) {                        // airborne position
                if (odd) { t.odLat = m.cprLat; t.odLon = m.cprLon; t.odMs = nowMs } else { t.evLat = m.cprLat; t.evLon = m.cprLon; t.evMs = nowMs }
                var p: Pair<Double, Double>? = null
                if (t.evMs > 0 && t.odMs > 0 && Math.abs(t.evMs - t.odMs) <= 10_000L) p = cprGlobal(t.evLat, t.evLon, t.odLat, t.odLon, odd)
                if (p == null) {
                    val la = t.lat; val lo = t.lon
                    if (la != null && lo != null && nowMs - t.posMs < 180_000L) p = cprLocal(m.cprLat, m.cprLon, odd, la, lo)
                    else if (rxLat != null && rxLon != null) p = cprLocal(m.cprLat, m.cprLon, odd, rxLat, rxLon)
                }
                if (p != null && (rxLat == null || rxLon == null || Geo.distanceKm(rxLat, rxLon, p.first, p.second) <= maxKm)) {
                    t.lat = p.first; t.lon = p.second; t.posMs = nowMs; positions++
                }
            }
            return m
        }

        /** A BaseStation line (port 30003 of dump1090 / readsb): "MSG,3,1,1,4CA2D6,1,...,callsign,alt,gs,track,lat,lon,vr,squawk,...". */
        @Synchronized
        fun onSbs(line: String, nowMs: Long): Boolean {
            val f = line.trim().split(',')
            if (f.size < 11 || f[0] != "MSG") return false
            val icao = f[4].trim().toIntOrNull(16) ?: return false
            frames++
            val t = tracks.getOrPut(icao) { Track(icao) }
            t.seen = nowMs; t.n++
            fun s(i: Int) = f.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            s(10)?.let { t.callsign = it }
            s(11)?.toDoubleOrNull()?.let { t.altFt = it.toInt() }
            s(12)?.toDoubleOrNull()?.let { t.gs = it }
            s(13)?.toDoubleOrNull()?.let { t.trk = it }
            val la = s(14)?.toDoubleOrNull(); val lo = s(15)?.toDoubleOrNull()
            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) { t.lat = la; t.lon = lo; t.posMs = nowMs; positions++ }
            s(16)?.toDoubleOrNull()?.let { t.vr = it.toInt() }
            s(17)?.toIntOrNull()?.let { t.squawk = it }
            s(21)?.let { t.ground = it == "-1" || it == "1" }
            return true
        }

        @Synchronized
        fun list(nowMs: Long): List<Aircraft> {
            tracks.values.removeAll { nowMs - it.seen > keepMs }
            return tracks.values.filter { it.n >= 2 }.map {
                val fresh = nowMs - it.posMs < keepMs
                Aircraft(it.icao, it.callsign, it.lat.takeIf { fresh }, it.lon.takeIf { fresh }, it.altFt, it.gs, it.trk, it.vr, it.squawk, it.ground, it.seen, it.posMs, it.n)
            }
        }
    }

    // ------------------------------------------------------------------ demodulator (RTL-SDR, 2 MS/s, 8-bit I / Q)

    /**
     * Mode S from the samples of an RTL-SDR tuned to 1090 MHz at 2 MS/s (unsigned 8-bit I, Q): looks for the preamble (four
     * pulses in 8 µs), slices the 56 or 112 bits (each bit is a pulse in its first or its second half) and hands the
     * messages that pass [decode] to [onFrame]. It keeps the end of each block for the next one.
     */
    class Demod(private val known: (Int) -> Boolean = { false }, private val onFrame: (ByteArray) -> Unit) {
        private var mag = IntArray(0)
        private var carry = 0
        var candidates = 0L; private set
        var good = 0L; private set

        fun process(iq: ByteArray, n: Int = iq.size) {
            val pairs = n / 2
            if (mag.size < carry + pairs) mag = mag.copyOf(carry + pairs)
            for (i in 0 until pairs) mag[carry + i] = MAG[((iq[2 * i].toInt() and 0xFF) shl 8) or (iq[2 * i + 1].toInt() and 0xFF)]
            val len = carry + pairs
            var j = 0
            val last = len - FULL
            val msg = ByteArray(14)
            while (j < last) {
                val m = mag
                if (!(m[j] > m[j + 1] && m[j + 1] < m[j + 2] && m[j + 2] > m[j + 3] && m[j + 3] < m[j] && m[j + 4] < m[j] && m[j + 5] < m[j] &&
                        m[j + 6] < m[j] && m[j + 7] > m[j + 8] && m[j + 8] < m[j + 9] && m[j + 9] > m[j + 6])) { j++; continue }
                val high = (m[j] + m[j + 2] + m[j + 7] + m[j + 9]) / 6
                if (m[j + 4] >= high || m[j + 5] >= high || m[j + 11] >= high || m[j + 12] >= high || m[j + 13] >= high || m[j + 14] >= high) { j++; continue }
                candidates++
                java.util.Arrays.fill(msg, 0)
                var prev = 0
                var bad = 0
                for (b in 0 until 112) {
                    val a = m[j + 16 + 2 * b]; val c = m[j + 17 + 2 * b]
                    val d = if (a > c) a - c else c - a
                    val bit = if (b > 0 && d < 256) prev else if (a == c) { bad++; prev } else if (a > c) 1 else 0
                    prev = bit
                    if (bit == 1) msg[b / 8] = (msg[b / 8].toInt() or (0x80 shr (b % 8))).toByte()
                    if (b == 4 && ((msg[0].toInt() and 0xFF) shr 3).let { it != 0 && it != 4 && it != 5 && it != 11 && it != 16 && it != 17 && it != 18 && it != 20 && it != 21 }) { bad = 999; break }
                }
                if (bad > 4) { j++; continue }
                val df = (msg[0].toInt() and 0xFF) shr 3
                val bytes = if (df >= 16) 14 else 7
                val out = msg.copyOf(bytes)
                if (decode(out, known) != null) { good++; onFrame(out); j += (8 + bytes * 8) * 2 } else j++
            }
            // keep what a message starting at the end would need
            val keep = minOf(len, FULL)
            System.arraycopy(mag, len - keep, mag, 0, keep)
            carry = keep
        }

        private companion object {
            const val FULL = (8 + 112) * 2
            /** |I + jQ| of every pair of bytes, scaled so that full scale is about 65000 (the scale of the bit threshold). */
            val MAG = IntArray(65536) { v ->
                val i = (v shr 8) - 127.5; val q = (v and 0xFF) - 127.5
                Math.round(Math.sqrt(i * i + q * q) * 360.0).toInt()
            }
        }
    }

    // ------------------------------------------------------------------ receiver on the network (dump1090 / readsb)

    /**
     * What a dump1090 / readsb sends on its TCP ports, whichever it is: Beast binary (30005), raw AVR lines "*8D...;" (30002)
     * or BaseStation lines "MSG,..." (30003). The format is told from the data. Raw messages go to [onFrame], BaseStation
     * lines to [onSbs].
     */
    class NetParser(private val onFrame: (ByteArray) -> Unit, private val onSbs: (String) -> Unit) {
        var format = ""; private set                                     // "Beast", "AVR", "BaseStation" once known
        private val line = StringBuilder()
        private val beast = ByteArray(32)
        private var bLen = 0; private var bWant = 0; private var bEsc = false; private var bIn = false

        fun feed(d: ByteArray, n: Int = d.size) {
            for (k in 0 until n) {
                val c = d[k].toInt() and 0xFF
                if (format.isEmpty()) {
                    format = when (c) { 0x1A -> "Beast"; '*'.code, '@'.code -> "AVR"; 'M'.code -> "BaseStation"; else -> "" }
                    if (format.isEmpty()) continue
                }
                if (format == "Beast") beastByte(c) else textByte(c)
            }
        }

        private fun textByte(c: Int) {
            if (c == '\n'.code || c == '\r'.code) {
                if (line.isNotEmpty()) {
                    val s = line.toString(); line.setLength(0)
                    if (s.startsWith("MSG")) onSbs(s)
                    else if ((s.startsWith("*") || s.startsWith("@")) && s.endsWith(";")) {
                        val h = s.substring(if (s[0] == '@') 13.coerceAtMost(s.length - 1) else 1, s.length - 1)
                        unhex(h)?.takeIf { it.size == 7 || it.size == 14 }?.let(onFrame)
                    }
                }
            } else if (line.length < 256) line.append(c.toChar())
        }

        // 0x1A, type ('1' Mode A/C, '2' short, '3' long), 6 bytes of time, 1 of signal, the message; a 0x1A inside is doubled
        private fun beastByte(c: Int) {
            if (bEsc) {
                bEsc = false
                if (c != 0x1A) {                                         // start of a frame
                    bIn = true; bLen = 0
                    bWant = when (c) { '1'.code -> 7 + 2; '2'.code -> 7 + 7; '3'.code -> 7 + 14; else -> 0 }
                    if (bWant == 0) bIn = false
                    return
                }
            } else if (c == 0x1A) { bEsc = true; return }
            if (!bIn) return
            beast[bLen++] = c.toByte()
            if (bLen == bWant) {
                bIn = false
                if (bWant > 9) onFrame(beast.copyOfRange(7, bWant))
            }
        }
    }
}
