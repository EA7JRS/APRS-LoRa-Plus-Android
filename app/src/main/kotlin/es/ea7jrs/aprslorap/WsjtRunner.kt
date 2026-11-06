/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import es.ea7jrs.aprslorap.core.Dsp
import es.ea7jrs.aprslorap.core.Wsjt
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FT8 / FT4, WSPR and FST4W from the app receiver (USB audio, 12 kHz) or the microphone: UTC slots, each one decoded by a
 * separate program in lib/<abi>/ (ft8_lib, wsprd, the FST4W port of WSJT-X). The phone's clock must be right (Android sets
 * it from the network).
 */
object WsjtRunner {
    data class State(val running: Boolean = false, val mode: Wsjt.Mode = Wsjt.Mode.FT8, val source: ModemRunner.Source = ModemRunner.Source.RECEIVER,
                     val status: String = "Off", val dialHz: Long? = null, val rxHz: Int = Wsjt.FST4W_RX_HZ, val tolHz: Int = Wsjt.FST4W_TOL_HZ)

    @Volatile var active = false
        private set
    @Volatile private var source = ModemRunner.Source.RECEIVER
    @Volatile private var recorder: Wsjt.SlotRecorder? = null
    @Volatile private var mic: Thread? = null
    private val decoders = Executors.newSingleThreadExecutor()
    private var app: Context? = null

    fun binary(ctx: Context, mode: Wsjt.Mode = Wsjt.Mode.FT8): File = File(ctx.applicationInfo.nativeLibraryDir, mode.program)
    fun available(ctx: Context, mode: Wsjt.Mode = Wsjt.Mode.FT8) = binary(ctx, mode).canExecute()

    private fun set(f: (State) -> State) { synchronized(this) { AppState.wsjt.value = f(AppState.wsjt.value) } }

    @Synchronized
    fun start(ctx: Context, mode: Wsjt.Mode, src: ModemRunner.Source, rxHz: Int = Wsjt.FST4W_RX_HZ, tolHz: Int = Wsjt.FST4W_TOL_HZ) {
        stop()
        app = ctx.applicationContext
        if (!available(ctx, mode)) { set { it.copy(status = "This build has no ${mode.label} decoder for this phone") }; return }
        source = src
        recorder = Wsjt.SlotRecorder(mode) { start, samples -> decoders.execute { decode(mode, start, samples) } }
        active = true
        set { it.copy(running = true, mode = mode, source = src, rxHz = rxHz, tolHz = tolHz, status = "Waiting for the next ${mode.label} slot...") }
        if (src == ModemRunner.Source.MICROPHONE) mic = Thread({ micLoop() }, "wsjt-mic").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        active = false
        mic?.interrupt(); mic = null
        recorder = null
        set { it.copy(running = false, status = "Off") }
    }

    /** Receiver audio (12 kHz, ±1), from the receiver thread. */
    fun feedReceiver12k(x: FloatArray, n: Int) {
        if (!active || source != ModemRunner.Source.RECEIVER) return
        val r = recorder ?: return
        val s = ShortArray(n) { (x[it].coerceIn(-1f, 1f) * 20000f).toInt().toShort() }
        r.feed(s, n, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    private fun micLoop() {
        // 12 kHz if the phone allows it, else 48 kHz taken down to 12 kHz
        var rate = Wsjt.RATE
        var rec = try { AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 16384) } catch (_: Exception) { null }
        if (rec?.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release(); rate = 48_000
            rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 32768) } catch (_: Exception) { null }
        }
        if (rec?.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); set { it.copy(status = "The microphone cannot be opened (permission?)") }; return }
        val down = if (rate == 48_000) Dsp.RFir(Dsp.lowPass(63, 5_000.0, 48_000.0), 4) else null
        rec.startRecording()
        val buf = ShortArray(rate / 10)
        val f = FloatArray(buf.size); val o = FloatArray(buf.size)
        try {
            while (active && source == ModemRunner.Source.MICROPHONE) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                val r = recorder ?: break
                if (down == null) r.feed(buf, n, now)
                else {
                    for (i in 0 until n) f[i] = buf[i] / 32768f
                    val m = down.process(f, n, o)
                    r.feed(ShortArray(m) { (o[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }, m, now)
                }
            }
        } finally { try { rec.stop() } catch (_: Exception) {}; rec.release() }
    }

    private fun decode(mode: Wsjt.Mode, slotMs: Long, samples: ShortArray) {
        val ctx = app ?: return
        val f = File(ctx.cacheDir, "slot_${mode.label}.wav")
        try {
            f.writeBytes(Wsjt.wav(samples))
            val dial = if (source == ModemRunner.Source.RECEIVER) AppState.sdr.value.freqHz else null
            val st = AppState.wsjt.value
            val args = Wsjt.arguments(mode, binary(ctx, mode).absolutePath, f.absolutePath, dial, st.rxHz, st.tolHz, File(ctx.filesDir, "fst4w_state.txt").absolutePath)
            val p = ProcessBuilder(args).directory(ctx.filesDir).redirectErrorStream(false).start()     // WSPR keeps its hash table there
            Thread { try { p.errorStream.readBytes() } catch (_: Exception) {} }.start()
            val killer = Thread { try { if (!p.waitFor(if (mode.periodMs > 120_000) 600L else 90L, TimeUnit.SECONDS)) p.destroy() } catch (_: InterruptedException) {} }
            killer.isDaemon = true; killer.start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor(5, TimeUnit.SECONDS)
            val found = out.mapNotNull { Wsjt.parse(mode, it, slotMs, dial) }
            if (found.isNotEmpty()) {
                val l = AppState.wsjtDecodes.value
                AppState.wsjtDecodes.value = (l + found).takeLast(300)
            }
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(slotMs))
            set { it.copy(status = "${mode.label}: slot $t UTC, ${found.size} decoded", dialHz = dial) }
        } catch (e: Exception) {
            set { it.copy(status = "Decoder error: ${e.message}") }
        } finally { f.delete() }
    }
}
