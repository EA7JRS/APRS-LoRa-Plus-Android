/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Reverse Beacon Network spots (skimmers "CALL-#") as they come through a DX cluster: the comment of a skimmer spot is
 * "CW 12 dB 22 WPM CQ", "FT8 -10 dB", "RTTY 8 dB 45 BPS CQ", "CW 25 dB 22 WPM NCDXF B" or "... BEACON". RBN asks end users
 * to take its spots from a retail DX cluster, not from its own telnet nodes (reversebeacon.net, Telnet servers), so the
 * app reads them from the cluster connection of the DX cluster screen.
 */
object Rbn {
    enum class Kind(val label: String) { CQ("CQ"), BEACON("BEACON"), NCDXF("NCDXF"), DX("DX") }

    data class Hear(
        val skimmer: String, val dx: String, val freqKhz: Double, val mode: String, val snrDb: Int?, val speed: Int?, val speedUnit: String,
        val kind: Kind, val band: String?, val utc: String, val skimmerLocator: String?, val receivedMs: Long,
    )

    private val SNR = Regex("(-?\\d+)\\s*dB\\b", RegexOption.IGNORE_CASE)
    private val SPEED = Regex("\\b(\\d+)\\s*(WPM|BPS)\\b", RegexOption.IGNORE_CASE)

    fun kindOf(comment: String): Kind {
        val c = comment.uppercase()
        return when {
            Regex("\\bNCDXF\\b").containsMatchIn(c) -> Kind.NCDXF
            Regex("\\bBEACON\\b").containsMatchIn(c) -> Kind.BEACON
            Regex("\\bCQ\\b").containsMatchIn(c) -> Kind.CQ
            else -> Kind.DX
        }
    }

    /** A skimmer spot as an RBN reception, null for a spot made by a person. */
    fun of(s: DxCluster.Spot): Hear? {
        if (!s.spotter.endsWith("-#")) return null
        val speed = SPEED.find(s.comment)
        return Hear(s.spotter, s.dx, s.freqKhz, s.mode, SNR.find(s.comment)?.groupValues?.get(1)?.toIntOrNull(), speed?.groupValues?.get(1)?.toIntOrNull(),
            speed?.groupValues?.get(2)?.uppercase() ?: "", kindOf(s.comment), s.band, s.utc, s.spotterLocator, s.receivedMs)
    }

    /** Position of a station: its locator when known, else the centre of its DXCC entity in cty.dat. */
    fun position(table: Cty.Table?, call: String, locator: String?): Pair<Double, Double>? {
        locator?.let { RotorFollow.locatorToLatLon(it)?.let { p -> return p } }
        val e = table?.lookup(call.removeSuffix("-#").substringBefore('-')) ?: return null
        return e.lat to e.lon
    }

    // ------------------------------------------------------------------ alerts for skimmer spots on VHF / UHF

    /** Which spots raise an alert: none, 300 MHz and up (UHF and microwaves) or 30 MHz and up (VHF, UHF, microwaves). */
    enum class AlertRange(val label: String, val minKhz: Double) { OFF("OFF", Double.MAX_VALUE), UHF("UHF", 300_000.0), VHF("VHF+", 30_000.0) }

    /** "432.050 MHz" from 30 MHz up, "14025.0 kHz" below. */
    fun freqText(khz: Double): String =
        if (khz >= 30_000.0) String.format(java.util.Locale.US, "%.3f MHz", khz / 1000.0) else String.format(java.util.Locale.US, "%.1f kHz", khz)

    /** Minutes since the spot's "HHMM" UTC (the day before if it is ahead of [nowMs]); null if it does not read. */
    fun utcAgeMin(utc: String, nowMs: Long): Int? {
        val t = utc.trim().removeSuffix("Z")
        if (t.length != 4 || !t.all { it.isDigit() }) return null
        val spot = t.substring(0, 2).toInt() * 60 + t.substring(2).toInt()
        val now = ((nowMs / 60_000L) % 1440L).toInt()
        return (now - spot + 1440) % 1440
    }

    /** Alert text: "OH6BG hears SM6FMB on 432.050 MHz · CW · CQ · 12 dB · 20 WPM · 0830Z". */
    fun alertText(h: Hear): String =
        "${h.skimmer.removeSuffix("-#")} hears ${h.dx} on ${freqText(h.freqKhz)} · ${h.mode} · ${h.kind.label}" +
            (h.snrDb?.let { " · $it dB" } ?: "") + (h.speed?.let { " · $it ${h.speedUnit}" } ?: "") + (if (h.utc.isNotEmpty()) " · ${h.utc.removeSuffix("Z")}Z" else "")

    /**
     * Decides which skimmer spots raise an alert: in the chosen range, only stations heard about you if [onlyMe], spots no
     * older than [maxAgeMin] (the node's history when connecting does not ring), and each station once per band every
     * [repeatMin] minutes.
     */
    class Alerter(val repeatMin: Int = 30, val maxAgeMin: Int = 15) {
        private val last = HashMap<String, Long>()

        fun check(h: Hear, range: AlertRange, onlyMe: Boolean, myCall: String, nowMs: Long): Boolean {
            if (range == AlertRange.OFF || h.freqKhz < range.minKhz) return false
            if (onlyMe) {
                val me = myCall.trim().uppercase()
                if (me.isEmpty() || (h.dx != me && h.dx.substringBefore('/') != me && h.dx.substringBefore('-') != me)) return false
            }
            utcAgeMin(h.utc, nowMs)?.let { if (it > maxAgeMin) return false }
            val key = h.dx + "|" + (h.band ?: freqText(h.freqKhz))
            synchronized(last) {
                val t = last[key]
                if (t != null && nowMs - t < repeatMin * 60_000L) return false
                last[key] = nowMs
                if (last.size > 500) last.entries.removeIf { nowMs - it.value > repeatMin * 60_000L }
            }
            return true
        }
    }
}
