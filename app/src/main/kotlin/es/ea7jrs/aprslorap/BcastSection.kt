// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tracker tab: broadcast receiver on the tracker's I2C bus (RDA5807M, Si4703, Si4735). Commands "bcast" and "bcastset".
package es.ea7jrs.aprslorap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.BcastState

@Composable
fun BcastSection(chip: String) {
    val st by AppState.bcastState.collectAsState()
    val c = AppState.controller
    var freq by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) {
        c?.control("bcast", "sub" to 2)
        onDispose { c?.control("bcast", "sub" to 0) }
    }

    Section("Broadcast radio ($chip)")
    val s = st
    if (s == null) { Text("Waiting for the receiver...", fontSize = 12.sp); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.on, onCheckedChange = { c?.control("bcastset", "on" to it) })
        Text(if (s.on) "On" else "Off", Modifier.padding(start = 6.dp))
    }
    if (!s.on) return
    Text(s.freqText + (if (s.stereo) "  ST" else ""), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    if (s.station.isNotEmpty()) Text(s.station, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    if (s.radioText.isNotEmpty()) Text(s.radioText, fontSize = 13.sp)
    Text("RSSI ${s.rssi} dBµV" + (if (s.snr > 0) " · SNR ${s.snr} dB" else "") + (if (s.seeking) " · seeking" else "") + (s.clock?.let { " · RDS $it UTC" } ?: ""), fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { c?.control("bcastset", "seek" to -1) }) { Text("Seek ◀") }
        OutlinedButton(onClick = { c?.control("bcastset", "step" to -1) }) { Text("◀") }
        OutlinedButton(onClick = { c?.control("bcastset", "step" to 1) }) { Text("▶") }
        OutlinedButton(onClick = { c?.control("bcastset", "seek" to 1) }) { Text("▶ Seek") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(freq, { freq = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) }, label = { Text("MHz (FM) or kHz (AM)") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = freq.toDoubleOrNull() != null, onClick = {
            val v = freq.toDoubleOrNull() ?: 0.0
            c?.control("bcastset", "f" to (if (v < 200) Math.round(v * 1000) else Math.round(v))); freq = ""
        }, modifier = Modifier.padding(start = 8.dp)) { Text("Tune") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { c?.control("bcastset", "vol" to s.volume - 1) }) { Text("Vol −") }
        Text(if (s.muted) "mute" else "vol ${s.volume}")
        OutlinedButton(onClick = { c?.control("bcastset", "vol" to s.volume + 1) }) { Text("Vol +") }
        OutlinedButton(onClick = { c?.control("bcastset", "mute" to !s.muted) }) { Text(if (s.muted) "Unmute" else "Mute") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = s.mono, onCheckedChange = { c?.control("bcastset", "mono" to it) }); Text("Mono", Modifier.padding(start = 6.dp, end = 12.dp))
        for (b in s.bands) OutlinedButton(onClick = { c?.control("bcastset", "band" to b) }, modifier = Modifier.padding(end = 4.dp)) {
            Text((if (b == s.band) "● " else "") + BcastState.BANDS.getOrElse(b) { "?" }, fontSize = 12.sp)
        }
    }
    Text("Presets (tap to tune; Store keeps the current frequency in the first free one)", fontSize = 12.sp)
    s.presets.forEachIndexed { i, k ->
        Row(Modifier.fillMaxWidth().clickable(enabled = k > 0) { c?.control("bcastset", "op" to "recall", "i" to i) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("P${i + 1}  " + (if (k > 0) BcastState.freqText(k) else "—"), Modifier.weight(1f), fontSize = 14.sp)
            if (k > 0) OutlinedButton(onClick = { c?.control("bcastset", "op" to "del", "i" to i) }) { Text("Del", fontSize = 11.sp) }
        }
    }
    Button(onClick = {
        val free = s.presets.indexOfFirst { it == s.kHz }.takeIf { it >= 0 } ?: s.presets.indexOfFirst { it <= 0 }
        if (free >= 0) c?.control("bcastset", "op" to "store", "i" to free)
    }) { Text("Store") }
}
