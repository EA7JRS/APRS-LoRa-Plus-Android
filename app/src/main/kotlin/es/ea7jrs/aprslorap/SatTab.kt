/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import es.ea7jrs.aprslorap.core.Sat
import es.ea7jrs.aprslorap.core.RotorFollow
import es.ea7jrs.aprslorap.core.SatTransmitter
import es.ea7jrs.aprslorap.core.TrackerMode
import java.util.Locale

private fun mhz(hz: Long?) = if (hz == null || hz <= 0L) "—" else String.format(Locale.US, "%.4f MHz", hz / 1e6)

/**
 * SAT mode of the tracker: pass in progress, satellite list, receiver with Doppler, transmitter, rotor and its fixed targets,
 * the phone's position and the satellite file. Everything the WebUI and the buttons of the tracker do in this mode.
 */
@Composable
fun SatTab() {
    val ready by AppState.controlReady.collectAsState()
    if (!ready) { NoControl(); return }
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val info by AppState.trackerInfo.collectAsState()
    val state by AppState.satState.collectAsState()
    val list by AppState.satList.collectAsState()
    val total by AppState.satListTotal.collectAsState()
    val selected by AppState.satSelected.collectAsState()
    val txs by AppState.satTransmitters.collectAsState()
    val rx by AppState.satReceiver.collectAsState()
    val rxLog by AppState.satRxLog.collectAsState()
    val sent by AppState.satTxSent.collectAsState()
    val rot by AppState.rotor.collectAsState()
    val targets by AppState.rotorTargets.collectAsState()
    val targetSel by AppState.rotorTargetSel.collectAsState()
    val source by AppState.rotorSource.collectAsState()
    val dataBusy by AppState.satDataBusy.collectAsState()
    val dataMsg by AppState.satDataMessage.collectAsState()
    val phoneText by AppState.phonePosition.collectAsState()
    val phoneAgps by AppState.phoneAgpsMessage.collectAsState()
    val aidBusy by AppState.gpsAidBusy.collectAsState()
    val aidMsg by AppState.gpsAidMessage.collectAsState()
    val c = AppState.controller

    val inSat = info?.mode == TrackerMode.SAT
    val canSat = info?.modes?.any { it.mode == TrackerMode.SAT } == true
    // T-TWR: the FM VFO follows the satellite also in FM mode (listen, PTT voice and APRS through the satellite)
    val canListen = inSat || (info?.hasSatFm == true && info?.mode == TrackerMode.FM)
    val hasFav = info?.hasSatFav == true

    var filter by rememberSaveable { mutableStateOf("") }
    var txText by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(settings.satDataUrl) }
    var groups by rememberSaveable { mutableStateOf(settings.satGroups) }
    var onlyCategory by rememberSaveable { mutableStateOf(false) }
    var phoneOn by rememberSaveable { mutableStateOf(false) }
    var confirmTx by remember { mutableStateOf<SatTransmitter?>(null) }
    var confirmPos by remember { mutableStateOf<SatTransmitter?>(null) }
    var confirmMode by remember { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newLat by rememberSaveable { mutableStateOf("") }
    var newLon by rememberSaveable { mutableStateOf("") }
    var newAlt by rememberSaveable { mutableStateOf("0") }
    var newMhz by rememberSaveable { mutableStateOf("") }

    val phone = remember { PhonePosition(ctx) }
    fun startPhone() { if (!phone.start()) phoneOn = false }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) startPhone() else { phoneOn = false; AppState.error.value = "Location permission is needed to send the phone position" }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) SatData.sendFile(ctx, uri, groups) }

    DisposableEffect(Unit) {
        c?.control("sat")
        c?.control("sattxlist")
        c?.control("tgtlist")
        if (AppState.satList.value.isEmpty()) c?.satListRead()
        if (phoneOn) startPhone()
        onDispose { phone.stop() }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- state of the pass
        item {
            Section("Satellite pass")
            if (!inSat) {
                Text(if (canListen) "FM mode: the VFO can follow the chosen satellite (receiver below); the rest needs SAT mode."
                     else "The tracker is not in SAT mode: the pass, the receiver and the transmitter need it.", fontSize = 13.sp)
                if (canSat) OutlinedButton(onClick = { confirmMode = true }) { Text("Switch the tracker to SAT mode") }
                else Text("This board or firmware has no SAT mode.", fontSize = 13.sp)
            }
            val s = state
            if (s == null || !s.ok) Text(if (s == null) "Waiting for the tracker..." else "No satellite data in the tracker: send the satellite file (below).")
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field("Satellite", (s.name ?: "?") + (s.noradId?.let { " ($it)" } ?: ""))
                    if (hasFav && s.noradId != null) TextButton(onClick = { c?.control("satfav", "id" to s.noradId, "on" to !s.favorite); markFav(s.noradId, !s.favorite) }) {
                        Text(if (s.favorite) "★ Favorite" else "☆ Add to favorites")
                    }
                }
                Field("Position used", s.positionSource.ifBlank { "none yet (no GNSS fix, no phone position)" })
                Field("Orbit data age", fmt(s.tleAgeDays, "%.1f", " days"))
                val az = s.azDeg
                val el = s.elDeg
                if (az != null && el != null) {
                    Field("Azimuth", fmt(az, "%.1f", "° " + Sat.compass(az)))
                    Field("Elevation", fmt(el, "%.1f", "°") + if (el < 0) " (below the horizon)" else "")
                    Field("Distance", s.rangeKm?.let { "$it km" } ?: "—")
                    Field("Radial speed", fmt(s.rangeRateKmS, "%.3f", " km/s") + if ((s.rangeRateKmS ?: 0.0) < 0) " (approaching)" else " (receding)")
                }
                if (s.geostationary) Text("Geostationary: it does not pass.", fontSize = 13.sp)
                else Sat.passText(s.aos, s.los, System.currentTimeMillis() / 1000)?.let { Text(it, fontWeight = FontWeight.Medium) }
                s.maxElDeg?.let { Field("Maximum elevation of the pass", "$it°") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- receiver and transmitters
        item {
            Section("Receiver (with Doppler)")
            val r = rx
            if (r == null || !r.on) Text("Off. Choose a transmitter below and press Listen.", fontSize = 13.sp)
            else {
                Field("Mode", r.kind ?: "?")
                Field("Tuned to", mhz(r.freqHz))
                Field("Doppler applied", r.dopplerHz?.let { "$it Hz" } ?: "—")
                Field("Fine tuning", r.fineHz?.let { "$it Hz" } ?: "—")
                Field("Signal", r.rssi?.let { "$it dBm" } ?: "—")
                Field("Frames received", r.frames?.toString() ?: "0")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c?.control("satrx", "fine" to -100) }) { Text("−100 Hz") }
                    OutlinedButton(onClick = { c?.control("satrx", "fine" to 100) }) { Text("+100 Hz") }
                    Button(onClick = { c?.control("satrx", "on" to false) }) { Text("Stop") }
                }
            }
            if (rxLog.isNotEmpty()) {
                Section("Received")
                for (l in rxLog.takeLast(12).reversed()) Text(l, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Transmitters of the chosen satellite")
            if (txs.isEmpty()) Text("None listed (choose a satellite first).", fontSize = 13.sp)
        }
        items(txs, key = { "tx${it.index}" }) { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text("#${t.index} ${t.description.ifBlank { t.mode }} — ${t.mode}${if (t.baud > 0) " ${t.baud} bd" else ""}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Down ${mhz(t.rxDopplerHz ?: t.downHz)}  Up ${mhz(t.txDopplerHz ?: t.upHz)}", fontSize = 12.sp)
                    Text("Tracker can receive: ${t.rxKind}   transmit: ${t.txKind}" + (t.toneHz?.let { String.format(Locale.US, "   CTCSS %.1f Hz", it) } ?: "") +
                         (if (t.aprsDigi) "   APRS digipeater" else "") + (if (t.txKind == "FM") "   (voice: PTT of the tracker)" else ""), fontSize = 12.sp)
                }
                if (t.rxKind != "n/a") OutlinedButton(onClick = { c?.control("satrx", "on" to true, "tx" to t.index) }, enabled = canListen) { Text("Listen") }
                if (t.txKind != "n/a" && t.txKind != "FM") TextButton(onClick = { confirmTx = t }, enabled = canListen) { Text(if (t.aprsDigi) "Message" else "Transmit") }
                if (t.aprsDigi && t.txKind == "APRS") TextButton(onClick = { confirmPos = t }, enabled = canListen) { Text("Position") }
            }
        }
        item {
            if (sent > 0) Text("Transmissions to the satellite in this link: $sent", fontSize = 12.sp)
            OutlinedTextField(
                txText, { txText = it.take(64) }, label = { Text("Message to transmit (max 64; empty = the one of the WebUI)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Text("The tracker only transmits with Allow transmitting set in its WebUI, and applies its own safety limits (docs/SAT_MODE.md).", fontSize = 11.sp)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- list of satellites
        item {
            Section("Satellites in the tracker ($total)")
            if (hasFav) {
                Text("List of the tracker (its button and encoder go through it): ${Sat.categoryLabel(state?.category ?: 0)} · ${state?.favorites ?: 0} favorites", fontSize = 12.sp)
                for (row in Sat.CATEGORIES.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((code, name) in row) {
                        if ((state?.category ?: 0) == code) Button(onClick = {}) { Text(name, fontSize = 12.sp) }
                        else OutlinedButton(onClick = { c?.control("satcat", "k" to code); c?.satListRead() }) { Text(name, fontSize = 12.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show only that list", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(onlyCategory, { onlyCategory = it })
                }
            }
            OutlinedTextField(filter, { filter = it }, label = { Text("Search name or NORAD number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (list.size < total) Text("Loading the list... ${list.size} of $total", fontSize = 12.sp)
        }
        val shown = list.filter { (!onlyCategory || it.inCategory) && (filter.isBlank() || it.name.contains(filter, ignoreCase = true) || it.noradId.toString().contains(filter)) }.take(80)
        items(shown, key = { "s${it.index}" }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = canListen) { c?.control("satsel", "i" to e.index); c?.control("sattxlist") }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((if (e.index == selected) "● " else "○ ") + e.name, Modifier.weight(1f), fontSize = 14.sp,
                    fontWeight = if (e.index == selected) FontWeight.Bold else FontWeight.Normal)
                val bands = Sat.bandsText(e.bands)
                Text("${if (bands.isNotEmpty()) bands else Sat.categoryName(e.category)} · ${e.transmitters} tx · ${e.noradId}", fontSize = 11.sp)
                if (hasFav) TextButton(onClick = { c?.control("satfav", "id" to e.noradId, "on" to !e.favorite); markFav(e.noradId, !e.favorite) }) {
                    Text(if (e.favorite) "★" else "☆", fontSize = 18.sp)
                }
            }
        }
        item {
            if (list.isNotEmpty() && shown.size == 80) Text("Only the first 80 matches are shown: refine the search.", fontSize = 11.sp)
            if (!canListen && list.isNotEmpty()) Text("Switch the tracker to SAT mode to choose a satellite.", fontSize = 11.sp)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- rotor
        item {
            Section("Rotor")
            val r = rot
            if (r == null) Text("No rotor data yet (it needs \"Send the rot event\" on in the WebUI).", fontSize = 13.sp)
            else {
                Field("Pointing at", (r.name ?: "—") + " (" + Sat.rotorSource(r.source) + if (r.tracking) ", tracking)" else ")")
                Field("Azimuth", fmt(r.azDeg, "%.1f", "° " + Sat.compass(r.azDeg)))
                Field("Elevation", fmt(r.elDeg, "%.1f", "°"))
                r.km?.let { Field("Distance", "$it km") }
                if (r.mobile) Field("Vehicle heading", fmt(r.headingDeg, "%.0f", "°") + " (absolute azimuth " + fmt(r.absAzDeg, "%.0f", "°") + ")")
                if (r.pitchDeg != null && r.rollDeg != null) Field("Mount tilt", fmt(r.pitchDeg, "%.1f", "° pitch, ") + fmt(r.rollDeg, "%.1f", "° roll"))
                if (r.actualAzDeg != null && r.actualElDeg != null) Field("Rotor reports", fmt(r.actualAzDeg, "%.1f", "° / ") + fmt(r.actualElDeg, "%.1f", "°"))
            }
            Text("Follow", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            for (row in RotorFollow.TRACKER_SOURCES.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (src in row) {
                    if (source == src) Button(onClick = {}) { Text(Sat.rotorSource(src)) }
                    else OutlinedButton(onClick = { c?.control("tgt", "src" to src) }) { Text(Sat.rotorSource(src)) }
                }
            }
            TrackerRotorFollowSettings(c)
            Section("Fixed targets (repeaters, beacons)")
            if (targets.isEmpty()) Text("None stored.", fontSize = 13.sp)
        }
        items(targets, key = { "t${it.slot}" }) { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text((if (t.slot == targetSel && source == 2) "● " else "○ ") + t.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(String.format(Locale.US, "%.5f, %.5f  %d m  %s", t.lat, t.lon, t.altM, mhz(t.freqHz)), fontSize = 11.sp)
                }
                OutlinedButton(onClick = { c?.control("tgt", "sel" to t.slot) }) { Text("Follow") }
                TextButton(onClick = { c?.control("tgt", "del" to t.slot) }) { Text("Delete") }
            }
        }
        item {
            Text("Add a target", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(newName, { newName = it.take(20) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newLat, { newLat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(newLon, { newLon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newAlt, { newAlt = it }, label = { Text("Altitude m") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(newMhz, { newMhz = it }, label = { Text("Frequency MHz") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            val lat = newLat.replace(',', '.').toDoubleOrNull()
            val lon = newLon.replace(',', '.').toDoubleOrNull()
            Button(
                onClick = {
                    c?.control(
                        "tgt", "n" to newName.trim(), "lat" to lat, "lon" to lon, "alt" to (newAlt.toIntOrNull() ?: 0),
                        "f" to Math.round((newMhz.replace(',', '.').toDoubleOrNull() ?: 0.0) * 1e6), "sel" to true,
                    )
                    newName = ""; newLat = ""; newLon = ""; newMhz = ""
                },
                enabled = newName.isNotBlank() && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0,
            ) { Text("Store and follow") }
            RepeaterBookSearch { name, mhz -> newName = name; newMhz = mhz }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RotorPhoneTools()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- phone position
        item {
            Section("Phone position")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send my phone position to the tracker", Modifier.weight(1f), fontSize = 14.sp)
                Switch(phoneOn, { on ->
                    phoneOn = on
                    if (!on) phone.stop()
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startPhone()
                    else locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                })
            }
            Text(phoneText, fontSize = 12.sp)
            Text("For when the tracker has no GNSS fix. Sent every few seconds while this screen is open; the tracker's own GNSS is used otherwise.", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { GpsAid.refreshPhone(ctx) }) { Text("Refresh phone A-GPS") }
                if (info?.hasGpsAid == true)
                    OutlinedButton(onClick = { GpsAid.sendTimeAndPosition(ctx) }, enabled = !aidBusy) { Text("A-GPS to tracker") }
            }
            if (phoneAgps.isNotBlank()) Text(phoneAgps, fontSize = 12.sp)
            if (aidMsg.isNotBlank()) Text(aidMsg, fontSize = 12.sp)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        // ---- satellite file
        item {
            Section("Satellite file")
            Text("CelesTrak groups to send (none = all that fit; favorites always go). Room in the tracker: ${(info?.satMaxBytes ?: 64000) / 1024} kB.", fontSize = 12.sp)
            for (row in Sat.GROUPS.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((letter, name) in row) {
                    val on = groups.contains(letter)
                    val pick = { val g = if (on) groups.replace(letter, "") else groups + letter; groups = g; settings.satGroups = g }
                    if (on) Button(onClick = pick) { Text(name, fontSize = 12.sp) } else OutlinedButton(onClick = pick) { Text(name, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(url, { url = it }, label = { Text("Download address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { settings.satDataUrl = url; SatData.downloadAndSend(url.trim(), groups) }, enabled = !dataBusy) { Text("Download and send") }
                OutlinedButton(onClick = { filePicker.launch("*/*") }, enabled = !dataBusy) { Text("Choose a file...") }
                OutlinedButton(onClick = { url = Sat.ALL_DATA_URL }) { Text("Default") }
            }
            if (dataMsg.isNotBlank()) Text(dataMsg, fontSize = 13.sp)
            Text("The phone downloads the file with its internet and sends it to the tracker over Bluetooth: the tracker needs no WiFi. The default file (all bands, groups, CTCSS) is renewed daily from the project's repository; until it is published the app uses the old 70 cm file. The WebUI of the tracker (Satellites) does the same by WiFi.", fontSize = 11.sp)
        }
    }

    confirmPos?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmPos = null },
            title = { Text("Position through the satellite") },
            text = { Text("The tracker sends its position as APRS on ${mhz(t.txDopplerHz ?: t.upHz)} towards ${state?.name ?: "the satellite"} (path of the WebUI, ARISS by default; comment = the message of the WebUI). Only with the satellite in view.") },
            confirmButton = { TextButton(onClick = { c?.control("sattx", "tx" to t.index, "pos" to true); confirmPos = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirmPos = null }) { Text("Cancel") } },
        )
    }
    confirmTx?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmTx = null },
            title = { Text("Transmit to the satellite") },
            text = {
                Text(
                    "The tracker will transmit on ${mhz(t.txDopplerHz ?: t.upHz)} (${t.txKind}) towards ${state?.name ?: "the satellite"}. " +
                        "Transmit only if your licence allows it on that frequency and the satellite is in view.",
                )
            },
            confirmButton = { TextButton(onClick = { c?.control("sattx", "tx" to t.index, "txt" to txText.trim()); confirmTx = null }) { Text("Transmit") } },
            dismissButton = { TextButton(onClick = { confirmTx = null }) { Text("Cancel") } },
        )
    }
    if (confirmMode) {
        AlertDialog(
            onDismissRequest = { confirmMode = false },
            title = { Text("SAT mode") },
            text = { Text("The tracker restarts in ${TrackerMode.name(TrackerMode.SAT)} mode (only for this start: the next one goes back to the saved APRS mode) and the link comes back by itself.") },
            confirmButton = { TextButton(onClick = { c?.control("mode", "m" to TrackerMode.SAT); confirmMode = false }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirmMode = false }) { Text("Cancel") } },
        )
    }
}

/** Favorite changed from the app: the list is updated here (the tracker answers with the "sat" event only). */
private fun markFav(noradId: Int, on: Boolean) {
    AppState.satList.value = AppState.satList.value.map { if (it.noradId == noradId) it.copy(favorite = on) else it }
}

/** The tracker's own rotor settings for following what it hears (firmware with sources 4 / 5): filter, max age, estimate. */
@Composable
private fun TrackerRotorFollowSettings(c: Controller?) {
    val cfg by AppState.rotorTrackerFollow.collectAsState()
    var filter by rememberSaveable(cfg?.filter) { mutableStateOf(cfg?.filter ?: "") }
    var maxMin by rememberSaveable(cfg?.maxAgeMin) { mutableStateOf((cfg?.maxAgeMin ?: 30).toString()) }
    var predictS by rememberSaveable(cfg?.predictMaxS) { mutableStateOf((cfg?.predictMaxS ?: 300).toString()) }
    val predict = cfg?.predict ?: true
    Text("Last heard: stations with position heard by APRS LoRa or FSK. CW beacon: callsign + locator decoded in Listen CW (MORSE). Angles from the tracker's GPS.", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(filter, { filter = it.uppercase() }, label = { Text("Callsigns (EA7JRS, EA7*)") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(maxMin, { maxMin = it.filter(Char::isDigit).take(3) }, label = { Text("Max min") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Moving stations: aim where they are now", Modifier.weight(1f), fontSize = 13.sp)
        androidx.compose.material3.Switch(predict, { on -> c?.control("tgt", "pr" to on) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(predictS, { predictS = it.filter(Char::isDigit).take(4) }, label = { Text("Predict up to (s)") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedButton(onClick = {
            c?.control("tgt", "ff" to filter.trim(), "fm" to (maxMin.toIntOrNull() ?: 30), "pm" to (predictS.toIntOrNull() ?: 300))
        }) { Text("Save") }
    }
}
