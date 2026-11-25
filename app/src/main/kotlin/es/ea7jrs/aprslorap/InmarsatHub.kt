/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Inmarsat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Inmarsat-C / STD-C (EGC / SafetyNET) receiver on the app's RTL-SDR, as a complex 48 kS/s channel tuned to the NCS TDM
 * frequency (L-band, ~1.5 GHz; needs a patch antenna and an LNA). The DSP chain (BPSK demod, Viterbi, descrambler, UW sync)
 * is tested end to end; the frame / EGC layout is a working model and needs a real STD-C recording to confirm.
 */
object InmarsatHub {
    data class Status(val listening: Boolean = false, val frames: Int = 0, val messages: Int = 0)

    val messages = MutableStateFlow<List<Inmarsat.Egc>>(emptyList())
    val status = MutableStateFlow(Status())
    private var reader: Inmarsat.FrameReader? = null
    private var tapped: RtlTcpLink? = null
    private const val MAX = 100

    @Synchronized
    fun start(): Boolean {
        val link = SdrControl.rtl() ?: run { status.value = status.value.copy(listening = false); return false }
        if (tapped !== link) {
            tapped?.baseband("stdc", null)
            val r = Inmarsat.FrameReader(48_000.0) { m -> onMessage(m) }
            reader = r
            link.baseband("stdc") { re, im, n -> synchronized(r) { r.feed(re, im, n) } }
            tapped = link
        }
        status.value = status.value.copy(listening = true)
        return true
    }

    @Synchronized
    fun stop() {
        tapped?.baseband("stdc", null)
        tapped = null
        status.value = status.value.copy(listening = false)
    }

    /** The link may have reconnected: listen again on the new one, and refresh the frame counter. */
    fun ensure() {
        if (status.value.listening && SdrControl.rtl() !== tapped) start()
        reader?.let { if (status.value.listening) status.value = status.value.copy(frames = it.frames) }
    }

    private fun onMessage(m: Inmarsat.Egc) {
        synchronized(this) {
            val old = messages.value
            if (old.none { it.id == m.id && it.text == m.text }) messages.value = (listOf(m) + old).take(MAX)
        }
        reader?.let { status.value = status.value.copy(frames = it.frames, messages = messages.value.size) }
    }

    fun clear() { messages.value = emptyList() }
}
