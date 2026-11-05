/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Amateur radio callsign check against the RadioID.net database (DMR ID / CCS7): a callsign with a DMR ID or CCS7 ID has
 * been checked against a licence by RadioID. The app transmits only with a callsign found there.
 * API: GET https://database.radioid.net/api/dmr/user/?callsign=EA7JRS -> {"count":n,"results":[{"id":2147001,"callsign":"EA7JRS",
 * "fname":"...","surname":"...","city":"...","state":"...","country":"Spain"}, ...]} (one result per DMR ID).
 */
object CallsignCheck {
    const val API = "https://database.radioid.net/api/dmr/user/?callsign="

    /** ITU-style callsign: prefix (1-2 letters, digit+letter or letter+digit), a digit, suffix ending in a letter. */
    private val CALL = Regex("^([A-Z]{1,2}|[0-9][A-Z]|[A-Z][0-9])[0-9][A-Z0-9]{0,3}[A-Z]$")

    /** "ea7jrs-7" -> "EA7JRS"; null if it does not look like an amateur callsign (NOCALL, N0CALL, letters only...). */
    fun base(call: String): String? {
        val b = call.trim().uppercase().substringBefore('-')
        if (b == "N0CALL" || b == "NOCALL" || !CALL.matches(b)) return null
        return b
    }

    fun url(base: String): String = API + java.net.URLEncoder.encode(base, "UTF-8")

    data class Result(val found: Boolean, val ids: List<Long>, val name: String, val country: String)

    /** Reads the RadioID answer; found only if a result has exactly this callsign (the API may also match others). */
    fun parse(json: String, base: String): Result? {
        val o = Json.parseObject(json) ?: return null
        val hits = o.objList("results").filter { it.str("callsign")?.trim()?.equals(base, ignoreCase = true) == true }
        if (hits.isEmpty()) return Result(false, emptyList(), "", "")
        val first = hits[0]
        val name = listOfNotNull(first.str("fname"), first.str("surname")).joinToString(" ").trim()
        return Result(true, hits.mapNotNull { it.long("id") }, name, first.str("country") ?: "")
    }
}
