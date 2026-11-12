/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/** An APRS weather report, in metric units (APRS sends mph, °F, hundredths of an inch and tenths of hPa). */
data class AprsWx(
    val windDirDeg: Int? = null,
    val windKmh: Double? = null,
    val gustKmh: Double? = null,
    val tempC: Double? = null,
    val rain1hMm: Double? = null,
    val rain24hMm: Double? = null,
    val rainMidnightMm: Double? = null,
    val humidityPct: Int? = null,
    val pressureHpa: Double? = null,
    val luminosityWm2: Int? = null,
    val snowCm: Double? = null,
) {
    val isEmpty: Boolean get() = listOf(windDirDeg, windKmh, gustKmh, tempC, rain1hMm, rain24hMm, rainMidnightMm, humidityPct, pressureHpa, luminosityWm2, snowCm).all { it == null }

    /** "21.5 °C · SW 12 km/h · 65 % · 1013 hPa": what fits on a line of a list or a map label. */
    fun summary(units: Int = 0): String = listOfNotNull(
        tempC?.let { String.format(Locale.US, "%.1f %s", WxCalc.tempShown(it, units), WxCalc.tempLabel(units)) },
        wind(units),
        humidityPct?.let { "$it %" },
        pressureHpa?.let { String.format(Locale.US, "%.0f hPa", it) },
        rain1hMm?.takeIf { it > 0 }?.let { String.format(Locale.US, "rain %.1f mm/h", it) },
    ).joinToString(" · ")

    /** "SW 12 km/h" (with the direction it comes from), "calm" or null. */
    fun wind(units: Int = 0): String? {
        val v = windKmh ?: return windDirDeg?.let { Geo.cardinal16(it.toDouble()) }
        if (v < 1.0) return "calm"
        val (k, label) = WxCalc.speedFactor(units)
        return (windDirDeg?.let { Geo.cardinal16(it.toDouble()) + " " } ?: "") + String.format(Locale.US, "%.0f %s", v * k, label)
    }

    /** Short label for the map: "21° 12 km/h". */
    fun mapLabel(units: Int = 0): String? = listOfNotNull(
        tempC?.let { String.format(Locale.US, "%.0f°", WxCalc.tempShown(it, units)) },
        windKmh?.let { if (it < 1.0) "calm" else WxCalc.speedFactor(units).let { (k, label) -> String.format(Locale.US, "%.0f %s", it * k, label) } },
    ).joinToString(" ").ifEmpty { null }
}

/** Weather data of APRS (APRS 1.0.1, chapter 12): in a position with the "_" symbol, in a positionless report "_MMDDhhmm" and in objects. */
object AprsWeather {
    /** The weather and the text after the weather fields (software / unit code and comment). */
    data class Parsed(val wx: AprsWx, val rest: String)

    private const val MPH = 1.609344
    private val WIND = Regex("^([0-9 .]{3})/([0-9 .]{3})")
    private val LEN = mapOf('c' to 3, 's' to 3, 'g' to 3, 't' to 3, 'r' to 3, 'p' to 3, 'P' to 3, 'h' to 2, 'b' to 5, 'L' to 3, 'l' to 3, '#' to 3)

    /**
     * Comment of a position with the weather symbol. Uncompressed positions start with the wind "ddd/sss" (direction / mph);
     * compressed ones carry the wind in the course / speed bytes: pass it in [windDirDeg] and [windKmh]. Null if there is no weather data.
     */
    fun parsePositioned(comment: String, windDirDeg: Int? = null, windKmh: Double? = null): Parsed? {
        var dir = windDirDeg
        var spd = windKmh
        var at = 0
        WIND.find(comment)?.let { m ->
            dir = number(m.groupValues[1])?.toInt()?.let { if (it in 0..360) it % 360 else null }
            spd = number(m.groupValues[2])?.let { it * MPH }
            at = m.range.last + 1
        }
        return fields(comment, at, positionless = false, AprsWx(windDirDeg = dir, windKmh = spd), windGiven = at > 0 || windKmh != null)
    }

    /** Positionless report: [body] is what follows "_MMDDhhmm" ("c220s004g005t077..."). */
    fun parsePositionless(body: String): Parsed? = fields(body, 0, positionless = true, AprsWx(), windGiven = false)

    private fun fields(s: String, start: Int, positionless: Boolean, initial: AprsWx, windGiven: Boolean): Parsed? {
        var wx = initial
        var i = start
        var speedSeen = windGiven
        while (i < s.length) {
            val c = s[i]
            val n = LEN[c] ?: break
            if (i + 1 + n > s.length) break
            val raw = s.substring(i + 1, i + 1 + n)
            val missing = raw.all { it == '.' || it == ' ' }
            val v = if (missing) null else number(raw) ?: break
            wx = when (c) {
                'c' -> wx.copy(windDirDeg = v?.toInt()?.let { if (it in 0..360) it % 360 else null })
                's' -> if (positionless || !speedSeen) { speedSeen = true; wx.copy(windKmh = v?.let { it * MPH }) } else wx.copy(snowCm = v?.let { it * 2.54 })
                'g' -> wx.copy(gustKmh = v?.let { it * MPH })
                't' -> wx.copy(tempC = v?.let { (it - 32.0) * 5.0 / 9.0 }?.takeIf { it in -80.0..70.0 })
                'r' -> wx.copy(rain1hMm = v?.let { it * 0.254 })
                'p' -> wx.copy(rain24hMm = v?.let { it * 0.254 })
                'P' -> wx.copy(rainMidnightMm = v?.let { it * 0.254 })
                'h' -> wx.copy(humidityPct = v?.toInt()?.let { if (it == 0) 100 else it }?.takeIf { it in 1..100 })
                'b' -> wx.copy(pressureHpa = v?.let { it / 10.0 }?.takeIf { it in 800.0..1100.0 })
                'L' -> wx.copy(luminosityWm2 = v?.toInt())
                'l' -> wx.copy(luminosityWm2 = v?.toInt()?.plus(1000))
                else -> wx      // '#': raw rain counter, not shown
            }
            i += 1 + n
        }
        if (wx.isEmpty) return null
        return Parsed(wx, s.substring(i).trim())
    }

    private fun number(t: String): Double? = t.trim().toDoubleOrNull()
}
