/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * APRS LoRa+ Rotor (repository EA7JRS/APRS-LoRa-Plus-Rotor, docs/CONTROL_PROTOCOL.md): JSON lines over BLE or TCP (port 4534).
 * The same on every rotor box: ESP32, ESP32-S3, Pico 2 W and Raspberry Pi.
 */
object RotorProto {
    const val SERVICE = "4c6f5261-2b00-4ea7-b000-000000000001"
    const val COMMAND = "4c6f5261-2b00-4ea7-b000-000000000002"   // the phone writes JSON lines
    const val EVENT = "4c6f5261-2b00-4ea7-b000-000000000003"     // the rotor notifies JSON lines
    const val DEFAULT_TCP_PORT = 4534
    const val CFG_CHUNK = 180

    // Hamlib rot_status_t flags (field "st" of the "pos" event)
    const val ST_BUSY = 1; const val ST_MOVING = 2; const val ST_MOVING_AZ = 4; const val ST_MOVING_LEFT = 8
    const val ST_MOVING_RIGHT = 16; const val ST_MOVING_EL = 32; const val ST_MOVING_UP = 64; const val ST_MOVING_DOWN = 128
    const val ST_LIMIT_UP = 256; const val ST_LIMIT_DOWN = 512; const val ST_LIMIT_LEFT = 1024; const val ST_LIMIT_RIGHT = 2048
    const val ST_OVERLAP_UP = 4096; const val ST_OVERLAP_DOWN = 8192; const val ST_OVERLAP_LEFT = 16384; const val ST_OVERLAP_RIGHT = 65536

    private val statusNames = listOf(
        ST_BUSY to "busy", ST_MOVING to "moving", ST_MOVING_LEFT to "CCW", ST_MOVING_RIGHT to "CW", ST_MOVING_UP to "up",
        ST_MOVING_DOWN to "down", ST_LIMIT_LEFT to "CCW limit", ST_LIMIT_RIGHT to "CW limit", ST_LIMIT_UP to "up limit",
        ST_LIMIT_DOWN to "down limit", ST_OVERLAP_LEFT to "overlap CCW", ST_OVERLAP_RIGHT to "overlap CW", ST_OVERLAP_UP to "over the zenith",
    )

    /** Readable status ("moving · CW · overlap CW"), empty when still. */
    fun statusText(st: Int): String = statusNames.filter { st and it.first != 0 }.joinToString(" · ") { it.second }

    enum class Move(val wire: String) { CW("cw"), CCW("ccw"), UP("up"), DOWN("down") }

    private fun line(vararg kv: Pair<String, Any?>): String = Json.encode(linkedMapOf(*kv)) + "\n"
    private fun r1(v: Double) = Math.round(v * 10) / 10.0

    fun auth(pin: String) = line("c" to "auth", "pin" to pin)
    fun info() = line("c" to "info")
    fun get() = line("c" to "get")
    fun raw() = line("c" to "raw")
    fun subscribe(ms: Int) = line("c" to "sub", "ms" to ms)
    fun pos(az: Double, el: Double?) = if (el == null) line("c" to "pos", "az" to r1(az)) else line("c" to "pos", "az" to r1(az), "el" to r1(el))
    fun stop() = line("c" to "stop")
    fun park() = line("c" to "park")
    fun clear() = line("c" to "clear")
    fun move(m: Move, speedPct: Int = 0) = if (speedPct > 0) line("c" to "move", "d" to m.wire, "s" to speedPct.coerceIn(1, 100)) else line("c" to "move", "d" to m.wire)
    fun calibrate(axis: String, point: String, deg: Double) = line("c" to "cal", "axis" to axis, "pt" to point, "deg" to deg)
    fun here(axis: String, deg: Double) = line("c" to "here", "axis" to axis, "deg" to deg)
    fun configGet() = line("c" to "cfgget")
    fun reboot() = line("c" to "reboot")

    // CAT of the radio / SDR receiver attached to the rotor box (docs/CAT.md of the rotor)
    val CAT_MODES = listOf("LSB", "USB", "CW", "FM", "AM", "PKTUSB")
    val CAT_STEPS = listOf(10L, 100L, 1000L, 5000L, 12500L, 25000L, 100000L)
    fun cat() = line("c" to "cat")
    fun catFreq(hz: Long) = line("c" to "catset", "f" to hz)
    fun catMode(mode: String) = line("c" to "catset", "m" to mode)
    fun catTxFreq(hz: Long) = line("c" to "catset", "txf" to hz)
    fun catSplit(on: Boolean) = line("c" to "catset", "split" to on)
    fun catVfo(v: String) = line("c" to "catset", "vfo" to v)            // "A", "B" or "swap"
    fun catPtt(on: Boolean) = line("c" to "catset", "ptt" to on)
    fun catTrackOn(on: Boolean) = line("c" to "cattrack", "on" to on)
    /** Doppler: nominal downlink / uplink (Hz, 0 = none) and range rate (km/s, + = going away); the box corrects and tunes. */
    fun catTrack(name: String, downHz: Long, upHz: Long, rangeRateKmS: Double) =
        line("c" to "cattrack", "name" to name, "dl" to downHz, "ul" to upHz, "rr" to Math.round(rangeRateKmS * 1000) / 1000.0)

    /** The whole configuration (compact JSON) as the "cfgset" lines, in order. */
    fun configSet(json: String): List<String> {
        val n = maxOf(1, (json.length + CFG_CHUNK - 1) / CFG_CHUNK)
        return (0 until n).map { i -> line("c" to "cfgset", "i" to i, "n" to n, "d" to json.substring(i * CFG_CHUNK, minOf(json.length, (i + 1) * CFG_CHUNK))) }
    }

    // ------------------------------------------------------------------ events

    data class Limits(val minAz: Double, val maxAz: Double, val minEl: Double, val maxEl: Double)

    data class Info(
        val device: String, val version: String, val board: String, val chip: String, val name: String, val driver: String,
        val callsign: String, val limits: Limits, val hasEl: Boolean, val pinRequired: Boolean, val ip: String, val rotctldPort: Int,
        val wifi: Boolean, val ble: Boolean, val spp: Boolean, val lora: Boolean, val canCalibrate: Boolean, val canMove: Boolean,
        val uptimeS: Long, val hamlib: String?, val cat: Boolean = false, val rigctldPort: Int = 0,
    )

    data class CatTracking(val enabled: Boolean, val active: Boolean, val name: String, val dopplerHz: Long)

    /** State of the radio (event `cat`, every second while subscribed). [smeter] 0-15 (S0-S9+60), -1 unknown. */
    data class CatState(
        val rig: String, val link: Boolean, val freqHz: Long, val mode: String, val txFreqHz: Long?, val split: Boolean,
        val vfo: String, val ptt: Boolean, val smeter: Int, val pttAllowed: Boolean, val tracking: CatTracking?,
    ) {
        val sText: String get() = when {
            smeter < 0 -> "—"
            smeter <= 9 -> "S$smeter"
            else -> "S9+${(smeter - 9) * 10}"
        }
    }

    data class Position(
        val azDeg: Double, val elDeg: Double?, val status: Int, val moving: Boolean, val fault: String,
        val mechAzDeg: Double?, val targetAzDeg: Double?, val targetElDeg: Double?, val aprs: String?,
    ) {
        val ok: Boolean get() = fault == "OK"
    }

    sealed class Event {
        data class InfoEv(val info: Info) : Event()
        data class Pos(val pos: Position) : Event()
        data class Raw(val az: Double?, val el: Double?, val unit: String, val link: Boolean?) : Event()
        data class ConfigPiece(val index: Int, val count: Int, val data: String) : Event()
        data class Ok(val command: String, val index: Int?, val done: Boolean) : Event()
        data class Err(val command: String, val message: String) : Event()
        data class Hello(val device: String) : Event()
        data class Cat(val state: CatState) : Event()
    }

    fun parse(line: String): Event? {
        val o = Json.parseObject(line.trim()) ?: return null
        return when (o.str("e")) {
            "info" -> {
                val lim = o.obj("lim") ?: emptyMap()
                val caps = o.obj("caps") ?: emptyMap()
                Event.InfoEv(Info(
                    o.str("dev") ?: "", o.str("ver") ?: "", o.str("board") ?: "", o.str("chip") ?: "", o.str("name") ?: "",
                    o.str("drv") ?: "", o.str("call") ?: "",
                    Limits(lim.num("minAz") ?: 0.0, lim.num("maxAz") ?: 360.0, lim.num("minEl") ?: 0.0, lim.num("maxEl") ?: 90.0),
                    o.bool("hasEl") ?: true, o.bool("pin") ?: false, o.str("ip") ?: "", o.int("rotctld") ?: 0,
                    caps.bool("wifi") ?: false, caps.bool("ble") ?: false, caps.bool("spp") ?: false, caps.bool("lora") ?: false,
                    caps.bool("cal") ?: false, caps.bool("move") ?: true, o.long("up") ?: 0L, o.str("hamlib"),
                    o.bool("cat") ?: caps.bool("cat") ?: false, o.int("rigctld") ?: 0,
                ))
            }
            "pos" -> {
                val az = o.num("az") ?: return null
                Event.Pos(Position(az, o.num("el"), o.int("st") ?: 0, o.bool("mv") ?: false, o.str("f") ?: "OK",
                    o.num("maz"), o.num("taz"), o.num("tel"), o.str("aprs")))
            }
            "raw" -> Event.Raw(o.num("az"), o.num("el"), o.str("unit") ?: "", o.bool("link"))
            "cfg" -> Event.ConfigPiece(o.int("i") ?: return null, o.int("n") ?: return null, o.str("d") ?: "")
            "ok" -> Event.Ok(o.str("c") ?: "", o.int("i"), o.bool("done") ?: false)
            "err" -> Event.Err(o.str("c") ?: "", o.str("m") ?: "")
            "hello" -> Event.Hello(o.str("dev") ?: "")
            "cat" -> {
                val tr = o.obj("tr")
                Event.Cat(CatState(
                    o.str("rig") ?: "", o.bool("l") ?: false, o.num("f")?.toLong() ?: 0L, o.str("m") ?: "", o.num("tf")?.toLong(),
                    o.bool("sp") ?: false, o.str("v") ?: "A", o.bool("ptt") ?: false, o.int("sm") ?: -1, o.bool("pa") ?: false,
                    tr?.let { CatTracking(it.bool("en") ?: false, it.bool("on") ?: false, it.str("n") ?: "", it.num("dop")?.toLong() ?: 0L) },
                ))
            }
            else -> null
        }
    }

    /** Bytes as they come (BLE notifications, TCP) -> complete lines. */
    class LineAssembler {
        private val buf = StringBuilder()
        fun feed(bytes: ByteArray): List<String> = feed(String(bytes, Charsets.UTF_8))
        fun feed(text: String): List<String> {
            val out = ArrayList<String>()
            for (c in text) {
                if (c == '\n') { if (buf.isNotBlank()) out.add(buf.toString()); buf.setLength(0) }
                else if (c != '\r') { if (buf.length < 4096) buf.append(c) else buf.setLength(0) }
            }
            return out
        }
        fun reset() = buf.setLength(0)
    }

    /** Joins the "cfg" pieces; returns the whole text once the last one arrives in order. */
    class ConfigAssembler {
        private val sb = StringBuilder()
        private var next = 0
        fun add(p: Event.ConfigPiece): String? {
            if (p.index == 0) { sb.setLength(0); next = 0 }
            if (p.index != next) { sb.setLength(0); next = 0; return null }
            sb.append(p.data); next++
            return if (next == p.count) sb.toString().also { sb.setLength(0); next = 0 } else null
        }
    }

    /** Whether a new pointing is worth sending (the tracker's `rot` event repeats every 10 s even when still). */
    fun worthSending(lastAz: Double?, lastEl: Double?, az: Double, el: Double?, stepDeg: Double): Boolean {
        if (lastAz == null) return true
        if (Math.abs(RotCtl.angleDiff(lastAz, az)) >= stepDeg) return true
        if (el != null && (lastEl == null || Math.abs(el - lastEl) >= stepDeg)) return true
        return false
    }

    /** 145.825.000 style (MHz.kHz.Hz). */
    fun freq(hz: Long): String {
        if (hz <= 0) return "—"
        val mhz = hz / 1_000_000; val khz = (hz / 1000) % 1000; val h = hz % 1000
        return String.format(Locale.US, "%d.%03d.%03d", mhz, khz, h)
    }

    /** Readable mode (PKTUSB -> DIG-U). */
    fun modeName(m: String): String = when (m) { "PKTUSB" -> "DIG-U"; "PKTLSB" -> "DIG-L"; "PKTFM" -> "DIG-FM"; "CWR" -> "CW-R"; "" -> "—"; else -> m }

    fun deg(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%.1f°", v)
}
