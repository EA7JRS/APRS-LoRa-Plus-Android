/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.ControlEvent
import es.ea7jrs.aprslorap.core.ChatMessage
import es.ea7jrs.aprslorap.core.BcastState
import es.ea7jrs.aprslorap.core.FmChannel
import es.ea7jrs.aprslorap.core.FmState
import es.ea7jrs.aprslorap.core.ActionPage
import es.ea7jrs.aprslorap.core.Gnss
import es.ea7jrs.aprslorap.core.RotorPointing
import es.ea7jrs.aprslorap.core.RotorTarget
import es.ea7jrs.aprslorap.core.SatEntry
import es.ea7jrs.aprslorap.core.SatReceiver
import es.ea7jrs.aprslorap.core.SatState
import es.ea7jrs.aprslorap.core.SatTransmitter
import es.ea7jrs.aprslorap.core.HeardStation
import es.ea7jrs.aprslorap.core.ScreenMirror
import es.ea7jrs.aprslorap.core.TrackerMessage
import es.ea7jrs.aprslorap.core.Page
import es.ea7jrs.aprslorap.core.Sensors
import es.ea7jrs.aprslorap.core.Station
import es.ea7jrs.aprslorap.core.TrackerInfo
import es.ea7jrs.aprslorap.core.TrackerStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** State shown by the screens; the service and the controller write it, the UI only reads it. */
object AppState {
    val bleStatus = MutableStateFlow("Not connected")
    val igateStatus = MutableStateFlow("Off")
    val stations = MutableStateFlow<List<Station>>(emptyList())
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val log = MutableStateFlow<List<String>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    /** Short notice that is not an error (e.g. "the tracker is restarting"). */
    val notice = MutableStateFlow<String?>(null)

    // Control service of the tracker (firmware with docs/BLE_CONTROL.md)
    val controlReady = MutableStateFlow(false)
    val trackerInfo = MutableStateFlow<TrackerInfo?>(null)
    val trackerStatus = MutableStateFlow<TrackerStatus?>(null)
    val sensors = MutableStateFlow<Sensors?>(null)
    val gnss = MutableStateFlow<Gnss?>(null)
    val sky = MutableStateFlow<es.ea7jrs.aprslorap.core.SkyView?>(null)            // satellites of the tracker's receiver (event `sky`)
    val phoneGnss = MutableStateFlow(PhoneGnss.State())                            // the phone's own GNSS and sensors (DISP > GPS)
    val pages = MutableStateFlow<List<ReceivedPage>>(emptyList())        // DAPNET, newest first
    val sentPages = MutableStateFlow<List<String>>(emptyList())          // DAPNET pages sent from the phone (log lines)
    // What the tracker itself holds: its message log, stations heard, screen and menu options, configuration file
    val trackerMessages = MutableStateFlow<List<TrackerMessage>>(emptyList())   // newest first
    val trackerMessageCounts = MutableStateFlow(0 to 0)                          // total, unread
    val heard = MutableStateFlow<List<HeardStation>>(emptyList())
    // FM voice mode of the T-TWR
    val fmState = MutableStateFlow<FmState?>(null)
    val fmMemories = MutableStateFlow<List<FmChannel>>(emptyList())
    val bcastState = MutableStateFlow<BcastState?>(null)                         // broadcast receiver on the tracker's I2C
    val heardCounts = MutableStateFlow(0 to 0L)                                  // stations, packets
    val screen = MutableStateFlow<ScreenMirror?>(null)
    val actions = MutableStateFlow<List<ActionPage>>(emptyList())
    val config = MutableStateFlow<String?>(null)                                 // /tracker_conf.json as read from the tracker
    val configBusy = MutableStateFlow(false)
    val configMessage = MutableStateFlow("")
    // SAT mode: current pass, list of satellites, transmitters, receiver, rotor
    val satState = MutableStateFlow<SatState?>(null)
    /** Weather from the internet (OpenWeatherMap / IQAir) for the tracker's TELEMETRY page, and the last message of the download. */
    val netWx = MutableStateFlow<es.ea7jrs.aprslorap.core.NetWeather.Data?>(null)
    val netWxMessage = MutableStateFlow("")
    /** Official alerts in force (OfficialAlertsFetcher + SAME), the most severe first; space weather; status line. */
    val officialAlerts = MutableStateFlow<List<es.ea7jrs.aprslorap.core.OfficialAlert>>(emptyList())
    val spaceWx = MutableStateFlow<es.ea7jrs.aprslorap.core.SpaceWx?>(null)
    /** Nearest trustworthy ionosonde (GIRO via KC2G) to the tracker's / phone's position, with the measured foF2 / MUF. */
    val ionosonde = MutableStateFlow<es.ea7jrs.aprslorap.core.HfProp.Ionosonde?>(null)
    val officialMessage = MutableStateFlow("")
    /** AIS vessels received by the tracker (by MMSI) and the state of its receiver. */
    val aisVessels = MutableStateFlow<Map<Long, es.ea7jrs.aprslorap.core.AisVessel>>(emptyMap())
    val aisSource = MutableStateFlow("")
    val aisMessages = MutableStateFlow(0)
    val vessel = MutableStateFlow<es.ea7jrs.aprslorap.core.Vessel.State?>(null)  // own vessel data of the nautical AIS profile
    val satList = MutableStateFlow<List<SatEntry>>(emptyList())
    val satSelected = MutableStateFlow(-1)                                       // index in the tracker's list
    val satListTotal = MutableStateFlow(0)
    val satTransmitters = MutableStateFlow<List<SatTransmitter>>(emptyList())
    val satReceiver = MutableStateFlow<SatReceiver?>(null)
    val satRxLog = MutableStateFlow<List<String>>(emptyList())                   // received lines, newest last
    val satTxSent = MutableStateFlow(0)                                          // transmissions made in this link
    val rotor = MutableStateFlow<RotorPointing?>(null)
    val rotorTargets = MutableStateFlow<List<RotorTarget>>(emptyList())
    val rotorTargetSel = MutableStateFlow(0)
    val rotorSource = MutableStateFlow(0)
    val satDataBusy = MutableStateFlow(false)
    val satDataMessage = MutableStateFlow("")
    val gpsAidBusy = MutableStateFlow(false)                                     // A-GPS going to the tracker
    val gpsAidMessage = MutableStateFlow("")
    val phoneAgpsMessage = MutableStateFlow("")                                  // A-GPS of the phone itself
    val ssdvState = MutableStateFlow<es.ea7jrs.aprslorap.core.SsdvState?>(null)  // SSDV image mode (status / progress)
    val ssdvUploadBusy = MutableStateFlow(false)                                 // a JPEG is going to the tracker over BLE
    val ssdvUploadMessage = MutableStateFlow("")
    val ssdvRxImage = MutableStateFlow<ByteArray?>(null)                         // last received SSDV image downloaded from the tracker (JPEG)
    val ssdvRxBusy = MutableStateFlow(false)                                     // downloading a received image from the tracker
    val ssdvRxMessage = MutableStateFlow("")
    val ssdvSdrRunning = MutableStateFlow(false)                                 // the app's SDR is decoding SSDV-FSK
    val ssdvSdrImage = MutableStateFlow<ByteArray?>(null)                        // last SSDV image decoded from the SDR (JPEG)
    val ssdvSdrStatus = MutableStateFlow("")
    // Phone sensors, rotctld, RepeaterBook
    val phoneSensors = MutableStateFlow(PhoneSensors.Reading())
    val attStatus = MutableStateFlow("Off")
    val rotClientStatus = MutableStateFlow("Off")
    val rotClientActual = MutableStateFlow<Pair<Double, Double>?>(null)          // what the network rotor reports
    val rotServerStatus = MutableStateFlow("Off")
    val rotServerLast = MutableStateFlow<Pair<Double, Double>?>(null)            // last az / el from Look4Sat, GPredict...
    // APRS LoRa+ Rotor box (Rotor tab)
    val rotorLinkStatus = MutableStateFlow("Off")
    val rotorInfo = MutableStateFlow<es.ea7jrs.aprslorap.core.RotorProto.Info?>(null)
    val rotorPos = MutableStateFlow<es.ea7jrs.aprslorap.core.RotorProto.Position?>(null)
    val rotorRaw = MutableStateFlow<es.ea7jrs.aprslorap.core.RotorProto.Event.Raw?>(null)
    val rotorAuthed = MutableStateFlow(false)
    val rotorConfig = MutableStateFlow<String?>(null)                             // configuration as read from the box
    val rotorBusy = MutableStateFlow(false)
    val rotorMessage = MutableStateFlow("")
    val rotorFollowSent = MutableStateFlow("")
    val rotorCat = MutableStateFlow<es.ea7jrs.aprslorap.core.RotorProto.CatState?>(null)   // radio on the rotor box (event `cat`)
    val aircraft = MutableStateFlow<List<es.ea7jrs.aprslorap.core.Adsb.Aircraft>>(emptyList())   // ADS-B received by the app (AdsbLink)
    val beacons = MutableStateFlow<List<es.ea7jrs.aprslorap.core.Beacons.Heard>>(emptyList())    // beacons heard by the app's receivers (BeaconHub)
    val phoneCat = MutableStateFlow<es.ea7jrs.aprslorap.core.RotorProto.CatState?>(null)   // radio plugged into the phone (USB CAT)
    val phoneCatStatus = MutableStateFlow("Off")
    val rotorTrackerFollow = MutableStateFlow<es.ea7jrs.aprslorap.core.ControlEvent.Targets?>(null)   // tracker's rotor settings (sources 4 / 5)
    val heardAtMs = MutableStateFlow(0L)                                          // when the tracker's heard list arrived
    val rbBusy = MutableStateFlow(false)
    val sdr = MutableStateFlow(SdrControl.State())
    val sigIdInfo = MutableStateFlow<SigIdDb.Info?>(null)                       // signal identification database
    val sigIdBusy = MutableStateFlow(false)
    val sigIdMessage = MutableStateFlow("")
    val modem = MutableStateFlow(ModemRunner.State())                           // text modes (CW, RTTY, PSK)
    val modemText = MutableStateFlow("")
    val signalId = MutableStateFlow<es.ea7jrs.aprslorap.core.SignalIdentify.Report?>(null)   // "Identify" in the SDR tab: null = idle; empty modes = running/none
    val waterfallRow = MutableStateFlow<FloatArray?>(null)
    /** Panadapter row of the app's RTL-SDR receiver (null = no receiver): dB per bin, centre = tuner, span = input rate. */
    class SdrSpectrum(val row: FloatArray, val centerHz: Long, val spanHz: Int, val markerHz: Long, val t: Long = System.nanoTime())
    val sdrSpectrum = MutableStateFlow<SdrSpectrum?>(null)
    val wsjt = MutableStateFlow(WsjtRunner.State())                              // FT8 / FT4
    val wsjtDecodes = MutableStateFlow<List<es.ea7jrs.aprslorap.core.Wsjt.Decode>>(emptyList())
    val extraStatus = MutableStateFlow<Map<String, String>>(emptyMap())          // receiver key -> status
    val qsoLog = MutableStateFlow<List<es.ea7jrs.aprslorap.core.Qso>>(emptyList())    // the logbook (Log tab), newest first
    val logStatus = MutableStateFlow("")
    val logDraft = MutableStateFlow<es.ea7jrs.aprslorap.core.Qso?>(null)              // contact to open in the Log form (from the DX cluster)
    val circuitAsk = MutableStateFlow<es.ea7jrs.aprslorap.core.Wefax.Ask?>(null)       // HF circuit to predict in the Wefax tool (from the DX cluster or the logbook)
    val lotwCert = MutableStateFlow<LogStore.Lotw12?>(null)                       // TrustedQSL certificate loaded
    val spotter = MutableStateFlow(Spotter.State())                               // PSK Reporter, WSPRnet, WSJT-X UDP, skimmer port
    val cwRxOn = MutableStateFlow(false)                                          // tracker's CW listening on RF
    val cwRxText = MutableStateFlow("")
    val cwRxLevel = MutableStateFlow("")
    val cwRxDbm = MutableStateFlow<Double?>(null)                                  // level of the tracker's CW listening (S-meter of band A)
    val rfTxSinceMs = MutableStateFlow(0L)                                        // band A TX light: the tracker is sending a frame since (0 = not)
    val rfRxAtMs = MutableStateFlow(0L)                                           // band A RX light: last frame heard by the tracker
    val trackerLeds = MutableStateFlow<ControlEvent.Led?>(null)                   // MSG / EMG lights and the white flash of the tracker
    val packetStatus = MutableStateFlow("Off")                                   // packet FSK (G3RUH) from the app receiver
    val packetLog = MutableStateFlow<List<String>>(emptyList())
    val aptStatus = MutableStateFlow("Off")                                      // NOAA APT image decoder
    val aptVersion = MutableStateFlow(0)                                         // bumped per decoded line to recompose the image
    val dvStatus = MutableStateFlow("Off")                                       // digital voice decoder (dsd-fme)
    val dvLog = MutableStateFlow<List<String>>(emptyList())                               // SDR program / server under control
    val rbMessage = MutableStateFlow("")
    val rbResults = MutableStateFlow<List<es.ea7jrs.aprslorap.core.RepeaterBook.Repeater>>(emptyList())
    val phonePosition = MutableStateFlow("Off")                                  // what the app tells about the phone position it sends
    val nmeaStatus = MutableStateFlow("Off")
    val nmeaRecent = MutableStateFlow<List<String>>(emptyList())
    val mockStatus = MutableStateFlow("Off")
    val phoneBeacon = MutableStateFlow("Off")                                    // position beacons from the phone: state and the last one sent
    // Telegraph key on the phone
    val cwActive = MutableStateFlow(false)
    val cwInputStatus = MutableStateFlow("Off")
    val cwKeyDown = MutableStateFlow(false)
    val cwText = MutableStateFlow("")                                      // decoded from your keying
    // Firmware
    val fwLatest = MutableStateFlow<es.ea7jrs.aprslorap.core.FirmwareSite.Release?>(null)
    val fwBusy = MutableStateFlow(false)
    val fwProgress = MutableStateFlow(0)
    val fwMessage = MutableStateFlow("")

    @Volatile var controller: Controller? = null
    /** The NMEA TCP server when it is on (NAVTEX messages go out as NRX for the plotter and other programs). */
    @Volatile var nmeaOut: NmeaServer? = null
    /** Application context for helpers that run from the Controller (internet weather). */
    @Volatile var appContext: android.content.Context? = null
    @Volatile var notifier: Notifier? = null
    @Volatile var ble: TrackerBle? = null

    fun addNmea(line: String) {
        val l = nmeaRecent.value
        nmeaRecent.value = (if (l.size >= 12) l.drop(l.size - 11) else l) + line
    }

    fun addLog(line: String) {
        val l = log.value
        log.value = (if (l.size >= 60) l.drop(l.size - 59) else l) + line
    }
}

/** A DAPNET page with the phone time at which it was received by the tracker. */
data class ReceivedPage(val page: Page, val atMs: Long)
