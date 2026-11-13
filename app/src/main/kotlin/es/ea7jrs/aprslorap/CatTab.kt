/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Cat
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.SMeter
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Radio plugged into the phone by USB (CAT): the link (model, baud rate, adapter and port, PTT), the radio (frequency,
 * mode, VFO, split, CTCSS, S-meter, PTT while held) and the Doppler of the tracker's satellite.
 */
@Composable
fun CatTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val cat by AppState.phoneCat.collectAsState()
    val status by AppState.phoneCatStatus.collectAsState()
    val txs by AppState.satTransmitters.collectAsState()
    val sat by AppState.satState.collectAsState()
    var model by remember { mutableStateOf(Cat.MODELS.firstOrNull { it.name == settings.catModel } ?: Cat.MODELS[4]) }
    var baud by remember { mutableStateOf(settings.catBaud) }
    var civ by rememberSaveable { mutableStateOf(String.format(Locale.US, "%02X", settings.catCivAddr)) }
    var devices by remember { mutableStateOf(CatLink.devices(ctx)) }
    var device by remember { mutableStateOf(settings.catDevice) }
    var portIdx by remember { mutableStateOf(settings.catPort) }
    var rts by remember { mutableStateOf(settings.catRts) }
    var dtr by remember { mutableStateOf(settings.catDtr) }
    var pttOn by remember { mutableStateOf(settings.catPtt) }
    var pttLine by remember { mutableStateOf(settings.catPttLine) }
    var track by remember { mutableStateOf(settings.catTrack) }
    var uplink by remember { mutableStateOf(settings.catUplink) }
    var step by remember { mutableStateOf(settings.catStep) }
    var typed by rememberSaveable { mutableStateOf("") }
    var toneText by rememberSaveable { mutableStateOf("") }
    var sentHz by remember { mutableStateOf(0L) }
    var sentAt by remember { mutableStateOf(0L) }
    var setup by rememberSaveable { mutableStateOf(!CatLink.connected) }
    val validated = Licence.isValid(settings)
    LaunchedEffect(Unit) { while (true) { if (!CatLink.connected) devices = CatLink.devices(ctx); delay(2000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- link
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(model.name + " · " + model.proto.label + " · " + baud + " Bd", fontWeight = FontWeight.Bold)
                Text(status, fontSize = 12.sp, color = if (cat?.link == true) Alp.Green else Alp.Muted)
            }
            if (CatLink.connected || status.startsWith("Allow")) OutlinedButton(onClick = { CatLink.disconnect(); setup = true }) { Text("Disconnect") }
            else Button(onClick = { CatLink.connect(ctx); setup = false }) { Text("Connect") }
        }
        Text(if (setup) "▾ Link settings" else "▸ Link settings", fontSize = 13.sp, color = Alp.Cyan, modifier = Modifier.clickable { setup = !setup }.padding(vertical = 4.dp))
        if (setup) {
            Section("Radio")
            for (m in Cat.MODELS) Line(m == model, m.name) {
                model = m; settings.catModel = m.name
                baud = m.baud; settings.catBaud = m.baud
                if (m.civAddr > 0 && m.proto == Cat.Proto.CIV) { civ = String.format(Locale.US, "%02X", m.civAddr); settings.catCivAddr = m.civAddr }
            }
            if (model.note.isNotEmpty()) Text(model.note, fontSize = 12.sp, color = Alp.Muted)
            Section("Baud rate (the same as the radio's CAT RATE menu)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in Cat.BAUDS) {
                    if (b == baud) Button(onClick = {}) { Text("$b", fontSize = 12.sp) }
                    else OutlinedButton(onClick = { baud = b; settings.catBaud = b }) { Text("$b", fontSize = 12.sp) }
                }
            }
            if (model.proto == Cat.Proto.CIV) OutlinedTextField(civ, { v ->
                civ = v.uppercase(Locale.US).filter { it in "0123456789ABCDEF" }.take(2)
                civ.toIntOrNull(16)?.let { settings.catCivAddr = it }
            }, label = { Text("CI-V address (hex)") }, singleLine = true)
            Section("USB adapter")
            if (devices.isEmpty()) Text("Nothing plugged in. Use an OTG adapter: the radio's USB socket (FT-991A, FT-891), its CAT cable (FT-817 / 818) or the SCU-17 interface.", fontSize = 12.sp, color = Alp.Muted)
            Line(device.isEmpty(), "The first one found") { device = ""; settings.catDevice = "" }
            for (d in devices) Line(device == d.key, d.label) { device = d.key; settings.catDevice = d.key }
            val ports = devices.firstOrNull { it.key == device }?.ports ?: devices.firstOrNull()?.ports ?: 2
            if (ports > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CAT port", fontSize = 14.sp)
                    for (i in 0 until ports.coerceAtMost(4)) {
                        if (i == portIdx) Button(onClick = {}) { Text("${i + 1}") } else OutlinedButton(onClick = { portIdx = i; settings.catPort = i }) { Text("${i + 1}") }
                    }
                }
                Text("Radios and interfaces with two ports (FT-991A, FT-891, SCU-17): the first one (Enhanced) is the CAT; the second one carries PTT on RTS.", fontSize = 11.sp, color = Alp.Muted)
            }
            SwitchLine("RTS high (Yaesu by USB waits for it: menu CAT RTS)", rts) { rts = it; settings.catRts = it }
            SwitchLine("DTR high (cables that take their supply from it)", dtr) { dtr = it; settings.catDtr = it }
            Section("Transmit")
            SwitchLine("PTT from the app (while a key is held)", pttOn) { pttOn = it; settings.catPtt = it; CatLink.applyPtt(settings) }
            SwitchLine("PTT on the RTS line of the second port (SCU-17) instead of the CAT command", pttLine) { pttLine = it; settings.catPttLine = it }
            if (pttOn && !validated) Text("PTT needs a validated callsign (Connect).", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Text("Changes to the link apply at the next Connect.", fontSize = 11.sp, color = Alp.Muted)
        }

        // ---- radio
        val c = cat
        if (c != null) {
            Section("Radio")
            Text(RotorProto.freq(c.freqHz), fontFamily = TechMono, fontSize = 34.sp, color = if (c.link) Alp.Text else Alp.Muted)
            SMeterBar(SMeter.fromCat(c.smeter), extra = listOfNotNull(RotorProto.modeName(c.mode), "VFO ${c.vfo}", c.txFreqHz?.takeIf { c.split }?.let { "TX ${RotorProto.freq(it)}" },
                c.toneHz?.let { String.format(Locale.US, "T %.1f", it) }, "TX".takeIf { c.ptt }).joinToString(" · "))

            fun tune(hz: Long) { if (hz > 0 && CatLink.freq(hz)) { sentHz = hz; sentAt = System.currentTimeMillis() } }
            fun base(): Long = if (System.currentTimeMillis() - sentAt < 1500 && sentHz > 0) sentHz else c.freqHz
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = c.link, onClick = { tune(base() - step) }) { Text("−") }
                OutlinedTextField(typed, { typed = it }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Button(enabled = c.link, onClick = {
                    val mhz = typed.replace(',', '.').toDoubleOrNull()
                    if (mhz == null || mhz <= 0) AppState.error.value = "Type the frequency in MHz" else tune(Math.round(mhz * 1e6))
                }) { Text("Set") }
                OutlinedButton(enabled = c.link, onClick = { tune(base() + step) }) { Text("+") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (st in RotorProto.CAT_STEPS) {
                    val label = if (st >= 1000) "${st / 1000.0}".removeSuffix(".0") + " k" else "$st"
                    if (st == step) Button(onClick = {}) { Text(label, fontSize = 12.sp) }
                    else OutlinedButton(onClick = { step = st; settings.catStep = st }) { Text(label, fontSize = 12.sp) }
                }
            }
            for (row in RotorProto.CAT_MODES.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in row) {
                    if (c.mode == m) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(RotorProto.modeName(m)) }
                    else OutlinedButton(enabled = c.link, onClick = { CatLink.mode(m) }, modifier = Modifier.weight(1f)) { Text(RotorProto.modeName(m)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = c.link, onClick = { CatLink.swapVfo() }) { Text("A / B") }
                Text("Split", fontSize = 14.sp)
                Switch(c.split, { CatLink.split(it) }, enabled = c.link)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(toneText, { toneText = it }, label = { Text("CTCSS Hz (empty = off)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Button(enabled = c.link, onClick = {
                    val hz = toneText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val std = if (hz > 0) RotorProto.ctcss(hz) else 0.0
                    if (std == null) AppState.error.value = "Not a standard CTCSS tone"
                    else if (!CatLink.tone(std)) AppState.error.value = "The radio did not take the tone"
                }) { Text("Tone") }
            }
            if (c.pttAllowed) {
                val red = MaterialTheme.colorScheme.error
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).border(2.dp, red, RoundedCornerShape(26.dp))
                        .pointerInput(c.link) {
                            if (!c.link) return@pointerInput
                            detectTapGestures(onPress = {
                                if (CatLink.ptt(true)) try { tryAwaitRelease() } finally { CatLink.ptt(false) }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(if (c.ptt) "ON AIR" else "PTT (hold)", color = red, fontWeight = FontWeight.Bold) }
            } else Text("PTT: off (Link settings > Transmit).", fontSize = 11.sp, color = Alp.Muted)

            // ---- satellite Doppler from the tracker
            SwitchLine("Doppler of the tracker's satellite (SAT mode)", track) { track = it; settings.catTrack = it }
            if (track) {
                SwitchLine("Also the uplink (radio in split)", uplink) { uplink = it; settings.catUplink = it }
                val tr = c.tracking
                Text(when {
                    tr?.active == true -> String.format(Locale.US, "%s: Doppler %+.1f kHz", tr.name, tr.dopplerHz / 1000.0)
                    sat?.ok == true -> "Waiting for the satellite to rise (${sat?.name ?: ""})."
                    else -> "No satellite data: tracker in SAT mode and the tracker link up."
                }, fontSize = 12.sp)
                if (txs.none { it.downHz > 0 }) Text("No transmitters yet: choose the satellite in the SAT tool.", fontSize = 12.sp)
                Text("The radio gets the mode, split and CTCSS of the transmitter chosen in SAT, and then follows it with the range rate of the tracker. The FT-817 / 818 have no CAT command for the other VFO: only the downlink follows.", fontSize = 11.sp, color = Alp.Muted)
            }
        }
        Text("Audio: with the FT-991A's USB cable or the SCU-17 the radio is also a USB sound card; Android takes it as the microphone, so Digital modes > Microphone decodes the radio, and the spots and the log take the frequency from the CAT.", fontSize = 11.sp, color = Alp.Muted)
    }
}

@Composable
private fun Line(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RadioButton(selected, onClick)
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(on, onChange)
    }
}
