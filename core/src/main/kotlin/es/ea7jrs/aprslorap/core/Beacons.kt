/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * Stations heard by the app's own receivers (SDR or radio audio) in the beacon modes: LR-FHSS, WSPR, FST4W, FT8, FT4 and
 * CW. One entry per station, mode and band, with where it is when the message says it (locator): for the map, the rotor
 * and to announce it to the DX cluster.
 */
object Beacons {
    data class Heard(val call: String, val mode: String, val freqHz: Long, val locator: String, val snrDb: Int?, val powerDbm: Int?, val text: String,
                     val timeMs: Long, val count: Int = 1, val spottedMs: Long = 0) {
        val key: String get() = call + "|" + mode + "|" + (Spots.band(freqHz) ?: (freqHz / 1_000_000).toString())
        val position: Pair<Double, Double>? get() = if (locator.isEmpty()) null else RotorFollow.locatorToLatLon(locator)
        val band: String get() = Spots.band(freqHz) ?: ""
    }

    fun fromLrFhss(b: LrFhss.Beacon, freqHz: Long, snrDb: Int, nowMs: Long, text: String) = Heard(b.callsign, "LR-FHSS", freqHz, b.locator, snrDb, b.powerDbm, text, nowMs)

    private val WSPR = Regex("^<?([A-Z0-9/]+)>?\\s+([A-R]{2}[0-9]{2}(?:[A-X]{2})?)\\s+(-?\\d+)$")

    /** FT8 / FT4 messages with a station and (or CQ) and WSPR / FST4W ones with callsign, locator and power. */
    fun fromWsjt(d: Wsjt.Decode, mode: Wsjt.Mode, dialHz: Long?): Heard? {
        if (mode.wsprLike) {
            val m = WSPR.find(d.message.trim().uppercase(Locale.US)) ?: return null
            val call = m.groupValues[1]
            if (!call.any(Char::isDigit) || !call.any(Char::isLetter)) return null
            val hz = d.rfHz ?: dialHz?.let { it + d.audioHz } ?: return null
            return Heard(call, if (mode.isFst4w) "FST4W" else "WSPR", hz, m.groupValues[2], Math.round(d.snr).toInt(), m.groupValues[3].toIntOrNull(), d.message.trim(), d.slotMs)
        }
        if (dialHz == null || dialHz <= 0) return null
        val (call, grid) = Spots.deCallAndGrid(d.message) ?: return null
        return Heard(call, mode.label, dialHz + d.audioHz, if (Spots.isGrid(grid)) grid.uppercase(Locale.US) else "", Math.round(d.snr).toInt(), null, d.message.trim(), d.slotMs)
    }

    fun fromCw(call: String, comment: String, locator: String, rfHz: Long, snrDb: Int, nowMs: Long) =
        Heard(call.uppercase(Locale.US), "CW", rfHz, locator.uppercase(Locale.US), snrDb, null, comment, nowMs)

    /** Comment of the spot, 30 characters at most: "LR-FHSS IM67NG 14dBm 9dB". */
    fun spotComment(h: Heard): String =
        listOfNotNull(h.mode, h.locator.ifEmpty { null }, h.powerDbm?.let { "${it}dBm" }, h.snrDb?.let { "${it}dB" }, if (h.mode == "CW") h.text.ifEmpty { null } else null)
            .joinToString(" ").take(30).trimEnd()

    fun spotCommand(h: Heard): String = DxCluster.spotCommand(h.freqHz / 1000.0, h.call, spotComment(h))

    /** The list: newest first, one entry per station, mode and band; a station keeps its last known locator. */
    class Log(private val max: Int = 300, private val keepMs: Long = 24 * 3600_000L) {
        private val map = LinkedHashMap<String, Heard>()
        @Synchronized fun add(h: Heard): Heard {
            val old = map.remove(h.key)
            val merged = if (old == null) h else h.copy(locator = h.locator.ifEmpty { old.locator }, powerDbm = h.powerDbm ?: old.powerDbm, count = old.count + 1, spottedMs = old.spottedMs)
            map[h.key] = merged
            val it = map.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (map.size > max || h.timeMs - e.value.timeMs > keepMs) it.remove() else break }
            return merged
        }
        @Synchronized fun spotted(key: String, nowMs: Long) { map[key]?.let { map[key] = it.copy(spottedMs = nowMs) } }
        @Synchronized fun list(): List<Heard> = map.values.reversed()
        @Synchronized fun clear() = map.clear()
        /** Whether to announce [h] by itself: never the same station, mode and band twice within [everyMs]. */
        @Synchronized fun due(h: Heard, nowMs: Long, everyMs: Long = 30 * 60_000L): Boolean = (map[h.key]?.spottedMs ?: 0L).let { it == 0L || nowMs - it >= everyMs }
    }
}

/** Header of a WAV file with I / Q in its two channels (SDRconnect, SDR++, SDR#, HDSDR...). */
object IqWav {
    /** [format]: 1 = integers ([bits] 8 unsigned, 16, 24, 32), 3 = floats of 32 bits. [dataBytes] 0 = to the end of the file. */
    data class Info(val sampleRate: Int, val channels: Int, val format: Int, val bits: Int, val dataOffset: Int, val dataBytes: Long, val centreHz: Long?) {
        val frameBytes: Int get() = channels * bits / 8
        val seconds: Double get() = if (frameBytes == 0 || sampleRate == 0) 0.0 else dataBytes.toDouble() / frameBytes / sampleRate
    }

    private fun u32(b: ByteArray, o: Int): Long = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** From the first bytes of the file (a few KB are enough). Null if it is not a WAV with two channels that can be read. */
    fun parse(head: ByteArray, n: Int = head.size): Info? {
        if (n < 44) return null
        val riff = String(head, 0, 4, Charsets.US_ASCII)
        if ((riff != "RIFF" && riff != "RF64") || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var o = 12
        var rate = 0; var ch = 0; var fmt = 0; var bits = 0; var centre: Long? = null
        while (o + 8 <= n) {
            val id = String(head, o, 4, Charsets.US_ASCII)
            val size = u32(head, o + 4)
            when (id) {
                "fmt " -> if (o + 24 <= n) {
                    fmt = u16(head, o + 8); ch = u16(head, o + 10); rate = u32(head, o + 12).toInt(); bits = u16(head, o + 22)
                    if (fmt == 0xFFFE && o + 34 <= n && size >= 26) fmt = u16(head, o + 32)          // WAVE_FORMAT_EXTENSIBLE: the real format is in the sub-format
                }
                "auxi" -> if (size >= 36 && o + 8 + 36 <= n) centre = u32(head, o + 8 + 32).takeIf { it > 0 }   // SpectraVue / SDR# / HDSDR: centre frequency after two SYSTEMTIME
                "data" -> {
                    if (ch != 2 || rate <= 0 || !((fmt == 1 && bits in intArrayOf(8, 16, 24, 32)) || (fmt == 3 && bits == 32))) return null
                    return Info(rate, ch, fmt, bits, o + 8, if (size == 0L || size == 0xFFFFFFFFL) 0L else size, centre)
                }
            }
            o += 8 + ((size + 1) and 1L.inv()).toInt().coerceAtLeast(0)
            if (size > Int.MAX_VALUE) return null
        }
        return null
    }

    /** [frames] I / Q pairs of [raw] (from [offset]) as floats in -1..1, interleaved. */
    fun toFloat(info: Info, raw: ByteArray, offset: Int, frames: Int, out: FloatArray) {
        var o = offset
        for (i in 0 until frames * 2) {
            out[i] = when {
                info.format == 3 -> java.lang.Float.intBitsToFloat(u32(raw, o).toInt())
                info.bits == 8 -> ((raw[o].toInt() and 0xFF) - 127.5f) / 127.5f
                info.bits == 16 -> u16(raw, o).toShort() / 32768f
                info.bits == 24 -> (((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() shl 16))) / 8388608f
                else -> u32(raw, o).toInt() / 2147483648f
            }
            o += info.bits / 8
        }
    }
}
