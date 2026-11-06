/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Sdr
import es.ea7jrs.aprslorap.core.SigId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SigID tab: identify a signal by frequency, bandwidth, mode or words (Artemis database, sigidwiki.com). */
@Composable
fun SigIdTab() {
    val ctx = LocalContext.current
    val info by AppState.sigIdInfo.collectAsState()
    val busy by AppState.sigIdBusy.collectAsState()
    val msg by AppState.sigIdMessage.collectAsState()
    val sdr by AppState.sdr.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var freq by rememberSaveable { mutableStateOf("") }
    var tol by rememberSaveable { mutableStateOf("2") }
    var bw by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(Unit) { if (AppState.sigIdInfo.value == null) withContext(Dispatchers.IO) { SigIdDb.load(ctx) } }

    val open = SigIdDb.signals.firstOrNull { it.id == openId }
    if (open != null) { SignalDetail(open) { openId = -1 }; return }

    val q = SigId.Query(
        text = text, freqHz = Sdr.parseFreq(freq), freqTol = (tol.replace(',', '.').toDoubleOrNull() ?: 2.0) / 100.0,
        bandwidthHz = bw.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1000) }, category = cat,
    )
    val results = remember(info, q) { SigId.search(SigIdDb.signals, q) }
    val categories = remember(info) { SigIdDb.signals.flatMap { it.categories }.distinct().sorted() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("Signal identification")
            Text(info?.let { "Database version ${it.version} (${it.date}), ${it.signals} signals" } ?: "No database yet: download it (about 1 MB).", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { SigIdDb.update(ctx) }, enabled = !busy) { Text(if (info == null) "Download" else "Check for update") }
                if (info != null) OutlinedButton(onClick = { SigIdDb.update(ctx, force = true) }, enabled = !busy) { Text("Download again") }
            }
            if (msg.isNotBlank()) Text(msg, fontSize = 12.sp)
            Text("${SigId.ATTRIBUTION}. Images and audio are downloaded when you open them.", fontSize = 11.sp)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedTextField(text, { text = it }, label = { Text("Words (name, description, mode)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(freq, { freq = it }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1.4f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(tol, { tol = it }, label = { Text("± %") }, singleLine = true, modifier = Modifier.weight(0.8f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(bw, { bw = it }, label = { Text("Width kHz") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sdr.freqHz?.let { freq = String.format(java.util.Locale.US, "%.6f", it / 1e6) } }, enabled = sdr.freqHz != null) { Text("SDR frequency") }
                TextButton(onClick = { text = ""; freq = ""; bw = ""; cat = "" }) { Text("Clear") }
            }
            if (categories.isNotEmpty()) {
                for (row in (listOf("") + categories).chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in row) {
                        val label = c.ifEmpty { "All" }
                        if (c == cat) Button(onClick = { cat = c }) { Text(label, fontSize = 10.sp) } else OutlinedButton(onClick = { cat = c }) { Text(label, fontSize = 10.sp) }
                    }
                }
            }
            Text("${results.size} signal(s)", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        items(results.take(200), key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().clickable { openId = s.id }.padding(vertical = 6.dp)) {
                Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(s.freqText + "  ·  " + s.bwText + "  ·  " + (s.modulations + s.modes).distinct().joinToString(", "), fontSize = 11.sp)
                if (s.categories.isNotEmpty()) Text(s.categories.joinToString(", "), fontSize = 11.sp)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SignalDetail(s: SigId.Signal, close: () -> Unit) {
    val ctx = LocalContext.current
    var image by remember(s.id) { mutableStateOf<ImageBitmap?>(null) }
    var status by remember(s.id) { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val sdr by AppState.sdr.collectAsState()
    LaunchedEffect(s.id) {
        if (s.hasImage) {
            status = "Loading the spectrum..."
            val f = withContext(Dispatchers.IO) { SigIdDb.media(ctx, s, "png") }
            image = f?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
            status = if (image == null) "No spectrum image (no internet?)" else ""
        }
    }
    DisposableEffect(s.id) { onDispose { player?.release(); player = null } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = close) { Text("‹ Back") }
                Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Field("Frequency", s.freqText)
            Field("Bandwidth", s.bwText)
            if (s.modulations.isNotEmpty()) Field("Modulation", s.modulations.joinToString(", "))
            if (s.modes.isNotEmpty()) Field("Mode", s.modes.joinToString(", "))
            if (s.categories.isNotEmpty()) Field("Category", s.categories.joinToString(", "))
            if (s.locations.isNotEmpty()) Field("Location", s.locations.joinToString(", "))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.hasAudio) OutlinedButton(onClick = {
                    player?.release(); player = null
                    status = "Loading the audio..."
                    Thread {
                        val f = SigIdDb.media(ctx, s, "ogg")
                        if (f == null) { status = "No audio (no internet?)"; return@Thread }
                        try { player = MediaPlayer().apply { setDataSource(f.path); prepare(); start() }; status = "Playing the sigidwiki recording" }
                        catch (e: Exception) { status = "Cannot play: ${e.message}" }
                    }.start()
                }) { Text("Listen") }
                if (player != null) OutlinedButton(onClick = { player?.release(); player = null; status = "" }) { Text("Stop") }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("sigidwiki") }
            }
            val tunable = s.frequencies.filter { it in 500_000L..1_766_000_000L }.sorted()
            if (sdr.connected && tunable.isNotEmpty() && tunable.size <= 8) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (f in tunable.take(4)) OutlinedButton(onClick = { SdrControl.tune(f) }) { Text("Tune " + SigId.hz(f), fontSize = 10.sp) }
            }
            if (status.isNotBlank()) Text(status, fontSize = 12.sp)
            image?.let { Image(it, contentDescription = "Spectrum of ${s.name}", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) }
            Text(SigId.plainText(s.description), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            Text(SigId.ATTRIBUTION, fontSize = 11.sp)
        }
    }
}
