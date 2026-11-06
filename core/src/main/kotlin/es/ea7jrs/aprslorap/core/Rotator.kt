/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Hamlib rotctld protocol ("NET rotctl", model 2), the same subset as the tracker's own WiFi server (include/rot_proto.h of
 * the firmware): P / p / S / K / R / _ / q and the long forms (\set_pos, \get_pos, \dump_state...).
 * - [Session]: the phone as a server for Look4Sat, GPredict...: their azimuth / elevation go to the tracker's rotor (`rotext`).
 * - [setPosLine] / [parseReply]: the phone as a client of a rotctld on the network (a rotor controller, a Raspberry Pi...),
 *   fed with the pointing the tracker computes (event `rot`).
 */
object RotCtl {
    const val DEFAULT_PORT = 4533

    interface Rot {
        fun setPos(azDeg: Double, elDeg: Double): Boolean
        /** Real position of the rotor if known, else the last one asked for. */
        fun getPos(): Pair<Double, Double>
        fun park(): Boolean
        fun info(): String
    }

    class Session(private val rot: Rot) {
        private val line = StringBuilder()

        /** Bytes from the client; the answers are added to [out]. True = close the connection ("q"). */
        fun feed(text: String, out: StringBuilder): Boolean {
            var close = false
            for (c in text) {
                when (c) {
                    '\n' -> { if (handle(line.toString(), out)) close = true; line.setLength(0) }
                    '\r' -> {}
                    else -> if (line.length < 120) line.append(c) else line.setLength(0)
                }
            }
            return close
        }

        private fun rprt(out: StringBuilder, code: Int) { out.append("RPRT ").append(if (code == 0) 0 else -code).append('\n') }

        private fun handle(raw: String, out: StringBuilder): Boolean {
            if (raw.isEmpty()) return false
            val cmd: String
            val args: List<String>
            if (raw[0] == '\\') {
                val t = raw.substring(1).split(' ').filter { it.isNotEmpty() }
                if (t.isEmpty()) return false
                cmd = t[0]; args = t.drop(1)
            } else {
                if (raw[0] == '+' || raw[0] == ';' || raw[0] == '|') { rprt(out, E_NIMPL); return false }
                args = raw.substring(1).split(' ').filter { it.isNotEmpty() }
                cmd = when (raw[0]) {
                    'P' -> "set_pos"; 'p' -> "get_pos"; 'S' -> "stop"; 'K' -> "park"; 'R' -> "reset"; '_' -> "get_info"
                    'q', 'Q' -> "quit"
                    else -> { rprt(out, E_NIMPL); return false }
                }
            }
            when (cmd) {
                "set_pos" -> {
                    val az = args.getOrNull(0)?.toDoubleOrNull()
                    val el = args.getOrNull(1)?.toDoubleOrNull()
                    if (az == null || el == null || !az.isFinite() || !el.isFinite() || az < -360 || az > 720 || el < -90 || el > 180) { rprt(out, E_INVAL); return false }
                    rprt(out, if (rot.setPos(wrap360(az), minOf(el, 90.0))) 0 else E_REJECTED)
                }
                "get_pos" -> { val (az, el) = rot.getPos(); out.append(String.format(Locale.US, "%.6f\n%.6f\n", az, el)) }
                "stop", "reset" -> rprt(out, 0)
                "park" -> rprt(out, if (rot.park()) 0 else E_REJECTED)
                "get_info" -> out.append(rot.info()).append('\n')
                "dump_state" -> out.append("1\n0\n0.000000\n360.000000\n0.000000\n90.000000\n0\n6\n")
                "quit" -> { rprt(out, 0); return true }
                else -> rprt(out, E_NIMPL)
            }
            return false
        }
    }

    private const val E_INVAL = 1
    private const val E_NIMPL = 4
    private const val E_REJECTED = 9

    fun wrap360(a: Double): Double { var x = a % 360.0; if (x < 0) x += 360.0; return x }

    /** Client: the command that points a rotctld. */
    fun setPosLine(azDeg: Double, elDeg: Double): String = String.format(Locale.US, "P %.1f %.1f\n", wrap360(azDeg), elDeg.coerceIn(0.0, 90.0))

    /** Client: "RPRT n" -> n; null if the line is something else. */
    fun parseReply(line: String): Int? = line.trim().takeIf { it.startsWith("RPRT") }?.substring(4)?.trim()?.toIntOrNull()

    /** Signed difference b - a in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double { var d = (b - a) % 360.0; if (d > 180) d -= 360.0; if (d <= -180) d += 360.0; return d }

    /** True if the rotor should be sent a new position: moved at least [stepDeg] in azimuth or elevation. */
    fun moved(lastAz: Double?, lastEl: Double?, az: Double, el: Double, stepDeg: Double): Boolean =
        lastAz == null || lastEl == null || abs(angleDiff(lastAz, az)) >= stepDeg || abs(el - lastEl) >= stepDeg
}

/** The phone's own sensors: tilt for the rotor mount (`att`), barometer. */
object PhoneMath {
    /**
     * Pitch and roll of the phone lying face up on the rotor mount, top edge to the front, from the gravity vector of Android
     * (m/s², device axes: x right, y top, z out of the screen; flat = (0, 0, +9.8)). Pitch + = front up, roll + = right side down
     * (the convention of the tracker's `att`). Null when the phone is not near face up (more than 60° tilted).
     */
    fun tilt(x: Double, y: Double, z: Double): Pair<Double, Double>? {
        val g = sqrt(x * x + y * y + z * z)
        if (g < 5.0 || z < g * 0.5) return null
        val pitch = Math.toDegrees(atan2(y, sqrt(x * x + z * z)))
        val roll = Math.toDegrees(atan2(-x, sqrt(y * y + z * z)))
        return pitch to roll
    }

    /** Altitude (m) from the pressure (hPa) and the sea-level pressure [qnh] (international standard atmosphere). */
    fun baroAltitude(hPa: Double, qnh: Double = 1013.25): Double = 44330.0 * (1.0 - (hPa / qnh).pow(1.0 / 5.255))

    /** Sea-level pressure (QNH, hPa) from the pressure (hPa) at a known altitude (m). */
    fun qnh(hPa: Double, altM: Double): Double = hPa / (1.0 - altM / 44330.0).pow(5.255)
}

/**
 * RepeaterBook (repeaterbook.com). Token in the header X-RB-App-Token; redirects are not followed (the API asks it). The
 * documentation does not list the coordinates among the fields: they are read if present, otherwise the user types them.
 * RepeaterBook (repeaterbook.com): search repeaters to make them fixed rotor targets (`tgt`). The API needs a personal token
 * (`rbuapp_...`, asked for at repeaterbook.com, free for personal use) and an identifying User-Agent with a contact e-mail;
 * personal use only, no lists or maps for other people, results not stored. "Data courtesy of RepeaterBook.com".
 */
object RepeaterBook {
    const val ATTRIBUTION = "Data courtesy of RepeaterBook.com"
    const val TOKEN_PAGE = "https://www.repeaterbook.com/user/api_apps.php"
    private const val NA = "https://www.repeaterbook.com/api/export.php"
    private const val ROW = "https://www.repeaterbook.com/api/exportROW.php"

    data class Repeater(
        val callsign: String, val freqMhz: Double?, val inputMhz: Double?, val tone: String, val city: String, val region: String,
        val country: String, val lat: Double?, val lon: Double?, val use: String, val status: String,
    ) {
        val freqHz: Long get() = Math.round((freqMhz ?: 0.0) * 1e6)
        val hasPosition: Boolean get() = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
        /** Name for the tracker (20 characters): callsign and output frequency. */
        val targetName: String get() = (callsign + (freqMhz?.let { String.format(Locale.US, " %.3f", it) } ?: "")).take(20)
    }

    /** Search address. [northAmerica] = USA, Canada, Mexico (export.php); the rest of the world uses exportROW.php. */
    fun url(northAmerica: Boolean, callsign: String = "", city: String = "", country: String = "", freqMhz: String = ""): String {
        val q = ArrayList<String>()
        fun add(k: String, v: String) { if (v.isNotBlank()) q.add(k + "=" + URLEncoder.encode(v.trim(), "UTF-8")) }
        add("callsign", callsign.uppercase()); add("city", city); add("country", country); add("frequency", freqMhz.replace(',', '.'))
        return (if (northAmerica) NA else ROW) + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    private fun Map<String, Any?>.text(k: String): String = when (val v = this[k]) { is String -> v.trim(); is Number -> v.toString(); else -> "" }
    private fun Map<String, Any?>.dbl(k: String): Double? = when (val v = this[k]) { is Number -> v.toDouble(); is String -> v.trim().replace(',', '.').toDoubleOrNull(); else -> null }

    /** The answer of the API: the repeaters, or an error message. */
    fun parse(text: String): Result<List<Repeater>> {
        val o = try { Json.parseObject(text.trim()) } catch (e: Exception) { null }
            ?: return Result.failure(IllegalStateException("not a RepeaterBook answer: " + text.trim().take(80)))
        val msg = o.text("message").ifEmpty { o.text("error") }.ifEmpty { o.text("code") }
        val results = o.objList("results")
        if (results.isEmpty() && msg.isNotEmpty()) return Result.failure(IllegalStateException(msg))
        return Result.success(results.map { r ->
            Repeater(
                r.text("Callsign"), r.dbl("Frequency"), r.dbl("Input Freq"), r.text("PL"), r.text("Nearest City"),
                r.text("Region").ifEmpty { r.text("State") }, r.text("Country"), r.dbl("Lat") ?: r.dbl("Latitude") ?: r.dbl("lat"), r.dbl("Long") ?: r.dbl("Longitude") ?: r.dbl("lng") ?: r.dbl("lon"), r.text("Use"), r.text("Operational Status"),
            )
        })
    }
}
