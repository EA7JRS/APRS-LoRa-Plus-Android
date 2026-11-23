/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * A received frame explained: its TNC2 text cut in parts (to colour them), every field said in words and the way it travelled
 * (who sent it, which digipeaters repeated it, the iGate that brought it from APRS-IS). It adds nothing to what the parser
 * decodes: it only says it.
 */
object PacketExplain {
    enum class Kind { SOURCE, DEST, PATH, DATA, COMMENT, SEP }
    data class Part(val text: String, val kind: Kind)
    data class Field(val name: String, val value: String, val note: String = "")
    enum class Role { TX, DIGI, IGATE, IS }
    data class Hop(val who: String, val role: Role, val note: String)

    private val TOCALLS = listOf(
        "APLJRT" to "APRS LoRa+ tracker", "APLJRA" to "APRS LoRa+ for Android", "APZLRT" to "APRS LoRa+ tracker (old id)",
        "APZLRP" to "APRS LoRa+ for Android (old id)", "APZLRR" to "APRS LoRa+ rotor", "APLRT" to "CA2RXU LoRa tracker",
        "APLRG" to "CA2RXU LoRa iGate", "APLT" to "LoRa tracker", "APLG" to "LoRa iGate", "APDR" to "APRSdroid", "APDW" to "Dire Wolf",
        "APRX" to "aprx", "APMI" to "Microsat", "APK" to "Kenwood", "APY" to "Yaesu", "APOT" to "OpenTracker", "APZ" to "experimental",
    )

    /** What the destination callsign says of the sender's device; "" when it is not one of the few the app names. */
    fun device(tocall: String): String = TOCALLS.firstOrNull { tocall.uppercase().startsWith(it.first) }?.second ?: ""

    /** The TNC2 line in parts: source, destination, path and the information field (data, then the comment if we know where it starts). */
    fun parts(frame: Ax25Frame, comment: String? = null): List<Part> {
        val out = ArrayList<Part>()
        out += Part(frame.source.text(), Kind.SOURCE)
        out += Part(">", Kind.SEP)
        out += Part(frame.destination.text(), Kind.DEST)
        if (frame.path.isNotEmpty()) { out += Part(",", Kind.SEP); out += Part(frame.path.joinToString(",") { it.toString() }, Kind.PATH) }
        out += Part(":", Kind.SEP)
        val info = frame.infoText
        val c = comment?.takeIf { it.isNotEmpty() && info.endsWith(it) && it.length < info.length }
        if (c != null) { out += Part(info.dropLast(c.length), Kind.DATA); out += Part(c, Kind.COMMENT) } else out += Part(info, Kind.DATA)
        return out
    }

    private fun typeName(info: String): String {
        if (info.isEmpty()) return "empty"
        fun pos(body: String) = if (body.isNotEmpty() && !body[0].isDigit()) "compressed" else "plain"
        return when (info[0]) {
            '!' -> "Position, ${pos(info.drop(1))}, no messaging"
            '=' -> "Position, ${pos(info.drop(1))}, with messaging"
            '/' -> "Position with time, ${pos(info.drop(8))}, no messaging"
            '@' -> "Position with time, ${pos(info.drop(8))}, with messaging"
            ':' -> "Message"
            '`', '\'', '\u001c', '\u001d' -> "Mic-E position"
            ';' -> "Object"
            ')' -> "Item"
            '>' -> "Status"
            'T' -> if (info.startsWith("T#")) "Telemetry" else "Other"
            '_' -> "Weather report without position"
            '}' -> "Third-party frame (from APRS-IS, sent by an iGate)"
            '?' -> "Query"
            '<' -> "Station capabilities"
            '$' -> "Raw NMEA sentence"
            else -> "Other"
        }
    }

    private fun latLon(v: Double, isLat: Boolean): String {
        val a = Math.abs(v); val d = a.toInt(); val m = (a - d) * 60
        val h = if (isLat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")
        return String.format(Locale.US, if (isLat) "%02d°%05.2f'%s" else "%03d°%05.2f'%s", d, m, h)
    }

    /** The "cs" bytes of a compressed position said in words; null if the position is not compressed or has none. */
    fun compressedCs(info: String): String? {
        if (info.isEmpty()) return null
        val body = when (info[0]) { '!', '=' -> info.drop(1); '/', '@' -> info.drop(8); else -> return null }
        if (body.length < 13 || body[0].isDigit()) return null
        if (body[10] == ' ') return "empty"
        val c = body[10].code - 33; val s = body[11].code - 33; val t = body[12].code - 33
        if (c !in 0..90 || s !in 0..90 || t !in 0..90) return null
        return when {
            (t shr 3) and 3 == 2 -> "altitude (T = ${body[12]})"
            c in 0..89 -> "course and speed (T = ${body[12]})"
            c == 90 -> "radio range (T = ${body[12]})"
            else -> null
        }
    }

    /**
     * Every field of the frame in words. [derived] = course and speed worked out from two positions (the packet had none);
     * [sinceMs] is not used here: the caller shows the time.
     */
    fun fields(frame: Ax25Frame, derivedCourse: Int? = null, derivedSpeedKmh: Double? = null): List<Field> {
        val out = ArrayList<Field>()
        val inner = Aprs.unwrapThirdParty(frame)
        val third = inner !== frame
        if (third) out += Field("Sent to the radio by", frame.source.text(), "an iGate, from APRS-IS")
        out += Field("Source", inner.source.text(), AprsConventions.ssidMeaning(inner.source.ssid))
        out += Field("Device (tocall)", inner.destination.text(), device(inner.destination.call))
        val info = inner.infoText
        out += Field("Type", typeName(info))
        when (val p = Aprs.parse(info, inner.destination.call)) {
            is AprsPacket.Position -> {
                p.objectName?.let { out += Field(if (info.startsWith(")")) "Item" else "Object", it, if (p.killed) "deleted by its owner" else "") }
                out += Field("Position", latLon(p.lat, true) + " " + latLon(p.lon, false))
                out += Field("Symbol", "${p.symbolTable}${p.symbolCode}", AprsSymbols.describe(p.symbolTable, p.symbolCode))
                compressedCs(info)?.let { out += Field("cs bytes", it) }
                p.micE?.let { out += Field("Mic-E status", it) }
                if (p.wx != null) out += Field("Weather", p.wx.summary(0), "the course / speed field carries the wind")
                else if (p.courseDeg != null || p.speedKmh != null)
                    out += Field("Course / speed", String.format(Locale.US, "%03d° · %.0f km/h", p.courseDeg ?: 0, p.speedKmh ?: 0.0))
                else out += Field("Course / speed", "not in the packet",
                    if (derivedSpeedKmh != null && derivedSpeedKmh >= 1 && derivedCourse != null) String.format(Locale.US, "~ %.0f km/h, %03d° from 2 positions", derivedSpeedKmh, derivedCourse)
                    else if (derivedSpeedKmh != null) "not moving, by its last 2 positions" else "")
                p.altitudeM?.let { out += Field("Altitude", String.format(Locale.US, "%.0f m", it)) }
                if (p.comment.isNotEmpty()) out += Field("Comment", p.comment)
            }
            is AprsPacket.Message -> {
                out += Field("To", p.addressee.trim(), if (p.isGroup) AprsConventions.groupName(p.addressee) + ": never acknowledged" else "")
                out += Field(if (p.isAck) "Acknowledges" else if (p.isRej) "Rejects" else "Text", if (p.isAck || p.isRej) "message " + (p.msgNo ?: "") else p.text)
                if (!p.isAck && !p.isRej) out += Field("Number", p.msgNo ?: "none", if (p.msgNo == null) "no acknowledgement asked" else "asks for an acknowledgement")
            }
            is AprsPacket.Weather -> { out += Field("Weather", p.wx.summary(0)); if (p.comment.isNotEmpty()) out += Field("Comment", p.comment) }
            is AprsPacket.Other -> if (info.length > 1) out += Field("Content", info.drop(1))
        }
        return out
    }

    // the q construct of APRS-IS; the address parser folds the case, so qAo / qAO and qAr / qAR read the same here
    private val Q = mapOf(
        "QAC" to "came straight from a verified client", "QAX" to "came from an unverified client", "QAU" to "came by UDP",
        "QAO" to "gated from the radio by an iGate", "QAR" to "gated from the radio by an iGate", "QAS" to "came from another server",
        "QAZ" to "not for the network", "QAI" to "traced server by server",
    )

    /** The way of the frame, first hop first; the last one (our receiver) is added by the caller, who knows the signal. */
    fun hops(frame: Ax25Frame): List<Hop> {
        val out = ArrayList<Hop>()
        val inner = Aprs.unwrapThirdParty(frame)
        out += Hop(inner.source.text(), Role.TX, "transmits")
        fun digis(path: List<Ax25Address>) {
            for ((i, a) in path.withIndex()) {
                if (!a.repeated || a.call.startsWith("WIDE") || a.call.startsWith("TCP") || a.call.startsWith("QA")) continue
                val alias = path.getOrNull(i + 1)?.takeIf { it.call.startsWith("WIDE") && it.repeated }?.call
                out += Hop(a.text(), Role.DIGI, "digipeater" + (alias?.let { " · used $it" } ?: ""))
            }
        }
        if (inner !== frame) {
            // inside: SRC>DEST,TCPIP,IGATE* (the rule) or with the q construct left in (qAC,SERVER): how it reached APRS-IS
            val texts = inner.path.map { it.call }
            val qi = texts.indexOfFirst { it.length == 3 && it.startsWith("QA") }
            if (qi >= 0) digis(inner.path.take(qi))                 // without q the inside path is TCPIP,IGATE*: no digipeaters
            if (qi >= 0) out += Hop(inner.path.getOrNull(qi + 1)?.text() ?: "APRS-IS", Role.IS, Q[texts[qi]] ?: "APRS-IS (${texts[qi]})")
            else out += Hop("APRS-IS", Role.IS, "came through the internet")
            out += Hop(frame.source.text(), Role.IGATE, "iGate · sent it to the radio")
            digis(frame.path)
        } else digis(frame.path)
        return out
    }

    /** "1 hop used, 1 left" for the path asked for, from the WIDEn-N left in it. */
    fun pathSummary(frame: Ax25Frame): String {
        val used = frame.path.count { it.repeated && !it.call.startsWith("WIDE") }
        val left = frame.path.filter { !it.repeated && it.call.startsWith("WIDE") }.sumOf { it.ssid }
        return if (frame.path.isEmpty()) "No path: heard directly." else "$used hop${if (used == 1) "" else "s"} used, $left left."
    }
}
