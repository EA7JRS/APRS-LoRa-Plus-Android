/*
 * Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * High-altitude balloon (HAB) telemetry: UKHAS "$$CALL,id,time,lat,lon,alt,...*CRC" sentences, as sent by RTTY
 * (decoded through [Modems.Rtty]) or carried as ASCII in a LoRa packet. The CRC is CRC16-CCITT, the same one the
 * APRS LoRa+ tracker uses for its RTTY beacon. Binary Horus telemetry (4FSK) is a separate format and is not parsed here.
 */
object Hab {

    /** CRC16-CCITT (0xFFFF init, polynomial 0x1021) over the body between the `$$` and the `*`, as UKHAS defines it. */
    fun crc16(body: String): Int {
        var crc = 0xFFFF
        for (c in body) {
            crc = crc xor (c.code shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1) }
            crc = crc and 0xFFFF
        }
        return crc and 0xFFFF
    }

    data class Telemetry(
        val callsign: String,
        val counter: Int?,
        val timeUtc: String?,          // HH:MM:SS if present
        val lat: Double?,
        val lon: Double?,
        val altM: Double?,
        val fields: List<String>,      // every comma field of the body, starting at the callsign
        val extra: List<String>,       // fields beyond altitude (sats, voltage, temperature, ...)
        val crcOk: Boolean,
        val raw: String
    ) {
        val hasPosition get() = lat != null && lon != null
    }

    private val TIME_RE = Regex("""\d{1,2}:\d{2}:\d{2}""")

    /**
     * Parse one "$$...*CRC" sentence (two or more leading `$`). Returns null if it is not a UKHAS-shaped sentence.
     * The field order follows the UKHAS convention (callsign, sentence id, time, lat, lon, alt, then free fields);
     * fields that do not fit are left null and kept verbatim in [Telemetry.fields].
     */
    fun parse(line: String): Telemetry? {
        val s = line.trim()
        if (!s.startsWith("$$")) return null
        val dollarEnd = s.indexOfFirst { it != '$' }
        if (dollarEnd < 2) return null
        val star = s.lastIndexOf('*')
        if (star <= dollarEnd) return null
        val body = s.substring(dollarEnd, star)
        if (body.isEmpty()) return null
        val crcText = s.substring(star + 1).trim().take(4)
        val crcOk = crcText.length == 4 &&
            crcText.uppercase() == String.format("%04X", crc16(body)) &&
            crcText.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }

        val fields = body.split(',')
        val callsign = fields.getOrNull(0)?.trim().orEmpty()
        if (callsign.isEmpty()) return null
        val counter = fields.getOrNull(1)?.trim()?.toIntOrNull()
        val timeUtc = fields.getOrNull(2)?.trim()?.takeIf { TIME_RE.matches(it) }
        val lat = fields.getOrNull(3)?.trim()?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lon = fields.getOrNull(4)?.trim()?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        val altM = fields.getOrNull(5)?.trim()?.toDoubleOrNull()
        val extra = if (fields.size > 6) fields.subList(6, fields.size).map { it.trim() } else emptyList()
        return Telemetry(callsign, counter, timeUtc, lat, lon, altM, fields.map { it.trim() }, extra, crcOk, s)
    }

    /**
     * Assembles sentences from a character stream (e.g. the RTTY modem's [Modems.Modem.onText]). It resynchronises on
     * every `$$` so noise before a sentence is dropped, and emits a sentence either at a CR/LF or as soon as the 4 CRC
     * characters after the `*` have arrived (RTTY carries no reliable line ending). [onSentence] gets every parsed
     * sentence, CRC-valid or not (check [Telemetry.crcOk]); callers usually keep only the valid ones.
     */
    class Assembler(val onSentence: (Telemetry) -> Unit) {
        private val sb = StringBuilder()

        fun feed(text: String) { for (c in text) feedChar(c) }

        fun feedChar(c: Char) {
            if (c == '\n' || c == '\r') { flush(); return }
            if (c.code < 32) return                               // ignore other control characters
            sb.append(c)
            // resync: a new "$$" starts a fresh sentence, dropping anything collected before it
            val n = sb.length
            if (c == '$' && n >= 2 && sb[n - 2] == '$' && (n == 2 || sb[n - 3] != '$')) {
                sb.setLength(0); sb.append("$$")
            }
            if (sb.length > 300) { sb.setLength(0); return }      // runaway without a terminator
            // auto-emit once we have "$$...*XXXX"
            val star = sb.lastIndexOf("*")
            if (star >= 2 && sb.length - star - 1 >= 4 && sb.startsWith("$$")) flush()
        }

        private fun flush() {
            val idx = sb.indexOf("$$")
            val line = if (idx >= 0) sb.substring(idx) else ""
            sb.setLength(0)
            if (line.isNotEmpty()) parse(line)?.let(onSentence)
        }
    }

    /** Keeps the latest telemetry per balloon callsign, newest first, for the list and the map. */
    class Tracker {
        data class Entry(var tlm: Telemetry, var heardMs: Long, var count: Int)

        private val map = LinkedHashMap<String, Entry>()

        /** Feeds a parsed sentence. Returns the entry if it was accepted (CRC ok), or null if dropped. */
        fun onSentence(t: Telemetry, nowMs: Long): Entry? {
            if (!t.crcOk) return null
            val e = map[t.callsign]
            if (e == null) {
                val ne = Entry(t, nowMs, 1); map[t.callsign] = ne; return ne
            }
            e.tlm = t; e.heardMs = nowMs; e.count++
            return e
        }

        fun list(): List<Entry> = map.values.sortedByDescending { it.heardMs }
        fun clear() = map.clear()
        fun size() = map.size
    }
}
