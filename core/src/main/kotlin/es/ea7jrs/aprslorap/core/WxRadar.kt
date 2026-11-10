/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Weather radar layer of the map.
 * RainViewer Weather Maps API (personal and educational use): past radar, 2 h in 10 min steps, Web Mercator tiles up to zoom 7,
 * only the "Universal Blue" colour scheme (2) since 2026-01-01. AEMET OpenData: the Spanish national composite as an image
 * (a free key; the answer gives the address of the data, which is the image).
 */
object WxRadar {
    const val RAINVIEWER_URL = "https://api.rainviewer.com/public/weather-maps.json"
    const val MAX_ZOOM = 7
    const val COLOR = 2                       // Universal Blue
    const val OPTIONS = "1_1"                 // smoothed, snow in its own colours
    const val AEMET_NATIONAL = "https://opendata.aemet.es/opendata/api/red/radar/nacional"

    data class Frame(val timeS: Long, val path: String)
    data class Frames(val host: String, val past: List<Frame>)

    fun parseFrames(json: String): Frames? {
        val o = Json.parseObject(json) ?: return null
        val host = o.str("host") ?: return null
        val past = o.obj("radar")?.objList("past")?.mapNotNull { f -> val t = f.long("time"); val p = f.str("path"); if (t != null && p != null) Frame(t, p) else null }
            ?: return null
        return Frames(host, past.sortedBy { it.timeS })
    }

    fun tileUrl(host: String, f: Frame, z: Int, x: Int, y: Int, size: Int = 256): String =
        "$host${f.path}/$size/$z/$x/$y/$COLOR/$OPTIONS.png"

    /** Zoom of the radar tiles for a map zoom: the map's own, at most [MAX_ZOOM] (above it the tiles are drawn larger). */
    fun tileZoom(mapZoom: Double): Int = floor(mapZoom).toInt().coerceIn(0, MAX_ZOOM)

    fun tileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    fun tileY(lat: Double, z: Int): Int {
        val l = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(l) + 1.0 / kotlin.math.cos(l)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0
    fun tileLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    /** Address of the data in an AEMET OpenData answer ({"estado":200,"datos":"https://..."}), or the error text. */
    fun aemetData(json: String): Result<String> {
        val o = Json.parseObject(json) ?: return Result.failure(IllegalStateException("AEMET: unreadable answer"))
        val d = o.str("datos")
        return if ((o.int("estado") ?: 0) == 200 && d != null) Result.success(d)
            else Result.failure(IllegalStateException("AEMET: " + (o.str("descripcion") ?: "error ${o.int("estado")}")))
    }

    fun ago(nowS: Long, f: Frame): String {
        val m = ((nowS - f.timeS) / 60).coerceAtLeast(0)
        return if (m < 2) "now" else "−$m min"
    }
}
