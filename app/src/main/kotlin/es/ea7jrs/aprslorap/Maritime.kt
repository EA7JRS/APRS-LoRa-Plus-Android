/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.MtCheck
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the maritime call sign of an MMSI against MarineTraffic, on the phone's internet. The ITU MARS is confirmed by hand
 * (no API): the Vessel screen opens [MtCheck.MARS_URL] in the browser. Used by the Vessel screen (mirrors the plotter).
 */
object Maritime {
    /** Asks MarineTraffic on its own thread. [onDone] gets the result and, on MATCH / MISMATCH, the call sign it has. */
    fun check(key: String, mmsi: Long, call: String, onDone: (MtCheck.Result, String) -> Unit) {
        if (!MtCheck.validKey(key)) { onDone(MtCheck.Result.BAD_KEY, ""); return }
        Thread({
            val (result, mtCall) = try {
                val c = URL(MtCheck.searchUrl(key, mmsi)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                MtCheck.parse(code, body, call)
            } catch (e: Exception) {
                MtCheck.Result.FAILED to ""
            }
            onDone(result, mtCall)
        }, "marinetraffic").start()
    }
}
