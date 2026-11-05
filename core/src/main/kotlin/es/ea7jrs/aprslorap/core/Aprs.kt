/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** Parsed APRS information field (only what the app uses). */
sealed class AprsPacket {
    data class Position(
        val lat: Double, val lon: Double,
        val symbolTable: Char, val symbolCode: Char,
        val comment: String,
    ) : AprsPacket()

    /** APRS message. [msgNo] is the text after "{"; ack and rej carry the number of the message they answer. */
    data class Message(
        val addressee: String, val text: String, val msgNo: String?,
        val isAck: Boolean, val isRej: Boolean,
    ) : AprsPacket() {
        val isBulletin: Boolean get() = addressee.startsWith("BLN") || addressee.startsWith("NWS")
    }

    data class Other(val raw: String) : AprsPacket()
}

object Aprs {
    const val MAX_MESSAGE_TEXT = 67

    fun parse(info: String): AprsPacket {
        if (info.isEmpty()) return AprsPacket.Other(info)
        return when (info[0]) {
            '!', '=' -> parsePosition(info.substring(1)) ?: AprsPacket.Other(info)
            '/', '@' -> if (info.length > 8) parsePosition(info.substring(8)) ?: AprsPacket.Other(info) else AprsPacket.Other(info)   // 7-char timestamp
            ':' -> parseMessage(info) ?: AprsPacket.Other(info)
            else -> AprsPacket.Other(info)
        }
    }

    private fun parsePosition(s: String): AprsPacket.Position? {
        if (s.isEmpty()) return null
        return if (s[0].isDigit()) parseUncompressed(s) else parseCompressed(s)
    }

    // DDMM.mmN/DDDMM.mmE$comment
    private fun parseUncompressed(s: String): AprsPacket.Position? {
        if (s.length < 19) return null
        val lat = parseDm(s.substring(0, 7), 2) ?: return null
        val ns = s[7]
        val table = s[8]
        val lon = parseDm(s.substring(9, 17), 3) ?: return null
        val ew = s[17]
        val code = s[18]
        if (ns != 'N' && ns != 'S') return null
        if (ew != 'E' && ew != 'W') return null
        return AprsPacket.Position(if (ns == 'S') -lat else lat, if (ew == 'W') -lon else lon, table, code, s.substring(19))
    }

    /** "DDMM.mm" (degDigits = 2) or "DDDMM.mm" (3). Spaces (position ambiguity) count as zero. */
    private fun parseDm(t: String, degDigits: Int): Double? {
        val clean = t.replace(' ', '0')
        if (clean.length != degDigits + 5 || clean[degDigits + 2] != '.') return null
        val deg = clean.substring(0, degDigits).toIntOrNull() ?: return null
        val min = clean.substring(degDigits).toDoubleOrNull() ?: return null
        if (min >= 60.0) return null
        return deg + min / 60.0
    }

    // /YYYYXXXX$csT : table, 4 chars lat, 4 chars lon, code, 2 chars course/speed, 1 type
    private fun parseCompressed(s: String): AprsPacket.Position? {
        if (s.length < 13) return null
        val table = s[0]
        val lat = 90.0 - base91(s, 1) / 380926.0
        val lon = -180.0 + base91(s, 5) / 190463.0
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return AprsPacket.Position(lat, lon, table, s[9], s.substring(13))
    }

    private fun base91(s: String, at: Int): Double {
        var v = 0.0
        for (i in 0 until 4) {
            val d = s[at + i].code - 33
            if (d !in 0..90) return Double.NaN
            v = v * 91 + d
        }
        return v
    }

    // :ADDRESSEE:text{id
    private fun parseMessage(info: String): AprsPacket.Message? {
        if (info.length < 11 || info[10] != ':') return null
        val addressee = info.substring(1, 10).trim()
        var text = info.substring(11)
        var no: String? = null
        val brace = text.lastIndexOf('{')
        if (brace >= 0 && text.length - brace - 1 in 1..5) {
            no = text.substring(brace + 1)
            text = text.substring(0, brace)
        }
        if (no == null && (text.startsWith("ack") || text.startsWith("rej")) && text.length in 4..8) {
            val n = text.substring(3)
            return AprsPacket.Message(addressee, "", n, text.startsWith("ack"), text.startsWith("rej"))
        }
        return AprsPacket.Message(addressee, text, no, false, false)
    }

    /** Information field of a message: ":ADDRESSEE:text{no" (no = null: without confirmation). */
    fun encodeMessage(addressee: String, text: String, msgNo: String?): String {
        val sb = StringBuilder(":").append(addressee.uppercase().padEnd(9, ' ').take(9)).append(':').append(text)
        if (msgNo != null) sb.append('{').append(msgNo)
        return sb.toString()
    }

    fun encodeAck(addressee: String, msgNo: String): String = encodeMessage(addressee, "ack$msgNo", null)

    /** Message text allowed on the air: at most 67 characters and without { | ~ or control characters. */
    fun messageTextError(text: String): String? {
        if (text.isEmpty()) return "empty"
        if (text.length > MAX_MESSAGE_TEXT) return "too long (max $MAX_MESSAGE_TEXT)"
        if (text.any { it == '{' || it == '|' || it == '~' || it.code < 32 || it.code > 126 }) return "invalid character"
        return null
    }
}
