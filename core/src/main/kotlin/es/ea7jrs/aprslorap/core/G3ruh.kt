/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Packet FSK G3RUH (AX.25 at 9600 baud, also 4800 / 19200): the tracker's APRS FSK mode, cubesats, 9k6 packet.
 * FM discriminator samples in -> slicer -> clock recovery (DPLL) -> G3RUH descrambler (x^17 + x^12 + 1) -> NRZI -> HDLC
 * (flags, bit unstuffing) -> FCS (CRC-16/X.25) -> AX.25. Same conventions as the firmware (ax25_g3ruh.cpp). FX.25 frames carry
 * a normal AX.25 frame inside, so they are decoded too; IL2P is not.
 */
object G3ruh {
    /** CRC-16/X.25 (the AX.25 FCS) of [data]. */
    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc.inv() and 0xFFFF
    }

    class Decoder(private val sampleRate: Double, val baud: Double = 9600.0, private val onFrame: (ByteArray) -> Unit) {
        private val step = baud / sampleRate
        private var phase = 0.0
        private var dc = 0.0
        private var last = false
        private var lfsr = 0
        private var prevLevel = 0
        // HDLC
        private var shreg = 0
        private var ones = 0
        private var inFrame = false
        private var byte = 0
        private var nbits = 0
        private val buf = java.io.ByteArrayOutputStream()
        var frames = 0
            private set
        var badFcs = 0
            private set

        /** Discriminator samples (any scale; the sign is the bit). */
        fun process(x: FloatArray, n: Int) {
            for (i in 0 until n) {
                dc += (x[i] - dc) * 0.0005
                val v = x[i] - dc
                val level = v > 0
                if (level != last) {                                        // transition: pull the clock to a bit boundary
                    val e = if (phase < 0.5) phase else phase - 1.0
                    phase -= 0.25 * e
                    last = level
                }
                val before = phase
                phase += step
                if (before < 0.5 && phase >= 0.5) lineBit(if (level) 1 else 0)
                if (phase >= 1.0) phase -= 1.0
            }
        }

        private fun lineBit(inBit: Int) {
            val d = inBit xor ((lfsr ushr 16) and 1) xor ((lfsr ushr 11) and 1)
            lfsr = ((lfsr shl 1) or inBit) and 0x1FFFF
            val bit = if (d == prevLevel) 1 else 0                           // NRZI: no change = 1
            prevLevel = d
            hdlc(bit)
        }

        private fun hdlc(bit: Int) {
            shreg = ((shreg ushr 1) or (bit shl 7)) and 0xFF
            if (shreg == 0x7E) {                                            // flag: end of a frame, start of the next
                if (inFrame && buf.size() >= 17) finish()
                inFrame = true; buf.reset(); byte = 0; nbits = 0; ones = 0
                return
            }
            if (!inFrame) return
            if (bit == 1) {
                ones++
                if (ones >= 7) { inFrame = false; buf.reset(); return }         // abort
            } else {
                if (ones == 5) { ones = 0; return }                            // stuffed zero
                ones = 0
            }
            byte = (byte ushr 1) or (bit shl 7); nbits++
            if (nbits == 8) {
                buf.write(byte); nbits = 0; byte = 0
                if (buf.size() > 400) { inFrame = false; buf.reset() }
            }
        }

        private fun finish() {
            val f = buf.toByteArray()
            // the flag's own 7 bits were shifted in before it was seen: whole bytes only
            val len = f.size
            if (len < 17) return
            val fcs = (f[len - 2].toInt() and 0xFF) or ((f[len - 1].toInt() and 0xFF) shl 8)
            if (crc16(f, len - 2) == fcs) { frames++; onFrame(f.copyOf(len - 2)) } else badFcs++
        }
    }

    // ---- transmitter side (tests, and to know what the tracker sends) ----

    /** HDLC bits (flags, frame + FCS LSB first with bit stuffing, flags) of an AX.25 frame without FCS. */
    fun hdlcBits(frame: ByteArray, leadFlags: Int = 20, tailFlags: Int = 4): IntArray {
        val out = ArrayList<Int>()
        fun flag() { for (i in 0 until 8) out.add((0x7E shr i) and 1) }
        repeat(leadFlags) { flag() }
        val crc = crc16(frame)
        val all = frame + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
        var ones = 0
        for (b in all) for (i in 0 until 8) {
            val bit = (b.toInt() shr i) and 1
            out.add(bit)
            if (bit == 1) { ones++; if (ones == 5) { out.add(0); ones = 0 } } else ones = 0
        }
        repeat(tailFlags) { flag() }
        return out.toIntArray()
    }

    /** HDLC bits -> NRZI -> G3RUH scrambler: the bits on air (as the firmware does). */
    fun lineBits(hdlc: IntArray): IntArray {
        var level = 0; var lfsr = 0
        return IntArray(hdlc.size) { i ->
            if (hdlc[i] == 0) level = level xor 1                            // NRZI: 0 = change
            val x = level xor ((lfsr ushr 16) and 1) xor ((lfsr ushr 11) and 1)
            lfsr = ((lfsr shl 1) or x) and 0x1FFFF
            x
        }
    }
}
