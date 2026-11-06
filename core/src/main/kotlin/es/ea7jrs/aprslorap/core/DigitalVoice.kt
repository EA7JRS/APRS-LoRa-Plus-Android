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
}
