/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * SSDV transmit image sizes and centre-crop maths. The tracker (RadioLib SSDVClient) does the SSDV packetisation; the app
 * only has to hand it a baseline JPEG. SSDV uses 16x16 MCUs, so the width and height must be multiples of 16. Smaller
 * sizes mean fewer packets and a shorter transmission over LoRa/FSK.
 */
object Ssdv {
    data class Format(val name: String, val w: Int, val h: Int) {
        val aspect: Double get() = w.toDouble() / h
        val label: String get() = "$name ($w×$h)"
    }

    /** Sizes offered for a photo or a QSL card (all multiples of 16). */
    val FORMATS: List<Format> = listOf(
        Format("QVGA", 320, 240),
        Format("VGA", 640, 480),
        Format("QSL landscape", 480, 320),
        Format("QSL wide", 640, 384),
        Format("Square", 256, 256),
        Format("Postcard", 640, 416),
    )

    val DEFAULT: Format get() = FORMATS[0]

    /** SSDV accepts only JPEGs whose dimensions are multiples of 16 (and within a sane range). */
    fun isValid(w: Int, h: Int): Boolean = w in 16..4096 && h in 16..4096 && w % 16 == 0 && h % 16 == 0

    /** Rounds a free dimension down to the nearest valid multiple of 16 (at least 16). */
    fun round16(v: Int): Int = (v / 16 * 16).coerceAtLeast(16)

    /**
     * Centre-crop rectangle of a [srcW]x[srcH] image to the aspect ratio of [dstW]x[dstH].
     * Returns (left, top, width, height) of the area to keep before scaling to the target size.
     */
    fun centreCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return intArrayOf(0, 0, srcW, srcH)
        val dstA = dstW.toDouble() / dstH
        val srcA = srcW.toDouble() / srcH
        return if (srcA > dstA) {                       // source is wider: trim the sides
            val w = Math.round(srcH * dstA).toInt().coerceIn(1, srcW)
            intArrayOf((srcW - w) / 2, 0, w, srcH)
        } else {                                        // source is taller: trim top and bottom
            val h = Math.round(srcW / dstA).toInt().coerceIn(1, srcH)
            intArrayOf(0, (srcH - h) / 2, srcW, h)
        }
    }

    // On-air SSDV packet (UKHAS, the format the tracker / RadioLib use): 256 bytes starting with the sync byte and a type byte.
    const val PKT_SIZE = 256
    const val SYNC = 0x55
    const val TYPE_FEC = 0x66            // normal mode, RS(255,223) FEC
    const val TYPE_NOFEC = 0x67          // no-FEC mode

    /** SSDV JPEG quality index 0..7 (the tracker maps it to JPEG 13..100). */
    const val QUALITY_MIN = 0
    const val QUALITY_MAX = 7
    const val QUALITY_DEFAULT = 4

    fun qualityLabel(q: Int): String = "Q" + q.coerceIn(QUALITY_MIN, QUALITY_MAX) + when (q.coerceIn(QUALITY_MIN, QUALITY_MAX)) {
        0 -> " · smallest"
        QUALITY_MAX -> " · best"
        else -> ""
    }
}
