/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Radio -> APRS-IS gateway logic (the connection itself lives in the app). Receive-only: it never gates APRS-IS -> RF, so it
 * marks what it gates with qAO (aprs-is.net, IGateDetails). Follows aprsc's IGATE-HINTS.md: the packet goes out byte for byte
 * (only cut at the first CR or LF, nothing trimmed, NUL and binary bytes kept: ISO-8859-1 maps each byte to one char), and
 * there is no duplicate filter (the servers drop duplicates and use them for "who heard whom").
 */
class IGate(
    private val myCall: String,
    private val softwareName: String = "APRSLoRaPlusAndroid",
    private val softwareVersion: String = "0.1",
) {
    var gated = 0L; private set
    var dropped = 0L; private set

    /** Line to send to the server, or null if it must not be gated. */
    fun toIsLine(frame: Ax25Frame): String? {
        val line = isLine(frame)
        if (line == null) dropped++ else gated++
        return line
    }

    private fun isLine(frame: Ax25Frame): String? {
        if (dropReason(frame) != null) return null
        val info = firstLine(frame.infoText)
        val q = ",qAO," + myCall.uppercase() + ":"
        if (!info.startsWith("}")) {
            val sb = StringBuilder()
            sb.append(frame.source.text()).append('>').append(frame.destination.text())
            for (d in frame.path) sb.append(',').append(d.toString())
            return sb.append(q).append(info).toString()
        }
        // 3rd party: the RF header and the "}" go; the inner packet is gated unless its header says it came from the internet
        val inner = info.substring(1)
        val colon = inner.indexOf(':')
        val gt = inner.indexOf('>')
        if (gt <= 0 || colon < gt) return null
        val head = inner.substring(0, colon)
        val body = inner.substring(colon + 1)
        if (body.isEmpty() || body.startsWith("?") || body.startsWith("}")) return null
        for (part in head.substring(gt + 1).split(',').drop(1)) {
            val c = part.trimEnd('*').substringBefore('-').uppercase()
            if (c in BLOCKED || (c.length == 3 && c[0] == 'Q' && c[1] == 'A')) return null
        }
        return head + q + body
    }

    fun dropReason(frame: Ax25Frame): String? {
        if (frame.info.isEmpty()) return "empty"
        for (d in frame.path) {
            val c = d.call.uppercase()
            if (c in BLOCKED) return "path " + c
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
        private val BLOCKED = setOf("TCPIP", "TCPXX", "NOGATE", "RFONLY")

        /** Everything up to the first CR or LF (an APRS-IS line ends there). */
        fun firstLine(text: String): String {
            val i = text.indexOfFirst { it == '\r' || it == '\n' }
            return if (i < 0) text else text.substring(0, i)
        }

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

/**
 * How a packet was heard on the air, sent to APRS-IS on a "#is2-meta" line just before the packet (aprsc doc/IS2PLAIN.md): a
 * plaintext client feeds the IS2 reception metadata this way, and servers without IS2 take the line for a comment. Only the
 * values that are known go out; with none, no line at all.
 */
data class RxMeta(
    val rssiDbm: Double? = null,
    val snrDb: Double? = null,
    val modem: Int? = null,
    val bitrate: Long? = null,
    val freqHz: Long? = null,
) {
    fun line(): String? {
        val parts = ArrayList<String>()
        rssiDbm?.takeIf { it < 0 && it > -200 }?.let { parts.add("rx_rssi_dbm=" + num(it)) }
        snrDb?.takeIf { it > -50 && it < 60 }?.let { parts.add("rx_snr_db=" + num(it)) }
        modem?.takeIf { it in 1..1024 }?.let { parts.add("rx_modem=$it") }
        bitrate?.takeIf { it > 0 }?.let { parts.add("rx_bitrate=$it") }
        freqHz?.takeIf { it > 0 }?.let { parts.add("rx_frequency=$it") }
        return if (parts.isEmpty()) null else "#is2-meta " + parts.joinToString(" ")
    }

    companion object {
        /** ModemType of aprsis2.proto. */
        const val MODEM_AX25 = 1
        const val MODEM_LORA_APRS = 2
        const val MODEM_M17 = 9

        private fun num(v: Double): String =
            if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

        /** FNV-1a (32 bits) of "SOURCE:info", as the tracker puts it in its "rf" event (h): pairs the event with the KISS frame. */
        fun hash(frame: Ax25Frame): Long {
            var h = 2166136261L
            fun add(b: Int) { h = ((h xor (b.toLong() and 0xFF)) * 16777619L) and 0xFFFFFFFFL }
            for (c in frame.source.text()) add(c.code)
            add(':'.code)
            for (b in frame.info) add(b.toInt())
            return h
        }
    }
}

/** The tracker's "rf" receive events waiting for their KISS frame (it comes a moment later, on the other characteristic). */
class RxMetaMatcher(private val maxAgeMs: Long = 5_000, private val size: Int = 8) {
    private data class Pending(val hash: Long, val meta: RxMeta, val atMs: Long)
    private val pending = ArrayDeque<Pending>()

    @Synchronized fun offer(hash: Long, meta: RxMeta, nowMs: Long) {
        pending.addLast(Pending(hash, meta, nowMs))
        while (pending.size > size) pending.removeFirst()
    }

    @Synchronized fun take(frame: Ax25Frame, nowMs: Long): RxMeta? {
        pending.removeAll { nowMs - it.atMs > maxAgeMs }
        val h = RxMeta.hash(frame)
        val i = pending.indexOfFirst { it.hash == h }
        return if (i < 0) null else pending.removeAt(i).meta
    }
}
