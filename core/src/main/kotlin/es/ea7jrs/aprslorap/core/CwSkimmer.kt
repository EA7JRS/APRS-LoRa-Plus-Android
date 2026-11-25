/*
 * Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.roundToInt

/**
 * CW skimmer: decodes several CW signals at once inside the receiver's audio passband. A bank of narrow CW channels
 * (each one a [Modems.Cw] downconverter + decoder) runs over the same audio, and a per-channel [Spots.CwSpotter] turns
 * the decoded text into callsigns (after CQ / DE / QRZ / TEST, as CW Skimmer does). It is limited to the passband of the
 * receiver's current channel; a true wideband skimmer would process the wide I/Q instead.
 */
class CwSkimmer(centersHz: List<Double>, ownCall: String = "", wpm: Int = 18) {

    data class Spot(val audioHz: Double, val call: String, val comment: String, val snrDb: Int, val wpm: Int)

    private class Chan(val audioHz: Double, val cw: Modems.Cw, val spotter: Spots.CwSpotter)
    private val chans: List<Chan>
    @Volatile private var nowMs = 0L

    /** Every callsign a channel resolves. */
    var onSpot: (Spot) -> Unit = {}
    /** Raw decoded characters per channel (audioHz, text), for a waterfall-style view. */
    var onText: (Double, String) -> Unit = { _, _ -> }

    val centers: List<Double> get() = chans.map { it.audioHz }

    init {
        chans = centersHz.map { hz ->
            val cw = Modems.Cw(hz, wpm)
            val sp = Spots.CwSpotter(ownCall)
            cw.onText = { t ->
                onText(hz, t)
                for (h in sp.feed(t, nowMs)) onSpot(Spot(hz, h.call, h.comment, cw.snrDb.roundToInt(), cw.wpm))
            }
            Chan(hz, cw, sp)
        }
    }

    /** Feeds [n] audio samples (at [Modems.RATE]) with the wall-clock [nowMs] used for the spot timing. */
    fun process(x: FloatArray, n: Int, nowMs: Long) {
        this.nowMs = nowMs
        for (c in chans) c.cw.process(x, n)
    }

    companion object {
        /** A channel grid every [stepHz] from [minHz] to [maxHz] (inclusive). CW channels are ~120 Hz wide. */
        fun grid(minHz: Double = 400.0, maxHz: Double = 2800.0, stepHz: Double = 100.0): List<Double> {
            val out = ArrayList<Double>()
            var f = minHz
            while (f <= maxHz + 1e-9) { out.add(f); f += stepHz }
            return out
        }
    }
}
