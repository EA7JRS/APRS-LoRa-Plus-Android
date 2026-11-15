/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * Cospas-Sarsat 406 MHz distress beacons, first generation (EPIRB, ELT, PLB): 400 bit/s biphase-L, phase modulation of ±1.1 rad
 * after 160 ms of carrier; 15 bits of 1, frame sync 000101111 (011010000 in self-test), short (112 bits) or long (144 bits) message
 * with BCH(82,61) over bits 25-85 and, in the long one, BCH(38,26) over bits 107-132. Signal, layout, protocol codes and the
 * modified Baudot table follow ITU-R M.633 Annex II; the location protocols (standard, national, user-location) are read as the
 * Cospas-Sarsat specification C/S T.001 lays them out (written from memory: check against a decoded real beacon).
 * Second-generation beacons (spread spectrum, 2021 on) are not decoded.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object Beacon406 {
    const val BAUD = 400.0
    /** Channels in use (Hz); tuning [TUNE_HZ] with 30 kHz of band covers them all. */
    val CHANNELS_HZ = listOf(406_025_000L, 406_028_000L, 406_031_000L, 406_037_000L, 406_040_000L)
    const val TUNE_HZ = 406_031_000L
    const val SYNC_NORMAL = 0b000101111
    const val SYNC_SELF_TEST = 0b011010000

    // ------------------------------------------------------------------ BCH

    /** Product of the three minimal polynomials of M.633 (x^7+x^3+1)(x^7+x^3+x^2+x+1)(x^7+x^4+x^3+x^2+1): degree 21. */
    val BCH1: Long = run {
        fun mul(a: Long, b: Long): Long { var r = 0L; for (i in 0 until 63) if ((b shr i) and 1L == 1L) r = r xor (a shl i); return r }
        mul(mul(0b10001001L, 0b10001111L), 0b10011101L)
    }
    const val BCH2: Long = 0b1010100111001L        // x^12+x^10+x^8+x^5+x^4+x^3+1

    /** Bit n (1-based, as the specification numbers them) of [b] (bit n at index n-1). */
    private fun bit(b: IntArray, n: Int) = if (n - 1 < b.size) b[n - 1] else 0
    /** Bits [from]..[to] (1-based, inclusive) as an unsigned number, first bit most significant. */
    fun field(b: IntArray, from: Int, to: Int): Long { var v = 0L; for (n in from..to) v = (v shl 1) or bit(b, n).toLong(); return v }

    /** The [deg] check bits of data bits [from]..[to] for generator [gen]. */
    fun parity(b: IntArray, from: Int, to: Int, gen: Long, deg: Int): Long {
        var r = 0L
        val top = 1L shl deg
        for (n in from..to) {
            val fb = ((r shr (deg - 1)) and 1L) xor bit(b, n).toLong()
            r = (r shl 1) and (top - 1)
            if (fb == 1L) r = r xor (gen and (top - 1))
        }
        return r
    }

    // ------------------------------------------------------------------ modified Baudot (M.633 Table XI)

    private val BAUDOT: Map<Int, Char> = mapOf(
        0b111000 to 'A', 0b110011 to 'B', 0b101110 to 'C', 0b110010 to 'D', 0b110000 to 'E', 0b110110 to 'F', 0b101011 to 'G',
        0b100101 to 'H', 0b101100 to 'I', 0b111010 to 'J', 0b111110 to 'K', 0b101001 to 'L', 0b100111 to 'M', 0b100110 to 'N',
        0b100011 to 'O', 0b101101 to 'P', 0b111101 to 'Q', 0b101010 to 'R', 0b110100 to 'S', 0b100001 to 'T', 0b111100 to 'U',
        0b101111 to 'V', 0b111001 to 'W', 0b110111 to 'X', 0b110101 to 'Y', 0b110001 to 'Z',
        0b001101 to '0', 0b011101 to '1', 0b011001 to '2', 0b010000 to '3', 0b001010 to '4', 0b000001 to '5', 0b010101 to '6',
        0b011100 to '7', 0b001100 to '8', 0b000011 to '9', 0b011000 to '-', 0b010111 to '/', 0b100100 to ' ',
    )
    private val BAUDOT_CODE: Map<Char, Int> = BAUDOT.entries.associate { it.value to it.key }
    fun baudot(b: IntArray, from: Int, chars: Int) = (0 until chars).map { BAUDOT[field(b, from + 6 * it, from + 6 * it + 5).toInt()] ?: '?' }.joinToString("")

    // ------------------------------------------------------------------ the message

    data class Message(
        /** The message bits from bit 1 (bit sync) to 112 or 144. */
        val bits: IntArray,
        val long: Boolean,
        val selfTest: Boolean,
        val userProtocol: Boolean,
        /** Country code (MID, 224 = Spain). */
        val country: Int,
        val protocolCode: Int,
        val protocolName: String,
        /** EPIRB, ELT, PLB, SSAS or "" if the protocol does not say. */
        val beaconType: String,
        /** What identifies the beacon: MMSI digits, serial, 24-bit address, call sign, registration... */
        val id: String,
        val lat: Double?, val lon: Double?,
        /** "internal" / "external" GNSS, or null. */
        val positionSource: String?,
        val homing121: Boolean?,
        val bch1Ok: Boolean,
        /** Null in a short message. */
        val bch2Ok: Boolean?,
        /** The 15-hex identification (bits 26-85, position fields at their defaults). */
        val hexId: String,
        /** Bits 25 to the end in hexadecimal (30 hex for a long message, 22 for a short one). */
        val hex: String,
        val test: Boolean,
        val timeMs: Long = 0,
        /** Carrier offset from the tuned frequency (Hz) and level over the noise (dB), from the receiver. */
        val offsetHz: Double = 0.0, val snrDb: Double = 0.0,
    ) {
        val valid get() = bch1Ok && bch2Ok != false
        /** A real alert: valid, not a self-test, not a test protocol. */
        val distress get() = valid && !selfTest && !test
        val key get() = hexId
        override fun equals(other: Any?) = other is Message && other.bits.contentEquals(bits) && other.timeMs == timeMs
        override fun hashCode() = bits.contentHashCode() * 31 + timeMs.hashCode()

        fun summary(): String = listOfNotNull(
            (if (selfTest) "SELF-TEST · " else if (test) "TEST · " else "") + (beaconType.ifEmpty { "Beacon" }) + " " + hexId,
            protocolName, "country $country", id.takeIf { it.isNotBlank() },
            if (lat != null && lon != null) String.format(java.util.Locale.US, "%.4f %.4f", lat, lon) + (positionSource?.let { " ($it GNSS)" } ?: "") else "no position",
            homing121?.let { if (it) "121.5 MHz homing" else null },
            if (!valid) "BCH ERROR" else null,
        ).joinToString(" · ")
    }

    private fun locationName(code: Int) = when (code) {
        0b0010 -> "Standard location, EPIRB (MMSI)"; 0b0011 -> "Standard location, ELT (24-bit address)"; 0b0100 -> "Standard location, ELT (serial)"
        0b0101 -> "Standard location, ELT (operator)"; 0b0110 -> "Standard location, EPIRB (serial)"; 0b0111 -> "Standard location, PLB (serial)"
        0b1100 -> "Standard location, ship security (SSAS)"; 0b1000 -> "National location, ELT"; 0b1001 -> "ELT(DT) location"
        0b1010 -> "National location, EPIRB"; 0b1011 -> "National location, PLB"; 0b1101 -> "RLS location"; 0b1110 -> "Standard location, test"
        0b1111 -> "National location, test"; else -> "Location protocol $code"
    }
    private fun locationType(code: Int) = when (code) {
        0b0010, 0b0110, 0b1010 -> "EPIRB"; 0b0011, 0b0100, 0b0101, 0b1000, 0b1001 -> "ELT"; 0b0111, 0b1011 -> "PLB"; 0b1100 -> "SSAS"; else -> ""
    }
    private fun userName(code: Int) = when (code) {
        0 -> "Orbitography"; 1 -> "User, aviation"; 2 -> "User, maritime"; 3 -> "User, serial"; 4 -> "User, national"; 6 -> "User, radio call sign"
        7 -> "User, test"; else -> "User protocol $code"
    }

    /** Reads a message ([bits] from bit 1, 112 or 144 of them). */
    fun parse(bits: IntArray, timeMs: Long = 0, offsetHz: Double = 0.0, snrDb: Double = 0.0): Message {
        val sync = field(bits, 16, 24).toInt()
        val selfTest = sync == SYNC_SELF_TEST
        val long = bit(bits, 25) == 1 && bits.size >= 144
        val user = bit(bits, 26) == 1
        val country = field(bits, 27, 36).toInt()
        val bch1Ok = parity(bits, 25, 85, BCH1, 21) == field(bits, 86, 106)
        val bch2Ok = if (long) parity(bits, 107, 132, BCH2, 12) == field(bits, 133, 144) else null
        var lat: Double? = null; var lon: Double? = null; var src: String? = null; var homing: Boolean? = null
        val code: Int; val name: String; var type = ""; var id = ""; var test = false
        // the 15-hex identification: bits 26-85, with the coarse position of the location protocols at its default value
        val hexBits = bits.copyOf(maxOf(bits.size, 144))
        fun setDefault(from: Int, pattern: String) { for (k in pattern.indices) hexBits[from - 1 + k] = pattern[k] - '0' }
        if (user) {
            code = field(bits, 37, 39).toInt()
            name = userName(code)
            test = code == 7
            when (code) {
                2 -> { id = baudot(bits, 40, 6).trim(); type = "EPIRB" }
                6 -> { id = baudot(bits, 40, 6).trim(); type = "EPIRB" }
                1 -> { id = baudot(bits, 40, 7).trim(); type = "ELT" }
                3 -> {
                    val t = field(bits, 40, 42).toInt()
                    type = when (t) { 0, 1, 3 -> "ELT"; 2, 4 -> "EPIRB"; 6 -> "PLB"; else -> "" }
                    id = "serial " + field(bits, 44, 63)
                }
                else -> id = ""
            }
            if (long) {
                src = if (bit(bits, 107) == 1) "internal" else "external"
                val la = field(bits, 109, 115) + field(bits, 116, 119) * 4 / 60.0
                val lo = field(bits, 121, 128) + field(bits, 129, 132) * 4 / 60.0
                if (la <= 90 && lo <= 180 && !(field(bits, 108, 119) == 0xFFFL && field(bits, 120, 132) == 0x1FFFL)) {
                    lat = if (bit(bits, 108) == 1) -la else la; lon = if (bit(bits, 120) == 1) -lo else lo
                }
            }
        } else {
            code = field(bits, 37, 40).toInt()
            name = locationName(code)
            type = locationType(code)
            test = code == 0b1110 || code == 0b1111
            val national = code in listOf(0b1000, 0b1010, 0b1011, 0b1111)
            if (national) {
                id = "national " + field(bits, 41, 58)
                val laQ = field(bits, 60, 66) + field(bits, 67, 71) * 2 / 60.0
                val loQ = field(bits, 73, 80) + field(bits, 81, 85) * 2 / 60.0
                var la = laQ; var lo = loQ
                val defaultPos = field(bits, 59, 71) == 0b0111111100000L && field(bits, 72, 85) == 0b01111111100000L
                if (long && bit(bits, 107) == 1 && bit(bits, 108) == 1 && bit(bits, 109) == 0) {
                    src = if (bit(bits, 110) == 1) "internal" else "external"
                    homing = bit(bits, 111) == 1
                    val dLa = (field(bits, 113, 114) * 60 + field(bits, 115, 118) * 4) / 3600.0
                    val dLo = (field(bits, 120, 121) * 60 + field(bits, 122, 125) * 4) / 3600.0
                    la += if (bit(bits, 112) == 1) dLa else -dLa
                    lo += if (bit(bits, 119) == 1) dLo else -dLo
                }
                if (!defaultPos && laQ <= 90 && loQ <= 180) { lat = if (bit(bits, 59) == 1) -la else la; lon = if (bit(bits, 72) == 1) -lo else lo }
                setDefault(59, "0111111100000"); setDefault(72, "01111111100000")
            } else {
                when (code) {
                    0b0010, 0b1100 -> id = String.format(java.util.Locale.US, "MMSI …%06d", field(bits, 41, 60)) + (field(bits, 61, 64).takeIf { it != 0L }?.let { " #$it" } ?: "")
                    0b0011 -> id = String.format(java.util.Locale.US, "aircraft %06X", field(bits, 41, 64))
                    0b0100, 0b0110, 0b0111 -> id = "TAC ${field(bits, 41, 50)} serial ${field(bits, 51, 64)}"
                    else -> id = String.format(java.util.Locale.US, "%06X", field(bits, 41, 64))
                }
                if (code != 0b1101 && code != 0b1001) {
                    val laQ = field(bits, 66, 74) / 4.0
                    val loQ = field(bits, 76, 85) / 4.0
                    var la = laQ; var lo = loQ
                    val defaultPos = field(bits, 65, 74) == 0b0111111111L && field(bits, 75, 85) == 0b01111111111L
                    if (long && field(bits, 107, 110) == 0b1101L) {
                        src = if (bit(bits, 111) == 1) "internal" else "external"
                        homing = bit(bits, 112) == 1
                        val dLa = (field(bits, 114, 118) * 60 + field(bits, 119, 122) * 4) / 3600.0
                        val dLo = (field(bits, 124, 128) * 60 + field(bits, 129, 132) * 4) / 3600.0
                        la += if (bit(bits, 113) == 1) dLa else -dLa
                        lo += if (bit(bits, 123) == 1) dLo else -dLo
                    }
                    if (!defaultPos && laQ <= 90 && loQ <= 180) { lat = if (bit(bits, 65) == 1) -la else la; lon = if (bit(bits, 75) == 1) -lo else lo }
                    setDefault(65, "0111111111"); setDefault(75, "01111111111")
                }
            }
        }
        fun hexOf(from: Int, to: Int): String {
            val sb = StringBuilder()
            var n = from
            while (n <= to) { val e = minOf(n + 3, to); var v = field(hexBits, n, e).toInt(); v = v shl (3 - (e - n)); sb.append("0123456789ABCDEF"[v]); n += 4 }
            return sb.toString()
        }
        val hexId = hexOf(26, 85)
        val all = run { val b = bits; val sb = StringBuilder(); var n = 25; val end = if (long) 144 else 112
            while (n <= end) { val e = minOf(n + 3, end); var v = field(b, n, e).toInt(); v = v shl (3 - (e - n)); sb.append("0123456789ABCDEF"[v]); n += 4 }; sb.toString() }
        return Message(bits.copyOf(if (long) 144 else 112), long, selfTest, user, country, code, name, type, id, lat, lon, src, homing, bch1Ok, bch2Ok,
            hexId, all, test, timeMs, offsetHz, snrDb)
    }

    // ------------------------------------------------------------------ building messages (tests)

    private fun put(b: IntArray, from: Int, to: Int, v: Long) { for (n in from..to) b[n - 1] = ((v shr (to - n)) and 1L).toInt() }

    private fun frame(selfTest: Boolean): IntArray {
        val b = IntArray(144)
        put(b, 1, 15, 0x7FFF); put(b, 16, 24, (if (selfTest) SYNC_SELF_TEST else SYNC_NORMAL).toLong())
        return b
    }
    private fun seal(b: IntArray, long: Boolean): IntArray {
        put(b, 86, 106, parity(b, 25, 85, BCH1, 21))
        if (long) put(b, 133, 144, parity(b, 107, 132, BCH2, 12))
        return if (long) b else b.copyOf(112)
    }

    /** Long standard-location EPIRB (MMSI) message: coarse position in 1/4°, the rest in the offsets of bits 113-132. */
    fun standardMmsi(country: Int, mmsi6: Int, lat: Double?, lon: Double?, beaconNo: Int = 0, selfTest: Boolean = false, internal: Boolean = true): IntArray {
        val b = frame(selfTest)
        put(b, 25, 25, 1); put(b, 26, 26, 0); put(b, 27, 36, country.toLong()); put(b, 37, 40, 0b0010)
        put(b, 41, 60, mmsi6.toLong()); put(b, 61, 64, beaconNo.toLong())
        put(b, 107, 110, 0b1101); put(b, 111, 111, if (internal) 1 else 0); put(b, 112, 112, 1)
        if (lat == null || lon == null) {
            put(b, 65, 74, 0b0111111111); put(b, 75, 85, 0b01111111111)
            put(b, 113, 122, 0b1000001111); put(b, 123, 132, 0b1000001111)
        } else {
            fun one(v: Double, flagBit: Int, from: Int, to: Int, offFrom: Int) {
                val a = abs(v); val q = (a * 4).toInt()
                val sec = ((a - q / 4.0) * 3600).roundToInt().coerceAtMost(14 * 60 + 56)
                put(b, flagBit, flagBit, if (v < 0) 1 else 0); put(b, from, to, q.toLong())
                put(b, offFrom, offFrom, 1); put(b, offFrom + 1, offFrom + 5, (sec / 60).toLong()); put(b, offFrom + 6, offFrom + 9, ((sec % 60) / 4).toLong())
            }
            one(lat, 65, 66, 74, 113); one(lon, 75, 76, 85, 123)
        }
        return seal(b, true)
    }

    /** Short or long maritime user message (6 trailing MMSI digits in modified Baudot); long = with user-location position. */
    fun userMaritime(country: Int, mmsi6: String, lat: Double?, lon: Double?, long: Boolean, selfTest: Boolean = false): IntArray {
        val b = frame(selfTest)
        put(b, 25, 25, if (long) 1 else 0); put(b, 26, 26, 1); put(b, 27, 36, country.toLong()); put(b, 37, 39, 0b010)
        val s = mmsi6.padStart(6, ' ').take(6)
        for (k in 0 until 6) put(b, 40 + 6 * k, 45 + 6 * k, (BAUDOT_CODE[s[k]] ?: 0b100100).toLong())
        put(b, 76, 81, BAUDOT_CODE['0']!!.toLong()); put(b, 84, 85, 0b01)
        if (long) {
            put(b, 107, 107, 1)
            if (lat == null || lon == null) { put(b, 108, 119, 0xFFF); put(b, 120, 132, 0x1FFF) } else {
                val la = abs(lat); val lo = abs(lon)
                put(b, 108, 108, if (lat < 0) 1 else 0); put(b, 109, 115, la.toInt().toLong()); put(b, 116, 119, (((la - la.toInt()) * 60) / 4).toInt().toLong())
                put(b, 120, 120, if (lon < 0) 1 else 0); put(b, 121, 128, lo.toInt().toLong()); put(b, 129, 132, (((lo - lo.toInt()) * 60) / 4).toInt().toLong())
            }
        }
        return seal(b, long)
    }

    /** The burst at [rate] complex samples: 160 ms of carrier, then the bits in biphase-L (1 = +1.1 rad then -1.1 rad). */
    fun modulate(bits: IntArray, rate: Int, offsetHz: Double, amp: Double = 1.0, lead: Double = 0.2): Pair<FloatArray, FloatArray> {
        val spb = rate / BAUD
        val carrier = (0.16 * rate).toInt()
        val total = carrier + (bits.size * spb).toInt()
        val pad = (lead * rate).toInt()
        val re = FloatArray(total + 2 * pad); val im = FloatArray(total + 2 * pad)
        var ph = 0.3
        val w = 2 * PI * offsetHz / rate
        for (n in 0 until total) {
            ph += w
            var m = 0.0
            if (n >= carrier) {
                val t = (n - carrier) / spb
                val k = t.toInt().coerceAtMost(bits.size - 1)
                val first = t - k < 0.5
                m = if ((bits[k] == 1) == first) 1.1 else -1.1
            }
            re[pad + n] = (amp * cos(ph + m)).toFloat(); im[pad + n] = (amp * sin(ph + m)).toFloat()
        }
        return re to im
    }

    // ------------------------------------------------------------------ the receiver

    /**
     * Complex baseband at [rate] (48 kS/s, the app receiver's channel tuned to [TUNE_HZ] with 30 kHz of band) in, messages out.
     * Each 1024-sample spectrum looks for a carrier 13 dB over its bin's noise in two spectra running; the burst around it is then
     * mixed to zero, filtered and decimated to 12 kS/s; the phase, less its trend and its 16-bit mean, gives the bits; the timing is
     * the offset where the 24 sync bits match best (either polarity), kept on the mid-bit transitions.
     */
    class Receiver(val rate: Int = 48_000, val spanHz: Double = 16_000.0) {
        var onMessage: (Message) -> Unit = {}
        var clock: () -> Long = { System.currentTimeMillis() }
        var bursts = 0; private set
        var decoded = 0; private set
        /** Last carrier seen: offset (Hz) and level over noise (dB). */
        var lastOffsetHz = 0.0; private set
        var lastSnrDb = 0.0; private set

        private val n = 1024
        private val ringLen = (1.4 * rate).toInt()
        private val ringRe = FloatArray(ringLen); private val ringIm = FloatArray(ringLen)
        private var written = 0L
        private val fre = DoubleArray(n); private val fim = DoubleArray(n)
        private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        private val floor = DoubleArray(n)
        private var fill = 0
        private var frames = 0
        private var lastBin = -9999
        private var hits = 0
        private var pendingAt = -1L            // sample index where the burst block ends (process then)
        private var pendingStart = 0L
        private var pendingBin = 0
        private var quietUntil = 0L

        fun feed(re: FloatArray, im: FloatArray, len: Int) {
            for (i in 0 until len) {
                val p = (written % ringLen).toInt()
                ringRe[p] = re[i]; ringIm[p] = im[i]
                written++
                fre[fill] = re[i] * win[fill]; fim[fill] = im[i] * win[fill]
                if (++fill == n) { fill = 0; spectrum() }
                if (pendingAt >= 0 && written >= pendingAt) { val s = pendingStart; val b = pendingBin; pendingAt = -1; burst(s, b) }
            }
        }

        private fun spectrum() {
            Modems.fft(fre, fim)
            frames++
            val span = (spanHz / (rate.toDouble() / n)).toInt()
            var best = 0; var bestR = 0.0
            for (k in -span..span) {
                val idx = (k + n) % n
                val pw = fre[idx] * fre[idx] + fim[idx] * fim[idx]
                val f = floor[idx]
                if (f > 0 && pw / f > bestR) { bestR = pw / f; best = k }
                // mean noise of the bin; a carrier (10 dB over it) does not raise it
                floor[idx] = if (f == 0.0) pw else if (frames < 20) f + (pw - f) * 0.2 else if (pw < 10 * f) f + (pw - f) * 0.05 else f
            }
            if (frames < 20 || written < quietUntil || pendingAt >= 0) return
            if (bestR > 25.0) {
                if (abs(best - lastBin) <= 1) hits++ else hits = 1
                lastBin = best
                if (hits == 2) {
                    bursts++
                    lastOffsetHz = best * rate.toDouble() / n
                    lastSnrDb = 10 * kotlin.math.log10(bestR)
                    // the carrier began at most ~3 spectra ago; the burst lasts 520 ms at most
                    pendingStart = written - 4L * n
                    pendingAt = pendingStart + (0.64 * rate).toLong()
                    pendingBin = best
                    quietUntil = pendingAt
                    hits = 0
                }
            } else hits = 0
        }

        private fun burst(start: Long, bin: Int) {
            val len = (written - start).toInt().coerceAtMost(ringLen)
            val s0 = written - len
            val xr = FloatArray(len); val xi = FloatArray(len)
            var ph = 0.0
            val f0 = bin * rate.toDouble() / n
            val w = -2 * PI * f0 / rate
            for (i in 0 until len) {
                val p = ((s0 + i) % ringLen).toInt()
                val c = cos(ph); val s = sin(ph); ph += w
                xr[i] = (ringRe[p] * c - ringIm[p] * s).toFloat(); xi[i] = (ringRe[p] * s + ringIm[p] * c).toFloat()
            }
            val decim = rate / 12_000
            val fir = Dsp.CFir(Dsp.lowPass(129, 1_800.0, rate.toDouble()), decim)
            val yr = FloatArray(len / decim + 2); val yi = FloatArray(len / decim + 2)
            val m = fir.process(xr, xi, len, yr, yi)
            decode(yr, yi, m, 12_000, f0)?.let { decoded++; onMessage(it) }
        }

        private fun decode(yr: FloatArray, yi: FloatArray, m: Int, r: Int, f0: Double): Message? {
            if (m < r / 2) return null
            // strong part of the burst (amplitude over half its peak)
            val amp = DoubleArray(m) { kotlin.math.hypot(yr[it].toDouble(), yi[it].toDouble()) }
            val peak = amp.sorted()[m * 9 / 10]
            var a = 0; while (a < m && amp[a] < peak / 2) a++
            var b = m - 1; while (b > a && amp[b] < peak / 2) b--
            if (b - a < r * 0.3) return null
            val spb = (r / BAUD).toInt()                 // 30
            val half = spb / 2
            a = (a - spb).coerceAtLeast(0); b = (b + spb).coerceAtMost(m - 1)
            // residual frequency over the strong part
            var sr = 0.0; var si = 0.0
            for (i in a + 1..b) { val pr = yr[i - 1].toDouble(); val pi = yi[i - 1].toDouble(); sr += yr[i] * pr + yi[i] * pi; si += yi[i] * pr - yr[i] * pi }
            val dw = atan2(si, sr)
            // the phase against the carrier: each sample against the mean phasor of 16 bits around it (biphase-L averages to the
            // carrier), so no unwrapping and no cycle slips
            val len = b - a + 1
            val zr = DoubleArray(len); val zi = DoubleArray(len)
            for (i in 0 until len) {
                val c0 = cos(-dw * i); val s0 = sin(-dw * i)
                val xr0 = yr[a + i].toDouble(); val xi0 = yi[a + i].toDouble()
                zr[i] = xr0 * c0 - xi0 * s0; zi[i] = xr0 * s0 + xi0 * c0
            }
            val pr = DoubleArray(len + 1); val pim = DoubleArray(len + 1)
            for (i in 0 until len) { pr[i + 1] = pr[i] + zr[i]; pim[i + 1] = pim[i] + zi[i] }
            val win = 16 * spb
            val d = DoubleArray(len) { i ->
                val lo = (i - win / 2).coerceAtLeast(0); val hi = (i + win / 2).coerceAtMost(len)
                val rr = pr[hi] - pr[lo]; val ri = pim[hi] - pim[lo]
                atan2(zi[i] * rr - zr[i] * ri, zr[i] * rr + zi[i] * ri)
            }
            val c = DoubleArray(d.size + 1)
            for (i in d.indices) c[i + 1] = c[i] + d[i]
            fun soft(t: Int): Double = if (t < 0 || t + spb > d.size) 0.0 else (c[t + half] - c[t]) - (c[t + spb] - c[t + half])
            // timing: the 24 preamble bits, normal or self-test, either polarity
            var bestT = -1; var bestScore = 0.0; var bestSign = 1.0
            for (sync in listOf(SYNC_NORMAL, SYNC_SELF_TEST)) {
                val pat = IntArray(24) { if (it < 15) 1 else (sync shr (23 - it)) and 1 }
                for (t in 0 until d.size - 112 * spb) {
                    var sc = 0.0
                    for (k in 0 until 24) sc += if (pat[k] == 1) soft(t + k * spb) else -soft(t + k * spb)
                    if (abs(sc) > abs(bestScore)) { bestScore = sc; bestT = t; bestSign = if (sc >= 0) 1.0 else -1.0 }
                }
            }
            if (bestT < 0) return null
            // the bits, following the mid-bit transitions
            val bits = IntArray(144)
            var t = bestT.toDouble()
            var count = 144
            for (k in 0 until 144) {
                val ti = t.roundToInt()
                if (ti + spb > d.size) { count = k; break }
                val s0 = soft(ti) * bestSign
                bits[k] = if (s0 > 0) 1 else 0
                val e = abs(soft(ti + 1)) - abs(soft(ti - 1))
                t += spb + (if (e > 0) 0.05 else if (e < 0) -0.05 else 0.0)
                if (k == 24 && bits[24] == 0) { count = 112 }
                if (k + 1 >= count) break
            }
            if (count < 112) return null
            val got = if (bits[24] == 1 && count >= 144) bits else bits.copyOf(112)
            val snr = lastSnrDb
            val msg = parse(got, clock(), f0 + dw * r / (2 * PI), snr)
            return msg
        }
    }
}
