/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.graphics.Bitmap
import es.ea7jrs.aprslorap.core.Apt

/**
 * NOAA APT image decoder on the app receiver (137 MHz, NFM). Lines from Apt.Decoder are drawn, grey, into a growing bitmap
 * (both video channels A and B, 2080 px wide). Fed from the FM discriminator tap, like the packet decoders.
 */
object AptRunner {
    private const val MAX_LINES = 1600                                       // ~13 min of a pass
    @Volatile var active = false
        private set
    @Volatile var bitmap: Bitmap? = null
        private set
    @Volatile private var dec: Apt.Decoder? = null
    private var row = 0

    @Synchronized
    fun start() {
        val b = Bitmap.createBitmap(Apt.WORDS_PER_LINE, MAX_LINES, Bitmap.Config.ARGB_8888)
        bitmap = b; row = 0
        dec = Apt.Decoder(48_000.0) { line -> onLine(line) }
        active = true
        AppState.aptStatus.value = "Listening for NOAA APT…"
    }

    private fun onLine(line: IntArray) {
        val b = bitmap ?: return
        if (row >= MAX_LINES) return                                         // full: keep the first pass, ignore the rest
        val px = IntArray(line.size) { val v = line[it] and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        synchronized(b) { b.setPixels(px, 0, line.size, 0, row, line.size, 1) }
        row++
        AppState.aptVersion.value = AppState.aptVersion.value + 1
        AppState.aptStatus.value = "NOAA APT: $row line(s)"
    }

    /** Discriminator (NFM audio) samples from the receiver thread. */
    fun feed(x: FloatArray, n: Int) { dec?.process(x, n) }

    @Synchronized
    fun stop() {
        active = false; dec = null
        if (AppState.aptStatus.value != "Off") AppState.aptStatus.value = "Off"
    }
}
