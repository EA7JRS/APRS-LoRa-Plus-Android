// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// FM voice mode of the LilyGO T-TWR (tracker mode 11): channel state (event "fm"), memories (event "fmmems"), tones and the APRS
// frequency specification used for QSY ("146.940MHz T100 -060", APRS 1.2 freqspec). The tracker parses and tunes; the app only has
// to recognise a beacon that announces a frequency and send its comment with the "qsy" command.
package es.ea7jrs.aprslorap.core

import java.util.Locale

object FmTone {
    const val OFF = 0; const val TONE = 1; const val TSQL = 2; const val DCS = 3; const val BURST = 4
    val MODES = listOf("No tone", "TONE", "TSQL", "DCS", "1750 Hz")
    val CTCSS = listOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273,
        1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995,
        2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541)
    val DCS_CODES = listOf(23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74, 114, 115, 116, 122, 125, 131, 132, 134, 143, 145,
        152, 155, 156, 162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265, 266, 271,
        274, 306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371, 411, 412, 413, 423, 431, 432, 445, 446, 452, 454,
        455, 462, 464, 465, 466, 503, 506, 516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664, 703, 712,
        723, 731, 732, 734, 743, 754)
    val STEPS_HZ = listOf(5000, 6250, 10000, 12500, 15000, 20000, 25000, 50000, 100000)

    fun text(mode: Int, tenths: Int, dcs: Int, inv: Boolean): String = when (mode) {
        TONE -> String.format(Locale.US, "T %.1f", tenths / 10.0)
        TSQL -> String.format(Locale.US, "TSQL %.1f", tenths / 10.0)
        DCS -> String.format(Locale.US, "DCS %03d%s", dcs, if (inv) "I" else "N")
        BURST -> "1750 Hz"
        else -> "no tone"
    }
}

/** A channel of the radio (VFO or memory). Frequencies in Hz; [shift] -1 / 0 / +1. */
data class FmChannel(
    val rxHz: Long, val txHz: Long, val shift: Int, val offsetHz: Long, val toneMode: Int, val tone: Int, val dcs: Int, val dcsInv: Boolean,
    val wide: Boolean, val high: Boolean, val name: String = "", val index: Int = -1,
) {
    val freqText: String get() = mhz(rxHz)
    val shiftText: String get() = if (shift == 0) "simplex" else String.format(Locale.US, "%c%.3f MHz", if (shift > 0) '+' else '-', offsetHz / 1e6)
    val toneText: String get() = FmTone.text(toneMode, tone, dcs, dcsInv)
    val summary: String get() = "$shiftText · $toneText · ${if (wide) "wide" else "narrow"} · ${if (high) "high" else "low"} power"

    companion object {
        fun mhz(hz: Long): String = String.format(Locale.US, "%.4f", hz / 1e6)
        fun parse(o: Map<String, Any?>): FmChannel = FmChannel(
            o.long("f") ?: 0, o.long("tx") ?: (o.long("f") ?: 0), o.int("sh") ?: 0, o.long("ofs") ?: 0, o.int("tm") ?: 0, o.int("t") ?: 885,
            o.int("d") ?: 23, o.bool("inv") ?: false, o.bool("w") ?: false, o.bool("hp") ?: false, o.str("n") ?: "", o.int("i") ?: -1,
        )
    }
}

/** Event "fm": what the radio is doing now. [arts]: 0 off, 1 searching, 2 in range, 3 out of range; [scan]: 0 off, 1 scanning, 2 holding. */
data class FmState(
    val channel: FmChannel, val memory: Boolean, val memIndex: Int, val stepHz: Long, val squelch: Int, val squelchManual: Int,
    val squelchAuto: Boolean, val noiseReduction: Int, val edit: Int, val busy: Boolean, val ptt: Boolean, val txSeconds: Int, val rssi: Int,
    val scan: Int, val arts: Int, val eaiSeconds: Int, val aprsBeacon: Boolean, val qsyInBeacon: Boolean, val toneOk: Boolean,
    val speakerFromEsp: Boolean, val notice: String?,
    val sat: FmSat? = null,                         // the VFO follows a satellite (Doppler, uplink and CTCSS of the transmitter)
) {
    val artsText: String get() = when (arts) { 1 -> "ARTS: searching"; 2 -> "ARTS: IN RANGE"; 3 -> "ARTS: OUT OF RANGE"; else -> "" }

    companion object {
        val EDITS = listOf("frequency", "tone", "offset", "squelch")
        fun parse(o: Map<String, Any?>) = FmState(
            FmChannel.parse(o), o.bool("m") ?: false, o.int("i") ?: 0, o.long("step") ?: 12500, o.int("sq") ?: 1, o.int("sqm") ?: 1,
            o.bool("sqa") ?: true, o.int("nr") ?: 0, o.int("edit") ?: 0, o.bool("busy") ?: false, o.bool("ptt") ?: false, o.int("txs") ?: 0,
            o.int("rssi") ?: 0, o.int("scan") ?: 0, o.int("arts") ?: 0, o.int("eai") ?: 0, o.bool("aprs") ?: true, o.bool("qsyb") ?: true,
            o.bool("tok") ?: true, o.bool("spk") ?: false, o.str("note"),
            o.obj("sat")?.let { FmSat.parse(it) },
        )
    }
}

/** Satellite followed by the FM VFO of the T-TWR (object "sat" of the "fm" event). Frequencies in Hz; [listenUplink]: the downlink is on
 *  another band, the radio listens to the uplink; [txOk] false = it cannot transmit now ([why]). */
data class FmSat(
    val name: String, val noradId: Int, val tx: Int, val rxHz: Long, val upHz: Long, val dopplerHz: Int, val fineHz: Int, val dopplerOk: Boolean,
    val listenUplink: Boolean, val aprs: Boolean, val toneHz: Double, val sent: Int, val heard: Int, val azDeg: Double?, val elDeg: Double?,
    val txOk: Boolean, val why: String?,
) {
    companion object {
        fun parse(o: Map<String, Any?>) = FmSat(
            o.str("n") ?: "?", o.int("id") ?: 0, o.int("tx") ?: 0, o.long("f") ?: 0, o.long("up") ?: 0, o.int("dop") ?: 0, o.int("fine") ?: 0,
            o.bool("dok") ?: false, o.bool("xup") ?: false, o.bool("aprs") ?: false, o.num("tone") ?: 0.0, o.int("sent") ?: 0, o.int("heard") ?: 0,
            o.num("az"), o.num("el"), o.bool("txok") ?: false, o.str("why"),
        )
    }
}

object FmQsy {
    // frequency in the first 10 bytes of the comment ("146.940MHz", "146.82 MHz") or in an object name ("146.94-R")
    private val inComment = Regex("""^\s*(\d{3}\.\d{2}\d?)( ?)[Mm][Hh][Zz]""")
    private val inName = Regex("""^(\d{3}\.\d{2}\d?)""")

    /** MHz announced by a station (comment first, then the object / station name), or null. */
    fun announcedMHz(name: String?, comment: String?): Double? {
        comment?.let { c -> inComment.find(c)?.let { m -> if (m.groupValues[2].isEmpty() || m.groupValues[1].length == 6) return m.groupValues[1].toDoubleOrNull() } }
        name?.let { n -> inName.find(n)?.let { return it.groupValues[1].toDoubleOrNull() } }
        return null
    }

    /** True if the T-TWR band can tune it (VHF 134-174 or UHF 400-480 MHz module). */
    fun inBand(mhz: Double, uhf: Boolean) = if (uhf) mhz in 400.0..480.0 else mhz in 134.0..174.0
}

/** Broadcast receiver on the tracker's I2C (RDA5807M, Si4703, Si4735): event "bcast". Frequencies in kHz; [band] 0 FM, 1 MW, 2 SW, 3 LW. */
data class BcastState(
    val chip: String, val on: Boolean, val band: Int, val kHz: Long, val volume: Int, val muted: Boolean, val mono: Boolean,
    val rssi: Int, val snr: Int, val stereo: Boolean, val seeking: Boolean, val station: String, val radioText: String, val pi: Int,
    val clock: String?, val bands: List<Int>, val presets: List<Long>,
) {
    val freqText: String get() = freqText(kHz)

    companion object {
        val BANDS = listOf("FM", "MW", "SW", "LW")
        fun freqText(kHz: Long): String = if (kHz >= 64000) String.format(Locale.US, "%.2f MHz", kHz / 1000.0) else "$kHz kHz"
        fun parse(o: Map<String, Any?>) = BcastState(
            o.str("chip") ?: "none", o.bool("on") ?: false, o.int("band") ?: 0, o.long("f") ?: 0, o.int("vol") ?: 8, o.bool("mute") ?: false,
            o.bool("mono") ?: false, o.int("rssi") ?: 0, o.int("snr") ?: 0, o.bool("st") ?: false, o.bool("seek") ?: false,
            (o.str("ps") ?: "").trim(), (o.str("rt") ?: "").trim(), o.int("pi") ?: 0, o.str("ct"),
            (o["bands"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: listOf(0),
            (o["presets"] as? List<*>)?.map { (it as? Number)?.toLong() ?: 0L } ?: emptyList(),
        )
    }
}
