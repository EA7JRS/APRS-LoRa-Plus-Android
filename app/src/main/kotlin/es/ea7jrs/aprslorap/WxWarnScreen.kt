/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.ea7jrs.aprslorap.core.WxWarn
import es.ea7jrs.aprslorap.core.WxWarning

/** Which warnings already had their window (or were closed), and the one asked for from the list. */
object WxWarnPopups {
    val shown = mutableStateOf(setOf<String>())
    val asked = mutableStateOf<WxWarning?>(null)
}

private fun levelColor(level: Int) = Color(0xFF000000 or WxWarn.levelColor(level).toLong())
private val Ink = Color(0xFF131A22)

private fun span(w: WxWarning): String = (if (w.onset.isEmpty()) "now" else WxWarn.shortTime(w.onset)) + (if (w.expires.isEmpty()) "" else " to " + WxWarn.shortTime(w.expires))
private fun where(w: WxWarning): String = when {
    !w.hasArea -> w.area
    w.inside -> w.area + " · you are inside"
    else -> w.area + " · " + Fmt.km(w.distanceKm) + " away"
}

/**
 * The pop-up window of a weather warning: once for each new warning of our zone at the chosen level or above, with the header band
 * in the colour of its level (the same window as the plotter and the Display). An emergency alert goes first.
 */
@Composable
fun WxWarnOverlay(nav: Navigator) {
    val list by AppState.wxWarnings.collectAsState()
    val alerts by AlertCenter.items.collectAsState()
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var shown by WxWarnPopups.shown
    var asked by WxWarnPopups.asked
    if (alerts.isNotEmpty()) return
    val minLevel = settings.warnPopup
    val waiting = list.filter { minLevel > 0 && it.level >= minLevel && (!it.hasArea || it.inside) && it.key !in shown }
    val w = asked ?: waiting.firstOrNull() ?: return
    val close = { if (asked != null) asked = null else shown = shown + w.key }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xBC06090D)).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Alp.Panel)) {
                Column(Modifier.fillMaxWidth().background(levelColor(w.level)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("WEATHER WARNING", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 25.sp, color = Ink)
                    Text(listOf(w.source, w.levelName + " level", w.phenomenon).filter { it.isNotBlank() }.joinToString(" · "),
                        fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(w.event, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 24.sp, color = Alp.Text)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Bg).border(1.dp, levelColor(w.level).copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOfNotNull("Area" to where(w), "In force" to span(w), w.probability.takeIf { it.isNotBlank() }?.let { "Probability" to it }).forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(label, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.width(84.dp))
                                Text(value, fontSize = 15.sp, lineHeight = 19.sp, color = Alp.Text, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (w.description.isNotBlank()) Text(w.description, fontSize = 16.sp, lineHeight = 21.sp, color = Alp.Text)
                    if (w.instruction.isNotBlank()) Text(w.instruction, fontSize = 14.sp, lineHeight = 19.sp, color = Alp.SubText)
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioKey("ALL WARNINGS", Modifier.weight(1f).height(50.dp), color = Alp.Cyan, border = Alp.Cyan) {
                        asked = null; shown = shown + list.map { it.key }; nav.go("WARN")
                    }
                    val more = if (asked == null) waiting.size - 1 else 0
                    RadioKey(if (more > 0) "CLOSE ($more more)" else "CLOSE", Modifier.weight(1f).height(50.dp)) { close() }
                }
            }
        }
    }
}

/** Weather warnings in force for our area, and where they come from. */
@Composable
fun WxWarnScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val list by AppState.wxWarnings.collectAsState()
    val message by AppState.wxWarnMessage.collectAsState()
    var source by remember { mutableStateOf(settings.warnSource) }
    var popup by remember { mutableStateOf(settings.warnPopup) }
    var key by remember { mutableStateOf(settings.aemetKey) }
    var country by remember { mutableStateOf(settings.warnCountry) }
    var area by remember { mutableStateOf(settings.warnArea) }
    var setup by remember { mutableStateOf(settings.warnSource == 0) }
    LaunchedEffect(Unit) { WxWarnFetcher.maybeFetch(settings) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("WEATHER WARNINGS", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            Chip("SETUP", setup, color = Alp.Amber) { setup = !setup }
        }
        Text(message.ifBlank { if (source == 0) "Off: choose a source in SETUP." else "Waiting for the first download." }, fontSize = 13.sp, color = Alp.Muted)
        if (setup) Panel(Modifier.fillMaxWidth()) {
            SectionLabel("SOURCE")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf("OFF", "AEMET", "METEOALARM").forEachIndexed { i, n -> Chip(n, source == i, Modifier.weight(1f)) { source = i; settings.warnSource = i; WxWarnFetcher.fetchNow(settings) } }
            }
            Text(when (source) {
                1 -> "Official AEMET warnings (Spain) for the zone you are in and those within 30 km, from the tracker's position or your locator. Needs your own free key."
                2 -> "Meteoalarm, the European weather services' warnings. It has no zone outlines: write the names of your zones as Meteoalarm calls them."
                else -> "Official weather warnings (yellow, orange, red) with a pop-up window when a new one comes in for your area."
            }, fontSize = 12.sp, color = Alp.Muted)
            if (source == 1) OutlinedTextField(key, { key = it.trim(); settings.aemetKey = key }, label = { Text("AEMET OpenData API key (free, opendata.aemet.es)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (source == 2) OutlinedTextField(country, { country = it; settings.warnCountry = it }, label = { Text("Country (spain, portugal, france…)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (source != 0) OutlinedTextField(area, { area = it; settings.warnArea = it }, label = { Text(if (source == 1) "Zone names, only while there is no position" else "Zone names, with commas (Sevilla, Estrecho)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionLabel("POP-UP WINDOW FROM", Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf("NEVER", "YELLOW", "ORANGE", "RED").forEachIndexed { i, n ->
                    Chip(n, popup == i, Modifier.weight(1f), color = if (i == 0) Alp.Cyan else levelColor(i)) { popup = i; settings.warnPopup = i }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (list.isEmpty()) Text(if (source == 0) "Weather warnings are off." else "No warnings in force for your area.", color = Alp.Muted, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                items(list, key = { it.key }) { w ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { WxWarnPopups.asked.value = w }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(8.dp).height(44.dp).clip(RoundedCornerShape(4.dp)).background(levelColor(w.level)))
                        Column(Modifier.weight(1f)) {
                            Text(w.event, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Alp.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(where(w), fontSize = 13.sp, color = Alp.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(span(w) + (w.probability.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted)
                        }
                        Text(w.levelName, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = levelColor(w.level))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey("REFRESH", Modifier.weight(1f).height(54.dp)) { WxWarnFetcher.fetchNow(settings) }
            RadioKey("WX", Modifier.weight(1f).height(54.dp), sub = "stations") { nav.go("WX") }
        }
    }
}
