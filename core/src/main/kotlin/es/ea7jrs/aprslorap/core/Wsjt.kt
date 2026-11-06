/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * WSJT-X style modes received by the app: audio (12 kHz, USB) is cut into UTC time slots, each slot is written as a WAV and
 * handed to a decoder program (FT8 / FT4: ft8_lib, native/ft8). Here: slots, the decoder's output lines, messages.
 */
object Wsjt {
    /** [program]: the decoder in lib/<abi>/ (native/ft8, native/wspr). */
    enum class Mode(val label: String, val periodMs: Long, val flag: String?, val program: String) {
        FT8("FT8", 15_000, null, "libft8dec.so"), FT4("FT4", 7_500, "-ft4", "libft8dec.so"), WSPR("WSPR", 120_000, null, "libwsprdec.so"),
    }

    /** Command line of the decoder for one slot file. */
    fun arguments(mode: Mode, programPath: String, wavPath: String, dialHz: Long?): List<String> = when (mode) {
        Mode.WSPR -> listOf(programPath, wavPath, (dialHz ?: 0L).toString())
        else -> listOf(programPath) + listOfNotNull(mode.flag) + wavPath
    }

    const val RATE = 12_000

    /** Start (ms since the epoch, UTC) of the slot that contains [ms]. */
    fun slotStart(ms: Long, periodMs: Long): Long = ms - Math.floorMod(ms, periodMs)

    /** [rfHz]: WSPR gives the frequency itself (dial + audio); FT8 / FT4 the audio frequency ([audioHz]). */
    data class Decode(val slotMs: Long, val snr: Double, val dt: Double, val audioHz: Int, val message: String, val rfHz: Long? = null, val drift: Int? = null) {
        /** Callsign-like words of the message (for highlighting own / the tracker's calls). */
        val calls: List<String> get() = message.split(' ').map { it.trim('<', '>') }.filter { Regex("^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,4}(/[A-Z0-9]+)?$").matches(it) && it.any(Char::isLetter) && !GRID.matches(it) }
    }

    private val GRID = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
    private val LINE = Regex("^\\s*(\\d{6})\\s+([+-]?\\d+(?:\\.\\d+)?)\\s+([+-]?\\d+(?:\\.\\d+)?)\\s+(\\d+)\\s+~\\s+(.*)$")

    private val WSPR_LINE = Regex("^WSPR\\s+([+-]?\\d+)\\s+([+-]?\\d+(?:\\.\\d+)?)\\s+(\\d+\\.\\d+)\\s+([+-]?\\d+)\\s+(.+)$")

    /** One line of libwsprdec.so ("WSPR snr dt freqMHz drift CALL LOC DBM"); [dialHz] 0 or null = audio only. */
    fun parseWspr(line: String, slotMs: Long, dialHz: Long?): Decode? {
        val m = WSPR_LINE.find(line.trim()) ?: return null
        val hz = Math.round(m.groupValues[3].toDouble() * 1e6)
        val dial = dialHz ?: 0L
        return Decode(slotMs, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), (hz - dial).toInt(), m.groupValues[5].trim(),
            if (dial > 0) hz else null, m.groupValues[4].toInt())
    }

    fun parse(mode: Mode, line: String, slotMs: Long, dialHz: Long?): Decode? = if (mode == Mode.WSPR) parseWspr(line, slotMs, dialHz) else parse(line, slotMs)

    /** One line of ft8_lib's decode_ft8 ("HHMMSS SNR DT FREQ ~  MESSAGE"); null for its other output. */
    fun parse(line: String, slotMs: Long): Decode? {
        val m = LINE.find(line) ?: return null
        val msg = m.groupValues[5].trim()
        if (msg.isEmpty() || msg.startsWith("Error [")) return null
        return Decode(slotMs, m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), m.groupValues[4].toInt(), msg)
    }

    /** A whole slot as 16-bit mono WAV at [RATE]. */
    fun wav(samples: ShortArray): ByteArray {
        val head = Dsp.wavHeader(RATE, samples.size * 2)
        val out = ByteArray(head.size + samples.size * 2)
        head.copyInto(out)
        var o = head.size
        for (s in samples) { out[o++] = s.toByte(); out[o++] = (s.toInt() shr 8).toByte() }
        return out
    }

    /**
     * Collects audio into slots. Feed samples with the wall-clock time of the LAST sample; a slot is handed out when it is
     * complete (only slots heard from their start).
     */
    class SlotRecorder(val mode: Mode, private val onSlot: (startMs: Long, samples: ShortArray) -> Unit) {
        private val len = (mode.periodMs * RATE / 1000).toInt()
        private var buf = ShortArray(len)
        private var fill = 0
        private var slot = -1L
        private var complete = false                                 // this slot began at (or near) its start

        fun feed(x: ShortArray, n: Int, lastSampleMs: Long) {
            for (i in 0 until n) {
                val t = lastSampleMs - (n - 1 - i) * 1000L / RATE
                val s = slotStart(t, mode.periodMs)
                if (s != slot) {
                    if (slot >= 0 && complete && fill >= len * 9 / 10) onSlot(slot, buf.copyOf(len))
                    slot = s; fill = 0; buf.fill(0)
                    complete = t - s < 200                               // joined less than 0.2 s late
                }
                if (fill < len) buf[fill++] = x[i]
            }
        }
    }
}
