// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Official alerts: weather (AEMET, MeteoAlarm, NWS), tsunamis, earthquakes, space weather (SWPC) and NOAA Weather Radio (SAME), with
// the sources, levels and zones. The list is the one the tracker and APRS LoRa+ Display get.
package es.ea7jrs.aprslorap

import androidx.compose.foundation.background
import es.ea7jrs.aprslorap.core.HfProp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.OfficialAlert
import es.ea7jrs.aprslorap.core.OfficialAlerts
import es.ea7jrs.aprslorap.core.SpaceWx
import java.util.Locale

@Composable
fun AlertsTab() {
    val ctx = LocalContext.current
    val settings = Settings(ctx)
    val list by AppState.officialAlerts.collectAsState()
    val space by AppState.spaceWx.collectAsState()
    val msg by AppState.officialMessage.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (list.isEmpty()) "No alerts in force" else "${list.size} in force", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Button(onClick = { OfficialAlertsFetcher.fetchNow(ctx, settings) }) { Text("Update") }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = Alp.Muted)
        if (info != null) Text(if (info?.hasAlerts == true) "The tracker shows them too (window, buzzer, TELEMETRY > ALERTS)" else "The tracker firmware is too old to show them", fontSize = 12.sp, color = Alp.Muted)
        for (a in list) AlertRow(a)
        space?.let { SpaceCard(it) }
        AlertSettings(settings)
        Text("Sources: AEMET (Meteoalerta, © AEMET), MeteoAlarm (EUMETNET, CC BY 4.0), NWS / NOAA (US public domain), tsunami.gov (PTWC, NTWC), " +
             "USGS, EMSC-CSEM and Raspberry Shake ShakeNet earthquakes, NOAA SWPC space weather. The phone downloads them with its internet at the tracker's position (or its own); " +
             "only the US weather service gets that position, rounded to 0.01°. Not a replacement for the official channels of civil protection.", fontSize = 11.sp, color = Alp.Muted)
    }
}

@Composable
private fun AlertRow(a: OfficialAlert) {
    val c = officialColor(a.level)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Alp.Panel).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(6.dp).height(46.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Column(Modifier.weight(1f)) {
            Text(a.event, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Alp.Text)
            Text(listOf(a.area, a.src.title).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = Alp.SubText)
            if (a.text.isNotBlank()) Text(a.text, fontSize = 12.sp, color = Alp.Muted, maxLines = 3)
            val f = java.text.SimpleDateFormat("EEE dd HH:mm", Locale.US)
            val v = listOfNotNull(a.onset.takeIf { it > 0 }?.let { f.format(java.util.Date(it * 1000)) }, a.expires.takeIf { it > 0 }?.let { "until " + f.format(java.util.Date(it * 1000)) })
            if (v.isNotEmpty()) Text(v.joinToString(" "), fontSize = 12.sp, color = Alp.Muted)
        }
        Text(a.levelName, fontSize = 12.sp, color = c, fontWeight = FontWeight.Bold)
    }
}

/** A NOAA scale value: in a box of the NOAA colour with black letters from level 1, plain text at level 0. */
@Composable
private fun NoaaBadge(text: String, level: Int, size: Int) {
    if (level >= 1) Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Color.Black,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF000000.toInt() or OfficialAlerts.noaaScaleRgb(level))).padding(horizontal = 4.dp))
    else Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Alp.Text)
}

@Composable
private fun SpaceCard(w: SpaceWx) {
    Section("Space weather (NOAA SWPC)")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // NOAA (SWPC) colour code, as on its website: box in yellow, light orange, orange, red or dark red for levels 1 to 5 with
        // black letters; level 0 without colour. The X-rays the same with their R scale; Kp in green below G1
        for ((b, v) in listOf('G' to w.g, 'R' to w.r, 'S' to w.s)) NoaaBadge(if (v >= 0) "$b$v" else "$b–", v, 22)
        val xr = OfficialAlerts.xrayToR(HfProp.xrayFlux(w.xray))
        Column {
            Text(listOfNotNull(w.kpNow?.let { String.format(Locale.US, "Kp %.1f", it) }, w.sfi?.let { String.format(Locale.US, "SFI %.0f", it) },
                w.ssn?.let { String.format(Locale.US, "SSN %.0f", it) },
                w.xray.takeIf { it.isNotEmpty() && xr == 0 }?.let { "X-ray $it" }).joinToString(" · "), fontSize = 14.sp, color = Alp.Text)
            if (w.xray.isNotEmpty() && xr >= 1) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("X-ray ", fontSize = 14.sp, color = Alp.Text); NoaaBadge(w.xray, xr, 14)
            }
            Text(listOfNotNull(w.bz?.let { String.format(Locale.US, "Bz %+.0f nT", it) }, w.bt?.let { String.format(Locale.US, "Bt %.0f nT", it) },
                w.windKms?.let { String.format(Locale.US, "wind %.0f km/s", it) }, w.density?.let { String.format(Locale.US, "%.1f p/cm³", it) },
                OfficialAlerts.windPressureNpa(w)?.let { String.format(Locale.US, "%.1f nPa", it) },
                w.gMax.takeIf { it > 0 }?.let { "G$it forecast" }).joinToString(" · "), fontSize = 13.sp, color = Alp.SubText)
        }
    }
    // Kp of the last 24 h (3-hour values), dotted line at Kp 5
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in w.kp) {
            val g = OfficialAlerts.kpToG(k)
            Box(Modifier.width(14.dp).height((4 + k * 4.5).dp).clip(RoundedCornerShape(2.dp))
                .background(if (g >= 1) Color(0xFF000000.toInt() or OfficialAlerts.noaaScaleRgb(g)) else Alp.Green))
        }
        Text("  Kp 24 h", fontSize = 11.sp, color = Alp.Muted)
        // SWPC forecast: the next 3-hour periods, same scale and colours, dimmed
        if (w.kpForecast.isNotEmpty()) {
            Box(Modifier.width(1.dp).height(48.dp).background(Alp.Muted))
            for (f in w.kpForecast.take(16)) {
                Box(Modifier.width(8.dp).height((4 + f.kp * 4.5).dp).clip(RoundedCornerShape(2.dp))
                    .background((if (f.g >= 1) Color(0xFF000000.toInt() or OfficialAlerts.noaaScaleRgb(f.g)) else Alp.Green).copy(alpha = 0.55f)))
            }
            Text("  forecast", fontSize = 11.sp, color = Alp.Muted)
        }
    }
    // GFZ Hp30: the same thing every half hour and without the cap at 9 (a storm shows up to 2.5 h earlier than in the Kp)
    if (w.hp30.isNotEmpty()) Row(Modifier.height(40.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (h in w.hp30) {
            val g = OfficialAlerts.kpToG(h)
            Box(Modifier.width(7.dp).height((4 + minOf(h, 12.0) * 3.0).dp).clip(RoundedCornerShape(2.dp))
                .background(if (g >= 1) Color(0xFF000000.toInt() or OfficialAlerts.noaaScaleRgb(g)) else Alp.Green))
        }
        Text(String.format(Locale.US, "  Hp30 %.1f · %s UTC (GFZ, 12 h)", w.hp30.last(),
            java.text.SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(w.hp30Time * 1000))),
            fontSize = 11.sp, color = Alp.Muted)
    }
    val nowS = System.currentTimeMillis() / 1000
    val fc = listOf("next 24 h" to (nowS to nowS + 86400), "24–48 h" to (nowS + 86400 to nowS + 2 * 86400)).mapNotNull { (n, r) ->
        OfficialAlerts.kpForecastMax(w.kpForecast, r.first, r.second)?.let { String.format(Locale.US, "%s Kp %.1f", n, it.kp) + if (it.g >= 1) " (G${it.g})" else "" }
    }
    val idx = listOfNotNull(w.aIndex?.let { String.format(Locale.US, "A %.0f", it) },
        w.dst?.let { String.format(Locale.US, "Dst %+.0f nT (%s)", it, OfficialAlerts.dstName(it)) })
    if (fc.isNotEmpty()) Text("Kp forecast: " + fc.joinToString(" · "), fontSize = 13.sp, color = Alp.Text)
    if (idx.isNotEmpty()) Text(idx.joinToString(" · "), fontSize = 13.sp, color = Alp.SubText)
    if (w.backup.isNotEmpty()) Text("${w.backup} from N0NBH (hamqsl.com): SWPC did not answer.", fontSize = 11.sp, color = Alp.Muted)
    // HF now (formulas of sat-aprs, as the tracker's TELEMETRY > HF): MUF / LUF here and the state of each band for DX
    val sfi = w.sfi
    val pos = Own.position()
    if (sfi != null && pos != null) {
        val elev = es.ea7jrs.aprslorap.core.Astro.sunPosition(System.currentTimeMillis(), pos.first, pos.second).altDeg
        val e = HfProp.estimate(sfi, elev, HfProp.xrayFlux(w.xray))
        // Hp30 when it is fresh (it reacts before the 3-hour Kp), clamped to the 0..9 the band states expect
        val geo = OfficialAlerts.geomagNow(w, System.currentTimeMillis() / 1000)
        val kp = (geo?.first ?: 0.0).coerceAtMost(9.0)
        Text(String.format(Locale.US, "HF now: MUF %.1f · LUF %.1f MHz", e.muf, e.luf) + (HfProp.bestDx(e, kp)?.let { " · best DX $it" } ?: "") +
            if (elev > 0) " (day)" else " (night)", fontSize = 13.sp, color = Alp.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, mhz) in HfProp.BANDS) {
                val st = HfProp.state(mhz, e, kp)
                val c = when (st) { HfProp.State.OPEN -> Alp.Green; HfProp.State.FAIR -> officialColor(1); HfProp.State.POOR -> officialColor(2); else -> Alp.Muted }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name.removeSuffix("m"), fontSize = 11.sp, color = Alp.SubText)
                    Text(st.short, fontSize = 11.sp, color = c, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Estimated from the solar flux, the sun's elevation here, X-rays and ${geo?.second ?: "Kp"}; without an ionosonde.", fontSize = 11.sp, color = Alp.Muted)
    }
}

@Composable
private fun AlertSettings(s: Settings) {
    val ctx = LocalContext.current
    var on by rememberSaveable { mutableStateOf(s.alertsOn) }
    var wx by rememberSaveable { mutableStateOf(s.alertWeather) }
    var eq by rememberSaveable { mutableStateOf(s.alertQuakes) }
    var tsu by rememberSaveable { mutableStateOf(s.alertTsunami) }
    var spc by rememberSaveable { mutableStateOf(s.alertSpace) }
    var same by rememberSaveable { mutableStateOf(s.alertSame) }
    var popup by rememberSaveable { mutableStateOf(s.alertPopupLevel) }
    var buzz by rememberSaveable { mutableStateOf(s.alertBuzzLevel) }
    var zones by rememberSaveable { mutableStateOf(s.alertZones) }
    var country by rememberSaveable { mutableStateOf(s.alertCountry) }
    var minutes by rememberSaveable { mutableStateOf(s.alertMinutes.toString()) }
    Section("Settings")
    @Composable fun sw(label: String, v: Boolean, set: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp); Switch(v, set)
    }
    sw("Download official alerts", on) { on = it; s.alertsOn = it; if (it) OfficialAlertsFetcher.fetchNow(ctx, s) }
    sw("Weather (AEMET · MeteoAlarm · NWS)", wx) { wx = it; s.alertWeather = it }
    sw("Earthquakes (USGS · EMSC · ShakeNet)", eq) { eq = it; s.alertQuakes = it }
    sw("Tsunamis (tsunami.gov)", tsu) { tsu = it; s.alertTsunami = it }
    sw("Space weather (SWPC)", spc) { spc = it; s.alertSpace = it }
    sw("NOAA Weather Radio (SAME: T-TWR, RTL-SDR)", same) { same = it; s.alertSame = it }
    val levels = listOf("Yellow +", "Orange +", "Red", "Never")
    Text("Window from", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        levels.forEachIndexed { i, l -> RadioKey(l, Modifier.weight(1f).height(40.dp), bg = if (popup == i + 1) Alp.Cyan else Alp.Key) { popup = i + 1; s.alertPopupLevel = popup } }
    }
    Text("Alarm sound from", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        levels.forEachIndexed { i, l -> RadioKey(l, Modifier.weight(1f).height(40.dp), bg = if (buzz == i + 1) Alp.Cyan else Alp.Key) { buzz = i + 1; s.alertBuzzLevel = buzz } }
    }
    OutlinedTextField(zones, { zones = it; s.alertZones = it }, label = { Text("MeteoAlarm zones (comma separated words; empty = whole country, window only for red)") },
        modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(country, { country = it.filter(Char::isLetter).take(2).uppercase(); s.alertCountry = country }, label = { Text("Country (ISO, empty = auto)") },
            singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3); minutes.toIntOrNull()?.let { m -> s.alertMinutes = m } },
            label = { Text("Every (min, ≥ 10)") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Text("Spain: AEMET (the alert is shown when its polygon has the position inside). Rest of Europe: MeteoAlarm by country (no polygons: use the zones). " +
         "US: NWS for the position. Earthquakes: window by magnitude and distance (M5 under 600 km, M6 under 2000 km...), the far ones only in the list.", fontSize = 11.sp, color = Alp.Muted)
    Box(Modifier.size(1.dp))
}
