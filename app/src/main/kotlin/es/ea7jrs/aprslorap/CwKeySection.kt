/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.KeyerMode
import es.ea7jrs.aprslorap.core.SerialLine

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, value: T, enabled: Boolean, onPick: (T) -> Unit) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    for ((v, text) in options) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled && v != value) { onPick(v) }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (v == value) "●" else "○", Modifier.width(24.dp))
            Text(text, fontSize = 14.sp)
        }
    }
}

/** Telegraph key on the phone (tracker in MORSE mode): adapter, keyer, real time or text, sidetone. */
@Composable
fun PhoneKeySection(hasRemoteKey: Boolean) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val active by AppState.cwActive.collectAsState()
    val status by AppState.cwInputStatus.collectAsState()
    val keyDown by AppState.cwKeyDown.collectAsState()
    val decoded by AppState.cwText.collectAsState()
    var input by remember { mutableStateOf(settings.cwInput) }
    var keyer by remember { mutableStateOf(settings.cwKeyer) }
    var wpm by remember { mutableStateOf(settings.cwWpm.toString()) }
    var realtime by remember { mutableStateOf(settings.cwRealtime) }
    var delay by remember { mutableStateOf(settings.cwDelayMs.toString()) }
    var tone by remember { mutableStateOf(settings.cwSidetone) }
    var toneHz by remember { mutableStateOf(settings.cwToneHz.toString()) }
    var ditLine by remember { mutableStateOf(settings.cwDitLine) }
    var dahLine by remember { mutableStateOf(settings.cwDahLine) }
    val editable = !active

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Key on the phone", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (!hasRemoteKey) {
            Text("The tracker's firmware does not take a remote key (needs the BLE control service with the \"k\" command).", fontSize = 12.sp)
            return@Column
        }
        Choice(
            "Adapter",
            listOf(
                "keyboard" to "USB / Bluetooth keyboard type (VBand, Vail, home-made): Left Ctrl or [ = dit, Right Ctrl or ] = dah",
                "midi" to "MIDI (Vail in MIDI mode, USB or Bluetooth)",
                "serial" to "USB serial (FTDI, CP210x, CH34x, PL2303): key between DTR/RTS and an input line",
            ),
            input, editable,
        ) { input = it }
        if (input == "serial") {
            val lines = SerialLine.values().map { it to it.name }
            Row {
                Column(Modifier.weight(1f)) { Choice("Dit / straight key line", lines, ditLine, editable) { ditLine = it } }
                Column(Modifier.weight(1f)) { Choice("Dah line", lines, dahLine, editable) { dahLine = it } }
            }
        }
        Choice(
            "Keyer",
            listOf(
                KeyerMode.STRAIGHT to "Straight key, or the adapter's own keyer (Vail)",
                KeyerMode.IAMBIC_A to "Iambic A (keyer on the phone)",
                KeyerMode.IAMBIC_B to "Iambic B (keyer on the phone)",
            ),
            keyer, editable,
        ) { keyer = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                wpm, { wpm = it.filter(Char::isDigit).take(2) }, label = { Text("WPM") }, singleLine = true, enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp),
            )
            Switch(checked = tone, onCheckedChange = { tone = it }, enabled = editable)
            Text("Sidetone")
            OutlinedTextField(
                toneHz, { toneHz = it.filter(Char::isDigit).take(4) }, label = { Text("Hz") }, singleLine = true, enabled = editable && tone,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp),
            )
        }
        Choice(
            "Sending",
            listOf(
                true to "Real time: your own timing, the tracker plays it with a fixed delay",
                false to "Text: the phone decodes, the tracker sends the words at its own speed",
            ),
            realtime, editable,
        ) { realtime = it }
        if (realtime) {
            OutlinedTextField(
                delay, { delay = it.filter(Char::isDigit).take(4) }, label = { Text("Delay (ms): more if the link is poor") }, singleLine = true,
                enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!active) {
                Button(onClick = {
                    settings.cwInput = input
                    settings.cwKeyer = keyer
                    settings.cwWpm = wpm.toIntOrNull() ?: 18
                    settings.cwRealtime = realtime
                    settings.cwDelayMs = delay.toIntOrNull() ?: 150
                    settings.cwSidetone = tone
                    settings.cwToneHz = toneHz.toIntOrNull() ?: 700
                    settings.cwDitLine = ditLine
                    settings.cwDahLine = dahLine
                    CwEngine.start(ctx)
                }) { Text("Start keying") }
            } else {
                OutlinedButton(onClick = { CwEngine.stop() }) { Text("Stop") }
            }
            Spacer(Modifier.width(12.dp))
            Text(if (keyDown) "■ KEY DOWN" else "□", fontWeight = FontWeight.Bold)
        }
        Text(status, fontSize = 12.sp)
        if (decoded.isNotEmpty()) Text(decoded, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}
