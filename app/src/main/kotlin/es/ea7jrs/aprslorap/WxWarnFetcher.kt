// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Official weather warnings (core/WxWarn.kt): downloaded every 15 minutes from AEMET OpenData (Spain; the user's own key, the
// warnings of the zone we are in and of those nearby) or from the Meteoalarm feed of a European country (zones by name).
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.WxWarn
import es.ea7jrs.aprslorap.core.WxWarning
import java.net.HttpURLConnection
import java.net.URL

object WxWarnFetcher {
    const val NEAR_KM = 30.0
    private const val EVERY_MS = 15 * 60_000L
    private const val RETRY_MS = 3 * 60_000L
    @Volatile private var busy = false
    @Volatile private var lastMs = 0L
    @Volatile private var failed = false

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
        return c
    }

    private fun text(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("key refused (HTTP $code)")
            if (code == 429) throw IllegalStateException("too many calls (HTTP 429)")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.use { String(it.readBytes().copyOf(), Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    /** Called often (Controller tick, the Warnings screen): downloads when it is due. */
    fun maybeFetch(settings: Settings) {
        if (settings.warnSource == 0 || busy) return
        if (lastMs != 0L && System.currentTimeMillis() - lastMs < (if (failed) RETRY_MS else EVERY_MS)) return
        fetchNow(settings)
    }

    fun fetchNow(settings: Settings) {
        if (busy) return
        val source = settings.warnSource
        if (source == 0) { AppState.wxWarnMessage.value = "Off: choose a source"; AppState.wxWarnings.value = emptyList(); return }
        busy = true
        lastMs = System.currentTimeMillis()
        val key = settings.aemetKey.trim(); val country = settings.warnCountry; val areaFilter = settings.warnArea
        Thread({
            try {
                val pos = Own.position()
                val url = if (source == 1) {
                    if (key.isEmpty()) throw IllegalStateException("enter your AEMET OpenData key")
                    if (pos == null && areaFilter.isBlank()) throw IllegalStateException("no position yet and no zone names")
                    // AEMET answers with the address of the data: an archive with every CAP warning in force in Spain
                    Regex("\"datos\"\\s*:\\s*\"([^\"]+)\"").find(text(WxWarn.aemetUrl(key)))?.groupValues?.get(1)
                        ?: throw IllegalStateException("AEMET: unexpected answer")
                } else {
                    if (country.isBlank()) throw IllegalStateException("enter the country")
                    WxWarn.meteoalarmUrl(country)
                }
                AppState.wxWarnMessage.value = "Downloading…"
                val nowS = System.currentTimeMillis() / 1000
                val keep = LinkedHashMap<String, WxWarning>()
                val sc = WxWarn.Scanner()
                var bytes = 0L
                val c = open(url)
                try {
                    if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                    c.inputStream.use { inp ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            bytes += n
                            for (blk in sc.feed(buf, n)) {
                                val w = WxWarn.parse(blk, pos) ?: continue
                                if (w.expired(nowS) || !WxWarn.mine(w, NEAR_KM, areaFilter, source == 1)) continue
                                if (keep.size < 60) keep.putIfAbsent(w.key, w)
                            }
                            if (sc.gzip) break
                        }
                    }
                } finally { c.disconnect() }
                if (sc.gzip) throw IllegalStateException("the file came compressed: not supported yet")
                AppState.wxWarnings.value = keep.values.sortedWith(WxWarn.ORDER)
                AppState.wxWarnMessage.value = "${if (source == 1) "AEMET" else "Meteoalarm"}: ${keep.size} in your area of ${sc.blocks} in force (${bytes / 1024} kB) · ${Fmt.hhmm(System.currentTimeMillis())}"
                failed = false
            } catch (e: Exception) {
                failed = true
                AppState.wxWarnMessage.value = "Warnings: " + (e.message ?: e.javaClass.simpleName)
            } finally { busy = false }
        }, "wxwarn").start()
    }
}
