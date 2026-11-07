/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a rotor can follow besides the tracker's own pointing (same rules as include/rotor_follow.h of the tracker firmware):
 * an APRS station (aimed where it is now, from the speed and course of its last position), the last station heard with a
 * callsign filter, and a CW beacon that sends its Maidenhead locator. Azimuth / elevation are computed from the tracker's GPS.
 */
object RotorFollow {
    /** Tracker sources (field `src` of `tgt`, `rot` and `targets`). 3 = external program, never stored. */
    const val SRC_SAT = 0; const val SRC_APRS_TARGET = 1; const val SRC_FIXED = 2; const val SRC_EXTERNAL = 3
    const val SRC_LAST_HEARD = 4; const val SRC_CW_BEACON = 5
    val TRACKER_SOURCES = listOf(SRC_SAT, SRC_APRS_TARGET, SRC_FIXED, SRC_LAST_HEARD, SRC_CW_BEACON)

    private const val EARTH_KM = 6371.0                      // the same sphere as the tracker (sat_core.cpp targetLook)

    data class Look(val azDeg: Double, val elDeg: Double, val km: Double)

    /** Moving station: where it is now, [dtS] seconds after its last position (at most [maxS]); null = stopped or no data. */
    fun predict(lat: Double, lon: Double, speedKmh: Double?, courseDeg: Double?, dtS: Double, maxS: Double, minSpeedKmh: Double = 3.0): Pair<Double, Double>? {
        if (speedKmh == null || courseDeg == null || speedKmh < minSpeedKmh || speedKmh > 1500 || dtS <= 0 || courseDeg < 0 || courseDeg > 360) return null
        val t = minOf(dtS, maxS)
        val d = speedKmh * t / 3600.0 / EARTH_KM
        val brg = Math.toRadians(courseDeg); val la1 = Math.toRadians(lat); val lo1 = Math.toRadians(lon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(brg))
        val lo2 = lo1 + atan2(sin(brg) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return Math.toDegrees(la2) to ((Math.toDegrees(lo2) + 540.0) % 360.0 - 180.0)
    }

    /** Azimuth, elevation (earth curvature with standard refraction, k = 4/3) and range from the observer to a point. */
    fun look(obsLat: Double, obsLon: Double, obsAltM: Double, lat: Double, lon: Double, altM: Double): Look {
        val r = EARTH_KM
        val la1 = Math.toRadians(obsLat); val la2 = Math.toRadians(lat); val dlo = Math.toRadians(lon - obsLon)
        var az = Math.toDegrees(atan2(sin(dlo) * cos(la2), cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dlo)))
        if (az < 0) az += 360.0
        val a = cos(la2) * sin(dlo)
        val b = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dlo)
        val c = sin(la1) * sin(la2) + cos(la1) * cos(la2) * cos(dlo)
        val d = atan2(sqrt(a * a + b * b), c) * r
        val dh = (altM - obsAltM) / 1000.0
        if (d < 0.02) return Look(0.0, if (dh >= 0) 90.0 else -90.0, sqrt(d * d + dh * dh))
        return Look(az, Math.toDegrees(atan2(dh - d * d / (2.0 * r * 4.0 / 3.0), d)), sqrt(d * d + dh * dh))
    }

    /** Maidenhead locator (4, 6 or 8 characters) -> centre of the square. */
    fun locatorToLatLon(loc: String): Pair<Double, Double>? {
        val l = loc.uppercase()
        if (l.length != 4 && l.length != 6 && l.length != 8) return null
        if (l[0] !in 'A'..'R' || l[1] !in 'A'..'R' || !l[2].isDigit() || !l[3].isDigit()) return null
        var lon = (l[0] - 'A') * 20.0 - 180.0 + (l[2] - '0') * 2.0
        var lat = (l[1] - 'A') * 10.0 - 90.0 + (l[3] - '0') * 1.0
        var w = 2.0; var h = 1.0
        if (l.length >= 6) {
            if (l[4] !in 'A'..'X' || l[5] !in 'A'..'X') return null
            w /= 24.0; h /= 24.0
            lon += (l[4] - 'A') * w; lat += (l[5] - 'A') * h
        }
        if (l.length == 8) {
            if (!l[6].isDigit() || !l[7].isDigit()) return null
            w /= 10.0; h /= 10.0
            lon += (l[6] - '0') * w; lat += (l[7] - '0') * h
        }
        return (lat + h / 2.0) to (lon + w / 2.0)
    }

    fun looksLikeCall(t: String): Boolean =
        t.length in 3..10 && t.all { it.isLetterOrDigit() || it == '/' } && t.any { it.isDigit() } && t.any { it.isLetter() } && locatorToLatLon(t) == null

    data class Beacon(val call: String, val locator: String, val lat: Double, val lon: Double)

    /** Last callsign + locator pair in decoded CW ("VVV DE ED7YAA IM76HR"); the callsign may be missing. */
    fun parseCwBeacon(text: String): Beacon? {
        val w = text.uppercase().split(' ', '\n', '\r').filter { it.isNotEmpty() && it.length <= 11 }
        for (i in w.indices.reversed()) {
            val pos = locatorToLatLon(w[i]) ?: continue
            var call = ""
            for (j in i - 1 downTo maxOf(1, i - 6)) if (w[j - 1] == "DE" && looksLikeCall(w[j])) { call = w[j]; break }
            if (call.isEmpty()) for (j in i - 1 downTo maxOf(0, i - 4)) if (looksLikeCall(w[j])) { call = w[j]; break }
            if (call.isEmpty() && i + 1 < w.size && looksLikeCall(w[i + 1])) call = w[i + 1]
            return Beacon(call, w[i], pos.first, pos.second)
        }
        return null
    }

    /** Callsign filter: "EA7JRS" any SSID, "EA7JRS-9" only that one, "EA7*" prefix; empty = all. */
    fun callMatches(call: String, filter: String): Boolean {
        val tokens = filter.uppercase().split(',', ' ', ';').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val c = call.uppercase()
        val base = c.substringBefore('-')
        return tokens.any { t ->
            when {
                t.endsWith("*") -> c.startsWith(t.dropLast(1))
                t.contains('-') -> c == t
                else -> base == t
            }
        }
    }

    /**
     * Station to follow from the tracker's heard list (newest first): [call] if given, otherwise the newest with position that
     * passes [filter] and is not older than [maxAgeMin]. [sinceListS] = seconds since the list arrived. Returns the station,
     * where to aim (estimated when moving and [predict]) and whether it is estimated.
     */
    data class Aim(val call: String, val lat: Double, val lon: Double, val altM: Double, val estimated: Boolean)

    fun aimAtStation(list: List<HeardStation>, call: String, filter: String, maxAgeMin: Int, predict: Boolean, predictMaxS: Int, sinceListS: Double): Aim? {
        val st = if (call.isNotBlank()) list.firstOrNull { it.call.equals(call.trim(), ignoreCase = true) && it.lat != null && it.lon != null }
        else list.firstOrNull { it.lat != null && it.lon != null && it.ageS + sinceListS <= maxAgeMin * 60.0 && callMatches(it.call, filter) }
        st ?: return null
        val lat = st.lat!!; val lon = st.lon!!
        val dt = (st.posAgeS ?: st.ageS) + sinceListS
        val p = if (predict) predict(lat, lon, st.speedKmh?.toDouble(), st.course?.toDouble(), dt, predictMaxS.toDouble()) else null
        return Aim(st.call, p?.first ?: lat, p?.second ?: lon, (st.altitudeM ?: 0).toDouble(), p != null)
    }
}
