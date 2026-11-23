/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Aprs
import es.ea7jrs.aprslorap.core.AprsExtra
import es.ea7jrs.aprslorap.core.AprsWeather
import es.ea7jrs.aprslorap.core.AprsWx
import es.ea7jrs.aprslorap.core.AprsSymbols
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.HeardStation
import es.ea7jrs.aprslorap.core.Nav
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.SMeter
import es.ea7jrs.aprslorap.core.Spots
import es.ea7jrs.aprslorap.core.Station
import kotlinx.coroutines.launch
import java.util.Locale

// The tracker's heard list says speed 0 and course 0 when the packet carried neither (its library has no "unknown"): only a
// station it says is moving has a speed and a course worth showing
private fun heardSpeed(h: es.ea7jrs.aprslorap.core.HeardStation?): Double? = h?.speedKmh?.takeIf { it > 0 }?.toDouble()
private fun heardCourse(h: es.ea7jrs.aprslorap.core.HeardStation?): Int? = h?.course?.takeIf { (h.speedKmh ?: 0) > 0 }

/** A station as the screens show it: what the phone decoded, completed with what the tracker's heard list says of it. */
data class StationView(
    val call: String, val table: Char?, val code: Char?, val lat: Double?, val lon: Double?, val lastMs: Long, val positionMs: Long?,
    val comment: String, val path: String, val packets: Int, val rssi: Int?, val snr: Double?, val courseDeg: Int?, val speedKmh: Double?,
    val altitudeM: Double?, val device: String?, val status: String?,
    val wx: AprsWx? = null, val wxMs: Long? = null, val owner: String? = null, val micE: String? = null,
    val wxPressD3h: Double? = null,     // change of the pressure over 3 h, once the station has been heard for an hour
    val motionDerived: Boolean = false, // course and speed worked out from two positions, not sent by the station
    val telemetry: es.ea7jrs.aprslorap.core.TelemetryReading? = null, val telemetryDefs: es.ea7jrs.aprslorap.core.TelemetryDefs? = null,
    val statusMs: Long? = null,
    val lastFrame: es.ea7jrs.aprslorap.core.Ax25Frame? = null,          // the last frame the app decoded from it (packet card)
    val trail: List<es.ea7jrs.aprslorap.core.TrackPoint> = emptyList(), // where it has been, oldest first (map)
) {
    val symbol: AprsSymbols.Symbol? get() = AprsSymbols.of(table, code)
    val kind: String get() = AprsSymbols.describe(table, code)
    val isWx: Boolean get() = code == '_' || wx != null
    val isInfra: Boolean get() = code == '#' || code == '&' || code == 'r'
    val moving: Boolean get() = (speedKmh ?: 0.0) > 2.0
    val emergency: Boolean get() = (table == '\\' && code == '!') || micE == Aprs.MICE_EMERGENCY
}

/** Pressure readings of the weather stations heard only by the tracker (the app decodes the others itself: Station.wxPressD3h). */
private object HeardTrends {
    private val byCall = HashMap<String, es.ea7jrs.aprslorap.core.WxCalc.PressureTrend>()

    @Synchronized fun d3h(call: String, atMs: Long, hpa: Double?): Double? {
        if (hpa == null) return null
        if (byCall.size > 64 && !byCall.containsKey(call)) byCall.clear()
        val t = byCall.getOrPut(call) { es.ea7jrs.aprslorap.core.WxCalc.PressureTrend() }
        t.add(atMs / 1000, hpa)
        return t.delta3h(System.currentTimeMillis() / 1000)
    }
}

fun stationViews(stations: List<Station>, heard: List<HeardStation>, heardAtMs: Long): List<StationView> {
    val byCall = heard.associateBy { it.call }
    val out = ArrayList<StationView>()
    for (s in stations) {
        val h = byCall[s.call]
        val wxStation = s.wx != null || s.symbolCode == '_'
        out.add(StationView(
            s.call, s.symbolTable, s.symbolCode, s.lat ?: h?.lat, s.lon ?: h?.lon, s.lastHeardMs, s.lastPositionMs,
            AprsExtra.cleanComment(s.comment).ifEmpty { if (wxStation) "" else h?.comment ?: "" }, s.lastPath.ifEmpty { h?.path ?: "" }, s.packets,
            h?.rssi, h?.snr,
            s.courseDeg ?: heardCourse(h).takeIf { !wxStation && s.speedKmh == null }, s.speedKmh ?: heardSpeed(h).takeIf { !wxStation },
            s.altitudeM ?: h?.altitudeM?.toDouble(), h?.device, s.status ?: h?.status,
            wx = s.wx, wxMs = s.wxMs, owner = s.owner, micE = s.micE, wxPressD3h = s.wxPressD3h, motionDerived = s.motionDerived,
            lastFrame = s.lastFrame, trail = s.trail, telemetry = s.telemetry, telemetryDefs = s.telemetryDefs, statusMs = s.statusMs,
        ))
    }
    val known = stations.map { it.call }.toSet()
    for (h in heard) if (h.call !in known) {
        val sym = AprsSymbols.of(h.symbol)
        // a weather station heard only by the tracker: its comment carries the weather (and "course / speed" would be the wind)
        val w = if (h.symbol.getOrNull(1) == '_') AprsWeather.parsePositioned(h.comment ?: "") else null
        val at = heardAtMs - h.ageS * 1000
        out.add(StationView(
            h.call, h.symbol.getOrNull(0), h.symbol.getOrNull(1), h.lat, h.lon, at, h.posAgeS?.let { heardAtMs - it * 1000 },
            w?.rest ?: h.comment ?: "", h.path ?: "", h.packets, h.rssi, h.snr,
            if (w != null || h.wx != null) null else heardCourse(h), if (w != null || h.wx != null) null else heardSpeed(h), h.altitudeM?.toDouble(), h.device, h.status,
            wx = w?.wx, wxMs = if (w != null) at else null, wxPressD3h = HeardTrends.d3h(h.call, at, w?.wx?.pressureHpa),
        ).let { if (sym == null) it.copy(table = null, code = null) else it })
    }
    return out
}

@Composable
fun rememberStationViews(): List<StationView> {
    val stations by AppState.stations.collectAsState()
    val heard by AppState.heard.collectAsState()
    val heardAt by AppState.heardAtMs.collectAsState()
    return remember(stations, heard, heardAt) { stationViews(stations, heard, heardAt) }
}

/** Route to a position in Google Maps (or any maps app). */
fun goToPos(ctx: Context, lat: Double, lon: Double, label: String) {
    val maps = Intent(Intent.ACTION_VIEW, Uri.parse(Nav.googleNavigationUri(lat, lon))).setPackage(Notifier.GOOGLE_MAPS)
    try { ctx.startActivity(maps) } catch (e: ActivityNotFoundException) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Nav.geoUri(lat, lon, label)))) } catch (e2: ActivityNotFoundException) {
            AppState.error.value = "No maps app found"
        }
    }
}

/** Points the APRS LoRa+ Rotor box at a station (azimuth from our position; elevation 0). */
fun aimRotorAt(lat: Double?, lon: Double?, name: String) {
    val d = Own.to(lat, lon) ?: run { AppState.error.value = "No position for $name or for us (tracker GNSS or locator)"; return }
    if (RotorLink.command(RotorProto.pos(d.second, 0.0))) AppState.notice.value = String.format(Locale.US, "Rotor box → %03.0f° (%s, %s)", d.second, name, Fmt.km(d.first))
}

private val FILTERS = listOf("ALL", "WITH POS", "MOVING", "WX", "DIGI · iGATE", "EMERGENCY")

@Composable
fun StationListScreen(nav: Navigator) {
    val all = rememberStationViews()
    var sort by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(0) }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    KeepHeardFresh(30_000)
    val dist = remember(all) { all.associate { it.call to Own.to(it.lat, it.lon) } }
    val list = all.filter {
        when (filter) { 1 -> it.lat != null; 2 -> it.moving; 3 -> it.isWx; 4 -> it.isInfra; 5 -> it.emergency; else -> true }
    }.let { l ->
        when (sort) {
            1 -> l.sortedBy { it.call }
            2 -> l.sortedBy { dist[it.call]?.first ?: Double.MAX_VALUE }
            else -> l.sortedByDescending { it.lastMs }
        }
    }
    val selected = list.firstOrNull { it.call == sel }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("APRS STATION LIST", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            Text("${list.size}", fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Muted, modifier = Modifier.padding(end = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("TIME", "CALL", "DIST").forEachIndexed { i, n -> Chip(n, i == sort) { sort = i } }
            Spacer(Modifier.weight(1f))
            Chip("FILTER: " + FILTERS[filter], filter != 0, color = Alp.Green) { filter = (filter + 1) % FILTERS.size }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (list.isEmpty()) Text(if (all.isEmpty()) "No stations heard yet. Start the link in Connect." else "No station passes the filter.",
                color = Alp.Muted, modifier = Modifier.padding(16.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(4.dp)) {
                itemsIndexed(list, key = { _, s -> s.call }) { i, s ->
                    val on = s.call == sel
                    val d = dist[s.call]
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Alp.Selected else Color.Transparent)
                            .border(2.dp, if (on) Alp.Amber else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { if (on) nav.go("STATION:" + s.call) else sel = s.call }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${i + 1}", fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.width(22.dp), textAlign = TextAlign.End)
                        AprsSymbolIcon(s.symbol, 24.dp, if (s.emergency) Color(0xFF4A2326) else Alp.SymbolBg)
                        Column(Modifier.weight(1f)) {
                            Text(s.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = if (s.emergency) Alp.RedSoft else Alp.Text, maxLines = 1)
                            if (s.wx != null) Text(s.wx.summary(Fmt.units), fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else Text(s.kind + (s.micE?.takeIf { it != "Off duty" }?.let { " · $it" } ?: "") + (s.rssi?.let { " · " + SMeter.fromDbm(it.toDouble(), trackerFreqHz()).label } ?: ""),
                                fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(Fmt.hhmm(s.lastMs), fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Muted)
                        Text(Fmt.km(d?.first), fontFamily = BarlowCondensed, fontSize = 16.sp, color = Alp.Text, modifier = Modifier.width(66.dp), textAlign = TextAlign.End)
                        BearingArrow(d?.second)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey("TOP", Modifier.weight(1f).height(54.dp)) { scope.launch { listState.animateScrollToItem(0) }; sel = list.firstOrNull()?.call }
            RadioKey("DETAIL", Modifier.weight(1f).height(54.dp), enabled = selected != null, border = Alp.Amber, color = Alp.Amber) { selected?.let { nav.go("STATION:" + it.call) } }
            RadioKey("MSG", Modifier.weight(1f).height(54.dp), enabled = selected != null) { selected?.let { nav.go("THREAD:" + it.call) } }
            RadioKey("MAP", Modifier.weight(1f).height(54.dp)) { MapFocus.call = selected?.call; nav.go("MAP") }
        }
    }
}

@Composable
fun StationDetailScreen(nav: Navigator, call: String) {
    val ctx = LocalContext.current
    val all = rememberStationViews()
    val gnss by AppState.gnss.collectAsState()
    val rotorPos by AppState.rotorPos.collectAsState()
    var hdgUp by rememberSaveable { mutableStateOf(false) }
    val order = all.sortedByDescending { it.lastMs }
    val idx = order.indexOfFirst { it.call == call }
    val s = order.getOrNull(idx)
    DispState.station = call
    KeepHeardFresh(30_000)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader(if (idx >= 0) "${idx + 1} / ${order.size}" else call, onBack = { if (!nav.back()) nav.go("STATIONS") }) {
            if (idx > 0) Chip("‹", false, height = 32.dp) { nav.stack[nav.stack.size - 1] = "STATION:" + order[idx - 1].call }
            Spacer(Modifier.width(6.dp))
            if (idx in 0 until order.size - 1) Chip("›", false, height = 32.dp) { nav.stack[nav.stack.size - 1] = "STATION:" + order[idx + 1].call }
        }
        if (s == null) {
            Text("$call is not in the list any more.", color = Alp.Muted, modifier = Modifier.padding(16.dp))
            Spacer(Modifier.weight(1f))
        } else {
            val d = Own.to(s.lat, s.lon)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AprsSymbolIcon(s.symbol, 40.dp, if (s.emergency) Color(0xFF4A2326) else Alp.SymbolBg)
                        Column(Modifier.weight(1f)) {
                            Text(s.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = if (s.emergency) Alp.RedSoft else Alp.Text)
                            Text(s.kind + " · heard " + Fmt.hhmm(s.lastMs, utc = true) + "Z · " + Fmt.ago(s.lastMs), fontSize = 14.sp, color = Alp.Muted)
                        }
                        val favs = remember { Settings(ctx) }
                        var fav by remember(s.call) { mutableStateOf(s.call.uppercase() in favs.favStations) }
                        Text(if (fav) "★" else "☆", fontSize = 30.sp, color = if (fav) Alp.Amber else Alp.Muted,
                            modifier = Modifier.clickable {
                                fav = !fav
                                favs.favStations = if (fav) favs.favStations + s.call.uppercase() else favs.favStations - s.call.uppercase()
                            }.padding(horizontal = 4.dp))
                        if (s.moving) Text("MOVING", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Alp.Cyan,
                            modifier = Modifier.border(1.dp, Alp.Cyan, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp))
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val heading = gnss?.courseDeg?.takeIf { (gnss?.speedKmh ?: 0.0) > 3.0 }
                            CompassRose(Modifier.size(164.dp), headingUp = if (hdgUp) heading ?: 0.0 else 0.0, targetDeg = d?.second, rotorDeg = rotorPos?.azDeg, ownCourse = heading)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Chip("N-UP", !hdgUp, height = 30.dp) { hdgUp = false }
                                Chip("HDG-UP", hdgUp, height = 30.dp) { hdgUp = true }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionLabel("DISTANCE")
                            Text(Fmt.km(d?.first), fontFamily = TechMono, fontSize = 26.sp, color = Alp.Text)
                            SectionLabel("BEARING")
                            Text(if (d == null) "—" else Fmt.deg(d.second) + " " + Geo.cardinal16(d.second), fontFamily = TechMono, fontSize = 26.sp, color = Alp.Amber)
                            rotorPos?.let { r ->
                                val on = d != null && Math.abs(((r.azDeg - d.second + 540) % 360) - 180) < 5
                                Text(String.format(Locale.US, "Rotor box %03.0f°", r.azDeg) + if (on) " · on target" else "", fontSize = 13.sp, color = if (on) Alp.Green else Alp.Muted)
                            }
                            if (d == null) Text("Our position: tracker GNSS or the locator in Spotting.", fontSize = 12.sp, color = Alp.Muted)
                        }
                    }
                }
                if (s.wx != null) WeatherPanel(s.wx, s.wxMs, s.wxPressD3h)
                Panel(Modifier.fillMaxWidth(), padding = 10.dp) {
                    if (s.rssi != null) {
                        SectionLabel("SIGNAL AT THE TRACKER")
                        SMeterBar(SMeter.fromDbm(s.rssi.toDouble(), trackerFreqHz()),
                            extra = listOfNotNull("${s.rssi} dBm", s.snr?.let { String.format(Locale.US, "SNR %.1f", it) }).joinToString(" · "),
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    // a course only means something while it moves; "~" = worked out from its last two positions
                    // not for a weather station: in a weather report APRS uses the course / speed field for the wind (WEATHER card)
                    val est = if (s.motionDerived) "~ " else ""
                    if (!s.isWx) {
                        InfoRow("SPEED", if (s.speedKmh == null) "—" else est + Fmt.speed(s.speedKmh))
                        InfoRow("COURSE", s.courseDeg?.takeIf { (s.speedKmh ?: 0.0) >= 1.0 }?.let { est + Fmt.deg(it.toDouble()) } ?: "—")
                    }
                    es.ea7jrs.aprslorap.core.AprsConventions.ssidMeaning(s.call).takeIf { it.isNotEmpty() && s.owner == null }?.let { InfoRow("SSID", it) }
                    InfoRow("ALTITUDE", s.altitudeM?.let { String.format(Locale.US, "%.0f m", it) } ?: "—")
                    InfoRow("LAT", s.lat?.let { Fmt.latLon(it, true) } ?: "—")
                    InfoRow("LON", s.lon?.let { Fmt.latLon(it, false) } ?: "—")
                    InfoRow("LOCATOR", if (s.lat != null && s.lon != null) Spots.locator(s.lat, s.lon, 6) else "—")
                    InfoRow("POSITION", Fmt.ago(s.positionMs))
                    InfoRow("PACKETS", "${s.packets}" + (s.device?.let { " · $it" } ?: ""))
                    if (s.path.isNotEmpty()) InfoRow("PATH", s.path)
                    if (!s.status.isNullOrBlank()) InfoRow("STATUS", s.status + (s.statusMs?.let { " · " + Fmt.ago(it) } ?: ""))
                    s.micE?.let { InfoRow("MIC-E", it, if (it == Aprs.MICE_EMERGENCY) Alp.RedSoft else Alp.Text) }
                    s.owner?.let { InfoRow("OBJECT OF", it) }
                    if (s.comment.isNotBlank()) Text("“${s.comment}”", fontSize = 15.sp, color = Alp.SubText, modifier = Modifier.padding(top = 6.dp))
                }
                s.telemetry?.let { TelemetryPanel(it, s.telemetryDefs) }
                s.lastFrame?.let { PacketCard(it, s) }
            }
            Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("STATIONS") }
                RadioKey("MSG", Modifier.weight(1f).height(54.dp)) { nav.go("THREAD:" + s.call) }
                RadioKey("MAP", Modifier.weight(1f).height(54.dp), enabled = s.lat != null) { MapFocus.call = s.call; nav.go("MAP") }
                RadioKey("GO TO", Modifier.weight(1f).height(54.dp), sub = "Maps", enabled = s.lat != null, border = Alp.GreenDim, color = Alp.Green) {
                    goToPos(ctx, s.lat!!, s.lon!!, s.call)
                }
                RadioKey("ROTOR", Modifier.weight(1f).height(54.dp), sub = "aim", enabled = s.lat != null, border = Alp.AmberDim, color = Alp.Amber) {
                    aimRotorAt(s.lat, s.lon, s.call)
                }
            }
        }
    }
}

/** The last telemetry report of a station: analog channels with their names and units, bits as lamps. */
@Composable
fun TelemetryPanel(r: es.ea7jrs.aprslorap.core.TelemetryReading, defs: es.ea7jrs.aprslorap.core.TelemetryDefs?) {
    val values = remember(r, defs) { es.ea7jrs.aprslorap.core.Telemetry.values(defs, r) }
    Panel(Modifier.fillMaxWidth(), padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("TELEMETRY" + (defs?.title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
            Spacer(Modifier.weight(1f))
            Text("#${r.seq} · " + Fmt.ago(r.timeMs), fontSize = 13.sp, color = Alp.Muted)
        }
        for (v in values.filter { it.on == null }) InfoRow(v.name.uppercase(), v.value + if (v.unit.isNotEmpty()) " " + v.unit else "")
        val bits = values.filter { it.on != null }
        if (bits.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in bits) {
                val c = if (b.on == true) Alp.Green else Alp.Muted
                Text(b.name + if (b.unit.isNotEmpty() && b.on == true) " " + b.unit else "", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c,
                    modifier = Modifier.border(1.dp, c, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        if (defs == null) Text("The station has not said yet what its channels are (PARM / UNIT / EQNS): raw values.", fontSize = 12.sp, color = Alp.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The last frame of the station explained: the line as it came with its parts in colour, every field in words and the way it
 * travelled to our tracker (core PacketExplain). Only for stations the app has decoded a frame from.
 */
@Composable
fun PacketCard(frame: es.ea7jrs.aprslorap.core.Ax25Frame, s: StationView) {
    val pe = es.ea7jrs.aprslorap.core.PacketExplain
    val inner = remember(frame) { Aprs.unwrapThirdParty(frame) }
    val comment = remember(frame) { (Aprs.parse(inner.infoText, inner.destination.call) as? es.ea7jrs.aprslorap.core.AprsPacket.Position)?.comment }
    val parts = remember(frame) { pe.parts(frame, comment) }
    val fields = remember(frame, s.courseDeg, s.speedKmh) {
        pe.fields(frame, s.courseDeg.takeIf { s.motionDerived }, s.speedKmh.takeIf { s.motionDerived })
    }
    val hops = remember(frame) { pe.hops(frame) }
    Panel(Modifier.fillMaxWidth(), padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("LAST PACKET")
            Spacer(Modifier.weight(1f))
            Text(Fmt.hhmm(s.lastMs, utc = true) + "Z · " + Fmt.ago(s.lastMs), fontSize = 13.sp, color = Alp.Muted)
        }
        Text(androidx.compose.ui.text.buildAnnotatedString {
            for (p in parts) {
                val c = when (p.kind) {
                    es.ea7jrs.aprslorap.core.PacketExplain.Kind.SOURCE -> Alp.Green
                    es.ea7jrs.aprslorap.core.PacketExplain.Kind.DEST -> Alp.Cyan
                    es.ea7jrs.aprslorap.core.PacketExplain.Kind.PATH -> Alp.Amber
                    es.ea7jrs.aprslorap.core.PacketExplain.Kind.DATA -> Alp.Text
                    es.ea7jrs.aprslorap.core.PacketExplain.Kind.COMMENT -> Alp.SubText
                    es.ea7jrs.aprslorap.core.PacketExplain.Kind.SEP -> Alp.Muted
                }
                pushStyle(androidx.compose.ui.text.SpanStyle(color = c)); append(p.text); pop()
            }
        }, fontFamily = TechMono, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(Alp.SymbolBg, RoundedCornerShape(8.dp)).padding(8.dp))
        SectionLabel("DECODED")
        for (f in fields) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(f.name, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.width(118.dp))
            Column(Modifier.weight(1f)) {
                Text(f.value, fontFamily = TechMono, fontSize = 14.sp, color = Alp.Text)
                if (f.note.isNotEmpty()) Text(f.note, fontSize = 12.sp, color = Alp.Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        SectionLabel("HOW IT GOT HERE")
        for ((i, h) in hops.withIndex()) {
            val (tag, col) = when (h.role) {
                es.ea7jrs.aprslorap.core.PacketExplain.Role.TX -> "TX" to Alp.Green
                es.ea7jrs.aprslorap.core.PacketExplain.Role.DIGI -> "DIGI" to Alp.Amber
                es.ea7jrs.aprslorap.core.PacketExplain.Role.IGATE -> "IGATE" to Alp.Cyan
                es.ea7jrs.aprslorap.core.PacketExplain.Role.IS -> "IS" to Alp.Muted
            }
            HopRow(i + 1, tag, col, h.who, h.note)
        }
        HopRow(hops.size + 1, "RX", Alp.Green, "Your tracker",
            listOfNotNull(s.rssi?.let { "$it dBm" }, s.snr?.let { String.format(Locale.US, "SNR %.1f", it) }).joinToString(" · ").ifEmpty { "received" })
        Text(pe.pathSummary(frame), fontSize = 12.sp, color = Alp.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HopRow(n: Int, tag: String, color: Color, who: String, note: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$n", fontFamily = TechMono, fontSize = 13.sp, color = Alp.Muted, modifier = Modifier.width(16.dp))
        Text(tag, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = color,
            textAlign = TextAlign.Center, modifier = Modifier.width(46.dp).border(1.dp, color, RoundedCornerShape(5.dp)).padding(vertical = 1.dp))
        Text(who, fontFamily = TechMono, fontSize = 14.sp, color = Alp.Text)
        Text(note, fontSize = 12.sp, color = Alp.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Colour of a temperature: amber from 24 °C, red from 30 °C, light blue at 5 °C or less. */
fun tempColor(c: Double?): Color = when {
    c == null -> Alp.Text
    c >= 30.0 -> Alp.RedSoft
    c >= 24.0 -> Alp.Amber
    c <= 5.0 -> Color(0xFF7FD3F0)
    else -> Alp.Text
}

/** Weather report of a station: wind with its direction, temperature, humidity, pressure, rain, light and snow. */
@Composable
fun WeatherPanel(wx: AprsWx, atMs: Long?, pressD3h: Double? = null) {
    val calc = es.ea7jrs.aprslorap.core.WxCalc
    val t = wx.tempC
    val hum = wx.humidityPct?.toDouble()
    val dew = if (t != null && hum != null) calc.dewPointC(t, hum) else null
    // feels-like only when it differs from the air temperature (wind chill below it, heat index above it)
    val feels = t?.let { calc.feelsLikeC(it, hum, wx.windKmh) }?.takeIf { kotlin.math.abs(it - t) >= 0.5 }
        ?.let { it to (if (it < t) "wind chill" else "heat index") }
    Panel(Modifier.fillMaxWidth(), border = Alp.Cyan.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("WEATHER")
            Spacer(Modifier.weight(1f))
            Text(Fmt.ago(atMs), fontSize = 13.sp, color = Alp.Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Text(Fmt.temp(wx.tempC, unit = false), fontFamily = TechMono, fontSize = 40.sp, color = tempColor(wx.tempC))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // the arrow points where the wind blows to (it comes from windDirDeg)
                    val dir = wx.windDirDeg
                    if (dir != null && (wx.windKmh ?: 1.0) >= 1.0) BearingArrow((dir + 180) % 360.0, Alp.Cyan, 22.dp)
                    Text(wx.wind(Fmt.units) ?: "no wind data", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Alp.Cyan)
                }
                wx.gustKmh?.let { Text("gusts " + Fmt.speed(it) + (wx.windDirDeg?.let { d -> " · from " + Fmt.deg(d.toDouble()) } ?: ""), fontSize = 14.sp, color = Alp.Muted) }
            }
        }
        wx.windKmh?.takeIf { it >= 1.0 }?.let {
            val f = calc.beaufort(calc.kmhToKn(it))
            InfoRow("BEAUFORT", "Force $f · ${calc.beaufortName(f)} · ${Fmt.speed(it)}", if (f >= 8) Alp.Red else if (f >= 6) Alp.Amber else Alp.Text)
        }
        feels?.let { InfoRow("FEELS LIKE", Fmt.temp(it.first) + " (" + it.second + ")", tempColor(it.first)) }
        wx.humidityPct?.let { InfoRow("HUMIDITY", "$it %") }
        dew?.let { d -> InfoRow("DEW POINT", Fmt.temp(d) + (calc.cloudBaseM(wx.tempC, d)?.let { String.format(Locale.US, " · cloud base ~%.0f m", it) } ?: "")) }
        wx.pressureHpa?.let {
            InfoRow("PRESSURE", String.format(Locale.US, "%.1f hPa", it) + (pressD3h?.let { d -> String.format(Locale.US, " · %s (%+.1f in 3 h)", calc.trendName(d), d) } ?: ""),
                if ((pressD3h ?: 0.0) <= -3.5) Alp.Amber else Alp.Text)
        }
        wx.rain1hMm?.let { InfoRow("RAIN 1 H", String.format(Locale.US, "%.1f mm", it)) }
        wx.rain24hMm?.let { InfoRow("RAIN 24 H", String.format(Locale.US, "%.1f mm", it)) }
        wx.rainMidnightMm?.let { InfoRow("RAIN TODAY", String.format(Locale.US, "%.1f mm", it)) }
        wx.luminosityWm2?.let { InfoRow("SUNLIGHT", "$it W/m²") }
        wx.snowCm?.let { InfoRow("SNOW 24 H", String.format(Locale.US, "%.1f cm", it)) }
    }
}

/** Station the map should center on when it opens (set by S.LIST / detail). */
object MapFocus {
    @Volatile var call: String? = null
}
