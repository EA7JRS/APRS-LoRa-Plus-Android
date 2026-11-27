/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2-FSK demodulator and byte framer for SSDV received with the phone's SDR (the tracker's SSDV-FSK mode; SSDV over LoRa cannot
 * be demodulated with an RTL-SDR). It takes real audio samples (e.g. the 12 kHz receiver output), measures the mark and space
 * tone energy over one bit, slices, recovers the bit clock from transitions and assembles bytes. Bytes are scanned for the
 * SSDV packet start (sync 0x55 followed by a packet-type byte) and collected into 256-byte packets for the native decoder.
 *
 * Bit/byte order follows the on-air SSDV stream: bytes are MSB-first, NRZ (mark = 1). Baud, tone spacing and polarity are
 * configurable because they depend on the tracker's FSK settings; they are tuned against a real signal.
 */
class SsdvFsk(
    private val sampleRate: Int = 12000,
    private val baud: Double = 2000.0,
    markHz: Double = 2000.0,
    spaceHz: Double = 1000.0,
    private val invert: Boolean = false,
) {
    private val samplesPerBit = (sampleRate / baud)
    // one-bit sliding Goertzel-style correlators for the two tones
    private val win = maxOf(4, samplesPerBit.toInt())
    private val buf = FloatArray(win)
    private var bp = 0
    private var filled = 0
    private val markCos = DoubleArray(win) { cos(2.0 * Math.PI * markHz * it / sampleRate) }
    private val markSin = DoubleArray(win) { sin(2.0 * Math.PI * markHz * it / sampleRate) }
    private val spaceCos = DoubleArray(win) { cos(2.0 * Math.PI * spaceHz * it / sampleRate) }
    private val spaceSin = DoubleArray(win) { sin(2.0 * Math.PI * spaceHz * it / sampleRate) }

    private var phase = 0.0                      // bit-clock phase in samples
    private var lastSoft = 0.0
    private var lastBit = 0

    // packet framing: a bit-level shift register to find the sync word, then byte-aligned collection of a 256-byte packet
    private var bitReg = 0                         // last 16 bits (MSB-first)
    private var bitAcc = 0
    private var bitCnt = 0
    private val pkt = ByteArray(Ssdv.PKT_SIZE)
    private var pktLen = -1                        // -1 = searching for the sync word

    /** Feed audio; returns any complete 256-byte SSDV packets found. */
    fun feed(x: FloatArray, n: Int): List<ByteArray> {
        var out: ArrayList<ByteArray>? = null
        for (i in 0 until n) {
            buf[bp] = x[i]; bp = (bp + 1) % win; if (filled < win) filled++
            // correlate the last `win` samples against both tones
            var mc = 0.0; var ms = 0.0; var sc = 0.0; var ss = 0.0
            var idx = bp
            for (k in 0 until win) {
                val v = buf[idx].toDouble()
                mc += v * markCos[k]; ms += v * markSin[k]
                sc += v * spaceCos[k]; ss += v * spaceSin[k]
                idx = (idx + 1) % win
            }
            val mark = mc * mc + ms * ms
            val space = sc * sc + ss * ss
            var soft = mark - space
            if (invert) soft = -soft

            phase += 1.0
            // clock recovery: nudge the phase towards transitions (zero crossings of the soft signal)
            if ((soft >= 0) != (lastSoft >= 0)) {
                if (phase > samplesPerBit / 2) phase -= samplesPerBit else { /* keep */ }
            }
            lastSoft = soft
            if (phase >= samplesPerBit) {
                phase -= samplesPerBit
                val bit = if (soft >= 0) 1 else 0
                lastBit = bit
                val p = pushBit(bit)
                if (p != null) { (out ?: ArrayList<ByteArray>().also { out = it }).add(p) }
            }
        }
        return out ?: emptyList()
    }

    private fun pushBit(bit: Int): ByteArray? {
        if (pktLen < 0) {
            // bit-level sync-word search: 0x55 followed by a known packet type, at any bit offset
            bitReg = ((bitReg shl 1) or bit) and 0xFFFF
            if (bitReg == ((Ssdv.SYNC shl 8) or Ssdv.TYPE_FEC) || bitReg == ((Ssdv.SYNC shl 8) or Ssdv.TYPE_NOFEC)) {
                pkt[0] = Ssdv.SYNC.toByte(); pkt[1] = (bitReg and 0xFF).toByte()
                pktLen = 2; bitAcc = 0; bitCnt = 0                     // subsequent bytes are byte-aligned from here
            }
            return null
        }
        bitAcc = ((bitAcc shl 1) or bit) and 0xFF                     // MSB-first
        bitCnt++
        if (bitCnt < 8) return null
        bitCnt = 0
        pkt[pktLen++] = bitAcc.toByte()
        if (pktLen >= Ssdv.PKT_SIZE) {
            val done = pkt.copyOf()
            pktLen = -1; bitReg = 0
            return done
        }
        return null
    }

    /** Resets the byte/packet framing (not the tone filters). */
    fun resetFraming() { bitAcc = 0; bitCnt = 0; pktLen = -1; bitReg = 0 }

    companion object {
        /** 2-FSK modulation of a byte stream, MSB-first, used by the self-test (and as a reference modulator). */
        fun modulate(bytes: ByteArray, sampleRate: Int = 12000, baud: Double = 2000.0,
                     markHz: Double = 2000.0, spaceHz: Double = 1000.0): FloatArray {
            val spb = sampleRate / baud
            val total = (bytes.size * 8 * spb).toInt() + spb.toInt()
            val out = FloatArray(total)
            var ph = 0.0
            var s = 0
            for (by in bytes) {
                for (k in 7 downTo 0) {
                    val bit = (by.toInt() ushr k) and 1
                    val f = if (bit == 1) markHz else spaceHz
                    val end = s + spb.toInt()
                    while (s < end && s < total) { ph += 2.0 * Math.PI * f / sampleRate; out[s] = sin(ph).toFloat(); s++ }
                }
            }
            return out
        }
    }
}
