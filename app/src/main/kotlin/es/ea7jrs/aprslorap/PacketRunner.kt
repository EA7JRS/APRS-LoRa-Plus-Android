/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Ax25Frame
import es.ea7jrs.aprslorap.core.G3ruh
import es.ea7jrs.aprslorap.core.RxMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Packet FSK G3RUH (the tracker's APRS FSK beacons, 9600 bd packet, cubesats) decoded from the app receiver's FM
 * discriminator (48 kHz). Every good frame goes to the log and to the station / message handling, like one from the tracker.
 */
object PacketRunner {
    @Volatile var active = false
        private set
    @Volatile private var decoder: G3ruh.Decoder? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun start(baud: Double) {
        decoder = G3ruh.Decoder(48_000.0, baud) { bytes ->
            val f = Ax25Frame.decode(bytes)
            val line = f?.toTnc2() ?: ("(not AX.25) " + bytes.joinToString("") { String.format("%02X", it) }.take(80))
            val l = AppState.packetLog.value
            AppState.packetLog.value = (if (l.size >= 50) l.drop(1) else l) + (fmt.format(Date()) + " " + line)
            if (f != null) AppState.controller?.onPhoneRadioFrame(f, "RTL-SDR", RxMeta(modem = RxMeta.MODEM_AX25, bitrate = baud.toLong()))
            AppState.packetStatus.value = "Packet ${baud.toInt()} bd: ${decoder?.frames ?: 0} frame(s), ${decoder?.badFcs ?: 0} with bad FCS"
        }
        active = true
        AppState.packetStatus.value = "Listening for packet ${baud.toInt()} bd (G3RUH)"
    }

    @Synchronized
    fun stop() { active = false; decoder = null; if (AppState.packetStatus.value != "Off") AppState.packetStatus.value = "Off" }

    /** Discriminator samples from the receiver thread. */
    fun feed(x: FloatArray, n: Int) { decoder?.process(x, n) }
}
