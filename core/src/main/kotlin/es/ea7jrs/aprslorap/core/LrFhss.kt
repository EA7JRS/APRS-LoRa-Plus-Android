/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * LR-FHSS as the tracker sends it (SX1262 / SX1268 through RadioLib): the frame format, written as the inverse of what
 * RadioLib's `SX126x_LR_FHSS.cpp` builds (MIT; adapted there from Semtech's SWDM001 driver, Clear BSD), so that the app can
 * receive the tracker's LR-FHSS beacon with an SDR. Here only bits: header (5 bytes with CRC-8, rate 1/2 tail-biting
 * code, interleaver, sync word), payload (whitening, CRC-16, rate 1/3 code with puncturing, interleaver, 48-bit
 * fragments with 2 guard bits), the hop sequence and the beacon text. The radio side (finding the bursts, GMSK) is apart.
 *
 * One symbol is 1 / 488.28125 s. A packet is [headers] header bursts of 114 symbols (the first one 115) and then payload
 * fragments of up to 50 symbols, each burst on its own frequency.
 */
object LrFhss {
    const val SYMBOL_RATE = 488.28125                       // 500 kHz / 1024
    const val HEADER_BITS = 114
    const val FRAG_BITS = 48
    const val BLOCK_BITS = 50                               // 2 guard bits + 48
    const val PLL_STEP_HZ = 32_000_000.0 / (1 shl 25)       // 0.95367... Hz
    /** RadioLib's sync word for the SX126x, the one the tracker uses. */
    val SYNC_WORD = intArrayOf(0x12, 0xAD, 0x10, 0x1B)
    /** Occupied bandwidth in kHz by index (the "bw" field of the header). */
    val BW_KHZ = doubleArrayOf(39.06, 85.94, 136.72, 183.59, 335.94, 386.72, 722.66, 773.44, 1523.4, 1574.2)
    private val CHANNELS = intArrayOf(80, 176, 280, 376, 688, 792, 1480, 1584, 3120, 3224)
    val CR_NAMES = arrayOf("5/6", "2/3", "1/2", "1/3")

    // ------------------------------------------------------------------ convolutional codes (RadioLib's tables)

    private val TABLE_1_3 = longArrayOf(
        0x07347043, 0x61521625, 0x16256152, 0x70430734, 0x43703407, 0x25165261, 0x52612516, 0x34074370,
        0x70430734, 0x16256152, 0x61521625, 0x07347043, 0x34074370, 0x52612516, 0x25165261, 0x43703407)
    private val TABLE_1_2 = longArrayOf(0x03122130, 0x21300312, 0x30211203, 0x12033021)

    /** Output symbol (2 or 3 bits, first bit = most significant) of the encoder in [state] for the input [bit]. */
    private fun codeOut(table: LongArray, state: Int, bit: Int): Int =
        ((table[state / 4] shr ((3 - state % 4) * 8 + (1 - bit) * 4)) and 0x0F).toInt()

    private val OUT2 = Array(16) { s -> IntArray(2) { b -> codeOut(TABLE_1_2, s, b) } }
    private val OUT3 = Array(64) { s -> IntArray(2) { b -> codeOut(TABLE_1_3, s, b) } }

    private fun encode(bits: IntArray, rate: Int, startState: Int = 0): Pair<IntArray, Int> {
        val out = IntArray(bits.size * rate)
        val tab = if (rate == 2) OUT2 else OUT3
        val mod = if (rate == 2) 16 else 64
        var st = startState
        for (i in bits.indices) {
            val o = tab[st][bits[i]]
            for (k in 0 until rate) out[i * rate + k] = (o shr (rate - 1 - k)) and 1
            st = (st * 2 + bits[i]) % mod
        }
        return out to st
    }

    private val PUNCTURE = intArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0)
    private fun punctureLen(cr: Int) = when (cr) { 0 -> 15; 1 -> 6; 2 -> 3; else -> 0 }

    // ------------------------------------------------------------------ CRCs and whitening

    fun crc8(b: IntArray, n: Int = b.size): Int {            // poly 0x2F, start 0xFF, no final XOR
        var c = 0xFF
        for (i in 0 until n) { c = c xor b[i]; repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x2F) and 0xFF else (c shl 1) and 0xFF } }
        return c
    }
    fun crc16(b: IntArray, n: Int = b.size): Int {           // poly 0x755B, start 0xFFFF
        var c = 0xFFFF
        for (i in 0 until n) { c = c xor (b[i] shl 8); repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x755B) and 0xFFFF else (c shl 1) and 0xFFFF } }
        return c
    }
    /** Whitening of the payload (the same call takes it off, with [undo]): XOR with an LFSR and the two nibbles swapped. */
    fun whiten(data: IntArray, undo: Boolean = false): IntArray {
        var lfsr = 0xFF
        return IntArray(data.size) { i ->
            val r = if (undo) { val s = ((data[i] and 0x0F) shl 4) or ((data[i] and 0xF0) shr 4); s xor lfsr }
                    else { val u = data[i] xor lfsr; ((u and 0x0F) shl 4) or ((u and 0xF0) shr 4) }
            lfsr = ((lfsr shl 1) or (((lfsr shr 7) xor (lfsr shr 5) xor (lfsr shr 4) xor (lfsr shr 3)) and 1)) and 0xFF
            r and 0xFF
        }
    }

    private fun bitsOf(bytes: IntArray, nBits: Int = bytes.size * 8) = IntArray(nBits) { (bytes[it / 8] shr (7 - it % 8)) and 1 }
    private fun bytesOf(bits: IntArray, nBytes: Int = bits.size / 8) = IntArray(nBytes) { i -> var v = 0; for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]; v }

    // ------------------------------------------------------------------ header

    /** What a header says. [index]: 0 in the last header burst, counting up towards the first. */
    data class Header(val payloadLen: Int, val cr: Int, val narrowGrid: Boolean, val hopping: Boolean, val bw: Int, val hopSeqId: Int, val index: Int) {
        val bwKhz: Double get() = BW_KHZ.getOrElse(bw) { 0.0 }
        /** Coded payload bits on the air, and the number of payload fragments. */
        val codedBits: Int get() = codedBits(payloadLen, cr)
        val fragments: Int get() = (codedBits + FRAG_BITS - 1) / FRAG_BITS
        /** Symbols of fragment [i] (guard bits included). */
        fun fragmentSymbols(i: Int): Int = 2 + minOf(FRAG_BITS, codedBits - i * FRAG_BITS)
    }

    fun codedBits(payloadLen: Int, cr: Int): Int {
        val n = (payloadLen + 2) * 8 + 6
        return when (cr) { 0 -> (n * 6 + 4) / 5; 1 -> n * 3 / 2; 2 -> n * 2; else -> n * 3 }
    }

    private val HDR_INTERLEAVER = intArrayOf(
        0, 18, 36, 54, 72, 4, 22, 40, 58, 76, 8, 26, 44, 62, 12, 30, 48, 66, 16, 34, 52, 70, 1, 19, 37, 55, 73, 5, 23, 41, 59, 77,
        9, 27, 45, 63, 13, 31, 49, 67, 17, 35, 53, 71, 2, 20, 38, 56, 74, 6, 24, 42, 60, 78, 10, 28, 46, 64, 14, 32, 50, 68, 3, 21,
        39, 57, 75, 7, 25, 43, 61, 79, 11, 29, 47, 65, 15, 33, 51, 69)

    private fun headerBytes(h: Header): IntArray {
        val r = IntArray(5)
        r[0] = h.payloadLen and 0xFF
        r[1] = (h.cr shl 3) or ((if (h.narrowGrid) 1 else 0) shl 2) or ((if (h.hopping) 1 else 0) shl 1) or (h.bw shr 3)
        r[2] = ((h.bw and 7) shl 5) or (h.hopSeqId shr 4)
        r[3] = ((h.hopSeqId and 0x0F) shl 4) or ((h.index and 3) shl 2)
        r[4] = crc8(r, 4)
        return r
    }

    /** The 114 bits of a header burst: 2 guard bits, 40 coded bits, the 32 of the sync word and 40 coded bits. */
    fun headerBits(h: Header, sync: IntArray = SYNC_WORD): IntArray {
        val raw = bitsOf(headerBytes(h))
        val first = encode(raw, 2)                              // tail-biting: the code is run twice, the second pass starts
        val coded = encode(raw, 2, first.second).first          // in the state the first one ended in
        val out = IntArray(HEADER_BITS)
        for (j in 0 until 40) { out[2 + j] = coded[HDR_INTERLEAVER[j]]; out[2 + 40 + 32 + j] = coded[HDR_INTERLEAVER[40 + j]] }
        val s = bitsOf(sync)
        for (j in 0 until 32) out[2 + 40 + j] = s[j]
        return out
    }

    /** Where the sync word sits in a header burst, in symbols from its start. */
    const val SYNC_AT = 42

    /**
     * Header from the soft bits of a burst (114 values, positive = 1; only the 80 coded ones are used). Null if the CRC
     * does not pass. Several bursts of the same packet can be added up before (their index bits differ: see [combinable]).
     */
    fun decodeHeader(soft: FloatArray): Header? {
        if (soft.size < HEADER_BITS) return null
        val coded = FloatArray(80)
        for (j in 0 until 40) { coded[HDR_INTERLEAVER[j]] = soft[2 + j]; coded[HDR_INTERLEAVER[40 + j]] = soft[2 + 40 + 32 + j] }
        // tail-biting: the encoder starts and ends in the state made by the last 4 bits; try every state and keep the best
        var best: IntArray? = null
        var bestM = Float.NEGATIVE_INFINITY
        for (s0 in 0 until 16) {
            val (bits, m) = viterbi(coded, 2, 16, s0, s0) ?: continue
            if (m > bestM) { bestM = m; best = bits }
        }
        val b = bytesOf(best ?: return null)
        if (crc8(b, 4) != b[4]) return null
        val bw = ((b[1] and 1) shl 3) or (b[2] shr 5)
        if (bw >= BW_KHZ.size) return null
        return Header(b[0], (b[1] shr 3) and 3, (b[1] shr 2) and 1 == 1, (b[1] shr 1) and 1 == 1, bw, ((b[2] and 0x1F) shl 4) or (b[3] shr 4), (b[3] shr 2) and 3)
    }

    /**
     * Viterbi over [soft] coded bits (positive = 1, 0 = unknown) for the code of rate 1 / [rate] with [states] states,
     * from [start] to [end] (-1 = any). Returns the data bits and the metric of the path.
     */
    private fun viterbi(soft: FloatArray, rate: Int, states: Int, start: Int, end: Int): Pair<IntArray, Float>? {
        val n = soft.size / rate
        val tab = if (rate == 2) OUT2 else OUT3
        val neg = -1e30f
        var cur = FloatArray(states) { if (start < 0 || it == start) 0f else neg }
        var nxt = FloatArray(states)
        val from = Array(n) { ByteArray(states) }                // predecessor: 0 or 1 = which of the two
        val half = states / 2
        for (i in 0 until n) {
            java.util.Arrays.fill(nxt, neg)
            for (s in 0 until states) {
                val m = cur[s]
                if (m <= neg) continue
                for (bit in 0..1) {
                    val o = tab[s][bit]
                    var bm = 0f
                    for (k in 0 until rate) { val v = soft[i * rate + k]; bm += if ((o shr (rate - 1 - k)) and 1 == 1) v else -v }
                    val t = (s * 2 + bit) % states
                    if (m + bm > nxt[t]) { nxt[t] = m + bm; from[i][t] = (if (s >= half) 1 else 0).toByte() }
                }
            }
            val x = cur; cur = nxt; nxt = x
        }
        var st = end
        if (st < 0) { st = 0; for (s in 1 until states) if (cur[s] > cur[st]) st = s }
        val metric = cur[st]
        if (metric <= neg) return null
        val bits = IntArray(n)
        for (i in n - 1 downTo 0) { bits[i] = st and 1; st = (st shr 1) + (if (from[i][st].toInt() == 1) half else 0) }
        return bits to metric
    }

    // ------------------------------------------------------------------ payload

    /** Order in which the coded bits go on the air: on-air position -> position in the coded stream. */
    private fun interleaveOrder(nBits: Int): IntArray {
        var step = 0
        while (step * step < nBits) step++
        val stepV = step shr 1
        step = step shl 1
        val order = IntArray(nBits)
        var pos = 0; var st = 0; var stInit = 0
        for (i in 0 until nBits) {
            order[i] = pos
            pos += step
            if (pos >= nBits) {
                st += stepV
                if (st >= step) { stInit++; st = stInit }
                pos = st
            }
        }
        return order
    }

    /** The payload on the air: one array per fragment, each with its 2 guard bits (zeros) and up to 48 coded bits. */
    fun payloadBlocks(payload: ByteArray, cr: Int): List<IntArray> {
        val w = whiten(IntArray(payload.size) { payload[it].toInt() and 0xFF })
        val crc = crc16(w)
        val bytes = w + intArrayOf(crc shr 8, crc and 0xFF, 0)
        var coded = encode(bitsOf(bytes, (payload.size + 2) * 8 + 6), 3).first
        val pl = punctureLen(cr)
        if (pl > 0) coded = coded.filterIndexed { i, _ -> PUNCTURE[i % pl] == 1 }.toIntArray()
        val order = interleaveOrder(coded.size)
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < coded.size) {
            val n = minOf(FRAG_BITS, coded.size - i)
            out.add(IntArray(2 + n) { if (it < 2) 0 else coded[order[i + it - 2]] })
            i += n
        }
        return out
    }

    /**
     * Payload from the soft coded bits in the order they came on the air, guard bits already removed
     * ([Header.codedBits] values, positive = 1). Null if the CRC does not pass.
     */
    fun decodePayload(soft: FloatArray, h: Header): ByteArray? {
        val nCoded = h.codedBits
        if (soft.size < nCoded || h.payloadLen <= 0) return null
        val order = interleaveOrder(nCoded)
        val coded = FloatArray(nCoded)
        for (i in 0 until nCoded) coded[order[i]] = soft[i]
        val nData = (h.payloadLen + 2) * 8 + 6
        val mother = FloatArray(nData * 3)                       // punctured bits stay at 0 = unknown
        val pl = punctureLen(h.cr)
        if (pl == 0) System.arraycopy(coded, 0, mother, 0, minOf(coded.size, mother.size))
        else { var k = 0; for (i in mother.indices) if (PUNCTURE[i % pl] == 1 && k < nCoded) mother[i] = coded[k++] }
        val (bits, _) = viterbi(mother, 3, 64, 0, 0) ?: return null   // six zero bits at the end bring the code back to state 0
        val b = bytesOf(bits, h.payloadLen + 2)
        if (crc16(b, h.payloadLen) != ((b[h.payloadLen] shl 8) or b[h.payloadLen + 1])) return null
        val plain = whiten(b.copyOf(h.payloadLen), undo = true)
        return ByteArray(h.payloadLen) { plain[it].toByte() }
    }

    // ------------------------------------------------------------------ hop sequence

    /**
     * Frequency of every burst of a packet, in Hz from the centre frequency: [headers] header bursts and then the
     * payload fragments. Null when that bandwidth / grid / sequence does not exist.
     */
    fun hopOffsetsHz(narrowGrid: Boolean, bw: Int, hopSeqId: Int, headers: Int, fragments: Int): DoubleArray? {
        if (bw !in CHANNELS.indices || headers !in 1..4) return null
        val perGrid = if (narrowGrid) 8 else 52
        val nGrid = CHANNELS[bw] / perGrid
        var state = 6
        val poly: Int; val seed: Int
        when (nGrid) {
            10, 22, 28, 30, 35, 47, 60, 62 -> {
                if (hopSeqId >= 384) return null
                if (nGrid >= 60) state = 56
                poly = intArrayOf(33, 45, 48, 51, 54, 57)[hopSeqId shr 6]; seed = hopSeqId and 0x3F
            }
            86, 99 -> { poly = intArrayOf(65, 68, 71, 72)[(hopSeqId shr 7) and 3]; seed = hopSeqId and 0x7F }
            185, 198 -> { poly = intArrayOf(142, 149)[(hopSeqId shr 8) and 1]; seed = hopSeqId and 0xFF }
            390, 403 -> { poly = 264; seed = hopSeqId and 0x1FF }
            else -> return null
        }
        fun step(): Int {
            var hop: Int
            var guard = 0
            do {
                val lsb = state and 1
                state = state shr 1
                if (lsb == 1) state = state xor poly
                hop = seed
                if (hop != state) hop = hop xor state
                if (++guard > 100_000) return 1
            } while (hop > nGrid)
            return hop
        }
        repeat(4 - headers) { step() }
        val gridOffset = (1 + nGrid % 2) * (perGrid / 2)
        val gridSteps = if (narrowGrid) 4096 else 26624
        return DoubleArray(headers + fragments) { i ->
            var table = step() - 1
            if (table >= nGrid shr 1) table -= nGrid
            var pll = -table * gridSteps - gridOffset * 512
            if (i < headers && (headers - i) % 2 == 0) pll += 256
            pll * PLL_STEP_HZ
        }
    }

    /** Symbols of burst [i] of a packet with [headers] header bursts (the first one carries one more symbol). */
    fun burstSymbols(h: Header, headers: Int, i: Int): Int = when {
        i == 0 -> HEADER_BITS + 1
        i < headers -> HEADER_BITS
        else -> h.fragmentSymbols(i - headers)
    }

    // ------------------------------------------------------------------ the tracker's beacon

    /** "PB1 EA7JRS IM67NG 14": callsign, locator of 4, 6 or 8 characters and power in dBm. */
    data class Beacon(val callsign: String, val locator: String, val powerDbm: Int)

    fun parseBeacon(payload: ByteArray): Beacon? {
        val t = String(payload, Charsets.US_ASCII).trim().split(' ').filter { it.isNotEmpty() }
        if (t.size != 4 || t[0] != "PB1") return null
        val call = t[1].uppercase()
        val loc = t[2].uppercase()
        if (call.length !in 3..9 || !call.all { it.isLetterOrDigit() || it == '-' || it == '/' }) return null
        if (loc.length !in intArrayOf(4, 6, 8) || RotorFollow.locatorToLatLon(loc) == null) return null
        val dbm = t[3].toIntOrNull() ?: return null
        if (dbm !in -20..40) return null
        return Beacon(call, loc, dbm)
    }

    // ------------------------------------------------------------------ radio side

    /** Sample rate the receiver works at: 128 samples per symbol. */
    const val SAMPLE_RATE = 62500
    private const val SPS = 128

    /** Hop of every burst of a packet seen from a header with index [index]: its own offset first, then the payload fragments. */
    private fun hopsFrom(h: Header): DoubleArray? {
        val all = hopOffsetsHz(h.narrowGrid, h.bw, h.hopSeqId, 4, h.fragments) ?: return null
        return DoubleArray(1 + h.fragments) { if (it == 0) all[3 - h.index] else all[3 + it] }
    }

    /**
     * A whole packet as I / Q at [SAMPLE_RATE] (interleaved I, Q), for tests and as a test signal: GMSK of index 0.5,
     * every burst on its hop frequency around [centreHz]. [lead] and [tail] are silent samples before and after.
     */
    fun modulate(payload: ByteArray, cr: Int, narrowGrid: Boolean, bw: Int, hopSeqId: Int, headers: Int, centreHz: Double = 0.0,
                 lead: Int = 4000, tail: Int = 4000, amplitude: Float = 1f): FloatArray? {
        val blocks = payloadBlocks(payload, cr)
        val hops = hopOffsetsHz(narrowGrid, bw, hopSeqId, headers, blocks.size) ?: return null
        val bursts = ArrayList<IntArray>()
        for (i in 0 until headers) bursts.add(headerBits(Header(payload.size, cr, narrowGrid, true, bw, hopSeqId, headers - 1 - i)))
        bursts.addAll(blocks)
        val nSym = bursts.sumOf { it.size }
        val dev = FloatArray(nSym * SPS)                       // deviation in units of the nominal one, before shaping
        val hop = DoubleArray(nSym * SPS)
        var p = 0
        for ((bi, b) in bursts.withIndex()) for (bit in b) { for (k in 0 until SPS) { dev[p] = if (bit == 1) 1f else -1f; hop[p] = hops[bi]; p++ } }
        val g = FloatArray(SPS + 1) { val t = (it - SPS / 2) / SPS.toDouble(); Math.exp(-t * t * 2 * Math.PI * Math.PI / Math.log(2.0)).toFloat() }   // Gaussian, BT = 1
        val gs = g.sum()
        val out = FloatArray((lead + nSym * SPS + tail) * 2)
        var ph = 0.0
        for (n in 0 until nSym * SPS) {
            var a = 0f
            for (k in g.indices) { val j = n + k - SPS / 2; a += g[k] * dev[j.coerceIn(0, dev.size - 1)] }
            ph += 2 * Math.PI * (a / gs * SYMBOL_RATE / 4 + hop[n] + centreHz) / SAMPLE_RATE
            if (ph > Math.PI) ph -= 2 * Math.PI else if (ph < -Math.PI) ph += 2 * Math.PI
            out[(lead + n) * 2] = amplitude * Math.cos(ph).toFloat(); out[(lead + n) * 2 + 1] = amplitude * Math.sin(ph).toFloat()
        }
        return out
    }

    /** A packet received. [centreHz]: carrier of the transmitter from the tuned frequency; [snrDb]: rough, in the symbol bandwidth. */
    class Packet(val header: Header, val payload: ByteArray, val centreHz: Double, val snrDb: Double, val sample: Long, val fragmentsLost: Int) {
        val beacon: Beacon? get() = parseBeacon(payload)
        val text: String get() = String(payload, Charsets.ISO_8859_1).map { if (it.code in 32..126) it else '.' }.joinToString("")
    }

    /**
     * Receiver: I / Q at [SAMPLE_RATE] centred on the nominal frequency in, packets out. It looks for the sync word of the
     * header bursts in every 244 Hz bin of the band (differential detection, so the carrier error does not matter), reads
     * the header, and with it knows where and when every payload fragment is.
     */
    class Receiver(private val onPacket: (Packet) -> Unit) {
        private val N = 1 shl 19                                   // 8.4 s of samples
        private val re = FloatArray(N); private val im = FloatArray(N)
        private var count = 0L
        private val F = 256                                        // FFT: 128 samples with a Hann window, padded
        private val win = FloatArray(SPS) { (0.5 - 0.5 * Math.cos(2 * Math.PI * (it + 0.5) / SPS)).toFloat() }
        private val cosT = FloatArray(F / 2) { Math.cos(2 * Math.PI * it / F).toFloat() }
        private val sinT = FloatArray(F / 2) { Math.sin(2 * Math.PI * it / F).toFloat() }
        private val fr = FloatArray(F); private val fi = FloatArray(F)
        private val H = 128                                        // frames of history (4 per symbol)
        private val xr = Array(H) { FloatArray(F) }; private val xi = Array(H) { FloatArray(F) }
        private val dr = Array(H) { FloatArray(F) }; private val di = Array(H) { FloatArray(F) }; private val da = Array(H) { FloatArray(F) }
        private var frame = 0L
        private val accR = FloatArray(F); private val accI = FloatArray(F); private val accA = FloatArray(F)
        private val sync = IntArray(32) { (SYNC_WORD[it / 8] shr (7 - it % 8)) and 1 }
        private class Cand(var first: Long, var frame: Long, var bin: Int, var metric: Float)
        private val cands = ArrayList<Cand>()
        private class Pending(val h: Header, val hdrStart: Long, val f0: Double, val hops: DoubleArray, val ref: Float, val snrDb: Double) {
            val payloadStart: Long get() = hdrStart + HEADER_BITS.toLong() * SPS * (h.index + 1)
            val end: Long get() = payloadStart + ((h.fragments - 1L) * BLOCK_BITS + h.fragmentSymbols(h.fragments - 1) + 1) * SPS
        }
        private val pending = ArrayList<Pending>()
        private val done = ArrayList<Long>()
        /** Headers read and packets that failed their CRC, for the screen. */
        var headersSeen = 0; private set
        var payloadErrors = 0; private set
        var threshold = 0.6f

        fun feed(iq: FloatArray, n: Int = iq.size / 2) {
            for (k in 0 until n) feed(iq[2 * k], iq[2 * k + 1])
        }
        fun feed(i: Float, q: Float) {
            val p = (count and (N - 1).toLong()).toInt()
            re[p] = i; im[p] = q
            count++
            if (count >= SPS && count % 32 == 0L) onFrame()
        }

        private fun fft() {
            var j = 0
            for (i in 1 until F) { var bit = F shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
                if (i < j) { val a = fr[i]; fr[i] = fr[j]; fr[j] = a; val b = fi[i]; fi[i] = fi[j]; fi[j] = b } }
            var len = 2
            while (len <= F) {
                val step = F / len
                var i = 0
                while (i < F) {
                    for (k in 0 until len / 2) {
                        val c = cosT[k * step]; val s = -sinT[k * step]
                        val a = i + k; val b = a + len / 2
                        val tr = fr[b] * c - fi[b] * s; val ti = fr[b] * s + fi[b] * c
                        fr[b] = fr[a] - tr; fi[b] = fi[a] - ti; fr[a] += tr; fi[a] += ti
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        private fun onFrame() {
            val start = count - SPS
            for (k in 0 until SPS) { val p = ((start + k) and (N - 1).toLong()).toInt(); fr[k] = re[p] * win[k]; fi[k] = im[p] * win[k] }
            java.util.Arrays.fill(fr, SPS, F, 0f); java.util.Arrays.fill(fi, SPS, F, 0f)
            fft()
            val h = (frame % H).toInt(); val h4 = ((frame + H - 4) % H).toInt()
            val cr = xr[h]; val ci = xi[h]; val pr = xr[h4]; val pi = xi[h4]
            for (b in 0 until F) {
                // phase of bin b at the centre of the window, not at its start: turn by b * 2 pi * start / F
                val t = ((b.toLong() * (start % F)) % F).toInt()
                val c = cosT1(t); val s = sinT1(t)
                val r = fr[b] * c + fi[b] * s; val q = fi[b] * c - fr[b] * s
                cr[b] = r; ci[b] = q
                val a = r * pr[b] + q * pi[b]; val e = q * pr[b] - r * pi[b]
                dr[h][b] = a; di[h][b] = e; da[h][b] = Math.sqrt((a * a + e * e).toDouble()).toFloat()
            }
            if (frame >= H) {
                java.util.Arrays.fill(accR, 0f); java.util.Arrays.fill(accI, 0f); java.util.Arrays.fill(accA, 0f)
                for (k in 0 until 32) {
                    val g = ((frame + H - 4L * (31 - k)) % H).toInt()
                    val gr = dr[g]; val gi = di[g]; val ga = da[g]
                    // bit 1 = +90 degrees: multiply by -j; bit 0: by +j
                    if (sync[k] == 1) for (b in 0 until F) { accR[b] += gi[b]; accI[b] -= gr[b]; accA[b] += ga[b] }
                    else for (b in 0 until F) { accR[b] -= gi[b]; accI[b] += gr[b]; accA[b] += ga[b] }
                }
                val t2 = threshold * threshold
                for (b in 0 until F) {
                    val sa = accA[b]
                    val score = accR[b] * accR[b] + accI[b] * accI[b]
                    if (sa > 0f && score > t2 * sa * sa) {
                        // strongest bin and frame of each burst (a strong one also shows, weaker, in bins far from its own)
                        val c = cands.firstOrNull { Math.abs(it.frame - frame) <= 8 && Math.abs(it.bin - b) <= 6 }
                        if (c == null) cands.add(Cand(frame, frame, b, score)) else if (score > c.metric) { c.frame = frame; c.bin = b; c.metric = score }
                    }
                }
            }
            frame++
            if (cands.isNotEmpty()) {
                val it = cands.iterator()
                while (it.hasNext()) {
                    val c = it.next()
                    // the last window of the sync word ended at (c.frame * 32 + 128); its centre is the end of the sync word
                    val syncEnd = c.frame * 32 + SPS - SPS / 2
                    val hdrStart = syncEnd - (SYNC_AT + 32L) * SPS
                    if (count < hdrStart + (HEADER_BITS + 1L) * SPS) continue
                    it.remove()
                    if (cands.none { o -> o !== c && Math.abs(o.frame - c.frame) <= 8 && o.metric > c.metric * 50 }) tryHeader(hdrStart, binHz(c.bin))
                }
            }
            if (pending.isNotEmpty()) {
                val it = pending.iterator()
                while (it.hasNext()) { val p = it.next(); if (count >= p.end) { it.remove(); finish(p) } }
            }
        }
        private fun cosT1(t: Int): Float = if (t < F / 2) cosT[t] else -cosT[t - F / 2]
        private fun sinT1(t: Int): Float = if (t < F / 2) sinT[t] else -sinT[t - F / 2]
        private fun binHz(b: Int): Double = (if (b < F / 2) b else b - F) * SAMPLE_RATE.toDouble() / F

        /** Phasors at the symbol boundaries 0..[symbols] of a burst that starts at sample [start], at [freq]. Null if the samples are gone. */
        private fun boundaries(start: Long, symbols: Int, freq: Double): Pair<FloatArray, FloatArray>? {
            val a = start - SPS / 2
            val b = start + symbols.toLong() * SPS + SPS / 2
            if (a < 0 || a < count - N || b > count) return null
            val halves = (symbols + 1) * 2
            val hr = FloatArray(halves); val hi = FloatArray(halves)
            val w = -2 * Math.PI * freq / SAMPLE_RATE
            var pos = a
            for (m in 0 until halves) {
                var sr = 0.0; var si = 0.0
                val ph0 = w * ((pos - start) % SAMPLE_RATE)
                val cs = Math.cos(w); val sn = Math.sin(w)
                var c = Math.cos(ph0); var s = Math.sin(ph0)
                for (k in 0 until SPS / 2) {
                    val p = (pos and (N - 1).toLong()).toInt()
                    sr += re[p] * c - im[p] * s; si += re[p] * s + im[p] * c
                    val c2 = c * cs - s * sn; s = c * sn + s * cs; c = c2
                    pos++
                }
                hr[m] = sr.toFloat(); hi[m] = si.toFloat()
            }
            val br = FloatArray(symbols + 1) { hr[2 * it] + hr[2 * it + 1] }
            val bi = FloatArray(symbols + 1) { hi[2 * it] + hi[2 * it + 1] }
            return br to bi
        }
        /** d[k] = boundary k+1 times the conjugate of boundary k: +90 degrees for a 1, -90 for a 0. */
        private fun diffs(b: Pair<FloatArray, FloatArray>): Pair<FloatArray, FloatArray> {
            val n = b.first.size - 1
            return FloatArray(n) { b.first[it + 1] * b.first[it] + b.second[it + 1] * b.second[it] } to
                   FloatArray(n) { b.second[it + 1] * b.first[it] - b.first[it + 1] * b.second[it] }
        }

        private fun tryHeader(hdrStart0: Long, coarseHz: Double) {
            run {
                // timing: the offset where the sync word correlates best
                var best = 0.0; var bestOff = 0; var bestAng = 0.0
                var off = -56
                while (off <= 56) {
                    val b = boundaries(hdrStart0 + off + SYNC_AT.toLong() * SPS, 32, coarseHz)
                    if (b != null) {
                        val d = diffs(b)
                        var sr = 0.0; var si = 0.0
                        for (k in 0 until 32) if (sync[k] == 1) { sr += d.second[k]; si -= d.first[k] } else { sr -= d.second[k]; si += d.first[k] }
                        val m = sr * sr + si * si
                        if (m > best) { best = m; bestOff = off; bestAng = Math.atan2(si, sr) }
                    }
                    off += 8
                }
                if (best <= 0.0) return
                val start = hdrStart0 + bestOff
                var f = coarseHz + bestAng / (2 * Math.PI) * SYMBOL_RATE
                var d = diffs(boundaries(start, HEADER_BITS, f) ?: return)
                var ref = 0f
                for (k in 2 until HEADER_BITS) ref += Math.hypot(d.first[k].toDouble(), d.second[k].toDouble()).toFloat()
                ref /= (HEADER_BITS - 2)
                if (ref <= 0f) return
                val h = decodeHeader(FloatArray(HEADER_BITS) { d.second[it] / ref }) ?: return
                if (!h.hopping || h.payloadLen == 0) return
                val hops = hopsFrom(h) ?: return
                // with all the bits known: the carrier, and how clean the burst is
                val bits = headerBits(h)
                var sr = 0.0; var si = 0.0
                for (k in 2 until HEADER_BITS) if (bits[k] == 1) { sr += d.second[k]; si -= d.first[k] } else { sr -= d.second[k]; si += d.first[k] }
                f += Math.atan2(si, sr) / (2 * Math.PI) * SYMBOL_RATE
                d = diffs(boundaries(start, HEADER_BITS, f) ?: return)
                var mean = 0.0; var sq = 0.0
                for (k in 2 until HEADER_BITS) { val v = (if (bits[k] == 1) d.second[k] else -d.second[k]).toDouble(); mean += v; sq += v * v }
                mean /= (HEADER_BITS - 2); sq /= (HEADER_BITS - 2)
                val noise = (sq - mean * mean).coerceAtLeast(mean * mean * 1e-4)
                val snr = 10 * Math.log10(mean * mean / noise)
                headersSeen++
                val p = Pending(h, start, f - hops[0], hops, mean.toFloat(), snr)
                if (p.end - start > N - 4 * SPS) return
                if (done.any { Math.abs(it - p.payloadStart) < 2 * SPS } || pending.any { Math.abs(it.payloadStart - p.payloadStart) < 2 * SPS }) return
                pending.add(p)
            }
        }

        private fun finish(p: Pending) {
            val soft = FloatArray(p.h.codedBits)
            val level = DoubleArray(p.h.fragments)                      // 0 = samples gone
            var o = 0
            for (i in 0 until p.h.fragments) {
                val syms = p.h.fragmentSymbols(i)
                val b = boundaries(p.payloadStart + i.toLong() * BLOCK_BITS * SPS, syms, p.f0 + p.hops[1 + i])
                if (b == null) { o += syms - 2; continue }
                val d = diffs(b)
                // what is left of the carrier error in this fragment, from the bits as they look
                var sr = 0.0; var si = 0.0; var pw = 0.0
                for (k in 2 until syms) { if (d.second[k] >= 0) { sr += d.second[k]; si -= d.first[k] } else { sr -= d.second[k]; si += d.first[k] }; pw += Math.hypot(d.first[k].toDouble(), d.second[k].toDouble()) }
                val ang = Math.atan2(si, sr).coerceIn(-0.6, 0.6)
                val c = Math.cos(ang).toFloat(); val s = Math.sin(ang).toFloat()
                level[i] = pw / (syms - 2)
                for (k in 2 until syms) soft[o++] = (d.second[k] * c - d.first[k] * s) / p.ref
            }
            val data = decodePayload(soft, p.h)
            if (data == null) { payloadErrors++; return }
            // fragments much weaker than the usual one of this packet (out of the band, faded, covered); not against the header:
            // a receiver with AGC is still turning its gain down during the first header
            val median = level.sorted()[level.size / 2]
            val lost = level.count { it < median * 0.2 }
            done.add(p.payloadStart); if (done.size > 16) done.removeAt(0)
            onPacket(Packet(p.h, data, p.f0, p.snrDb, p.hdrStart, lost))
        }
    }

    /**
     * Any I / Q sample rate to the [SAMPLE_RATE] of the receiver: windowed-sinc low-pass (flat to 22 kHz) read at the output
     * instants. [shiftHz]: where the wanted centre sits in the input (a receiver tuned to one side, away from its DC spike).
     */
    class Resampler(private val inRate: Double, private val shiftHz: Double = 0.0, private val out: (Float, Float) -> Unit) {
        private val step = inRate / SAMPLE_RATE
        private val half = Math.ceil(8 * maxOf(step, 1.0)).toInt()               // half width of the filter, in input samples
        private val PH = 64
        private val table = FloatArray((2 * half + 1) * PH + 1).also { t ->
            val fc = minOf(0.46 * SAMPLE_RATE, 0.46 * inRate) / inRate            // cut-off, cycles per input sample
            for (i in t.indices) {
                val x = i.toDouble() / PH - half
                val s = if (Math.abs(x) < 1e-9) 2 * fc else Math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                val w = if (Math.abs(x) >= half) 0.0 else 0.42 + 0.5 * Math.cos(Math.PI * x / half) + 0.08 * Math.cos(2 * Math.PI * x / half)
                t[i] = (s * w).toFloat()
            }
        }
        private val L = Integer.highestOneBit(2 * half + 2) shl 1
        private val br = FloatArray(L); private val bi = FloatArray(L)
        private var n = 0L                                                       // input samples so far
        private var next = half.toDouble()                                       // input instant of the next output sample
        private val w = -2 * Math.PI * shiftHz / inRate
        private var c = 1.0; private var s = 0.0
        private val dc = Math.cos(w); private val ds = Math.sin(w)

        private val direct = shiftHz == 0.0 && Math.abs(inRate - SAMPLE_RATE) < 0.5     // nothing to do: the whole band goes through untouched

        fun feed(i: Float, q: Float) {
            if (direct) { out(i, q); return }
            val p = (n and (L - 1).toLong()).toInt()
            if (shiftHz == 0.0) { br[p] = i; bi[p] = q }
            else {
                br[p] = (i * c - q * s).toFloat(); bi[p] = (i * s + q * c).toFloat()
                val c2 = c * dc - s * ds; s = c * ds + s * dc; c = c2
                if (n and 1023L == 0L) { val g = 1.0 / Math.hypot(c, s); c *= g; s *= g }
            }
            n++
            while (next + half < n) {
                val k0 = Math.ceil(next - half).toLong()
                var sr = 0f; var si = 0f
                var k = k0
                val end = Math.floor(next + half).toLong()
                while (k <= end) {
                    val t = table[((k - next + half) * PH + 0.5).toInt()]
                    val j = (k and (L - 1).toLong()).toInt()
                    sr += br[j] * t; si += bi[j] * t
                    k++
                }
                out(sr, si)
                next += step
            }
        }
        fun feed(iq: FloatArray, frames: Int) { for (k in 0 until frames) feed(iq[2 * k], iq[2 * k + 1]) }
        /** rtl_tcp bytes (unsigned, I then Q). */
        fun feedU8(b: ByteArray, bytes: Int) { var k = 0; while (k + 1 < bytes) { feed(((b[k].toInt() and 0xFF) - 127.5f) / 127.5f, ((b[k + 1].toInt() and 0xFF) - 127.5f) / 127.5f); k += 2 } }
    }
}
