/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * DX cluster over telnet (DX Spider, AR-Cluster, CC Cluster, the RBN telnet servers): spot lines, mode and origin of
 * each spot, activity references (POTA, SOTA, IOTA, WWFF, DME) and the filters of the DX Cluster screen.
 */
object DxCluster {
    enum class Origin(val label: String) { HUMAN("HUMAN"), RBN_CW("RBN-CW"), RBN_DIG("RBN-DIG"), MINE("MINE") }
    enum class Group(val label: String) { CW("CW"), PHONE("PHO"), DIGITAL("DIG") }

    data class Node(val name: String, val host: String, val port: Int, val info: String = "") {
        val address: String get() = "$host:$port"
    }

    /** Nodes offered by default (EA4URE is the URE cluster); more can be added by hand. */
    val DEFAULT_NODES = listOf(
        Node("EA4URE", "ea4ure.com", 7300, "URE · DX Spider"),
        Node("EA4RCH", "dxfun.com", 8000, "DX Spider · Madrid"),
        Node("DL8LAS", "dl8las.dyndns.org", 7300, "Skimmer server"),
    )
    const val NODE_LIST_URL = "https://www.ng3k.com/misc/cluster.html"

    data class Spot(
        val spotter: String, val freqKhz: Double, val dx: String, val comment: String, val utc: String, val spotterLocator: String?,
        val mode: String, val group: Group, val origin: Origin, val tags: List<String>, val receivedMs: Long,
    ) {
        val freqHz: Long get() = Math.round(freqKhz * 1000.0)
        val band: String? get() = Spots.band(freqHz)
        val key: String get() = "$dx@" + Math.round(freqKhz)
    }

    // "DX de EA7DEF:     18075.0  3B8ABC       TNX QSO                        1830Z" (+ optional locator of the spotter)
    private val LINE = Regex(
        "^DX de\\s+([A-Za-z0-9/#\\-]+):?\\s+(\\d+(?:\\.\\d+)?)\\s+([A-Za-z0-9/]+)\\s+(.*?)\\s*(\\d{4})Z(?:\\s+([A-Ra-r]{2}\\d{2}(?:[A-Xa-x]{2})?))?\\s*$")

    // Answer to "sh/dx" of DX Spider: "  14025.0  JA1ABC       9-Nov-2026 1832Z CW 22 dB 28 WPM CQ          <EA5ABC-#>"
    private val SHOW = Regex(
        "^\\s*(\\d+(?:\\.\\d+)?)\\s+([A-Za-z0-9/]+)\\s+\\d{1,2}-[A-Za-z]{3}-\\d{4}\\s+(\\d{4})Z\\s*(.*?)\\s*<([A-Za-z0-9/#\\-]+)>\\s*$")

    /** A live spot ("DX de ...") or a line of the "sh/dx" answer; null for any other line. */
    fun parse(line: String, ownCall: String, nowMs: Long): Spot? {
        val l = line.trimEnd('\r', '\n', '\u0007')
        LINE.find(l)?.let { m ->
            return spot(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5], m.groupValues[6], ownCall, nowMs)
        }
        SHOW.find(l)?.let { m ->
            return spot(m.groupValues[5], m.groupValues[1], m.groupValues[2], m.groupValues[4], m.groupValues[3], "", ownCall, nowMs)
        }
        return null
    }

    private fun spot(spotterIn: String, freq: String, dxIn: String, commentIn: String, utc: String, loc: String, ownCall: String, nowMs: Long): Spot? {
        val spotter = spotterIn.uppercase()
        var khz = freq.toDoubleOrNull() ?: return null
        if (khz > 1e7) khz /= 1000.0                      // some nodes give Hz for microwave spots
        val dx = dxIn.uppercase()
        val comment = commentIn.trim()
        val mode = modeOf(khz, comment)
        val group = groupOf(mode)
        val own = ownCall.substringBefore('-').uppercase()
        val skimmer = spotter.endsWith("-#")
        val origin = when {
            own.isNotEmpty() && spotter.substringBefore('-') == own && skimmer -> Origin.MINE
            skimmer && group == Group.CW -> Origin.RBN_CW
            skimmer -> Origin.RBN_DIG
            else -> Origin.HUMAN
        }
        return Spot(spotter, khz, dx, comment, utc, loc.ifEmpty { null }?.uppercase(), mode, group, origin, tags(comment), nowMs)
    }

    private val MODE_WORDS = listOf(
        "FT8", "FT4", "FST4W", "FST4", "JS8", "Q65", "MSK144", "JT65", "JT9", "WSPR", "RTTY", "PSK31", "PSK63", "PSK125", "PSK",
        "OLIVIA", "SSTV", "MFSK", "THOR", "HELL", "FM", "AM", "SSB", "USB", "LSB", "CW",
    )
    private val FT8_KHZ = doubleArrayOf(1840.0, 3573.0, 5357.0, 7074.0, 10136.0, 14074.0, 18100.0, 21074.0, 24915.0, 28074.0, 50313.0, 70154.0, 144174.0)
    private val FT4_KHZ = doubleArrayOf(3575.5, 7047.5, 10140.0, 14080.0, 18104.0, 21140.0, 24919.0, 28180.0, 50318.0, 144170.0)

    // Band plan (IARU Region 1, simplified): (from, to) kHz -> mode of that segment
    private val PLAN = listOf(
        Triple(1800.0, 1838.0, "CW"), Triple(1838.0, 1843.0, "DIG"), Triple(1843.0, 2000.0, "SSB"),
        Triple(3500.0, 3570.0, "CW"), Triple(3570.0, 3600.0, "DIG"), Triple(3600.0, 4000.0, "SSB"),
        Triple(5351.5, 5354.0, "CW"), Triple(5354.0, 5366.0, "SSB"),
        Triple(7000.0, 7040.0, "CW"), Triple(7040.0, 7060.0, "DIG"), Triple(7060.0, 7300.0, "SSB"),
        Triple(10100.0, 10130.0, "CW"), Triple(10130.0, 10150.0, "DIG"),
        Triple(14000.0, 14070.0, "CW"), Triple(14070.0, 14112.0, "DIG"), Triple(14112.0, 14350.0, "SSB"),
        Triple(18068.0, 18095.0, "CW"), Triple(18095.0, 18111.0, "DIG"), Triple(18111.0, 18168.0, "SSB"),
        Triple(21000.0, 21070.0, "CW"), Triple(21070.0, 21151.0, "DIG"), Triple(21151.0, 21450.0, "SSB"),
        Triple(24890.0, 24915.0, "CW"), Triple(24915.0, 24931.0, "DIG"), Triple(24931.0, 24990.0, "SSB"),
        Triple(28000.0, 28070.0, "CW"), Triple(28070.0, 28190.0, "DIG"), Triple(28190.0, 28225.0, "CW"), Triple(28225.0, 29510.0, "SSB"), Triple(29510.0, 29700.0, "FM"),
        Triple(50000.0, 50100.0, "CW"), Triple(50100.0, 50300.0, "SSB"), Triple(50300.0, 50400.0, "DIG"), Triple(50400.0, 50500.0, "CW"), Triple(51000.0, 54000.0, "FM"),
        Triple(144000.0, 144150.0, "CW"), Triple(144150.0, 144400.0, "SSB"), Triple(144400.0, 144500.0, "CW"), Triple(144500.0, 146000.0, "FM"),
        Triple(430000.0, 432150.0, "FM"), Triple(432000.0, 432100.0, "CW"), Triple(432100.0, 432400.0, "SSB"), Triple(432400.0, 440000.0, "FM"),
    )

    /** Mode of a spot: from its comment first, then the FT8 / FT4 frequencies, then the band plan. */
    fun modeOf(khz: Double, comment: String): String {
        val words = comment.uppercase(Locale.US).split(Regex("[^A-Z0-9]+")).toSet()
        for (w in MODE_WORDS) if (w in words) return when (w) { "USB", "LSB" -> "SSB"; else -> w }
        if (FT8_KHZ.any { kotlin.math.abs(khz - it) <= 3.0 }) return "FT8"
        if (FT4_KHZ.any { kotlin.math.abs(khz - it) <= 2.0 }) return "FT4"
        for ((a, b, m) in PLAN) if (khz >= a && khz < b) return if (m == "DIG") "DIG" else m
        return "SSB"
    }

    fun groupOf(mode: String): Group = when (mode) {
        "CW" -> Group.CW
        "SSB", "FM", "AM" -> Group.PHONE
        else -> Group.DIGITAL
    }

    /** Mode to put on the radio / SDR for this spot (LSB below 10 MHz, USB above; data modes in USB). */
    fun rigMode(spot: Spot): String = when (spot.group) {
        Group.CW -> "CW"
        Group.PHONE -> when (spot.mode) { "FM" -> "FM"; "AM" -> "AM"; else -> if (spot.freqKhz < 10000) "LSB" else "USB" }
        Group.DIGITAL -> "USB"
    }

    private val POTA = Regex("\\b[A-Z0-9]{1,4}-\\d{4,5}\\b")
    private val SOTA = Regex("\\b[A-Z0-9]{1,4}/[A-Z]{2}-\\d{3}\\b")
    private val IOTA = Regex("\\b(?:AF|AN|AS|EU|NA|OC|SA)-?\\d{3}\\b")
    private val WWFF = Regex("\\b[A-Z0-9]{1,4}FF-\\d{4}\\b")
    private val DME = Regex("\\bDME\\b|\\bDME\\s*\\d{5}\\b")

    /** Activity references found in a comment: POTA, SOTA, IOTA, WWFF, DME. */
    fun tags(comment: String): List<String> {
        val c = comment.uppercase(Locale.US)
        val l = ArrayList<String>()
        if ("POTA" in c || POTA.containsMatchIn(c) && !WWFF.containsMatchIn(c)) l.add("POTA")
        if ("SOTA" in c || SOTA.containsMatchIn(c)) l.add("SOTA")
        if ("IOTA" in c || IOTA.containsMatchIn(c)) l.add("IOTA")
        if ("WWFF" in c || WWFF.containsMatchIn(c)) l.add("WWFF")
        if (DME.containsMatchIn(c)) l.add("DME")
        return l
    }

    val ACTIVITIES = listOf("ANY", "POTA", "SOTA", "IOTA", "WWFF", "DME")
    val BANDS = listOf("ALL", "HF", "VHF+", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm", "23cm")

    data class Filter(
        val origins: Set<Origin> = Origin.entries.toSet(), val band: String = "ALL", val group: Group? = null, val activity: String = "ANY",
        val text: String = "",
    ) {
        fun passes(s: Spot): Boolean {
            if (s.origin !in origins) return false
            if (group != null && s.group != group) return false
            if (activity != "ANY" && activity !in s.tags) return false
            when (band) {
                "ALL" -> {}
                "HF" -> if (s.freqKhz >= 30000) return false
                "VHF+" -> if (s.freqKhz < 30000) return false
                else -> if (s.band != band) return false
            }
            if (text.isNotBlank()) {
                val t = text.trim().uppercase(Locale.US)
                if (t !in s.dx && t !in s.comment.uppercase(Locale.US)) return false
            }
            return true
        }
    }

    /** True if the node is asking for the callsign ("login:", "Please enter your call:"...). */
    fun isLoginPrompt(text: String): Boolean {
        val t = text.trimEnd().lowercase(Locale.US)
        return t.endsWith("login:") || t.endsWith("call:") || t.endsWith("callsign:") || t.endsWith("your call") ||
            t.endsWith("call?") || t.endsWith("login :")
    }

    /** Command that announces a spot to the cluster. */
    fun spotCommand(khz: Double, call: String, comment: String): String =
        String.format(Locale.US, "DX %.1f %s %s", khz, call.uppercase(Locale.US), comment.take(30)).trimEnd()

    fun formatKhz(khz: Double): String {
        val t = String.format(Locale.US, "%.1f", khz)
        val i = t.substringBefore('.')
        val d = t.substringAfter('.')
        return (if (i.length > 3) i.dropLast(3) + " " + i.takeLast(3) else i) + "." + d
    }
}

/**
 * Country file cty.dat (AD1C, www.country-files.com): DXCC entity of a callsign by its longest prefix, with the position
 * of the entity to work out the beam heading. Longitudes in the file are positive to the west; [Entity.lon] is east-positive.
 */
object Cty {
    const val URL = "https://www.country-files.com/cty/cty.dat"

    data class Entity(val name: String, val cqz: Int, val ituz: Int, val continent: String, val lat: Double, val lon: Double, val prefix: String)

    class Table(private val prefixes: Map<String, Entity>, private val exact: Map<String, Entity>, val entities: Int) {
        fun lookup(callIn: String): Entity? {
            val call = callIn.uppercase(Locale.US).trim()
            exact[call]?.let { return it }
            val base = homeCall(call)
            exact[base]?.let { return it }
            for (n in base.length downTo 1) prefixes[base.substring(0, n)]?.let { return it }
            return null
        }

        val size: Int get() = prefixes.size
    }

    private val SUFFIXES = setOf("P", "M", "MM", "AM", "QRP", "A", "B", "LH", "R", "T", "J", "E", "N", "S", "W", "X")

    /** Part of a portable callsign that decides the entity: "EA8/DL1ABC" -> "EA8", "DL1ABC/P" -> "DL1ABC", "DL1ABC/EA8" -> "EA8". */
    fun homeCall(call: String): String {
        val parts = call.split('/').filter { it.isNotEmpty() && it !in SUFFIXES && !(it.length == 1 && it[0].isDigit()) }
        if (parts.isEmpty()) return call
        if (parts.size == 1) return parts[0]
        return parts.minByOrNull { it.length }!!
    }

    fun parse(text: String): Table {
        val prefixes = HashMap<String, Entity>()
        val exact = HashMap<String, Entity>()
        var count = 0
        val records = text.replace("\r", "").split(';')
        for (rec in records) {
            val r = rec.trim()
            if (r.isEmpty()) continue
            val head = r.substringBefore('\n')
            val f = head.split(':').map { it.trim() }
            if (f.size < 8) continue
            val lat = f[4].toDoubleOrNull() ?: continue
            val lonW = f[5].toDoubleOrNull() ?: continue
            val ent = Entity(f[0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3], lat, -lonW, f[7].removePrefix("*"))
            count++
            val list = r.substringAfter('\n', "").replace("\n", "")
            for (tok in list.split(',')) {
                var t = tok.trim()
                if (t.isEmpty()) continue
                var e = ent
                Regex("<(-?[\\d.]+)/(-?[\\d.]+)>").find(t)?.let { m ->
                    val la = m.groupValues[1].toDoubleOrNull(); val lo = m.groupValues[2].toDoubleOrNull()
                    if (la != null && lo != null) e = e.copy(lat = la, lon = -lo)
                }
                Regex("\\((\\d+)\\)").find(t)?.let { m -> e = e.copy(cqz = m.groupValues[1].toInt()) }
                Regex("\\[(\\d+)]").find(t)?.let { m -> e = e.copy(ituz = m.groupValues[1].toInt()) }
                Regex("\\{([A-Z]{2})}").find(t)?.let { m -> e = e.copy(continent = m.groupValues[1]) }
                t = t.replace(Regex("\\(.*?\\)|\\[.*?]|<.*?>|\\{.*?}|~.*?~"), "")
                if (t.startsWith("=")) exact[t.substring(1)] = e else prefixes[t] = e
            }
            prefixes.putIfAbsent(ent.prefix, ent)
        }
        return Table(prefixes, exact, count)
    }

    /** Short and long path from (lat, lon) to the entity of [call]: bearing SP, bearing LP, km SP, km LP. */
    data class Path(val entity: Entity, val shortDeg: Int, val longDeg: Int, val shortKm: Int, val longKm: Int)

    /** (The locator at the end of a cluster line is the spotter's, not the DX station's: the entity position is used.) */
    fun path(table: Table?, call: String, myLat: Double?, myLon: Double?): Path? {
        if (myLat == null || myLon == null) return null
        val e = table?.lookup(call) ?: return null
        val b = Math.round(Geo.bearingDeg(myLat, myLon, e.lat, e.lon)).toInt() % 360
        val d = Geo.distanceKm(myLat, myLon, e.lat, e.lon)
        return Path(e, b, (b + 180) % 360, Math.round(d).toInt(), Math.round(40030.0 - d).toInt())
    }
}
