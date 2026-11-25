/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Afsk1200
import es.ea7jrs.aprslorap.core.Ax25Frame
import es.ea7jrs.aprslorap.core.G3ruh
import es.ea7jrs.aprslorap.core.RxMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Packet decoded from the app receiver's FM discriminator (48 kHz): G3RUH direct FSK (the tracker's APRS FSK beacons, 9600 bd,
 * 9k6 cubesats) or Bell 202 AFSK 1200 (APRS on FM and most cubesats' 1k2 telemetry, the format SatNOGS / gr-satellites decode).
 * Every good AX.25 frame goes to the log and to the station / message handling, like one from the tracker.
 */
object PacketRunner {
    @Volatile var active = false
        private set
    @Volatile private var decoder: G3ruh.Decoder? = null
    @Volatile private var afsk: Afsk1200.Decoder? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun onBytes(bytes: ByteArray, bitrate: Long, frames: Int, badFcs: Int, label: String) {
        val f = Ax25Frame.decode(bytes)
        val line = f?.toTnc2() ?: ("(not AX.25) " + bytes.joinToString("") { String.format("%02X", it) }.take(80))
        val l = AppState.packetLog.value
        AppState.packetLog.value = (if (l.size >= 50) l.drop(1) else l) + (fmt.format(Date()) + " " + line)
        if (f != null) AppState.controller?.onPhoneRadioFrame(f, "RTL-SDR", RxMeta(modem = RxMeta.MODEM_AX25, bitrate = bitrate))
        AppState.packetStatus.value = "$label: $frames frame(s), $badFcs with bad FCS"
    }

    @Synchronized
    fun start(baud: Double) {
        afsk = null
        decoder = G3ruh.Decoder(48_000.0, baud) { bytes -> onBytes(bytes, baud.toLong(), decoder?.frames ?: 0, decoder?.badFcs ?: 0, "Packet ${baud.toInt()} bd") }
        active = true
        AppState.packetStatus.value = "Listening for packet ${baud.toInt()} bd (G3RUH)"
    }

    /** Bell 202 AFSK 1200 (satellite telemetry), demodulated from the NFM audio the discriminator tap carries. */
    @Synchronized
    fun startAfsk1200() {
        decoder = null
        afsk = Afsk1200.Decoder(48_000.0) { bytes -> onBytes(bytes, 1200L, afsk?.frames ?: 0, afsk?.badFcs ?: 0, "AFSK 1200 bd") }
        active = true
        AppState.packetStatus.value = "Listening for AFSK 1200 bd (satellite telemetry)"
    }

    @Synchronized
    fun stop() { active = false; decoder = null; afsk = null; if (AppState.packetStatus.value != "Off") AppState.packetStatus.value = "Off" }

    /** Discriminator samples from the receiver thread (the NFM audio for AFSK, the FSK baseband for G3RUH). */
    fun feed(x: FloatArray, n: Int) { decoder?.process(x, n); afsk?.process(x, n) }
}
