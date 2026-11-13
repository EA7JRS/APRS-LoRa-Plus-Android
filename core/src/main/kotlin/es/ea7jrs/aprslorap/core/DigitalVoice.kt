/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Digital voice with dsd-fme (lwvmobile, ISC / GPL-2.0 parts) and mbelib, run by the app as a separate program
 * (native/dsd/build.sh): FM discriminator audio in (TCP, 48 kHz, 16-bit), voice out (stdout, 8 kHz 16-bit, two channels =
 * the two TDMA slots), call information on stderr. The app passes no keys: encrypted voice stays muted.
 */
object DigitalVoice {
    /** Modes offered, with the dsd-fme option. */
    val MODES = linkedMapOf(
        "Auto" to "-fa", "DMR" to "-fs", "D-STAR" to "-fd", "YSF / C4FM" to "-fy", "P25 phase 1" to "-f1", "P25 phase 2" to "-f2",
        "NXDN 4800" to "-fi", "NXDN 9600" to "-fn", "dPMR" to "-fm", "M17" to "-fz",
    )

    fun flag(mode: String): String = MODES[mode] ?: "-fa"

    /** dsd-fme writes two channels (slot 1, slot 2) for the TDMA-capable modes, one otherwise. */
    fun channels(mode: String): Int = when (flag(mode)) { "-fa", "-fs", "-f2", "-fx", "-ft" -> 2; else -> 1 }

    fun arguments(mode: String, port: Int): List<String> = listOf(flag(mode), "-i", "tcp:127.0.0.1:$port", "-o", "-")

    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    /** A line of dsd-fme's log without terminal colours. */
    fun clean(line: String): String = line.replace(ansi, "").replace("\r", "").trimEnd()

    /** Lines worth showing: synchronisation, calls, identities, texts, positions. */
    fun interesting(line: String): Boolean = line.contains("Sync:") || line.contains("SRC") || line.contains("TGT") || line.contains("TG ") ||
        line.contains("Talkgroup", true) || line.contains("RID") || line.contains("DATA:") || line.contains("TEXT") || line.contains("GPS", true)

    /** An M17 station in dsd-fme's log: its callsign, and its position when the transmission carries one. */
    data class M17Heard(val call: String, val lat: Double? = null, val lon: Double? = null)

    private val M17_SRC = Regex("CAN:\\s*\\d+.*\\bSRC:\\s+([A-Z0-9/.\\-]{3,9})")
    private val M17_GPS = Regex("GPS:\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)")

    /**
     * Follows the log for M17: " CAN: 0 DST: BROADCAST SRC: EA7JRS" names the station and a later " GPS: (37.27, -6.94);"
     * is its position. Gives each thing once as it comes; the same callsign again only after [repeatMs].
     */
    class M17Log(private val repeatMs: Long = 60_000L) {
        private var src = ""
        private var lastCall = ""; private var lastMs = 0L
        fun feed(line: String, nowMs: Long): M17Heard? {
            M17_SRC.find(line)?.let { m ->
                val c = m.groupValues[1].trimEnd('.', '-', '/')
                if (c == "UNKNOWN" || c == "RESERVED" || !c.any(Char::isDigit) || !c.any(Char::isLetter)) { src = ""; return null }
                src = c
                if (c == lastCall && nowMs - lastMs < repeatMs) return null
                lastCall = c; lastMs = nowMs
                return M17Heard(c)
            }
            M17_GPS.find(line)?.let { m ->
                val la = m.groupValues[1].toDoubleOrNull() ?: return null; val lo = m.groupValues[2].toDoubleOrNull() ?: return null
                if (src.isEmpty() || la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return null
                return M17Heard(src, la, lo)
            }
            return null
        }
    }
}
