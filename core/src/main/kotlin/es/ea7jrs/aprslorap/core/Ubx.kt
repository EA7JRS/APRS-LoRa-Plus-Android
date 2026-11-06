/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/**
 * A-GPS for the tracker's u-blox receiver (T-Beam Supreme, MAX-M10S), sent with the BLE command `gpsaid`.
 * - Time and position of the phone: UBX-MGA-INI-TIME_UTC and UBX-MGA-INI-POS_LLH (no account; speeds the first fix up).
 * - u-blox Predictive Orbits (free for M9/M10, a u-blox / Thingstream account and a registered device are needed): a stream of
 *   UBX-MGA messages downloaded with the phone's internet, sent as it comes, after the time and position.
 */
object Ubx {
    const val MGA = 0x13
    const val MGA_INI = 0x40
    const val MAX_AID_BYTES = 200_000             // Predictive Orbits files are 3-135 KB
    const val CHUNK_BYTES = 135                   // 180 characters of base64 per command

    fun frame(cls: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 8)
        out[0] = 0xB5.toByte(); out[1] = 0x62
        out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xFF).toByte(); out[5] = (payload.size shr 8).toByte()
        payload.copyInto(out, 6)
        var a = 0; var b = 0
        for (i in 2 until 6 + payload.size) { a = (a + (out[i].toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF }
        out[out.size - 2] = a.toByte(); out[out.size - 1] = b.toByte()
        return out
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** UBX-MGA-INI-TIME_UTC: UTC time of the phone, accuracy [accS] seconds (leap seconds unknown: the receiver knows them). */
    fun iniTimeUtc(utcMs: Long, accS: Int = 2): ByteArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
        val p = le(24)
            .put(0x10).put(0x00).put(0x00).put((-128).toByte())               // type, version, ref (none), leapSecs unknown
            .putShort(c.get(Calendar.YEAR).toShort()).put((c.get(Calendar.MONTH) + 1).toByte()).put(c.get(Calendar.DAY_OF_MONTH).toByte())
            .put(c.get(Calendar.HOUR_OF_DAY).toByte()).put(c.get(Calendar.MINUTE).toByte()).put(c.get(Calendar.SECOND).toByte()).put(0)
            .putInt(c.get(Calendar.MILLISECOND) * 1_000_000)                  // ns
            .putShort(accS.coerceIn(0, 65535).toShort()).putShort(0)          // tAccS, reserved
            .putInt(0)                                                         // tAccNs
        return frame(MGA, MGA_INI, p.array())
    }

    /** UBX-MGA-INI-POS_LLH: position of the phone, accuracy [accM] metres. */
    fun iniPosLlh(lat: Double, lon: Double, altM: Double, accM: Double): ByteArray {
        val p = le(20)
            .put(0x01).put(0x00).putShort(0)                                  // type, version, reserved
            .putInt(Math.round(lat * 1e7).toInt()).putInt(Math.round(lon * 1e7).toInt())
            .putInt(Math.round(altM * 100).toInt())
            .putInt(Math.round(accM.coerceIn(1.0, 4_000_000.0) * 100).toInt().coerceAtLeast(100))
        return frame(MGA, MGA_INI, p.array())
    }

    /**
     * Checks a downloaded A-GPS file: only complete UBX frames with good checksums. Returns the number of messages, or null
     * with the reason in [why] (an error page or a text status instead of the data, for example).
     */
    fun countFrames(data: ByteArray, why: StringBuilder? = null): Int? {
        if (data.isEmpty()) { why?.append("empty file"); return null }
        if (data.size > MAX_AID_BYTES) { why?.append("file too big (${data.size} bytes)"); return null }
        var i = 0; var n = 0
        while (i < data.size) {
            if (i + 8 > data.size || data[i] != 0xB5.toByte() || data[i + 1] != 0x62.toByte()) {
                why?.append(if (n == 0) "not u-blox data: " + String(data, 0, minOf(80, data.size), Charsets.ISO_8859_1).trim() else "broken message at byte $i")
                return null
            }
            val len = (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8)
            if (i + 8 + len > data.size) { why?.append("truncated message at byte $i"); return null }
            val f = frame(data[i + 2].toInt() and 0xFF, data[i + 3].toInt() and 0xFF, data.copyOfRange(i + 6, i + 6 + len))
            if (f[f.size - 2] != data[i + 6 + len] || f[f.size - 1] != data[i + 7 + len]) { why?.append("bad checksum at byte $i"); return null }
            i += 8 + len; n++
        }
        return n
    }

    /** The `gpsaid` command lines for [data]: begin, base64 pieces (180 characters), end. */
    fun aidLines(data: ByteArray): List<String> {
        val lines = ArrayList<String>()
        lines.add(Control.command("gpsaid", "s" to "begin", "n" to data.size))
        val enc = java.util.Base64.getEncoder()
        var pos = 0
        while (pos < data.size) {
            val end = minOf(data.size, pos + CHUNK_BYTES)
            lines.add(Control.command("gpsaid", "s" to "c", "d" to enc.encodeToString(data.copyOfRange(pos, end))))
            pos = end
        }
        lines.add(Control.command("gpsaid", "s" to "end"))
        return lines
    }
}
