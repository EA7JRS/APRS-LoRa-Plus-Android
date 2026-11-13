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
}
