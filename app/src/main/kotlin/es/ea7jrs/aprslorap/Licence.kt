/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.CallsignCheck
import java.net.HttpURLConnection
import java.net.URL

/**
 * Amateur radio licence check: the callsign (without SSID) must have a DMR ID / CCS7 ID in RadioID.net.
 * Without a validated callsign the app only listens: no APRS frames, no iGate, no DAPNET pages, no tracker commands that
 * transmit or change the radio. The result is kept, so the check needs internet only once (and again after [RECHECK_MS]).
 */
object Licence {
    const val RECHECK_MS = 90L * 24 * 3600 * 1000

    fun isValid(settings: Settings): Boolean {
        val base = CallsignCheck.base(settings.callsign) ?: return false
        return base == settings.validatedCall
    }

    fun summary(settings: Settings): String {
        val base = CallsignCheck.base(settings.callsign) ?: return "Not a valid amateur callsign"
        if (base != settings.validatedCall) return "$base not validated: the app only listens"
        val who = listOf(settings.validatedName, settings.validatedCountry).filter { it.isNotBlank() }.joinToString(", ")
        return "$base validated in RadioID (DMR/CCS7 ID ${settings.validatedIds})" + if (who.isNotEmpty()) " · $who" else ""
    }

    /** Asks RadioID on its own thread; [onDone] gets null if the callsign was found, or the reason. */
    fun validate(settings: Settings, onDone: (String?) -> Unit) {
        val base = CallsignCheck.base(settings.callsign)
        if (base == null) { onDone("Not a valid amateur callsign"); return }
        Thread({
            val result = try {
                val c = URL(CallsignCheck.url(base)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
                val code = c.responseCode
                val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
                c.disconnect()
                if (code !in 200..299) "RadioID answered HTTP $code"
                else {
                    val r = CallsignCheck.parse(body, base)
                    when {
                        r == null -> "Unexpected answer from RadioID"
                        !r.found -> "$base has no DMR ID / CCS7 in RadioID.net (register it there first)"
                        else -> {
                            settings.saveValidation(base, r.ids.joinToString(","), r.name, r.country)
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                "No connection with RadioID: ${e.message}"
            }
            onDone(result)
        }, "radioid").start()
    }

    /** At start: if validated long ago, check again in the background; a network failure keeps the old result. */
    fun recheckIfOld(settings: Settings) {
        if (!isValid(settings)) return
        if (System.currentTimeMillis() - settings.validatedAtMs < RECHECK_MS) return
        val base = settings.validatedCall
        validate(settings) { err ->
            if (err != null && err.contains("has no DMR ID")) {
                settings.clearValidation()
                AppState.error.value = "$base is no longer in RadioID: the app now only listens"
            }
        }
    }
}
