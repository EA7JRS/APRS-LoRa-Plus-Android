/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import es.ea7jrs.aprslorap.core.Wsjt.Mode as WsjtMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import es.ea7jrs.aprslorap.core.Modems
import java.util.Locale

private const val WF_W = 400                 // 0..4000 Hz, 10 Hz a pixel
private const val WF_H = 120

/** Modes tab: CW, RTTY and PSK from the app's receiver or the microphone, with a waterfall (tap to tune). */
@Composable
fun DigimodesTab() {
    val ctx = LocalContext.current
    val st by AppState.modem.collectAsState()
    val text by AppState.modemText.collectAsState()
    val row by AppState.waterfallRow.collectAsState()
    var mode by remember { mutableStateOf(st.mode) }
    var source by remember { mutableStateOf(st.source) }
    val bmp = remember { Bitmap.createBitmap(WF_W, WF_H, Bitmap.Config.ARGB_8888) }
    var frame by remember { mutableStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ModemRunner.start(mode, ModemRunner.Source.MICROPHONE, st.centerHz) else AppState.error.value = "Microphone permission is needed"
    }

    LaunchedEffect(row) {
        val r = row ?: return@LaunchedEffect
        // scroll down one line and draw the newest spectrum on top
        val px = IntArray(WF_W * (WF_H - 1)); bmp.getPixels(px, 0, WF_W, 0, 0, WF_W, WF_H - 1); bmp.setPixels(px, 0, WF_W, 0, 1, WF_W, WF_H - 1)
        val line = IntArray(WF_W)
        val floor = r.sorted()[r.size / 5]
        for (x in 0 until WF_W) {
            val bin = (x * r.size / WF_W).coerceIn(0, r.size - 1)
            val v = ((r[bin] - floor) / 30f).coerceIn(0f, 1f)
            val c = (v * 255).toInt()
            line[x] = (0xFF shl 24) or ((c) shl 16) or ((c * 0.8).toInt() shl 8) or (255 - c / 2).coerceAtLeast(40)
        }
        bmp.setPixels(line, 0, WF_W, 0, 0, WF_W, 1)
        frame++
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("Text modes (fldigi style)")
        for (r in Modems.MODES.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in r) {
                val go = { mode = m; if (st.running) ModemRunner.start(m, source, st.centerHz) }
                if (m == mode) Button(onClick = go) { Text(m, fontSize = 10.sp) } else OutlinedButton(onClick = go) { Text(m, fontSize = 10.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (s in ModemRunner.Source.entries) {
                val label = if (s == ModemRunner.Source.RECEIVER) "App receiver (USB)" else "Microphone"
                val go = { source = s }
                if (s == source) Button(onClick = go) { Text(label, fontSize = 11.sp) } else OutlinedButton(onClick = go) { Text(label, fontSize = 11.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (source == ModemRunner.Source.MICROPHONE && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else ModemRunner.start(mode, source, st.centerHz)
            }) { Text(if (st.running) "Restart" else "Start") }
            OutlinedButton(onClick = { ModemRunner.stop() }, enabled = st.running) { Text("Stop") }
            Text("AFC", fontSize = 13.sp)
            Switch(st.afc, { ModemRunner.setAfc(it) })
        }
        if (source == ModemRunner.Source.RECEIVER) Text("Use the RTL-SDR receiver of the SDR tab in USB (CW: USB or CW). Tap the waterfall on the signal.", fontSize = 11.sp)
        else Text("Put the phone next to the radio's speaker (SSB / USB). Tap the waterfall on the signal.", fontSize = 11.sp)
        Text(String.format(Locale.US, "%s at %.0f Hz · quality %.0f %%", st.mode, st.centerHz, st.quality * 100) + if (st.info.isNotEmpty()) " · ${st.info}" else "", fontSize = 12.sp)

        // waterfall, 0..4 kHz; the cursor marks the modem's audio frequency
        Canvas(Modifier.fillMaxWidth().height(140.dp).pointerInput(Unit) {
            detectTapGestures { p -> ModemRunner.setCenter((p.x / size.width * 4000.0).coerceIn(100.0, 3900.0)) }
        }) {
            frame.let { }
            drawImage(bmp.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(WF_W, WF_H), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            val x = (st.centerHz / 4000.0 * size.width).toFloat()
            drawLine(Color.Red, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            if (st.mode.startsWith("RTTY")) {
                val shift = st.mode.substringAfter('/').toDoubleOrNull() ?: 170.0
                for (d in listOf(-shift / 2, shift / 2)) { val xx = ((st.centerHz + d) / 4000.0 * size.width).toFloat(); drawLine(Color.Yellow, Offset(xx, 0f), Offset(xx, size.height), strokeWidth = 1f) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("1 kHz", fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("2 kHz", fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("3 kHz", fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("4", fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Received", fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { AppState.modemText.value = "" }) { Text("Clear") }
        }
        Text(text.ifEmpty { "—" }, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        WsjtSection(source)
        SpottingSection()
        Text("CW, RTTY and BPSK receive in the manner of fldigi (varicode, Baudot and PSK timing from fldigi, GPL). More fldigi modes come in the next steps.", fontSize = 11.sp)
    }
}

/** FT8 / FT4 (ft8_lib), WSPR (wsprd), FST4W (port of WSJT-X): decodes per UTC slot, newest first; own and tracker callsigns highlighted. */
@Composable
private fun WsjtSection(source: ModemRunner.Source) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val st by AppState.wsjt.collectAsState()
    val decodes by AppState.wsjtDecodes.collectAsState()
    var mode by remember { mutableStateOf(st.mode) }
    var rxHz by remember { mutableStateOf(st.rxHz.toString()) }
    var tolHz by remember { mutableStateOf(st.tolHz) }
    val startIt = { WsjtRunner.start(ctx, mode, source, rxHz.toIntOrNull()?.coerceIn(200, 4600) ?: es.ea7jrs.aprslorap.core.Wsjt.FST4W_RX_HZ, tolHz) }
    val mine = remember { setOf(settings.callsign.substringBefore('-'), settings.trackerCallsign.substringBefore('-')).filter { it.isNotEmpty() }.toSet() }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) WsjtRunner.start(ctx, mode, ModemRunner.Source.MICROPHONE, rxHz.toIntOrNull()?.coerceIn(200, 4600) ?: es.ea7jrs.aprslorap.core.Wsjt.FST4W_RX_HZ, tolHz)
        else AppState.error.value = "Microphone permission is needed"
    }
    Section("FT8 / FT4 / WSPR / FST4W (WSJT-X modes)")
    if (!WsjtRunner.available(ctx)) { Text("This build of the app has no FT8 / FT4 / WSPR decoder for this phone.", fontSize = 12.sp); return }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((label, m) in listOf("FT8" to WsjtMode.FT8, "FT4" to WsjtMode.FT4, "WSPR" to WsjtMode.WSPR, "FST4W" to WsjtMode.FST4W_120)) {
            val sel = if (m.isFst4w) mode.isFst4w else m == mode
            val go = { if (!(m.isFst4w && mode.isFst4w)) mode = m }
            if (sel) Button(onClick = go) { Text(label, fontSize = 11.sp) } else OutlinedButton(onClick = go) { Text(label, fontSize = 11.sp) }
        }
    }
    if (mode.isFst4w) {
        if (!WsjtRunner.available(ctx, mode)) Text("This build of the app has no FST4W decoder for this phone.", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in WsjtMode.entries.filter { it.isFst4w }) {
                val go = { mode = m }
                val l = m.label.removePrefix("FST4W-") + " s"
                if (m == mode) Button(onClick = go) { Text(l, fontSize = 11.sp) } else OutlinedButton(onClick = go) { Text(l, fontSize = 11.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(rxHz, { rxHz = it.filter(Char::isDigit).take(4) }, label = { Text("Rx Hz") }, singleLine = true, modifier = Modifier.width(110.dp))
            val tols = listOf(50, 100, 200, 500)
            OutlinedButton(onClick = { tolHz = tols[(tols.indexOf(tolHz) + 1) % tols.size] }) { Text("± $tolHz Hz", fontSize = 11.sp) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            if (source == ModemRunner.Source.MICROPHONE && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else startIt()
        }) { Text(if (st.running) "Restart" else "Decode") }
        OutlinedButton(onClick = { WsjtRunner.stop() }, enabled = st.running) { Text("Stop") }
    }
    Text(st.status + (st.dialHz?.let { "  ·  dial " + es.ea7jrs.aprslorap.core.Sdr.mhz(it) + " MHz (USB)" } ?: ""), fontSize = 12.sp)
    Text("Same source as above. Receiver in USB with a 3 kHz filter; the phone's clock must be on time (network time). WSPR: 2-minute slots from even minutes, around 1500 Hz audio. FST4W: slots of 2, 5, 15 or 30 minutes from the hour; the frequency shown is the lowest of its 4 tones.", fontSize = 11.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Decoded (${decodes.size})", fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { AppState.wsjtDecodes.value = emptyList() }) { Text("Clear") }
    }
    val fmt = remember { java.text.SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    for (d in decodes.takeLast(40).asReversed()) {
        val hit = d.calls.any { it in mine }
        Text(String.format(Locale.US, "%s %+3.0f %+4.1f %s  %s", fmt.format(java.util.Date(d.slotMs)), d.snr, d.dt,
                d.rfHz?.let { es.ea7jrs.aprslorap.core.Sdr.mhz(it) } ?: String.format(Locale.US, "%4d", d.audioHz), d.message),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (hit) Alp.RedSoft else Color.Unspecified)
    }
}
