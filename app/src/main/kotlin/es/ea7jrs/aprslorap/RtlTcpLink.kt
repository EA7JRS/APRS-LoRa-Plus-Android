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
import android.os.Environment
import es.ea7jrs.aprslorap.core.Dsp
import es.ea7jrs.aprslorap.core.Sdr
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

/**
 * The app as the receiver: IQ from rtl_tcp (the RTL-SDR on USB through the "RTL-SDR driver" app, or an rtl_tcp server on the
 * network), demodulated here ([Dsp.Receiver]) and played on the phone; recordings as WAV in the app's Music folder.
 * Small retunes (Doppler) move the NCO only; the tuner is retuned when the frequency goes too far from it.
 */
internal class RtlTcpLink(
    private val ctx: Context, private val host: String, private val port: Int,
    private val ppm: Int, private val gainPercent: Int, private val squelchDb: Double,
) : SdrControl.LinkApi {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    private var sock: Socket? = null
    private val rx = Dsp.Receiver()
    @Volatile private var wantHz: Long = 145_800_000L
    @Volatile private var tunerHz: Long = 0L
    @Volatile private var newMode: Pair<String, Int>? = null
    @Volatile private var wav: RandomAccessFile? = null
    private var wavBytes = 0
    private var wavRate = 0
    var wavFile: File? = null
        private set

    init {
        rx.squelchDb = squelchDb
        Settings(ctx).let { rx.noiseBlanker = it.sdrNoiseBlanker; rx.noiseReduction = it.sdrNoiseReduction; rx.autoNotch = it.sdrAutoNotch }
        Thread({ run() }, "sdr-rtltcp").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY - 1; it.start() }
    }

    /** Live toggle of the noise tools (blanker, de-noise, auto-notch). */
    fun noise(nb: Boolean, nr: Boolean, anf: Boolean) { rx.noiseBlanker = nb; rx.noiseReduction = nr; rx.autoNotch = anf }

    // ---- CTCSS sub-audible tone detector (NFM)
    private val ctcss = es.ea7jrs.aprslorap.core.Ctcss.Detector(Dsp.NARROW_AUDIO_RATE.toDouble())
    @Volatile private var lastTone = -1.0

    // ---- signal identification (identify by decoding), captured on the SDR thread
    private val idRes = es.ea7jrs.aprslorap.core.Modems.Resampler12to8()
    @Volatile private var idCapturing = false
    private var idBuf = FloatArray(0); private var idPos = 0; private var idCenter = 1000.0; private var idBw: Double? = null

    /** Capture [seconds] of the demodulated audio and identify the mode; the result is posted to AppState.signalId. */
    fun identify(seconds: Double = 5.0, centerHz: Double = 1000.0, bwHz: Double? = null) {
        idBuf = FloatArray((seconds * es.ea7jrs.aprslorap.core.Modems.RATE).toInt()); idPos = 0
        idCenter = centerHz; idBw = bwHz; idCapturing = true                                                 // the UI clears/sets AppState.signalId
    }

    @Volatile private var sameDec: es.ea7jrs.aprslorap.core.SameDecoder? = null
    private val sameWanted: Boolean get() = AppState.appContext?.let { Settings(it).alertSame } ?: false

    override fun tune(hz: Long) {
        wantHz = hz
        if (tunerHz == 0L || abs(hz - (tunerHz - Dsp.IF_OFFSET)) > Dsp.NCO_RANGE) {
            tunerHz = hz + Dsp.IF_OFFSET
            q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ, tunerHz.toInt()))
        }
        rx.setOffset((hz - tunerHz).toDouble())
        SdrControl.update { it.copy(freqHz = hz) }
    }

    override fun mode(mode: String, bwHz: Int) { newMode = mode to bwHz; SdrControl.update { it.copy(mode = mode, bandwidthHz = if (bwHz > 0) bwHz else null) } }

    override fun gain(g: Int) {
        if (g < 0) { q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_GAIN_MODE, 0)); q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_AGC_MODE, 1)) }
        else { q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_AGC_MODE, 0)); q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_GAIN_MODE, 1)); q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.ANDROID_GAIN_PERCENT, g.coerceIn(0, 100))) }
        SdrControl.update { it.copy(gain = g) }
    }

    fun squelch(db: Double) { rx.squelchDb = db }

    /** Frequency correction now, without reconnecting (rtl_tcp command 5). */
    fun setPpm(ppm: Int) { q.offer(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ_CORRECTION, ppm)) }

    private val taps = java.util.concurrent.ConcurrentHashMap<String, (FloatArray, FloatArray, Int) -> Unit>()

    /** The complex 48 kS/s channel to [tap] under [key] ("vor" for [Navaids.PpmMeter], "406" for the beacon receiver); null removes it. */
    fun baseband(key: String, tap: ((FloatArray, FloatArray, Int) -> Unit)?) {
        if (tap == null) taps.remove(key) else taps[key] = tap
        rx.basebandTap = if (taps.isEmpty()) null else { re, im, n -> for (t in taps.values) t(re, im, n) }
    }

    override fun record(on: Boolean) {
        synchronized(this) {
            if (on && wav == null) {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
                val f = File(dir, "sdr_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + Sdr.mhz(wantHz) + ".wav")
                wav = RandomAccessFile(f, "rw").also { it.write(Dsp.wavHeader(rx.audioRate, 0)) }
                wavBytes = 0; wavRate = rx.audioRate; wavFile = f
                SdrControl.update { it.copy(recording = true, status = "Recording to ${f.name}") }
            } else if (!on) closeWav()
        }
    }

    private fun closeWav() {
        synchronized(this) {
            val w = wav ?: return
            try { w.seek(0); w.write(Dsp.wavHeader(wavRate, wavBytes)); w.close() } catch (_: Exception) {}
            wav = null
            SdrControl.update { it.copy(recording = false, status = "Recording saved: ${wavFile?.absolutePath}") }
        }
    }

    override fun close() {
        running = false
        try { if (host == "127.0.0.1" || host == "localhost") sock?.getOutputStream()?.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.ANDROID_EXIT, 0)) } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
        closeWav()
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 4, rate / 2))                 // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }

    private fun run() {
        while (running) {
            var track: AudioTrack? = null
            try {
                Socket().use { s ->
                    sock = s
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.soTimeout = 5000
                    s.receiveBufferSize = 1 shl 18
                    val inp = BufferedInputStream(s.getInputStream(), 1 shl 16)
                    val out = s.getOutputStream()
                    val hello = ByteArray(12)
                    var got = 0
                    while (got < 12) { val n = inp.read(hello, got, 12 - got); if (n < 0) throw java.io.EOFException(); got += n }
                    val g = Sdr.RtlTcp.greeting(hello) ?: throw java.io.IOException("not an rtl_tcp server")
                    SdrControl.update { it.copy(connected = true, device = "RTL-SDR, tuner ${g.tuner}", gainMin = 0, gainMax = 100, status = "Receiving from $host:$port") }
                    out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_SAMPLE_RATE, Dsp.INPUT_RATE))
                    if (ppm != 0) out.write(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ_CORRECTION, ppm))
                    tunerHz = 0L; tune(wantHz)
                    gain(gainPercent)
                    val m0 = newMode ?: (AppState.sdr.value.mode ?: "NFM") to 0
                    newMode = null; rx.setMode(m0.first, m0.second)
                    track = newTrack(rx.audioRate)
                    val buf = ByteArray(32_768)
                    val audio = FloatArray(16_384)
                    val pcm = ShortArray(16_384)
                    val bytes = ByteArray(32_768)
                    var lastLevel = 0L
                    val spec = es.ea7jrs.aprslorap.core.Modems.IqSpectrum(1024)      // panadapter: raw input band
                    val specRow = FloatArray(1024)
                    var lastSpec = 0L
                    rx.specTap = { re, im, nn -> spec.push(re, im, nn) }
                    while (running) {
                        while (true) { val c = q.poll() ?: break; out.write(c) }
                        newMode?.let { (m, bw) ->
                            newMode = null
                            val oldRate = rx.audioRate
                            rx.setMode(m, bw)
                            if (rx.audioRate != oldRate) { track?.release(); track = newTrack(rx.audioRate); if (wav != null) { closeWav(); record(true) } }
                        }
                        var n = 0
                        while (n < buf.size) { val r = inp.read(buf, n, buf.size - n); if (r < 0) throw java.io.EOFException(); n += r }
                        if (AisSdrRunner.active) AisSdrRunner.feed(buf, n, tunerHz)
                        val m = rx.process(buf, n, audio)
                        val dv = DigitalVoiceRunner.active
                        val pk = PacketRunner.active
                        val apt = AptRunner.active
                        // NOAA Weather Radio (WX1..WX7 in NFM): SAME headers to the official alerts (EA7JRS)
                        val wx = sameWanted && es.ea7jrs.aprslorap.core.Same.nwrChannel(wantHz / 1e6) != 0
                        if (wx && sameDec == null) sameDec = es.ea7jrs.aprslorap.core.SameDecoder(48_000.0) else if (!wx) sameDec = null
                        if ((dv || pk || wx || apt) && rx.discriminatorTap == null) rx.discriminatorTap = { x, k ->
                            if (DigitalVoiceRunner.active) DigitalVoiceRunner.feed(x, k)
                            if (PacketRunner.active) PacketRunner.feed(x, k)
                            if (AptRunner.active) AptRunner.feed(x, k)
                            sameDec?.let { d -> d.feed(x, 0, k); while (true) { OfficialAlertsFetcher.onSameHeader(d.poll() ?: break) } }
                        } else if (!dv && !pk && !wx && !apt && rx.discriminatorTap != null) rx.discriminatorTap = null
                        if (rx.audioRate == Dsp.NARROW_AUDIO_RATE) { ModemRunner.feedReceiver12k(audio, m); WsjtRunner.feedReceiver12k(audio, m); SsdvRxRunner.feedReceiver12k(audio, m) }
                        if (rx.mode == "NFM") {
                            ctcss.feed(audio, m)
                            if (ctcss.tone != lastTone) { lastTone = ctcss.tone; SdrControl.update { it.copy(ctcssTone = if (ctcss.tone > 0) ctcss.tone else null) } }
                        } else if (lastTone != -1.0) { lastTone = -1.0; SdrControl.update { it.copy(ctcssTone = null) } }
                        if (idCapturing && rx.audioRate == Dsp.NARROW_AUDIO_RATE) {
                            val y = FloatArray(m * 2 / 3 + 2)
                            val k = idRes.process(audio, m, y)
                            val take = minOf(k, idBuf.size - idPos)
                            System.arraycopy(y, 0, idBuf, idPos, take); idPos += take
                            if (idPos >= idBuf.size) {
                                idCapturing = false
                                val b = idBuf; val c = idCenter; val bw = idBw
                                Thread({ AppState.signalId.value = es.ea7jrs.aprslorap.core.SignalIdentify.Report(es.ea7jrs.aprslorap.core.SignalIdentify.identify(b, c, bw), emptyList()) }, "sdr-identify").also { it.isDaemon = true; it.start() }
                            }
                        }
                        Dsp.toPcm16(audio, m, pcm)
                        if (!dv) track?.write(pcm, 0, m, AudioTrack.WRITE_NON_BLOCKING)   // if the phone's audio falls behind, samples are dropped, not the IQ
                        synchronized(this) {
                            val w = wav
                            if (w != null) {
                                for (i in 0 until m) { bytes[2 * i] = pcm[i].toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                                w.write(bytes, 0, 2 * m); wavBytes += 2 * m
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastLevel > 300) { lastLevel = now; SdrControl.update { it.copy(signalDb = rx.levelDb) } }
                        if (now - lastSpec > 45 && spec.ready) {            // ~20 rows/s panadapter
                            lastSpec = now
                            AppState.sdrSpectrum.value = AppState.SdrSpectrum(spec.db(specRow).copyOf(), tunerHz, Dsp.INPUT_RATE, wantHz)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                SdrControl.update { it.copy(connected = false, status = "No IQ from $host:$port (${e.message}); retrying") }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            } finally {
                rx.specTap = null
                AppState.sdrSpectrum.value = null
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }
}
