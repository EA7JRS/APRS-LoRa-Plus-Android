/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.ChatMessage
import es.ea7jrs.aprslorap.core.ActionPage
import es.ea7jrs.aprslorap.core.Gnss
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
    val pages = MutableStateFlow<List<ReceivedPage>>(emptyList())        // DAPNET, newest first
    val sentPages = MutableStateFlow<List<String>>(emptyList())          // DAPNET pages sent from the phone (log lines)
    // What the tracker itself holds: its message log, stations heard, screen and menu options, configuration file
    val trackerMessages = MutableStateFlow<List<TrackerMessage>>(emptyList())   // newest first
    val trackerMessageCounts = MutableStateFlow(0 to 0)                          // total, unread
    val heard = MutableStateFlow<List<HeardStation>>(emptyList())
    val heardCounts = MutableStateFlow(0 to 0L)                                  // stations, packets
    val screen = MutableStateFlow<ScreenMirror?>(null)
    val actions = MutableStateFlow<List<ActionPage>>(emptyList())
    val config = MutableStateFlow<String?>(null)                                 // /tracker_conf.json as read from the tracker
    val configBusy = MutableStateFlow(false)
    val configMessage = MutableStateFlow("")
    val nmeaStatus = MutableStateFlow("Off")
    val nmeaRecent = MutableStateFlow<List<String>>(emptyList())
    val mockStatus = MutableStateFlow("Off")
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
    val fwWifi = MutableStateFlow<es.ea7jrs.aprslorap.core.FwStatus?>(null)

    @Volatile var controller: Controller? = null
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
