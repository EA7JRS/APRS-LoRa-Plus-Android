/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Client side of EA7JRS/Wefax, the weather facsimile receiver that runs on a computer of the local network: the charts it
 * has received, what it is doing, its stations and schedule, and its VOACAP predictions (the table of its own stations and
 * any other circuit to where that receiver is). The app only reads; it changes nothing there.
 *
 * Wefax serves plain HTTP on the local network (port 8081). The requests are written here and sent over a plain socket by
 * the app, as it does with rigctl or gpsd.
 */
object Wefax {
    const val DEFAULT_PORT = 8081

    // ---------------------------------------------------------------- HTTP, the little that is needed
    /** A host of the local network: a name or an IPv4 address, nothing else. */
    fun validHost(host: String): Boolean = host.length in 1..80 && Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?").matches(host)

    /** The request for a path ("/api/charts?limit=50"). HTTP/1.0: the server answers and closes. */
    fun request(host: String, port: Int, path: String): ByteArray =
        "GET $path HTTP/1.0\r\nHost: $host:$port\r\nUser-Agent: APRS-LoRa-Plus-Android\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    class Response(val status: Int, val contentType: String, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = status in 200..299
    }

    /** Splits what the server sent into status and body. Null when it is not an HTTP answer. */
    fun parseResponse(raw: ByteArray): Response? {
        var end = -1
        for (i in 0..raw.size - 4) if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() && raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()) { end = i; break }
        if (end < 0) return null
        val head = String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n")
        val status = Regex("HTTP/1\\.[01] (\\d{3})").find(head.firstOrNull() ?: return null)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        var type = ""; var length = -1
        for (h in head.drop(1)) {
            val k = h.substringBefore(':').trim().lowercase(); val v = h.substringAfter(':', "").trim()
            if (k == "content-type") type = v
            if (k == "content-length") length = v.toIntOrNull() ?: -1
        }
        val from = end + 4
        val n = if (length in 0..(raw.size - from)) length else raw.size - from
        // a body shorter than announced is a cut answer
        if (length > raw.size - from) return null
        return Response(status, type, raw.copyOfRange(from, from + n))
    }

    // ---------------------------------------------------------------- what the receiver has
    data class Chart(val id: Long, val receivedMs: Long, val station: String, val title: String, val khz: Double, val ioc: Int, val lpm: Int,
                     val width: Int, val height: Int, val ended: String, val offsetHz: Double, val clockPpm: Double, val phased: Boolean, val keep: Boolean) {
        val complete: Boolean get() = ended == "stop"
    }

    /** "2026-11-22T04:30:00Z" to milliseconds; null when it is not that. */
    fun isoMs(s: String): Long? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z").matchEntire(s) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured.toList().map { it.toInt() }
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || se > 60) return null
        // days from the civil date (Howard Hinnant's algorithm)
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong() * 146097 + doe - 719468
        return ((days * 24 + h) * 60 + mi) * 60_000L + se * 1000L
    }
    private operator fun <T> List<T>.component6(): T = this[5]

    fun parseCharts(json: String): List<Chart> {
        val list = try { Json.parse(json) as? List<*> } catch (e: Json.ParseException) { null } ?: return emptyList()
        return list.mapNotNull { it as? Map<*, *> }.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST") val o = raw as Map<String, Any?>
            val id = o.long("id") ?: return@mapNotNull null
            Chart(id, isoMs(o.str("received") ?: "") ?: 0L, o.str("station") ?: "", o.str("title") ?: "", o.num("khz") ?: 0.0, o.int("ioc") ?: 576,
                o.int("lpm") ?: 120, o.int("width") ?: 0, o.int("height") ?: 0, o.str("ended") ?: "", o.num("offset_hz") ?: 0.0, o.num("clock_ppm") ?: 0.0,
                o.bool("phased") ?: true, o.bool("keep") ?: false)
        }
    }

    fun chartPath(id: Long) = "/chart/$id.png"

    data class Status(val state: String, val alive: Boolean, val tunedKhz: Double?, val decoder: String, val lines: Int, val station: String, val title: String,
                      val nextStartMs: Long?, val nextStation: String, val nextTitle: String, val nextKhz: Double?, val nextWhy: String, val nextReliability: Double?) {
        val receiving: Boolean get() = alive && decoder == "chart"
    }

    /** /api/status is {"name":{"value":"..","at":unix time},...}. The receiver is alive when it spoke in the last 30 seconds. */
    fun parseStatus(json: String, nowMs: Long): Status? {
        val o = try { Json.parseObject(json) } catch (e: Json.ParseException) { null } ?: return null
        fun v(name: String): String = o.obj(name)?.str("value") ?: ""
        val aliveAt = o.obj("alive")?.long("at") ?: 0L
        val rel = v("next_rel").toDoubleOrNull()
        return Status(v("state"), aliveAt > 0 && nowMs / 1000 - aliveAt < 30, v("tuned_khz").toDoubleOrNull()?.takeIf { it > 0 }, v("decoder"),
            v("lines").toDoubleOrNull()?.toInt() ?: 0, v("station"), v("title"), isoMs(v("next_start")), v("next_station"), v("next_title"),
            v("next_khz").toDoubleOrNull()?.takeIf { it > 0 }, v("next_why"), rel?.takeIf { it >= 0 })
    }

    /** What the receiver says it is doing, in the words of the app. */
    fun stateText(state: String): String = when (state) {
        "off" -> "Off"; "stopped" -> "Stopped"; "listening" -> "Listening"; "receiving a chart" -> "Receiving a chart"; "no receiver" -> "No SDR device"
        "tuned, waiting for the broadcast" -> "Tuned, waiting for the broadcast"; "listening on a fixed frequency" -> "Listening on a fixed frequency"
        "no station followed" -> "No station followed"; "nothing in the schedule for the stations followed" -> "Nothing in the schedule"
        "waiting: the receiver is with Navtex" -> "Waiting: Navtex has the SDR"; "" -> "No data"
        else -> state
    }

    // ---------------------------------------------------------------- stations and schedule
    data class Freq(val khz: Double, val call: String, val from: String?, val to: String?) {
        /** Whether it is on the air at a minute of the UTC day. */
        fun onAir(minute: Int): Boolean {
            val a = minutesOf(from ?: return true) ?: return true; val b = minutesOf(to ?: return true) ?: return true
            return if (a <= b) minute in a until b else minute >= a || minute < b
        }
    }
    data class Broadcast(val utc: String, val title: String, val lpm: Int, val ioc: Int)
    data class Station(val id: String, val name: String, val country: String, val deviation: Int, val lat: Double?, val lon: Double?, val kw: Double?,
                       val freqs: List<Freq>, val broadcasts: List<Broadcast>)
    data class StationList(val source: String, val note: String, val stations: List<Station>) {
        fun byId(id: String): Station? = stations.firstOrNull { it.id == id }
    }

    fun minutesOf(hhmm: String): Int? {
        if (!Regex("\\d{4}").matches(hhmm)) return null
        val h = hhmm.substring(0, 2).toInt(); val m = hhmm.substring(2).toInt()
        return if (h <= 23 && m <= 59) h * 60 + m else null
    }

    fun parseStations(json: String): StationList? {
        val o = try { Json.parseObject(json) } catch (e: Json.ParseException) { null } ?: return null
        val list = o.objList("stations").mapNotNull { s ->
            val id = s.str("id") ?: return@mapNotNull null
            Station(id, s.str("name") ?: id, s.str("country") ?: "", s.int("deviation") ?: 400, s.num("lat"), s.num("lon"), s.num("kw"),
                s.objList("freqs").mapNotNull { f -> f.num("khz")?.let { Freq(it, f.str("call") ?: "", f.str("from"), f.str("to")) } },
                s.objList("broadcasts").mapNotNull { b -> b.str("utc")?.takeIf { minutesOf(it) != null }?.let { Broadcast(it, b.str("title") ?: "", b.int("lpm") ?: 120, b.int("ioc") ?: 576) } }
                    .sortedBy { it.utc })
        }
        return StationList(o.str("source") ?: "", o.str("note") ?: "", list)
    }

    data class Slot(val station: Station, val broadcast: Broadcast, val startMs: Long, val endMs: Long, val skipped: Boolean)

    /**
     * The broadcasts in progress or starting in the next [hours], of the stations followed ("DDH,GYA"), as Wefax works them
     * out: each one lasts until the next of its station, between 8 and 30 minutes. [skip] is "ID:HHMM,...".
     */
    fun upcoming(list: StationList, follow: String, skip: String, nowMs: Long, hours: Int = 24): List<Slot> {
        val followed = follow.split(',').filter { it.isNotEmpty() }.toSet(); val skipped = skip.split(',').filter { it.isNotEmpty() }.toSet()
        val day = nowMs - Math.floorMod(nowMs, 86_400_000L)
        val out = ArrayList<Slot>()
        for (s in list.stations) {
            if (s.id !in followed || s.broadcasts.isEmpty()) continue
            for (d in -1..1) for ((i, b) in s.broadcasts.withIndex()) {
                val m = minutesOf(b.utc) ?: continue
                val next = minutesOf(s.broadcasts[(i + 1) % s.broadcasts.size].utc) ?: continue
                val gap = (Math.floorMod(next - m, 1440).takeIf { it > 0 } ?: 1440).coerceIn(8, 30)
                val start = day + d * 86_400_000L + m * 60_000L; val end = start + gap * 60_000L
                if (end < nowMs || start > nowMs + hours * 3_600_000L) continue
                out.add(Slot(s, b, start, end, "${s.id}:${b.utc}" in skipped))
            }
        }
        return out.sortedBy { it.startMs }
    }

    // ---------------------------------------------------------------- predictions (VOACAP, run by Wefax)
    /** Reliability (0..1) and signal to noise ratio (dB in 1 Hz) of each frequency at each UTC hour. Null: not said. */
    data class Hours(val khz: List<Double>, val rel: List<List<Double?>>, val snr: List<List<Double?>>) {
        private fun index(k: Double) = khz.indexOfFirst { kotlin.math.abs(it - k) < 0.05 }
        fun reliability(k: Double, hour: Int): Double? = rel.getOrNull(index(k))?.getOrNull(hour)
        fun signal(k: Double, hour: Int): Double? = snr.getOrNull(index(k))?.getOrNull(hour)
        /** The most reliable of [candidates] at that hour; between equals, the stronger signal. As the receiver does. */
        fun best(candidates: List<Double>, hour: Int): Double? {
            var best: Double? = null; var bestRel = -1.0; var bestSnr = -1e9
            for (k in candidates) {
                val r = reliability(k, hour) ?: continue; val q = signal(k, hour) ?: -1e9
                if (r > bestRel + 0.005 || (r > bestRel - 0.005 && q > bestSnr)) { best = k; bestRel = r; bestSnr = q }
            }
            return best
        }
    }
    data class Table(val year: Int, val month: Int, val ssn: Double, val lat: Double?, val lon: Double?, val stations: Map<String, Hours>) {
        /** A table is for one month. */
        fun valid(year: Int, month: Int) = this.year == year && this.month == month
    }

    private fun hoursOf(o: Map<String, Any?>): Hours? {
        fun rows(name: String): List<List<Double?>> = (o[name] as? List<*>)?.map { row -> (row as? List<*>)?.map { (it as? Number)?.toDouble() } ?: emptyList() } ?: emptyList()
        val khz = (o["khz"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: return null
        val rel = rows("rel")
        if (khz.isEmpty() || rel.size != khz.size || rel.any { it.size != 24 }) return null
        return Hours(khz, rel, rows("snr"))
    }

    /** /api/propagation. Null when Wefax has no table (an empty object). */
    fun parseTable(json: String): Table? {
        val o = try { Json.parseObject(json) } catch (e: Json.ParseException) { null } ?: return null
        val st = o.obj("stations") ?: return null
        val map = LinkedHashMap<String, Hours>()
        for ((id, v) in st) { @Suppress("UNCHECKED_CAST") hoursOf(v as? Map<String, Any?> ?: continue)?.let { map[id] = it } }
        return Table(o.int("year") ?: return null, o.int("month") ?: return null, o.num("ssn") ?: 0.0, o.num("lat"), o.num("lon"), map)
    }

    /** The path of a prediction from a transmitter to where the Wefax receiver is. Null when the numbers cannot be a circuit. */
    fun circuitPath(txLat: Double, txLon: Double, khz: List<Double>, kw: Double): String? {
        if (txLat !in -90.0..90.0 || txLon !in -180.0..180.0 || khz.isEmpty() || khz.size > 11 || khz.any { it < 2000 || it > 30000 } || kw < 0.001 || kw > 1000) return null
        fun n(v: Double, d: Int) = String.format(java.util.Locale.ROOT, "%.${d}f", v)
        return "/api/voacap?lat=${n(txLat, 2)}&lon=${n(txLon, 2)}&khz=${khz.joinToString(",") { n(it, 1) }}&kw=${n(kw, 3)}"
    }

    data class Circuit(val ok: Boolean, val why: String, val km: Double?, val ssn: Double?, val month: Int?, val hours: Hours?)

    fun parseCircuit(json: String): Circuit? {
        val o = try { Json.parseObject(json) } catch (e: Json.ParseException) { null } ?: return null
        val ok = o.bool("ok") ?: return null
        return Circuit(ok, o.str("why") ?: "", o.num("km"), o.num("ssn"), o.int("month"), if (ok) hoursOf(o) else null)
    }

    /** Why Wefax cannot predict, for the user. */
    fun whyText(why: String): String = when (why) {
        "voacap not installed" -> "VOACAP is not installed on the Wefax computer"
        "no sunspot number" -> "Wefax has no sunspot number: set it in its Receiver tab"
        "no position" -> "Wefax has no position"
        else -> why
    }

    /** The hours (UTC) a frequency is at or above a reliability, as ranges: "07-10, 16-18". "" when none. */
    fun goodHours(rel: List<Double?>, atLeast: Double = 0.5): String {
        val good = (0 until 24).map { (rel.getOrNull(it) ?: 0.0) >= atLeast }
        if (good.all { it }) return "all day"
        if (good.none { it }) return ""
        // start after a gap so that a run across midnight comes out whole
        val first = (0 until 24).first { good[it] && !good[(it + 23) % 24] }
        val out = ArrayList<String>(); var i = 0
        while (i < 24) {
            val h = (first + i) % 24
            if (!good[h]) { i++; continue }
            var n = 1
            while (n < 24 - i && good[(h + n) % 24]) n++
            out.add(String.format(java.util.Locale.ROOT, "%02d-%02d", h, (h + n) % 24))
            i += n
        }
        return out.joinToString(", ")
    }

    /** The amateur band frequency (kHz) nearest to one, to ask for a circuit when only the band is known. */
    val HAM_KHZ = listOf(3650.0, 5360.0, 7100.0, 10120.0, 14150.0, 18100.0, 21200.0, 24930.0, 28500.0)
}
