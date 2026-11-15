/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.AprsSymbols
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.RotorFollow
import es.ea7jrs.aprslorap.core.SMeter
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ APRS symbols (aprs.fi set, assets/aprs-symbols)

/** The three sprite sheets of the aprs.fi symbol set (48 px per symbol), loaded once. */
object AprsSprites {
    const val CELL = 48
    @Volatile private var sheets: List<android.graphics.Bitmap>? = null
    @Volatile private var images: List<ImageBitmap>? = null

    fun bitmaps(ctx: Context): List<android.graphics.Bitmap> = sheets ?: synchronized(this) {
        sheets ?: (0..2).map { i -> ctx.assets.open("aprs-symbols/aprs-symbols-48-$i.png").use { BitmapFactory.decodeStream(it) } }.also { sheets = it }
    }

    fun images(ctx: Context): List<ImageBitmap> = images ?: synchronized(this) {
        images ?: bitmaps(ctx).map { it.asImageBitmap() }.also { images = it }
    }

    /** The symbol as an Android bitmap of [px] x [px] (for map markers). */
    fun bitmap(ctx: Context, sym: AprsSymbols.Symbol, px: Int): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val dst = android.graphics.Rect(0, 0, px, px)
        val b = bitmaps(ctx)
        for (cell in listOfNotNull(sym.base, sym.overlay)) {
            val src = android.graphics.Rect(cell.col * CELL, cell.row * CELL, (cell.col + 1) * CELL, (cell.row + 1) * CELL)
            c.drawBitmap(b[cell.sheet], src, dst, paint)
        }
        return out
    }
}

/** An APRS symbol, as aprs.fi draws it; a red-dot placeholder when the station sent none. */
@Composable
fun AprsSymbolIcon(sym: AprsSymbols.Symbol?, size: Dp = 26.dp, background: Color? = null) {
    val ctx = LocalContext.current
    val imgs = remember { try { AprsSprites.images(ctx) } catch (_: Exception) { null } }
    Box(
        Modifier.size(size + 4.dp).clip(RoundedCornerShape(6.dp)).let { if (background != null) it.background(background) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (sym == null || imgs == null) {
            Box(Modifier.size(size / 3).clip(CircleShape).background(Alp.Off))
        } else {
            Canvas(Modifier.size(size)) {
                for (cell in listOfNotNull(sym.base, sym.overlay)) {
                    drawImage(
                        imgs[cell.sheet], srcOffset = IntOffset(cell.col * AprsSprites.CELL, cell.row * AprsSprites.CELL),
                        srcSize = IntSize(AprsSprites.CELL, AprsSprites.CELL), dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ keys, chips, panels

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioKey(
    label: String, modifier: Modifier = Modifier, sub: String? = null, color: Color = Alp.Text, border: Color = Alp.KeyBorder,
    bg: Color = Alp.Key, subColor: Color = Alp.Muted, enabled: Boolean = true, labelSize: Int = 16,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, border, RoundedCornerShape(12.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = labelSize.sp, color = if (enabled) color else Alp.Off,
            maxLines = 1, lineHeight = (labelSize + 1).sp, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = subColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, color: Color = Alp.Cyan, height: Dp = 34.dp, onClick: () -> Unit) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(if (selected) color else Color.Transparent)
            .border(1.dp, if (selected) color else Alp.KeyBorder, RoundedCornerShape(height / 2)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp,
            color = if (selected) Alp.Bg else Alp.Text, maxLines = 1)
    }
}

/** Outlined toggle: green text and border when on. */
@Composable
fun Toggle(label: String, on: Boolean, modifier: Modifier = Modifier, color: Color = Alp.Green, onClick: () -> Unit) {
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, if (on) color else Alp.KeyBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (on) color else Alp.Muted) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = Alp.PanelBorder, bg: Color = Alp.Panel, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(BorderStroke(1.dp, border), RoundedCornerShape(14.dp)).padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = BarlowCondensed, fontSize = 13.sp, letterSpacing = 1.5.sp, color = Alp.Muted)
}

/** Header of the screens that are not the radio screen: back arrow, title and whatever goes on the right. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Text("‹", fontSize = 30.sp, color = Alp.Text, fontWeight = FontWeight.Bold)
        }
        Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp, color = Alp.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ------------------------------------------------------------------ S-meter

/**
 * Colour of an S-meter segment (IARU scale, as on current Yaesu / Icom radios): S1-S5 light green to blue-green (weak
 * signals, band noise), S6-S8 yellow (good, readable), S9 orange (the reference), S9+10 ... +60 dB bright red (very strong).
 * A smooth gradient inside each zone.
 */
private val S_COLORS = listOf(
    0xFF4FC3E0, 0xFF52D4C0, 0xFF5BDFA0, 0xFF72E688, 0xFF96EA74,   // S1-S5
    0xFFD4E650, 0xFFF2DE3E, 0xFFFFCF33,                           // S6-S8
    0xFFFF9A2E,                                                   // S9
    0xFFFF5A3C, 0xFFFF4636, 0xFFFF3A33, 0xFFFF3030, 0xFFFF2A2A, 0xFFFF2424,   // +10 ... +60
).map { Color(it) }

fun sMeterColor(segment: Int): Color = S_COLORS[(segment - 1).coerceIn(0, S_COLORS.size - 1)]

/**
 * Bar of 9 S segments and 6 above S9, coloured by intensity, with the reading in S units ("S7", "S9+20") next to it. A gap
 * and a white tick mark the step from S9 to the dB above it; unlit segments keep a faint tint so the scale is always seen.
 */
@Composable
fun SMeterBar(reading: SMeter.Reading?, modifier: Modifier = Modifier, extra: String? = null) {
    val lit = reading?.segments ?: 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(9f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    for (i in 1..9) SMeterSegment(i, lit, Modifier.weight(1f))
                }
                Box(Modifier.padding(horizontal = 3.dp).width(2.dp).height(16.dp).clip(RoundedCornerShape(1.dp)).background(Alp.Text.copy(alpha = 0.8f)))
                Row(Modifier.weight(6f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    for (i in 10..SMeter.SEGMENTS) SMeterSegment(i, lit, Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 1.dp)) {
                Row(Modifier.weight(9f)) {
                    for (t in listOf("1", "3", "5", "7", "9")) Text(t, Modifier.weight(if (t == "9") 1f else 2f), fontFamily = BarlowCondensed, fontSize = 10.sp,
                        color = if (t == "9") sMeterColor(9) else Alp.Faint)
                }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(6f)) {
                    for (t in listOf("+20", "+40", "+60")) Text(t, Modifier.weight(1f), fontFamily = BarlowCondensed, fontSize = 10.sp, color = Alp.Faint,
                        textAlign = TextAlign.End)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(reading?.label ?: "S—", fontFamily = TechMono, fontSize = 18.sp, color = if (reading == null || lit == 0) Alp.Off else sMeterColor(lit))
            if (extra != null) Text(extra, fontFamily = BarlowCondensed, fontSize = 12.sp, color = Alp.Muted)
        }
    }
}

@Composable
private fun SMeterSegment(i: Int, lit: Int, modifier: Modifier) {
    val c = sMeterColor(i)
    Box(modifier.height(if (i <= 9) 10.dp else 13.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= lit) c else c.copy(alpha = 0.15f)))
}

// ------------------------------------------------------------------ compass

/**
 * Compass rose. [headingUp]: degrees the top of the dial points to (0 = north up). [targetDeg]: bearing drawn in amber;
 * [rotorDeg]: where the rotor points, in cyan; [ownCourse]: own course, the green arrow in the middle.
 */
@Composable
fun CompassRose(modifier: Modifier, headingUp: Double = 0.0, targetDeg: Double? = null, rotorDeg: Double? = null, ownCourse: Double? = null) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Alp.Bg, r, c)
        drawCircle(Alp.KeyBorder, r, c, style = Stroke(2.dp.toPx()))
        drawCircle(Alp.SubBorder, r * 0.65f, c, style = Stroke(1.dp.toPx()))
        rotate(-headingUp.toFloat(), c) {
            for (a in 0 until 360 step 10) {
                val len = if (a % 90 == 0) r * 0.13f else if (a % 30 == 0) r * 0.09f else r * 0.05f
                val rad = Math.toRadians(a.toDouble())
                val o = Offset(c.x + (r * sin(rad)).toFloat(), c.y - (r * cos(rad)).toFloat())
                val i = Offset(c.x + ((r - len) * sin(rad)).toFloat(), c.y - ((r - len) * cos(rad)).toFloat())
                drawLine(Alp.Off, o, i, if (a % 30 == 0) 2.dp.toPx() else 1.dp.toPx())
            }
            for ((t, a) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                val rad = Math.toRadians(a)
                val p = Offset(c.x + (r * 0.74f * sin(rad)).toFloat(), c.y - (r * 0.74f * cos(rad)).toFloat())
                val style = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = (if (t == "N") 18 else 15).sp,
                    color = if (t == "N") Alp.Red else Alp.Muted)
                val l = measurer.measure(t, style)
                rotate(headingUp.toFloat(), p) { drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f)) }
            }
            fun ray(deg: Double, color: Color, dot: Boolean) {
                val rad = Math.toRadians(deg)
                val e = Offset(c.x + (r * 0.86f * sin(rad)).toFloat(), c.y - (r * 0.86f * cos(rad)).toFloat())
                drawLine(color, c, e, 3.dp.toPx(), StrokeCap.Round)
                if (dot) drawCircle(color, 7.dp.toPx(), e)
            }
            rotorDeg?.let { ray(it, Alp.Cyan, false) }
            targetDeg?.let { ray(it, Alp.Amber, true) }
            ownCourse?.let { crs ->
                rotate(crs.toFloat(), c) {
                    val s = r * 0.14f
                    val p = Path().apply {
                        moveTo(c.x, c.y - s); lineTo(c.x + s * 0.7f, c.y + s * 0.85f); lineTo(c.x, c.y + s * 0.45f); lineTo(c.x - s * 0.7f, c.y + s * 0.85f); close()
                    }
                    drawPath(p, Alp.Green)
                }
            }
        }
        if (ownCourse == null) drawCircle(Alp.Green, 5.dp.toPx(), c)
    }
}

/**
 * Rotor dial, the same style as sat-aprs (EquipoRotor.tsx), the Display and the rotor box: ticks every 5 degrees (long every
 * 30, medium every 10), numbers every 30, red N; blue two-tone needle with counterweight (turns the short way, 1.4 s);
 * target in amber (dotted line and index on the rim); heading of a moving station in green; mechanical stops in red.
 */
@Composable
fun RotorDial(modifier: Modifier, azDeg: Double?, targetDeg: Double? = null, headingDeg: Double? = null, minAz: Double = 0.0, maxAz: Double = 360.0) {
    val measurer = rememberTextMeasurer()
    // accumulated angle: past north the needle keeps turning the short way instead of going all the way round
    var acc by remember { mutableFloatStateOf(Float.NaN) }
    azDeg?.let { az -> acc = if (acc.isNaN()) az.toFloat() else acc + ((((az.toFloat() - acc) % 360f) + 540f) % 360f) - 180f }
    val shown by animateFloatAsState(if (acc.isNaN()) 0f else acc, tween(1400, easing = LinearEasing), label = "rotor")
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val s = size.minDimension / 220f
        fun at(deg: Float, r: Float, side: Float = 0f): Offset {
            val t = Math.toRadians(deg.toDouble()); val ux = sin(t).toFloat(); val uy = -cos(t).toFloat()
            return Offset(c.x + (r * ux - side * uy) * s, c.y + (r * uy + side * ux) * s)
        }
        fun tri(a: Offset, b: Offset, d: Offset) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); close() }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF16283A), Color(0xFF060B14)), center = Offset(c.x, c.y - 0.08f * 106 * s), radius = 143 * s), 106 * s, c)
        drawCircle(Color(0xFF334155), 106 * s, c, style = Stroke(2 * s))
        drawCircle(Color(0xFF1E293B), 62 * s, c, style = Stroke(1 * s))
        if (maxAz - minAz < 360) {                                  // dead zone between the stops
            drawArc(Color(0xB3F87171), (maxAz - 90).toFloat(), (360 - (maxAz - minAz)).toFloat(), false,
                topLeft = Offset(c.x - 104 * s, c.y - 104 * s), size = androidx.compose.ui.geometry.Size(208 * s, 208 * s), style = Stroke(4 * s))
        }
        for (d in 0 until 360 step 5) {
            val major = d % 30 == 0; val mid = d % 10 == 0
            drawLine(Color(if (major) 0xFFE2E8F0 else if (mid) 0xFF94A3B8 else 0xFF475569), at(d.toFloat(), if (major) 90f else if (mid) 94f else 97f),
                at(d.toFloat(), 103f), (if (major) 2f else 1f) * s, StrokeCap.Round)
        }
        for (d in 0 until 360 step 30) {
            val card = when (d) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> null }
            val style = if (card != null) TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = (17 * s).toSp(),
                color = if (d == 0) Color(0xFFF87171) else Color(0xFFF1F5F9))
            else TextStyle(fontFamily = TechMono, fontSize = (11 * s).toSp(), color = Color(0xFF94A3B8))
            val l = measurer.measure(card ?: d.toString(), style)
            val p = at(d.toFloat(), if (card != null) 76f else 78f)
            drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f))
        }
        headingDeg?.let { h ->
            val hf = h.toFloat()
            drawPath(tri(at(hf, 60f), at(hf, 48f, -6f), at(hf, 48f, 6f)), Color(0xFFA7F3D0), style = Stroke(1.5f * s, join = StrokeJoin.Round))
            drawLine(Color(0x80A7F3D0), at(hf, 48f), at(hf, 16f), 1 * s)
        }
        targetDeg?.let { t ->
            val tf = t.toFloat()
            drawLine(Color(0xFFFBBF24), at(tf, 14f), at(tf, 88f), 1.5f * s, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(3 * s, 5 * s)))
            val p = tri(at(tf, 91f), at(tf, 105f, -7f), at(tf, 105f, 7f))
            drawPath(p, Color(0xFFFBBF24)); drawPath(p, Color(0xFF060B14), style = Stroke(1 * s))
        }
        if (azDeg != null) {
            val a = shown
            drawPath(tri(at(a, 0f, -5f), at(a, -34f), at(a, 0f, 5f)), Color(0xFF475569))
            val tip = tri(at(a, 0f, -7f), at(a, 86f), at(a, 0f, 7f))
            drawPath(tip, Brush.linearGradient(listOf(Color(0xFF7DD3FC), Color(0xFF38BDF8), Color(0xFF0284C7)), at(a, 0f, -7f), at(a, 0f, 7f)))
            drawPath(tip, Color(0xFFE0F2FE), style = Stroke(0.6f * s, join = StrokeJoin.Round))
            drawLine(Color(0x99E0F2FE), at(a, 6f), at(a, 80f), 0.8f * s)
        }
        drawCircle(Color(0xFF0F172A), 9 * s, c)
        drawCircle(Color(0xFF7DD3FC), 9 * s, c, style = Stroke(2 * s))
        drawCircle(Color(0xFF7DD3FC), 3 * s, c)
    }
}

/** Small arrow pointing to a bearing (for lists). */
@Composable
fun BearingArrow(deg: Double?, color: Color = Alp.Cyan, size: Dp = 16.dp) {
    if (deg == null) { Spacer(Modifier.width(size)); return }
    Canvas(Modifier.size(size).rotate(deg.toFloat())) {
        val w = this.size.width
        drawLine(color, Offset(w / 2, w * 0.85f), Offset(w / 2, w * 0.15f), 2.4.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * 0.25f, w * 0.42f), Offset(w / 2, w * 0.15f), 2.4.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * 0.75f, w * 0.42f), Offset(w / 2, w * 0.15f), 2.4.dp.toPx(), StrokeCap.Round)
    }
}

// ------------------------------------------------------------------ own position, formats, clock

object Own {
    /** Own position: the tracker's GNSS with a fix, else the locator of Spotting (by hand or the phone's last position). */
    fun position(): Pair<Double, Double>? {
        AppState.gnss.value?.let { g -> if (g.fix && g.lat != null && g.lon != null) return g.lat!! to g.lon!! }
        return try { RotorFollow.locatorToLatLon(Spotter.locator()) } catch (_: Exception) { null }
    }

    fun to(lat: Double?, lon: Double?): Pair<Double, Double>? {
        val me = position() ?: return null
        if (lat == null || lon == null) return null
        return Geo.distanceKm(me.first, me.second, lat, lon) to Geo.bearingDeg(me.first, me.second, lat, lon)
    }
}

object Fmt {
    /** Unit system chosen in the side menu (Settings.units): 0 metric, 1 nautical, 2 imperial. */
    @Volatile var units: Int = 0

    /** A distance given in km, in the chosen units. */
    fun km(km: Double?): String {
        if (km == null) return "—"
        val (k, label) = es.ea7jrs.aprslorap.core.WxCalc.distFactor(units)
        val v = km * k
        return if (v < 100) String.format(Locale.US, "%.1f %s", v, label) else String.format(Locale.US, "%,d %s", Math.round(v), label)
    }

    /** A speed given in km/h, in the chosen units. */
    fun speed(kmh: Double?): String {
        if (kmh == null) return "—"
        val (k, label) = es.ea7jrs.aprslorap.core.WxCalc.speedFactor(units)
        return String.format(Locale.US, "%.0f %s", kmh * k, label)
    }

    /** A temperature given in °C, in the chosen units. */
    fun temp(c: Double?, decimals: Int = 1, unit: Boolean = true): String {
        if (c == null) return "—"
        return String.format(Locale.US, "%.${decimals}f", es.ea7jrs.aprslorap.core.WxCalc.tempShown(c, units)) + if (unit) " " + es.ea7jrs.aprslorap.core.WxCalc.tempLabel(units) else "°"
    }

    fun deg(d: Double?): String = if (d == null) "—" else String.format(Locale.US, "%03d°", Math.round(d) % 360)

    fun ago(ms: Long?): String {
        if (ms == null || ms <= 0) return "—"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    fun hhmm(ms: Long, utc: Boolean = false): String {
        val c = Calendar.getInstance(if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()).apply { timeInMillis = ms }
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** "433.775" (MHz with kHz), and the hundreds of Hz apart for the small digits. */
    fun mhz(hz: Long): String = String.format(Locale.US, "%d.%03d", hz / 1_000_000, (hz / 1000) % 1000)
    fun subKhz(hz: Long): String = String.format(Locale.US, "%02d", (hz % 1000) / 10)

    fun latLon(lat: Double, isLat: Boolean): String {
        val a = Math.abs(lat)
        val d = a.toInt()
        val m = (a - d) * 60
        val h = if (isLat) (if (lat >= 0) "N" else "S") else (if (lat >= 0) "E" else "W")
        return if (isLat) String.format(Locale.US, "%02d°%05.2f′ %s", d, m, h) else String.format(Locale.US, "%03d°%05.2f′ %s", d, m, h)
    }
}

/** Current time, refreshed every second while on screen. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { now = System.currentTimeMillis(); delay(periodMs - now % periodMs) } }
    return now
}

/** Small round status light. */
@Composable
fun Lamp(on: Boolean, color: Color = Alp.Green) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) color else Alp.Off))
}

/** Light of the band header (TX, RX, MSG, EMG): glows when on, [halo] = white ring (the tracker's flash). */
@Composable
fun RadioLed(label: String, on: Boolean, color: Color, halo: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) color.copy(alpha = 0.35f) else Color.Transparent), contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (on) color else Alp.Off)
                .let { if (on && halo) it.border(1.5.dp, Color.White, CircleShape) else it })
        }
        Text(label, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 10.sp, color = if (on) color else Alp.Muted)
    }
}

/** Text with a label above, for the data tiles. */
@Composable
fun DataTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Alp.Text, unit: String? = null) {
    Panel(modifier, padding = 10.dp) {
        SectionLabel(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = TechMono, fontSize = 22.sp, color = valueColor, maxLines = 1)
            if (unit != null) Text(" $unit", fontSize = 13.sp, color = Alp.Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}
