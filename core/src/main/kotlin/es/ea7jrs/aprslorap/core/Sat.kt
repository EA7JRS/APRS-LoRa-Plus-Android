/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/** Helpers for the SAT mode of the tracker: data file, phone position, texts. Protocol: docs/BLE_CONTROL.md. */
object Sat {
    /** Daily list of satellites and transmitters published by the firmware repository (workflow satdata.yml). */
    const val DEFAULT_DATA_URL = "https://raw.githubusercontent.com/EA7JRS/APRS-LoRa-Plus/satdata/sats.txt"

    /** The tracker keeps the position of the phone for 2 minutes: it is sent again this often. */
    const val POSITION_EVERY_MS = 8_000L

    /** Text of a `sats.txt` the tracker would refuse, or null if it looks right (the tracker checks it again). */
    fun checkData(text: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes < 200) return "The file is too short ($bytes bytes)"
        if (bytes > Control.SAT_DATA_MAX) return "The file is too big ($bytes bytes; the tracker takes ${Control.SAT_DATA_MAX})"
        if (text.lineSequence().none { it.startsWith("S|") }) return "It is not a satellite file (no line starting with S|)"
        return null
    }

    /** Number of satellites in a `sats.txt` (lines starting with S|). */
    fun countSatellites(text: String): Int = text.lineSequence().count { it.startsWith("S|") }

    /** `pos` command: phone position for SAT mode. Out-of-range values give null. */
    fun position(lat: Double, lon: Double, altM: Double?, accM: Float?): String? {
        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        val fields = ArrayList<Pair<String, Any?>>()
        fields.add("lat" to round(lat, 6)); fields.add("lon" to round(lon, 6))
        if (altM != null && !altM.isNaN()) fields.add("alt" to Math.round(altM).toInt())
        if (accM != null && !accM.isNaN()) fields.add("acc" to Math.round(accM).toInt())
        return Control.command("pos", *fields.toTypedArray())
    }

    private fun round(v: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10.0 }
        return Math.round(v * f) / f
    }

    /** Category of the list: U 70 cm, L LoRa, G geostationary. */
    fun categoryName(c: String) = when (c) { "U" -> "70 cm"; "L" -> "LoRa"; "G" -> "Geostationary"; else -> c }

    /** "in 12 min 05 s", "now", "5 min ago" for a Unix time against [nowS]. */
    fun relative(timeS: Long, nowS: Long): String {
        val d = timeS - nowS
        val a = Math.abs(d)
        val text = if (a >= 3600) String.format(Locale.US, "%d h %02d min", a / 3600, (a % 3600) / 60)
                   else if (a >= 60) String.format(Locale.US, "%d min %02d s", a / 60, a % 60)
                   else "$a s"
        return if (a < 2) "now" else if (d > 0) "in $text" else "$text ago"
    }

    /** What the pass is doing at [nowS]: waiting for AOS, in view, or over. */
    fun passText(aos: Long?, los: Long?, nowS: Long): String? {
        if (aos == null || los == null) return null
        return when {
            nowS < aos -> "Next pass starts ${relative(aos, nowS)}"
            nowS <= los -> "In view, ends ${relative(los, nowS)}"
            else -> "Pass over"
        }
    }

    /** Name of the source of the rotor's target. */
    fun rotorSource(src: Int) = when (src) { 0 -> "Satellite"; 1 -> "APRS station"; 2 -> "Fixed target"; else -> "Source $src" }

    /** Compass point of an azimuth, for reading it at a glance. */
    fun compass(azDeg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val a = ((azDeg % 360.0) + 360.0) % 360.0
        return names[((a + 22.5) / 45.0).toInt() % 8]
    }
}
