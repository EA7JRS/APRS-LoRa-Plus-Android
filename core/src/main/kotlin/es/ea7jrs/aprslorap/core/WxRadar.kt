/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Weather radar of the map: AEMET OpenData, the Spanish national composite as an image (a free key; the answer gives the address
 * of the data, which is the image; reuse allowed with attribution). RainViewer was removed (2026-11-10, Manuel): its free API is
 * for personal and educational use only, and the app will be sold.
 */
object WxRadar {
    const val AEMET_NATIONAL = "https://opendata.aemet.es/opendata/api/red/radar/nacional"
    /** Lightning detected by AEMET's network in the last hours, as an image of Spain. */
    const val AEMET_LIGHTNING = "https://opendata.aemet.es/opendata/api/red/rayos/mapa"

    /** Address of the data in an AEMET OpenData answer ({"estado":200,"datos":"https://..."}), or the error text. */
    fun aemetData(json: String): Result<String> {
        val o = Json.parseObject(json) ?: return Result.failure(IllegalStateException("AEMET: unreadable answer"))
        val d = o.str("datos")
        return if ((o.int("estado") ?: 0) == 200 && d != null) Result.success(d)
            else Result.failure(IllegalStateException("AEMET: " + (o.str("descripcion") ?: "error ${o.int("estado")}")))
    }
}
