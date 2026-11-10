// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tracker tab, FM voice mode of the LilyGO T-TWR: the radio as seen on its FM page, tuning, tones, squelch, noise reduction,
// memories, scan, ARTS, EAI and APRS beacons during voice. Commands "fm", "fmset", "fmdial", "fmmem", "fmscan", "fmarts", "fmeai".
package es.ea7jrs.aprslorap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.FmChannel
import es.ea7jrs.aprslorap.core.FmState
import es.ea7jrs.aprslorap.core.FmTone
import java.util.Locale

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${options.getOrElse(selected) { "?" }}", fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

@Composable
fun FmSection() {
    val st by AppState.fmState.collectAsState()
    val mems by AppState.fmMemories.collectAsState()
    val c = AppState.controller
    var freq by rememberSaveable { mutableStateOf("") }
    var memName by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) {
        c?.control("fm")
        c?.control("fmmem", "op" to "list")
        onDispose { }
    }

    Section("FM voice radio")
    val s = st
    if (s == null) { Text("Waiting for the radio state...", fontSize = 12.sp); return }
    val ch = s.channel
    Text(
        (if (s.ptt) "TX  " else if (s.busy) "RX  " else "") + FmChannel.mhz(if (s.ptt) ch.txHz else ch.rxHz) + " MHz",
        fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
    )
    Text((if (s.memory) String.format(Locale.US, "M%02d %s · ", s.memIndex + 1, ch.name) else "VFO · ") + ch.summary, fontSize = 13.sp)
    val status = ArrayList<String>()
    if (s.busy && s.rssi != 0) status.add("${s.rssi} dBm")
    status.add("squelch ${if (s.squelchAuto) "auto (${s.squelch})" else s.squelch.toString()}")
    if (s.noiseReduction > 0) status.add("noise reduction ${listOf("", "low", "mid", "high")[s.noiseReduction]}")
    if (s.scan > 0) status.add(if (s.scan == 2) "scan: holding" else "scanning")
    if (s.arts > 0) status.add(s.artsText)
    if (s.eaiSeconds > 0) status.add("EAI ${s.eaiSeconds / 60}:${String.format(Locale.US, "%02d", s.eaiSeconds % 60)} left")
    if (s.ptt) status.add("TX ${s.txSeconds} s")
    if (!s.toneOk) status.add("tone not available with this module firmware")
    Text(status.joinToString(" · "), fontSize = 12.sp)
    s.notice?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Medium) }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { c?.control("fmdial", "d" to -10) }) { Text("◀◀") }
        OutlinedButton(onClick = { c?.control("fmdial", "d" to -1) }) { Text("◀") }
        OutlinedButton(onClick = { c?.control("fmdial", "d" to 1) }) { Text("▶") }
        OutlinedButton(onClick = { c?.control("fmdial", "d" to 10) }) { Text("▶▶") }
    }
    Choice("Dial", FmState.EDITS, s.edit) { c?.control("fmset", "edit" to it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(freq, { freq = it.filter { ch2 -> ch2.isDigit() || ch2 == '.' }.take(9) }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = freq.toDoubleOrNull() != null, onClick = {
            val hz = Math.round((freq.toDoubleOrNull() ?: 0.0) * 1e6)
            c?.control("fmset", "f" to hz); freq = ""
        }, modifier = Modifier.padding(start = 8.dp)) { Text("Tune") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice("Shift", listOf("−", "simplex", "+"), ch.shift + 1) { c?.control("fmset", "sh" to it - 1) }
        Choice("Step", FmTone.STEPS_HZ.map { String.format(Locale.US, "%.2f k", it / 1000.0) }, FmTone.STEPS_HZ.indexOf(s.stepHz.toInt()).coerceAtLeast(0)) { c?.control("fmset", "step" to it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice("Tone", FmTone.MODES, ch.toneMode) { c?.control("fmset", "tm" to it) }
        if (ch.toneMode == FmTone.DCS) Choice("DCS", FmTone.DCS_CODES.map { String.format(Locale.US, "%03d", it) }, FmTone.DCS_CODES.indexOf(ch.dcs).coerceAtLeast(0)) { c?.control("fmset", "d" to FmTone.DCS_CODES[it]) }
        else Choice("CTCSS", FmTone.CTCSS.map { String.format(Locale.US, "%.1f", it / 10.0) }, FmTone.CTCSS.indexOf(ch.tone).coerceAtLeast(0)) { c?.control("fmset", "t" to FmTone.CTCSS[it]) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Switch(checked = ch.high, onCheckedChange = { c?.control("fmset", "hp" to it) }); Text("High power")
        Switch(checked = ch.wide, onCheckedChange = { c?.control("fmset", "w" to it) }); Text(if (ch.wide) "FM (25 kHz)" else "NFM (12.5 kHz)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.squelchAuto, onCheckedChange = { c?.control("fmset", "sqa" to it) }); Text("Automatic squelch", Modifier.padding(start = 6.dp))
    }
    if (!s.squelchAuto) {
        Text("Squelch level ${s.squelchManual}", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in 0..8) OutlinedButton(onClick = { c?.control("fmset", "sq" to l) }, modifier = Modifier.width(36.dp)) { Text("$l", fontSize = 11.sp) }
        }
    }
    Choice("Noise reduction (Rev2.1, restart)", listOf("off", "low", "mid", "high"), s.noiseReduction) { c?.control("fmset", "nr" to it) }

    if (AppState.trackerInfo.collectAsState().value?.hasSatFm == true) FmSatBlock(s)

    Section("Scan, ARTS, EAI")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.scan == 0) {
            OutlinedButton(onClick = { c?.control("fmscan", "s" to "start", "mem" to true) }) { Text("Scan memories") }
            OutlinedButton(onClick = { c?.control("fmscan", "s" to "start", "mem" to false) }) { Text("Scan band") }
        } else Button(onClick = { c?.control("fmscan", "s" to "stop") }) { Text("Stop scan") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { c?.control("fmarts", "s" to if (s.arts > 0) "stop" else "start") }) { Text(if (s.arts > 0) "Stop ARTS" else "Start ARTS") }
        OutlinedButton(onClick = { c?.control("fmeai", "s" to if (s.eaiSeconds > 0) "stop" else "start") }) { Text(if (s.eaiSeconds > 0) "Stop EAI" else "Start EAI") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.aprsBeacon, onCheckedChange = { c?.control("fmset", "aprs" to it) }); Text("APRS beacons on the APRS frequency", Modifier.padding(start = 6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.qsyInBeacon, onCheckedChange = { c?.control("fmset", "qsyb" to it) }); Text("Channel frequency in the beacon (QSY)", Modifier.padding(start = 6.dp))
    }
    Text("ARTS, EAI times, man-down, scan limits and the memories of the WebUI: Config tab, group \"fm\".", fontSize = 11.sp)

    Section("Memories")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(memName, { memName = it.take(8) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = {
            val used = mems.map { it.index }.toSet()
            val slot = if (s.memory) s.memIndex else (0 until 32).firstOrNull { it !in used } ?: -1
            if (slot >= 0) c?.control("fmmem", "op" to "store", "i" to slot, "n" to memName.trim())
            memName = ""
        }, modifier = Modifier.padding(start = 8.dp)) { Text("Store") }
        if (s.memory) OutlinedButton(onClick = { c?.control("fmmem", "op" to "vfo") }, modifier = Modifier.padding(start = 6.dp)) { Text("VFO") }
    }
    for (m in mems) {
        Row(Modifier.fillMaxWidth().clickable { c?.control("fmmem", "op" to "recall", "i" to m.index) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.memory && s.memIndex == m.index) "●" else "○", Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(String.format(Locale.US, "M%02d  %s  %s", m.index + 1, m.freqText, m.name), fontSize = 14.sp)
                Text(m.summary, fontSize = 11.sp)
            }
            OutlinedButton(onClick = { c?.control("fmmem", "op" to "del", "i" to m.index) }) { Text("Del", fontSize = 11.sp) }
        }
    }
}


/** The VFO following a satellite: Doppler on the downlink, uplink pre-compensated, CTCSS of the transmitter, APRS through a digipeater
 *  (ISS 145.825, path of the WebUI). The satellite is the one chosen in the SAT tab (or the tracker's menu). */
@Composable
private fun FmSatBlock(s: FmState) {
    val c = AppState.controller
    val txs by AppState.satTransmitters.collectAsState()
    val sat by AppState.satState.collectAsState()
    var msg by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) { c?.control("sat"); c?.control("sattxlist"); onDispose { } }
    Section("Satellite")
    val fs = s.sat
    if (fs == null) {
        Text("The VFO can follow the satellite chosen in the SAT tab: " + (sat?.name ?: "none chosen yet") + ".", fontSize = 12.sp)
        val usable = txs.filter { it.rxKind != "n/a" || it.txKind != "n/a" }
        if (usable.isEmpty()) Text("No transmitter of it in the band of this radio.", fontSize = 12.sp)
        for (t in usable) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${t.description.ifBlank { t.mode }} · ${t.mode}" + (t.toneHz?.let { String.format(Locale.US, " · %.1f Hz", it) } ?: ""), Modifier.weight(1f), fontSize = 13.sp)
            OutlinedButton(onClick = { c?.control("satrx", "on" to true, "tx" to t.index) }) { Text("Follow") }
        }
        return
    }
    Text("${fs.name} · " + (if (fs.elDeg != null) String.format(Locale.US, "El %.0f° Az %.0f°", fs.elDeg, fs.azDeg ?: 0.0) else "no time / position") +
         (if (fs.aprs) " · APRS digipeater" else ""), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Text("RX ${FmChannel.mhz(fs.rxHz)} MHz (Doppler ${fs.dopplerHz} Hz, fine ${fs.fineHz} Hz)" + (if (fs.listenUplink) " · downlink on another band: listening to the uplink" else ""), fontSize = 12.sp)
    Text(if (fs.upHz > 0) "TX ${FmChannel.mhz(fs.upHz)} MHz" + (if (fs.toneHz > 0) String.format(Locale.US, " · CTCSS %.1f Hz", fs.toneHz) else "") else "No uplink in this band", fontSize = 12.sp)
    Text(if (fs.txOk) "Transmitting allowed now (PTT, APRS)" else "No TX now: ${fs.why ?: "?"}", fontSize = 12.sp)
    if (fs.aprs) Text("APRS frames heard ${fs.heard}, sent ${fs.sent}", fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { c?.control("satrx", "fine" to -100) }) { Text("−100 Hz") }
        OutlinedButton(onClick = { c?.control("satrx", "fine" to 100) }) { Text("+100 Hz") }
        Button(onClick = { c?.control("satrx", "on" to false) }) { Text("Stop") }
    }
    if (fs.aprs) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(msg, { msg = it.take(64) }, label = { Text("APRS text (empty = WebUI message)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { c?.control("sattx", "tx" to fs.tx, "txt" to msg.trim()); msg = "" }, enabled = fs.txOk, modifier = Modifier.padding(start = 6.dp)) { Text("Send") }
        }
        OutlinedButton(onClick = { c?.control("sattx", "tx" to fs.tx, "pos" to true) }, enabled = fs.txOk) { Text("Send position via satellite") }
        Text("With APRS beacons on, the beacons and messages go through the satellite while it is in view (and back to the APRS frequency after).", fontSize = 11.sp)
    }
}
