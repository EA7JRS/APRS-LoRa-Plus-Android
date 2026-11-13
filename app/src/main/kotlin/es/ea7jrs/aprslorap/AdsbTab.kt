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
import es.ea7jrs.aprslorap.core.Adsb
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.Sdr
import java.util.Locale

/**
 * Aircraft received by the app (ADS-B on 1090 MHz): the source (RTL-SDR on this phone, rtl_tcp or dump1090 / readsb on the
 * network), and the list with distance, bearing and elevation from here; ROTOR aims the rotor box at an aircraft.
 */
@Composable
fun AdsbTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val st by AdsbLink.state.collectAsState()
    val list by AppState.aircraft.collectAsState()
    var source by remember { mutableStateOf(settings.adsbSource) }
    var host by rememberSaveable { mutableStateOf(settings.adsbHost) }
    var port by rememberSaveable { mutableStateOf(settings.adsbPort.toString()) }
    var open by rememberSaveable { mutableStateOf(-1) }
    var driverMissing by remember { mutableStateOf(false) }
    val usbPort = Sdr.Kind.RTLTCP.defaultPort

    val driver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) AdsbLink.startRtl(ctx, "127.0.0.1", usbPort, settings.sdrPpm, settings.sdrGainPercent)
        else AppState.error.value = "The RTL-SDR driver did not start: " + (r.data?.getStringExtra("detailed_exception_message") ?: "is the dongle plugged in (USB OTG) and allowed?")
    }
    fun start() {
        settings.adsbSource = source; settings.adsbHost = host.trim()
        when (source) {
            "USB" -> {
                SdrControl.disconnect()
                val uri = Sdr.RtlTcp.driverUri(usbPort, Adsb.SAMPLE_RATE, Adsb.FREQ_HZ, settings.sdrPpm, settings.sdrBiasTee)
                try { driverMissing = false; driver.launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                catch (_: ActivityNotFoundException) { driverMissing = true }
            }
            "RTLTCP" -> {
                val p = port.toIntOrNull() ?: 1234; settings.adsbPort = p
                if (host.isBlank()) AppState.error.value = "Write the address of the rtl_tcp server" else AdsbLink.startRtl(ctx, host.trim(), p, settings.sdrPpm, settings.sdrGainPercent)
            }
            else -> {
                val p = port.toIntOrNull() ?: 30005; settings.adsbPort = p
                if (host.isBlank()) AppState.error.value = "Write the address of the receiver" else AdsbLink.startNet(ctx, host.trim(), p)
            }
        }
    }

    val me = st.rx
    val rows = list.map { a ->
        val la = a.lat; val lo = a.lon
        val d = if (me != null && la != null && lo != null) Geo.distanceKm(me.first, me.second, la, lo) else null
        Triple(a, d, if (me != null && la != null && lo != null) Geo.bearingDeg(me.first, me.second, la, lo) else null)
    }.sortedWith(compareBy({ it.first.emergency == null }, { it.second ?: 1e9 }, { it.first.hex }))

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Aircraft (${list.size}, ${list.count { it.lat != null }} with position)", fontWeight = FontWeight.Bold)
                    Text(st.status + (if (st.running && st.format.isNotEmpty()) " · ${st.format}" else ""), fontSize = 12.sp, color = if (st.running && st.frames > 0) Alp.Green else Alp.Muted)
                    if (st.running) Text("${st.frames} messages · ${st.positions} positions" + (if (st.candidates > 0) " · ${st.candidates} preambles" else "") +
                        (if (me == null) " · no position of your own: distances unknown" else ""), fontSize = 11.sp, color = Alp.Muted)
                }
                if (st.running) OutlinedButton(onClick = { AdsbLink.stop() }) { Text("Stop") } else Button(onClick = { start() }) { Text("Start") }
            }
            if (!st.running) {
                for ((id, label) in listOf("USB" to "RTL-SDR on this phone (USB OTG)", "RTLTCP" to "rtl_tcp on the network", "NET" to "dump1090 / readsb on the network")) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        source = id; port = when (id) { "RTLTCP" -> "1234"; "NET" -> "30005"; else -> port }
                    }) { RadioButton(source == id, { source = id; port = when (id) { "RTLTCP" -> "1234"; "NET" -> "30005"; else -> port } }); Text(label, fontSize = 14.sp) }
                }
                if (source != "USB") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(port, { port = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text(when (source) {
                    "USB" -> "The app tunes the dongle to 1090 MHz at 2 MS/s and decodes the aircraft itself (free \"RTL-SDR driver\" app; gain, ppm and bias-T are those of the SDR tool). The receiver of the SDR tool stops meanwhile. Use an antenna for 1090 MHz."
                    "RTLTCP" -> "An rtl_tcp server with the dongle (rtl_tcp -a 0.0.0.0, port 1234): the app decodes. 2 MS/s is about 32 Mbit/s: cable or a very good WiFi."
                    else -> "A receiver that already decodes: port 30005 (Beast), 30002 (raw) or 30003 (BaseStation). The format is detected."
                }, fontSize = 11.sp, color = Alp.Muted)
                if (driverMissing) {
                    Text("The RTL-SDR driver app is not installed.", fontSize = 12.sp, color = Alp.RedSoft)
                    OutlinedButton(onClick = {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Sdr.RtlTcp.DRIVER_PACKAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { AppState.error.value = "Install \"RTL-SDR driver\" (${Sdr.RtlTcp.DRIVER_PACKAGE})" }
                    }) { Text("Install the driver") }
                }
            }
            Text("On the map: layer \"Aircraft (ADS-B, your own receiver)\". Nothing comes from the internet.", fontSize = 11.sp, color = Alp.Muted)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
        }
        items(rows, key = { it.first.icao }) { (a, d, brg) ->
            val altFt = a.altFt
            val el = if (d != null && altFt != null) elevationDeg(d, altFt * 0.3048) else null
            Column(Modifier.fillMaxWidth().clickable { open = if (open == a.icao) -1 else a.icao }.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (a.emergency != null) Alp.RedSoft else Alp.Text, modifier = Modifier.width(96.dp))
                    Text(a.flightLevel() ?: "—", fontFamily = TechMono, fontSize = 13.sp, color = Alp.Cyan, modifier = Modifier.width(70.dp))
                    Text((a.gsKt?.let { String.format(Locale.US, "%.0f kn", it) } ?: "") + (a.trackDeg?.let { String.format(Locale.US, " %03.0f°", it) } ?: ""), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (d != null && brg != null) String.format(Locale.US, "%.0f km %03.0f°", d, brg) else "no position", fontFamily = TechMono, fontSize = 12.5.sp, color = if (d != null) Alp.Green else Alp.Muted)
                }
                a.emergency?.let { Text(it, fontSize = 12.sp, color = Alp.RedSoft, fontWeight = FontWeight.Bold) }
                if (open == a.icao) {
                    Text(listOfNotNull("ICAO ${a.hex}", a.squawk?.let { String.format(Locale.US, "squawk %04d", it) }, a.altFt?.let { String.format(Locale.US, "%,d ft", it) },
                        a.vrFpm?.let { String.format(Locale.US, "%+d ft/min", it) }, if (a.onGround) "on the ground" else null, "${a.messages} messages", "heard " + Fmt.ago(a.seenMs),
                        a.lat?.let { "position " + Fmt.ago(a.posMs) }).joinToString(" · "), fontSize = 12.sp, color = Alp.Muted)
                    if (d != null && brg != null) {
                        Text(String.format(Locale.US, "From here: %.1f km at %03.0f°", d, brg) + (el?.let { String.format(Locale.US, ", elevation %.1f°", it) } ?: ""), fontSize = 12.sp)
                        OutlinedButton(onClick = {
                            AppState.error.value = if (RotorLink.command(RotorProto.pos(brg, el?.coerceAtLeast(0.0)))) String.format(Locale.US, "Rotor box → %03.0f°", brg) + (el?.let { String.format(Locale.US, " / %.0f°", it.coerceAtLeast(0.0)) } ?: "")
                            else "Rotor box not connected"
                        }) { Text("ROTOR") }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

/** Elevation of something [altM] metres up at [distKm] over the ground, with the curve of the Earth (4/3 radius for refraction). */
private fun elevationDeg(distKm: Double, altM: Double): Double {
    val r = 6371.0 * 4 / 3
    val h = altM / 1000.0
    val drop = distKm * distKm / (2 * r)
    return Math.toDegrees(Math.atan2(h - drop, distKm.coerceAtLeast(0.01)))
}
