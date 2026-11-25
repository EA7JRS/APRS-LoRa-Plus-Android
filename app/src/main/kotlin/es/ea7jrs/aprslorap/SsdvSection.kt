// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tracker tab, SSDV image mode and POCSAG pager. SSDV: shows the status/progress, uploads a JPEG from the phone over BLE
// (image source 2) and starts/stops the emission. POCSAG: sends a page to a RIC. The image source and radio parameters are
// configured in the Config tab (groups "ssdv" / "pocsag"). Commands "ssdv", "ssdvtx", "ssdvimg", "pocsag".
package es.ea7jrs.aprslorap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SSDV image mode: status, upload a JPEG from the phone and transmit. Shown in SSDV mode when the firmware has the mode. */
@Composable
fun SsdvSection() {
    val ctx = LocalContext.current
    val c = AppState.controller
    val st by AppState.ssdvState.collectAsState()
    val upBusy by AppState.ssdvUploadBusy.collectAsState()
    val upMsg by AppState.ssdvUploadMessage.collectAsState()
    var txAfter by rememberSaveable { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            if (bytes == null || bytes.isEmpty()) AppState.error.value = "Cannot read the image"
            else c?.ssdvImageWrite(bytes, startAfter = txAfter)
        }
    }

    Section("SSDV image")
    Text("Slow-scan digital video: sends a JPEG as SSDV packets. The image source (test pattern, URL, this upload or the camera) " +
         "and the radio parameters are in the Config tab, group \"ssdv\". Request a status to see the progress.", fontSize = 11.sp)

    val s = st
    if (s != null) {
        Field("State", (if (s.busy) "transmitting" else if (s.inMode) "ready" else "not in SSDV mode") +
            (if (s.totalPackets > 0) " · ${s.sentPackets}/${s.totalPackets} pkt" else ""))
        if (s.rxImages > 0 || s.rxPackets > 0) Field("Received", "${s.rxImages} image(s) · ${s.rxPackets} pkt in progress")
        if (s.status.isNotBlank()) Text(s.status, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { c?.ssdvTx(true) }, enabled = !upBusy) { Text("Transmit") }
        OutlinedButton(onClick = { c?.ssdvTx(false) }) { Text("Stop") }
        OutlinedButton(onClick = { c?.ssdvStatus() }) { Text("Status") }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(txAfter, { txAfter = it })
        Text("transmit after upload", fontSize = 13.sp)
    }
    Button(onClick = { try { picker.launch("image/*") } catch (_: Exception) { AppState.error.value = "No file picker on this phone" } },
           enabled = !upBusy, modifier = Modifier.fillMaxWidth()) {
        Text(if (upBusy) "Uploading…" else "Upload a JPEG from the phone")
    }
    Text("Pick a small JPEG (set the SSDV source to \"BLE upload\" in Config). A large image takes a while over BLE.", fontSize = 11.sp)
    if (upMsg.isNotBlank()) Text(upMsg, fontSize = 12.sp)
}

/** POCSAG pager: sends a page to a RIC. Independent of the current mode (the radio returns to the profile afterwards). */
@Composable
fun PocsagSection() {
    val c = AppState.controller
    var ric by rememberSaveable { mutableStateOf("") }
    var msg by rememberSaveable { mutableStateOf("") }
    var numeric by rememberSaveable { mutableStateOf(false) }

    Section("POCSAG page")
    Text("Sends one POCSAG page on the frequency and speed of the Config group \"pocsag\". Transmits, so the callsign must be validated.", fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(ric, { ric = it.filter { ch -> ch.isDigit() }.take(7) }, label = { Text("RIC") }, singleLine = true, modifier = Modifier.width(140.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(numeric, { numeric = it })
            Text("numeric", fontSize = 13.sp)
        }
    }
    OutlinedTextField(msg, { msg = it.take(80) }, label = { Text(if (numeric) "Digits" else "Message") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = ric.isNotBlank() && msg.isNotBlank(),
           onClick = { c?.pocsagSend(ric.toLongOrNull() ?: 0L, msg.trim(), numeric); msg = "" },
           modifier = Modifier.fillMaxWidth()) { Text("Send page") }
}
