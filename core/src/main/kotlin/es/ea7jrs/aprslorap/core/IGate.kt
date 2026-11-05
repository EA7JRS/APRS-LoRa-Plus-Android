/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** Radio -> APRS-IS gateway logic (the connection itself lives in the app). */
class IGate(
    private val myCall: String,
    private val now: () -> Long,
    private val dedupeWindowMs: Long = 30_000L,
    private val softwareName: String = "APRSLoRaPlusAndroid",
    private val softwareVersion: String = "0.1",
) {
    private val recent = HashMap<String, Long>()
    var gated = 0L; private set
    var dropped = 0L; private set

    /** Line to send to the server, or null if it must not be gated. */
    fun toIsLine(frame: Ax25Frame): String? {
        val reason = dropReason(frame)
        if (reason != null) { dropped++; return null }
        val t = now()
        recent.entries.removeIf { t - it.value > dedupeWindowMs }
        val key = frame.source.text() + ">" + frame.destination.text() + ":" + frame.infoText
        if (recent.containsKey(key)) { dropped++; return null }
        recent[key] = t
        gated++
        val sb = StringBuilder()
        sb.append(frame.source.text()).append('>').append(frame.destination.text())
        for (d in frame.path) sb.append(',').append(d.toString())
        sb.append(",qAR,").append(myCall.uppercase()).append(':').append(frame.infoText)
        return sb.toString()
    }

    fun dropReason(frame: Ax25Frame): String? {
        if (frame.info.isEmpty()) return "empty"
        if (frame.infoText.startsWith("}")) return "third party"
        for (d in frame.path) {
            val c = d.call.uppercase()
            if (c == "TCPIP" || c == "TCPXX" || c == "NOGATE" || c == "RFONLY") return "path " + c
        }
        if (frame.infoText.startsWith("?")) return "query"
        return null
    }

    fun loginLine(filter: String? = null): String {
        val sb = StringBuilder("user ").append(myCall.uppercase()).append(" pass ").append(passcode(myCall))
            .append(" vers ").append(softwareName).append(' ').append(softwareVersion)
        if (!filter.isNullOrBlank()) sb.append(" filter ").append(filter.trim())
        return sb.toString()
    }

    companion object {
        /** APRS-IS passcode of a callsign (without SSID). */
        fun passcode(call: String): Int {
            val base = call.uppercase().substringBefore('-')
            var hash = 0x73e2
            var i = 0
            while (i < base.length) {
                hash = hash xor (base[i].code shl 8)
                if (i + 1 < base.length) hash = hash xor base[i + 1].code
                i += 2
            }
            return hash and 0x7fff
        }
    }
}
