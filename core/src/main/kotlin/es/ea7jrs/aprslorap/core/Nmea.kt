/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.abs

/**
 * NMEA 0183. The tracker sends its own sentences over BLE (GNSS, MDA/XDR and APRS stations as AIS !AIVDM) on boards with NMEA
 * output; the app passes them on as they are. For boards without it, [Nmea.rmc] and [Nmea.gga] build the basic position
 * sentences from the "gnss" event, so other apps still get the tracker's position.
 */
object Nmea {
    fun checksum(body: String): Int = body.fold(0) { acc, c -> acc xor c.code }

    /** Adds "$", "*CS" and CRLF to a sentence body such as "GPRMC,...". */
    fun sentence(body: String, start: Char = '$'): String = String.format(Locale.US, "%c%s*%02X\r\n", start, body, checksum(body))

    /** True if the line ("$...*CS" or "!...*CS", with or without CRLF) has a correct checksum. */
    fun valid(line: String): Boolean {
        val l = line.trimEnd('\r', '\n')
        if (l.length < 4 || (l[0] != '$' && l[0] != '!')) return false
        val star = l.lastIndexOf('*')
        if (star < 1 || star + 3 != l.length) return false
        val cs = l.substring(star + 1).toIntOrNull(16) ?: return false
        return checksum(l.substring(1, star)) == cs
    }

    /** Sentence type without the talker: "$GPRMC,..." -> "RMC", "!AIVDM,..." -> "VDM". */
    fun type(line: String): String = if (line.length >= 6) line.substring(3, 6) else ""

    private fun latText(lat: Double): String {
        val a = abs(lat); val d = a.toInt(); val m = (a - d) * 60.0
        return String.format(Locale.US, "%02d%07.4f,%c", d, m, if (lat >= 0) 'N' else 'S')
    }

    private fun lonText(lon: Double): String {
        val a = abs(lon); val d = a.toInt(); val m = (a - d) * 60.0
        return String.format(Locale.US, "%03d%07.4f,%c", d, m, if (lon >= 0) 'E' else 'W')
    }

    /** "2026-11-05T07:45:09Z" -> ("074509.00", "051126"); null if the text is not in that form. */
    fun utcParts(utc: String?): Pair<String, String>? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(utc ?: return null) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return Pair("$h$mi$s.00", "$d$mo${y.substring(2)}")
    }

    fun rmc(g: Gnss): String? {
        if (g.lat == null || g.lon == null) return null
        val (time, date) = utcParts(g.utc) ?: Pair("", "")
        val knots = (g.speedKmh ?: 0.0) / 1.852
        val body = String.format(
            Locale.US, "GPRMC,%s,%c,%s,%s,%.1f,%.1f,%s,,,%c",
            time, if (g.fix) 'A' else 'V', latText(g.lat), lonText(g.lon), knots, g.courseDeg ?: 0.0, date, if (g.fix) 'A' else 'N',
        )
        return sentence(body)
    }

    fun gga(g: Gnss): String? {
        if (g.lat == null || g.lon == null) return null
        val time = utcParts(g.utc)?.first ?: ""
        val body = String.format(
            Locale.US, "GPGGA,%s,%s,%s,%d,%02d,%.1f,%.1f,M,,M,,",
            time, latText(g.lat), lonText(g.lon), if (g.fix) 1 else 0, g.satellites ?: 0, g.hdop ?: 99.9, g.altitudeM ?: 0.0,
        )
        return sentence(body)
    }
}
