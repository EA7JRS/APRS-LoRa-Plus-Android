/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.ea7jrs.aprslorap.core.Ais
import es.ea7jrs.aprslorap.core.AlertKind
import es.ea7jrs.aprslorap.core.Alerts
import es.ea7jrs.aprslorap.core.EmergencyAlert
import es.ea7jrs.aprslorap.core.Spots
import java.util.Locale

/** What the window shows for one alert, worked out from the live state (station list, AIS list, EAI event). */
private class AlertView(
    val who: String, val source: String, val icon: String,            // icon: "aprs", "ais" or "own"
    val sym: es.ea7jrs.aprslorap.core.AprsSymbols.Symbol? = null,
    val distance: String? = null, val bearingDeg: Double? = null, val bearingText: String = "",
    val message: String? = null, val countdown: String? = null, val countLabel: String = "",
    val lat: Double? = null, val lon: Double? = null, val line2: String = "", val line3: String = "",
    val heard: String = "", val level: String = "",
    /** The data in rows ("Distance / bearing" → "4.6 km · 318° (NW)"), the same ones the plotter and the Display show. */
    val rows: List<Pair<String, String>> = emptyList(),
    val actions: List<Triple<String, String, () -> Unit>> = emptyList(),
)

/** The emergency window over any screen (and over the lock screen while an alert is open). */
@Composable
fun AlertOverlay(nav: Navigator) {
    val items by AlertCenter.items.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(items.isNotEmpty()) {
        val act = ctx as? Activity ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 27) { act.setShowWhenLocked(items.isNotEmpty()); act.setTurnScreenOn(items.isNotEmpty()) }
    }
    if (items.isEmpty()) return
    var index by remember { mutableStateOf(0) }
    val i = index.coerceIn(0, items.size - 1)
    val a = items[i]
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xBC06090D)).padding(16.dp), contentAlignment = Alignment.Center) {
            AlertWindow(nav, a, i, items.size, onPrev = { index = (i - 1 + items.size) % items.size }, onNext = { index = (i + 1) % items.size })
        }
    }
}

@Composable
private fun AlertWindow(nav: Navigator, a: EmergencyAlert, i: Int, n: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val accent = if (a.kind.distress) Alp.Red else Alp.Amber
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(0.25f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "ring")
    val sounding by AlertCenter.sounding.collectAsState()
    val leds by AppState.trackerLeds.collectAsState()
    val trackerSounding = leds?.emg == true
    val v = alertView(nav, a, ctx)
    val close = { AlertCenter.close(a.key) }
    // leaving the window to act on the alert: the alarm stops, the alert stays in the queue until CLOSE
    fun act(f: () -> Unit) { AlertCenter.stopAlarm(); f() }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Alp.Panel)
        .border(2.dp, accent.copy(alpha = 0.4f + 0.6f * pulse), RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().background(accent).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(26.dp)) {
                val s = size.width
                val p = Path().apply { moveTo(s / 2, s * 0.1f); lineTo(s * 0.95f, s * 0.88f); lineTo(s * 0.05f, s * 0.88f); close() }
                drawPath(p, Color(0xFF131A22), style = Stroke(width = s * 0.09f))
                drawLine(Color(0xFF131A22), androidx.compose.ui.geometry.Offset(s / 2, s * 0.4f), androidx.compose.ui.geometry.Offset(s / 2, s * 0.6f), s * 0.09f)
                drawCircle(Color(0xFF131A22), s * 0.05f, androidx.compose.ui.geometry.Offset(s / 2, s * 0.74f))
            }
            Column(Modifier.weight(1f)) {
                Text(a.kind.title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 24.sp, color = Color(0xFF131A22), maxLines = 1)
                Text(kindLine(a), fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF131A22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (n > 1) {
                Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF131A22), modifier = Modifier.clickable(onClick = onPrev).padding(horizontal = 8.dp))
                Text("${i + 1} / $n", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF131A22))
                Text("›", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF131A22), modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 8.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Alp.Key), contentAlignment = Alignment.Center) {
                    when (v.icon) {
                        "aprs" -> AprsSymbolIcon(v.sym, 40.dp)
                        "ais" -> Canvas(Modifier.size(32.dp)) {
                            val s = size.width
                            val p = Path().apply { moveTo(s / 2, s * 0.06f); lineTo(s * 0.72f, s * 0.7f); lineTo(s / 2, s * 0.58f); lineTo(s * 0.28f, s * 0.7f); close() }
                            drawPath(p, accent, style = Stroke(width = s * 0.08f))
                            drawCircle(accent, s * 0.07f, androidx.compose.ui.geometry.Offset(s / 2, s * 0.86f))
                        }
                        else -> Text(Settings(ctx).callsign.take(1).ifEmpty { "•" }, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = accent)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(v.who, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 30.sp, color = Alp.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.source, fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Muted, maxLines = 2)
                }
                if (v.distance != null) Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (v.bearingDeg != null) Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Alp.Cyan, modifier = Modifier.rotate(v.bearingDeg.toFloat()))
                        Text(v.distance, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Alp.Text)
                    }
                    Text(v.bearingText, fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Muted)
                }
            }
            if (v.message != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Bg).border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text("TO ${Settings(ctx).callsign} · ${Fmt.hhmm(a.atMs, utc = true)}Z", fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted)
                Text(v.message, fontSize = 17.sp, lineHeight = 23.sp, color = Alp.Text)
            }
            if (v.countdown != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Bg).border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(v.countdown, fontFamily = TechMono, fontSize = 54.sp, lineHeight = 56.sp, color = accent)
                Text("  " + v.countLabel, fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.SubText, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (v.rows.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Bg)
                .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                v.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(label, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.width(118.dp))
                        Text(value, fontSize = 15.sp, lineHeight = 19.sp, color = Alp.Text, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (v.rows.isEmpty() && v.message == null && (v.lat != null || v.line2.isNotBlank())) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Bg)
                .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (v.lat != null && v.lon != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.latLon(v.lat, true), fontFamily = TechMono, fontSize = 16.sp, color = Alp.Text)
                    Text(Fmt.latLon(v.lon, false), fontFamily = TechMono, fontSize = 16.sp, color = Alp.Text)
                    Text(Spots.locator(v.lat, v.lon), fontFamily = TechMono, fontSize = 16.sp, color = Alp.Text)
                }
                if (v.line2.isNotBlank()) Text(v.line2, fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.SubText)
                if (v.line3.isNotBlank()) Text(v.line3, fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.SubText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (v.rows.isEmpty() && v.heard.isNotBlank()) Text(v.heard, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                if (v.rows.isEmpty() && v.level.isNotBlank()) Text(v.level, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                if (trackerSounding) Text("Tracker alarm sounding", fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Amber)
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            v.actions.forEachIndexed { k, (label, sub, f) ->
                val main = k == 0
                val bg = when { !main -> Alp.Key; label == "I'M OK" -> Alp.Green; label == "STOP EAI" -> Alp.Red; else -> Alp.Cyan }
                RadioKey(label, Modifier.weight(1f).height(54.dp), sub = sub, bg = bg, color = if (main) Color(0xFF131A22) else Alp.Text,
                    subColor = if (main) Color(0xFF131A22) else Alp.Muted, border = if (main) bg else Alp.KeyBorder) { act(f) }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val quiet = !sounding && !trackerSounding
            RadioKey(if (quiet) "ALARM SILENCED" else "SILENCE ALARM", Modifier.weight(1f).height(48.dp), color = Alp.Amber, border = Alp.Amber, bg = Color.Transparent,
                enabled = !quiet) { AlertCenter.silence() }
            RadioKey("CLOSE", Modifier.weight(1f).height(48.dp)) { AlertCenter.silence(); close() }
        }
    }
}

private fun action(label: String, sub: String, f: () -> Unit): Triple<String, String, () -> Unit> = Triple(label, sub, f)

private fun kindLine(a: EmergencyAlert): String = when (a.kind) {
    AlertKind.APRS_BEACON -> if (a.text.startsWith("Mic-E", true)) "APRS · Mic-E status EMERGENCY" else "APRS beacon · ${a.text.take(40)}"
    AlertKind.APRS_MESSAGE -> "APRS message · emergency words"
    AlertKind.MOB -> if (a.fromMsg) "APRS message · MOB" else "APRS beacon · MOB"
    AlertKind.AIS_DISTRESS -> "AIS · " + a.text.ifBlank { Ais.distressName(a.mmsi ?: 0).ifBlank { "distress" } }
    AlertKind.AIS_COLLISION -> "AIS · closest point of approach"
    AlertKind.EAI -> "This tracker · own emergency beacon"
    AlertKind.MAN_DOWN -> "This tracker · no movement detected"
}

@Composable
private fun alertView(nav: Navigator, a: EmergencyAlert, ctx: android.content.Context): AlertView {
    val stations by AppState.stations.collectAsState()
    val heard by AppState.heard.collectAsState()
    val heardAt by AppState.heardAtMs.collectAsState()
    val vessels by AppState.aisVessels.collectAsState()
    val eai by AlertCenter.eai.collectAsState()
    val now = rememberNow(1000)
    val c = AppState.controller
    return when (a.kind) {
        AlertKind.APRS_BEACON, AlertKind.APRS_MESSAGE, AlertKind.MOB -> {
            val sv = stationViews(stations, heard, heardAt).firstOrNull { it.call.equals(a.who, true) }
            val d = Own.to(sv?.lat, sv?.lon)
            val pos = sv?.lat != null && sv.lon != null
            val goTo = action("GO TO", "Google Maps") { if (pos) { goToPos(ctx, sv!!.lat!!, sv.lon!!, a.who) } else { AppState.error.value = "No position known for ${a.who}" } }
            val map = action("MAP", "station") { MapFocus.call = a.who; nav.go("MAP") }
            AlertView(
                a.who, if (a.fromMsg) "Message" else "Position beacon" + (sv?.kind?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), "aprs", sv?.symbol,
                d?.let { Fmt.km(it.first) }, d?.second, d?.let { Fmt.deg(it.second) } ?: "",
                message = if (a.fromMsg) a.text else null,
                lat = sv?.lat, lon = sv?.lon,
                line2 = listOfNotNull(sv?.speedKmh?.takeIf { it > 2 }?.let { String.format(Locale.US, "%.0f km/h", it) } ?: if (pos) "Stopped" else null,
                    sv?.altitudeM?.let { String.format(Locale.US, "alt %.0f m", it) }, (sv?.comment ?: a.text).takeIf { it.isNotBlank() }?.let { "“$it”" }).joinToString(" · "),
                heard = listOfNotNull(sv?.path?.takeIf { it.isNotBlank() }?.let { "via $it" }, sv?.lastMs?.let { Fmt.ago(it) }).joinToString(" · "),
                level = listOfNotNull(sv?.rssi?.let { "$it dBm" }, sv?.snr?.let { String.format(Locale.US, "SNR %.1f", it) }).joinToString(" · "),
                rows = listOfNotNull(
                    d?.let { "Distance / bearing" to Fmt.km(it.first) + " · " + Fmt.deg(it.second) + " (" + es.ea7jrs.aprslorap.core.Geo.cardinal16(it.second) + ")" },
                    if (pos) "Position" to Fmt.latLon(sv?.lat ?: 0.0, true) + "  " + Fmt.latLon(sv?.lon ?: 0.0, false) + " · " + Spots.locator(sv?.lat ?: 0.0, sv?.lon ?: 0.0) else null,
                    sv?.speedKmh?.takeIf { it > 2 }?.let { "Speed / course" to Fmt.speed(it) + (sv?.courseDeg?.let { c -> " · " + Fmt.deg(c.toDouble()) } ?: "") },
                    sv?.altitudeM?.let { "Altitude" to String.format(Locale.US, "%.0f m", it) },
                    sv?.lastMs?.let { "Heard" to Fmt.ago(it) + (sv?.path?.takeIf { p -> p.isNotBlank() }?.let { p -> " · via $p" } ?: "") },
                    listOfNotNull(sv?.rssi?.let { "$it dBm" }, sv?.snr?.let { String.format(Locale.US, "SNR %.1f", it) }).joinToString(" · ").takeIf { it.isNotBlank() }?.let { "Signal" to it },
                    if (a.fromMsg) null else (sv?.comment ?: a.text).takeIf { it.isNotBlank() }?.let { "Text" to it },
                ),
                actions = if (a.fromMsg) listOf(action("REPLY", "message") { nav.go("THREAD:" + a.who.uppercase()) }, goTo, map)
                    else listOf(goTo, action("ROTOR", d?.let { "aim " + Fmt.deg(it.second) } ?: "aim") { aimRotorAt(sv?.lat, sv?.lon, a.who) }, map),
            )
        }
        AlertKind.AIS_DISTRESS, AlertKind.AIS_COLLISION -> {
            val ves = a.mmsi?.let { vessels[it] }
            val lat = ves?.lat; val lon = ves?.lon
            val d = Own.to(lat, lon)
            val nm = ves?.rangeNm ?: d?.let { it.first / 1.852 }
            val brg = ves?.bearingDeg?.toDouble() ?: d?.second
            val name = ves?.title ?: a.who
            val type = listOf(Ais.distressName(a.mmsi ?: 0), Ais.shipTypeName(ves?.shipType ?: 0), a.mmsi?.let { String.format(Locale.US, "%09d", it) } ?: "",
                ves?.callsign ?: "").filter { it.isNotBlank() && it != name }.joinToString(" · ")
            val goTo = action("GO TO", "Google Maps") { if (lat != null && lon != null) { goToPos(ctx, lat, lon, name) } else { AppState.error.value = "No position known for $name" } }
            val rotor = action("ROTOR", brg?.let { "aim " + Fmt.deg(it) } ?: "aim") { aimRotorAt(lat, lon, name) }
            val aisTab = action("MAP", "AIS") { nav.tool(Tool.AIS) }
            AlertView(
                name, type, "ais", null,
                nm?.let { String.format(Locale.US, "%.1f nm", it) }, brg, listOfNotNull(brg?.let { Fmt.deg(it) }, d?.let { Fmt.km(it.first) }).joinToString(" · "),
                lat = lat, lon = lon,
                line2 = listOfNotNull(ves?.sogKn?.let { String.format(Locale.US, "SOG %.1f kn", it) }, ves?.cogDeg?.let { String.format(Locale.US, "COG %03.0f°", it) },
                    ves?.posAgeS?.let { "position $it s old" }, ves?.takeIf { it.lengthM > 0 }?.let { "${it.lengthM} × ${it.beamM} m" }).joinToString(" · "),
                line3 = listOfNotNull(ves?.text?.takeIf { it.isNotBlank() }?.let { "“$it”" }, ves?.takeIf { it.cpaNm != null && it.tcpaMin != null }?.let {
                    String.format(Locale.US, "CPA %.2f nm in %.0f min", it.cpaNm, it.tcpaMin) }).joinToString(" · "),
                heard = ves?.channel?.takeIf { it.isNotBlank() }?.let { "AIS channel $it" } ?: "AIS",
                level = ves?.let { Fmt.ago(it.receivedAtMs) } ?: "",
                rows = listOfNotNull(
                    nm?.let { "Distance / bearing" to String.format(Locale.US, "%.1f nm", it) + (brg?.let { b -> " · " + Fmt.deg(b) } ?: "") },
                    ves?.takeIf { it.cpaNm != null && it.tcpaMin != null }?.let { "CPA" to String.format(Locale.US, "%.2f nm in %.0f min", it.cpaNm, it.tcpaMin) },
                    ves?.sogKn?.let { "Speed / course" to String.format(Locale.US, "%.1f kn", it) + (ves?.cogDeg?.let { c -> String.format(Locale.US, " · %03.0f°", c) } ?: "") },
                    if (lat != null && lon != null) "Position" to Fmt.latLon(lat, true) + "  " + Fmt.latLon(lon, false) + (ves?.posAgeS?.let { " · $it s old" } ?: "") else null,
                    a.mmsi?.let { "MMSI" to String.format(Locale.US, "%09d", it) + (ves?.cls?.takeIf { c -> c.isNotBlank() && c != "?" }?.let { c -> " · class $c" } ?: "") },
                    ves?.takeIf { it.lengthM > 0 }?.let { "Vessel" to "${it.lengthM} × ${it.beamM} m" },
                    ves?.let { "Heard" to Fmt.ago(it.receivedAtMs) + (it.channel.takeIf { c -> c.isNotBlank() }?.let { c -> " · AIS channel $c" } ?: "") },
                    ves?.text?.takeIf { it.isNotBlank() }?.let { "Text" to it },
                ),
                actions = if (a.kind == AlertKind.AIS_DISTRESS) listOf(goTo, rotor, aisTab) else listOf(aisTab, rotor, action("DETAIL", "vessel") { nav.tool(Tool.AIS) }),
            )
        }
        AlertKind.EAI -> {
            val e = eai
            val el = e?.let { (now - it.atMs) / 1000 } ?: 0
            val left = (e?.left ?: e?.fmLeft)?.let { it - el }
            val me = Own.position()
            val share = action("SHARE", "position") {
                val p = Own.position()
                val text = "EMERGENCY ${Settings(ctx).callsign}" + (p?.let { String.format(Locale.US, " %.5f,%.5f https://maps.google.com/?q=%.5f,%.5f", it.first, it.second, it.first, it.second) } ?: "")
                try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share position").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            AlertView(
                a.who.ifBlank { "This tracker" }, e?.let { AlertCenter.eaiSource(it) } ?: "", "own",
                countdown = left?.let { Alerts.formatLeft(it) } ?: e?.takeIf { it.sos == 2 }?.let { "SOS" }, countLabel = if (left != null) "left" else "beacon on",
                lat = me?.first, lon = me?.second,
                line2 = if ((e?.sos ?: 0) == 2) "SOS beacon every 30 s with GPS position · ${e?.sent ?: 0} sent" else "",
                line3 = if (e?.fm == true) "FM voice EAI: " + (if (e?.fmMode == 2) "Continuous (microphone open)" else "Interval (beeps)") + " · CW ID every 10 min" else "",
                actions = listOf(action("STOP EAI", "on the tracker") { c?.control("eai", "s" to "stop"); Unit },
                    action("MAP", "my position") { MapFocus.call = null; nav.go("MAP") }, share),
            )
        }
        AlertKind.MAN_DOWN -> {
            val e = eai
            val el = e?.let { (now - it.atMs) / 1000 } ?: 0
            val left = e?.manDownLeft?.let { it - el }
            val me = Own.position()
            AlertView(
                a.who.ifBlank { "This tracker" }, "No key touched for ${e?.manDownMin ?: 0} min", "own",
                countdown = left?.let { Alerts.formatLeft(it) }, countLabel = "to EAI",
                lat = me?.first, lon = me?.second, line2 = "Then: FM voice EAI" + " and the APRS SOS beacon if it is on",
                heard = "Man-down timer: ${e?.manDownMin ?: 0} min",
                actions = listOf(action("I'M OK", "reset timer") { c?.control("eai", "s" to "ok"); Unit },
                    action("START EAI", "now") { c?.control("eai", "s" to "start"); Unit },
                    action("SETTINGS", "man down") { nav.tool(Tool.TRACKER) }),
            )
        }
    }
}
