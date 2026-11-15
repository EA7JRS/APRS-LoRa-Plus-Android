/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import es.ea7jrs.aprslorap.core.Modems
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Text modes (CW, RTTY, BPSK...) on audio from the app's receiver (12 kHz, resampled to 8 kHz) or from the phone's microphone
 * (a radio's speaker): one worker thread runs the modem and the waterfall.
 */
object ModemRunner {
    enum class Source { RECEIVER, MICROPHONE }
    data class State(val running: Boolean = false, val mode: String = "BPSK31", val source: Source = Source.RECEIVER, val centerHz: Double = 1000.0,
                     val quality: Double = 0.0, val afc: Boolean = true, val info: String = "")

    private val queue = ArrayBlockingQueue<FloatArray>(64)
    @Volatile private var modem: Modems.Modem? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var mic: Thread? = null
    @Volatile var active = false
        private set
    @Volatile private var source = Source.RECEIVER
    private val resampler = Modems.Resampler12to8()

    private fun set(f: (State) -> State) { synchronized(this) { AppState.modem.value = f(AppState.modem.value) } }

    @Synchronized
    fun start(mode: String, src: Source, centerHz: Double) {
        stop()
        val m = Modems.create(mode, centerHz)
        m.afc = AppState.modem.value.afc
        m.onText = { t ->
            val cur = AppState.modemText.value; AppState.modemText.value = (cur + t).takeLast(6000)
            if (m is Modems.Cw) Spotter.onPhoneCw(t, m.centerHz, m.snrDb, m.wpm, src)        // calls heard -> PSK Reporter, skimmer port
        }
        if (m is es.ea7jrs.aprslorap.core.Navtex.Decoder) m.onMessage = { NavtexHub.onMessage(it) }
        if (m is es.ea7jrs.aprslorap.core.Dsc.Decoder) m.onCall = { DscHub.onCall(it, if (m.vhf) 156_525.0 else Settings.dscKhzNow) }
        modem = m; source = src; active = true; queue.clear()
        set { it.copy(running = true, mode = mode, source = src, centerHz = centerHz, info = "") }
        worker = Thread({ work() }, "modem").also { it.isDaemon = true; it.start() }
        if (src == Source.MICROPHONE) mic = Thread({ micLoop() }, "modem-mic").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        active = false
        worker?.interrupt(); worker = null
        mic?.interrupt(); mic = null
        modem = null
        set { it.copy(running = false) }
    }

    fun setCenter(hz: Double) { modem?.centerHz = hz; set { it.copy(centerHz = hz) } }
    fun setAfc(on: Boolean) { modem?.afc = on; set { it.copy(afc = on) } }

    /** Audio of the app receiver (any narrow mode, 12 kHz), from its thread. */
    fun feedReceiver12k(x: FloatArray, n: Int) {
        if (!active || source != Source.RECEIVER) return
        val y = FloatArray(n * 2 / 3 + 2)
        val m = synchronized(resampler) { resampler.process(x, n, y) }
        queue.offer(y.copyOf(m))
    }

    @SuppressLint("MissingPermission")
    private fun micLoop() {
        val min = AudioRecord.getMinBufferSize(Modems.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, Modems.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192)) }
                  catch (e: Exception) { null }
        val r = if (rec?.state == AudioRecord.STATE_INITIALIZED) rec else try {
            rec?.release(); AudioRecord(MediaRecorder.AudioSource.MIC, Modems.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        } catch (e: Exception) { set { it.copy(info = "No microphone: ${e.message}") }; return }
        if (r.state != AudioRecord.STATE_INITIALIZED) { set { it.copy(info = "The microphone cannot be opened (permission?)") }; r.release(); return }
        r.startRecording()
        val buf = ShortArray(400)
        try {
            while (active && source == Source.MICROPHONE) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                queue.offer(FloatArray(n) { buf[it] / 32768f })
            }
        } finally { try { r.stop() } catch (_: Exception) {}; r.release() }
    }

    private fun work() {
        val spec = Modems.Spectrum(1024)
        var lastRow = 0L
        try {
            while (active) {
                val x = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val m = modem ?: break
                m.process(x, x.size)
                spec.push(x, x.size)
                val now = System.currentTimeMillis()
                if (now - lastRow >= 120) {
                    lastRow = now
                    AppState.waterfallRow.value = spec.db()
                    set { it.copy(quality = m.quality, centerHz = m.centerHz, info = if (m is Modems.Cw) "${m.wpm} wpm" else it.info) }
                }
            }
        } catch (_: InterruptedException) {}
    }
}
