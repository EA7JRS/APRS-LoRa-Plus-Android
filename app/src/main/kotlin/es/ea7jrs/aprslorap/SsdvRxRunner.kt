/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * Receives SSDV images with the phone's SDR. The receiver's 12 kHz audio is demodulated as 2-FSK (SsdvFsk), the 256-byte SSDV
 * packets are collected per image, and the native decoder (libssdvdec.so, fsphil ssdv) reconstructs the JPEG, shown in the SDR
 * tab. Works with the tracker's SSDV-FSK mode (SSDV over LoRa cannot be demodulated with an RTL-SDR).
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Ssdv
import es.ea7jrs.aprslorap.core.SsdvFsk
import java.io.File
import java.util.concurrent.Executors

object SsdvRxRunner {
    @Volatile private var running = false
    @Volatile private var fsk: SsdvFsk? = null
    private val packets = ArrayList<ByteArray>()
    private var imageId = -1
    private var app: Context? = null
    private val worker = Executors.newSingleThreadExecutor()

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libssdvdec.so")
    fun available(ctx: Context): Boolean = binary(ctx).canExecute()

    @Synchronized
    fun start(ctx: Context, baud: Double, markHz: Double, spaceHz: Double, invert: Boolean) {
        app = ctx.applicationContext
        if (!available(ctx)) { AppState.ssdvSdrStatus.value = "This build has no SSDV decoder for this phone"; return }
        fsk = SsdvFsk(sampleRate = Dsp.NARROW_AUDIO_RATE, baud = baud, markHz = markHz, spaceHz = spaceHz, invert = invert)
        synchronized(packets) { packets.clear() }
        imageId = -1
        running = true
        AppState.ssdvSdrRunning.value = true
        AppState.ssdvSdrStatus.value = "Listening for SSDV (FSK)…"
    }

    @Synchronized
    fun stop() {
        running = false
        fsk = null
        AppState.ssdvSdrRunning.value = false
        AppState.ssdvSdrStatus.value = "Off"
    }

    /** Receiver audio (12 kHz, ±1), from the receiver thread. */
    fun feedReceiver12k(x: FloatArray, n: Int) {
        if (!running) return
        val f = fsk ?: return
        val got = f.feed(x, n)
        if (got.isEmpty()) return
        for (p in got) onPacket(p)
    }

    private fun onPacket(p: ByteArray) {
        val id = p[6].toInt() and 0xFF                       // header: byte 6 = image ID
        val eoi = (p[11].toInt() and 0x04) != 0              // byte 11 bit 2 = end of image
        synchronized(packets) {
            if (id != imageId) { packets.clear(); imageId = id }
            packets.add(p)
            if (packets.size > 2000) packets.removeAt(0)
            AppState.ssdvSdrStatus.value = "SSDV image $id · ${packets.size} packet(s)"
        }
        if (eoi) decodeNow()
    }

    /** Decode the packets collected so far into a JPEG (also useful before the end-of-image packet arrives). */
    fun decodeNow() {
        val ctx = app ?: return
        val snapshot = synchronized(packets) { if (packets.isEmpty()) return else packets.toList() }
        worker.execute {
            try {
                val pkFile = File(ctx.filesDir, "ssdv_sdr_packets.bin")
                pkFile.outputStream().use { os -> for (p in snapshot) os.write(p, 0, Ssdv.PKT_SIZE) }
                val outFile = File(ctx.filesDir, "ssdv_sdr.jpg")
                outFile.delete()
                val pr = ProcessBuilder(listOf(binary(ctx).absolutePath, pkFile.absolutePath, outFile.absolutePath))
                    .directory(ctx.filesDir).redirectErrorStream(false).start()
                val err = pr.errorStream.bufferedReader().readText().trim()
                pr.waitFor()
                if (outFile.exists() && outFile.length() > 0) {
                    AppState.ssdvSdrImage.value = outFile.readBytes()
                    AppState.ssdvSdrStatus.value = "Decoded: ${err.ifBlank { "image ready" }}"
                } else {
                    AppState.ssdvSdrStatus.value = "No image yet (${err.ifBlank { "need more packets" }})"
                }
            } catch (e: Exception) {
                AppState.ssdvSdrStatus.value = "Decoder error: ${e.message}"
            }
        }
    }
}
