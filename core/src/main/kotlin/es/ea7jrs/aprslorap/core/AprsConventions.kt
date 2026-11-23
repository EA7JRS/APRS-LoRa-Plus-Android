/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * APRS conventions that are not in the frame format: addressees meant for everybody, what each SSID is usually for and
 * which digipeater paths are in use today (APRS Protocol Reference 1.0.1 chapter 14 and the "New n-N paradigm" of aprs.org).
 * They are conventions: nothing here refuses a packet.
 */
object AprsConventions {
    /** BLN0..BLN9, BLNA..BLNZ (announcements), BLNnGROUP, NWS-..., SKY..., ALL, QST, CQ: for everybody, never acknowledged. */
    fun isGroupAddressee(addressee: String): Boolean {
        val a = addressee.trim().uppercase()
        return a.startsWith("BLN") || a.startsWith("NWS") || a.startsWith("SKY") || a == "ALL" || a == "QST" || a == "CQ"
    }

    /** What the group addressee is, for the title of its conversation. */
    fun groupName(addressee: String): String {
        val a = addressee.trim().uppercase()
        return when {
            a.startsWith("BLN") && a.length >= 4 && a[3].isDigit() -> if (a.length > 4) "Group bulletin " + a.substring(4) else "Bulletin"
            a.startsWith("BLN") -> "Announcement"
            a.startsWith("NWS") -> "Weather service"
            a.startsWith("SKY") -> "SKYWARN"
            else -> "To everybody"
        }
    }

    private val SSID = listOf(
        "main fixed station", "additional station", "digipeater", "additional station", "additional station", "other networks (D-STAR, 3G...)",
        "special activity (satellite, camping...)", "handheld", "boat, motorhome or second mobile", "main mobile", "internet side (iGate, EchoLink...)",
        "balloon, aircraft or spacecraft", "one-way tracker", "weather station", "trucker", "additional station",
    )

    /** The usual use of an SSID (0..15); "" outside that range. */
    fun ssidMeaning(ssid: Int): String = SSID.getOrElse(ssid) { "" }
    fun ssidMeaning(call: String): String = Ax25Address.parse(call)?.let { ssidMeaning(it.ssid) } ?: ""

    /** A -12 is a one-way tracker by convention: it will not read a message. */
    fun oneWay(call: String): Boolean = Ax25Address.parse(call)?.ssid == 12

    enum class PathLevel { OK, NOTE, BAD }

    /**
     * What to say about the digipeater path of the frames we send. OK: empty, WIDE1-1, WIDE1-1,WIDE2-1, WIDE2-1, WIDE2-2 (fixed).
     * NOTE: three hops, or direct callsigns. BAD: RELAY / WIDE / TRACE of the old paradigm, WIDEn-N with n > 2 or N > n, more than
     * three hops in all, or something that is not a callsign.
     */
    fun pathAdvice(path: String): Pair<PathLevel, String> {
        val parts = path.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return PathLevel.OK to "No digipeaters: only stations that hear you directly (and their iGates)."
        var hops = 0
        for (p in parts) {
            val a = Ax25Address.parse(p) ?: return PathLevel.BAD to "\"$p\" is not a callsign or an alias."
            val wide = Regex("WIDE(\\d)").matchEntire(a.call)
            when {
                a.call == "RELAY" || a.call == "TRACE" || a.call == "WIDE" || a.call.startsWith("TRACE") ->
                    return PathLevel.BAD to "${a.call} belongs to the old paradigm: digipeaters no longer answer to it. Use WIDE1-1,WIDE2-1."
                wide != null -> {
                    val n = wide.groupValues[1].toInt()
                    if (a.ssid == 0) return PathLevel.BAD to "$p asks for no hop: write it as WIDE$n-$n or less."
                    if (n > 2 || a.ssid > n) return PathLevel.BAD to "$p floods the network: nothing above WIDE2-2 is accepted today."
                    hops += a.ssid
                }
                else -> hops += 1
            }
        }
        return when {
            hops > 3 -> PathLevel.BAD to "$hops hops: more than WIDE1-1,WIDE2-2 only loads the channel of others."
            hops == 3 -> PathLevel.NOTE to "3 hops: for remote places. On the road WIDE1-1,WIDE2-1 is enough; at home, WIDE2-2 or less."
            parts.size >= 2 && parts[0] != "WIDE1-1" && parts.any { it.startsWith("WIDE1") } -> PathLevel.NOTE to "WIDE1-1 only works as the first element."
            else -> PathLevel.OK to "$hops hop${if (hops == 1) "" else "s"}: fine."
        }
    }
}
