// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// AIS reception with the phone's RTL-SDR (listen only): raw IQ from RtlTcpLink -> AisRx.Receiver (both marine channels) ->
// !AIVDM sentences sent to the tracker with "aisin", which decodes and lists them. Enabled from the AIS tab; the SDR tunes
// to AisRx.CENTER_HZ. Does not transmit.
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.AisRx
import es.ea7jrs.aprslorap.core.Geo

object AisSdrRunner {
    @Volatile var active = false
        private set
    private var rx: AisRx.Receiver? = null

    fun start() {
        rx = AisRx.Receiver({ sentence ->
            // One frame may be several !AIVDM lines: send each to the tracker (it decodes and lists them too).
            for (line in sentence.split("\r\n")) {
                val l = line.trim()
                if (l.startsWith("!")) AppState.controller?.control("aisin", "l" to l)
            }
            AppState.aisSource.value = "Phone RTL-SDR (${rx?.frames ?: 0} frames)"
        }, { decoded ->
            // Decode locally too, so the vessels show (and alarm) in the app even without a tracker.
            val m = AppState.aisVessels.value.toMutableMap()
            var v = AisRx.mergeInto(m[decoded.mmsi], decoded)
            val me = Own.position()
            val vlat = v.lat; val vlon = v.lon
            if (me != null && vlat != null && vlon != null) {
                val nm = Geo.distanceKm(me.first, me.second, vlat, vlon) / 1.852
                v = v.copy(rangeNm = nm, bearingDeg = Math.round(Geo.bearingDeg(me.first, me.second, vlat, vlon)).toInt())
                // Own course/speed from the phone's or tracker's GNSS (0 if no fix): collision risk if CPA <= 0.5 nm within 20 min.
                val g = AppState.gnss.value
                val oSog = if (g?.fix == true) (g.speedKmh ?: 0.0) / 1.852 else 0.0
                val oCog = if (g?.fix == true) (g.courseDeg ?: 0.0) else 0.0
                val (cpaNm, tcpa) = AisRx.cpa(me.first, me.second, oSog, oCog, vlat, vlon, v.sogKn, v.cogDeg)
                val risk = cpaNm <= 0.5 && tcpa >= 0.0 && tcpa <= 20.0
                v = v.copy(cpaNm = cpaNm, tcpaMin = tcpa, danger = v.danger || risk)
            }
            m[decoded.mmsi] = v
            val now = System.currentTimeMillis()
            m.entries.removeAll { now - it.value.receivedAtMs > 12 * 60_000 }   // gone after 12 min, like the tracker
            AppState.aisVessels.value = m
            AlertCenter.onAis(v)
        })
        active = true
    }

    fun stop() {
        active = false
        rx = null
    }

    /** Raw 8-bit IQ at Dsp.INPUT_RATE from RtlTcpLink; [tunerHz] is the current tuner frequency. */
    fun feed(iq: ByteArray, len: Int, tunerHz: Long) {
        val r = rx ?: return
        r.center(tunerHz)
        r.process(iq, len)
    }
}
