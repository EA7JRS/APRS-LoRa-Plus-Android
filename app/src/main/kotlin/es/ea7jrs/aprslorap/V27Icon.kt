/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

// The V-27 road warning beacon of the ROAD EMERGENCY alert (panel 18 of the design canvas, approved by Manuel 2026-11-15):
// own drawing, not the official image: a red warning triangle with "!" and three radio waves, on a white tile.

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object V27 {
    private const val RED = 0xFFE5202A.toInt()
    private const val INK = 0xFF131A22.toInt()

    /** Draws the sign in a square of [s] pixels at ([x], [y]) (drawing units: 50 × 50). */
    fun draw(c: android.graphics.Canvas, x: Float, y: Float, s: Float) {
        val k = s / 50f
        fun px(v: Float) = x + v * k
        fun py(v: Float) = y + (v + 3f) * k
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tri = Path().apply { moveTo(px(22f), py(9f)); lineTo(px(4.5f), py(40f)); lineTo(px(39.5f), py(40f)); close() }
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawPath(tri, p)
        p.style = Paint.Style.STROKE; p.color = RED; p.strokeWidth = 5f * k; p.strokeJoin = Paint.Join.ROUND; c.drawPath(tri, p)
        p.color = INK; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 3.6f * k
        c.drawLine(px(22f), py(20.5f), px(22f), py(30f), p)
        p.style = Paint.Style.FILL; c.drawCircle(px(22f), py(35f), 2.1f * k, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * k
        for (r in floatArrayOf(5.5f, 10.5f, 15.5f)) {
            c.drawArc(RectF(px(31f - r), py(15f - r), px(31f + r), py(15f + r)), -85f, 75f, false, p)
        }
    }

    /** The sign on a white disc, for the notification avatar (phone and Android Auto). */
    fun bitmap(px: Int = 128): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        draw(c, px * 0.17f, px * 0.15f, px * 0.68f)
        return b
    }
}

/** The sign on a white rounded tile, for the header of the ROAD EMERGENCY window. */
@Composable
fun V27Tile(size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(Color.White), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.82f)) {
            drawIntoCanvas { V27.draw(it.nativeCanvas, 0f, 0f, this.size.width) }
        }
    }
}
