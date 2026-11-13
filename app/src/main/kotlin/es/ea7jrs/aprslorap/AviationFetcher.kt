/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Aviation
import es.ea7jrs.aprslorap.core.Geo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * METAR, TAF and SIGMET for the map layers, from the NOAA Aviation Weather Center (aviationweather.gov, public domain). Only
 * while one of the two layers is on: a box of ±3° latitude and ±4° longitude around the tracker's GNSS position (or the
 * phone's last one) every 15 min, or again when the position moves more than 100 km.
 */
object AviationFetcher {
    data class Data(val metars: List<Aviation.Metar> = emptyList(), val tafs: List<Aviation.Taf> = emptyList(),
                    val sigmets: List<Aviation.Sigmet> = emptyList(), val atMs: Long = 0, val error: String = "")

    val data = MutableStateFlow(Data())
    @Volatile private var busy = false
    @Volatile private var lastMs = 0L
    private var lastAt: Pair<Double, Double>? = null
    private const val EVERY_MS = 15 * 60_000L
    private const val UA = "APRSLoRaPlus-Android (+https://ea7jrs.github.io/APRS-LoRa-Plus/)"

    private fun layers(settings: Settings): Pair<Boolean, Boolean> {
        val on = MapOverlays.parseOn(settings.mapOverlays)
        return ("metar" in on) to ("sigmet" in on)
    }

    /** Called often (Controller tick, map screen): downloads when due. */
    fun maybeFetch(ctx: Context, settings: Settings) {
        val (metar, sigmet) = layers(settings)
        if ((!metar && !sigmet) || busy) return
        val here = NetWeatherFetcher.position(ctx) ?: return
        val moved = lastAt?.let { Geo.distanceKm(it.first, it.second, here.first, here.second) > 100 } ?: true
        if (!moved && lastMs != 0L && System.currentTimeMillis() - lastMs < EVERY_MS) return
        fetch(here, metar, sigmet)
    }

    private fun fetch(here: Pair<Double, Double>, metar: Boolean, sigmet: Boolean) {
        busy = true
        Thread({
            val (la, lo) = here
            val errors = ArrayList<String>()
            var metars = if (metar) data.value.metars else emptyList()
            var tafs = if (metar) data.value.tafs else emptyList()
            var sigmets = if (sigmet) data.value.sigmets else emptyList()
            try {
                if (metar) {
                    runCatching { Aviation.parseMetars(text(Aviation.metarUrl(la - 3, lo - 4, la + 3, lo + 4))) ?: throw IllegalStateException("not METAR data") }
                        .onSuccess { metars = it }.onFailure { errors.add("METAR: ${it.message}") }
                    runCatching { Aviation.parseTafs(text(Aviation.tafUrl(la - 3, lo - 4, la + 3, lo + 4))) ?: throw IllegalStateException("not TAF data") }
                        .onSuccess { tafs = it }.onFailure { errors.add("TAF: ${it.message}") }
                }
                if (sigmet) runCatching { Aviation.parseSigmets(text(Aviation.sigmetUrl(Aviation.sigmetRegion(la, lo)), "application/geo+json")) ?: throw IllegalStateException("not SIGMET data") }
                    .onSuccess { sigmets = it }.onFailure { errors.add("SIGMET: ${it.message}") }
                data.value = Data(metars, tafs, sigmets, System.currentTimeMillis(), errors.joinToString("; "))
                if (errors.isEmpty()) { lastMs = System.currentTimeMillis(); lastAt = here }
                else { lastMs = System.currentTimeMillis() - EVERY_MS + 120_000L; AppState.addLog("Aviation weather: " + errors.joinToString("; ")) }   // again in 2 min
            } finally { busy = false }
        }, "aviation").apply { isDaemon = true; start() }
    }

    private fun text(url: String, accept: String = "application/json"): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        try {
            val code = c.responseCode
            if (code == 204) return if (accept.contains("geo")) "{\"features\":[]}" else "[]"   // nothing in the box
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally { c.disconnect() }
    }
}
