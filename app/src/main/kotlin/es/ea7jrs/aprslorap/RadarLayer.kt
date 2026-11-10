/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.util.LruCache
import es.ea7jrs.aprslorap.core.WxRadar
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Weather radar for the map: RainViewer frames (past 2 h) as a tile overlay, and the AEMET national composite as an image. */
object WxRadarSource {
    val frames = MutableStateFlow<WxRadar.Frames?>(null)
    val status = MutableStateFlow("")
    private val exec = Executors.newFixedThreadPool(3)
    @Volatile private var fetchedMs = 0L

    fun http(url: String, timeoutMs: Int = 15_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** RainViewer frame list, at most every 5 minutes (they change every 10). */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - fetchedMs < 5 * 60_000L && frames.value != null) return
        fetchedMs = now
        exec.execute {
            try {
                frames.value = WxRadar.parseFrames(String(http(WxRadar.RAINVIEWER_URL)))
                status.value = ""
            } catch (e: Exception) { status.value = "Radar not available: ${e.message}" }
        }
    }

    fun load(url: String, done: (Bitmap?) -> Unit) {
        exec.execute { done(try { http(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (_: Exception) { null }) }
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

/** Draws one RainViewer frame over the base map: tiles of the map's zoom (at most 7, drawn larger above it), half transparent. */
class RadarOverlay(private val map: MapView) : Overlay() {
    @Volatile var host: String = ""
    @Volatile var frame: WxRadar.Frame? = null
        set(v) { field = v; map.postInvalidate() }
    private val cache = LruCache<String, Bitmap>(96)
    private val pending = HashSet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 170 }
    private val a = Point()
    private val b = Point()

    override fun draw(c: Canvas, pj: Projection) {
        val f = frame ?: return
        if (host.isEmpty()) return
        val z = WxRadar.tileZoom(pj.zoomLevel)
        val n = 1 shl z
        val bb = pj.boundingBox
        val x0 = WxRadar.tileX(bb.lonWest, z); var x1 = WxRadar.tileX(bb.lonEast, z)
        if (x1 < x0) x1 += n                                            // across the antimeridian
        val y0 = WxRadar.tileY(bb.latNorth, z); val y1 = WxRadar.tileY(bb.latSouth, z)
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) return
        for (xx in x0..x1) for (y in y0..y1) {
            val x = ((xx % n) + n) % n
            val key = "${f.timeS}/$z/$x/$y"
            val bmp = cache.get(key)
            if (bmp == null) {
                val first = synchronized(pending) { pending.add(key) }
                if (first) WxRadarSource.load(WxRadar.tileUrl(host, f, z, x, y)) { got ->
                    synchronized(pending) { pending.remove(key) }
                    if (got != null) { cache.put(key, got); map.postInvalidate() }
                }
                continue
            }
            pj.toPixels(GeoPoint(WxRadar.tileLat(y, z), WxRadar.tileLon(xx, z)), a)
            pj.toPixels(GeoPoint(WxRadar.tileLat(y + 1, z), WxRadar.tileLon(xx + 1, z)), b)
            c.drawBitmap(bmp, null, Rect(a.x, a.y, b.x, b.y), paint)
        }
    }
}
