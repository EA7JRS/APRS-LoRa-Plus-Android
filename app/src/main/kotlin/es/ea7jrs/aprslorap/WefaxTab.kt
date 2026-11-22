// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Weather facsimile: the charts EA7JRS/Wefax has received on a computer of the local network, its schedule with the VOACAP
// reliability of each broadcast, and the prediction of any short wave circuit (a DX station, a contact) to that receiver.
package es.ea7jrs.aprslorap

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.RotorFollow
import es.ea7jrs.aprslorap.core.Wefax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun utc(ms: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
private fun khzText(k: Double): String = if (k == Math.floor(k)) "${k.toLong()} kHz" else String.format(Locale.ROOT, "%.1f kHz", k)
private fun pct(r: Double): String = "${Math.round(r * 100)} %"

/** Decodes a PNG without filling the memory: a chart is 1810 pixels wide and may be 3000 long. */
private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
fun WefaxTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    val charts by WefaxLink.charts.collectAsState()
    val status by WefaxLink.status.collectAsState()
    val stations by WefaxLink.stations.collectAsState()
    val table by WefaxLink.table.collectAsState()
    val follow by WefaxLink.follow.collectAsState()
    val skip by WefaxLink.skip.collectAsState()
    val msg by WefaxLink.message.collectAsState()
    var host by rememberSaveable { mutableStateOf(settings.wefaxHost) }
    var port by rememberSaveable { mutableStateOf(settings.wefaxPort.toString()) }
    var open by remember { mutableStateOf<Wefax.Chart?>(null) }

    // while the screen is open: every 10 seconds
    LaunchedEffect(Unit) { while (true) { WefaxLink.refresh(settings); delay(10_000) } }

    val shown = open
    if (shown != null) { ChartViewer(settings, shown) { open = null }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("Wefax computer (name or IP)") })
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true, label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                settings.wefaxHost = host; settings.wefaxPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: Wefax.DEFAULT_PORT
                WefaxLink.stations.value = null
                scope.launch { WefaxLink.refresh(settings) }
            }) { Text("Read") }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = Alp.Amber)

        status?.let { StatusCard(it, stations) }
        if (status?.receiving == true) LiveChart(settings)

        val list = stations
        if (list != null) ScheduleCard(list, table, follow, skip)

        Text(if (charts.isEmpty()) "No charts received yet" else "${charts.size} charts", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        for (c in charts.take(60)) ChartRow(settings, c, list) { open = c }

        CircuitCard(settings)
        Text("Charts, schedule and predictions come from EA7JRS/Wefax on your network; the app changes nothing there. Predictions are VOACAP's " +
             "(NTIA / ITS): the median of the month to where the Wefax receiver is, with assumed antennas and noise. They do not see today's storm or blackout. " +
             "Schedules: NOAA / National Weather Service, transcribed automatically.", fontSize = 11.sp, color = Alp.Muted)
    }
}

@Composable
private fun StatusCard(s: Wefax.Status, list: Wefax.StationList?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Alp.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (s.alive) Wefax.stateText(s.state) else "The receiver does not answer", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (s.alive) Alp.Green else Alp.Amber)
        if (s.alive && s.tunedKhz != null) Text(khzText(s.tunedKhz!!) + if (s.receiving) " · ${s.lines} lines" else "", fontSize = 13.sp, color = Alp.Text)
        val t = s.nextStartMs
        if (t != null) {
            val name = list?.byId(s.nextStation)?.name ?: s.nextStation
            Text("Next: ${utc(t, "HH:mm")} UTC · $name" + (s.nextKhz?.let { " · ${khzText(it)}" } ?: "") +
                 (s.nextReliability?.let { " · VOACAP ${pct(it)}" } ?: ""), fontSize = 13.sp, color = Alp.Text)
            if (s.nextTitle.isNotBlank()) Text(s.nextTitle, fontSize = 12.sp, color = Alp.Muted)
        }
    }
}

@Composable
private fun LiveChart(settings: Settings) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            WefaxLink.image(settings, "/live.png")?.let { b -> withContext(Dispatchers.Default) { decode(b, 1400) }?.let { image = it } }
            delay(20_000)
        }
    }
    image?.let {
        Text("Receiving now", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Image(it, "Chart being received", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
    }
}

@Composable
private fun ScheduleCard(list: Wefax.StationList, table: Wefax.Table?, follow: String, skip: String) {
    val now = System.currentTimeMillis()
    val slots = remember(list, follow, skip, now / 60_000) { Wefax.upcoming(list, follow, skip, now, 6) }
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Alp.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Next 6 hours", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (slots.isEmpty()) Text(if (follow.isBlank()) "Wefax follows no station: choose them in its Stations tab" else "Nothing in the schedule", fontSize = 12.sp, color = Alp.Muted)
        for (sl in slots.take(40)) {
            cal.timeInMillis = sl.startMs
            val hour = cal.get(Calendar.HOUR_OF_DAY); val minute = hour * 60 + cal.get(Calendar.MINUTE)
            val onAir = sl.station.freqs.filter { it.onAir(minute) }.map { it.khz }
            val hours = table?.takeIf { it.valid(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1) }?.stations?.get(sl.station.id)
            val best = hours?.best(onAir, hour)
            val rel = if (hours != null && best != null) hours.reliability(best, hour) else null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(utc(sl.startMs, "HH:mm"), Modifier.width(48.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (sl.startMs <= now) Alp.Green else Alp.Text)
                Column(Modifier.weight(1f)) {
                    Text(sl.broadcast.title, fontSize = 13.sp, color = if (sl.skipped) Alp.Faint else Alp.Text)
                    Text(sl.station.name + (best?.let { " · ${khzText(it)}" } ?: "") + (if (sl.skipped) " · not wanted" else ""), fontSize = 11.sp, color = Alp.Muted)
                }
                if (rel != null) Text(pct(rel), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (rel >= 0.7) Alp.Green else if (rel >= 0.3) Alp.Amber else Alp.Red)
            }
        }
        if (table == null) Text("No VOACAP table on the Wefax computer: reliabilities are not shown", fontSize = 11.sp, color = Alp.Muted)
    }
}

@Composable
private fun ChartRow(settings: Settings, c: Wefax.Chart, list: Wefax.StationList?, onOpen: () -> Unit) {
    var thumb by remember(c.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(c.id) { WefaxLink.image(settings, Wefax.chartPath(c.id))?.let { b -> thumb = withContext(Dispatchers.Default) { decode(b, 360) } } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Alp.Panel).clickable { onOpen() }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(96.dp).height(72.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color.White)) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
        }
        Column(Modifier.weight(1f)) {
            Text(c.title.ifBlank { "Untitled chart" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${utc(c.receivedMs, "yyyy-MM-dd HH:mm")} UTC · ${list?.byId(c.station)?.name ?: c.station.ifBlank { "?" }} · ${khzText(c.khz)}", fontSize = 12.sp, color = Alp.Muted)
            Text("${c.height} lines · " + (if (c.complete) "complete" else "cut (${c.ended})") + (if (c.phased) "" else " · not phased") + (if (c.keep) " · kept" else ""), fontSize = 12.sp, color = Alp.Muted)
        }
    }
}

@Composable
private fun ChartViewer(settings: Settings, c: Wefax.Chart, onClose: () -> Unit) {
    var image by remember(c.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(c.id) { mutableStateOf(false) }
    var wide by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(c.id) {
        val b = WefaxLink.image(settings, Wefax.chartPath(c.id))
        image = b?.let { withContext(Dispatchers.Default) { decode(it, 3600) } }
        failed = image == null
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { "Untitled chart" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("${utc(c.receivedMs, "yyyy-MM-dd HH:mm")} UTC · ${khzText(c.khz)} · ${c.lpm} LPM · IOC ${c.ioc}", fontSize = 11.sp, color = Alp.Muted)
            }
            Button(onClick = { wide = !wide }) { Text(if (wide) "Fit" else "Zoom") }
            Button(onClick = onClose) { Text("Close") }
        }
        val img = image
        if (img == null) Text(if (failed) "The chart could not be read" else "Reading the chart…", fontSize = 13.sp, color = Alp.Muted)
        else if (wide) Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Image(img, c.title, Modifier.width(1400.dp), contentScale = ContentScale.FillWidth)
        } else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Image(img, c.title, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        }
    }
}

/** A short wave circuit from a station (callsign, locator or position) to where the Wefax receiver is. */
@Composable
private fun CircuitCard(settings: Settings) {
    val scope = rememberCoroutineScope()
    var where by rememberSaveable { mutableStateOf("") }
    var mhz by rememberSaveable { mutableStateOf("") }
    var watts by rememberSaveable { mutableStateOf("100") }
    var result by remember { mutableStateOf<Wefax.Circuit?>(null) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Alp.Panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("HF circuit (VOACAP)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("From a station to the Wefax receiver: when a band is likely to be open this month. Leave the frequency empty for all the HF amateur bands.", fontSize = 12.sp, color = Alp.Muted)
        OutlinedTextField(where, { where = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Callsign, locator or lat, lon") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(mhz, { mhz = it }, Modifier.weight(1f), singleLine = true, label = { Text("MHz (2 to 30)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(watts, { watts = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true, label = { Text("Their power, W") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                val text = where.trim()
                val latLon = Regex("(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)").matchEntire(text)?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                    ?: (try { RotorFollow.locatorToLatLon(text) } catch (e: Exception) { null })
                    ?: DxLink.cty?.lookup(text.uppercase(Locale.ROOT))?.let { it.lat to it.lon }
                val f = mhz.replace(',', '.').toDoubleOrNull()
                val khz = if (f != null) listOf(f * 1000.0) else Wefax.HAM_KHZ
                val kw = (watts.toDoubleOrNull() ?: 100.0) / 1000.0
                result = null
                if (latLon == null) { note = "I do not know where that is: write a callsign, a locator (IM66uq) or a position (36.53, -6.29)"; return@Button }
                if (Wefax.circuitPath(latLon.first, latLon.second, khz, kw) == null) { note = "VOACAP works from 2 to 30 MHz"; return@Button }
                note = "Asking Wefax…"
                scope.launch {
                    val c = WefaxLink.circuit(settings, latLon.first, latLon.second, khz, kw)
                    result = c
                    note = when { c == null -> "Wefax does not answer"; !c.ok -> Wefax.whyText(c.why); else -> "" }
                }
            }) { Text("Predict") }
        }
        if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = Alp.Amber)
        val c = result; val h = c?.hours
        if (c != null && h != null) {
            val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
            Text((c.km?.let { "${Math.round(it)} km · " } ?: "") + "sunspot number ${c.ssn?.let { Math.round(it) } ?: "?"} · now ${String.format(Locale.ROOT, "%02d", hour)} UTC", fontSize = 12.sp, color = Alp.Muted)
            for ((i, k) in h.khz.withIndex()) {
                val now = h.rel.getOrNull(i)?.getOrNull(hour)
                val good = Wefax.goodHours(h.rel.getOrNull(i) ?: emptyList())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(String.format(Locale.ROOT, "%.3f MHz", k / 1000.0), Modifier.width(96.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(now?.let { pct(it) } ?: "—", Modifier.width(52.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if ((now ?: 0.0) >= 0.7) Alp.Green else if ((now ?: 0.0) >= 0.3) Alp.Amber else Alp.Red)
                    Text(if (good.isEmpty()) "no good hour" else "good at $good UTC", Modifier.weight(1f), fontSize = 12.sp, color = Alp.Muted)
                }
            }
            Text("Percentage: share of the days of the month the signal is good at this hour. Good hours: half the days or more.", fontSize = 11.sp, color = Alp.Muted)
        }
    }
}
