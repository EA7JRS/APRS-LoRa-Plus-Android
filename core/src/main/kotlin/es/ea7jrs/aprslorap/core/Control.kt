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
    const val SAT = 9
    const val M17 = 10                          // LilyGO T-TWR only: APRS in M17 APRS packets; M17 SMS and voice-call callsigns / GNSS heard
    const val FM = 11                           // LilyGO T-TWR only: FM voice radio (APRS beacons on the APRS frequency during voice)

    fun name(m: Int): String = when (m) {
        APRS_LORA -> "APRS LoRa"; APRS_FSK -> "APRS FSK"; MORSE -> "Morse / CW"; WSPR -> "WSPR"; LRFHSS -> "LR-FHSS"
        DAPNET -> "DAPNET"; FT8 -> "FT8"; FT4 -> "FT4"; FST4W -> "FST4W"; SAT -> "SAT"; M17 -> "M17"; FM -> "FM voice"; else -> "Mode $m"
    }

    fun isAprs(m: Int) = m == APRS_LORA || m == APRS_FSK || m == M17 || m == FM
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
    val hasGpsAid: Boolean = false,             // A-GPS for the u-blox receiver ("gpsaid")
    val hasRotor: Boolean = false,              // rotor on in the WebUI ("rotext", "att", "tgt")
    val radio: String = "lora",                 // "lora" (SX12xx / LLCC68) or "sa868" (LilyGO T-TWR Plus: FM radio, APRS AFSK 1200)
    val sa868: Sa868Info? = null,               // only with radio = "sa868"; its settings are the "sa868" group of the configuration
    val hasM17: Boolean = false,                // M17 mode and the "m17sms" command (T-TWR)
    val hasFm: Boolean = false,                 // FM voice mode: "fm*", "qsy", "carrier" (T-TWR)
    val bcastChip: String = "none",             // broadcast receiver on I2C ("RDA5807M", "Si4703", "Si4735"): "bcast", "bcastset"
    val satMaxBytes: Int = Control.SAT_DATA_MAX, // satellite file the tracker can store (the app filters sats_all.txt by groups to fit)
    val hasSatFav: Boolean = false,             // favorites and categories ("satfav", "satcat")
    val hasSatFm: Boolean = false,              // T-TWR: the FM VFO follows the satellite ("satrx" / "sattx" also in FM mode, "sattx pos")
    val hasWxNet: Boolean = false,              // weather from the internet for the TELEMETRY page ("wxnet")
)

/** FM radio of the LilyGO T-TWR Plus: board revision (20 / 21), module firmware ("NiceRF" / "OpenRTX"), band, whether it answers,
 *  modem in use ("AFSK 1200", "G3RUH 9600", "M17"), frequencies in use and the M17 frequency (Hz; 0 = not reported). */
data class Sa868Info(
    val revision: Int, val firmware: String, val band: String, val ok: Boolean,
    val modem: String = "", val rxHz: Long = 0, val txHz: Long = 0, val m17Hz: Long = 0,
    val dapnetHz: Long = 0, val speakerFromEsp: Boolean = false,
) {
    val uhf: Boolean get() = band.equals("UHF", ignoreCase = true)
    val text: String get() = if (!ok) "SA868 not answering" else String.format(java.util.Locale.US, "SA868 %s, Rev%.1f, %s firmware", band, revision / 10.0, firmware)
    val channel: String get() = when {
        rxHz <= 0 -> modem
        txHz > 0 && txHz != rxHz -> String.format(java.util.Locale.US, "%s, RX %.4f / TX %.4f MHz", modem, rxHz / 1e6, txHz / 1e6)
        else -> String.format(java.util.Locale.US, "%s, %.4f MHz", modem, rxHz / 1e6)
    }
}

data class TrackerStatus(
    val mode: Int, val profile: Int, val lora: Int, val digi: Boolean, val smartBeacon: Boolean, val sos: Boolean,
    val batteryV: Double?, val uptimeS: Long, val bleKiss: Boolean,
)

data class Sensors(
    val batteryV: Double?, val temperatureC: Double?, val humidityPct: Double?, val pressureHpa: Double?, val qnhHpa: Double?,
    val baroAltitudeM: Double?, val headingDeg: Double?, val moving: Boolean?, val imuTemperatureC: Double?, val boardTemperatureC: Double?,
    // Rest of the sensors and the power system (firmware with the full `sensors` event)
    val gasKohm: Double? = null, val iaq: Double? = null, val iaqLabel: String? = null, val wxSensor: String? = null,
    val accelG: List<Double>? = null, val gyroDps: List<Double>? = null, val flat: Boolean? = null,
    val magCalibrated: Boolean? = null, val magAligned: Boolean? = null,
    val charging: Boolean? = null, val vbusV: Double? = null, val pmuTemperatureC: Double? = null, val batteryMa: Double? = null,
    val batteryPct: Int? = null, val freeHeap: Long? = null,
)

data class Gnss(
    val fix: Boolean, val lat: Double?, val lon: Double?, val altitudeM: Double?, val speedKmh: Double?, val courseDeg: Double?,
    val satellites: Int?, val hdop: Double?, val ageMs: Long?, val utc: String?,
)

/** A DAPNET page received by the tracker (POCSAG). */
data class Page(val ric: Long, val function: Int, val own: Boolean, val corrupt: Boolean, val ageS: Long, val rssi: Int, val text: String)

/** A message of the tracker's own log (APRS). state: 0 unread, 1 read, 2 sent, 3 waiting for ack, 4 acked, 5 failed. */
data class TrackerMessage(val index: Int, val incoming: Boolean, val epoch: Long, val state: Int, val ackId: Int, val call: String, val text: String)

/** A station heard by the tracker. */
data class HeardStation(
    val call: String, val symbol: String, val kind: String, val lat: Double?, val lon: Double?, val altitudeM: Int?, val speedKmh: Int?, val course: Int?,
    val rssi: Int?, val snr: Double?, val ageS: Long, val packets: Int, val device: String?, val path: String?, val comment: String?, val status: String?,
    val wx: Map<String, Any?>?,
    val posAgeS: Long? = null,                       // seconds since its last position (firmware with "pa")
)

/** Text of the tracker's screen (last page drawn). */
data class ScreenMirror(val page: Int, val current: Int, val title: String, val right: String, val ageMs: Long, val displayOn: Boolean, val rows: List<String>)

/** One menu option of the tracker; [allowed] false = it exists but cannot be run from the app. */
data class TrackerAction(val name: String, val label: String, val allowed: Boolean)
data class ActionPage(val page: Int, val title: String, val actions: List<TrackerAction>)

/** State of SAT mode (event `sat`, every 2 s while in that mode). Times are Unix UTC seconds. */
data class SatState(
    val ok: Boolean, val name: String?, val noradId: Int?, val index: Int?, val geostationary: Boolean, val transmitters: Int, val tleAgeDays: Double?,
    val positionSource: String, val azDeg: Double?, val elDeg: Double?, val rangeKm: Int?, val rangeRateKmS: Double?,
    val aos: Long?, val los: Long?, val maxElDeg: Int?,
    val favorite: Boolean = false, val category: Int = 0, val total: Int = 0, val favorites: Int = 0, val stamp: String = "",
)

/** One satellite of the tracker's list. [category]: U 70 cm, V 2 m, L LoRa, G geostationary; [bands]: Sat.BAND_* bits of its transmitters;
 *  [inCategory]: in the list (category) chosen in the tracker. */
data class SatEntry(
    val index: Int, val noradId: Int, val name: String, val category: String, val transmitters: Int,
    val bands: Int = 0, val favorite: Boolean = false, val inCategory: Boolean = true,
)

/** One transmitter of the chosen satellite; [rxKind] / [txKind]: CW, FSK, LoRa, n/a (what the tracker can do with it). */
data class SatTransmitter(
    val index: Int, val mode: String, val baud: Int, val description: String, val upHz: Long, val downHz: Long,
    val rxKind: String, val txKind: String, val rxDopplerHz: Long?, val txDopplerHz: Long?,
    val toneHz: Double? = null, val aprsDigi: Boolean = false,      // CTCSS of the uplink; APRS digipeater (ISS 145.825)
)

/** Receiver of satellites (event `satrx`). [text] only when there is a new received line. */
data class SatReceiver(
    val on: Boolean, val kind: String?, val freqHz: Long?, val dopplerHz: Int?, val fineHz: Int?, val rssi: Int?, val frames: Int?, val tx: Int?, val text: String?,
)

/** A fixed target of the rotor (repeater, beacon...). */
data class RotorTarget(val slot: Int, val name: String, val lat: Double, val lon: Double, val altM: Int, val freqHz: Long)

/** Where the rotor points (event `rot`). [source]: 0 satellite, 1 APRS station of the compass, 2 fixed target. */
data class RotorPointing(
    val azDeg: Double, val elDeg: Double, val tracking: Boolean, val source: Int, val name: String?, val km: Int?,
    val pitchDeg: Double?, val rollDeg: Double?, val mobile: Boolean, val absAzDeg: Double?, val headingDeg: Double?,
    val actualAzDeg: Double? = null, val actualElDeg: Double? = null,      // what the rotor reports, if known
)

sealed class ControlEvent {
    data class Info(val info: TrackerInfo) : ControlEvent()
    data class Status(val status: TrackerStatus) : ControlEvent()
    data class SensorData(val sensors: Sensors) : ControlEvent()
    data class Position(val gnss: Gnss) : ControlEvent()
    data class DapnetHistory(val pages: List<Page>) : ControlEvent()
    data class NewPage(val page: Page) : ControlEvent()
    data class OtaProgress(val offset: Long, val done: Boolean) : ControlEvent()
    data class Messages(val total: Int, val unread: Int, val from: Int, val items: List<TrackerMessage>) : ControlEvent()
    data class MessageChanged(val total: Int, val unread: Int, val newest: TrackerMessage?) : ControlEvent()
    data class Heard(val total: Int, val packets: Long, val from: Int, val items: List<HeardStation>) : ControlEvent()
    data class Screen(val screen: ScreenMirror) : ControlEvent()
    data class Actions(val page: ActionPage) : ControlEvent()
    data class ActionsEnd(val pages: Int) : ControlEvent()
    /** A piece of the tracker's configuration file (answer to `cfgget`). */
    data class ConfigPiece(val offset: Int, val total: Int, val bytes: Int, val text: String) : ControlEvent()
    data class Sat(val state: SatState) : ControlEvent()
    data class SatList(val total: Int, val selected: Int, val from: Int, val items: List<SatEntry>) : ControlEvent()
    data class SatTransmitters(val items: List<SatTransmitter>) : ControlEvent()
    data class SatRx(val receiver: SatReceiver) : ControlEvent()
    data class SatTxDone(val ok: Boolean, val freqHz: Long, val count: Int) : ControlEvent()
    data class Rotor(val pointing: RotorPointing) : ControlEvent()
    /** CW listening on RF (`cwrx`): [letter] is null for a level-only update; [on] false = the session ended. */
    /** The tracker asks the app to download something with the phone's internet: "wx" (TELEMETRY weather) or "sat" (satellite file). */
    data class NetRequest(val what: String) : ControlEvent()
    data class CwRx(val on: Boolean, val letter: String?, val dbm: Double?, val snr: Double?, val wpm: Int?, val freqHz: Long? = null) : ControlEvent()
    data class Targets(
        val selected: Int, val source: Int, val items: List<RotorTarget>,
        // rotor following what the tracker hears (firmware with sources 4 / 5): callsign filter, max age, moving-station estimate
        val filter: String = "", val maxAgeMin: Int = 30, val predict: Boolean = true, val predictMaxS: Int = 300,
    ) : ControlEvent()
    data class Fm(val state: FmState) : ControlEvent()
    data class FmMemories(val max: Int, val items: List<FmChannel>) : ControlEvent()
    data class Bcast(val state: BcastState) : ControlEvent()
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
                    o.num("gas"), o.num("iaq"), o.str("iaqLabel"), o.str("wxSensor"), numList(o, "accel"), numList(o, "gyro"), o.bool("flat"),
                    o.bool("magCal"), o.bool("magAlign"), o.bool("charging"), o.num("vbus"), o.num("pmuTemp"), o.num("battMa"),
                    o.int("battPct"), o.long("heap"),
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
            "msgs" -> ControlEvent.Messages(o.int("tot") ?: 0, o.int("unread") ?: 0, o.int("from") ?: 0, o.objList("items").map { message(it) })
            "msg" -> ControlEvent.MessageChanged(o.int("tot") ?: 0, o.int("unread") ?: 0, if (o.containsKey("c")) message(o) else null)
            "fm" -> ControlEvent.Fm(FmState.parse(o))
            "bcast" -> ControlEvent.Bcast(BcastState.parse(o))
            "fmmems" -> ControlEvent.FmMemories(o.int("max") ?: 32, o.objList("mem").map { FmChannel.parse(it) })
            "heard" -> ControlEvent.Heard(o.int("tot") ?: 0, o.long("pkts") ?: 0, o.int("from") ?: 0, o.objList("items").mapNotNull { heard(it) })
            "screen" -> ControlEvent.Screen(
                ScreenMirror(
                    o.int("p") ?: 0, o.int("cur") ?: 0, o.str("t") ?: "", o.str("r") ?: "", o.long("age") ?: 0, o.bool("on") ?: true, o.strList("rows"),
                ),
            )
            "actlist" -> if (o.bool("end") == true) ControlEvent.ActionsEnd(o.int("pages") ?: 0) else ControlEvent.Actions(
                ActionPage(
                    o.int("p") ?: 0, o.str("pt") ?: "",
                    o.objList("items").mapNotNull { a -> a.str("n")?.let { TrackerAction(it, a.str("l") ?: "", a.bool("ok") ?: true) } },
                ),
            )
            "cfg" -> ControlEvent.ConfigPiece(o.int("o") ?: 0, o.int("n") ?: 0, o.int("b") ?: 0, o.str("d") ?: "")
            "sat" -> ControlEvent.Sat(
                SatState(
                    o.bool("ok") ?: false, o.str("n"), o.int("id"), o.int("i"), o.bool("geo") ?: false, o.int("tx") ?: 0, o.num("tle"),
                    o.str("pos") ?: "", o.num("az"), o.num("el"), o.int("rng"), o.num("rr"), o.long("aos"), o.long("los"), o.int("max"),
                    o.bool("fav") ?: false, o.int("cat") ?: 0, o.int("tot") ?: 0, o.int("nfav") ?: 0, o.str("stamp") ?: "",
                ),
            )
            "satlist" -> ControlEvent.SatList(
                o.int("tot") ?: 0, o.int("sel") ?: -1, o.int("from") ?: 0,
                o.objList("items").mapNotNull { e ->
                    e.int("i")?.let {
                        SatEntry(it, e.int("id") ?: 0, e.str("n") ?: "?", e.str("c") ?: "", e.int("t") ?: 0, e.int("b") ?: 0, e.bool("fav") ?: false,
                                 e.bool("in") ?: true)         // older firmware: no categories
                    }
                },
            )
            "sattxlist" -> ControlEvent.SatTransmitters(
                o.objList("items").mapNotNull { t ->
                    t.int("i")?.let {
                        SatTransmitter(
                            it, t.str("m") ?: "", t.int("b") ?: 0, t.str("d") ?: "", t.long("up") ?: 0, t.long("dn") ?: 0,
                            t.str("rx") ?: "n/a", t.str("tx") ?: "n/a", t.long("rxd"), t.long("txd"), t.num("tone"), t.bool("aprs") ?: false,
                        )
                    }
                },
            )
            "satrx" -> ControlEvent.SatRx(
                SatReceiver(
                    o.bool("on") ?: false, o.str("k"), o.long("f"), o.int("dop"), o.int("fine"), o.int("rssi"), o.int("n"), o.int("tx"), o.str("txt"),
                ),
            )
            "sattx" -> ControlEvent.SatTxDone(o.bool("ok") ?: false, o.long("f") ?: 0, o.int("n") ?: 0)
            "rot" -> ControlEvent.Rotor(
                RotorPointing(
                    o.num("az") ?: 0.0, o.num("el") ?: 0.0, o.bool("trk") ?: false, o.int("src") ?: 0, o.str("name"), o.int("km"),
                    o.num("pit"), o.num("rol"), o.bool("mob") ?: false, o.num("abs"), o.num("hdg"), o.num("aaz"), o.num("ael"),
                ),
            )
            "netreq" -> ControlEvent.NetRequest(o.str("what") ?: "")
            "cwrx" -> ControlEvent.CwRx(o.bool("on") ?: false, o.str("t"), o.num("dbm"), o.num("snr"), o.int("wpm"), o.long("f"))
            "targets" -> ControlEvent.Targets(
                o.int("sel") ?: 0, o.int("src") ?: 0,
                o.objList("items").mapNotNull { t ->
                    t.int("i")?.let { RotorTarget(it, t.str("n") ?: "", t.num("lat") ?: 0.0, t.num("lon") ?: 0.0, t.int("alt") ?: 0, t.long("f") ?: 0) }
                },
                o.str("ff") ?: "", o.int("fm") ?: 30, o.bool("pr") ?: true, o.int("pm") ?: 300,
            )
            "ok" -> ControlEvent.Ok(o.str("c") ?: "", o.int("id"))
            "err" -> ControlEvent.Error(o.str("c") ?: "", o.int("id"), o.str("msg") ?: "error")
            else -> ControlEvent.Unknown(line)
        }
    }

    private fun numList(o: Map<String, Any?>, key: String): List<Double>? = (o[key] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }

    private fun message(o: Map<String, Any?>) = TrackerMessage(
        o.int("i") ?: 0, o.str("d") != "T", o.long("ep") ?: 0, o.int("st") ?: 0, o.int("ack") ?: 0, o.str("c") ?: "", o.str("t") ?: "",
    )

    private fun heard(o: Map<String, Any?>): HeardStation? {
        val call = o.str("c") ?: return null
        return HeardStation(
            call, o.str("sym") ?: "", o.str("k") ?: "", o.num("lat"), o.num("lon"), o.int("alt"), o.int("spd"), o.int("crs"), o.int("rssi"), o.num("snr"),
            o.long("age") ?: 0, o.int("n") ?: 0, o.str("dev"), o.str("path"), o.str("cmt"), o.str("sts"), o.obj("wx"), o.long("pa"),
        )
    }

    /**
     * A long text sent in steps: begin, chunks, end (cfgset, satdata). Every line fits the tracker's 255-byte limit; the tracker answers
     * `ok` to each step and the caller sends the next one then (stop and wait). Returns the command lines.
     */
    fun chunked(name: String, begin: Map<String, Any?>, text: String): List<String> {
        val lines = ArrayList<String>()
        lines.add(command(name, "s" to "begin", *begin.map { it.key to it.value }.toTypedArray()))
        var pos = 0
        while (pos < text.length) {
            var end = minOf(text.length, pos + 200)
            while (end - pos > 1) {
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                if (command(name, "s" to "c", "d" to text.substring(pos, end)).toByteArray(Charsets.UTF_8).size <= MAX_LINE_SAFE) break
                end--
            }
            lines.add(command(name, "s" to "c", "d" to text.substring(pos, end)))
            pos = end
        }
        lines.add(command(name, "s" to "end"))
        return lines
    }

    /** The tracker refuses command lines over 255 bytes; we stay below with room for an id. */
    const val MAX_LINE_SAFE = 235

    /** Whole configuration file for `cfgset` (read it with `cfgget`, change it, send it back). */
    fun configSet(configJson: String, reboot: Boolean = true): List<String> =
        chunked("cfgset", mapOf("n" to configJson.toByteArray(Charsets.UTF_8).size, "reboot" to reboot), configJson)

    /** The file `sats.txt` for `satdata` (the tracker takes up to [TrackerInfo.satMaxBytes] bytes, 64 kB on most boards). Same stop-and-wait steps as [configSet]. */
    fun satData(text: String): List<String> = chunked("satdata", mapOf("n" to text.toByteArray(Charsets.UTF_8).size), text)

    const val SAT_DATA_MAX = 64_000

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
            caps.bool("gpsaid") ?: false,
            caps.bool("rotor") ?: false,
            caps.str("radio") ?: "lora",
            o.obj("sa868")?.let {
                Sa868Info(it.int("rev") ?: 0, it.str("fw") ?: "?", it.str("band") ?: "?", it.bool("ok") ?: false,
                          it.str("modem") ?: "", it.long("rx") ?: 0, it.long("tx") ?: 0, it.long("m17") ?: 0,
                          it.long("dapnet") ?: 0, it.bool("spkEsp") ?: false)
            },
            caps.bool("m17") ?: false,
            caps.bool("fm") ?: false,
            caps.str("bcast") ?: "none",
            caps.int("satMax") ?: SAT_DATA_MAX,
            caps.bool("satFav") ?: false,
            caps.bool("satFm") ?: false,
            caps.bool("wxnet") ?: false,
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
