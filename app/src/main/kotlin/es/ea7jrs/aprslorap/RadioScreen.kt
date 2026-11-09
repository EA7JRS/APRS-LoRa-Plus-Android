/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import es.ea7jrs.aprslorap.core.AprsSymbols
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.HeardStation
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.SMeter
import es.ea7jrs.aprslorap.core.Spots
import es.ea7jrs.aprslorap.core.TrackerMode
import kotlinx.coroutines.delay
import java.util.Locale

/** Asks the tracker for its heard list every [periodMs] while the calling screen is shown (RSSI / SNR of the last stations). */
@Composable
fun KeepHeardFresh(periodMs: Long = 15_000) {
    val control by AppState.controlReady.collectAsState()
    LaunchedEffect(control) {
        var n = 0
        while (control) {
            if (n++ % 2 == 0) AppState.controller?.control("status")      // the dial follows the mode / profile in use
            AppState.controller?.control("heard", "cnt" to 12)
            delay(periodMs / 2)
        }
    }
}

/** Frequency of the tracker's LoRa profile in use (for the S-meter scale); 433.775 MHz if not known yet. */
fun trackerFreqHz(): Long = AppState.trackerInfo.value?.loras?.getOrNull(AppState.trackerStatus.value?.lora ?: -1)?.freqHz ?: 433_775_000L

/** The last station the tracker heard with an RSSI (the "signal" of band A). */
fun lastHeardWithSignal(list: List<HeardStation>): HeardStation? = list.filter { it.rssi != null }.minByOrNull { it.ageS }

/** Band A: the tracker (LoRa / FSK APRS). */
@Composable
private fun BandA(nav: Navigator, big: Boolean) {
    val info by AppState.trackerInfo.collectAsState()
    val status by AppState.trackerStatus.collectAsState()
    val heard by AppState.heard.collectAsState()
    val ble by AppState.bleStatus.collectAsState()
    val running by AppState.running.collectAsState()
    val heardAt by AppState.heardAtMs.collectAsState()
    val cwOn by AppState.cwRxOn.collectAsState()
    val cwDbm by AppState.cwRxDbm.collectAsState()
    val lora = info?.loras?.getOrNull(status?.lora ?: -1)
    val mode = status?.mode ?: TrackerMode.APRS_LORA
    val modeName = info?.modes?.firstOrNull { it.mode == mode }?.name ?: TrackerMode.name(mode)
    val sa868 = info?.radio == "sa868"
    // modulation and frequency of the mode in use: from the status event; older firmware: the LoRa profile only in the APRS modes
    val mod = status?.modulation ?: if (mode == TrackerMode.APRS_LORA && lora?.fsk == true) "FSK" else TrackerMode.modulation(mode, sa868)
    val aprsLike = mode == TrackerMode.APRS_LORA || mode == TrackerMode.APRS_FSK
    val hz: Long? = status?.freqHz ?: if (aprsLike) lora?.freqHz else null
    val last = lastHeardWithSignal(heard)
    val detail = when (mod) {
        "LoRa" -> lora?.let { "SF${it.sf} · CR4/${it.cr}" } ?: "LoRa"
        "FSK" -> "G3RUH 9600"
        "AFSK" -> "AFSK 1200"
        else -> modeName
    }
    // signal: APRS modes = last station the tracker heard; MORSE = the tracker's CW listening
    val reading = when {
        mode == TrackerMode.MORSE -> cwDbm?.takeIf { cwOn }?.let { SMeter.fromDbm(it, hz ?: trackerFreqHz()) }
        TrackerMode.isAprs(mode) -> last?.rssi?.let { SMeter.fromDbm(it.toDouble(), hz ?: trackerFreqHz()) }
        else -> null
    }
    val readingText = when {
        mode == TrackerMode.MORSE -> if (cwOn) cwDbm?.let { String.format(Locale.US, "%.0f dBm · CW listening", it) } else "CW listening off"
        TrackerMode.isAprs(mode) -> last?.let { listOfNotNull(it.rssi?.let { r -> "$r dBm" }, it.snr?.let { s -> String.format(Locale.US, "SNR %.1f", s) }).joinToString(" · ") }
        else -> null
    }
    val modColor = when (mod) { "CW" -> Alp.Amber; "POCSAG", "SAT", "AIS" -> Alp.Green; else -> Alp.Cyan }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).clickable { nav.tool(if (running) Tool.TRACKER else Tool.CONNECT) }) {
        Box(Modifier.padding(top = 14.dp).width(5.dp).height(if (big) 140.dp else 40.dp).clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Alp.Green))
        Column(Modifier.padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Alp.Bg,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Alp.Green).padding(horizontal = 7.dp))
                Text("TRACKER", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Alp.Muted)
                Spacer(Modifier.weight(1f))
                Text(if (status == null) "—" else "$mod · ${modeName.uppercase(Locale.US)}", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = modColor, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Alp.Bg).padding(horizontal = 6.dp))
            }
            if (status == null) {
                Text(if (running) ble else "Tracker not connected · touch to connect", fontSize = 15.sp, color = Alp.Muted)
                if (!big) return@Column
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (hz != null) {
                        Text(Fmt.mhz(hz), fontFamily = TechMono, fontSize = if (big) 46.sp else 36.sp, color = Alp.Text, lineHeight = 48.sp)
                        Text(Fmt.subKhz(hz), fontFamily = TechMono, fontSize = if (big) 22.sp else 18.sp, color = Alp.Muted, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                    } else {
                        Text("---.---", fontFamily = TechMono, fontSize = if (big) 46.sp else 36.sp, color = Alp.Off, lineHeight = 48.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (big) Column(horizontalAlignment = Alignment.End) {
                        Text(detail, fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.SubText)
                        Text(listOfNotNull(if (TrackerMode.isAprs(mode)) info?.profiles?.getOrNull(status?.profile ?: -1)?.label else null,
                            if (status?.digi == true && TrackerMode.isAprs(mode)) "DIGI" else null).joinToString(" · "),
                            fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                    }
                }
                if (hz == null && big) Text("This tracker firmware does not report the frequency of this mode: update it (Firmware).", fontSize = 12.sp, color = Alp.Muted)
            }
            if (!big) return@Column
            SMeterBar(reading, extra = readingText)
            if (last != null && TrackerMode.isAprs(mode)) {
                val pos = Own.to(last.lat, last.lon)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable { nav.go("STATION:" + last.call) }) {
                    Text("Last RX", fontSize = 13.sp, color = Alp.Muted)
                    AprsSymbolIcon(AprsSymbols.of(last.symbol), 20.dp, Alp.SymbolBg)
                    Text(last.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Alp.Text)
                    Spacer(Modifier.weight(1f))
                    Text(listOfNotNull(pos?.let { Fmt.km(it.first) + " " + Fmt.deg(it.second) }, ageS(last.ageS + (System.currentTimeMillis() - heardAt) / 1000)).joinToString(" · "),
                        fontSize = 13.sp, color = Alp.SubText)
                }
            }
        }
    }
}

private fun ageS(s: Long): String = when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60} min"; else -> "${s / 3600} h" }

/** Band B: the radio on the rotor box (CAT) or, without it, the SDR receiver under control. */
@Composable
private fun BandB(nav: Navigator, big: Boolean) {
    val cat by AppState.rotorCat.collectAsState()
    val sdr by AppState.sdr.collectAsState()
    val c = cat?.takeIf { it.link && it.freqHz > 0 }
    val title: String; val hz: Long?; val mode: String; val reading: SMeter.Reading?; val extra: String?; val target: () -> Unit
    when {
        c != null -> {
            title = "CAT · " + c.rig.ifEmpty { "radio" }; hz = c.freqHz; mode = RotorProto.modeName(c.mode)
            reading = SMeter.fromCat(c.smeter)
            extra = listOfNotNull("VFO ${c.vfo}", if (c.split) "SPLIT" else null, c.toneHz?.let { String.format(Locale.US, "T %.1f", it) }, if (c.ptt) "TX" else null).joinToString(" · ")
            target = { nav.tool(Tool.ROTOR) }
        }
        sdr.connected -> {
            title = "SDR · " + sdr.device.ifEmpty { "receiver" }; hz = sdr.freqHz; mode = sdr.mode ?: "—"
            reading = sdr.signalDb?.let { SMeter.fromDbm(it, sdr.freqHz ?: 0L) }
            extra = listOfNotNull(sdr.signalDb?.let { String.format(Locale.US, "%.0f dBm", it) }, sdr.snrDb?.let { String.format(Locale.US, "SNR %.0f", it) }).joinToString(" · ")
            target = { nav.tool(Tool.SDR) }
        }
        else -> { title = "NO RADIO"; hz = null; mode = ""; reading = null; extra = null; target = { nav.tool(Tool.SDR) } }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.SubPanel).clickable(onClick = target)) {
        Box(Modifier.padding(top = 12.dp).width(5.dp).height(if (big) 90.dp else 36.dp).clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Color(0xFF3A4A5B)))
        Column(Modifier.padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("B", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Alp.Bg,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Alp.SubMuted).padding(horizontal = 7.dp))
                Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Alp.SubMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (mode.isNotEmpty()) Text(mode, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Alp.SubText,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Alp.Bg).padding(horizontal = 6.dp))
            }
            if (hz == null) {
                Text("CAT through the rotor box, or an SDR receiver · touch to set up", fontSize = 14.sp, color = Alp.SubMuted)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.mhz(hz), fontFamily = TechMono, fontSize = if (big) 34.sp else 28.sp, color = Alp.SubText)
                    Text(Fmt.subKhz(hz), fontFamily = TechMono, fontSize = 16.sp, color = Alp.SubMuted, modifier = Modifier.padding(bottom = 4.dp, start = 2.dp))
                }
                if (big) SMeterBar(reading, extra = extra)
            }
        }
    }
}

/** The two bands, compact (for the [F] menu). */
@Composable
fun BandSummary(nav: Navigator) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BandA(nav, big = false)
        BandB(nav, big = false)
    }
}

@Composable
fun RadioScreen(nav: Navigator, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val stations by AppState.stations.collectAsState()
    val status by AppState.trackerStatus.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val igate by AppState.igateStatus.collectAsState()
    val rotorPos by AppState.rotorPos.collectAsState()
    val rotor by AppState.rotor.collectAsState()
    KeepHeardFresh()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow(nav, openDrawer)
        BandA(nav, big = true)
        BandB(nav, big = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val profile = info?.profiles?.getOrNull(status?.profile ?: -1)?.label
            StateTile("BEACON", if (status?.smartBeacon == true) "SMART" else profile ?: "—", if (status?.smartBeacon == true) Alp.Amber else Alp.Text, Modifier.weight(1f)) { nav.tool(Tool.TRACKER) }
            StateTile("iGATE", if (settings.igateEnabled) igate.take(10) else "OFF", if (settings.igateEnabled && igate.startsWith("Conn", true)) Alp.Green else Alp.Text, Modifier.weight(1f)) { nav.tool(Tool.CONNECT) }
            val az = rotorPos?.azDeg ?: rotor?.azDeg
            val el = rotorPos?.elDeg ?: rotor?.elDeg
            StateTile("ROTOR", if (az == null) "—" else String.format(Locale.US, "%03.0f°/%.0f°", az, el ?: 0.0), Alp.Text, Modifier.weight(1f)) { nav.tool(Tool.ROTOR) }
        }
        Panel(Modifier.fillMaxWidth(), padding = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)) {
                SectionLabel("LAST HEARD", Modifier.weight(1f))
                Text("${stations.size} stations ›", fontSize = 13.sp, color = Alp.Cyan, modifier = Modifier.clickable { nav.go("STATIONS") }.padding(4.dp))
            }
            if (stations.isEmpty()) Text("Nothing heard yet.", fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.padding(6.dp))
            for (s in stations.sortedByDescending { it.lastHeardMs }.take(4)) {
                val d = Own.to(s.lat, s.lon)
                Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).clickable { nav.go("STATION:" + s.call) }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AprsSymbolIcon(AprsSymbols.of(s.symbolTable, s.symbolCode), 22.dp, Alp.SymbolBg)
                    Text(s.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Alp.Text, modifier = Modifier.weight(1f))
                    Text(Fmt.hhmm(s.lastHeardMs), fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                    Text(Fmt.km(d?.first), fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Text, modifier = Modifier.width(64.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    BearingArrow(d?.second)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StateTile(label: String, value: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Alp.Panel).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        SectionLabel(label)
        Text(value, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ [DISP]: compass, GPS, altitude, clock

object DispState {
    val PAGES = listOf("COMPASS", "GPS", "ALTITUDE", "CLOCK")
    var page by mutableIntStateOf(-1)
    var station: String? = null
}

@Composable
fun DispScreen(nav: Navigator, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    if (DispState.page < 0) DispState.page = settings.uiDispPage.coerceIn(0, 3)
    val page = DispState.page
    fun setPage(p: Int) { DispState.page = p; settings.uiDispPage = p }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow(nav, openDrawer)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DispState.PAGES.forEachIndexed { i, n -> Chip(n, i == page, Modifier.weight(1f), height = 32.dp) { setPage(i) } }
        }
        when (page) {
            0 -> CompassPage(nav)
            1 -> GpsStatusPage()
            2 -> AltitudePage()
            else -> ClockPage()
        }
        RadioKey("DISP ›  ${DispState.PAGES[(page + 1) % 4]}", Modifier.fillMaxWidth().height(46.dp), border = Alp.Cyan, color = Alp.Cyan) { setPage((page + 1) % 4) }
    }
}

@Composable
private fun CompassPage(nav: Navigator) {
    val gnss by AppState.gnss.collectAsState()
    val sensors by AppState.sensors.collectAsState()
    val stations by AppState.stations.collectAsState()
    val sat by AppState.satState.collectAsState()
    val rotorPos by AppState.rotorPos.collectAsState()
    val phone by AppState.phoneSensors.collectAsState()
    var target by rememberSaveable { mutableStateOf("STN") }
    var hdgUp by rememberSaveable { mutableStateOf(false) }
    val moving = (gnss?.speedKmh ?: 0.0) > 3.0
    val heading = if (moving) gnss?.courseDeg else sensors?.headingDeg ?: phone.headingDeg
    val st = (DispState.station?.let { c -> stations.firstOrNull { it.call == c } } ?: stations.filter { it.lat != null }.maxByOrNull { it.lastHeardMs })
    val stn = st?.let { Own.to(it.lat, it.lon) }
    val (tName, tDeg, tLine) = when (target) {
        "STN" -> Triple(st?.call ?: "No station with position", stn?.second, stn?.let { Fmt.km(it.first) } ?: "—")
        "SAT" -> Triple(sat?.name ?: "No satellite (SAT mode)", sat?.azDeg, sat?.elDeg?.let { String.format(Locale.US, "el %.0f°", it) } ?: "—")
        "ROTOR" -> Triple("Rotor box", rotorPos?.azDeg, rotorPos?.elDeg?.let { String.format(Locale.US, "el %.0f°", it) } ?: "—")
        else -> Triple("My course", gnss?.courseDeg, gnss?.speedKmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "—")
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel(if (hdgUp) "HEADING UP" else "NORTH UP")
                Text(tName, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Alp.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (target == "STN" && st != null) Modifier.clickable { nav.go("STATION:" + st.call) } else Modifier)
            }
            Chip(if (hdgUp) "HDG-UP" else "N-UP", hdgUp, height = 30.dp) { hdgUp = !hdgUp }
        }
        CompassRose(Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp), headingUp = if (hdgUp) heading ?: 0.0 else 0.0,
            targetDeg = tDeg, rotorDeg = if (target != "ROTOR") rotorPos?.azDeg else null, ownCourse = heading)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                SectionLabel("BEARING")
                Text(if (tDeg == null) "—" else Fmt.deg(tDeg) + " " + Geo.cardinal16(tDeg), fontFamily = TechMono, fontSize = 30.sp, color = Alp.Amber)
            }
            Column(horizontalAlignment = Alignment.End) {
                SectionLabel(if (target == "STN") "DISTANCE" else if (target == "MY") "SPEED" else "ELEVATION")
                Text(tLine, fontFamily = TechMono, fontSize = 24.sp, color = Alp.Text)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in listOf("MY", "STN", "SAT", "ROTOR")) RadioKey(t, Modifier.weight(1f).height(48.dp), border = if (t == target) Alp.Amber else Alp.KeyBorder,
            color = if (t == target) Alp.Amber else Alp.Text) { target = t }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTile("SPEED", gnss?.speedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "—", Modifier.weight(1f), unit = "km/h")
        DataTile("COURSE", Fmt.deg(gnss?.courseDeg), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTile("TEMP", sensors?.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f), unit = "°C")
        DataTile("QNH", sensors?.qnhHpa?.let { String.format(Locale.US, "%.0f", it) } ?: "—", Modifier.weight(1f), unit = "hPa")
    }
}

@Composable
private fun AltitudePage() {
    val g by AppState.gnss.collectAsState()
    val s by AppState.sensors.collectAsState()
    val phone by AppState.phoneSensors.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTile("GNSS ALT", g?.altitudeM?.let { String.format(Locale.US, "%.0f", it) } ?: "—", Modifier.weight(1f), unit = "m")
        DataTile("BARO ALT", s?.baroAltitudeM?.let { String.format(Locale.US, "%.0f", it) } ?: "—", Modifier.weight(1f), unit = "m")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTile("PRESSURE", s?.pressureHpa?.let { String.format(Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f), unit = "hPa")
        DataTile("QNH", s?.qnhHpa?.let { String.format(Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f), unit = "hPa")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTile("TEMP", s?.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f), unit = "°C")
        DataTile("HUMIDITY", s?.humidityPct?.let { String.format(Locale.US, "%.0f", it) } ?: "—", Modifier.weight(1f), unit = "%")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTile("PHONE BARO", phone.pressureHpa?.let { String.format(Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f), unit = "hPa")
        DataTile("AIR", s?.iaqLabel ?: s?.iaq?.let { String.format(Locale.US, "IAQ %.0f", it) } ?: "—", Modifier.weight(1f))
    }
}

@Composable
private fun ClockPage() {
    val now = rememberNow()
    val status by AppState.trackerStatus.collectAsState()
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("UTC")
        Text(String.format(Locale.US, "%02d:%02d:%02d", utc.get(java.util.Calendar.HOUR_OF_DAY), utc.get(java.util.Calendar.MINUTE), utc.get(java.util.Calendar.SECOND)),
            fontFamily = TechMono, fontSize = 58.sp, color = Alp.Green)
        Text(String.format(Locale.US, "%04d-%02d-%02d", utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH) + 1, utc.get(java.util.Calendar.DAY_OF_MONTH)),
            fontFamily = TechMono, fontSize = 22.sp, color = Alp.SubText)
        Spacer(Modifier.height(12.dp))
        SectionLabel("LOCAL")
        Text(Fmt.hhmm(now), fontFamily = TechMono, fontSize = 40.sp, color = Alp.Text)
        Spacer(Modifier.height(8.dp))
        InfoRow("LOCATOR", Spotter.locator().ifEmpty { "—" })
        InfoRow("TRACKER UP", status?.uptimeS?.let { String.format(Locale.US, "%d h %02d min", it / 3600, (it / 60) % 60) } ?: "—")
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Alp.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.width(96.dp))
        Text(value, fontFamily = TechMono, fontSize = 16.sp, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
