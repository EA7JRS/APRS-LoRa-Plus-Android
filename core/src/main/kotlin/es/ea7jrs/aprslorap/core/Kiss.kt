/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 *
 * APRS LoRa+ for Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package es.ea7jrs.aprslorap.core

/** KISS framing (FEND/FESC escapes). Pure code, no Android dependencies. */
object Kiss {
    const val FEND = 0xC0
    const val FESC = 0xDB
    const val TFEND = 0xDC
    const val TFESC = 0xDD
    const val CMD_DATA = 0x00

    /** Wraps an AX.25 frame in a KISS data frame for the given port (0..15). */
    fun encode(ax25: ByteArray, port: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream(ax25.size + 8)
        out.write(FEND)
        out.write(((port and 0x0F) shl 4) or CMD_DATA)
        for (b in ax25) {
            when (b.toInt() and 0xFF) {
                FEND -> { out.write(FESC); out.write(TFEND) }
                FESC -> { out.write(FESC); out.write(TFESC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(FEND)
        return out.toByteArray()
    }

    /**
     * Stream decoder: feed it the bytes as they arrive (BLE notifications or a TCP stream, in any fragmentation)
     * and it returns the complete data frames (AX.25 bytes) found so far. Frames of other commands are dropped.
     */
    class Decoder(private val maxFrame: Int = 1024) {
        private var buf = java.io.ByteArrayOutputStream()
        private var inFrame = false
        private var escaped = false
        private var overflow = false

        fun feed(data: ByteArray): List<ByteArray> {
            val frames = ArrayList<ByteArray>()
            for (raw in data) {
                val b = raw.toInt() and 0xFF
                if (b == FEND) {
                    if (inFrame && buf.size() > 1 && !overflow) {
                        val frame = buf.toByteArray()
                        if ((frame[0].toInt() and 0x0F) == CMD_DATA) frames.add(frame.copyOfRange(1, frame.size))
                    }
                    buf = java.io.ByteArrayOutputStream()
                    inFrame = true
                    escaped = false
                    overflow = false
                    continue
                }
                if (!inFrame) continue
                val v: Int
                if (escaped) {
                    escaped = false
                    v = when (b) {
                        TFEND -> FEND
                        TFESC -> FESC
                        else -> { overflow = true; continue }   // invalid escape: drop the frame
                    }
                } else if (b == FESC) {
                    escaped = true
                    continue
                } else {
                    v = b
                }
                if (buf.size() >= maxFrame) { overflow = true; continue }
                buf.write(v)
            }
            return frames
        }

        fun reset() {
            buf = java.io.ByteArrayOutputStream()
            inFrame = false
            escaped = false
            overflow = false
        }
    }
}
