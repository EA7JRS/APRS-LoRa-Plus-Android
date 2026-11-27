// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Ionosondes for HF & propagation: the phone downloads the GIRO network list through the KC2G service and keeps the nearest
// trustworthy station (recent, close, good autoscale) at the tracker's GNSS position or the phone's last one. Its measured foF2 /
// MUF feed the band conditions and go to the tracker in the "spacewx" command, which prefers a measured MUF over its estimate.
// KC2G terms of use for a paid app are unread: the user turns this off in App settings if needed; the data is credited to the GIRO
// network and the KC2G service.
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.HfProp
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object HfFetcher {
    @Volatile private var busy = false
    @Volatile private var lastMs = 0L
    private const val UA = "APRSLoRaPlus-Android (+https://ea7jrs.github.io/APRS-LoRa-Plus/)"

    /** Download the ionosondes and keep the nearest one to our position. Debounced to once every 10 min unless [force]. */
    fun fetchNow(ctx: Context, settings: Settings = Settings(ctx), force: Boolean = false) {
        if (!settings.ionosondesOn) { AppState.ionosonde.value = null; return }
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastMs < 10 * 60_000L)) return
        busy = true
        Thread {
            try {
                val pos = Own.position()
                val json = text(HfProp.IONOSONDE_URL)
                val stations = HfProp.readIonosondes(json)
                val near = if (pos != null) HfProp.nearestIonosonde(stations, pos.first, pos.second, System.currentTimeMillis()) else null
                AppState.ionosonde.value = near
                lastMs = System.currentTimeMillis()
                if (near != null) OfficialAlertsFetcher.syncTracker()      // push the measured foF2 / MUF to the tracker
            } catch (_: Exception) {
            } finally { busy = false }
        }.start()
    }

    private fun text(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 4_000_000) throw IllegalStateException("too big") }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally { c.disconnect() }
    }
}
