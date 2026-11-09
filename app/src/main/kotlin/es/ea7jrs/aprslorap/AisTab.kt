// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// AIS tab: vessels received by the tracker (T-TWR AIS mode, external AIS module, the phone's RTL-SDR), nearest first, with details,
// GO TO (tracker compass and rotor, or Google Maps), collision alarm settings and the T-TWR channel. Commands "aislist", "aisset".
package es.ea7jrs.aprslorap

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Ais
import es.ea7jrs.aprslorap.core.Nav
import es.ea7jrs.aprslorap.core.TrackerMode
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun AisTab() {
    val ready by AppState.controlReady.collectAsState()
    if (!ready) { NoControl(); return }
    val info by AppState.trackerInfo.collectAsState()
    val vessels by AppState.aisVessels.collectAsState()
    val source by AppState.aisSource.collectAsState()
    val msgs by AppState.aisMessages.collectAsState()
    val c = AppState.controller
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(-1L) }
    var cpa by rememberSaveable { mutableStateOf("0.5") }
    var tcpa by rememberSaveable { mutableStateOf("15") }
    var guard by rememberSaveable { mutableStateOf("0") }

    LaunchedEffect(Unit) { while (true) { c?.control("aislist", "from" to 0, "cnt" to 15); delay(10_000) } }
    DisposableEffect(Unit) { onDispose { } }

    if (info?.hasAis != true) { Text("The tracker firmware has no AIS receiver (update it).", Modifier.padding(12.dp)); return }
    val list = vessels.values.sortedWith(compareBy({ !it.danger }, { it.rangeNm ?: 1e9 }))
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("AIS vessels (${list.size})")
            Text("Source: ${source.ifBlank { "—" }} · $msgs messages" + if (info?.mode == TrackerMode.AIS) "" else "  (T-TWR: AIS receiver mode; other boards: AIS module on the UART, WebUI > AIS receiver)", fontSize = 12.sp)
            if (info?.mode == TrackerMode.AIS) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { c?.control("aisset", "ch" to 0) }) { Text("A 161.975") }
                OutlinedButton(onClick = { c?.control("aisset", "ch" to 1) }) { Text("B 162.025") }
                OutlinedButton(onClick = { c?.control("aisset", "ch" to 2) }) { Text("Alternate") }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
        }
        items(list, key = { it.mmsi }) { v ->
            Column(Modifier.fillMaxWidth().clickable { open = if (open == v.mmsi) -1L else v.mmsi }.padding(vertical = 6.dp)) {
                Text((if (v.danger) "⚠ " else "") + v.title + (if (v.callsign.isNotBlank()) " · ${v.callsign}" else ""), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                     color = if (v.danger || v.cls == "T") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(Ais.summary(v).ifBlank { "no position yet" }, fontSize = 12.sp)
                if (open == v.mmsi) {
                    Text(String.format(Locale.US, "MMSI %09d · %s", v.mmsi, Ais.className(v.cls)) + (if (v.imo > 0) " · IMO ${v.imo}" else ""), fontSize = 12.sp)
                    Ais.statusName(v.status).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp) }
                    if (v.lat != null && v.lon != null) Text(String.format(Locale.US, "%.5f, %.5f (%d s ago)", v.lat, v.lon, v.posAgeS ?: 0), fontSize = 12.sp)
                    if (v.heading != null) Text("Heading ${v.heading}°", fontSize = 12.sp)
                    if (v.lengthM > 0) Text("${v.lengthM} × ${v.beamM} m" + (v.draughtM?.let { String.format(Locale.US, ", draught %.1f m", it) } ?: ""), fontSize = 12.sp)
                    if (v.destination.isNotBlank()) Text("To ${v.destination}" + if (v.eta.isNotBlank()) " (ETA ${v.eta} UTC)" else "", fontSize = 12.sp)
                    if (v.altitudeM != null) Text("Altitude ${v.altitudeM} m", fontSize = 12.sp)
                    if (v.text.isNotBlank()) Text(v.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Last heard ${v.ageS} s ago" + (if (v.channel.isNotBlank()) " on ${v.channel}" else "") + if (v.messages > 0) " · ${v.messages} messages" else "", fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (v.lat != null) OutlinedButton(onClick = { c?.control("aisset", "goto" to v.mmsi) }) { Text("Tracker GO TO") }
                        val la = v.lat; val lo = v.lon
                        if (la != null && lo != null) OutlinedButton(onClick = {
                            val maps = Intent(Intent.ACTION_VIEW, Uri.parse(Nav.geoUri(la, lo, v.title)))
                            try { ctx.startActivity(maps) } catch (_: ActivityNotFoundException) { AppState.error.value = "No maps app found" }
                        }) { Text("Map") }
                    }
                }
            }
            HorizontalDivider()
        }
        item {
            Section("Alarms (tracker)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(cpa, { cpa = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) }, label = { Text("CPA nm") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(tcpa, { tcpa = it.filter(Char::isDigit).take(3) }, label = { Text("TCPA min") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(guard, { guard = it.filter { ch -> ch.isDigit() || ch == '.' }.take(4) }, label = { Text("Guard nm") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    AppState.controller?.let { ctl ->
                        ctl.control("aisset", "cpa" to Math.round((cpa.toDoubleOrNull() ?: 0.5) * 10).toInt(), "tcpa" to (tcpa.toIntOrNull() ?: 15),
                                    "guard" to Math.round((guard.toDoubleOrNull() ?: 0.0) * 10).toInt(), "alarm" to true)
                    }
                }) { Text("Save, alarm on") }
                OutlinedButton(onClick = { c?.control("aisset", "alarm" to false) }) { Text("Alarm off") }
                OutlinedButton(onClick = { c?.control("aisset", "clear" to true); AppState.aisVessels.value = emptyMap() }) { Text("Clear") }
            }
            Text("Collision risk when the closest point of approach is under the CPA within the TCPA, or a vessel enters the guard zone (0 = off). AIS-SART, MOB and EPIRB always sound (WebUI can turn them off).", fontSize = 11.sp)
        }
    }
}
