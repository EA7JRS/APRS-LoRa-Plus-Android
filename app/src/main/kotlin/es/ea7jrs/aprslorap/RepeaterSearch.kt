/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.RepeaterBook
import java.net.HttpURLConnection
import java.net.URL

/**
 * Repeater search in RepeaterBook with the user's own token, to make a repeater a fixed rotor target. Results are only shown,
 * never stored (RepeaterBook terms).
 */
object RepeaterSearch {
    fun search(s: Settings, callsign: String, city: String, country: String, freqMhz: String) {
        if (AppState.rbBusy.value) return
        if (BuildConfig.PLAY) { AppState.rbMessage.value = "Use the RepeaterBook app (Connect) in this version"; return }
        val token = s.rbToken.trim()
        val agent = s.rbUserAgent.trim()
        if (token.isEmpty()) { AppState.rbMessage.value = "Put your RepeaterBook token (rbuapp_...) first"; return }
        if (!agent.contains('@')) { AppState.rbMessage.value = "The User-Agent must include your contact e-mail (RepeaterBook asks for it)"; return }
        if (callsign.isBlank() && city.isBlank() && freqMhz.isBlank()) { AppState.rbMessage.value = "Give a callsign, a city or a frequency"; return }
        val url = RepeaterBook.url(s.rbNorthAmerica, callsign, city, country, freqMhz)
        AppState.rbBusy.value = true
        AppState.rbMessage.value = "Searching..."
        Thread({
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false                       // the API asks to treat any redirect as a stop
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", agent)
                c.setRequestProperty("X-RB-App-Token", token)
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                val retry = c.getHeaderField("Retry-After")
                c.disconnect()
                when {
                    code == 429 -> AppState.rbMessage.value = "RepeaterBook asks to wait" + (retry?.let { " $it s" } ?: "") + " before searching again"
                    code in 300..399 -> AppState.rbMessage.value = "RepeaterBook redirected the request (HTTP $code): check the token and the User-Agent"
                    else -> RepeaterBook.parse(body).fold(
                        { list ->
                            AppState.rbResults.value = list.take(100)
                            AppState.rbMessage.value = if (list.isEmpty()) "No repeaters found" else "${list.size} repeater(s). ${RepeaterBook.ATTRIBUTION}"
                        },
                        { e -> AppState.rbMessage.value = "RepeaterBook (HTTP $code): ${e.message}" },
                    )
                }
            } catch (e: Exception) {
                AppState.rbMessage.value = "Search failed: ${e.message}"
            } finally {
                AppState.rbBusy.value = false
            }
        }, "repeaterbook").start()
    }
}
