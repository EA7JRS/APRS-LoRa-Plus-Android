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

    private fun http(url: String, timeoutMs: Int = 15_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** AEMET national radar composite (Spain): the answer gives the address of the image. */
    fun aemetNational(key: String, done: (Result<Bitmap>) -> Unit) {
        exec.execute {
            done(runCatching {
                val url = WxRadar.aemetData(String(http(WxRadar.AEMET_NATIONAL + "?api_key=" + java.net.URLEncoder.encode(key, "UTF-8")), Charsets.ISO_8859_1)).getOrThrow()
                val img = http(url, 30_000)
                BitmapFactory.decodeByteArray(img, 0, img.size) ?: throw IllegalStateException("AEMET: the image could not be read")
            })
        }
    }
}
