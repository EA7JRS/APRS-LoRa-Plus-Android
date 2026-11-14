// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Maritime call sign check of the MMSI (Manuel, 2026-11-14: "validated with MarineTraffic"; in the plotter and in the APRS
// LoRa+ MAPRS transponder). MarineTraffic: search by MMSI of its API (shipsearch, with the user's paid key; only knows
// vessels it has received over real AIS). If it is not there, the official register is the ITU MARS (free, no API): checked
// by hand and confirmed. The call sign holds for the checked pair MMSI + call: if either changes, it is no longer validated.
// Mirrors lib/plotter_core/mtcheck.cpp with the same test cases.
package es.ea7jrs.aprslorap.core

object MtCheck {
    const val MARS_URL = "https://www.itu.int/mmsapp/ShipStation/list"

    enum class Source(val code: Int) { NONE(0), MARINETRAFFIC(1), MARS(2) }
    enum class Result { MATCH, MISMATCH, NOT_FOUND, BAD_KEY, LIMIT, FAILED }

    /** 40 hex characters. */
    fun validKey(key: String?): Boolean = key != null && key.length == 40 && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    fun searchUrl(key: String, mmsi: Long): String =
        "https://services.marinetraffic.com/api/shipsearch/$key?mmsi=$mmsi&protocol=jsono"

    /** A-Z 0-9, at most 7. */
    private fun clean(s: String?): String =
        (s ?: "").uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(7)

    private fun hasWord(s: String, w: String) = s.uppercase().contains(w)

    /** Parses a shipsearch answer (protocol=jsono). Returns the result and the call sign MarineTraffic has, if any. */
    fun parse(httpCode: Int, body: String, call: String): Pair<Result, String> {
        if (httpCode == 429) return Result.LIMIT to ""
        if (httpCode == 401 || httpCode == 403) return Result.BAD_KEY to ""
        val v = try { Json.parse(body) } catch (e: Exception) { return Result.FAILED to "" }
        // API error: {"errors":[{"code":"...","detail":"..."}]}
        val errs = (v as? Map<*, *>)?.get("errors") as? List<*>
        if (errs != null) {
            val d = errs.filterIsInstance<Map<*, *>>().joinToString(" ") { "${it["detail"]} ${it["code"]}" }
            return when {
                hasWord(d, "KEY") -> Result.BAD_KEY
                hasWord(d, "RATE") || hasWord(d, "LIMIT") || hasWord(d, "CREDIT") -> Result.LIMIT
                hasWord(d, "NO DATA") || hasWord(d, "NOT FOUND") || hasWord(d, "NO RESULT") -> Result.NOT_FOUND
                else -> Result.FAILED
            } to ""
        }
        if (httpCode != 200) return Result.FAILED to ""
        val list = when {
            v is List<*> -> v
            v is Map<*, *> && v["DATA"] is List<*> -> v["DATA"] as List<*>
            else -> return Result.FAILED to ""
        }
        val want = clean(call)
        var any = ""
        for (item in list.filterIsInstance<Map<*, *>>()) {
            val got = clean(item["CALLSIGN"] as? String)
            if (got.isEmpty()) continue
            if (want.isNotEmpty() && got == want) return Result.MATCH to got
            if (any.isEmpty()) any = got
        }
        return if (any.isNotEmpty()) Result.MISMATCH to any else Result.NOT_FOUND to ""
    }

    fun resultText(r: Result): String = when (r) {
        Result.MATCH -> "Verified in MarineTraffic"
        Result.MISMATCH -> "MarineTraffic has another call sign for this MMSI"
        Result.NOT_FOUND -> "This MMSI is not in MarineTraffic: check it in the ITU MARS"
        Result.BAD_KEY -> "MarineTraffic key not valid"
        Result.LIMIT -> "MarineTraffic: call quota used up"
        Result.FAILED -> "No answer from MarineTraffic"
    }

    /** Checked pair: "224123456/EBXY" (call sign upper case, only A-Z 0-9). */
    fun pairKey(mmsi: Long, call: String): String = String.format("%09d/%s", mmsi, clean(call))

    fun verified(source: Int, storedPair: String?, mmsi: Long, call: String): Boolean {
        if (source != Source.MARINETRAFFIC.code && source != Source.MARS.code) return false
        val c = clean(call)
        if (c.isEmpty() || mmsi < 200_000_000L || mmsi > 799_999_999L) return false
        return storedPair == pairKey(mmsi, call)
    }

    fun sourceName(source: Int): String = when (source) {
        Source.MARINETRAFFIC.code -> "MarineTraffic"
        Source.MARS.code -> "ITU MARS"
        else -> ""
    }
}
