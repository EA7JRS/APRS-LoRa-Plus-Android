/*
 * Copyright (C) 2026 Manuel - EA7JRS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.ln

/**
 * Identifies the signal you are hearing on the SDR: the audio of the tuned channel is run through the continuous text
 * decoders (CW, RTTY, PSK) and the one that locks on (printable text plus sustained [Modems.Modem.quality]) names the
 * mode. The measured channel bandwidth from the waterfall, when known, narrows the candidates. Separately it crosses the
 * RF frequency and bandwidth against the SigID (Artemis) database, so the report gives both "what decodes" and "what the
 * database says it is".
 *
 * The slot modes (FT8 / FT4 / WSPR / FST4W) are not here: they are identified by [Wsjt] on the app side, where the
 * native decoders and the UTC slots live. This object is pure Kotlin and unit-tested with the synthetic generators in
 * SelfTest, so it needs no external audio samples.
 */
object SignalIdentify {

    /** One decode candidate, best [confidence] first. [sigId] is the database entry it was matched to, when any. */
    data class Match(val mode: String, val confidence: Double, val text: String)

    /** The whole picture for one tuned channel: what the decoders made of the audio, and what SigID says by RF freq/bw. */
    data class Report(val modes: List<Match>, val database: List<SigId.Signal>) {
        val best: Match? get() = modes.firstOrNull()
    }

    private class Cand(val mode: String, val nominalBwHz: Double, val make: () -> Modems.Modem)

    /** Candidate decoders to try on a channel centred at [centerHz] (audio Hz). */
    private fun candidates(centerHz: Double): List<Cand> = listOf(
        Cand("CW", 100.0) { Modems.Cw(centerHz) },
        Cand("BPSK31", 31.0) { Modems.Psk(centerHz, 31.25) },
        Cand("BPSK63", 63.0) { Modems.Psk(centerHz, 62.5) },
        Cand("BPSK125", 125.0) { Modems.Psk(centerHz, 125.0) },
        Cand("BPSK250", 250.0) { Modems.Psk(centerHz, 250.0) },
        Cand("RTTY 45/170", 270.0) { Modems.Rtty(centerHz, 45.45, 170.0) },
        Cand("RTTY 45/170 rev", 270.0) { Modems.Rtty(centerHz, 45.45, 170.0, reverse = true) },
        Cand("RTTY 50/170", 290.0) { Modems.Rtty(centerHz, 50.0, 170.0) },
        Cand("RTTY 75/170", 320.0) { Modems.Rtty(centerHz, 75.0, 170.0) },
    )

    /** Runs one modem over [audio] (at [Modems.RATE]); returns the decoded text and the peak quality seen. */
    private fun run(make: () -> Modems.Modem, audio: FloatArray, block: Int = 512): Pair<String, Double> {
        val m = make()
        val sb = StringBuilder()
        m.onText = { sb.append(it) }
        var qMax = 0.0
        var i = 0
        while (i < audio.size) {
            val n = minOf(block, audio.size - i)
            m.process(audio.copyOfRange(i, i + n), n)
            if (m.quality > qMax) qMax = m.quality
            i += n
        }
        return sb.toString() to qMax
    }

    /** 0.2..1 plausibility of [nominalBwHz] against a [measuredBwHz] from the waterfall (1 when they match). */
    private fun bwFactor(nominalBwHz: Double, measuredBwHz: Double?): Double {
        if (measuredBwHz == null || measuredBwHz <= 0) return 1.0
        val octaves = abs(ln(measuredBwHz / nominalBwHz) / ln(2.0))     // distance in octaves
        return (1.0 - 0.5 * octaves).coerceIn(0.2, 1.0)                 // a width an octave off loses half its score
    }

    /**
     * Mode matches for [audio] (at [Modems.RATE]) on a channel centred at [centerHz] (audio Hz). [bandwidthHz] from the
     * waterfall, when given, favours the modes whose width fits. Only matches at or above [minConfidence] are returned,
     * best first.
     */
    fun identify(audio: FloatArray, centerHz: Double, bandwidthHz: Double? = null, minConfidence: Double = 0.25): List<Match> {
        val out = ArrayList<Match>()
        for (c in candidates(centerHz)) {
            val (text, qMax) = run(c.make, audio)
            val printable = text.count { it == ' ' || it in '!'..'~' }
            if (printable < 3) continue                                 // nothing legible: not this mode
            val textScore = (printable / 10.0).coerceAtMost(1.0)        // ~10 good characters = full marks
            val conf = (textScore * qMax * bwFactor(c.nominalBwHz, bandwidthHz)).coerceIn(0.0, 1.0)
            if (conf >= minConfidence) out.add(Match(c.mode, conf, text.trim()))
        }
        // one mode per family when several RTTY/PSK speeds all lock: keep the strongest of each prefix group would hide
        // useful detail, so instead just sort and let the caller show the top few.
        return out.sortedByDescending { it.confidence }
    }

    /**
     * SigID (Artemis) database entries known at the RF [rfHz] centre. Matched by frequency only: the width measured on a
     * waterfall is too coarse to filter the database reliably (it would drop good hits), so bandwidth stays a decoder
     * hint, not a database filter.
     */
    fun database(signals: List<SigId.Signal>, rfHz: Long?): List<SigId.Signal> =
        if (rfHz == null) emptyList()
        else SigId.search(signals, SigId.Query(freqHz = rfHz)).take(8)

    /**
     * Full report for a tuned channel: [audio] at [Modems.RATE] centred at [audioCenterHz] (audio Hz), measured
     * [bwHz] width (decoder hint), the RF [rfHz] centre (dial + audio offset) for the database, and the loaded SigID
     * [signals].
     */
    fun analyse(audio: FloatArray, audioCenterHz: Double, bwHz: Long?, rfHz: Long?, signals: List<SigId.Signal>): Report =
        Report(identify(audio, audioCenterHz, bwHz?.toDouble()), database(signals, rfHz))
}
