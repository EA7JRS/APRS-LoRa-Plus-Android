// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// AIS receiver for the phone's RTL-SDR: the two marine channels (161.975 MHz "A" and 162.025 MHz "B") at once. The IQ
// (960 kS/s) is shifted so 162.000 MHz is at baseband, split into the two channels, FM-discriminated and GMSK-demodulated
// (NRZI, HDLC, X.25 FCS). Complete frames become !AIVDM sentences, sent to the tracker with "aisin" (it decodes and lists
// them). Listen only. The demodulator mirrors the tracker's AisCore::Demod (src/ais_core.cpp), tested with the same cases.
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object AisRx {
    const val BAUD = 9600
    const val CENTER_HZ = 162_000_000L          // between the two channels; tune the tuner to CENTER_HZ + Dsp.IF_OFFSET
    const val CHANNEL_HZ = 25_000.0             // A at -25 kHz, B at +25 kHz
    const val MAX_MSG_BYTES = 128

    /** CRC-16 X.25 (HDLC FCS): start 0xFFFF, poly 0x8408 reflected, final XOR 0xFFFF. */
    fun crcX25(d: ByteArray, n: Int): Int {
        var c = 0xFFFF
        for (i in 0 until n) {
            c = c xor (d[i].toInt() and 0xFF)
            for (k in 0 until 8) c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1
        }
        return c.inv() and 0xFFFF
    }

    private fun bitsU(d: ByteArray, nbits: Int, start: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) {
            val bit = start + i
            if (bit >= nbits) { v = v shl 1; continue }
            val b = (d[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1
            v = (v shl 1) or b.toLong()
        }
        return v
    }

    private fun bitsS(d: ByteArray, nbits: Int, start: Int, len: Int): Long {
        var v = bitsU(d, nbits, start, len)
        if (len < 64 && (v and (1L shl (len - 1))) != 0L) v = v or ((1L shl len) - 1L).inv()
        return v
    }

    private fun bitsText(d: ByteArray, nbits: Int, start: Int, nchars: Int): String {
        val sb = StringBuilder()
        for (i in 0 until nchars) {
            if (start + i * 6 + 6 > nbits) break
            val c = bitsU(d, nbits, start + i * 6, 6).toInt()
            sb.append((if (c < 32) c + 64 else c).toChar())
        }
        var s = sb.toString()
        val at = s.indexOf('@'); if (at >= 0) s = s.substring(0, at)    // '@' = end / padding
        return s.trim()
    }

    fun isDistress(mmsi: Long): Boolean = (mmsi / 1_000_000L).let { it == 970L || it == 972L || it == 974L }

    /**
     * Closest point of approach between own ship and a target, on a local flat plane (nautical miles). Returns (cpaNm, tcpaMin);
     * tcpaMin negative means the CPA is already past. Mirrors AisCore::cpa. Missing target course/speed are treated as 0.
     */
    fun cpa(oLat: Double, oLon: Double, oSogKn: Double, oCogDeg: Double, vLat: Double, vLon: Double, vSogKn: Double?, vCogDeg: Double?): Pair<Double, Double> {
        val d2r = PI / 180.0
        val rx = (vLon - oLon) * 60.0 * cos(oLat * d2r); val ry = (vLat - oLat) * 60.0
        val vs = if (vSogKn != null && vCogDeg != null) vSogKn else 0.0; val vc = (vCogDeg ?: 0.0) * d2r
        val oc = oCogDeg * d2r
        val vx = (vs * sin(vc) - oSogKn * sin(oc)) / 60.0; val vy = (vs * cos(vc) - oSogKn * cos(oc)) / 60.0
        val vv = vx * vx + vy * vy
        if (vv < 1e-9) return sqrt(rx * rx + ry * ry) to 0.0
        val tcpa = -(rx * vx + ry * vy) / vv
        val t = if (tcpa > 0) tcpa else 0.0
        val cx = rx + vx * t; val cy = ry + vy * t
        return sqrt(cx * cx + cy * cy) to tcpa
    }

    /** Merges a freshly decoded report into what we already have, keeping the last position/kinematics and static data. */
    fun mergeInto(old: AisVessel?, new: AisVessel): AisVessel {
        if (old == null) return new
        return new.copy(
            cls = if (new.cls != "?") new.cls else old.cls,
            name = new.name.ifBlank { old.name }, callsign = new.callsign.ifBlank { old.callsign },
            shipType = if (new.shipType != 0) new.shipType else old.shipType,
            status = if (new.status != 15) new.status else old.status,
            lat = new.lat ?: old.lat, lon = new.lon ?: old.lon, posAgeS = if (new.lat != null) 0 else old.posAgeS,
            sogKn = new.sogKn ?: old.sogKn, cogDeg = new.cogDeg ?: old.cogDeg, heading = new.heading ?: old.heading,
            destination = new.destination.ifBlank { old.destination }, lengthM = if (new.lengthM > 0) new.lengthM else old.lengthM,
            beamM = if (new.beamM > 0) new.beamM else old.beamM, draughtM = new.draughtM ?: old.draughtM, eta = new.eta.ifBlank { old.eta },
            imo = if (new.imo > 0) new.imo else old.imo, text = new.text.ifBlank { old.text }, channel = new.channel.ifBlank { old.channel },
            messages = old.messages + 1,
        )
    }

    private fun pos(lonRaw: Long, latRaw: Long): Pair<Double, Double>? {
        val lon = lonRaw / 600000.0; val lat = latRaw / 600000.0
        return if (kotlin.math.abs(lon) <= 180.0 && kotlin.math.abs(lat) <= 90.0 && !(lonRaw == 0L && latRaw == 0L)) lat to lon else null
    }

    /**
     * Decodes one AIS frame ([nbytes] data bytes) into a vessel, or null if the type is unknown or too short. Mirrors
     * AisCore::decode (tracker). Type 24 parts A/B and later updates are merged by [Ais.merge] / [mergeInto].
     */
    fun decodeVessel(d: ByteArray, nbytes: Int, channel: Char, nowMs: Long): AisVessel? {
        val n = nbytes * 8
        if (n < 38) return null
        val type = bitsU(d, n, 0, 6).toInt()
        val mmsi = bitsU(d, n, 8, 30)
        var cls = "?"; var name = ""; var call = ""; var dest = ""; var text = ""
        var shipType = 0; var status = 15
        var lat: Double? = null; var lon: Double? = null
        var sog: Double? = null; var cog: Double? = null; var hdg: Int? = null
        var len = 0; var beam = 0; var draught: Double? = null; var imo = 0L; var alt: Int? = null; var eta = ""
        fun sogCog(s: Long, c: Long, h: Long) { if (s != 1023L) sog = s / 10.0; if (c < 3600L) cog = c / 10.0; if (h < 360L) hdg = h.toInt() }
        fun dims(at: Int) { val bow = bitsU(d, n, at, 9); val stern = bitsU(d, n, at + 9, 9); val port = bitsU(d, n, at + 18, 6); val star = bitsU(d, n, at + 24, 6); len = (bow + stern).toInt(); beam = (port + star).toInt() }
        when (type) {
            1, 2, 3 -> {
                if (n < 168) return null; cls = "A"
                status = bitsU(d, n, 38, 4).toInt()
                sogCog(bitsU(d, n, 50, 10), bitsU(d, n, 116, 12), bitsU(d, n, 128, 9))
                pos(bitsS(d, n, 61, 28), bitsS(d, n, 89, 27))?.let { lat = it.first; lon = it.second }
            }
            4 -> { if (n < 168) return null; cls = "S"; pos(bitsS(d, n, 79, 28), bitsS(d, n, 107, 27))?.let { lat = it.first; lon = it.second } }
            5 -> {
                if (n < 420) return null; cls = "A"
                imo = bitsU(d, n, 40, 30); call = bitsText(d, n, 70, 7); name = bitsText(d, n, 112, 20)
                shipType = bitsU(d, n, 232, 8).toInt(); dims(240)
                val mo = bitsU(d, n, 274, 4); val da = bitsU(d, n, 278, 5); val ho = bitsU(d, n, 283, 5); val mi = bitsU(d, n, 288, 6)
                if (mo != 0L && da != 0L) eta = "%02d-%02d %02d:%02d".format(mo, da, ho, mi)
                draught = bitsU(d, n, 294, 8) / 10.0; if (draught!! <= 0.0) draught = null
                dest = bitsText(d, n, 302, 20)
            }
            9 -> {
                if (n < 168) return null; cls = "R"
                val a = bitsU(d, n, 38, 12); if (a != 4095L) alt = a.toInt()
                val s = bitsU(d, n, 50, 10); if (s != 1023L) sog = s.toDouble()
                val c = bitsU(d, n, 116, 12); if (c < 3600L) cog = c / 10.0
                pos(bitsS(d, n, 61, 28), bitsS(d, n, 89, 27))?.let { lat = it.first; lon = it.second }
            }
            14 -> { if (n < 46) return null; cls = if (isDistress(mmsi)) "T" else "?"; text = bitsText(d, n, 40, (n - 40) / 6) }
            18, 19 -> {
                if (n < 168 || (type == 19 && n < 306)) return null; cls = "B"
                sogCog(bitsU(d, n, 46, 10), bitsU(d, n, 112, 12), bitsU(d, n, 124, 9))
                pos(bitsS(d, n, 57, 28), bitsS(d, n, 85, 27))?.let { lat = it.first; lon = it.second }
                if (type == 19) { name = bitsText(d, n, 143, 20); shipType = bitsU(d, n, 263, 8).toInt(); dims(271) }
            }
            21 -> {
                if (n < 268) return null; cls = "N"
                name = bitsText(d, n, 43, 20)
                if (n >= 278) name += bitsText(d, n, 272, minOf(14, (n - 272) / 6))
                pos(bitsS(d, n, 164, 28), bitsS(d, n, 192, 27))?.let { lat = it.first; lon = it.second }
                dims(219)
            }
            24 -> {
                if (n < 160) return null; cls = "B"
                when (bitsU(d, n, 38, 2).toInt()) {
                    0 -> name = bitsText(d, n, 40, 20)
                    1 -> { if (n < 162) return null; shipType = bitsU(d, n, 40, 8).toInt(); call = bitsText(d, n, 90, 7); if (mmsi / 10_000_000L != 98L) dims(132) }
                    else -> return null
                }
            }
            else -> return null
        }
        if (isDistress(mmsi)) cls = "T"
        return AisVessel(
            mmsi, cls, name, call, shipType, status, lat, lon, if (lat != null) 0 else null, sog, cog, hdg, 0,
            null, null, null, null, isDistress(mmsi), dest, len, beam, draught, eta, imo, alt, text, channel.toString(), 0, nowMs,
        )
    }

    private fun armor(v: Long): Char = (if (v < 40) v + 48 else v + 56).toInt().toChar()

    fun nmeaChecksum(s: String): Int {
        var x = 0
        for (ch in s) x = x xor ch.code
        return x and 0xFF
    }

    /** Builds the !AIVDM sentences for a frame (its [nbits] data bits in [d]); "" if it does not fit 1..9 sentences. */
    fun toNmea(d: ByteArray, nbits: Int, channel: Char, seqId: Int = 0): String {
        val nchars = (nbits + 5) / 6
        val fill = nchars * 6 - nbits
        val per = 60
        val total = (nchars + per - 1) / per
        if (total < 1 || total > 9) return ""
        val sb = StringBuilder()
        for (k in 0 until total) {
            val sq = if (total > 1) ((if (seqId < 0) 0 else seqId) % 10).toString() else ""
            val body = StringBuilder()
            body.append("AIVDM,").append(total).append(',').append(k + 1).append(',').append(sq).append(',').append(channel).append(',')
            val from = k * per
            val to = if (from + per < nchars) from + per else nchars
            for (c in from until to) body.append(armor(bitsU(d, nbits, c * 6, 6)))
            body.append(',').append(if (k == total - 1) fill else 0)
            val cs = nmeaChecksum(body.toString())
            sb.append('!').append(body).append('*').append("%02X".format(cs)).append("\r\n")
        }
        return sb.toString()
    }

    /** Test transmitter: message bytes -> NRZI level sequence (training, flags, bit stuffing, FCS), before Gaussian shaping. */
    fun frameBits(msg: ByteArray, nbytes: Int): IntArray {
        val raw = ArrayList<Int>(nbytes * 10 + 128)
        for (i in 0 until 24) raw.add(i and 1)                       // training 0101...
        for (i in 0 until 8) raw.add((0x7E ushr i) and 1)           // start flag
        val fr = ByteArray(nbytes + 2)
        System.arraycopy(msg, 0, fr, 0, nbytes)
        val fcs = crcX25(msg, nbytes)
        fr[nbytes] = (fcs and 0xFF).toByte(); fr[nbytes + 1] = ((fcs ushr 8) and 0xFF).toByte()
        var ones = 0
        for (i in 0 until nbytes + 2) {
            for (k in 0 until 8) {
                val b = (fr[i].toInt() ushr k) and 1
                raw.add(b)
                if (b == 1) { if (++ones == 5) { raw.add(0); ones = 0 } } else ones = 0
            }
        }
        for (i in 0 until 8) raw.add((0x7E ushr i) and 1)           // end flag
        for (i in 0 until 8) raw.add(0)                              // tail
        var lvl = 0
        val out = IntArray(raw.size)
        for (i in raw.indices) { if (raw[i] == 0) lvl = lvl xor 1; out[i] = lvl }   // NRZI: 0 = level change, 1 = no change
        return out
    }

    /** Called with a demodulated frame: data bytes and how many. */
    fun interface FrameSink { fun onFrame(data: ByteArray, nbytes: Int) }

    /**
     * GMSK bit recovery + HDLC from an FM-discriminated channel at [fs] Hz (38400..96000). Port of AisCore::Demod.
     * Feed one discriminator sample at a time; complete, CRC-checked frames go to the sink.
     */
    class Demod(fs: Float) {
        private val fsv = fs
        private val lp: FloatArray
        private val lpLen: Int
        private val hist: FloatArray
        private var hpos = 0
        private var dcAvg = 0f
        private var peak = 0f
        private var valley = 0f
        private val agcFast = 0.08f
        private val agcSlow: Float
        private var prevDemod = 0f
        private var pll = 0
        private val pllStep: Int
        private val pllStepU: Long
        private var prevData = 0
        private var prevBit = 0
        private var lockedBits = 0
        private var shift = 0
        private var inFrame = false
        private var ones = 0
        private val buf = ByteArray(MAX_MSG_BYTES + 4)
        private var nbuf = 0
        private var cur = 0
        private var curBits = 0
        var frames = 0; private set
        var crcErrors = 0; private set

        init {
            var len = (1.5f * fsv / BAUD).roundToInt() or 1
            if (len > 63) len = 63
            lpLen = len
            val fc = 0.6f * BAUD; val mid = (len - 1) * 0.5f
            lp = FloatArray(len)
            var sum = 0f
            for (i in 0 until len) {
                val k = i - mid
                val h = if (kotlin.math.abs(k) < 1e-6f) 2f * fc / fsv else (sin(2.0 * PI * fc * k / fsv) / (PI * k)).toFloat()
                lp[i] = h * sin(PI * (i + 0.5) / len).toFloat()
                sum += lp[i]
            }
            for (i in 0 until len) lp[i] /= sum
            hist = FloatArray(len * 2)
            pllStepU = (BAUD.toLong() shl 32) / fsv.toLong()
            pllStep = pllStepU.toInt()
            agcSlow = 0.0001f * 48000f / fsv
        }

        fun reset() {
            java.util.Arrays.fill(hist, 0f); hpos = 0
            dcAvg = 0f; peak = 0f; valley = 0f; prevDemod = 0f
            pll = 0; prevData = 0; prevBit = 0; lockedBits = 0; shift = 0
            inFrame = false; ones = 0; nbuf = 0; cur = 0; curBits = 0
        }

        private fun endFrame(sink: FrameSink) {
            if (curBits == 7 && nbuf >= 4) {
                val n = nbuf - 2
                val fcs = (buf[n].toInt() and 0xFF) or ((buf[n + 1].toInt() and 0xFF) shl 8)
                if (crcX25(buf, n) == fcs) { frames++; sink.onFrame(buf, n) } else crcErrors++
            }
            nbuf = 0; cur = 0; curBits = 0
        }

        private fun hdlcBit(b: Int, sink: FrameSink) {
            shift = (shift shl 1) or b
            if ((shift and 0xFF) == 0x7E) {                         // flag
                if (inFrame && nbuf > 0) endFrame(sink)
                inFrame = true; ones = 0; nbuf = 0; cur = 0; curBits = 0
                return
            }
            if ((shift and 0x7F) == 0x7F) { inFrame = false; ones = 0; return }   // seven ones: abort
            if (!inFrame) return
            if (b == 1) ones++ else { if (ones == 5) { ones = 0; return }; ones = 0 }
            cur = (cur ushr 1) or (if (b == 1) 0x80 else 0)
            if (++curBits == 8) {
                if (nbuf < buf.size) buf[nbuf++] = cur.toByte() else inFrame = false
                cur = 0; curBits = 0
            }
        }

        fun feed(xIn: Float, sink: FrameSink) {
            var x = xIn
            dcAvg += (x - dcAvg) * 0.0005f
            x -= dcAvg
            hpos = if (hpos == 0) lpLen - 1 else hpos - 1
            hist[hpos] = x; hist[hpos + lpLen] = x
            var y = 0f
            for (i in 0 until lpLen) y += lp[i] * hist[hpos + 1 + i]
            peak = if (y >= peak) y * agcFast + peak * (1 - agcFast) else y * agcSlow + peak * (1 - agcSlow)
            valley = if (y <= valley) y * agcFast + valley * (1 - agcFast) else y * agcSlow + valley * (1 - agcSlow)
            val demod = if (peak > valley) (y - 0.5f * (peak + valley)) / (peak - valley) else 0f
            val data = if (demod > 0) 1 else 0

            val prev = pll
            pll += pllStep                                          // wraps like the C uint32 accumulator
            if (pll < 0 && prev > 0) {
                val after = ((pll.toLong() and 0xFFFFFFFFL) - 0x80000000L).toFloat() / pllStepU.toFloat()
                val atCross = demod - (demod - prevDemod) * after
                val lvl = if (atCross > 0) 1 else 0
                val b = if (lvl == prevBit) 1 else 0                // NRZI: no change = 1, change = 0
                prevBit = lvl
                hdlcBit(b, sink)
                if ((shift and 0xFF) == 0x7E || (shift and 0xFFFF) == 0x5555 || (shift and 0xFFFF) == 0xAAAA) lockedBits = 300
                else if (lockedBits > 0) lockedBits--
            }
            if (data != prevData) {
                var t0 = if (prevDemod != demod) prevDemod / (prevDemod - demod) else 1f
                if (t0 < 0) t0 = 0f; if (t0 > 1) t0 = 1f
                val back = (1.0 - t0) * pllStepU.toDouble()
                var nudged = (pll.toDouble() - back) * (if (lockedBits > 0) 0.85 else 0.65) + back
                if (nudged > 2147483647.0) nudged -= 4294967296.0
                if (nudged < -2147483648.0) nudged += 4294967296.0
                pll = nudged.toInt()
                prevData = data
            }
            prevDemod = demod
        }
    }

    /**
     * Front-end: 8-bit unsigned IQ at 960 kS/s (tuner at [CENTER_HZ] + Dsp.IF_OFFSET) -> both channels demodulated.
     * [onSentence] gets each complete frame as one or more !AIVDM lines, with its channel ('A' / 'B').
     */
    class Receiver(private val onSentence: (String) -> Unit, private val onVessel: ((AisVessel) -> Unit)? = null) {
        private val mainNco = Dsp.Nco(Dsp.INPUT_RATE.toDouble()).apply { freqHz = Dsp.IF_OFFSET.toDouble() }
        private val toWide = Dsp.CFir(Dsp.lowPass(41, 48_000.0, Dsp.INPUT_RATE.toDouble()), 10)   // 960 -> 96 kS/s
        private val chan = arrayOf(Channel('A', -CHANNEL_HZ), Channel('B', +CHANNEL_HZ))

        private var bRe = FloatArray(0); private var bIm = FloatArray(0)
        private var wRe = FloatArray(0); private var wIm = FloatArray(0)
        private var centerSet = Long.MIN_VALUE

        /** Sets the main NCO from the actual tuner frequency, so [CENTER_HZ] lands at baseband (the tuner sits IF_OFFSET away). */
        fun center(tunerHz: Long) {
            if (tunerHz == centerSet) return
            centerSet = tunerHz
            mainNco.freqHz = (tunerHz - CENTER_HZ).toDouble()
        }

        private inner class Channel(val name: Char, offsetHz: Double) {
            val nco = Dsp.Nco(96_000.0).apply { freqHz = -offsetHz }   // bring this channel to 0
            val fir = Dsp.CFir(Dsp.lowPass(63, 6_000.0, 96_000.0), 2)  // channel filter, 96 -> 48 kS/s (5 samples/symbol)
            val demod = Demod(48_000f)
            var re = FloatArray(0); var im = FloatArray(0)
            var cr = FloatArray(0); var ci = FloatArray(0)
            var lastRe = 1f; var lastIm = 0f
            val sink = FrameSink { data, nbytes ->
                val s = toNmea(data, nbytes * 8, name)
                if (s.isNotEmpty()) onSentence(s)
                onVessel?.let { cb -> decodeVessel(data, nbytes, name, System.currentTimeMillis())?.let(cb) }
            }
            fun process(wide: FloatArray, wideIm: FloatArray, n: Int) {
                if (re.size < n) { re = FloatArray(n); im = FloatArray(n) }
                System.arraycopy(wide, 0, re, 0, n); System.arraycopy(wideIm, 0, im, 0, n)
                nco.mix(re, im, n)
                if (cr.size < n / 2 + 2) { cr = FloatArray(n / 2 + 2); ci = FloatArray(n / 2 + 2) }
                val m = fir.process(re, im, n, cr, ci)
                for (i in 0 until m) {
                    val r = cr[i]; val ii = ci[i]
                    val d = atan2((ii * lastRe - r * lastIm).toDouble(), (r * lastRe + ii * lastIm).toDouble()).toFloat()
                    lastRe = r; lastIm = ii
                    demod.feed(d, sink)
                }
            }
            val frames: Int get() = demod.frames
        }

        /** Returns the total frames decoded so far (both channels), for a simple status. */
        val frames: Int get() = chan.sumOf { it.frames }

        fun process(iq: ByteArray, len: Int) {
            val n = len / 2
            if (bRe.size < n) { bRe = FloatArray(n); bIm = FloatArray(n) }
            for (i in 0 until n) {
                bRe[i] = ((iq[2 * i].toInt() and 0xFF) - 127.5f) / 127.5f
                bIm[i] = ((iq[2 * i + 1].toInt() and 0xFF) - 127.5f) / 127.5f
            }
            mainNco.mix(bRe, bIm, n)
            if (wRe.size < n / 10 + 2) { wRe = FloatArray(n / 10 + 2); wIm = FloatArray(n / 10 + 2) }
            val m = toWide.process(bRe, bIm, n, wRe, wIm)
            for (c in chan) c.process(wRe, wIm, m)
        }
    }
}
