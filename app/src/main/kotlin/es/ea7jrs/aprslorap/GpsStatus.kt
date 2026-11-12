/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.GnssSky
import es.ea7jrs.aprslorap.core.PhoneMath
import es.ea7jrs.aprslorap.core.SkySat
import es.ea7jrs.aprslorap.core.SkyView
import es.ea7jrs.aprslorap.core.Spots
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val GpsLabel = Color(0xFFE6A532)     // labels in amber, values in white (as GNSS status apps)
private val RingColor = Color(0xFFC9962A)

private fun sysColor(code: Char) = Color(GnssSky.system(code).argb)

/** DISP > GPS: sky plot, signal bars and data of the tracker's receiver or of the phone's own GNSS. */
@Composable
fun GpsStatusPage() {
    val ctx = LocalContext.current
    var source by rememberSaveable { mutableStateOf("TRACKER") }
    var headingUp by rememberSaveable { mutableStateOf(true) }
    val control by AppState.controlReady.collectAsState()
    val gnss by AppState.gnss.collectAsState()
    val trackerSky by AppState.sky.collectAsState()
    val sensors by AppState.sensors.collectAsState()
    val phone by AppState.phoneGnss.collectAsState()
    val phoneSensors by AppState.phoneSensors.collectAsState()
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { PhoneGnss.release(); PhoneGnss.use(ctx) } else AppState.error.value = "Location permission is needed for the phone GNSS"
    }

    DisposableEffect(source, control) {
        if (source == "TRACKER" && control) {
            AppState.controller?.control("sky")
            AppState.controller?.control("sub", "sky" to 3, "gnss" to 1)
        }
        if (source == "PHONE") {
            if (!PhoneGnss.granted(ctx)) askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            PhoneGnss.use(ctx)
        }
        onDispose {
            if (source == "TRACKER" && control) AppState.controller?.control("sub", "sky" to 0, "gnss" to 5)
            if (source == "PHONE") PhoneGnss.release()
        }
    }

    val isPhone = source == "PHONE"
    val sky: SkyView = if (isPhone) phone.sky else trackerSky ?: SkyView(emptyList(), hdop = gnss?.hdop, usedInFix = gnss?.satellites)
    val course = if (isPhone) phone.bearingDeg else gnss?.courseDeg
    val speed = if (isPhone) phone.speedKmh else gnss?.speedKmh
    val heading: Double? = if (isPhone) phoneSensors.headingDeg ?: phone.bearingDeg else sensors?.headingDeg ?: course?.takeIf { (speed ?: 0.0) > 3 }
    val up = if (headingUp) heading ?: 0.0 else 0.0

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("TRACKER", !isPhone, height = 32.dp) { source = "TRACKER" }
        Chip("PHONE", isPhone, height = 32.dp) { source = "PHONE" }
        Spacer(Modifier.weight(1f))
        Chip(if (headingUp) "HDG-UP" else "N-UP", headingUp, color = Alp.Green, height = 32.dp) { headingUp = !headingUp }
    }

    Panel(Modifier.fillMaxWidth(), bg = Alp.SkyBg, border = Alp.PanelBorder, padding = 10.dp) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(if (isPhone) "Course (GPS)" else "Course (tracker)", color = GpsLabel, fontSize = 15.sp)
                Text(course?.let { String.format(Locale.US, "%.0f°", it) } ?: "—", color = Alp.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Orientation", color = GpsLabel, fontSize = 15.sp)
                Text(heading?.let { cardinalText(it) } ?: "—", color = Alp.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        SkyPlot(sky, up, heading, Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(sky.errorM?.let { String.format(Locale.US, "%.1f", it) } ?: "—", color = Alp.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("Error (m)", color = GpsLabel, fontSize = 15.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${sky.used}/${if (sky.inView > 0) sky.inView else "—"}", color = Alp.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("Used/Sats", color = GpsLabel, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        SignalBars(sky.sats, Modifier.fillMaxWidth().height(56.dp))
        Legend(sky.sats)
        if (sky.sats.any { it.bands != null }) {                // tracker with NMEA 4.10+ (UC6580...): satellites per band
            val l1 = sky.sats.count { it.l1 }
            val l5 = sky.sats.count { it.l5 }
            val other = sky.sats.count { (it.bands ?: 0) and 4 != 0 }
            Text(listOfNotNull("L1 $l1", if (l5 > 0) "L5 $l5" else null, if (other > 0) "L2/E5b/E6 $other" else null).joinToString("   "),
                color = Alp.SubText, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (!isPhone && trackerSky == null) Text(
            if (control) "Waiting for the satellite list... (tracker firmware with the `sky` command)" else "Connect the tracker to see its satellites, or choose PHONE.",
            color = Alp.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (isPhone && phone.status != "Phone GNSS on") Text(phone.status, color = Alp.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }

    // data grid
    val cells: List<Pair<String, String>> = if (isPhone) {
        val p = phoneSensors
        listOf(
            "Tilt/Roll (°)" to (if (p.pitchDeg != null) String.format(Locale.US, "%.0f/%.0f", p.pitchDeg, p.rollDeg ?: 0.0) else "—"),
            "Mag (µT)/Decl" to listOfNotNull(phone.magUT?.let { String.format(Locale.US, "%.0f", it) }, p.declinationDeg?.let { String.format(Locale.US, "%.0f°", it) }).joinToString("/").ifEmpty { "—" },
            "Accel (g)" to (phone.accelG?.let { String.format(Locale.US, "%.2f", it) } ?: "—"),
            "Speed (km/h)" to (phone.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            (if (phone.altitudeMsl) "Altitude (m) MSL" else "Altitude (m)") to (phone.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            "Last pos. (UTC)" to (phone.fixTimeMs?.let { utcClock(it) } ?: "—"),
            "Latitude" to (phone.lat?.let { latText(it, true) } ?: "—"),
            "Longitude" to (phone.lon?.let { latText(it, false) } ?: "—"),
            "Locator" to (if (phone.lat != null && phone.lon != null) Spots.locator(phone.lat!!, phone.lon!!, 6) else "—"),
            (listOfNotNull(phone.battTempC?.let { String.format(Locale.US, "%.0f°C", it) }, phone.battV?.let { String.format(Locale.US, "%.2fV", it) })
                .joinToString(" ").let { if (it.isEmpty()) "Battery" else "Bat. ($it)" }) to (phone.battPct?.let { "$it%" } ?: "—"),
            "DOP/HDOP/VDOP" to GnssSky.dopText(sky, null),
            "Light (lux)" to (phone.lux?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            "Pressure (hPa)" to (p.pressureHpa?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            "Rotation (°/s)" to (phone.gyroDps?.let { String.format(Locale.US, "%03.0f", it) } ?: "—"),
            "Heading (°)" to (p.headingDeg?.let { String.format(Locale.US, "%03.0f", it) } ?: "—"),
        )
    } else {
        val s = sensors
        val g = gnss
        val acc = s?.accelG?.takeIf { it.size >= 3 }
        val tilt = acc?.let { PhoneMath.tilt(it[0] * 9.80665, it[1] * 9.80665, it[2] * 9.80665) }   // the tracker gives g
        val accelMag = acc?.let { kotlin.math.abs(sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) - 1.0) }
        val gyroMag = s?.gyroDps?.takeIf { it.size >= 3 }?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
        listOf(
            "Tilt/Roll (°)" to (tilt?.let { String.format(Locale.US, "%.0f/%.0f", it.first, it.second) } ?: "—"),
            "Heading (mag)" to (s?.headingDeg?.let { String.format(Locale.US, "%03.0f°", it) } ?: "—"),
            "Accel (g)" to (accelMag?.let { String.format(Locale.US, "%.2f", it) } ?: "—"),
            "Speed (km/h)" to (g?.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            "Altitude (m) MSL" to (g?.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            "Last pos. (UTC)" to (g?.utc?.substringAfter('T')?.removeSuffix("Z") ?: "—"),
            "Latitude" to (g?.lat?.let { latText(it, true) } ?: "—"),
            "Longitude" to (g?.lon?.let { latText(it, false) } ?: "—"),
            "Locator" to (if (g?.lat != null && g.lon != null) Spots.locator(g.lat!!, g.lon!!, 6) else "—"),
            (listOfNotNull(s?.boardTemperatureC?.let { String.format(Locale.US, "%.0f°C", it) }, (s?.batteryV)?.let { String.format(Locale.US, "%.2fV", it) })
                .joinToString(" ").let { if (it.isEmpty()) "Battery" else "Bat. ($it)" }) to (s?.batteryPct?.let { "$it%" } ?: "—"),
            "DOP/HDOP/VDOP" to GnssSky.dopText(sky, g?.hdop),
            "Air (IAQ)" to (s?.iaq?.let { String.format(Locale.US, "%.0f", it) } ?: "—"),
            "Pressure (hPa)" to (s?.pressureHpa?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
            "Rotation (°/s)" to (gyroMag?.let { String.format(Locale.US, "%03.0f", it) } ?: "—"),
            "Temp (°C)" to (s?.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "—"),
        )
    }
    Panel(Modifier.fillMaxWidth(), bg = Alp.SkyBg, padding = 8.dp) {
        for (row in cells.chunked(3)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                for ((label, value) in row) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = GpsLabel, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
                    Text(value, color = Alp.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun cardinalText(deg: Double): String = Geo.cardinal16(deg).let { c -> if (c.length == 3) c[0] + "-" + c.substring(1) else c }

private fun utcClock(ms: Long): String {
    val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
    return String.format(Locale.US, "%02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
}

private fun latText(v: Double, lat: Boolean) = String.format(Locale.US, "%.6f°%s", kotlin.math.abs(v), if (lat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W"))

/**
 * Sky plot: horizon, 30° and 60° rings; the rose turned so that [upDeg] is at the top; compass needle (red north) when the
 * heading is known; satellites by elevation and azimuth, shape by constellation (GPS circle, Galileo cross, GLONASS square,
 * BeiDou X, others triangle), filled when used in the fix, colour by signal (green strong, yellow medium, red weak).
 */
@Composable
private fun SkyPlot(sky: SkyView, upDeg: Double, heading: Double?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.86f
        val c = Offset(size.width / 2f, size.height / 2f)
        val ring = Stroke(1.6.dp.toPx())
        drawCircle(RingColor, r, c, style = ring)
        drawCircle(RingColor, r * 2f / 3f, c, style = ring)
        drawCircle(RingColor, r / 3f, c, style = ring)
        for ((t, k) in listOf("30°" to 2f / 3f, "60°" to 1f / 3f)) {
            val l = tm.measure(t, TextStyle(color = RingColor, fontSize = 11.sp))
            drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y + r * k + 2.dp.toPx()))
        }
        rotate(-upDeg.toFloat(), c) {
            // ticks every 15°, longer every 45°; the E-W line
            for (a in 0 until 360 step 15) {
                if (a % 45 == 0) continue                    // the 8 directions have their letters instead
                val rad = Math.toRadians(a.toDouble())
                drawLine(RingColor, at(c, r, rad), at(c, r * 0.94f, rad), 2.dp.toPx())
            }
            drawLine(RingColor.copy(alpha = 0.6f), at(c, r, Math.toRadians(90.0)), at(c, r, Math.toRadians(270.0)), 1.dp.toPx())
            for ((t, a) in listOf("N" to 0, "NE" to 45, "E" to 90, "SE" to 135, "S" to 180, "SW" to 225, "W" to 270, "NW" to 315)) {
                val p = at(c, r * 1.1f, Math.toRadians(a.toDouble()))
                val l = tm.measure(t, TextStyle(color = Alp.Text, fontSize = if (t.length == 1) 17.sp else 13.sp, fontWeight = FontWeight.Bold))
                rotate(a.toFloat(), p) { drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y - l.size.height / 2f)) }
            }
            if (heading != null) {
                // compass needle: red half to the north, blue half to the south
                val n = at(c, r * 0.98f, 0.0); val s = at(c, r * 0.98f, Math.PI)
                val w = 9.dp.toPx()
                drawPath(Path().apply { moveTo(n.x, n.y); lineTo(c.x + w, c.y); lineTo(c.x - w, c.y); close() }, Color(0xFFE53935))
                drawPath(Path().apply { moveTo(s.x, s.y); lineTo(c.x + w, c.y); lineTo(c.x - w, c.y); close() }, Color(0xFF1E88E5))
                drawCircle(Color(0xFFE53935), 5.dp.toPx(), n)
                drawCircle(Color(0xFF42A5F5), 5.dp.toPx(), s)
            }
        }
        // direction the top of the plot points to
        val tri = 9.dp.toPx()
        drawPath(Path().apply { moveTo(c.x, c.y - r - tri * 1.6f); lineTo(c.x + tri, c.y - r - 2f); lineTo(c.x - tri, c.y - r - 2f); close() }, Color(0xFF00C853))
        // satellites
        for (s in sky.sats) {
            val el = s.elevationDeg ?: continue
            val az = s.azimuthDeg ?: continue
            val (x, y) = GnssSky.plot(el, az, upDeg)
            val p = Offset(c.x + (x * r).toFloat(), c.y + (y * r).toFloat())
            val col = when (GnssSky.strength(s.snr)) { 2 -> Color(0xFF00E676); 1 -> Color(0xFFFFEB3B); 0 -> Color(0xFFFF7043); else -> Color(0xFF757575) }
            satMark(s, p, col, 7.dp.toPx())
            val l = tm.measure(GnssSky.label(s), TextStyle(color = sysColor(s.system), fontSize = 13.sp))
            drawText(l, topLeft = Offset(p.x + 9.dp.toPx(), p.y - l.size.height / 2f))
        }
    }
}

private fun at(c: Offset, r: Float, rad: Double) = Offset(c.x + (r * sin(rad)).toFloat(), c.y - (r * cos(rad)).toFloat())

private fun DrawScope.satMark(s: SkySat, p: Offset, col: Color, k: Float) {
    val fill = s.used
    val st = Stroke(2.2.dp.toPx())
    when (s.system) {
        'G' -> if (fill) drawCircle(col, k, p) else drawCircle(col, k * 0.9f, p, style = st)
        'R' -> if (fill) drawRect(col, Offset(p.x - k * 0.85f, p.y - k * 0.85f), Size(k * 1.7f, k * 1.7f))
               else drawRect(col, Offset(p.x - k * 0.8f, p.y - k * 0.8f), Size(k * 1.6f, k * 1.6f), style = st)
        'E' -> {
            val w = if (fill) 3.dp.toPx() else 2.dp.toPx()
            drawLine(col, Offset(p.x - k, p.y), Offset(p.x + k, p.y), w, StrokeCap.Square)
            drawLine(col, Offset(p.x, p.y - k), Offset(p.x, p.y + k), w, StrokeCap.Square)
        }
        'C' -> {
            val w = if (fill) 3.dp.toPx() else 2.dp.toPx()
            drawLine(col, Offset(p.x - k * 0.8f, p.y - k * 0.8f), Offset(p.x + k * 0.8f, p.y + k * 0.8f), w, StrokeCap.Square)
            drawLine(col, Offset(p.x - k * 0.8f, p.y + k * 0.8f), Offset(p.x + k * 0.8f, p.y - k * 0.8f), w, StrokeCap.Square)
        }
        else -> {
            val path = Path().apply { moveTo(p.x, p.y - k); lineTo(p.x + k, p.y + k * 0.8f); lineTo(p.x - k, p.y + k * 0.8f); close() }
            if (fill) drawPath(path, col) else drawPath(path, col, style = st)
        }
    }
}

/** One bar per satellite, height by SNR (0 ... 50 dB-Hz), colour by constellation; dim when not used in the fix. */
@Composable
private fun SignalBars(sats: List<SkySat>, modifier: Modifier) {
    val list = GnssSky.sorted(sats)
    Canvas(modifier) {
        drawLine(Color(0xFF424242), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx())
        if (list.isEmpty()) return@Canvas
        val slot = size.width / maxOf(list.size, 24)
        list.forEachIndexed { i, s ->
            val h = ((s.snr ?: 0).coerceIn(0, 50) / 50f) * (size.height - 4f)
            val x = i * slot + slot / 2f
            val col = sysColor(s.system).copy(alpha = if (s.used) 1f else 0.45f)
            drawLine(col, Offset(x, size.height - 1f), Offset(x, size.height - 1f - maxOf(h, 2f)), maxOf(slot * 0.55f, 2f))
        }
    }
}

@Composable
private fun Legend(sats: List<SkySat>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((codes, name) in listOf(listOf('G') to "GPS", listOf('E') to "Galileo", listOf('C') to "BeiDou", listOf('R') to "GLONASS", listOf('J', 'I', 'S', 'N') to "Other")) {
            val n = sats.count { it.system in codes }
            val u = sats.count { it.system in codes && it.used }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(sysColor(codes[0])))
                Text("$name $u/$n", color = if (n > 0) Alp.SubText else Alp.Faint, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
