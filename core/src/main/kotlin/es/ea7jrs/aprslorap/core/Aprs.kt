/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** Parsed APRS information field (only what the app uses). */
sealed class AprsPacket {
    /**
     * A position: plain, compressed, Mic-E, object or item. [comment] is the text left after the data the app decodes (for a weather
     * station, after the weather fields). [courseDeg], [speedKmh] and [altitudeM] are those of the station, never the wind.
     * Objects and items carry their [objectName]; [killed] = the owner deleted it. [micE] = Mic-E status ("En route", "Emergency"...).
     */
    data class Position(
        val lat: Double, val lon: Double,
        val symbolTable: Char, val symbolCode: Char,
        val comment: String,
        val courseDeg: Int? = null, val speedKmh: Double? = null, val altitudeM: Double? = null,
        val wx: AprsWx? = null,
        val objectName: String? = null, val killed: Boolean = false,
        val micE: String? = null,
    ) : AprsPacket() {
        val isEmergency: Boolean get() = (symbolTable == '\\' && symbolCode == '!') || micE == Aprs.MICE_EMERGENCY
    }

    /** Positionless weather report ("_MMDDhhmm..."). */
    data class Weather(val wx: AprsWx, val comment: String) : AprsPacket()

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
    const val MICE_EMERGENCY = "Emergency"
    private const val KNOT = 1.852

    /** [destination] (the AX.25 destination callsign, without SSID) is needed for Mic-E, which carries the latitude in it. */
    fun parse(info: String, destination: String = ""): AprsPacket {
        if (info.isEmpty()) return AprsPacket.Other(info)
        return when (info[0]) {
            '!', '=' -> parsePosition(info.substring(1)) ?: AprsPacket.Other(info)
            '/', '@' -> if (info.length > 8) parsePosition(info.substring(8)) ?: AprsPacket.Other(info) else AprsPacket.Other(info)   // 7-char timestamp
            ':' -> parseMessage(info) ?: AprsPacket.Other(info)
            '`', '\'', '\u001c', '\u001d' -> parseMicE(destination, info) ?: AprsPacket.Other(info)
            ';' -> parseObject(info) ?: AprsPacket.Other(info)
            ')' -> parseItem(info) ?: AprsPacket.Other(info)
            '_' -> if (info.length >= 9 && info.substring(1, 9).all { it.isDigit() })
                AprsWeather.parsePositionless(info.substring(9))?.let { AprsPacket.Weather(it.wx, it.rest) } ?: AprsPacket.Other(info)
                else AprsPacket.Other(info)
            else -> AprsPacket.Other(info)
        }
    }

    /** A third-party frame ("}SRC>DEST,PATH:info", what an iGate sends from APRS-IS to the radio) as the frame inside it; any other frame as it is. */
    fun unwrapThirdParty(frame: Ax25Frame): Ax25Frame {
        val info = frame.infoText
        if (info.length < 5 || info[0] != '}') return frame
        return Tnc2.parse(info.substring(1)) ?: frame
    }

    private fun parsePosition(s: String): AprsPacket.Position? {
        if (s.isEmpty()) return null
        val p = (if (s[0].isDigit()) parseUncompressed(s) else parseCompressed(s)) ?: return null
        if (p.symbolCode != '_') return p
        val w = AprsWeather.parsePositioned(p.comment, p.courseDeg, p.speedKmh)
            ?: return p.copy(courseDeg = null, speedKmh = null)      // with the weather symbol, course / speed would be the wind
        return p.copy(comment = w.rest, wx = w.wx, courseDeg = null, speedKmh = null)
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
        val comment = s.substring(19)
        val x = if (code == '_') AprsExtra.Info(null, null, null) else AprsExtra.parse(comment)
        return AprsPacket.Position(if (ns == 'S') -lat else lat, if (ew == 'W') -lon else lon, table, code, comment, x.courseDeg, x.speedKmh, x.altitudeM)
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
        // course / speed or altitude in the "cs" bytes, unless c is a space; T says which (APRS 1.0.1, chapter 9)
        var course: Int? = null; var speed: Double? = null; var alt: Double? = null
        val c = s[10].code - 33; val sp = s[11].code - 33; val t = s[12].code - 33
        if (s[10] != ' ' && c in 0..90 && sp in 0..90 && t in 0..90) {
            if ((t shr 3) and 3 == 2) alt = Math.pow(1.002, (c * 91 + sp).toDouble()) * 0.3048
            else if (c in 0..89) { course = c * 4; speed = (Math.pow(1.08, sp.toDouble()) - 1.0) * KNOT }
        }
        val comment = s.substring(13)
        return AprsPacket.Position(lat, lon, table, s[9], comment, course, speed, alt ?: AprsExtra.parse(comment).altitudeM)
    }

    // ;NAME_____*DDHHMMzPOSITION (live "*", killed "_")
    private fun parseObject(info: String): AprsPacket.Position? {
        if (info.length < 18 + 13 || (info[10] != '*' && info[10] != '_')) return null
        val name = info.substring(1, 10).trim().ifEmpty { return null }
        return parsePosition(info.substring(18))?.copy(objectName = name, killed = info[10] == '_')
    }

    // )NAME!POSITION: name of 3 to 9 characters, "!" live, "_" killed
    private fun parseItem(info: String): AprsPacket.Position? {
        val end = (4..minOf(10, info.length - 1)).firstOrNull { info[it] == '!' || info[it] == '_' } ?: return null
        val name = info.substring(1, end).trim().ifEmpty { return null }
        return parsePosition(info.substring(end + 1))?.copy(objectName = name, killed = info[end] == '_')
    }

    private val MICE_STD = arrayOf(MICE_EMERGENCY, "Priority", "Special", "Committed", "Returning", "In service", "En route", "Off duty")
    private val MICE_ALT = Regex("([!-{]{3})\\}")

    /** Mic-E (APRS 1.0.1, chapter 10): latitude and message bits in the destination, longitude, speed, course and symbol in the information field. */
    private fun parseMicE(destination: String, info: String): AprsPacket.Position? {
        val d = destination.substringBefore('-')
        if (d.length != 6 || info.length < 9) return null
        val digits = IntArray(6)
        var custom = 0
        for (i in 0 until 6) {
            val ch = d[i]
            digits[i] = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'J' -> ch - 'A'
                in 'P'..'Y' -> ch - 'P'
                'K', 'L', 'Z' -> 0          // position ambiguity
                else -> return null
            }
            if (i < 3 && ch in 'A'..'K') custom++
        }
        var bits = 0
        for (i in 0 until 3) bits = (bits shl 1) or (if (d[i] in 'P'..'Z' || d[i] in 'A'..'K') 1 else 0)
        val status = if (bits == 0) MICE_EMERGENCY else if (custom > 0) "Custom-${7 - bits}" else MICE_STD[bits]
        val north = d[3] in 'P'..'Z'
        val lonOffset = d[4] in 'P'..'Z'
        val west = d[5] in 'P'..'Z'
        val minutes = digits[2] * 10 + digits[3] + (digits[4] * 10 + digits[5]) / 100.0
        if (minutes >= 60.0) return null
        val lat = (digits[0] * 10 + digits[1]) + minutes / 60.0

        var deg = info[1].code - 28
        if (lonOffset) deg += 100
        if (deg in 180..189) deg -= 80 else if (deg in 190..199) deg -= 190
        var min = info[2].code - 28
        if (min >= 60) min -= 60
        val hun = info[3].code - 28
        if (deg !in 0..179 || min !in 0..59 || hun !in 0..99) return null
        val lon = deg + (min + hun / 100.0) / 60.0
        if (lat > 90.0) return null

        val sp = info[4].code - 28; val dc = info[5].code - 28; val se = info[6].code - 28
        var knots = sp * 10 + dc / 10
        var course = (dc % 10) * 100 + se
        if (knots >= 800) knots -= 800
        if (course >= 400) course -= 400
        val code = info[7]; val table = info[8]

        var text = info.substring(9)
        var alt: Double? = null
        MICE_ALT.find(text)?.let { m ->
            val v = m.groupValues[1]
            alt = ((v[0].code - 33) * 8281 + (v[1].code - 33) * 91 + (v[2].code - 33) - 10000).toDouble()
            text = text.removeRange(m.range)
        }
        text = cleanMicEText(text)
        return AprsPacket.Position(
            if (north) lat else -lat, if (west) -lon else lon, table, code, text,
            courseDeg = if (course in 1..360) course % 360 else null,
            speedKmh = if (knots in 0..799) knots * KNOT else null,
            altitudeM = alt, micE = status,
        )
    }

    /** Status text of a Mic-E frame without the radio type marks (Kenwood ">", "]" + "=", Yaesu and others "`" / "'" + 2 characters). */
    private fun cleanMicEText(t: String): String {
        if (t.isEmpty()) return t
        return when (t[0]) {
            '>' -> t.substring(1).removeSuffix("=").removeSuffix("^")
            ']' -> t.substring(1).removeSuffix("=")
            '`', '\'' -> t.substring(1).let { if (it.length >= 2) it.dropLast(2) else "" }
            else -> t
        }.trim()
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
