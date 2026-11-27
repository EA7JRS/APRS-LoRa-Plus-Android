/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asImageBitmap
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
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import es.ea7jrs.aprslorap.core.Dsp
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
    val sigId by AppState.signalId.collectAsState()
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

    var ppm by rememberSaveable { mutableStateOf(settings.sdrPpm.toString()) }
    var biasT by remember { mutableStateOf(settings.sdrBiasTee) }
    var sql by remember { mutableStateOf(settings.sdrSquelchDb.toFloat()) }
    var nbOn by remember { mutableStateOf(settings.sdrNoiseBlanker) }
    var nrOn by remember { mutableStateOf(settings.sdrNoiseReduction) }
    var anfOn by remember { mutableStateOf(settings.sdrAutoNotch) }
    var idClicked by remember { mutableStateOf(false) }
    var driverMissing by remember { mutableStateOf(false) }
    var dvMode by remember { mutableStateOf(settings.dvMode) }
    val dvStatus by AppState.dvStatus.collectAsState()
    val dvLog by AppState.dvLog.collectAsState()
    val pkStatus by AppState.packetStatus.collectAsState()
    val pkLog by AppState.packetLog.collectAsState()
    val local = host == "127.0.0.1" || host == "localhost"

    fun startLink() {
        settings.sdrKind = kind.name; settings.sdrHost = host; settings.sdrPort = port.toIntOrNull() ?: kind.defaultPort
        settings.sdrPpm = ppm.toIntOrNull() ?: 0; settings.sdrBiasTee = biasT
        SdrControl.configure(settings)
        SdrControl.connect(ctx, kind, host, settings.sdrPort, settings)
    }
    val driver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) startLink()
        else SdrControl.update { it.copy(status = "The RTL-SDR driver did not start: " + (r.data?.getStringExtra("detailed_exception_message") ?: "is the dongle plugged in (USB OTG) and allowed?")) }
    }
    fun connectNow() {
        settings.sdrPpm = ppm.toIntOrNull() ?: 0; settings.sdrBiasTee = biasT
        if (kind == Sdr.Kind.RTLTCP && local) {
            val p = port.toIntOrNull() ?: kind.defaultPort
            val uri = Sdr.RtlTcp.driverUri(p, Dsp.INPUT_RATE, settings.sdrLastHz + Dsp.IF_OFFSET, settings.sdrPpm, biasT)
            try { driverMissing = false; driver.launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            catch (e: ActivityNotFoundException) { driverMissing = true; SdrControl.update { it.copy(status = "The RTL-SDR driver app is not installed") } }
        } else startLink()
    }

    fun saveFollow() {
        settings.sdrFollow = follow.name; settings.sdrStepHz = step.toIntOrNull() ?: 100; settings.sdrOffsetHz = offset.toIntOrNull() ?: 0
        settings.sdrTx = tx; settings.sdrRecordPasses = rec
        SdrControl.configure(settings)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ---- receiver
        Section("SDR program or server")
        for (pair in Sdr.Kind.entries.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in pair) {
                val pick = { kind = k; port = k.defaultPort.toString(); if (k == Sdr.Kind.RTLTCP) host = "127.0.0.1" }
                if (k == kind) Button(onClick = pick) { Text(k.label, fontSize = 11.sp) } else OutlinedButton(onClick = pick) { Text(k.label, fontSize = 11.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { connectNow() }, enabled = host.isNotBlank()) { Text(if (st.status == "Off") "Connect" else "Reconnect") }
            OutlinedButton(onClick = { SdrControl.disconnect() }, enabled = st.status != "Off") { Text("Disconnect") }
        }
        Text(st.status, fontSize = 12.sp)
        if (st.device.isNotEmpty()) Field("Device", st.device)
        Text(when (kind) {
            Sdr.Kind.RIGCTL -> "SDR++: Module manager > add \"Rigctl Server\", tick Tuning, Listen (port 4532). Also SDR++ on this phone with a USB SDR (127.0.0.1). gqrx: Tools > Remote control (port 7356)."
            Sdr.Kind.SDRCONNECT -> "SDRconnect (GUI or headless) on the PC or Raspberry with the RSP, WebSocket interface on (port 5454; Windows firewall: private network). With \"Audio to the phone\" the app also decodes and plays what SDRconnect receives."
            Sdr.Kind.SPYSERVER -> "Airspy SpyServer (port 5555): the app keeps the device running with a tiny spectrum and tunes it if it is the first client (allow control in spyserver.config). Mode and filter belong to the program that listens (SDR#, SDR++)."
            Sdr.Kind.RTLTCP -> "The app is the receiver: it demodulates and plays the audio. 127.0.0.1 = RTL-SDR on this phone's USB (OTG) through the free \"RTL-SDR driver\" app; another address = an rtl_tcp server on the network (rtl_tcp -a 0.0.0.0)."
        }, fontSize = 11.sp)
        if (kind == Sdr.Kind.RTLTCP) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ppm, { ppm = it.filter { c -> c.isDigit() || c == '-' }.take(4) }, label = { Text("PPM correction") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Bias-T (LNA)", fontSize = 13.sp)
                Switch(biasT, { biasT = it; settings.sdrBiasTee = it })
            }
            if (local) RtlUsbPanel()
        }
        if (kind == Sdr.Kind.SDRCONNECT) {
            var scAudio by remember { mutableStateOf(settings.sdrConnectAudio) }
            var scPlay by remember { mutableStateOf(settings.sdrConnectPlay) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audio to the phone (decoders: CW, RTTY, PSK, FT8, FT4, WSPR, FST4W, spots)", Modifier.weight(1f), fontSize = 13.sp)
                Switch(scAudio, { scAudio = it; settings.sdrConnectAudio = it })
            }
            if (scAudio) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Play it on the phone", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(scPlay, { scPlay = it; settings.sdrConnectPlay = it })
                }
                Text("Applies at the next Connect. In Digital modes choose \"App receiver\" and put SDRconnect in USB (CW: USB or CW). Keep SDRconnect's AGC on: with it off the audio it sends clips. About 1.5 Mbit/s on the WiFi.", fontSize = 11.sp)
            }
        }

        // ---- tuning
        Section("Tuning")
        Field("Frequency", st.freqHz?.let { Sdr.mhz(it) + " MHz" } ?: "—")
        Field("Mode / filter", (st.mode ?: "—") + (st.bandwidthHz?.let { " / ${it} Hz" } ?: ""))
        if (st.signalDb != null) Field("Signal", String.format(Locale.US, "%.1f " + (if (kind == Sdr.Kind.RTLTCP) "dBFS" else "dB"), st.signalDb) + (st.snrDb?.let { String.format(Locale.US, ", SNR %.1f dB", it) } ?: ""))
        st.ctcssTone?.let { Field("CTCSS", String.format(Locale.US, "%.1f Hz", it)) }
        if (kind == Sdr.Kind.RTLTCP) {
            Text(if (sql <= -100f) "Squelch (NFM): open" else String.format(Locale.US, "Squelch (NFM): %.0f dB", sql), fontSize = 13.sp)
            Slider(sql, { sql = it }, valueRange = -100f..0f, onValueChangeFinished = { val v = if (sql <= -100f) -200 else sql.toInt(); settings.sdrSquelchDb = v; SdrControl.squelch(v.toDouble()) })
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val applyNoise = { SdrControl.rtl()?.noise(nbOn, nrOn, anfOn) }
                Text("NB", fontSize = 13.sp); Switch(nbOn, { nbOn = it; settings.sdrNoiseBlanker = it; applyNoise() })
                Text("NR", fontSize = 13.sp); Switch(nrOn, { nrOn = it; settings.sdrNoiseReduction = it; applyNoise() })
                Text("Notch", fontSize = 13.sp); Switch(anfOn, { anfOn = it; settings.sdrAutoNotch = it; applyNoise() })
            }
            // Identify the tuned signal by decoding (CW / RTTY / PSK); tune the signal into the passband first.
            OutlinedButton(onClick = { idClicked = true; AppState.signalId.value = null; SdrControl.rtl()?.identify() }, enabled = st.connected) { Text("Identify signal") }
            when {
                sigId != null -> {
                    val r = sigId!!
                    if (r.modes.isEmpty()) Text("No mode decoded — tune the signal into the passband and retry.", fontSize = 12.sp)
                    else r.modes.take(3).forEach { Text("${it.mode} · ${(it.confidence * 100).toInt()} %   ${it.text.take(40)}", fontSize = 13.sp) }
                }
                idClicked -> Text("Identifying… (5 s)", fontSize = 12.sp)
            }
        }
        if (!st.canControl) Text("This program does not let the app control the device.", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(freq, { freq = it }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Button(onClick = { Sdr.parseFreq(freq)?.let { settings.sdrLastHz = it; SdrControl.tune(it) } }, enabled = st.connected && Sdr.parseFreq(freq) != null) { Text("Tune") }
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
        // panadapter (app receiver only): spectrum + waterfall, tap / drag to tune
        SdrWaterfall(onTune = { settings.sdrLastHz = it; SdrControl.tune(it) })
        if (kind != Sdr.Kind.SPYSERVER) {
            // DV (digital voice) sits with the analog modes; the flavour (DMR, D-STAR, C4FM, M17...) is chosen in its section below.
            val dvActive = dvStatus.startsWith("Decoding")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in Sdr.MODES) {
                    val go = { if (DigitalVoiceRunner.active) DigitalVoiceRunner.stop(); settings.sdrMode = m; settings.sdrBandwidth = bw.toIntOrNull() ?: 0; SdrControl.mode(m, bw.toIntOrNull() ?: 0) }
                    if (st.mode == m && !dvActive) Button(onClick = go, enabled = st.connected) { Text(m, fontSize = 10.sp) }
                    else OutlinedButton(onClick = go, enabled = st.connected) { Text(m, fontSize = 10.sp) }
                }
                if (kind == Sdr.Kind.RTLTCP && DigitalVoiceRunner.available(ctx)) {
                    val goDv = { settings.sdrMode = "NFM"; settings.sdrBandwidth = 12_500; SdrControl.mode("NFM", 12_500); DigitalVoiceRunner.start(ctx, dvMode) }
                    if (dvActive) Button(onClick = goDv, enabled = st.connected) { Text("DV", fontSize = 10.sp) }
                    else OutlinedButton(onClick = goDv, enabled = st.connected) { Text("DV", fontSize = 10.sp) }
                }
            }
            OutlinedTextField(bw, { bw = it.filter(Char::isDigit).take(6) }, label = { Text("Filter Hz (with the mode; empty = keep)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (st.gainMax > st.gainMin) {
            Text("Gain " + (st.gain?.let { if (it < 0) "automatic" else it.toString() } ?: "?") + when (kind) { Sdr.Kind.SDRCONNECT -> " (LNA state: higher = less gain)"; Sdr.Kind.RTLTCP -> " %"; else -> "" }, fontSize = 13.sp)
            if (kind == Sdr.Kind.RTLTCP) OutlinedButton(onClick = { settings.sdrGainPercent = -1; SdrControl.gain(-1) }, enabled = st.connected) { Text("Automatic gain") }
            var g by remember(st.gain) { mutableStateOf((st.gain ?: st.gainMin).toFloat()) }
            Slider(g, { g = it }, valueRange = st.gainMin.toFloat()..st.gainMax.toFloat(), steps = (st.gainMax - st.gainMin - 1).coerceAtLeast(0),
                onValueChangeFinished = { if (kind == Sdr.Kind.RTLTCP) settings.sdrGainPercent = g.toInt(); SdrControl.gain(g.toInt()) }, enabled = st.connected)
        }

        // ---- packet FSK (the tracker's APRS FSK beacons)
        if (kind == Sdr.Kind.RTLTCP) {
            Section("Packet: G3RUH FSK + AFSK 1200 (satellite telemetry)")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in listOf(4800, 9600, 19200)) {
                    val go = {
                        val bw = (b * 2.1).toInt().coerceAtLeast(12_500)
                        settings.sdrMode = "NFM"; settings.sdrBandwidth = bw; SdrControl.mode("NFM", bw)
                        PacketRunner.start(b.toDouble())
                    }
                    OutlinedButton(onClick = go, enabled = st.connected) { Text("$b bd", fontSize = 11.sp) }
                }
                OutlinedButton(onClick = {
                    settings.sdrMode = "NFM"; settings.sdrBandwidth = 15_000; SdrControl.mode("NFM", 15_000)
                    PacketRunner.startAfsk1200()
                }, enabled = st.connected) { Text("1200 AFSK", fontSize = 11.sp) }
                OutlinedButton(onClick = { PacketRunner.stop() }, enabled = pkStatus != "Off") { Text("Stop", fontSize = 11.sp) }
            }
            Text(pkStatus, fontSize = 12.sp)
            for (l in pkLog.takeLast(10).asReversed()) Text(l, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text("G3RUH: AX.25 with the scrambler, as the tracker sends in APRS FSK mode (also FX.25; not IL2P). 1200 AFSK: Bell 202 tones, the APRS-on-FM and most cubesats' 1k2 telemetry (what SatNOGS decodes). Frames go to Stations and Messages like those from the tracker. Follow \"Tracker LoRa\" below to tune, or set a satellite's downlink. The CW beacon: Modes tab, CW, with the receiver in CW.", fontSize = 11.sp)
        }

        // ---- NOAA APT weather image
        if (kind == Sdr.Kind.RTLTCP) {
            val aptStatus by AppState.aptStatus.collectAsState()
            val aptVer by AppState.aptVersion.collectAsState()
            Section("NOAA APT weather image (137 MHz)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    settings.sdrMode = "NFM"; settings.sdrBandwidth = 40_000; SdrControl.mode("NFM", 40_000)
                    AptRunner.start()
                }, enabled = st.connected) { Text("Start") }
                OutlinedButton(onClick = { AptRunner.stop() }, enabled = aptStatus != "Off") { Text("Stop") }
            }
            Text(aptStatus, fontSize = 12.sp)
            aptVer.let { _ -> AptRunner.bitmap?.let { bm -> Image(bm.asImageBitmap(), "APT image", modifier = Modifier.fillMaxWidth().height(260.dp)) } }
            Text("NOAA 15 / 18 / 19, NFM. Needs a 137 MHz antenna (V-dipole or QFH). Tune the satellite's downlink as it passes; the picture builds top to bottom over the ~10-15 min pass. Two video channels: A (left) and B (right).", fontSize = 11.sp)
        }

        // ---- digital voice
        if (kind == Sdr.Kind.RTLTCP) {
            Section("Digital voice (DV): flavour")
            if (!DigitalVoiceRunner.available(ctx)) Text("This build of the app has no decoder for this phone's processor.", fontSize = 12.sp)
            else {
                for (row in es.ea7jrs.aprslorap.core.DigitalVoice.MODES.keys.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (m in row) {
                        val go = { dvMode = m; settings.dvMode = m; if (DigitalVoiceRunner.active) DigitalVoiceRunner.start(ctx, m) }   // apply at once if already decoding
                        if (m == dvMode) Button(onClick = go) { Text(m, fontSize = 10.sp) } else OutlinedButton(onClick = go) { Text(m, fontSize = 10.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        settings.sdrMode = "NFM"; settings.sdrBandwidth = 12_500; SdrControl.mode("NFM", 12_500)
                        DigitalVoiceRunner.start(ctx, dvMode)
                    }, enabled = st.connected) { Text(if (dvStatus.startsWith("Decoding")) "Restart" else "Decode") }
                    OutlinedButton(onClick = { DigitalVoiceRunner.stop() }, enabled = dvStatus != "Off") { Text("Stop") }
                }
                Text("Pick the flavour here, then DV in the mode row (next to AM / FM / CW) to decode. Auto tries to recognise it.", fontSize = 11.sp)
                Text(dvStatus, fontSize = 12.sp)
                for (l in dvLog.takeLast(14).asReversed()) Text(l, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("dsd-fme (lwvmobile) with mbelib, inside the app; the analog audio is muted while it decodes. No keys are used: encrypted calls stay muted. mbelib implements vocoders that may be covered by patents: personal use.", fontSize = 11.sp)
            }
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
                    Text("Record each pass (SDR++ recorder, SDRconnect audio, WAV of the app receiver)", Modifier.weight(1f), fontSize = 13.sp)
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
        VorSection(kind == Sdr.Kind.RTLTCP && st.connected)
        Beacon406Section(kind == Sdr.Kind.RTLTCP && st.connected)
        InmarsatSection(kind == Sdr.Kind.RTLTCP && st.connected)
    }
}

private fun abs(x: Long) = kotlin.math.abs(x)
