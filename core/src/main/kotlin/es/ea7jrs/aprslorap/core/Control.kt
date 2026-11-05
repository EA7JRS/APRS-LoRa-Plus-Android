/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Control protocol v1 of the APRS LoRa+ firmware: a second BLE service next to KISS, with one JSON object per line.
 * Specification: docs/BLE_CONTROL.md of the firmware repository.
 */
object ControlUuids {
    const val SERVICE = "4c6f5261-2b00-4ea7-a000-000000000001"
    const val COMMAND = "4c6f5261-2b00-4ea7-a000-000000000002"      // the phone writes here
    const val EVENT = "4c6f5261-2b00-4ea7-a000-000000000003"        // the tracker notifies here
    const val NMEA = "4c6f5261-2b00-4ea7-a000-000000000004"         // raw NMEA 0183, while subscribed
    const val FIRMWARE = "4c6f5261-2b00-4ea7-a000-000000000005"     // firmware image, binary (command "ota")
}

/** Operating modes of the firmware (Config.operatingMode). */
object TrackerMode {
    const val APRS_LORA = 0
    const val APRS_FSK = 1
    const val MORSE = 2
    const val WSPR = 3
    const val LRFHSS = 4
    const val DAPNET = 5
    const val FT8 = 6
    const val FT4 = 7
    const val FST4W = 8

    fun name(m: Int): String = when (m) {
        APRS_LORA -> "APRS LoRa"; APRS_FSK -> "APRS FSK"; MORSE -> "Morse / CW"; WSPR -> "WSPR"; LRFHSS -> "LR-FHSS"
        DAPNET -> "DAPNET"; FT8 -> "FT8"; FT4 -> "FT4"; FST4W -> "FST4W"; else -> "Mode $m"
    }

    fun isAprs(m: Int) = m == APRS_LORA || m == APRS_FSK
}

data class ModeOption(val mode: Int, val name: String)
data class BeaconProfile(val callsign: String, val label: String)
data class LoraProfile(val freqHz: Long, val sf: Int, val cr: Int, val fsk: Boolean) {
    val text: String get() = if (fsk) String.format(java.util.Locale.US, "%.3f MHz FSK", freqHz / 1e6)
                             else String.format(java.util.Locale.US, "%.3f MHz SF%d CR4/%d", freqHz / 1e6, sf, cr)
}

data class TrackerInfo(
    val protocol: Int, val firmware: String, val board: String,
    val hasNmea: Boolean, val hasDapnet: Boolean, val hasCw: Boolean, val canChangeMode: Boolean,
    val mode: Int, val modes: List<ModeOption>,
    val profile: Int, val profiles: List<BeaconProfile>,
    val lora: Int, val loras: List<LoraProfile>,
    val cwMessages: List<String>,
    val hasRemoteKey: Boolean = false,          // the firmware takes the key connected to the phone ("k" events)
    val hasSecondSlot: Boolean = false,         // firmware update over BLE ("ota")
    val hasWifiUpdate: Boolean = false,         // the tracker can update itself over its WiFi ("fwinstall")
)

/** State of the tracker's own on-line update (over its WiFi). */
data class FwStatus(val state: String, val progress: Int, val message: String, val latest: String, val current: String)

data class TrackerStatus(
    val mode: Int, val profile: Int, val lora: Int, val digi: Boolean, val smartBeacon: Boolean, val sos: Boolean,
    val batteryV: Double?, val uptimeS: Long, val bleKiss: Boolean,
)

data class Sensors(
    val batteryV: Double?, val temperatureC: Double?, val humidityPct: Double?, val pressureHpa: Double?, val qnhHpa: Double?,
    val baroAltitudeM: Double?, val headingDeg: Double?, val moving: Boolean?, val imuTemperatureC: Double?, val boardTemperatureC: Double?,
)

data class Gnss(
    val fix: Boolean, val lat: Double?, val lon: Double?, val altitudeM: Double?, val speedKmh: Double?, val courseDeg: Double?,
    val satellites: Int?, val hdop: Double?, val ageMs: Long?, val utc: String?,
)

/** A DAPNET page received by the tracker (POCSAG). */
data class Page(val ric: Long, val function: Int, val own: Boolean, val corrupt: Boolean, val ageS: Long, val rssi: Int, val text: String)

sealed class ControlEvent {
    data class Info(val info: TrackerInfo) : ControlEvent()
    data class Status(val status: TrackerStatus) : ControlEvent()
    data class SensorData(val sensors: Sensors) : ControlEvent()
    data class Position(val gnss: Gnss) : ControlEvent()
    data class DapnetHistory(val pages: List<Page>) : ControlEvent()
    data class NewPage(val page: Page) : ControlEvent()
    data class OtaProgress(val offset: Long, val done: Boolean) : ControlEvent()
    data class Firmware(val status: FwStatus) : ControlEvent()
    data class Ok(val command: String, val id: Int?) : ControlEvent()
    data class Error(val command: String, val id: Int?, val message: String) : ControlEvent()
    data class Unknown(val raw: String) : ControlEvent()
}

object Control {
    /** One command line, ready to write (with the final '\n'). */
    fun command(name: String, vararg fields: Pair<String, Any?>, id: Int? = null): String {
        val m = LinkedHashMap<String, Any?>()
        m["c"] = name
        if (id != null) m["id"] = id
        for ((k, v) in fields) m[k] = v
        return Json.encode(m) + "\n"
    }

    fun parse(line: String): ControlEvent {
        val o = Json.parseObject(line.trim()) ?: return ControlEvent.Unknown(line)
        return when (o.str("e")) {
            "info" -> ControlEvent.Info(info(o))
            "status" -> ControlEvent.Status(
                TrackerStatus(
                    o.int("mode") ?: 0, o.int("profile") ?: 0, o.int("lora") ?: 0, o.bool("digi") ?: false, o.bool("smart") ?: false,
                    o.bool("sos") ?: false, o.num("batt"), o.long("up") ?: 0, o.bool("bleKiss") ?: true,
                ),
            )
            "sensors" -> ControlEvent.SensorData(
                Sensors(
                    o.num("batt"), o.num("temp"), o.num("hum"), o.num("press"), o.num("qnh"), o.num("baroAlt"), o.num("heading"),
                    o.bool("moving"), o.num("imuTemp"), o.num("boardTemp"),
                ),
            )
            "gnss" -> ControlEvent.Position(
                Gnss(
                    o.bool("fix") ?: false, o.num("lat"), o.num("lon"), o.num("alt"), o.num("spd"), o.num("crs"), o.int("sats"), o.num("hdop"),
                    o.long("age"), o.str("utc"),
                ),
            )
            "dapnet" -> ControlEvent.DapnetHistory(o.objList("items").map { page(it) })
            "page" -> ControlEvent.NewPage(page(o))
            "ota" -> ControlEvent.OtaProgress(o.long("off") ?: 0, o.bool("done") ?: false)
            "fw" -> ControlEvent.Firmware(
                FwStatus(o.str("state") ?: "?", o.int("progress") ?: 0, o.str("message") ?: "", o.str("latest") ?: "", o.str("current") ?: ""),
            )
            "ok" -> ControlEvent.Ok(o.str("c") ?: "", o.int("id"))
            "err" -> ControlEvent.Error(o.str("c") ?: "", o.int("id"), o.str("msg") ?: "error")
            else -> ControlEvent.Unknown(line)
        }
    }

    private fun page(o: Map<String, Any?>) = Page(
        o.long("ric") ?: 0, o.int("f") ?: 0, o.bool("own") ?: false, o.bool("bad") ?: false, o.long("age") ?: 0, o.int("rssi") ?: 0, o.str("t") ?: "",
    )

    private fun info(o: Map<String, Any?>): TrackerInfo {
        val caps = o.obj("caps") ?: emptyMap()
        return TrackerInfo(
            o.int("v") ?: 0, o.str("fw") ?: "?", o.str("board") ?: "?",
            caps.bool("nmea") ?: false, caps.bool("dapnet") ?: false, caps.bool("cw") ?: false, caps.bool("modes") ?: false,
            o.int("mode") ?: 0, o.objList("modes").map { ModeOption(it.int("m") ?: 0, it.str("n") ?: TrackerMode.name(it.int("m") ?: 0)) },
            o.int("profile") ?: 0, o.objList("profiles").map { BeaconProfile(it.str("call") ?: "", it.str("label") ?: "") },
            o.int("lora") ?: 0, o.objList("loras").map { LoraProfile(it.long("f") ?: 0, it.int("sf") ?: 0, it.int("cr") ?: 0, it.bool("fsk") ?: false) },
            o.strList("cwMsgs"),
            caps.bool("rkey") ?: false,
            caps.bool("ota") ?: false,
            caps.bool("wifiUpd") ?: false,
        )
    }
}

/**
 * Splits a byte stream (BLE notifications of any size) into lines ending in '\n'. Bytes are UTF-8; '\r' is dropped.
 * A line longer than [maxLine] is thrown away (up to its '\n').
 */
class LineAssembler(private val maxLine: Int = 8192) {
    private val buf = java.io.ByteArrayOutputStream()
    private var overflow = false

    fun feed(data: ByteArray): List<String> {
        val lines = ArrayList<String>()
        for (b in data) {
            when (b) {
                '\n'.code.toByte() -> {
                    if (!overflow && buf.size() > 0) lines.add(String(buf.toByteArray(), Charsets.UTF_8))
                    buf.reset(); overflow = false
                }
                '\r'.code.toByte() -> {}
                else -> if (buf.size() < maxLine) buf.write(b.toInt()) else overflow = true
            }
        }
        return lines
    }

    fun reset() { buf.reset(); overflow = false }
}
