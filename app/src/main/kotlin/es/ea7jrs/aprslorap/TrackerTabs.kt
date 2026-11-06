/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Dapnet
import es.ea7jrs.aprslorap.core.TrackerMode
import java.util.Locale

internal fun fmt(v: Double?, pattern: String, unit: String): String =
    if (v == null) "—" else String.format(Locale.US, pattern, v) + unit

@Composable
internal fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun NoControl() {
    val running by AppState.running.collectAsState()
    Text(
        if (!running) "Start the link in the Connect tab."
        else "The tracker has not offered the control service. The Connect tab log says which BLE services it found: " +
            "\"control NO\" means the firmware on the tracker is not the ble-control one (flash it again from that branch).",
        Modifier.padding(16.dp),
    )
}

/** Tracker: mode, beacon profile, LoRa profile, beacon now, digipeater, CW. */
@Composable
fun TrackerTab() {
    val ready by AppState.controlReady.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val status by AppState.trackerStatus.collectAsState()
    var askMode by remember { mutableStateOf<Int?>(null) }
    var cwText by rememberSaveable { mutableStateOf("") }
    val i = info
    if (!ready || i == null) { NoControl(); return }
    val c = AppState.controller
    val mode = status?.mode ?: i.mode

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Firmware", "${i.firmware} (${i.board})")
        Field("Mode", TrackerMode.name(mode))
        status?.let { s ->
            Field("Battery", fmt(s.batteryV, "%.2f", " V"))
            Field("Up", "${s.uptimeS / 3600} h ${(s.uptimeS % 3600) / 60} min")
            if (s.sos) Text("SOS / emergency beacon ACTIVE", fontWeight = FontWeight.Bold)
        }

        Section("Radio mode (the tracker restarts)")
        if (!i.canChangeMode) Text("This board only has APRS modes.", fontSize = 12.sp)
        for (m in i.modes) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = m.mode != mode) { askMode = m.mode }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (m.mode == mode) "●" else "○", Modifier.width(24.dp))
                Text(m.name)
            }
        }

        Section("Beacon profile (callsign)")
        i.profiles.forEachIndexed { idx, p ->
            val now = (status?.profile ?: i.profile) == idx
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !now) { c?.control("profile", "i" to idx) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (now) "●" else "○", Modifier.width(24.dp))
                Text(if (p.label.isBlank()) p.callsign else "${p.callsign}  (${p.label})")
            }
        }

        if (TrackerMode.isAprs(mode)) {
            Section("LoRa profile")
            i.loras.forEachIndexed { idx, l ->
                val now = (status?.lora ?: i.lora) == idx
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !now) { c?.control("lora", "i" to idx) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (now) "●" else "○", Modifier.width(24.dp))
                    Text(l.text)
                }
            }
            Section("APRS")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { c?.control("beacon") }) { Text("Send beacon") }
                Spacer(Modifier.width(8.dp))
                Switch(checked = status?.digi ?: false, onCheckedChange = { c?.control("digi", "on" to it) })
                Text("Digipeater")
            }
        }

        if (mode == TrackerMode.MORSE && i.hasCw) {
            Section("CW")
            i.cwMessages.forEachIndexed { idx, msg ->
                if (msg.isNotBlank()) OutlinedButton(onClick = { c?.control("cw", "i" to idx) }, modifier = Modifier.fillMaxWidth()) { Text("${idx + 1}: $msg") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(cwText, { cwText = it.take(60) }, label = { Text("Free text") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = cwText.isNotBlank(), onClick = { c?.control("cw", "t" to cwText.trim()); cwText = "" }) { Text("Send") }
            }
            PhoneKeySection(i.hasRemoteKey)
        }

        Section("")
        OutlinedButton(onClick = { c?.control("reboot") }) { Text("Restart tracker") }
    }

    askMode?.let { m ->
        AlertDialog(
            onDismissRequest = { askMode = null },
            title = { Text("Change to ${TrackerMode.name(m)}?") },
            text = {
                Text(
                    "The tracker restarts in that mode and the link comes back by itself. " +
                        (if (TrackerMode.isAprs(m)) "It stays as the start-up mode." else "Only for this start: the next one goes back to the saved APRS mode."),
                )
            },
            confirmButton = { TextButton(onClick = { c?.control("mode", "m" to m); askMode = null }) { Text("Change") } },
            dismissButton = { TextButton(onClick = { askMode = null }) { Text("Cancel") } },
        )
    }
}

/** Sensors, GNSS and NMEA. */
@Composable
fun SensorsTab() {
    val ready by AppState.controlReady.collectAsState()
    val sensors by AppState.sensors.collectAsState()
    val gnss by AppState.gnss.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val nmeaStatus by AppState.nmeaStatus.collectAsState()
    val mockStatus by AppState.mockStatus.collectAsState()
    val nmea by AppState.nmeaRecent.collectAsState()
    val aidBusy by AppState.gpsAidBusy.collectAsState()
    val aidMsg by AppState.gpsAidMessage.collectAsState()
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var agpsUrl by rememberSaveable { mutableStateOf(settings.agpsUrl) }
    if (!ready) { NoControl(); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("GNSS")
        val g = gnss
        if (g == null) Text("No data yet") else {
            Field("Fix", if (g.fix) "Yes" else "No")
            Field("Position", if (g.lat != null && g.lon != null) String.format(Locale.US, "%.6f, %.6f", g.lat, g.lon) else "—")
            Field("Altitude", fmt(g.altitudeM, "%.0f", " m"))
            Field("Speed", fmt(g.speedKmh, "%.1f", " km/h"))
            Field("Course", fmt(g.courseDeg, "%.0f", "°"))
            Field("Satellites", g.satellites?.toString() ?: "—")
            Field("HDOP", fmt(g.hdop, "%.1f", ""))
            Field("UTC", g.utc ?: "—")
        }

        Section("A-GPS (u-blox receiver)")
        if (info?.hasGpsAid != true) Text("Not available: the tracker firmware is too old (no gpsaid command) or its GNSS receiver is not a u-blox.", fontSize = 12.sp)
        else {
            Button(onClick = { GpsAid.sendTimeAndPosition(ctx) }, enabled = !aidBusy) { Text("Send time and position") }
            Text("The phone's time and last real position (MGA-INI): the first fix comes sooner. No account needed.", fontSize = 11.sp)
            OutlinedTextField(agpsUrl, { agpsUrl = it }, label = { Text("AssistNow Predictive Orbits address (with your token)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { settings.agpsUrl = agpsUrl; GpsAid.sendOrbits(ctx, agpsUrl.trim()) }, enabled = !aidBusy && agpsUrl.isNotBlank()) { Text("Download orbits and send") }
            Text("Needs a free u-blox Thingstream account (AssistNow, Predictive Orbits for M10). The file is good for days; it is downloaded at most every 12 h (u-blox allows 2 a day).", fontSize = 11.sp)
            if (aidMsg.isNotBlank()) Text(aidMsg, fontSize = 13.sp)
        }

        Section("Sensors")
        val s = sensors
        if (s == null) Text("No data yet") else {
            Field("Battery", fmt(s.batteryV, "%.2f", " V"))
            Field("Temperature", fmt(s.temperatureC, "%.1f", " °C"))
            Field("Humidity", fmt(s.humidityPct, "%.0f", " %"))
            Field("Pressure", fmt(s.pressureHpa, "%.1f", " hPa"))
            Field("QNH", fmt(s.qnhHpa, "%.1f", " hPa"))
            Field("Barometric altitude", fmt(s.baroAltitudeM, "%.0f", " m"))
            Field("Magnetic heading", fmt(s.headingDeg, "%.0f", "°"))
            Field("Moving (IMU)", s.moving?.let { if (it) "Yes" else "No" } ?: "—")
            Field("IMU temperature", fmt(s.imuTemperatureC, "%.1f", " °C"))
            if (s.boardTemperatureC != null) Field("Board temperature", fmt(s.boardTemperatureC, "%.1f", " °C"))
            if (s.gasKohm != null) Field("Gas resistance (${s.wxSensor ?: "BME680"})", fmt(s.gasKohm, "%.1f", " kΩ"))
            if (s.iaq != null) Field("Air quality (IAQ)", fmt(s.iaq, "%.0f", "") + (s.iaqLabel?.let { " ($it)" } ?: ""))
            s.accelG?.let { if (it.size == 3) Field("Acceleration", String.format(Locale.US, "%.2f, %.2f, %.2f g", it[0], it[1], it[2])) }
            s.gyroDps?.let { if (it.size == 3) Field("Gyroscope", String.format(Locale.US, "%.1f, %.1f, %.1f °/s", it[0], it[1], it[2])) }
            if (s.flat != null) Field("Lying flat", if (s.flat == true) "Yes" else "No")
            if (s.magCalibrated != null) Field("Compass calibrated", if (s.magCalibrated == true) "Yes" else "No")
            if (s.magAligned != null) Field("Compass aligned to the board", if (s.magAligned == true) "Yes" else "No")
        }

        Section("Power")
        val pw = sensors
        if (pw == null) Text("No data yet") else {
            Field("Battery", fmt(pw.batteryV, "%.2f", " V") + (pw.batteryPct?.let { "  ($it %)" } ?: ""))
            if (pw.charging != null) Field("Charging", if (pw.charging == true) "Yes" else "No")
            if (pw.batteryMa != null) Field("Battery current", fmt(pw.batteryMa, "%.0f", " mA"))
            if (pw.vbusV != null) Field("USB / external", fmt(pw.vbusV, "%.2f", " V"))
            if (pw.pmuTemperatureC != null) Field("PMU temperature", fmt(pw.pmuTemperatureC, "%.1f", " °C"))
            pw.freeHeap?.let { Field("Free memory", "${it / 1024} kB") }
        }

        Section("NMEA")
        Field("From the tracker", if (info?.hasNmea == true) "Yes (GNSS, weather, AIS of APRS stations)" else "No: the phone builds RMC/GGA")
        Field("TCP server", nmeaStatus)
        Field("Mock location", mockStatus)
        HorizontalDivider()
        for (line in nmea.takeLast(12)) Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

/** DAPNET: pages received by the tracker and pages sent from the phone (network API). */
@Composable
fun DapnetTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val pages by AppState.pages.collectAsState()
    val sent by AppState.sentPages.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val status by AppState.trackerStatus.collectAsState()
    var to by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var emergency by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val mode = status?.mode ?: info?.mode

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Send a page (through the internet, DAPNET account)", fontWeight = FontWeight.Bold)
            OutlinedTextField(to, { to = it }, label = { Text("To (callsigns, comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                text, { text = it.take(Dapnet.MAX_TEXT) }, label = { Text("Text (${text.length}/${Dapnet.MAX_TEXT})") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = emergency, onCheckedChange = { emergency = it })
                Text("Emergency", Modifier.weight(1f))
                Button(
                    enabled = !sending && to.isNotBlank() && text.isNotBlank(),
                    onClick = {
                        sending = true
                        val names = Dapnet.names(to)
                        val body = text.trim()
                        DapnetSender.send(settings, body, names, emergency) { err ->
                            sending = false
                            if (err == null) {
                                AppState.sentPages.value = AppState.sentPages.value + "→ ${names.joinToString(",")}: $body"
                                AppState.notice.value = "Page sent to DAPNET"
                                text = ""
                            } else {
                                AppState.error.value = err
                            }
                        }
                    },
                ) { Text(if (sending) "Sending..." else "Send") }
            }
            if (mode != null && mode != es.ea7jrs.aprslorap.core.TrackerMode.DAPNET && info?.hasDapnet == true) {
                Text("To receive pages, switch the tracker to DAPNET mode (Tracker tab). It stops APRS meanwhile.", fontSize = 12.sp)
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(sent.asReversed()) { line -> Text(line, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 13.sp) }
            items(pages, key = { "${it.page.ric}/${it.atMs}/${it.page.text.hashCode()}" }) { rp ->
                val p = rp.page
                val age = (System.currentTimeMillis() - rp.atMs) / 60_000
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        "RIC ${p.ric}" + (if (p.own) "  (mine)" else "") + (if (p.corrupt) "  (errors)" else "") + "  ·  ${age} min  ·  ${p.rssi} dBm",
                        fontSize = 12.sp,
                    )
                    Text(p.text, fontWeight = if (p.own) FontWeight.Bold else FontWeight.Normal)
                }
                HorizontalDivider()
            }
        }
    }
}
