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
import es.ea7jrs.aprslorap.core.DigitalVoice
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs dsd-fme (lib/<abi>/libdsdfme.so) for the app's RTL-SDR receiver: the FM discriminator goes to it over a local TCP
 * socket, its decoded voice comes back on stdout and is played here, and its log (calls, IDs, talkgroups, texts) is shown.
 */
object DigitalVoiceRunner {
    @Volatile private var proc: Process? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var sink: OutputStream? = null
    private val queue = ArrayBlockingQueue<ShortArray>(64)
    @Volatile var active = false
        private set

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libdsdfme.so")
    fun available(ctx: Context): Boolean = binary(ctx).canExecute()

    @Synchronized
    fun start(ctx: Context, mode: String) {
        stop()
        val bin = binary(ctx)
        if (!bin.canExecute()) { AppState.dvStatus.value = "This build of the app has no digital voice decoder for this phone"; return }
        try {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server = ss
            val pb = ProcessBuilder(listOf(bin.absolutePath) + DigitalVoice.arguments(mode, ss.localPort))
                .directory(ctx.filesDir)
            pb.environment()["HOME"] = ctx.filesDir.absolutePath
            pb.environment()["TERM"] = "dumb"
            val p = pb.start()
            proc = p
            active = true
            queue.clear()
            AppState.dvLog.value = emptyList()
            AppState.dvStatus.value = "Decoding $mode"
            Thread({ acceptAndFeed(ss) }, "dv-feed").also { it.isDaemon = true; it.start() }
            Thread({ readAudio(p, DigitalVoice.channels(mode)) }, "dv-audio").also { it.isDaemon = true; it.start() }
            Thread({ readLog(p) }, "dv-log").also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            AppState.dvStatus.value = "The decoder did not start: ${e.message}"
            stop()
        }
    }

    @Synchronized
    fun stop() {
        active = false
        try { proc?.destroy() } catch (_: Exception) {}
        try { sink?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        proc = null; sink = null; server = null
        queue.clear()
        if (AppState.dvStatus.value.startsWith("Decoding")) AppState.dvStatus.value = "Off"
    }

    /** Discriminator samples (48 kHz, ±1) from the receiver thread; dropped if the decoder falls behind. */
    fun feed(x: FloatArray, n: Int) {
        if (!active || sink == null) return
        val s = ShortArray(n)
        for (i in 0 until n) s[i] = (x[i].coerceIn(-1f, 1f) * 16000f).toInt().toShort()
        queue.offer(s)
    }

    private fun acceptAndFeed(ss: ServerSocket) {
        try {
            ss.accept().use { c ->
                c.tcpNoDelay = true
                val out = c.getOutputStream()
                sink = out
                var buf = ByteArray(0)
                while (active) {
                    val s = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    if (buf.size < 2 * s.size) buf = ByteArray(2 * s.size)
                    for (i in s.indices) { buf[2 * i] = s[i].toByte(); buf[2 * i + 1] = (s[i].toInt() shr 8).toByte() }
                    out.write(buf, 0, 2 * s.size)
                }
            }
        } catch (_: Exception) {
            if (active) AppState.dvStatus.value = "The decoder closed its input"
        }
    }

    private fun readAudio(p: Process, channels: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, 8000))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        try {
            val inp = p.inputStream
            val frame = 2 * channels
            val buf = ByteArray(frame * 800)
            val mono = ShortArray(800)
            var fill = 0
            while (true) {
                val n = inp.read(buf, fill, buf.size - fill)
                if (n < 0) break
                fill += n
                val frames = fill / frame
                for (i in 0 until frames) {
                    var acc = 0
                    for (ch in 0 until channels) {
                        val o = i * frame + 2 * ch
                        acc += ((buf[o].toInt() and 0xFF) or (buf[o + 1].toInt() shl 8)).toShort().toInt()
                    }
                    mono[i] = acc.coerceIn(-32768, 32767).toShort()                 // slots 1 + 2: only one talks at a time
                }
                track.write(mono, 0, frames, AudioTrack.WRITE_NON_BLOCKING)
                val rest = fill - frames * frame
                if (rest > 0) System.arraycopy(buf, frames * frame, buf, 0, rest)
                fill = rest
            }
        } catch (_: Exception) {
        } finally {
            track.release()
        }
    }

    private fun readLog(p: Process) {
        val m17 = DigitalVoice.M17Log()
        try {
            p.errorStream.bufferedReader().forEachLine { raw ->
                val line = DigitalVoice.clean(raw)
                if (line.isEmpty() || !DigitalVoice.interesting(line)) return@forEachLine
                // M17 stations also go to the list of beacons heard (map layer, spots)
                val now = System.currentTimeMillis()
                m17.feed(line, now)?.let { h -> AppState.sdr.value.freqHz?.takeIf { it > 0 }?.let { hz -> BeaconHub.add(es.ea7jrs.aprslorap.core.Beacons.fromM17(h.call, h.lat, h.lon, hz, now)) } }
                val l = AppState.dvLog.value
                AppState.dvLog.value = (if (l.size >= 60) l.drop(l.size - 59) else l) + line
            }
        } catch (_: Exception) {}
        if (active) { AppState.dvStatus.value = "The decoder stopped (exit ${runCatching { p.waitFor() }.getOrNull()})"; active = false }
    }
}
