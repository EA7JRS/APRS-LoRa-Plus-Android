/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import es.ea7jrs.aprslorap.core.WxRadar
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Weather radar of the map: the AEMET national composite as an image (AEMET OpenData, the user's free key; reuse with attribution). */
object WxRadarSource {
    private val exec = Executors.newSingleThreadExecutor()

    /** An error answer: AEMET sends JSON with "descripcion" (e.g. "API key invalido" for HTTP 401). */
    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

    private fun http(url: String, timeoutMs: Int = 15_000, key: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "APRSLoRaPlus/${BuildConfig.VERSION_NAME} (Android)")
        c.setRequestProperty("Accept", "application/json")
        if (key != null) c.setRequestProperty("api_key", key)        // AEMET OpenData takes the key in the header too
        try {
            if (c.responseCode !in 200..299)
                throw HttpError(c.responseCode, try { c.errorStream?.use { String(it.readBytes(), Charsets.ISO_8859_1) } ?: "" } catch (_: Exception) { "" })
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** AEMET national radar composite (Spain): the answer gives the address of the image. */
    fun aemetNational(keyIn: String, done: (Result<Bitmap>) -> Unit) = aemetImage(WxRadar.AEMET_NATIONAL, keyIn, done)

    /** AEMET lightning map of Spain. */
    fun aemetLightning(keyIn: String, done: (Result<Bitmap>) -> Unit) = aemetImage(WxRadar.AEMET_LIGHTNING, keyIn, done)

    /** An AEMET OpenData product that is an image: first the JSON with the address of the data, then the image. */
    fun aemetImage(endpoint: String, keyIn: String, done: (Result<Bitmap>) -> Unit) {
        // the key is a long JWT ("eyJ..."): without spaces, line breaks or quotes copied from the e-mail
        val key = keyIn.filterNot { it.isWhitespace() || it == '"' || it == '\'' }
        exec.execute {
            done(runCatching {
                if (key.isEmpty()) throw IllegalStateException("AEMET: put your OpenData API key")
                val answer = try {
                    http(endpoint + "?api_key=" + java.net.URLEncoder.encode(key, "UTF-8"), key = key)
                } catch (e: HttpError) {
                    val why = WxRadar.aemetData(e.body).exceptionOrNull()?.message?.removePrefix("AEMET: ")?.takeIf { it.isNotBlank() && !it.startsWith("unreadable") }
                    throw IllegalStateException(when (e.code) {
                        401 -> "AEMET rejected the key (HTTP 401${why?.let { ": $it" } ?: ""}). Paste the whole key from AEMET's e-mail (a long text that starts eyJ); a new key has to be confirmed first from the link in that e-mail."
                        429 -> "AEMET: too many requests, wait a minute"
                        else -> "AEMET: HTTP ${e.code}" + (why?.let { ": $it" } ?: "")
                    })
                }
                val url = WxRadar.aemetData(String(answer, Charsets.ISO_8859_1)).getOrThrow()
                val img = http(url, 30_000)
                BitmapFactory.decodeByteArray(img, 0, img.size) ?: throw IllegalStateException("AEMET: the image could not be read")
            })
        }
    }
}
