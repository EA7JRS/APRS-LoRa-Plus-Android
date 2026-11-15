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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Geo
import java.util.Locale

/** Weather stations heard (APRS "_" reports): nearest first, or by time / temperature / wind. Touch one for its detail. */
@Composable
fun WxScreen(nav: Navigator) {
    val all = rememberStationViews()
    var sort by rememberSaveable { mutableStateOf(0) }
    KeepHeardFresh(30_000)
    rememberNow(30_000)
    val wx = all.filter { it.wx != null }
    val dist = remember(all) { wx.associate { it.call to Own.to(it.lat, it.lon) } }
    val list = when (sort) {
        1 -> wx.sortedByDescending { it.wxMs ?: it.lastMs }
        2 -> wx.sortedByDescending { it.wx?.tempC ?: -999.0 }
        3 -> wx.sortedByDescending { maxOf(it.wx?.gustKmh ?: 0.0, it.wx?.windKmh ?: 0.0) }
        else -> wx.sortedBy { dist[it.call]?.first ?: Double.MAX_VALUE }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("APRS WEATHER STATIONS", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            Text("${list.size}", fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Muted, modifier = Modifier.padding(end = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("DIST", "TIME", "TEMP", "WIND").forEachIndexed { i, n -> Chip(n, i == sort) { sort = i } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (list.isEmpty()) Text("No weather station heard yet. APRS weather stations (symbol “_”) appear here with their last report.",
                color = Alp.Muted, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                items(list, key = { it.call }) { s -> WxRow(s, dist[s.call]) { nav.go("STATION:" + s.call) } }
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey("S.LIST", Modifier.weight(1f).height(54.dp)) { nav.go("STATIONS") }
            RadioKey("MAP", Modifier.weight(1f).height(54.dp)) { MapFocus.call = list.firstOrNull()?.call; nav.go("MAP") }
        }
    }
}

@Composable
private fun WxRow(s: StationView, d: Pair<Double, Double>?, onClick: () -> Unit) {
    val w = s.wx ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AprsSymbolIcon(s.symbol, 26.dp, Alp.SymbolBg)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Alp.Text, maxLines = 1, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(8.dp))
                Text(Fmt.ago(s.wxMs), fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dir = w.windDirDeg
                if (dir != null && (w.windKmh ?: 1.0) >= 1.0) BearingArrow((dir + 180) % 360.0, Alp.Cyan, 15.dp)
                Text(listOfNotNull(w.wind(Fmt.units), w.gustKmh?.let { "gust " + Fmt.speed(it) }).joinToString(" · ").ifEmpty { "—" },
                    fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Cyan, maxLines = 1)
            }
            Text(listOfNotNull(
                w.windKmh?.takeIf { it >= 1.0 }?.let { "F" + es.ea7jrs.aprslorap.core.WxCalc.beaufort(es.ea7jrs.aprslorap.core.WxCalc.kmhToKn(it)) },
                w.humidityPct?.let { "$it %" },
                w.pressureHpa?.let { String.format(Locale.US, "%.0f hPa", it) + (s.wxPressD3h?.let { d -> " " + es.ea7jrs.aprslorap.core.WxCalc.trendName(d) } ?: "") },
                w.tempC?.let { t -> w.humidityPct?.let { h -> es.ea7jrs.aprslorap.core.WxCalc.dewPointC(t, h.toDouble()) } }?.let { "dew " + Fmt.temp(it, 0, unit = false) },
                (w.rain1hMm ?: w.rainMidnightMm)?.takeIf { it > 0 }?.let { String.format(Locale.US, "rain %.1f mm", it) + if ((w.rain1hMm ?: 0.0) > 0) "/h" else " today" },
            ).joinToString(" · "), fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.temp(w.tempC, unit = false), fontFamily = TechMono, fontSize = 24.sp, color = tempColor(w.tempC))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d == null) "—" else Fmt.km(d.first) + " " + Geo.cardinal16(d.second), fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted,
                    textAlign = TextAlign.End)
            }
        }
    }
}
