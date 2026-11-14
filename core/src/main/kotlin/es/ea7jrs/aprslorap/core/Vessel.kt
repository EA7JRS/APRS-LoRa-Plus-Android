// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Own vessel data of the nautical AIS profile: the "vessel" command and event of the tracker (APRS LoRa+ MAPRS transponder and
// any tracker with the nautical AIS profile). The app edits it and the tracker stores it; the maritime call sign verified with
// MarineTraffic / ITU MARS is kept by the plotter or the app. Protocol: docs/BLE_CONTROL.md. Mirrors lib/plotter_core/vessel.
package es.ea7jrs.aprslorap.core

object Vessel {
    /** A ship type of the nautical AIS profile. [cls] is 'A', or 'C' (class B "CS") / 'O' (class B "SO"); [shipType] is the AIS type. */
    data class Type(val id: Int, val name: String, val cls: Char, val shipType: Int) {
        val classB: Boolean get() = cls == 'C' || cls == 'O'
    }

    // Same table and order as lib/GateCore ais_vessel.cpp of the tracker (ids are the "t" field of the command / event).
    val TYPES = listOf(
        Type(0, "Sailing yacht (recreational)", 'C', 36),
        Type(1, "Power boat (recreational)", 'O', 37),
        Type(2, "Cargo ship", 'A', 70),
        Type(3, "Tanker", 'A', 80),
        Type(4, "Passenger ship / ferry", 'A', 60),
        Type(5, "Fishing vessel", 'A', 30),
        Type(6, "Tug", 'A', 52),
        Type(7, "Pilot vessel", 'A', 50),
        Type(8, "High-speed craft", 'A', 40),
        Type(9, "Law enforcement", 'A', 55),
        Type(10, "Search and rescue", 'A', 51),
        Type(11, "Emergency / medical transport", 'A', 58),
        Type(12, "Military", 'A', 35),
        Type(13, "Diving vessel", 'A', 34),
        Type(14, "Sailing ship (large)", 'A', 36),
        Type(15, "Wing-in-ground / hovercraft", 'A', 20),
        Type(16, "Other ship", 'A', 90),
    )

    fun type(id: Int): Type = TYPES.getOrElse(id) { TYPES[0] }
    fun className(cls: Char): String = when (cls) { 'A' -> "Class A"; 'C' -> "Class B (CS)"; 'O' -> "Class B (SO)"; else -> "?" }

    /** 0 = none (don't change), 1 = MarineTraffic, 2 = ITU MARS. */
    enum class Verified(val code: Int) { NONE(0), MARINETRAFFIC(1), MARS(2) }

    fun verifiedName(code: Int): String = when (code) { 1 -> "MarineTraffic"; 2 -> "ITU MARS"; else -> "" }

    /** The tracker's answer to the "vessel" command / query (event "vessel"). */
    data class State(
        val classBTransponder: Boolean,      // "b": the tracker only accepts class B types (MAPRS)
        val hasProfile: Boolean,             // "ok": there is a nautical AIS profile
        val index: Int,                      // "i": beacon index of the profile
        val active: Boolean,                 // "act": that profile is the active beacon
        val callsign: String,                // "call": APRS callsign with its SSID
        val mmsi: Long,                      // "mmsi": 0 = none
        val name: String,                    // "n"
        val maritimeCall: String,            // "mc": maritime call sign of the MMSI ("" = the APRS callsign)
        val maritimeVerified: Int,           // "mcok": 0 none, 1 MarineTraffic, 2 ITU MARS
        val maritimeUnverified: Boolean,     // "mcu": sent without checking (with a warning)
        val type: Int,                       // "t"
        val cls: String,                     // "cls": "A" / "C" / "O"
        val shipType: Int,                   // "st": AIS ship type
        val lengthM: Int,                    // "len"
        val beamM: Int,                      // "beam"
        val draughtM: Double,                // "dr"
    ) {
        val maritimeToSend: String get() = maritimeCall.ifBlank { baseCall(callsign) }
    }

    fun parse(o: Map<String, Any?>): State = State(
        classBTransponder = o.bool("b") ?: false,
        hasProfile = o.bool("ok") ?: false,
        index = o.int("i") ?: -1,
        active = o.bool("act") ?: false,
        callsign = o.str("call") ?: "",
        mmsi = o.long("mmsi") ?: 0,
        name = o.str("n") ?: "",
        maritimeCall = o.str("mc") ?: "",
        maritimeVerified = o.int("mcok") ?: 0,
        maritimeUnverified = o.bool("mcu") ?: false,
        type = o.int("t") ?: 0,
        cls = o.str("cls") ?: "",
        shipType = o.int("st") ?: 0,
        lengthM = o.int("len") ?: 0,
        beamM = o.int("beam") ?: 0,
        draughtM = o.num("dr") ?: 0.0,
    )

    fun queryCommand(): String = Control.command("vessel")

    /** Command to change the vessel data. Only the maritime verification ([mcok]) 1 or 2 is sent; 0 leaves it alone. */
    fun setCommand(
        name: String, mmsi: Long, callsign: String, maritimeCall: String, type: Int,
        lengthM: Int, beamM: Int, draughtM: Double, mcok: Int = 0, unverified: Boolean? = null,
    ): String {
        val f = ArrayList<Pair<String, Any?>>()
        f.add("n" to name)
        f.add("mmsi" to mmsi)
        baseCall(callsign).takeIf { it.isNotEmpty() }?.let { f.add("call" to it) }
        f.add("mc" to maritimeCall.trim())
        if (mcok == 1 || mcok == 2) f.add("mcok" to mcok)
        unverified?.let { f.add("mcu" to it) }
        f.add("t" to type)
        f.add("len" to lengthM)
        f.add("beam" to beamM)
        f.add("dr" to draughtM)
        return Control.command("vessel", *f.toTypedArray())
    }

    /** Base callsign: upper case, no SSID, 3 to 6 letters/digits; "" if not valid. */
    fun baseCall(call: String): String {
        val c = call.trim().uppercase().substringBefore('-')
        return if (c.length in 3..6 && c.all { it.isLetterOrDigit() }) c else ""
    }

    fun validMmsi(m: Long): Boolean = m in 200_000_000L..799_999_999L
}
