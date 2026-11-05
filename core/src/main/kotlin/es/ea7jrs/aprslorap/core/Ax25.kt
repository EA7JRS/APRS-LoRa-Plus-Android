/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** An AX.25 address: callsign + SSID; [repeated] is the "has been repeated" (H) bit of a digipeater. */
data class Ax25Address(val call: String, val ssid: Int = 0, val repeated: Boolean = false) {
    /** Text form as in TNC2: CALL-SSID (without "-0"), with "*" if repeated. */
    override fun toString(): String = text() + if (repeated) "*" else ""

    fun text(): String = if (ssid == 0) call else "$call-$ssid"

    companion object {
        /** Parses "CALL", "CALL-7" or "CALL-7*" (also WIDE2-1). Returns null if it is not valid. */
        fun parse(s: String): Ax25Address? {
            var t = s.trim()
            if (t.isEmpty()) return null
            val rep = t.endsWith("*")
            if (rep) t = t.dropLast(1)
            val dash = t.indexOf('-')
            val call = (if (dash >= 0) t.substring(0, dash) else t).uppercase()
            val ssid = if (dash >= 0) t.substring(dash + 1).toIntOrNull() ?: return null else 0
            if (call.isEmpty() || call.length > 6 || ssid !in 0..15) return null
            if (!call.all { it in 'A'..'Z' || it in '0'..'9' }) return null
            return Ax25Address(call, ssid, rep)
        }
    }
}

/** A UI frame (control 0x03, PID 0xF0), the only kind APRS uses. */
data class Ax25Frame(
    val destination: Ax25Address,
    val source: Ax25Address,
    val path: List<Ax25Address>,
    val info: ByteArray,
) {
    val infoText: String get() = String(info, Charsets.ISO_8859_1)

    /** TNC2 monitor text: SRC>DEST,DIGI*,DIGI:info */
    fun toTnc2(): String {
        val sb = StringBuilder()
        sb.append(source.text()).append('>').append(destination.text())
        for (d in path) sb.append(',').append(d.toString())
        sb.append(':').append(infoText)
        return sb.toString()
    }

    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val addrs = ArrayList<Ax25Address>()
        addrs.add(destination); addrs.add(source); addrs.addAll(path)
        for ((i, a) in addrs.withIndex()) {
            val padded = a.call.padEnd(6, ' ')
            for (c in padded) out.write(c.code shl 1)
            var ssidByte = 0x60 or ((a.ssid and 0x0F) shl 1)
            if (a.repeated && i >= 2) ssidByte = ssidByte or 0x80
            if (i == 0 || i == 1) ssidByte = ssidByte or 0x80           // C bits: command frame (dest = 1, src = 0 would be a response; APRS uses dest set)
            if (i == 1) ssidByte = ssidByte and 0x7F                    // source: C bit clear
            if (i == addrs.size - 1) ssidByte = ssidByte or 0x01
            out.write(ssidByte)
        }
        out.write(0x03)
        out.write(0xF0)
        out.write(info)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is Ax25Frame && destination == other.destination && source == other.source && path == other.path && info.contentEquals(other.info)

    override fun hashCode(): Int = ((destination.hashCode() * 31 + source.hashCode()) * 31 + path.hashCode()) * 31 + info.contentHashCode()

    companion object {
        fun create(source: String, destination: String, path: List<String>, info: String): Ax25Frame? {
            val s = Ax25Address.parse(source) ?: return null
            val d = Ax25Address.parse(destination) ?: return null
            val p = path.map { Ax25Address.parse(it) ?: return null }
            if (p.size > 8) return null
            return Ax25Frame(d, s, p, info.toByteArray(Charsets.ISO_8859_1))
        }

        /** Decodes the bytes of a KISS data frame. Returns null if it is not a valid APRS UI frame. */
        fun decode(data: ByteArray): Ax25Frame? {
            if (data.size < 16) return null
            val addrs = ArrayList<Ax25Address>()
            var i = 0
            while (true) {
                if (i + 7 > data.size || addrs.size >= 10) return null
                val sb = StringBuilder()
                for (k in 0 until 6) {
                    val ch = ((data[i + k].toInt() and 0xFF) shr 1).toChar()
                    if (ch != ' ') sb.append(ch)
                }
                val ssidByte = data[i + 6].toInt() and 0xFF
                val last = (ssidByte and 0x01) != 0
                val repeated = addrs.size >= 2 && (ssidByte and 0x80) != 0
                addrs.add(Ax25Address(sb.toString(), (ssidByte shr 1) and 0x0F, repeated))
                i += 7
                if (last) break
            }
            if (addrs.size < 2) return null
            if (i + 2 > data.size) return null
            val control = data[i].toInt() and 0xFF
            val pid = data[i + 1].toInt() and 0xFF
            if ((control and 0xEF) != 0x03 || pid != 0xF0) return null
            return Ax25Frame(addrs[0], addrs[1], addrs.drop(2), data.copyOfRange(i + 2, data.size))
        }
    }
}

/** Text form of a frame used in TNC2 mode and in APRS-IS. */
object Tnc2 {
    /** Parses "SRC>DEST,PATH...:info". Returns null if it does not look like TNC2. */
    fun parse(line: String): Ax25Frame? {
        val colon = line.indexOf(':')
        val gt = line.indexOf('>')
        if (gt <= 0 || colon < gt) return null
        val head = line.substring(0, colon).split(',')
        val src = head[0].substringBefore('>')
        val dst = head[0].substringAfter('>')
        val path = head.drop(1)
        return Ax25Frame.create(src, dst, path, line.substring(colon + 1))
    }
}
