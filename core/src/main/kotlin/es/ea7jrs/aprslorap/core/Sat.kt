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
    /** The complete file: all the bands of the trackers (2 m / VHF, 70 cm / UHF, LoRa, GEO), CelesTrak groups and CTCSS; filtered with [filter]. */
    const val ALL_DATA_URL = "https://raw.githubusercontent.com/EA7JRS/APRS-LoRa-Plus/satdata/sats_all.txt"

    /** Bits of [SatEntry.bands]: 144-148 MHz, 420-450 MHz, 134-174 MHz (T-TWR VHF), 400-480 MHz (T-TWR UHF). */
    const val BAND_2M = 1; const val BAND_70CM = 2; const val BAND_VHF = 4; const val BAND_UHF = 8

    /** CelesTrak groups of the complete file: letter in the S| line -> name. "l" and "g" come from the category (LoRa, geostationary). */
    val GROUPS = listOf(
        "s" to "Space stations", "a" to "Amateur radio", "c" to "CubeSats", "n" to "SatNOGS", "w" to "Weather", "v" to "Brightest (visual)",
        "x" to "Science", "d" to "Education", "e" to "Engineering", "l" to "LoRa / TinyGS", "g" to "Geostationary",
    )

    /** Categories of the tracker's list (command `satcat`), in the order the tracker goes through them. */
    val CATEGORIES = listOf(0 to "All", 4 to "Favorites", 6 to "This radio", 5 to "2 m", 1 to "70 cm", 2 to "LoRa / TinyGS", 3 to "GEO / Inmarsat")
    fun categoryLabel(c: Int) = CATEGORIES.firstOrNull { it.first == c }?.second ?: "Category $c"

    data class Filtered(val text: String, val count: Int, val dropped: Int, val total: Int)

    /**
     * Keeps from a satellite file the blocks of the chosen [groups] (letters of [GROUPS]; empty = all) and always the [favorites]
     * (NORAD numbers), favorites first, up to [maxBytes] (UTF-8). Comment lines at the top are kept. Works with the old file too
     * (no groups in it: everything matches when [groups] is empty, only favorites otherwise, and LoRa / GEO by category).
     */
    fun filter(text: String, groups: String, favorites: Set<Int>, maxBytes: Int): Filtered {
        val head = StringBuilder()
        val blocks = ArrayList<List<String>>()
        var cur: ArrayList<String>? = null
        for (raw in text.lineSequence()) {
            val l = raw.trimEnd('\r')
            if (l.startsWith("S|")) { cur = arrayListOf(l); blocks.add(cur) }
            else if (cur != null) { if (l.isNotEmpty()) cur.add(l) }
            else if (l.startsWith("#")) head.append(l).append('\n')
        }
        fun rank(b: List<String>): Int {
            val f = b[0].split('|')
            val id = f.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (id in favorites) return 2
            if (groups.isEmpty()) return 1
            val cat = f.getOrNull(3) ?: ""
            val g = (f.getOrNull(4) ?: "") + (if (cat == "L") "l" else "") + (if (cat == "G") "g" else "")
            return if (groups.any { g.indexOf(it) >= 0 }) 1 else 0
        }
        val chosen = blocks.map { rank(it) to it }.filter { it.first > 0 }.sortedByDescending { it.first }
        val out = StringBuilder(head)
        var size = out.toString().toByteArray(Charsets.UTF_8).size
        var n = 0; var dropped = 0
        for ((_, b) in chosen) {
            val t = b.joinToString("\n", postfix = "\n")
            val tb = t.toByteArray(Charsets.UTF_8).size
            if (size + tb > maxBytes) { dropped++; continue }
            out.append(t); size += tb; n++
        }
        return Filtered(out.toString(), n, dropped, blocks.size)
    }

    fun favCommand(noradId: Int, on: Boolean) = Control.command("satfav", "id" to noradId, "on" to on)
    fun categoryCommand(c: Int) = Control.command("satcat", "k" to c)
    /** Band of the T-TWR's module ("VHF" / "UHF") as a [SatEntry.bands] bit. */
    fun radioBandBit(sa868Band: String?) = if (sa868Band.equals("UHF", true)) BAND_UHF else if (sa868Band.equals("VHF", true)) BAND_VHF else BAND_70CM
    fun bandsText(b: Int): String = listOfNotNull(if (b and BAND_2M != 0) "2 m" else if (b and BAND_VHF != 0) "VHF" else null,
        if (b and BAND_70CM != 0) "70 cm" else if (b and BAND_UHF != 0) "UHF" else null).joinToString(" + ")

    /** The tracker keeps the position of the phone for 2 minutes: it is sent again this often. */
    const val POSITION_EVERY_MS = 8_000L

    /** Text of a `sats.txt` the tracker would refuse, or null if it looks right (the tracker checks it again). */
    fun checkData(text: String, maxBytes: Int = Control.SAT_DATA_MAX): String? {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes < 200) return "The file is too short ($bytes bytes)"
        if (bytes > maxBytes) return "The file is too big ($bytes bytes; the tracker takes $maxBytes)"
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
    fun categoryName(c: String) = when (c) { "U" -> "70 cm"; "V" -> "2 m"; "L" -> "LoRa"; "G" -> "Geostationary"; else -> c }

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
    fun rotorSource(src: Int) = when (src) {
        0 -> "Satellite"; 1 -> "APRS station"; 2 -> "Fixed target"; 3 -> "GPredict"; 4 -> "Last heard"; 5 -> "CW beacon"; 6 -> "Sun"; 7 -> "Moon"; else -> "Source $src"
    }

    /** Compass point of an azimuth, for reading it at a glance. */
    fun compass(azDeg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val a = ((azDeg % 360.0) + 360.0) % 360.0
        return names[((a + 22.5) / 45.0).toInt() % 8]
    }
}
