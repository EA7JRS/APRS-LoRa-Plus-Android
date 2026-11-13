/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import es.ea7jrs.aprslorap.core.CallsignCheck
import es.ea7jrs.aprslorap.core.DxCluster
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.HamLayers
import es.ea7jrs.aprslorap.core.Rbn
import es.ea7jrs.aprslorap.core.Spots
import es.ea7jrs.aprslorap.core.Terminator
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/** ALL, HF (below 30 MHz), VHF+ (30 MHz and up) and every amateur band, LF to millimetre waves. */
private val RBN_BANDS = listOf("ALL", "HF", "VHF+") + Spots.BANDS.map { it.first }
private fun bandColor(b: String?): Int = when (b) {
    "2200m", "630m" -> 0xFF9FA8DA; "160m", "80m", "60m" -> 0xFFB39DDB; "40m" -> 0xFF4FC3F7; "30m" -> 0xFF28B5E1
    "20m" -> 0xFF3CE68A; "17m" -> 0xFFA5E35B; "15m" -> 0xFFF2D35B; "12m" -> 0xFFFFB347; "10m" -> 0xFFFF8C1A
    "8m", "6m", "4m" -> 0xFFFF6B6B; "2m", "1.25m" -> 0xFFF06292; "70cm", "33cm" -> 0xFFCE93D8
    "HF", "VHF+" -> 0xFFE8EEF3; null, "" -> 0xFFE8EEF3; else -> 0xFFFFD54F
}.toInt()

private fun inBand(h: Rbn.Hear, band: String) = when (band) {
    "ALL" -> true
    "HF" -> h.freqKhz < 30000.0
    "VHF+" -> h.freqKhz >= 30000.0
    else -> h.band == band
}

/**
 * Map of the Reverse Beacon Network (reversebeacon.net): which skimmers hear a station (you by default, a callsign you
 * write, the beacons, or everything), with a line from each skimmer to the station coloured by band, the SNR and the
 * speed. The spots come from the DX cluster connection (RBN asks end users to use a retail cluster); positions: the
 * skimmer's locator when the node gives it, else the centre of its DXCC entity (cty.dat).
 */
@Composable
fun RbnScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val st by DxLink.state.collectAsState()
    val all by DxLink.rbn.collectAsState()
    val me = Own.position()
    val myCall = (CallsignCheck.base(settings.callsign) ?: settings.callsign).uppercase(Locale.US)
    var who by rememberSaveable { mutableStateOf(0) }            // 0 me, 1 a callsign, 2 beacons, 3 all
    var call by rememberSaveable { mutableStateOf("") }
    var band by rememberSaveable { mutableStateOf("ALL") }
    var minutes by rememberSaveable { mutableStateOf(30) }
    var sel by remember { mutableStateOf<Rbn.Hear?>(null) }
    val now = rememberNow(15_000)
    val validated = Licence.isValid(settings)
    val target = if (who == 0) myCall else call.trim().uppercase(Locale.US)
    val list = all.filter { h ->
        now - h.receivedMs <= minutes * 60_000L && inBand(h, band) && when (who) {
            0, 1 -> target.isNotEmpty() && (h.dx == target || h.dx.substringBefore('/') == target)
            2 -> h.kind == Rbn.Kind.BEACON || h.kind == Rbn.Kind.NCDXF
            else -> true
        }
    }.take(if (who == 3) 400 else 1000)
    val cty = DxLink.cty

    val map = remember {
        MapLayers.configure(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(MapLayers.NIGHT)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 1.0
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(40.0, me?.second ?: 0.0))
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause() } }
    val dynamic = remember { ArrayList<Overlay>() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenHeader("RBN · REVERSE BEACON NETWORK", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            Text("${list.size}", fontFamily = BarlowCondensed, fontSize = 15.sp, color = Alp.Muted, modifier = Modifier.padding(end = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ME", "CALL", "BEACONS", "ALL").forEachIndexed { i, n -> Chip(n, i == who) { who = i } }
            listOf(10, 30, 60).forEach { m -> Chip("$m'", m == minutes, color = Alp.Amber) { minutes = m } }
        }
        if (who == 1) OutlinedTextField(call, { call = it.uppercase(Locale.US).trim() }, label = { Text("Callsign heard by the skimmers") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in RBN_BANDS) Chip(b, b == band, color = if (b == "ALL") Alp.Cyan else Color(bandColor(b)), height = 30.dp) { band = b }
        }
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            AndroidView({ map }, Modifier.fillMaxSize()) { m ->
                m.overlays.removeAll(dynamic.toSet()); dynamic.clear()
                val d = m.context.resources.displayMetrics.density
                dynamic.add(Polygon(m).apply {
                    points = Terminator.nightPolygon(now).map { GeoPoint(it.first, it.second) }
                    fillPaint.color = 0x55050A10; outlinePaint.color = 0x99FFB347.toInt(); outlinePaint.strokeWidth = 1.2f * d
                    setOnClickListener { _, _, _ -> false }
                })
                val skimmers = HashMap<String, Pair<Double, Double>>()
                val targets = HashMap<String, Pair<Double, Double>>()
                for (h in list) {
                    val sp = Rbn.position(cty, h.skimmer, h.skimmerLocator) ?: continue
                    val tp = (if (h.dx.substringBefore('/') == myCall) me else null) ?: Rbn.position(cty, h.dx, null) ?: continue
                    skimmers[h.skimmer] = sp; targets[h.dx] = tp
                    val strong = ((h.snrDb ?: 10).coerceIn(0, 30) / 30.0)
                    for (seg in HamLayers.greatCircle(tp.first, tp.second, sp.first, sp.second, 40)) dynamic.add(Polyline(m).apply {
                        setPoints(seg.map { GeoPoint(it.first.coerceIn(-85.0, 85.0), it.second) })
                        outlinePaint.color = (((0.35 + 0.6 * strong) * 255).toInt() shl 24) or (bandColor(h.band) and 0xFFFFFF)
                        outlinePaint.strokeWidth = (if (h == sel) 4f else 1.6f) * d
                    })
                }
                val byCall = list.groupBy { it.skimmer }
                for ((s, p) in skimmers) dynamic.add(Marker(m).apply {
                    position = GeoPoint(p.first, p.second)
                    val best = byCall[s]?.maxByOrNull { it.snrDb ?: -99 }
                    icon = BitmapDrawable(m.context.resources, MarkerArt.tag(m.context, s.removeSuffix("-#") + (best?.snrDb?.let { " $it" } ?: ""), 0xE6131A22.toInt(), bandColor(best?.band)))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> sel = best; true }
                })
                if (targets.size <= 60) for ((c, p) in targets) dynamic.add(Marker(m).apply {
                    position = GeoPoint(p.first, p.second)
                    icon = BitmapDrawable(m.context.resources, MarkerArt.tag(m.context, c, 0xFFFFB347.toInt(), 0xFF131A22.toInt()))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                if (me != null) dynamic.add(Marker(m).apply {
                    position = GeoPoint(me.first, me.second); icon = BitmapDrawable(m.context.resources, MarkerArt.own(m.context, null))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                m.overlays.addAll(dynamic)
                m.invalidate()
            }
        }
        sel?.let { h -> HearCard(h, me, cty) { sel = null } }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (list.isEmpty()) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    !validated -> "The RBN spots come through the DX cluster, which is entered with your callsign: validate it first (Connect)."
                    !st.connected -> "Not connected to the DX cluster. Touch CONNECT (the node of the DX cluster screen)."
                    who == 0 -> "No skimmer has heard $myCall in the last $minutes min. Call CQ in CW, RTTY or FT8, or touch ASK for the node's recent spots."
                    else -> "Nothing yet. Skimmer spots arrive with the cluster's live spots."
                }, color = Alp.Muted, fontSize = 14.sp)
            }
            LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                items(list, key = { it.skimmer + it.dx + it.freqKhz + it.receivedMs }) { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (h == sel) Alp.GreenBg else Color.Transparent).clickable { sel = h }
                        .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(String.format(Locale.US, "%.1f", h.freqKhz), fontFamily = TechMono, fontSize = 13.sp, color = Color(bandColor(h.band)), modifier = Modifier.width(66.dp))
                        Text(h.dx, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Alp.Amber, modifier = Modifier.width(76.dp), maxLines = 1)
                        Text(h.skimmer.removeSuffix("-#"), fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((h.snrDb?.let { "$it dB" } ?: "") + (h.speed?.let { " · $it ${h.speedUnit.lowercase(Locale.US)}" } ?: ""), fontFamily = TechMono, fontSize = 12.sp, color = Alp.Muted)
                        Text(Fmt.ago(h.receivedMs).replace(" ago", ""), fontSize = 11.sp, color = Alp.Muted)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey(if (st.connected) "OFF" else "CONNECT", Modifier.weight(1f).height(54.dp), sub = st.node.substringBefore(' ').ifEmpty { settings.dxNode.substringBefore(':') }, enabled = validated,
                border = Alp.GreenDim, color = Alp.Green) {
                if (st.connected) DxLink.disconnect() else {
                    val nodes = DxCluster.DEFAULT_NODES + settings.dxNodes
                    DxLink.connect(ctx, nodes.firstOrNull { it.address == settings.dxNode } ?: nodes.first(), myCall)
                }
            }
            RadioKey("ASK", Modifier.weight(1f).height(54.dp), sub = "sh/dx", enabled = st.connected && target.isNotEmpty() && who <= 1) { DxLink.send("sh/dx $target 50") }
            RadioKey("DX", Modifier.weight(1f).height(54.dp), sub = "cluster") { nav.go("DX") }
        }
    }
}

@Composable
private fun HearCard(h: Rbn.Hear, me: Pair<Double, Double>?, cty: es.ea7jrs.aprslorap.core.Cty.Table?, onClose: () -> Unit) {
    val sp = Rbn.position(cty, h.skimmer, h.skimmerLocator)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Alp.Panel).border(1.dp, Color(bandColor(h.band)), RoundedCornerShape(12.dp))
        .clickable(onClick = onClose).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${h.skimmer.removeSuffix("-#")} hears ${h.dx}", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(bandColor(h.band)))
        Text(String.format(Locale.US, "%.1f kHz · %s · %s", h.freqKhz, h.mode, h.kind.label) + (h.snrDb?.let { " · $it dB" } ?: "") +
            (h.speed?.let { " · $it ${h.speedUnit}" } ?: "") + " · ${h.utc}Z", fontSize = 13.sp, color = Alp.Text)
        val pos = (h.skimmerLocator?.let { "skimmer at $it" } ?: cty?.lookup(h.skimmer.removeSuffix("-#"))?.let { "skimmer in ${it.name} (centre of the entity)" }) ?: ""
        val dist = if (me != null && sp != null) String.format(Locale.US, " · %,.0f km from you at %03.0f°", Geo.distanceKm(me.first, me.second, sp.first, sp.second),
            Geo.bearingDeg(me.first, me.second, sp.first, sp.second)) else ""
        Text(pos + dist, fontSize = 12.sp, color = Alp.Muted)
    }
}
