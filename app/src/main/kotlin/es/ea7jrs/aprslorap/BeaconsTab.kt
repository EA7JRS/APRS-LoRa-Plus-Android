/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Beacons
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.Sdr
import java.util.Locale

/**
 * Beacons heard by the app's own receivers: the LR-FHSS receiver (the propagation beacon of the trackers) and the list of
 * every station heard in LR-FHSS, WSPR, FST4W, FT8, FT4 and CW, with distance and bearing, MAP, ROTOR and SPOT (to the DX
 * cluster). [onMap]: opens the map with the layer of these stations.
 */
@Composable
fun BeaconsTab(onMap: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val st by LrFhssLink.state.collectAsState()
    val list by AppState.beacons.collectAsState()
    val dx by DxLink.state.collectAsState()
    var source by remember { mutableStateOf(settings.lrFhssSource) }
    var host by rememberSaveable { mutableStateOf(settings.lrFhssHost) }
    var port by rememberSaveable { mutableStateOf(settings.lrFhssPort.toString()) }
    var mhz by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.4f", settings.lrFhssFreqHz / 1e6)) }
    var auto by remember { mutableStateOf(settings.beaconAutoSpot) }
    var open by rememberSaveable { mutableStateOf("") }
    var driverMissing by remember { mutableStateOf(false) }
    val me = remember(list.size) { NetWeatherFetcher.position(ctx) }
    val usbPort = Sdr.Kind.RTLTCP.defaultPort
    fun freq(): Long? = mhz.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1e6) }?.takeIf { it in 24_000_000L..1_766_000_000L }

    val driver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) LrFhssLink.startRtl(ctx, "127.0.0.1", usbPort, settings.sdrPpm, settings.sdrGainPercent, settings.lrFhssFreqHz)
        else AppState.error.value = "The RTL-SDR driver did not start: " + (r.data?.getStringExtra("detailed_exception_message") ?: "is the dongle plugged in (USB OTG) and allowed?")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LrFhssLink.decodeFile(ctx, uri, settings.lrFhssFreqHz)
    }
    fun start() {
        val f = freq() ?: run { AppState.error.value = "Write the frequency in MHz (434.000)"; return }
        settings.lrFhssFreqHz = f; settings.lrFhssSource = source; settings.lrFhssHost = host.trim()
        when (source) {
            "USB" -> {
                SdrControl.disconnect(); if (AdsbLink.usesRtl) AdsbLink.stop()
                val uri = Sdr.RtlTcp.driverUri(usbPort, LrFhssLink.RTL_RATE, f - LrFhssLink.RTL_SHIFT, settings.sdrPpm, settings.sdrBiasTee)
                try { driverMissing = false; driver.launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                catch (_: ActivityNotFoundException) { driverMissing = true }
            }
            "RTLTCP" -> {
                val p = port.toIntOrNull() ?: 1234; settings.lrFhssPort = p
                if (host.isBlank()) AppState.error.value = "Write the address of the rtl_tcp server" else LrFhssLink.startRtl(ctx, host.trim(), p, settings.sdrPpm, settings.sdrGainPercent, f)
            }
            else -> try { picker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) } catch (_: ActivityNotFoundException) { AppState.error.value = "No file picker on this phone" }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("LR-FHSS receiver", fontWeight = FontWeight.Bold)
                    Text(st.status, fontSize = 12.sp, color = if (st.running) Alp.Green else Alp.Muted)
                    if (st.running || st.headers > 0) Text("${st.headers} headers · ${st.packets.size} packets · ${st.errors} with errors", fontSize = 11.sp, color = Alp.Muted)
                }
                if (st.running) OutlinedButton(onClick = { LrFhssLink.stop() }) { Text("Stop") } else Button(onClick = { start() }) { Text(if (source == "FILE") "Open" else "Start") }
            }
            if (st.progress >= 0) LinearProgressIndicator(progress = { st.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (!st.running) {
                OutlinedTextField(mhz, { mhz = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(10) }, label = { Text("Beacon frequency, MHz") }, singleLine = true,
                    modifier = Modifier.width(200.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                for ((id, label) in listOf("USB" to "RTL-SDR on this phone (USB OTG)", "RTLTCP" to "rtl_tcp on the network", "FILE" to "I / Q recording (WAV)")) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { source = id }) { RadioButton(source == id, { source = id }); Text(label, fontSize = 14.sp) }
                }
                if (source == "RTLTCP") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text(when (source) {
                    "USB" -> "The app tunes the dongle 250 kHz below the beacon at 1 MS/s and decodes it itself (free \"RTL-SDR driver\" app; gain, ppm and bias-T are those of the SDR tool). The receiver of the SDR tool and ADS-B stop meanwhile."
                    "RTLTCP" -> "An rtl_tcp server with the dongle (rtl_tcp -a 0.0.0.0, port 1234): the app decodes. 1 MS/s is about 16 Mbit/s."
                    else -> "A recording of the I / Q (two channels) made by SDRconnect, SDR++, SDR#...: 62.5 kS/s or more, centred on the beacon or near it, with the whole transmission inside."
                }, fontSize = 11.sp, color = Alp.Muted)
                if (driverMissing) {
                    Text("The RTL-SDR driver app is not installed.", fontSize = 12.sp, color = Alp.RedSoft)
                    OutlinedButton(onClick = {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Sdr.RtlTcp.DRIVER_PACKAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { AppState.error.value = "Install \"RTL-SDR driver\" (${Sdr.RtlTcp.DRIVER_PACKAGE})" }
                    }) { Text("Install the driver") }
                }
            }
            for (p in st.packets.take(if (open == "#packets") 50 else 3)) {
                Column(Modifier.fillMaxWidth().clickable { open = if (open == "#packets") "" else "#packets" }.padding(vertical = 3.dp)) {
                    Text(p.text, fontFamily = TechMono, fontSize = 13.sp, color = if (p.beacon) Alp.Green else Alp.Text)
                    Text(Fmt.ago(p.timeMs) + " · " + p.info, fontSize = 11.sp, color = Alp.Muted)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Heard (${list.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    settings.mapOverlays = (MapOverlays.parseOn(settings.mapOverlays) + "ham_beacons").joinToString(",")
                    onMap()
                }) { Text("MAP") }
                if (list.isNotEmpty()) OutlinedButton(onClick = { BeaconHub.clear() }) { Text("Clear") }
            }
            Text("Stations heard by this app in LR-FHSS, APRS FSK 9600 and M17 (SDR tool), WSPR, FST4W, FT8, FT4 and CW (Modes). Those with a locator go to the map.", fontSize = 11.sp, color = Alp.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Text("AUTO SPOT", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Alp.Muted)
                for ((id, label) in listOf("off" to "OFF", "lrfhss" to "TRACKER MODES", "all" to "ALL")) Chip(label, auto == id, color = if (id == "off") Alp.Cyan else Alp.Amber) { auto = id; settings.beaconAutoSpot = id }
            }
            Text(if (auto == "off") "Spots to the DX cluster only with the SPOT key of each station."
                else "Every new station " + (if (auto == "lrfhss") "heard in LR-FHSS, APRS FSK or M17" else "of the list") + " goes to the DX cluster by itself: once per mode and band every 30 min, never your own callsign, one spot every 20 s at most. " +
                    (if (dx.connected) "Cluster: ${dx.node}." else "The cluster is not connected: nothing goes out.") +
                    (if (auto == "all") " Many nodes do not want FT8 / WSPR decodes as spots: ask the sysop." else ""),
                fontSize = 11.sp, color = if (auto != "off" && !dx.connected) Alp.Amber else Alp.Muted)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
        }
        items(list, key = { it.key }) { h ->
            val pos = h.position
            val d = if (me != null && pos != null) Geo.distanceKm(me.first, me.second, pos.first, pos.second) else null
            val brg = if (me != null && pos != null) Geo.bearingDeg(me.first, me.second, pos.first, pos.second) else null
            Column(Modifier.fillMaxWidth().clickable { open = if (open == h.key) "" else h.key }.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(104.dp))
                    Text(h.mode, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (Beacons.isTrackerMode(h.mode)) Alp.Green else Alp.Cyan, modifier = Modifier.width(70.dp))
                    Text(h.locator.ifEmpty { "—" } + (h.snrDb?.let { "  $it dB" } ?: ""), fontFamily = TechMono, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                    Text(if (d != null && brg != null) String.format(Locale.US, "%.0f km %03.0f°", d, brg) else Fmt.ago(h.timeMs), fontFamily = TechMono, fontSize = 12.5.sp, color = if (d != null) Alp.Green else Alp.Muted)
                }
                if (open == h.key) {
                    Text(listOfNotNull(String.format(Locale.US, "%.4f MHz", h.freqHz / 1e6), h.band.ifEmpty { null }, h.powerDbm?.let { "$it dBm" }, "heard " + Fmt.ago(h.timeMs), "${h.count} times",
                        if (h.spottedMs > 0) "spotted " + Fmt.ago(h.spottedMs) else null).joinToString(" · "), fontSize = 12.sp, color = Alp.Muted)
                    if (h.text.isNotEmpty()) Text(h.text, fontFamily = TechMono, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { AppState.error.value = BeaconHub.spot(ctx, h) }) { Text("SPOT") }
                        if (brg != null) OutlinedButton(onClick = {
                            AppState.error.value = if (RotorLink.command(RotorProto.pos(brg, null))) String.format(Locale.US, "Rotor box → %03.0f°", brg) else "Rotor box not connected"
                        }) { Text("ROTOR") }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
