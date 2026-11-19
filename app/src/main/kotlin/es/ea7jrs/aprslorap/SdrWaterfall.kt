/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

// Panadapter of the app's RTL-SDR receiver (Manuel, 2026-11-19): spectrum line + colour waterfall of the whole tuned band
// (~±480 kHz), a marker at the demodulated frequency, and tap / drag to tune. Classic layout (as SDR++ and SDR# show it);
// own code and colour map. Only the app receiver (RTLTCP) fills AppState.sdrSpectrum, so nothing shows for the other SDRs.

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** ARGB colour for a normalised level 0..1: black → blue → cyan → green → yellow → red → white. */
fun waterfallColor(n: Float): Int {
    val x = n.coerceIn(0f, 1f)
    val r: Float; val g: Float; val b: Float
    when {
        x < 0.20f -> { val t = x / 0.20f; r = 0f; g = 0f; b = 0.25f + 0.75f * t }                 // black → blue
        x < 0.40f -> { val t = (x - 0.20f) / 0.20f; r = 0f; g = t; b = 1f }                        // blue → cyan
        x < 0.60f -> { val t = (x - 0.40f) / 0.20f; r = 0f; g = 1f; b = 1f - t }                   // cyan → green
        x < 0.80f -> { val t = (x - 0.60f) / 0.20f; r = t; g = 1f; b = 0f }                        // green → yellow
        x < 0.95f -> { val t = (x - 0.80f) / 0.15f; r = 1f; g = 1f - t; b = 0f }                   // yellow → red
        else -> { val t = (x - 0.95f) / 0.05f; r = 1f; g = t; b = t }                              // red → white
    }
    return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

@Composable
fun SdrWaterfall(onTune: (Long) -> Unit) {
    val spec by AppState.sdrSpectrum.collectAsState()
    val s = spec ?: return
    val bins = s.row.size
    val rows = 160
    val bmp = remember { Bitmap.createBitmap(bins, rows, Bitmap.Config.ARGB_8888) }
    val pix = remember { IntArray(bins * rows) }
    val avg = remember { FloatArray(bins) { -120f } }
    val floorCeil = remember { floatArrayOf(-80f, -20f) }      // displayed dB window, slowly adapted
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(s.t) {
        val row = s.row
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in row) { if (v < mn) mn = v; if (v > mx) mx = v }
        floorCeil[0] += 0.08f * ((mn + 2f) - floorCeil[0])
        floorCeil[1] += 0.08f * ((mx + 4f) - floorCeil[1])
        if (floorCeil[1] - floorCeil[0] < 18f) floorCeil[1] = floorCeil[0] + 18f
        val fl = floorCeil[0]; val sp = (floorCeil[1] - fl).coerceAtLeast(1f)
        System.arraycopy(pix, bins, pix, 0, bins * (rows - 1))             // scroll up one row
        val base = bins * (rows - 1)
        for (i in 0 until bins) {
            pix[base + i] = waterfallColor((row[i] - fl) / sp)
            avg[i] = 0.6f * avg[i] + 0.4f * row[i]
        }
        bmp.setPixels(pix, 0, bins, 0, 0, bins, rows)
        frame++
    }

    val fl = floorCeil[0]; val sp = (floorCeil[1] - fl).coerceAtLeast(1f)
    val markerFrac = (0.5 + (s.markerHz - s.centerHz).toDouble() / s.spanHz).coerceIn(0.0, 1.0).toFloat()
    val cMHz = s.centerHz / 1e6
    val halfMHz = s.spanHz / 2e6

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(String.format(Locale.US, "%.3f", cMHz - halfMHz), fontSize = 9.sp, color = Alp.Muted)
            Text(String.format(Locale.US, "%.3f MHz", cMHz), fontSize = 9.sp, color = Alp.Muted)
            Text(String.format(Locale.US, "%.3f", cMHz + halfMHz), fontSize = 9.sp, color = Alp.Muted)
        }
        Box(Modifier.fillMaxWidth().height(230.dp)
            .pointerInput(s.centerHz, s.spanHz) {
                val tuneAt: (Offset) -> Unit = { o -> onTune(s.centerHz + ((o.x / size.width - 0.5f) * s.spanHz).toLong()) }
                detectTapGestures(onTap = tuneAt)
            }
            .pointerInput(s.centerHz, s.spanHz) {
                detectDragGestures { change, _ -> onTune(s.centerHz + ((change.position.x / size.width - 0.5f) * s.spanHz).toLong()); change.consume() }
            }) {
            frame.let {}                                                   // redraw on each new row
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width; val h = this.size.height
                val specH = h * 0.30f
                drawRect(Alp.Bg, size = this.size)
                // spectrum line (smoothed)
                val path = Path()
                for (i in 0 until bins) {
                    val x = i / (bins - 1f) * w
                    val y = specH - ((avg[i] - fl) / sp).coerceIn(0f, 1f) * (specH - 2f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Alp.Green, style = Stroke(1.5f))
                // colour waterfall below, stretched to the width
                drawImage(bmp.asImageBitmap(),
                    srcOffset = IntOffset.Zero, srcSize = IntSize(bins, rows),
                    dstOffset = IntOffset(0, specH.toInt()), dstSize = IntSize(w.toInt(), (h - specH).toInt()))
                // marker at the demodulated frequency, and a faint centre tick
                drawLine(Alp.Faint, Offset(w / 2, 0f), Offset(w / 2, specH), strokeWidth = 1f)
                drawLine(Alp.Amber, Offset(markerFrac * w, 0f), Offset(markerFrac * w, h), strokeWidth = 2f)
            }
        }
        Text("Tap or drag the panadapter to tune.", fontSize = 10.sp, color = Alp.SubMuted)
    }
}
