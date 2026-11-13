/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * WMS services as map tiles (as `tiles` of the plotter core): the image of each 256 px tile asked by its box in Web Mercator
 * (EPSG:3857), and the layer names read from the service's GetCapabilities.
 */
object Wms {
    private const val SHIFT = 20037508.342789244

    /** Box of the XYZ tile in EPSG:3857 metres: minX, minY, maxX, maxY. */
    fun bounds3857(z: Int, x: Int, y: Int): DoubleArray {
        val size = 2 * SHIFT / (1 shl z)
        val minX = -SHIFT + x * size
        val maxY = SHIFT - y * size
        return doubleArrayOf(minX, maxY - size, minX + size, maxY)
    }

    fun tileUrl(base: String, layers: String, z: Int, x: Int, y: Int, transparent: Boolean = true, format: String = "image/png"): String {
        val b = bounds3857(z, x, y)
        return base + (if ('?' in base) "&" else "?") +
            "SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + java.net.URLEncoder.encode(layers, "UTF-8") + "&STYLES=&CRS=EPSG:3857" +
            String.format(Locale.US, "&BBOX=%.2f,%.2f,%.2f,%.2f", b[0], b[1], b[2], b[3]) +
            "&WIDTH=256&HEIGHT=256&FORMAT=" + format.replace("/", "%2F") + "&TRANSPARENT=" + if (transparent) "TRUE" else "FALSE"
    }

    /**
     * Layers to ask for, from a GetCapabilities answer: the root layer when it has a name (it draws everything), otherwise the
     * named layers without sub-layers (at most [max], joined with commas). Null if the answer has no layer.
     */
    fun layers(capabilities: String, max: Int = 30): String? {
        val cap = capabilities.indexOf("<Capability").takeIf { it >= 0 } ?: return null
        // style names are not layers
        val xml = capabilities.substring(cap).replace(Regex("<Style>.*?</Style>", RegexOption.DOT_MATCHES_ALL), "")
        val first = xml.indexOf("<Layer").takeIf { it >= 0 } ?: return null
        val nextLayer = xml.indexOf("<Layer", first + 6)
        val rootPart = if (nextLayer > 0) xml.substring(first, nextLayer) else xml.substring(first)
        Regex("<Name>\\s*([^<]+?)\\s*</Name>").find(rootPart)?.let { return it.groupValues[1] }
        val leaves = ArrayList<String>()
        val tag = Regex("<(/?)Layer\\b[^>]*>|<Name>\\s*([^<]+?)\\s*</Name>")
        var depth = 0; var pendingName: String? = null; var hasChild = BooleanArray(64)
        for (m in tag.findAll(xml.substring(first))) {
            when {
                m.value.startsWith("<Name") -> if (depth > 0) pendingName = pendingName ?: m.groupValues[2]
                m.groupValues[1] == "/" -> {
                    if (depth in 1..63 && !hasChild[depth] && pendingName != null) leaves.add(pendingName!!)
                    pendingName = null
                    depth--
                }
                else -> {
                    if (depth in 0..63) hasChild[depth] = true
                    depth++
                    if (depth in 0..63) hasChild[depth] = false
                    pendingName = null
                }
            }
        }
        return leaves.distinct().take(max).joinToString(",").ifEmpty { null }
    }
}

/** Day / night line: the point where the sun is overhead and the night side as a polygon for the map. */
object Terminator {
    /** Latitude and longitude where the sun is at the zenith (NOAA low-precision formulas, good to a few tenths of a degree). */
    fun subsolar(timeMs: Long): Pair<Double, Double> {
        val d = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val dec = Math.toDegrees(Math.asin(sin(e) * sin(l)))
        val ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l)))
        val gmst = (280.46061837 + 360.98564736629 * d) % 360
        var lon = ra - gmst
        lon = ((lon + 540) % 360 + 360) % 360 - 180
        return dec to lon
    }

    /** Night side as a closed ring of (lat, lon) points, from −180° to 180° and closed over the dark pole (Mercator limit ±85°). */
    fun nightPolygon(timeMs: Long, steps: Int = 180): List<Pair<Double, Double>> {
        val (dec0, sunLon) = subsolar(timeMs)
        val dec = if (kotlin.math.abs(dec0) < 0.01) 0.01 else dec0          // at an equinox the line is almost a meridian pair
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0..steps) {
            val lon = -180.0 + 360.0 * i / steps
            val lat = Math.toDegrees(atan(-cos(Math.toRadians(lon - sunLon)) / tan(Math.toRadians(dec))))
            pts.add(lat.coerceIn(-85.0, 85.0) to lon)
        }
        val pole = if (dec > 0) -85.0 else 85.0                               // the pole without sun
        pts.add(pole to 180.0); pts.add(pole to -180.0)
        return pts
    }

    /** True if the sun is below the horizon at that place (to check the polygon). */
    fun isNight(timeMs: Long, lat: Double, lon: Double): Boolean {
        val (dec, sunLon) = subsolar(timeMs)
        val h = Math.toRadians(lon - sunLon)
        val alt = Math.asin(sin(Math.toRadians(lat)) * sin(Math.toRadians(dec)) + cos(Math.toRadians(lat)) * cos(Math.toRadians(dec)) * cos(h))
        return alt < 0 && alt > -PI
    }
}

/** Magnitude of an earthquake alert ("M4.1 Earthquake"), null otherwise. */
fun OfficialAlert.magnitude(): Double? = if (!src.quake) null else Regex("M\\s?(\\d+(?:\\.\\d+)?)").find(event)?.groupValues?.get(1)?.toDoubleOrNull()

/**
 * Keeps the calls to a service under its limit per minute (OpenWeather's free key: 60 calls / min for everything done with
 * that key, and every map tile is a call). [waitMs] says how long to wait before the next call and counts it.
 */
class RateLimiter(val perMinute: Int, val windowMs: Long = 60_000L) {
    private val times = ArrayDeque<Long>()

    /** Milliseconds to wait before calling (0 = now). The call is counted at the moment it will be made. */
    @Synchronized
    fun waitMs(nowMs: Long): Long {
        while (times.isNotEmpty() && times.first() <= nowMs - windowMs) times.removeFirst()
        val at = if (times.size < perMinute) nowMs else maxOf(nowMs, times[times.size - perMinute] + windowMs)
        times.addLast(at)
        return at - nowMs
    }

    /** Calls counted in the minute that ends at [nowMs] (those already made). */
    @Synchronized
    fun used(nowMs: Long): Int = times.count { it > nowMs - windowMs && it <= nowMs }
}
