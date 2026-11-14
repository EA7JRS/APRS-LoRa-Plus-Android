// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vessel tab: own vessel data of the nautical AIS profile (APRS LoRa+ MAPRS transponder and any tracker with that profile).
// View and edit name, MMSI, APRS callsign, maritime call sign, ship type and dimensions, and send them to the tracker
// ("vessel" command). The maritime call sign of the MMSI is checked with MarineTraffic (the user's key) or confirmed by hand
// in the ITU MARS. Sending needs a validated amateur callsign (MAPRS transmits AIS). Protocol: docs/BLE_CONTROL.md.
package es.ea7jrs.aprslorap

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.MtCheck
import es.ea7jrs.aprslorap.core.Vessel
import java.util.Locale

@Composable
fun VesselTab() {
    val ctx0 = LocalContext.current
    val settings = remember { Settings(ctx0) }
    val ready by AppState.controlReady.collectAsState()
    if (!ready) { NoControl(); return }
    val info by AppState.trackerInfo.collectAsState()
    val v by AppState.vessel.collectAsState()
    val c = AppState.controller
    val ctx = LocalContext.current

    if (info?.hasVessel != true) {
        Text("This tracker has no nautical AIS vessel (the APRS LoRa+ MAPRS transponder, or a board with the nautical AIS profile and NMEA output).",
            Modifier.padding(16.dp))
        return
    }
    LaunchedEffect(Unit) { if (v == null) c?.queryVessel() }
    val st = v ?: run { Text("Reading the vessel data…", Modifier.padding(16.dp)); return }

    // Editable copy, reset whenever the tracker reports new data.
    var name by remember(st) { mutableStateOf(st.name) }
    var mmsi by remember(st) { mutableStateOf(if (st.mmsi == 0L) "" else st.mmsi.toString()) }
    var mcall by remember(st) { mutableStateOf(st.maritimeCall) }
    var type by remember(st) { mutableStateOf(st.type) }
    var len by remember(st) { mutableStateOf(if (st.lengthM == 0) "" else st.lengthM.toString()) }
    var beam by remember(st) { mutableStateOf(if (st.beamM == 0) "" else st.beamM.toString()) }
    var draught by remember(st) { mutableStateOf(if (st.draughtM == 0.0) "" else String.format(Locale.US, "%.1f", st.draughtM)) }
    var unverified by remember(st) { mutableStateOf(st.maritimeUnverified) }

    // Maritime check result for the pair currently typed (lives only in this screen).
    var mcStatus by remember(st) { mutableStateOf("") }
    var checkedCode by remember(st) { mutableStateOf(0) }   // 1 MarineTraffic, 2 MARS, confirmed for the typed pair
    var checking by remember { mutableStateOf(false) }

    val mmsiL = mmsi.toLongOrNull() ?: 0L
    val callBase = Vessel.baseCall(st.callsign)
    // Which call sign the maritime check is about (the typed one, or the APRS base if empty).
    val wantCall = mcall.trim().ifBlank { callBase }
    val licensed = Licence.isValid(settings)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Section("Vessel (nautical AIS profile)")
        Text(
            buildString {
                append(if (st.classBTransponder) "APRS LoRa+ MAPRS transponder · " else "")
                append(Vessel.className(st.cls.firstOrNull() ?: '?'))
                append(" · AIS type ${st.shipType}")
                append(if (st.active) " · active beacon" else " · beacon ${st.index + 1} (not active)")
            },
            fontSize = 12.sp,
        )
        Text("APRS: ${st.callsign.ifBlank { "—" }}", fontSize = 12.sp)

        OutlinedTextField(name, { name = it.take(20) }, label = { Text("Vessel name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mmsi, { mmsi = it.filter { ch -> ch.isDigit() }.take(9); mcStatus = ""; checkedCode = 0 }, label = { Text("MMSI (9 digits)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        // Ship type: class A hidden on a class-B-only transponder (MAPRS).
        val types = Vessel.TYPES.filter { !st.classBTransponder || it.classB }
        var typeOpen by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { typeOpen = true }) { Text("Type: ${Vessel.type(type).name}", fontSize = 13.sp) }
            DropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                types.forEach { t -> DropdownMenuItem(text = { Text("${t.name} (${Vessel.className(t.cls)})") }, onClick = { typeOpen = false; type = t.id }) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(len, { len = it.filter { ch -> ch.isDigit() }.take(3) }, label = { Text("Length m") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(beam, { beam = it.filter { ch -> ch.isDigit() }.take(3) }, label = { Text("Beam m") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(draught, { draught = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) }, label = { Text("Draught m") }, singleLine = true, modifier = Modifier.weight(1f))
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Maritime call sign of the MMSI")
        OutlinedTextField(mcall, { mcall = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(7); mcStatus = ""; checkedCode = 0 },
            label = { Text("Call sign (empty = APRS ${callBase.ifBlank { "—" }})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // Current verification stored on the tracker (for the pair it has).
        if (st.maritimeVerified != 0) {
            Text("Stored: verified in ${MtCheck.sourceName(st.maritimeVerified)} for ${st.maritimeToSend}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        } else if (st.maritimeUnverified) {
            Text("Stored: sent without checking", fontSize = 12.sp)
        } else {
            Text("Stored: not checked", fontSize = 12.sp)
        }

        OutlinedTextField(settings.marineTrafficKey, { settings.marineTrafficKey = it.trim() }, label = { Text("MarineTraffic API key (40 hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (mmsiL == 0L) { mcStatus = "Set the MMSI first"; return@Button }
                    checking = true; mcStatus = "Checking MarineTraffic…"
                    Maritime.check(settings.marineTrafficKey, mmsiL, wantCall) { r, mt ->
                        checking = false
                        mcStatus = MtCheck.resultText(r) + if ((r == MtCheck.Result.MATCH || r == MtCheck.Result.MISMATCH) && mt.isNotEmpty()) " ($mt)" else ""
                        checkedCode = if (r == MtCheck.Result.MATCH) MtCheck.Source.MARINETRAFFIC.code else 0
                    }
                },
                enabled = !checking && MtCheck.validKey(settings.marineTrafficKey),
            ) { Text("MarineTraffic", fontSize = 13.sp) }
            OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MtCheck.MARS_URL))) } catch (e: ActivityNotFoundException) {}
            }, modifier = Modifier.padding(start = 8.dp)) { Text("ITU MARS", fontSize = 13.sp) }
        }
        OutlinedButton(onClick = { checkedCode = MtCheck.Source.MARS.code; mcStatus = "Confirmed by hand in the ITU MARS for $wantCall" }) {
            Text("I confirmed it in the ITU MARS", fontSize = 13.sp)
        }
        if (mcStatus.isNotBlank()) Text(mcStatus, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(unverified, { unverified = it })
            Text("Send it even without checking (a warning is shown)", fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (!licensed) {
            Text("Validate your amateur callsign in the Connect tab before sending: the MAPRS transmits AIS.", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Button(
            enabled = licensed,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = {
                val m = mmsi.toLongOrNull() ?: 0L
                if (m != 0L && !Vessel.validMmsi(m)) { AppState.error.value = "MMSI must be 9 digits (200000000–799999999)"; return@Button }
                // mcok goes only when the check confirmed this exact pair; checkedCode is reset when MMSI or call change.
                val mcok = checkedCode
                val fields = ArrayList<Pair<String, Any?>>()
                fields.add("n" to name.trim()); fields.add("mmsi" to m)
                Vessel.baseCall(st.callsign).takeIf { it.isNotEmpty() }?.let { fields.add("call" to it) }
                fields.add("mc" to mcall.trim())
                if (mcok != 0) fields.add("mcok" to mcok)
                fields.add("mcu" to unverified)
                fields.add("t" to type)
                fields.add("len" to (len.toIntOrNull() ?: 0)); fields.add("beam" to (beam.toIntOrNull() ?: 0)); fields.add("dr" to (draught.toDoubleOrNull() ?: 0.0))
                c?.control("vessel", *fields.toTypedArray())
                AppState.notice.value = "Vessel data sent to the tracker"
            },
        ) { Text("Save and send") }
        Text("The tracker keeps the data; the AIS tag of the next beacon carries it. Class, SSID and symbol are set by the tracker.", fontSize = 11.sp)
    }
}
