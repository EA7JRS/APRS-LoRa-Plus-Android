/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Dapnet
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a DAPNET page through the network API (needs internet and a DAPNET account; the tracker only receives).
 * The default server only speaks plain HTTP: the user and password travel without encryption (see network_security_config).
 */
object DapnetSender {
    /** Runs on its own thread; [onDone] gets null on success or the error text. */
    fun send(settings: Settings, text: String, to: List<String>, emergency: Boolean, onDone: (String?) -> Unit) {
        val user = settings.dapnetUser
        val pass = settings.dapnetPassword
        val groups = Dapnet.groups(settings.dapnetGroup)
        val url = settings.dapnetUrl
        if (!Licence.isValid(settings)) { onDone("Callsign not validated in RadioID: pages cannot be sent"); return }
        if (user.isEmpty() || pass.isEmpty()) { onDone("Set your DAPNET user and password in the Connect tab"); return }
        Dapnet.check(text, to, groups)?.let { onDone(it); return }
        Thread({
            val result = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", Dapnet.authorization(user, pass))
                c.outputStream.use { it.write(Dapnet.body(text, to, groups, emergency).toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                c.disconnect()
                when (code) {
                    in 200..299 -> null
                    401 -> "DAPNET refused the user or password"
                    else -> "DAPNET answered HTTP $code"
                }
            } catch (e: Exception) {
                "No connection with DAPNET: ${e.message}"
            }
            onDone(result)
        }, "dapnet-send").start()
    }
}
