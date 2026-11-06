/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Sdr
import java.util.Locale

/** SDR tab: control of SDR++ / gqrx (rigctl), SDRconnect (SDRplay) or SpyServer (Airspy), and following the tracker. */
@Composable
fun SdrTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val st by AppState.sdr.collectAsState()
    val txs by AppState.satTransmitters.collectAsState()
    val sat by AppState.satState.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    var kind by remember { mutableStateOf(runCatching { Sdr.Kind.valueOf(settings.sdrKind) }.getOrDefault(Sdr.Kind.RIGCTL)) }
    var host by rememberSaveable { mutableStateOf(settings.sdrHost) }
    var port by rememberSaveable { mutableStateOf(settings.sdrPort.toString()) }
    var freq by rememberSaveable { mutableStateOf("") }
    var bw by rememberSaveable { mutableStateOf("") }
    var follow by remember { mutableStateOf(runCatching { SdrControl.Follow.valueOf(settings.sdrFollow) }.getOrDefault(SdrControl.Follow.MANUAL)) }
    var step by rememberSaveable { mutableStateOf(settings.sdrStepHz.toString()) }
    var offset by rememberSaveable { mutableStateOf(settings.sdrOffsetHz.toString()) }
    var tx by remember { mutableStateOf(settings.sdrTx) }
    var rec by remember { mutableStateOf(settings.sdrRecordPasses) }

    fun saveFollow() {
        settings.sdrFollow = follow.name; settings.sdrStepHz = step.toIntOrNull() ?: 100; settings.sdrOffsetHz = offset.toIntOrNull() ?: 0
        settings.sdrTx = tx; settings.sdrRecordPasses = rec
        SdrControl.configure(settings)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ---- receiver
        Section("SDR program or server")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in Sdr.Kind.entries) {
                val pick = { kind = k; port = k.defaultPort.toString() }
                if (k == kind) Button(onClick = pick) { Text(k.label, fontSize = 11.sp) } else OutlinedButton(onClick = pick) { Text(k.label, fontSize = 11.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                settings.sdrKind = kind.name; settings.sdrHost = host; settings.sdrPort = port.toIntOrNull() ?: kind.defaultPort
                SdrControl.configure(settings)
                SdrControl.connect(kind, host, settings.sdrPort)
            }, enabled = host.isNotBlank()) { Text(if (st.status == "Off") "Connect" else "Reconnect") }
            OutlinedButton(onClick = { SdrControl.disconnect() }, enabled = st.status != "Off") { Text("Disconnect") }
        }
        Text(st.status, fontSize = 12.sp)
        if (st.device.isNotEmpty()) Field("Device", st.device)
        Text(when (kind) {
            Sdr.Kind.RIGCTL -> "SDR++: Module manager > add \"Rigctl Server\", tick Tuning, Listen (port 4532). Also SDR++ on this phone with a USB SDR (127.0.0.1). gqrx: Tools > Remote control (port 7356)."
            Sdr.Kind.SDRCONNECT -> "SDRconnect (GUI or headless) with the WebSocket interface on (port 5454). Audio stays in SDRconnect."
            Sdr.Kind.SPYSERVER -> "Airspy SpyServer (port 5555): the app keeps the device running with a tiny spectrum and tunes it if it is the first client (allow control in spyserver.config). Mode and filter belong to the program that listens (SDR#, SDR++)."
        }, fontSize = 11.sp)

        // ---- tuning
        Section("Tuning")
        Field("Frequency", st.freqHz?.let { Sdr.mhz(it) + " MHz" } ?: "—")
        Field("Mode / filter", (st.mode ?: "—") + (st.bandwidthHz?.let { " / ${it} Hz" } ?: ""))
        if (st.signalDb != null || st.snrDb != null) Field("Signal", String.format(Locale.US, "%.1f dB, SNR %.1f dB", st.signalDb ?: Double.NaN, st.snrDb ?: Double.NaN))
        if (!st.canControl) Text("This program does not let the app control the device.", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(freq, { freq = it }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Button(onClick = { Sdr.parseFreq(freq)?.let { SdrControl.tune(it) } }, enabled = st.connected && Sdr.parseFreq(freq) != null) { Text("Tune") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in listOf(-12_500L, -1_000L, -100L, 100L, 1_000L, 12_500L)) {
                OutlinedButton(onClick = { st.freqHz?.let { SdrControl.tune(it + d) } }, enabled = st.connected && st.freqHz != null) {
                    Text((if (d > 0) "+" else "−") + (if (abs(d) >= 1000) "${abs(d) / 1000.0}k".replace(".0k", "k") else "${abs(d)}"), fontSize = 10.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in listOf(144_800_000L to "APRS 144.800", 433_775_000L to "LoRa 433.775", 145_825_000L to "ISS 145.825")) {
                OutlinedButton(onClick = { SdrControl.tune(f.first) }, enabled = st.connected) { Text(f.second, fontSize = 10.sp) }
            }
        }
        if (kind != Sdr.Kind.SPYSERVER) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in Sdr.MODES) {
                    val go = { SdrControl.mode(m, bw.toIntOrNull() ?: 0) }
                    if (st.mode == m) Button(onClick = go, enabled = st.connected) { Text(m, fontSize = 10.sp) }
                    else OutlinedButton(onClick = go, enabled = st.connected) { Text(m, fontSize = 10.sp) }
                }
            }
            OutlinedTextField(bw, { bw = it.filter(Char::isDigit).take(6) }, label = { Text("Filter Hz (with the mode; empty = keep)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (st.gainMax > st.gainMin) {
            Text("Gain " + (st.gain?.toString() ?: "?") + if (kind == Sdr.Kind.SDRCONNECT) " (LNA state: higher = less gain)" else "", fontSize = 13.sp)
            var g by remember(st.gain) { mutableStateOf((st.gain ?: st.gainMin).toFloat()) }
            Slider(g, { g = it }, valueRange = st.gainMin.toFloat()..st.gainMax.toFloat(), steps = (st.gainMax - st.gainMin - 1).coerceAtLeast(0),
                onValueChangeFinished = { SdrControl.gain(g.toInt()) }, enabled = st.connected)
        }

        // ---- follow the tracker
        Section("Follow the tracker")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (f in SdrControl.Follow.entries) {
                val go = { follow = f; saveFollow() }
                if (f == follow) Button(onClick = go) { Text(f.label, fontSize = 10.sp) } else OutlinedButton(onClick = go) { Text(f.label, fontSize = 10.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(step, { step = it.filter(Char::isDigit).take(6) }, label = { Text("Retune every Hz") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(offset, { offset = it.filter { c -> c.isDigit() || c == '-' }.take(7) }, label = { Text("Offset Hz") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedButton(onClick = { saveFollow() }) { Text("Apply") }
        st.followHz?.let { Field("Following", Sdr.mhz(it) + " MHz" + (st.dopplerHz?.takeIf { follow == SdrControl.Follow.SATELLITE }?.let { d -> String.format(Locale.US, " (Doppler %+d Hz)", d) } ?: "")) }
        when (follow) {
            SdrControl.Follow.SATELLITE -> {
                Text("Satellite: " + (sat?.name ?: "none (tracker in SAT mode, satellite chosen in the SAT tab)") +
                    (sat?.elDeg?.let { String.format(Locale.US, ", elevation %.0f°", it) } ?: ""), fontSize = 12.sp)
                if (txs.isEmpty()) Text("No transmitters: choose a satellite in the SAT tab.", fontSize = 12.sp)
                for (t in txs.filter { it.downHz > 0 }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Sdr.mhz(t.downHz) + " MHz  " + t.mode + (if (t.baud > 0) " ${t.baud} Bd" else ""), fontSize = 13.sp,
                                fontWeight = if (t.index == tx) FontWeight.Bold else FontWeight.Normal)
                            Text(t.description, fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { tx = t.index; saveFollow() }) { Text(if (t.index == tx) "●" else "Use") }
                    }
                }
                if (kind != Sdr.Kind.SPYSERVER) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Record each pass (SDR++ recorder / SDRconnect audio)", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(rec, { rec = it; saveFollow() })
                }
            }
            SdrControl.Follow.LORA -> Text("The tracker's current LoRa profile: " +
                (info?.let { i -> i.loras.getOrNull(AppState.trackerStatus.value?.lora ?: i.lora)?.text } ?: "unknown (link to the tracker)") +
                ". LoRa cannot be demodulated by these programs: watch it in the waterfall.", fontSize = 12.sp)
            SdrControl.Follow.TARGET -> Text("The frequency of the rotor's chosen fixed target (SAT tab, RepeaterBook).", fontSize = 12.sp)
            SdrControl.Follow.MANUAL -> Text("The app tunes only when you ask.", fontSize = 12.sp)
        }
        if (st.recording) Text("Recording", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { SdrControl.record(true) }, enabled = st.connected && kind != Sdr.Kind.SPYSERVER) { Text("Start recording") }
            OutlinedButton(onClick = { SdrControl.record(false) }, enabled = st.connected && kind != Sdr.Kind.SPYSERVER) { Text("Stop") }
        }
    }
}

private fun abs(x: Long) = kotlin.math.abs(x)
