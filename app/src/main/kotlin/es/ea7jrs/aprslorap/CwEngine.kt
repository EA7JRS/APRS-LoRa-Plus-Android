/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import es.ea7jrs.aprslorap.core.CwDecoder
import es.ea7jrs.aprslorap.core.IambicKeyer
import es.ea7jrs.aprslorap.core.KeyLine
import es.ea7jrs.aprslorap.core.KeyerMode
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.sin

/**
 * Telegraph key connected to the phone, keying the tracker (MORSE mode).
 * The adapters only report the paddle lines ([setLine]); a 1 ms thread runs the keyer here, plays the sidetone on the phone
 * (no delay) and sends each key change to the tracker:
 * - real time: {"c":"k","t":...,"d":...}; the tracker replays your exact timing with a fixed delay (kcfg);
 * - text: the phone decodes your keying and the tracker sends the words at its own speed (the "cw" command).
 */
object CwEngine {
    @Volatile private var ditDown = false
    @Volatile private var dahDown = false
    @Volatile private var straightDown = false
    @Volatile private var running = false
    private var thread: Thread? = null
    private var sidetone: Sidetone? = null
    private var input: CwInput? = null
    @Volatile var source: String = "keyboard"
        private set

    val active: Boolean get() = running

    /** Paddle line from an adapter (any thread). */
    fun setLine(line: KeyLine, down: Boolean) {
        when (line) {
            KeyLine.DIT -> ditDown = down
            KeyLine.DAH -> dahDown = down
            KeyLine.STRAIGHT -> straightDown = down
        }
    }

    fun start(ctx: Context) {
        if (running) return
        val settings = Settings(ctx)
        val c = AppState.controller
        if (c == null || !AppState.controlReady.value) { AppState.error.value = "Start the link with the tracker first"; return }
        if (!Licence.isValid(settings)) { AppState.error.value = "Callsign not validated in RadioID: the key cannot transmit"; return }
        if (AppState.trackerInfo.value?.hasRemoteKey != true) { AppState.error.value = "The tracker's firmware does not take a remote key"; return }
        ditDown = false; dahDown = false; straightDown = false
        source = settings.cwInput
        val realtime = settings.cwRealtime
        if (realtime) c.control("kcfg", "delay" to settings.cwDelayMs)
        sidetone = if (settings.cwSidetone) Sidetone(settings.cwToneHz).also { it.start() } else null
        input = when (source) {
            "midi" -> MidiKeyInput(ctx)
            "serial" -> SerialKeyInput(ctx, settings.cwDitLine, settings.cwDahLine)
            else -> null                                   // keyboard: MainActivity passes the key events
        }
        if (input == null) AppState.cwInputStatus.value = "Keyboard adapter: keep this screen open"
        input?.start()
        running = true
        AppState.cwActive.value = true
        AppState.cwText.value = ""
        val keyer = IambicKeyer(settings.cwWpm, settings.cwKeyer)
        thread = Thread({ loop(keyer, CwDecoder(settings.cwWpm), realtime) }, "cw-key").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(200)
        thread = null
        input?.stop(); input = null
        sidetone?.stop(); sidetone = null
        AppState.cwActive.value = false
        AppState.cwKeyDown.value = false
        AppState.cwInputStatus.value = "Off"
    }

    private fun loop(keyer: IambicKeyer, decoder: CwDecoder, realtime: Boolean) {
        var keyed = false
        val word = StringBuilder()
        var lastChange = SystemClock.uptimeMillis()
        try {
            while (running) {
                val now = SystemClock.uptimeMillis()
                val out = straightDown || keyer.update(now, ditDown, dahDown)
                if (out != keyed) {
                    keyed = out
                    lastChange = now
                    sidetone?.on = keyed
                    AppState.cwKeyDown.value = keyed
                    if (keyed) decoder.keyDown(now) else decoder.keyUp(now)
                    if (realtime) AppState.controller?.keyEvent(now, keyed)
                }
                val chars = decoder.tick(now)
                if (chars.isNotEmpty()) {
                    AppState.cwText.value = (AppState.cwText.value + chars).takeLast(200)
                    if (!realtime) {
                        for (ch in chars) if (ch == ' ') { flush(word) } else word.append(ch)
                        if (word.length >= 20) flush(word)
                    }
                }
                if (!realtime && word.isNotEmpty() && !keyed && now - lastChange > 3000) flush(word)
                LockSupport.parkNanos(500_000)            // ~1 ms resolution
            }
        } finally {
            if (keyed && realtime) AppState.controller?.keyEvent(SystemClock.uptimeMillis(), false)   // never leave the carrier on
            if (!realtime) flush(word)
            sidetone?.on = false
        }
    }

    private fun flush(word: StringBuilder) {
        val text = word.toString().trim()
        word.clear()
        if (text.isNotEmpty()) AppState.controller?.control("cw", "t" to text)
    }
}

/** An adapter that reports paddle lines to [CwEngine.setLine]. */
interface CwInput {
    fun start()
    fun stop()
}

/**
 * Sidetone on the phone: a sine wave streamed with a low-latency AudioTrack; on/off with a 5 ms ramp (no clicks).
 * The stream keeps running (silence when the key is up) so a key-down sounds within a few milliseconds.
 */
class Sidetone(private val hz: Int) {
    @Volatile var on = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ play() }, "cw-sidetone").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
    }

    private fun play() {
        val rate = AudioTrack.getNativeOutputSampleRate(android.media.AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            AppState.addLog("sidetone: ${e.message}")
            return
        }
        val frames = 64
        val buf = ShortArray(frames)
        val step = 2 * PI * hz / rate
        val ramp = 1.0 / (rate * 0.005)
        var phase = 0.0
        var gain = 0.0
        try {
            track.play()
            while (running) {
                val target = if (on) 1.0 else 0.0
                for (i in 0 until frames) {
                    gain = if (gain < target) minOf(target, gain + ramp) else maxOf(target, gain - ramp)
                    buf[i] = (sin(phase) * gain * 0.5 * Short.MAX_VALUE).toInt().toShort()
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(buf, 0, frames)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
