/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.HamLayers
import es.ea7jrs.aprslorap.core.Terminator
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/** What the user heard of each beacon on each band (a tap on HEARD), kept between sessions: "CALL|band" -> time. */
object IbpLog {
    val heard = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("ibp", Context.MODE_PRIVATE)
        heard.value = p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    }

    fun mark(ctx: Context, call: String, band: String, ms: Long = System.currentTimeMillis()) {
        val k = "$call|$band"
        heard.value = heard.value + (k to ms)
        ctx.getSharedPreferences("ibp", Context.MODE_PRIVATE).edit().putLong(k, ms).apply()
    }

    fun clear(ctx: Context) {
        heard.value = emptyMap()
        ctx.getSharedPreferences("ibp", Context.MODE_PRIVATE).edit().clear().apply()
    }
}

private val BAND_COLORS = listOf(0xFF3CE68A, 0xFF28B5E1, 0xFFF2D35B, 0xFFFF8C1A, 0xFFFF6B6B)

/**
 * NCDXF / IARU International Beacon Project: the 18 beacons (www.ncdxf.org/beacon/beaconlocations.html) on a world map
 * with the grey line, the beacon on the air on each band now (10 s each, 14.100 → 18.110 → 21.150 → 24.930 → 28.200,
 * 3-minute cycle), short and long path from us, tuning of the SDR or the radio of the rotor box, the rotor following the
 * beacon of the chosen band, and a table of what has been heard (like BeaconTime and the manual logging of BeSpeak).
 */
@Composable
fun IbpScreen(nav: Navigator) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { IbpLog.load(ctx) }
    val now = rememberNow(1000)
    var band by rememberSaveable { mutableStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var lastAim by remember { mutableStateOf("") }
    val heard by IbpLog.heard.collectAsState()
    val me = Own.position()
    val onAir = HamLayers.ibpNow(now)
    val left = HamLayers.ibpSecondsLeft(now)
    val slot = HamLayers.ibpSlot(now)
    val sel = onAir[band]
    val selIdx = HamLayers.BEACONS.indexOf(sel.second)

    // the rotor box follows the beacon of the chosen band (a new one every 10 s)
    if (follow) LaunchedEffect(slot, band) {
        val b = onAir[band].second
        if (lastAim != b.call + band) { lastAim = b.call + band; aimRotorAt(b.lat, b.lon, b.call) }
    }

    val map = remember {
        MapLayers.configure(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(MapLayers.NIGHT)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 1.0
            controller.setZoom(1.6)
            controller.setCenter(GeoPoint(25.0, (me?.second ?: 0.0).coerceIn(-60.0, 60.0)))
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause() } }
    val dynamic = remember { ArrayList<Overlay>() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("IBP BEACONS · NCDXF / IARU", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            Text(String.format(Locale.US, "slot %d · %d s", slot + 1, left), fontFamily = TechMono, fontSize = 14.sp, color = Alp.Green, modifier = Modifier.padding(end = 8.dp))
        }
        Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            AndroidView({ map }, Modifier.fillMaxSize()) { m ->
                m.overlays.removeAll(dynamic.toSet()); dynamic.clear()
                val d = m.context.resources.displayMetrics.density
                dynamic.add(Polygon(m).apply {
                    points = Terminator.nightPolygon(now).map { GeoPoint(it.first, it.second) }
                    fillPaint.color = 0x66050A10; outlinePaint.color = 0xCCFFB347.toInt(); outlinePaint.strokeWidth = 1.5f * d
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(4f * d, 6f * d), 0f)
                    setOnClickListener { _, _, _ -> false }
                })
                if (me != null) onAir.forEachIndexed { i, (_, b) ->
                    for (seg in HamLayers.greatCircle(me.first, me.second, b.lat, b.lon, 60)) dynamic.add(Polyline(m).apply {
                        setPoints(seg.map { GeoPoint(it.first.coerceIn(-85.0, 85.0), it.second) })
                        outlinePaint.color = (if (i == band) 0xFF000000.toInt() else 0x88000000.toInt()) or (BAND_COLORS[i].toInt() and 0xFFFFFF)
                        outlinePaint.strokeWidth = (if (i == band) 3.5f else 1.6f) * d
                    })
                }
                val active = onAir.mapIndexed { i, p -> p.second.call to i }.toMap()
                for (b in HamLayers.BEACONS) {
                    val i = active[b.call]
                    dynamic.add(Marker(m).apply {
                        position = GeoPoint(b.lat, b.lon)
                        val col = i?.let { BAND_COLORS[it].toInt() }
                        icon = BitmapDrawable(m.context.resources, MarkerArt.tag(m.context, b.call + (i?.let { " · " + HamLayers.IBP_BANDS[it].label } ?: ""),
                            col ?: 0xE6131A22.toInt(), if (col != null) 0xFF131A22.toInt() else 0xFF9DB2C7.toInt()))
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> i?.let { band = it }; true }
                    })
                }
                if (me != null) dynamic.add(Marker(m).apply {
                    position = GeoPoint(me.first, me.second); icon = BitmapDrawable(m.context.resources, MarkerArt.own(m.context, null))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                m.overlays.addAll(dynamic)
                m.invalidate()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HamLayers.IBP_BANDS.forEachIndexed { i, b -> Chip(b.label, i == band, Modifier.weight(1f), color = Color(BAND_COLORS[i])) { band = i } }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BeaconCard(sel.first, sel.second, selIdx, left, me, now, Color(BAND_COLORS[band]))
            Text("ON THE AIR NOW", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Alp.Muted, letterSpacing = 1.5.sp)
            onAir.forEachIndexed { i, (bd, b) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (i == band) Alp.GreenBg else Alp.Panel).clickable { band = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(String.format(Locale.US, "%.3f", bd.mhz), fontFamily = TechMono, fontSize = 14.sp, color = Color(BAND_COLORS[i]), modifier = Modifier.width(64.dp))
                    Text(b.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Alp.Text, modifier = Modifier.width(64.dp))
                    Text(b.entity, fontSize = 13.sp, color = Alp.Muted, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(me?.let { String.format(Locale.US, "%03.0f°", Geo.bearingDeg(it.first, it.second, b.lat, b.lon)) } ?: "", fontFamily = TechMono, fontSize = 13.sp, color = Alp.Cyan)
                }
            }
            Text("HEARD (TAP HEARD WHILE YOU HEAR THE BEACON)", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Alp.Muted, letterSpacing = 1.5.sp)
            HeardGrid(heard, now, onAir) { i -> band = i }
            Text("Each beacon sends its callsign in CW at 100 W and then four dashes of 1 s at 100, 10, 1 and 0.1 W. Positions: centre of the grid square that NCDXF lists. " +
                "A long press on ROTOR makes the rotor box follow the beacon of the chosen band.", fontSize = 12.sp, color = Alp.Muted)
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey("TUNE", Modifier.weight(1f).height(54.dp), sub = String.format(Locale.US, "%.3f CW", sel.first.mhz), border = Alp.GreenDim, color = Alp.Green) {
                AppState.notice.value = tuneIbp(sel.first.mhz)
            }
            RadioKey("ROTOR", Modifier.weight(1f).height(54.dp), sub = if (follow) "following" else "aim", border = Alp.AmberDim, color = Alp.Amber,
                bg = if (follow) Alp.GreenBg else Alp.Key, onLongClick = { follow = !follow; lastAim = "" }) {
                if (follow) { follow = false } else aimRotorAt(sel.second.lat, sel.second.lon, sel.second.call)
            }
            RadioKey("HEARD", Modifier.weight(1f).height(54.dp), sub = sel.second.call, border = Alp.GreenDim, color = Alp.Green) {
                IbpLog.mark(ctx, sel.second.call, sel.first.label, now)
            }
            RadioKey("MAP", Modifier.weight(1f).height(54.dp)) {
                Settings(ctx).let { s -> s.mapOverlays = (MapOverlays.parseOn(s.mapOverlays) + "ham_ibp").joinToString(",") }
                nav.go("MAP")
            }
        }
    }
}

/** Tunes the SDR and / or the radio of the rotor box to the beacon frequency, in CW. */
private fun tuneIbp(mhz: Double): String {
    val hz = Math.round(mhz * 1e6)
    val done = ArrayList<String>()
    if (AppState.sdr.value.connected) { SdrControl.tune(hz); SdrControl.mode("CW", 500); done.add("SDR") }
    done.addAll(Rig.tune(hz, "CW"))
    return if (done.isEmpty()) "Nothing tuned: connect an SDR (SDR), a radio by USB (CAT) or the radio on the rotor box (Rotor > Radio)"
    else String.format(Locale.US, "Tuned %.3f MHz CW → ", mhz) + done.joinToString(" + ")
}

@Composable
private fun BeaconCard(bd: HamLayers.IbpBand, b: HamLayers.Beacon, idx: Int, left: Int, me: Pair<Double, Double>?, now: Long, color: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, color, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(b.call, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = color)
            Text("${b.entity} · ${b.location}", fontSize = 14.sp, color = Alp.Text, modifier = Modifier.weight(1f), maxLines = 2)
            Text("$left s", fontFamily = TechMono, fontSize = 18.sp, color = color)
        }
        Text(String.format(Locale.US, "%.3f MHz · %s · slot %d · %s", bd.mhz, b.grid, idx + 1, b.sponsor), fontSize = 13.sp, color = Alp.Muted)
        if (me != null) {
            val km = Geo.distanceKm(me.first, me.second, b.lat, b.lon); val brg = Geo.bearingDeg(me.first, me.second, b.lat, b.lon)
            Text(String.format(Locale.US, "Short path %03.0f° · %,.0f km    Long path %03.0f° · %,.0f km", brg, km, (brg + 180) % 360, 40_030 - km),
                fontFamily = TechMono, fontSize = 13.sp, color = Alp.Cyan)
        }
        val night = Terminator.isNight(now, b.lat, b.lon); val meNight = me?.let { Terminator.isNight(now, it.first, it.second) }
        Text((if (night) "Night" else "Day") + " at the beacon" + (meNight?.let { " · " + (if (it) "night" else "day") + " here" } ?: ""), fontSize = 13.sp, color = Alp.Muted)
        val next = HamLayers.IBP_BANDS.filter { it != bd }.joinToString("  ") { it.label + " " + HamLayers.ibpSecondsUntil(now, idx, it) + " s" }
        Text("Next on: $next", fontSize = 12.sp, color = Alp.Muted)
    }
}

/** 18 beacons × 5 bands, coloured by how long ago each one was heard. */
@Composable
private fun HeardGrid(heard: Map<String, Long>, now: Long, onAir: List<Pair<HamLayers.IbpBand, HamLayers.Beacon>>, onBand: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text("", Modifier.width(70.dp))
            HamLayers.IBP_BANDS.forEachIndexed { i, b -> Text(b.label, Modifier.weight(1f).clickable { onBand(i) }, fontFamily = BarlowCondensed, fontSize = 13.sp, color = Color(BAND_COLORS[i])) }
        }
        for (b in HamLayers.BEACONS) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.call, Modifier.width(70.dp), fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Alp.Text)
            HamLayers.IBP_BANDS.forEachIndexed { i, bd ->
                val t = heard[b.call + "|" + bd.label]
                val age = t?.let { now - it }
                val bg = when {
                    age == null -> Alp.Key
                    age < 3 * 60_000L -> Color(0xFF3CE68A)
                    age < 30 * 60_000L -> Color(0xAA3CE68A)
                    age < 3 * 3_600_000L -> Color(0x553CE68A)
                    else -> Color(0x223CE68A)
                }
                val onNow = onAir[i].second.call == b.call
                Box(Modifier.weight(1f).height(18.dp).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(bg)
                    .border(if (onNow) 1.5.dp else 0.dp, if (onNow) Color(BAND_COLORS[i]) else Color.Transparent, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    if (age != null) Text(Fmt.ago(t).replace(" ago", ""), fontSize = 10.sp, color = if (age < 3 * 60_000L) Alp.Bg else Alp.Text, maxLines = 1)
                }
            }
        }
    }
}
