/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/** What a station says its telemetry channels are (messages PARM. / UNIT. / EQNS. / BITS. addressed to itself). */
data class TelemetryDefs(
    /** Names: A1..A5 then B1..B8. */
    val parm: List<String> = emptyList(),
    /** Units, same order. */
    val unit: List<String> = emptyList(),
    /** Three numbers a, b, c per analog channel: value = a·v² + b·v + c. */
    val eqns: List<Double> = emptyList(),
    /** Eight characters: the level ('1' or '0') at which each bit counts as on. */
    val bits: String = "",
    val title: String = "",
)

/** A telemetry report ("T#seq,a1,a2,a3,a4,a5,bbbbbbbb"). */
data class TelemetryReading(val seq: String, val analog: List<Double>, val digital: String, val timeMs: Long = 0)

/** A channel ready to show. [on]: for a bit, whether it is at its active level; null for an analog channel. */
data class TelemetryValue(val name: String, val value: String, val unit: String, val on: Boolean? = null)

object Telemetry {
    /** Reads "T#005,199,000,255,073,123,01101001comment"; null if it is not a telemetry report. */
    fun parse(info: String): Pair<TelemetryReading, String>? {
        if (!info.startsWith("T#")) return null
        val f = info.substring(2).split(',')
        if (f.size < 2) return null
        val seq = f[0].trim()
        if (seq.isEmpty() || seq.length > 5) return null
        val analog = ArrayList<Double>()
        var i = 1
        while (i < f.size && analog.size < 5) {
            val v = f[i].trim().toDoubleOrNull() ?: break
            // the bit field looks like a number too: eight 0 / 1 after at least one analog value is the digital part
            if (analog.isNotEmpty() && f[i].length >= 8 && f[i].take(8).all { it == '0' || it == '1' }) break
            analog.add(v); i++
        }
        if (analog.isEmpty()) return null
        var digital = ""; var comment = ""
        if (i < f.size) {
            val rest = f.drop(i).joinToString(",")
            digital = rest.takeWhile { it == '0' || it == '1' }.take(8)
            comment = rest.drop(digital.length).trim()
        }
        return TelemetryReading(seq, analog, digital) to comment
    }

    /** True when the text of a message defines telemetry channels. */
    fun isDefinition(text: String): Boolean = text.startsWith("PARM.") || text.startsWith("UNIT.") || text.startsWith("EQNS.") || text.startsWith("BITS.")

    /** [defs] with what the message text adds; null if the text is not a definition. */
    fun merge(defs: TelemetryDefs?, text: String): TelemetryDefs? {
        val d = defs ?: TelemetryDefs()
        val body = text.drop(5)
        return when {
            text.startsWith("PARM.") -> d.copy(parm = body.split(',').map { it.trim() })
            text.startsWith("UNIT.") -> d.copy(unit = body.split(',').map { it.trim() })
            text.startsWith("EQNS.") -> d.copy(eqns = body.split(',').map { it.trim().toDoubleOrNull() ?: 0.0 })
            text.startsWith("BITS.") -> d.copy(bits = body.take(8).takeWhile { it == '0' || it == '1' }, title = body.substringAfter(',', "").trim())
            else -> null
        }
    }

    private fun num(v: Double): String =
        if (v == Math.rint(v) && Math.abs(v) < 1e9) String.format(Locale.US, "%.0f", v)
        else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /** The channels of a report with their names, units and equations; without definitions, "A1".."B8" and the raw values. */
    fun values(defs: TelemetryDefs?, r: TelemetryReading): List<TelemetryValue> {
        val out = ArrayList<TelemetryValue>()
        for ((i, raw) in r.analog.withIndex()) {
            val e = defs?.eqns
            val v = if (e != null && e.size >= i * 3 + 3) e[i * 3] * raw * raw + e[i * 3 + 1] * raw + e[i * 3 + 2] else raw
            out += TelemetryValue(defs?.parm?.getOrNull(i)?.ifEmpty { null } ?: "A${i + 1}", num(v), defs?.unit?.getOrNull(i) ?: "")
        }
        for ((i, b) in r.digital.withIndex()) {
            val name = defs?.parm?.getOrNull(5 + i)?.ifEmpty { null }
            if (defs != null && defs.parm.size > 5 && name == null) continue       // the station names its bits and this one has none
            val sense = defs?.bits?.getOrNull(i) ?: '1'
            out += TelemetryValue(name ?: "B${i + 1}", b.toString(), defs?.unit?.getOrNull(5 + i) ?: "", b == sense)
        }
        return out
    }
}

/** Position beacons sent by the phone through the tracker (the app's own callsign). */
object PhoneBeacon {
    private fun dm(v: Double, degDigits: Int, pos: Char, neg: Char): String {
        var a = Math.abs(v); var d = a.toInt(); var m = Math.round((a - d) * 6000.0) / 100.0
        if (m >= 60.0) { m = 0.0; d += 1 }
        return String.format(Locale.US, "%0${degDigits}d%05.2f%c", d, m, if (v >= 0) pos else neg)
    }

    /**
     * Information field of a plain position with messaging ("=DDMM.mmN/DDDMM.mmW>CCC/SSS/A=aaaaaa comment"): course and
     * speed (knots) only when it moves, altitude in feet when known. Null if the position is not valid.
     */
    fun encode(lat: Double, lon: Double, table: Char, code: Char, courseDeg: Int?, speedKmh: Double?, altM: Double?, comment: String): String? {
        if (lat.isNaN() || lon.isNaN() || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null
        val sb = StringBuilder("=").append(dm(lat, 2, 'N', 'S')).append(table).append(dm(lon, 3, 'E', 'W')).append(code)
        if (speedKmh != null && speedKmh >= 1.0 && courseDeg != null) {
            val c = ((courseDeg % 360) + 360) % 360
            sb.append(String.format(Locale.US, "%03d/%03d", if (c == 0) 360 else c, Math.min(999, Math.round(speedKmh / 1.852).toInt())))
        }
        if (altM != null && altM > -400 && altM < 30000) sb.append(String.format(Locale.US, "/A=%06d", Math.round(altM / 0.3048).toInt().coerceAtLeast(0)))
        val text = comment.trim().filter { it >= ' ' && it != '|' && it != '~' }.take(43)
        if (text.isNotEmpty()) { if (sb.last().isDigit() && sb.length > 20) sb.append(' '); sb.append(text) }
        return sb.toString()
    }

    /**
     * When to beacon. [fixedS] > 0: every so many seconds. 0: SmartBeaconing with the usual APRS values (the tracker's
     * "APRS standard" profile): every 30 min below 5 km/h, every 90 s from 100 km/h, in between 100·90 / speed; a turn of
     * 30° + 240 / speed sends at once, with 15 s at least between beacons.
     */
    class Timer(private val fixedS: Int = 0) {
        companion object {
            const val SLOW_S = 1800; const val SLOW_KMH = 5.0; const val FAST_S = 90; const val FAST_KMH = 100.0
            const val TURN_MIN = 30.0; const val TURN_SLOPE = 240.0; const val TURN_S = 15
        }
        private var lastMs = 0L
        private var lastCourse: Int? = null

        fun intervalS(speedKmh: Double?): Int {
            if (fixedS > 0) return fixedS
            val v = speedKmh ?: 0.0
            return when {
                v < SLOW_KMH -> SLOW_S
                v >= FAST_KMH -> FAST_S
                else -> Math.min(SLOW_S.toDouble(), FAST_KMH * FAST_S / v).toInt()
            }
        }

        /** True when a beacon is due now (the first one at once). */
        fun due(nowMs: Long, speedKmh: Double?, courseDeg: Int?): Boolean {
            if (lastMs == 0L) return true
            val dt = (nowMs - lastMs) / 1000
            if (dt >= intervalS(speedKmh)) return true
            val v = speedKmh ?: 0.0
            val was = lastCourse
            if (fixedS == 0 && v >= SLOW_KMH && courseDeg != null && was != null && dt >= TURN_S) {
                val turn = Math.abs(((courseDeg - was + 540) % 360) - 180)
                if (turn >= TURN_MIN + TURN_SLOPE / v) return true
            }
            return false
        }

        fun sent(nowMs: Long, courseDeg: Int?) { lastMs = nowMs; lastCourse = courseDeg }
    }
}
