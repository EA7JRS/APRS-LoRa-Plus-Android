/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * S-meter reading (IARU Region 1 technical recommendation R.1): S9 = -73 dBm below 30 MHz and -93 dBm from 30 MHz up,
 * 6 dB per S unit; above S9 the excess in dB ("S9+20").
 */
object SMeter {
    /** [s] 0..9; [overDb] dB above S9 (only when s = 9). */
    data class Reading(val s: Int, val overDb: Int, val dbm: Double? = null) {
        val label: String get() = if (overDb > 0) "S9+$overDb" else "S$s"
        /** Lit segments of a bar with 9 S segments plus 6 over S9 (+10 ... +60 dB). */
        val segments: Int get() = if (overDb > 0) 9 + min(6, ceil(overDb / 10.0).toInt()) else s
    }

    const val SEGMENTS = 15

    fun s9Dbm(freqHz: Long): Double = if (freqHz >= 30_000_000L) -93.0 else -73.0

    fun fromDbm(dbm: Double, freqHz: Long): Reading {
        val diff = dbm - s9Dbm(freqHz)
        if (diff >= 0) return Reading(9, diff.roundToInt(), dbm)
        return Reading((9 + floor(diff / 6.0).toInt()).coerceAtLeast(0), 0, dbm)
    }

    /**
     * Uncalibrated S-meter for the app's own receiver (RTL-SDR, SDRconnect, SpyServer): [dbfs] is the channel power relative to
     * the ADC full scale (0 = full scale), not an absolute dBm — these receivers have no calibrated reference. S9 is taken at
     * [s9Dbfs] and 6 dB per S point, so the needle moves with the signal instead of pinning to the top. [dbfs] above full scale
     * never happens; 0 dBFS reads S9+40 (overload). Not frequency-dependent: there is nothing to calibrate against.
     */
    fun fromDbfs(dbfs: Double, s9Dbfs: Double = -40.0): Reading {
        val diff = dbfs - s9Dbfs
        if (diff >= 0) return Reading(9, diff.roundToInt().coerceAtMost(60), null)
        return Reading((9 + floor(diff / 6.0).toInt()).coerceAtLeast(0), 0, null)
    }

    /** CAT S-meter as the rotor box sends it: 0..9, then 10 = S9+10, 11 = S9+20...; negative = unknown. */
    fun fromCat(s: Int): Reading? = when {
        s < 0 -> null
        s <= 9 -> Reading(s, 0)
        else -> Reading(9, (s - 9) * 10)
    }
}

/**
 * APRS symbols drawn from the aprs.fi symbol set (github.com/hessu/aprs-symbols): three sprite sheets of 16 x 6 symbols,
 * 0 = primary table '/', 1 = alternate table '\', 2 = overlay characters, all in the order '!' (0x21) ... '~' (0x7E).
 * A table character other than '/' or '\' is an overlay: the symbol of the alternate table with that character on top.
 */
object AprsSymbols {
    const val COLUMNS = 16
    const val ROWS = 6

    data class Cell(val sheet: Int, val col: Int, val row: Int)
    data class Symbol(val base: Cell, val overlay: Cell?)

    private fun cell(sheet: Int, c: Char): Cell? {
        if (c !in '!'..'~') return null
        val i = c - '!'
        return Cell(sheet, i % COLUMNS, i / COLUMNS)
    }

    fun of(table: Char?, code: Char?): Symbol? {
        if (table == null || code == null) return null
        val base = cell(if (table == '/') 0 else 1, code) ?: return null
        val overlay = if (table == '/' || table == '\\') null
            else if (table.isDigit() || table in 'A'..'Z' || table in 'a'..'j') cell(2, table) else null
        return Symbol(base, overlay)
    }

    /** "/>" style text (as the tracker's `heard` list sends it). */
    fun of(text: String?): Symbol? = if (text == null || text.length < 2) null else of(text[0], text[1])

    private val PRIMARY = mapOf(
        '!' to "Police", '#' to "Digipeater", '$' to "Phone", '%' to "DX cluster", '&' to "HF gateway", '\'' to "Small aircraft",
        '(' to "Mobile satellite station", '*' to "Snowmobile", '+' to "Red Cross", ',' to "Boy Scouts", '-' to "House", '.' to "X",
        '/' to "Red dot", ':' to "Fire", ';' to "Campground", '<' to "Motorcycle", '=' to "Railroad engine", '>' to "Car",
        '?' to "File server", '@' to "Hurricane", 'A' to "Aid station", 'B' to "BBS", 'C' to "Canoe", 'E' to "Eyeball",
        'F' to "Tractor", 'G' to "Grid square", 'H' to "Hotel", 'I' to "TCP/IP", 'K' to "School", 'L' to "PC user",
        'M' to "MacAPRS", 'N' to "NTS station", 'O' to "Balloon", 'P' to "Police", 'R' to "Recreational vehicle",
        'S' to "Space shuttle", 'T' to "SSTV", 'U' to "Bus", 'V' to "ATV", 'W' to "Weather service site", 'X' to "Helicopter",
        'Y' to "Yacht", 'Z' to "WinAPRS", '[' to "Human / jogger", '\\' to "Triangle (DF)", ']' to "Mailbox", '^' to "Large aircraft",
        '_' to "Weather station", '`' to "Dish antenna", 'a' to "Ambulance", 'b' to "Bicycle", 'c' to "Incident command post",
        'd' to "Fire department", 'e' to "Horse", 'f' to "Fire truck", 'g' to "Glider", 'h' to "Hospital", 'i' to "IOTA",
        'j' to "Jeep", 'k' to "Truck", 'l' to "Laptop", 'm' to "Mic-E repeater", 'n' to "Node", 'o' to "EOC", 'p' to "Dog",
        'q' to "Grid square", 'r' to "Repeater", 's' to "Ship", 't' to "Truck stop", 'u' to "Truck (18 wheeler)", 'v' to "Van",
        'w' to "Water station", 'x' to "X-APRS", 'y' to "Yagi at QTH", 'z' to "Shelter",
    )
    private val ALTERNATE = mapOf(
        '!' to "Emergency", '#' to "Digipeater", '&' to "Gateway", '\'' to "Crash site", '-' to "House (HF)", '0' to "Circle",
        '>' to "Car", 'A' to "Box", 'D' to "Depot", 'E' to "Smoke", 'H' to "Haze", 'I' to "Rain shower", 'K' to "Kenwood",
        'L' to "Lighthouse", 'N' to "Navigation buoy", 'O' to "Rocket", 'P' to "Parking", 'Q' to "Earthquake", 'R' to "Restaurant",
        'S' to "Satellite", 'T' to "Thunderstorm", 'U' to "Sunny", 'V' to "VORTAC", 'W' to "NWS site", 'X' to "Pharmacy",
        '[' to "Wall cloud", '^' to "Aircraft", '_' to "Weather site", '`' to "Rain", 'a' to "ARRL / ARES", 'c' to "CD triangle",
        'h' to "Store", 'j' to "Work zone", 'k' to "SUV", 'm' to "Value sign", 'n' to "Triangle", 'o' to "Small circle",
        'r' to "Restrooms", 's' to "Boat", 'u' to "Truck", 'v' to "Van", 'w' to "Flooding", 'y' to "Skywarn",
    )

    /** Short English name of the symbol ("Car", "LoRa gateway"...). */
    fun describe(table: Char?, code: Char?): String {
        if (table == null || code == null) return "No symbol"
        if (table == '/') return PRIMARY[code] ?: "Symbol /$code"
        val base = ALTERNATE[code] ?: "Symbol \\$code"
        if (table == '\\') return base
        return when {
            code == '&' && table == 'L' -> "LoRa iGate"
            code == '&' && table == 'I' -> "iGate"
            code == '&' && table == 'R' -> "RX-only iGate"
            code == '#' && table == 'L' -> "LoRa digipeater"
            else -> "$base ($table)"
        }
    }
}

/** Course, speed and altitude carried in the comment of an uncompressed position ("088/036.../A=001234"). */
object AprsExtra {
    data class Info(val courseDeg: Int?, val speedKmh: Double?, val altitudeM: Double?)

    private val CSE_SPD = Regex("^(\\d{3})/(\\d{3})")
    private val ALT = Regex("/A=(-?\\d{6})")

    fun parse(comment: String?): Info {
        if (comment.isNullOrEmpty()) return Info(null, null, null)
        var course: Int? = null
        var speed: Double? = null
        CSE_SPD.find(comment)?.let { m ->
            val c = m.groupValues[1].toInt()
            val s = m.groupValues[2].toInt()
            if (c in 1..360) { course = c % 360; speed = s * 1.852 }
            else if (c == 0 && s == 0) { course = null; speed = 0.0 }
        }
        val alt = ALT.find(comment)?.groupValues?.get(1)?.toDouble()?.let { it * 0.3048 }
        return Info(course, speed, alt)
    }

    /** The comment without the course / speed and altitude fields (what is worth showing to the user). */
    fun cleanComment(comment: String?): String {
        if (comment.isNullOrEmpty()) return ""
        return ALT.replace(CSE_SPD.replace(comment, ""), "").trim()
    }
}
