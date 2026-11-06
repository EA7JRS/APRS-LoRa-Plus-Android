/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Aprs
import es.ea7jrs.aprslorap.core.AprsPacket
import es.ea7jrs.aprslorap.core.Ax25Frame
import es.ea7jrs.aprslorap.core.Control
import es.ea7jrs.aprslorap.core.ControlEvent
import es.ea7jrs.aprslorap.core.Json
import es.ea7jrs.aprslorap.core.Gnss
import es.ea7jrs.aprslorap.core.IGate
import es.ea7jrs.aprslorap.core.Kiss
import es.ea7jrs.aprslorap.core.LineAssembler
import es.ea7jrs.aprslorap.core.MessageEngine
import es.ea7jrs.aprslorap.core.Nmea
import es.ea7jrs.aprslorap.core.ActionPage
import es.ea7jrs.aprslorap.core.Page
import es.ea7jrs.aprslorap.core.Sat
import es.ea7jrs.aprslorap.core.StationTable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Joins the pieces of the core: bytes from the tracker -> frames -> stations, messages and iGate; control events -> tracker
 * state, sensors, GNSS, DAPNET; NMEA -> TCP server and mock location.
 * Everything runs on ONE thread (the executor), so the core classes need no locking.
 */
class Controller(
    private val settings: Settings,
    private val notifier: Notifier,
    private val transmitBytes: (ByteArray) -> Unit,
    private val isSend: (String) -> Unit,
    private val sendControlLine: (String) -> Unit = {},
    private val sendControlFast: (String) -> Unit = sendControlLine,
    private val setTrackerNmea: (Boolean) -> Unit = {},
    private val nmeaServer: NmeaServer? = null,
    private val mock: MockLocation? = null,
) {
    companion object {
        /**
         * Minimum time between two frames handed to the tracker. Firmware without the control service keeps only ONE frame
         * from the phone (BLEToLoRaPacket) and shows "BLE Tx" for 1 s before sending it: a second frame that arrives sooner
         * overwrites the first one. Firmware with the control service keeps a queue of 4.
         */
        const val TX_GAP_OLD_MS = 3000L
        const val TX_GAP_NEW_MS = 1500L
        const val STATUS_EVERY_MS = 15_000L
        const val MAX_PAGES = 100
        /** Commands that only read: allowed without a validated callsign. */
        val QUERIES = setOf("info", "status", "sensors", "gnss", "dapnet", "sub", "msgs", "heard", "actlist", "screen", "cfgget",
            // the firmware update does not transmit: allowed too
            "ota", "ota_abort",
            // SAT mode: reading, listening and telling the tracker where the phone is do not transmit (sattx, satdata and tgt are not here)
            "sat", "satlist", "sattxlist", "tgtlist", "satsel", "satrx", "pos",
            // A-GPS data for the tracker's GNSS receiver: nothing is transmitted
            "gpsaid")
        const val SAT_LIST_PAGE = 40
        const val SAT_LIST_MAX = 600
    }

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val decoder = Kiss.Decoder()
    private val table = StationTable()
    private val myCall = settings.callsign
    private val trackerAcks = MessageEngine.trackerSendsAcks(myCall, settings.trackerCallsign)
    private val engine = MessageEngine(myCall, { info -> transmitInfo(info) }, { System.currentTimeMillis() }, sendAcks = !trackerAcks)
    private val iGate = IGate(myCall, { System.currentTimeMillis() })
    private val txQueue = ArrayDeque<ByteArray>()
    private var lastTxMs = 0L

    private val actionsBuilding = ArrayList<ActionPage>()
    private var actionsFresh = true
    private val configBytes = java.io.ByteArrayOutputStream()
    private var configNext = 0
    private val configLines = ArrayDeque<String>()
    private var configSteps = 0
    private var configWriting = false
    private var configDeadlineMs = 0L

    private val satLines = ArrayDeque<String>()
    private var satSteps = 0
    private var satWriting = false
    private var satDeadlineMs = 0L
    private val aidLines = ArrayDeque<String>()
    private var aidSteps = 0
    private var aidWriting = false
    private var aidDeadlineMs = 0L
    private var aidDoneText = ""
    private var aidCurrent: String? = null
    private var satListLoading = false
    private var phoneLat = 0.0
    private var phoneLon = 0.0
    private var phoneAlt: Double? = null
    private var phoneAcc: Float? = null
    private var phoneAtMs = 0L
    private var phoneSentMs = 0L

    private val controlLines = LineAssembler()
    private val nmeaLines = LineAssembler(512)
    private var controlOn = false
    private var lastStatusMs = 0L
    private var nmeaFromTracker = false            // the board sends its own NMEA: the phone does not build RMC/GGA
    private val wantNmea get() = nmeaServer != null
    private val wantFastGnss get() = nmeaServer != null || mock != null

    val loginLine: String get() = iGate.loginLine()

    init {
        if (trackerAcks) AppState.addLog("Same callsign as the tracker: the tracker sends the ACKs")
        exec.scheduleWithFixedDelay({ safe { tick() } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { AppState.addLog("error: ${e.message}") }
    }

    // ------------------------------------------------------------------ APRS (KISS)

    /** Called from the BLE thread with each KISS notification of the tracker. */
    fun onBleBytes(data: ByteArray) {
        exec.execute {
            safe {
                for (f in decoder.feed(data)) Ax25Frame.decode(f)?.let { onFrame(it) }
            }
        }
    }

    /** A frame heard by the phone's own receiver (RTL-SDR: APRS FSK 9600...), handled like one from the tracker. */
    fun onPhoneRadioFrame(frame: Ax25Frame, via: String = "phone receiver") { exec.execute { safe { onFrame(frame, via) } } }

    private fun onFrame(frame: Ax25Frame, via: String? = null) {
        val now = System.currentTimeMillis()
        table.onFrame(frame, now)
        AppState.addLog("RX " + (via?.let { "[$it] " } ?: "") + frame.toTnc2())
        val p = Aprs.parse(frame.infoText)
        if (p is AprsPacket.Message) {
            engine.onMessage(frame.source.text(), p)?.let { msg ->
                notifier.incoming(msg, table.get(msg.peer))
            }
        }
        if (settings.igateEnabled && Licence.isValid(settings)) iGate.toIsLine(frame)?.let { isSend(it) }
        publish()
    }

    private fun transmitInfo(info: String) {
        if (!Licence.isValid(settings)) { AppState.error.value = "Callsign not validated in RadioID: nothing is transmitted"; return }
        val frame = Ax25Frame.create(myCall, "APZLRP", settings.pathList, info)
        if (frame == null) { AppState.error.value = "Invalid callsign or path in the settings"; return }
        AppState.addLog("TX " + frame.toTnc2())
        txQueue.addLast(Kiss.encode(frame.encode()))
        drainTx()
    }

    /** Hands the tracker the next queued frame once the gap has passed since the previous one. */
    private fun drainTx() {
        if (txQueue.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastTxMs < (if (controlOn) TX_GAP_NEW_MS else TX_GAP_OLD_MS)) return
        lastTxMs = now
        transmitBytes(txQueue.removeFirst())
    }

    fun sendMessage(to: String, text: String) {
        if (!Licence.isValid(settings)) { AppState.error.value = "Callsign not validated in RadioID: messages cannot be sent"; return }
        exec.execute {
            safe {
                engine.sendMessage(to, text).onFailure { AppState.error.value = "Message not sent: ${it.message}" }
                publish()
            }
        }
    }

    // ------------------------------------------------------------------ control service

    /** Sends a control command to the tracker (any thread). */
    fun control(name: String, vararg fields: Pair<String, Any?>) {
        if (!controlOn) { AppState.error.value = "The tracker has no control service (update its firmware)"; return }
        if (name !in QUERIES && !Licence.isValid(settings)) {
            AppState.error.value = "Callsign not validated in RadioID: the app cannot command the tracker"
            return
        }
        sendControlLine(Control.command(name, *fields))
    }

    /**
     * Key event from the telegraph key on the phone (any thread). [t] = phone milliseconds; only differences matter.
     * Goes out at once, without response; the tracker plays it back with a fixed delay.
     */
    fun keyEvent(t: Long, down: Boolean) {
        if (!controlOn || !Licence.isValid(settings)) return
        sendControlFast(Control.command("k", "t" to (t and 0xFFFFFFFFL), "d" to if (down) 1 else 0))
    }

    /** The tracker's control service appeared (true) or the link was lost (false). From the BLE thread. */
    fun onControlReady(on: Boolean) {
        exec.execute {
            safe {
                controlOn = on
                AppState.controlReady.value = on
                controlLines.reset(); nmeaLines.reset()
                if (!on) {
                    satWriting = false; satLines.clear(); satListLoading = false
                    if (aidWriting) { aidWriting = false; aidLines.clear(); AppState.gpsAidBusy.value = false; AppState.gpsAidMessage.value = "Link lost" }
                    AppState.satDataBusy.value = false
                    AppState.satReceiver.value = null
                }
                if (on) {
                    sendControlLine(Control.command("info"))
                    sendControlLine(Control.command("status"))
                    sendControlLine(Control.command("dapnet"))
                    sendControlLine(Control.command("msgs", "cnt" to 20))
                    sendControlLine(Control.command("heard", "cnt" to 12))
                    sendControlLine(Control.command("actlist"))
                    sendControlLine(Control.command("sub", "sensors" to 10, "gnss" to if (wantFastGnss) 1 else 5))
                    lastStatusMs = System.currentTimeMillis()
                }
            }
        }
    }

    fun onControlBytes(data: ByteArray) {
        exec.execute { safe { for (line in controlLines.feed(data)) onControlEvent(Control.parse(line)) } }
    }

    private fun onControlEvent(ev: ControlEvent) {
        SdrControl.onEvent(ev)
        when (ev) {
            is ControlEvent.Info -> {
                AppState.trackerInfo.value = ev.info
                nmeaFromTracker = ev.info.hasNmea
                setTrackerNmea(wantNmea && nmeaFromTracker)
            }
            is ControlEvent.Status -> AppState.trackerStatus.value = ev.status
            is ControlEvent.SensorData -> AppState.sensors.value = ev.sensors
            is ControlEvent.Position -> onGnss(ev.gnss)
            is ControlEvent.DapnetHistory -> ev.pages.forEach { addPage(it, notify = false) }
            is ControlEvent.NewPage -> addPage(ev.page, notify = true)
            is ControlEvent.Messages -> {
                AppState.trackerMessageCounts.value = ev.total to ev.unread
                AppState.trackerMessages.value = if (ev.from == 0) ev.items else AppState.trackerMessages.value + ev.items
            }
            is ControlEvent.MessageChanged -> {
                AppState.trackerMessageCounts.value = ev.total to ev.unread
                sendControlLine(Control.command("msgs", "cnt" to 20))
            }
            is ControlEvent.Heard -> {
                AppState.heardCounts.value = ev.total to ev.packets
                AppState.heard.value = if (ev.from == 0) ev.items else AppState.heard.value + ev.items
            }
            is ControlEvent.Screen -> AppState.screen.value = ev.screen
            is ControlEvent.Actions -> {
                if (actionsFresh) { actionsBuilding.clear(); actionsFresh = false }
                actionsBuilding.add(ev.page)
            }
            is ControlEvent.ActionsEnd -> { AppState.actions.value = actionsBuilding.toList(); actionsFresh = true }
            is ControlEvent.ConfigPiece -> onConfigPiece(ev)
            is ControlEvent.Sat -> {
                AppState.satState.value = ev.state
                ev.state.index?.let { AppState.satSelected.value = it }
            }
            is ControlEvent.SatList -> onSatList(ev)
            is ControlEvent.SatTransmitters -> AppState.satTransmitters.value = ev.items
            is ControlEvent.SatRx -> {
                AppState.satReceiver.value = ev.receiver
                ev.receiver.text?.let { t ->
                    val l = AppState.satRxLog.value
                    AppState.satRxLog.value = (if (l.size >= 40) l.drop(l.size - 39) else l) + t
                }
            }
            is ControlEvent.SatTxDone -> {
                AppState.satTxSent.value = ev.count
                AppState.notice.value = if (ev.ok) "Transmitted to the satellite on ${"%.4f".format(java.util.Locale.US, ev.freqHz / 1e6)} MHz (${ev.count} so far)"
                                        else "The tracker did not transmit"
            }
            is ControlEvent.Rotor -> { AppState.rotor.value = ev.pointing; RotctlLink.onPointing(ev.pointing) }
            is ControlEvent.Targets -> {
                AppState.rotorTargets.value = ev.items; AppState.rotorTargetSel.value = ev.selected; AppState.rotorSource.value = ev.source
            }
            is ControlEvent.OtaProgress -> FirmwareUpdater.onProgress(ev.offset, ev.done)
            is ControlEvent.Ok -> when (ev.command) {
                "mode", "reboot" -> AppState.notice.value = "The tracker is restarting; the link comes back by itself"
                "profile", "lora", "digi" -> { sendControlLine(Control.command("status")); sendControlLine(Control.command("info")) }
                "beacon" -> AppState.notice.value = "Beacon requested (it goes out with the next GPS position)"
                "cw" -> if (!AppState.cwActive.value) AppState.notice.value = "CW sent to the tracker"
                "cfgset" -> onConfigStepDone()
                "satdata" -> onSatDataStepDone()
                "gpsaid" -> onGpsAidStepDone()
                "satsel" -> { AppState.satRxLog.value = emptyList(); sendControlLine(Control.command("sattxlist")) }
                "act" -> sendControlLine(Control.command("screen"))
                "msg" -> { AppState.notice.value = "Message queued in the tracker"; sendControlLine(Control.command("msgs", "cnt" to 20)) }
                "ota" -> FirmwareUpdater.onStarted()
            }
            is ControlEvent.Error -> {
                if (ev.command == "ota") FirmwareUpdater.onError(ev.message)
                if (ev.command == "cfgset") onConfigFailed(ev.message)
                if (ev.command == "satdata") onSatDataFailed(ev.message)
                if (ev.command == "gpsaid" && ev.message == "busy") { onGpsAidBusy(); return }
                if (ev.command == "gpsaid") onGpsAidFailed(ev.message)
                if (ev.command == "cfgget") { AppState.configBusy.value = false; AppState.configMessage.value = ev.message }
                AppState.error.value = "Tracker (${ev.command}): ${ev.message}"
            }
            is ControlEvent.Unknown -> AppState.addLog("control? ${ev.raw.take(80)}")
        }
    }

    private fun onGnss(g: Gnss) {
        AppState.gnss.value = g
        mock?.onGnss(g)
        if (nmeaServer != null && !nmeaFromTracker && g.fix) {
            Nmea.rmc(g)?.let { nmeaServer.send(it); AppState.addNmea(it.trimEnd()) }
            Nmea.gga(g)?.let { nmeaServer.send(it); AppState.addNmea(it.trimEnd()) }
        }
    }

    /** Adds a page unless it is already listed (the tracker's history repeats what was pushed). */
    private fun addPage(p: Page, notify: Boolean) {
        val at = System.currentTimeMillis() - p.ageS * 1000
        val list = AppState.pages.value
        if (list.any { it.page.ric == p.ric && it.page.text == p.text && abs(it.atMs - at) < 15_000 }) return
        AppState.pages.value = (listOf(ReceivedPage(p, at)) + list).sortedByDescending { it.atMs }.take(MAX_PAGES)
        if (notify) notifier.page(p)
    }

    // ------------------------------------------------------------------ the tracker's configuration file (whole file, piece by piece)

    /** Reads /tracker_conf.json from the tracker into [AppState.config] (any thread). */
    fun configRead() {
        exec.execute {
            safe {
                if (!controlOn) { AppState.configMessage.value = "No control service"; return@safe }
                if (AppState.configBusy.value) return@safe
                configBytes.reset(); configNext = 0; configWriting = false
                AppState.configBusy.value = true
                AppState.configMessage.value = "Reading..."
                configDeadlineMs = System.currentTimeMillis() + 8000
                sendControlLine(Control.command("cfgget", "o" to 0))
            }
        }
    }

    private fun onConfigPiece(p: ControlEvent.ConfigPiece) {
        if (!AppState.configBusy.value || configWriting) return
        if (p.offset != configNext) { AppState.configBusy.value = false; AppState.configMessage.value = "Configuration read out of order"; return }
        configBytes.write(p.text.toByteArray(Charsets.UTF_8))
        configNext = p.offset + p.bytes
        configDeadlineMs = System.currentTimeMillis() + 8000
        if (p.bytes == 0 || configNext >= p.total) {
            AppState.config.value = String(configBytes.toByteArray(), Charsets.UTF_8)
            AppState.configBusy.value = false
            AppState.configMessage.value = "Read ${configNext} bytes"
        } else {
            AppState.configMessage.value = "Reading... ${configNext * 100 / p.total} %"
            sendControlLine(Control.command("cfgget", "o" to configNext))
        }
    }

    /** Replaces the tracker's configuration with [json] (the whole file). The tracker restarts when it is stored. */
    fun configWrite(json: String, reboot: Boolean = true) {
        exec.execute {
            safe {
                if (!controlOn) { AppState.configMessage.value = "No control service"; return@safe }
                if (!Licence.isValid(settings)) { AppState.error.value = "Callsign not validated in RadioID: the app cannot command the tracker"; return@safe }
                if (AppState.configBusy.value) return@safe
                if (Json.parseObject(json) == null) { AppState.configMessage.value = "Not valid JSON: nothing sent"; return@safe }
                configLines.clear(); configLines.addAll(Control.configSet(json, reboot))
                configSteps = configLines.size
                configWriting = true
                AppState.configBusy.value = true
                configNextLine()
            }
        }
    }

    private fun configNextLine() {
        val line = configLines.removeFirstOrNull()
        if (line == null) {
            configWriting = false
            AppState.configBusy.value = false
            AppState.configMessage.value = "Configuration stored; the tracker restarts"
            return
        }
        configDeadlineMs = System.currentTimeMillis() + 8000
        AppState.configMessage.value = "Sending... ${(configSteps - configLines.size) * 100 / configSteps} %"
        sendControlLine(line)
    }

    private fun onConfigStepDone() { if (configWriting) configNextLine() }

    private fun onConfigFailed(message: String) {
        if (!configWriting) return
        configWriting = false; configLines.clear()
        AppState.configBusy.value = false
        AppState.configMessage.value = "Tracker refused the configuration: $message"
        sendControlLine(Control.command("cfgset", "s" to "abort"))
    }

    // ------------------------------------------------------------------ SAT mode: list, file of satellites, phone position

    /** Asks the tracker for its whole list of satellites, page by page (any thread). */
    fun satListRead() {
        exec.execute {
            safe {
                if (!controlOn) return@safe
                satListLoading = true
                sendControlLine(Control.command("satlist", "from" to 0, "cnt" to SAT_LIST_PAGE))
            }
        }
    }

    private fun onSatList(ev: ControlEvent.SatList) {
        AppState.satListTotal.value = ev.total
        AppState.satSelected.value = ev.selected
        if (!satListLoading && ev.from != 0) return
        AppState.satList.value = if (ev.from == 0) ev.items else AppState.satList.value + ev.items
        val got = ev.from + ev.items.size
        if (satListLoading && ev.items.isNotEmpty() && got < ev.total && got < SAT_LIST_MAX) {
            sendControlLine(Control.command("satlist", "from" to got, "cnt" to SAT_LIST_PAGE))
        } else {
            satListLoading = false
        }
    }

    /** Sends the text of a `sats.txt` to the tracker, step by step (any thread). The tracker validates it and reloads its list. */
    fun satDataWrite(text: String) {
        exec.execute {
            safe {
                if (!controlOn) { AppState.satDataMessage.value = "No control service"; return@safe }
                if (!Licence.isValid(settings)) { AppState.error.value = "Callsign not validated in RadioID: the app cannot command the tracker"; return@safe }
                if (AppState.satDataBusy.value) return@safe
                val bad = Sat.checkData(text)
                if (bad != null) { AppState.satDataMessage.value = bad; return@safe }
                satLines.clear(); satLines.addAll(Control.satData(text))
                satSteps = satLines.size
                satWriting = true
                AppState.satDataBusy.value = true
                satDataNextLine()
            }
        }
    }

    private fun satDataNextLine() {
        val line = satLines.removeFirstOrNull()
        if (line == null) {
            satWriting = false
            AppState.satDataBusy.value = false
            AppState.satDataMessage.value = "Satellite file stored in the tracker"
            satListRead()
            sendControlLine(Control.command("sattxlist"))
            return
        }
        satDeadlineMs = System.currentTimeMillis() + 8000
        AppState.satDataMessage.value = "Sending to the tracker... ${(satSteps - satLines.size) * 100 / satSteps} %"
        sendControlLine(line)
    }

    private fun onSatDataStepDone() { if (satWriting) satDataNextLine() }

    private fun onSatDataFailed(message: String) {
        if (!satWriting) return
        satWriting = false; satLines.clear()
        AppState.satDataBusy.value = false
        AppState.satDataMessage.value = "Tracker refused the file: $message"
        sendControlLine(Control.command("satdata", "s" to "abort"))
    }

    /**
     * Sends A-GPS data (UBX-MGA messages) to the tracker's u-blox receiver, step by step (any thread). [what] names it in the
     * messages ("time and position", "orbits"...).
     */
    fun gpsAidWrite(data: ByteArray, what: String) {
        exec.execute {
            safe {
                if (!controlOn) { AppState.gpsAidMessage.value = "No control service"; return@safe }
                if (AppState.trackerInfo.value?.hasGpsAid != true) { AppState.gpsAidMessage.value = "The tracker firmware or its GNSS receiver does not take A-GPS (u-blox only)"; return@safe }
                if (aidWriting) return@safe
                aidLines.clear(); aidLines.addAll(es.ea7jrs.aprslorap.core.Ubx.aidLines(data))
                aidSteps = aidLines.size
                aidDoneText = "A-GPS sent to the tracker: $what (${data.size} bytes)"
                aidWriting = true
                AppState.gpsAidBusy.value = true
                gpsAidNextLine()
            }
        }
    }

    private fun gpsAidNextLine() {
        val line = aidLines.removeFirstOrNull()
        aidCurrent = line
        if (line == null) {
            aidWriting = false
            AppState.gpsAidBusy.value = false
            AppState.gpsAidMessage.value = aidDoneText
            return
        }
        aidDeadlineMs = System.currentTimeMillis() + 8000
        AppState.gpsAidMessage.value = "Sending A-GPS to the tracker... ${(aidSteps - aidLines.size) * 100 / aidSteps} %"
        sendControlLine(line)
    }

    private fun onGpsAidStepDone() { if (aidWriting) gpsAidNextLine() }

    /** The tracker's buffer for the GNSS port is full (slow UART, 9600 baud): the same piece again a bit later. */
    private fun onGpsAidBusy() {
        val line = aidCurrent ?: return
        if (!aidWriting) return
        aidDeadlineMs = System.currentTimeMillis() + 8000
        exec.schedule({ safe { if (aidWriting && aidCurrent === line) { aidDeadlineMs = System.currentTimeMillis() + 8000; sendControlLine(line) } } }, 300, TimeUnit.MILLISECONDS)
    }

    private fun onGpsAidFailed(message: String) {
        if (!aidWriting) return
        aidWriting = false; aidLines.clear()
        AppState.gpsAidBusy.value = false
        AppState.gpsAidMessage.value = "Tracker refused the A-GPS data: $message"
        sendControlLine(Control.command("gpsaid", "s" to "abort"))
    }

    /** The phone's position, for the tracker's SAT mode (any thread). The tracker forgets it after 2 minutes: it is repeated here. */
    fun phonePosition(lat: Double, lon: Double, altM: Double?, accM: Float?) {
        exec.execute {
            safe {
                phoneLat = lat; phoneLon = lon; phoneAlt = altM; phoneAcc = accM; phoneAtMs = System.currentTimeMillis()
                sendPhonePosition(force = true)
            }
        }
    }

    /** Stops repeating the phone position (the tracker uses its own GNSS again after 2 minutes). */
    fun phonePositionOff() { exec.execute { safe { phoneAtMs = 0 } } }

    private fun sendPhonePosition(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!controlOn || phoneAtMs == 0L || now - phoneAtMs > 90_000) return
        if (!force && now - phoneSentMs < Sat.POSITION_EVERY_MS) return
        val line = Sat.position(phoneLat, phoneLon, phoneAlt, phoneAcc) ?: return
        phoneSentMs = now
        sendControlLine(line)
    }

    // ------------------------------------------------------------------ NMEA from the tracker

    fun onNmeaBytes(data: ByteArray) {
        exec.execute {
            safe {
                for (line in nmeaLines.feed(data)) {
                    if (!Nmea.valid(line)) continue
                    nmeaServer?.send(line)
                    AppState.addNmea(line)
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private fun tick() {
        if (engine.tick().isNotEmpty()) publish()
        drainTx()
        val now = System.currentTimeMillis()
        if (AppState.configBusy.value && now > configDeadlineMs) {
            if (configWriting) { configWriting = false; configLines.clear(); sendControlLine(Control.command("cfgset", "s" to "abort")) }
            AppState.configBusy.value = false
            AppState.configMessage.value = "The tracker did not answer"
        }
        if (AppState.satDataBusy.value && satWriting && now > satDeadlineMs) {
            satWriting = false; satLines.clear()
            AppState.satDataBusy.value = false
            AppState.satDataMessage.value = "The tracker did not answer"
            sendControlLine(Control.command("satdata", "s" to "abort"))
        }
        if (aidWriting && now > aidDeadlineMs) {
            aidWriting = false; aidLines.clear()
            AppState.gpsAidBusy.value = false
            AppState.gpsAidMessage.value = "The tracker did not answer"
            sendControlLine(Control.command("gpsaid", "s" to "abort"))
        }
        sendPhonePosition(force = false)
        if (controlOn && now - lastStatusMs >= STATUS_EVERY_MS) {
            lastStatusMs = now
            sendControlLine(Control.command("status"))
        }
    }

    private fun publish() {
        AppState.messages.value = engine.all()
        AppState.stations.value = table.recent()
    }

    fun stop() {
        exec.shutdownNow()
    }
}
