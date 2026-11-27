/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import es.ea7jrs.aprslorap.core.AlertSrc
import es.ea7jrs.aprslorap.core.AprsSymbols
import es.ea7jrs.aprslorap.core.Aviation
import es.ea7jrs.aprslorap.core.Terminator
import es.ea7jrs.aprslorap.core.magnitude
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * Base maps and overlays. Each tile server has its own terms: OpenStreetMap (tile usage policy: app User-Agent, no bulk
 * download), IGN Spain (CC BY 4.0), OpenSeaMap (CC BY-SA), Esri World Imagery (needs the user's own ArcGIS key).
 */
object MapLayers {
    data class Layer(val id: String, val label: String, val attribution: String)

    val BASE = listOf(
        Layer("osm", "OpenStreetMap", "© OpenStreetMap contributors"),
        Layer("pnoa", "Satellite (IGN PNOA)", "© Instituto Geográfico Nacional · PNOA (CC BY 4.0)"),
        Layer("ign", "Topographic IGN", "© Instituto Geográfico Nacional (CC BY 4.0)"),
        Layer("ignbase", "IGN Base", "IGNBaseTodo CC-BY 4.0 ign.es"),
        Layer("esri", "Satellite (Esri)", "Powered by Esri · Esri, Maxar, Earthstar Geographics"),
        Layer("dark", "Dark (night)", "© OpenStreetMap contributors"),
    )

    private class Wmts(name: String, private val url: String, private val layer: String) :
        OnlineTileSourceBase(name, 0, 19, 256, ".jpg", arrayOf(url)) {
        override fun getTileURLString(index: Long): String =
            "$url?service=WMTS&request=GetTile&version=1.0.0&layer=$layer&style=default&tilematrixset=GoogleMapsCompatible&format=image/jpeg" +
                "&TileMatrix=${MapTileIndex.getZoom(index)}&TileCol=${MapTileIndex.getX(index)}&TileRow=${MapTileIndex.getY(index)}"
    }

    private class Esri(private val key: String) :
        OnlineTileSourceBase("Esri-World-Imagery", 0, 19, 256, ".jpg", arrayOf("https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/")) {
        override fun getTileURLString(index: Long): String =
            baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}?token=$key"
    }

    /** IGN Base (IGNBaseTodo): ready tiles with TMS numbering (y from the bottom), as in the plotter core. */
    private object IgnBase : OnlineTileSourceBase("IGN-Base", 0, 17, 256, ".jpeg", arrayOf("https://tms-ign-base.idee.es/1.0.0/IGNBaseTodo/")) {
        override fun getTileURLString(index: Long): String {
            val z = MapTileIndex.getZoom(index)
            return baseUrl + "$z/${MapTileIndex.getX(index)}/${(1 shl z) - 1 - MapTileIndex.getY(index)}.jpeg"
        }
    }

    /** Opacity for a tile overlay: the alpha of every pixel scaled. */
    fun alpha(pct: Int) = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, pct / 100f, 0f,
    )))

    fun source(id: String, arcgisKey: String): ITileSource = when (id) {
        "ignbase" -> IgnBase
        "pnoa" -> Wmts("IGN-PNOA", "https://www.ign.es/wmts/pnoa-ma", "OI.OrthoimageCoverage")
        "ign" -> Wmts("IGN-MTN", "https://www.ign.es/wmts/mapa-raster", "MTN")
        "esri" -> if (arcgisKey.isNotBlank()) Esri(arcgisKey) else TileSourceFactory.MAPNIK
        else -> TileSourceFactory.MAPNIK
    }

    /** Night map: OpenStreetMap with the colours inverted and dimmed (no extra tile server). */
    val NIGHT = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
        -0.75f, 0f, 0f, 0f, 220f,
        0f, -0.75f, 0f, 0f, 225f,
        0f, 0f, -0.75f, 0f, 235f,
        0f, 0f, 0f, 1f, 0f,
    )))

    val SEAMARKS: ITileSource = XYTileSource("OpenSeaMap", 0, 18, 256, ".png", arrayOf("https://tiles.openseamap.org/seamark/"))

    private var configured = false
    fun configure(ctx: Context) {
        if (configured) return
        val c = Configuration.getInstance()
        c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        c.userAgentValue = "APRSLoRaPlus-Android/" + try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "1" }
        c.osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        c.osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        c.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
        c.tileFileSystemCacheTrimBytes = 160L * 1024 * 1024
        configured = true
    }
}

/** Marker image: the APRS symbol with the callsign beside it. */
internal object MarkerArt {
    private val cache = LruCache<String, Bitmap>(400)

    /** [sub]: a second line under the callsign (the weather of a weather station: "21° 12 km/h"). */
    /** [course]: for a car that moves, our own car seen from above turned to where it goes (10° steps), instead of the symbol. */
    fun station(ctx: Context, sym: AprsSymbols.Symbol?, call: String, selected: Boolean, emergency: Boolean, sub: String? = null, course: Int? = null): Bitmap {
        val turn = course?.let { ((it + 5) / 10 * 10) % 360 }
        val key = "$call|$sym|$selected|$emergency|$sub|$turn"
        cache.get(key)?.let { return it }
        val d = ctx.resources.displayMetrics.density
        val symPx = (26 * d).toInt()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (emergency) 0xFFFF8A80.toInt() else 0xFFE8EEF3.toInt(); textSize = 13 * d; isFakeBoldText = true
            typeface = try { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.barlow_condensed_bold) } catch (_: Exception) { null }
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF28B5E1.toInt(); textSize = 11.5f * d; typeface = text.typeface }
        val tw = maxOf(text.measureText(call), sub?.let { small.measureText(it) } ?: 0f)
        val pad = 5 * d
        val w = (symPx + 4 * d + tw + pad * 2).toInt()
        val h = (symPx + 4 * d).toInt()
        val half = if (sub == null) 10 * d else 16 * d
        val hb = maxOf(h, (2 * half + 2 * d).toInt())
        val b = Bitmap.createBitmap(w, hb, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.translate(0f, (hb - h) / 2f)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFFFB347.toInt() }
        if (selected) c.drawCircle(h / 2f, h / 2f, h / 2f - 1.5f * d, ring)
        if (turn != null) car(c, h / 2f, h / 2f, d, turn.toFloat(), emergency)
        else if (sym != null) c.drawBitmap(AprsSprites.bitmap(ctx, sym, symPx), 2 * d, 2 * d, null)
        else c.drawCircle(h / 2f, h / 2f, 6 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9131A22.toInt() }
        val left = symPx + 4 * d
        c.drawRoundRect(RectF(left, h / 2f - half, left + tw + pad * 2, h / 2f + half), 4 * d, 4 * d, bg)
        if (sub == null) c.drawText(call, left + pad, h / 2f + 4.5f * d, text)
        else { c.drawText(call, left + pad, h / 2f - 2 * d, text); c.drawText(sub, left + pad, h / 2f + 11.5f * d, small) }
        cache.put(key, b)
        return b
    }

    /** A car seen from above, nose to [deg] (0 = north): body, windscreen, rear window and headlights. Our own drawing. */
    private fun car(c: Canvas, cx: Float, cy: Float, d: Float, deg: Float, emergency: Boolean) {
        c.save(); c.rotate(deg, cx, cy)
        val body = RectF(cx - 5.5f * d, cy - 11.5f * d, cx + 5.5f * d, cy + 11.5f * d)
        c.drawRoundRect(body, 4 * d, 4.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (emergency) 0xFFE53935.toInt() else 0xFF28B5E1.toInt() })
        c.drawRoundRect(body, 4 * d, 4.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = 0xFF131A22.toInt() })
        val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF131A22.toInt() }
        c.drawRoundRect(RectF(cx - 4 * d, cy - 6 * d, cx + 4 * d, cy - 2 * d), 1.5f * d, 1.5f * d, glass)       // windscreen
        c.drawRoundRect(RectF(cx - 3.6f * d, cy + 5 * d, cx + 3.6f * d, cy + 8 * d), 1.2f * d, 1.2f * d, glass)   // rear window
        val lamp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt() }
        c.drawCircle(cx - 3.4f * d, cy - 10 * d, 1.2f * d, lamp); c.drawCircle(cx + 3.4f * d, cy - 10 * d, 1.2f * d, lamp)
        c.restore()
    }

    /** A small label for the map (alert, warning, earthquake): text on a coloured rounded box. */
    fun tag(ctx: Context, text: String, bg: Int, fg: Int): Bitmap {
        val key = "tag|$text|$bg|$fg"
        cache.get(key)?.let { return it }
        val d = ctx.resources.displayMetrics.density
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = 13 * d; isFakeBoldText = true
            typeface = try { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.barlow_condensed_bold) } catch (_: Exception) { null }
        }
        val w = (p.measureText(text) + 12 * d).toInt(); val h = (22 * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 6 * d, 6 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        c.drawText(text, 6 * d, h / 2f + 4.5f * d, p)
        cache.put(key, b)
        return b
    }

    /**
     * A METAR station for the map: dot in the flight-category colour, wind barb towards where the wind comes from
     * (barb 10 kn, half 5 kn, flag 50 kn), temperature over the dew point on the left and the ICAO code with the gust on the
     * right. Returns the bitmap and the anchor (fractions) of the dot.
     */
    fun metar(ctx: Context, m: Aviation.Metar): Triple<Bitmap, Float, Float> {
        val d = ctx.resources.displayMetrics.density
        val key = "metar|${m.icao}|${m.cat}|${m.wdir}|${m.wspdKt}|${m.wgstKt}|${m.tempC}|${m.dewpC}"
        val w = (150 * d).toInt(); val h = (80 * d).toInt()
        val cx = 50 * d; val cy = 40 * d
        val cached = cache.get(key)
        if (cached != null) return Triple(cached, cx / w, cy / h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF131A22.toInt(); strokeWidth = 2 * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val halo = Paint(ink).apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 4.5f * d }
        if (m.wspdKt > 2 && m.wdir >= 0) {
            val a = Math.toRadians(m.wdir.toDouble()); val sx = Math.sin(a).toFloat(); val sy = -Math.cos(a).toFloat(); val px = Math.cos(a).toFloat(); val py = Math.sin(a).toFloat()
            val l = 30 * d
            val path = android.graphics.Path()
            path.moveTo(cx + 8 * d * sx, cy + 8 * d * sy); path.lineTo(cx + l * sx, cy + l * sy)
            var k = (m.wspdKt + 2) / 5 * 5; var pos = 0f
            fun at(t: Float) = (cx + (l - t) * sx) to (cy + (l - t) * sy)
            val flags = android.graphics.Path()
            while (k >= 50) { val (x1, y1) = at(pos); val (x2, y2) = at(pos + 7 * d); flags.moveTo(x1, y1); flags.lineTo(x1 + 12 * d * px, y1 + 12 * d * py); flags.lineTo(x2, y2); flags.close(); k -= 50; pos += 9 * d }
            while (k >= 10) { val (x, y) = at(pos); path.moveTo(x, y); path.lineTo(x + 12 * d * px, y + 12 * d * py); k -= 10; pos += 6 * d }
            if (k >= 5) { if (pos == 0f) pos = 6 * d; val (x, y) = at(pos); path.moveTo(x, y); path.lineTo(x + 6 * d * px, y + 6 * d * py) }
            c.drawPath(path, halo); c.drawPath(flags, halo); c.drawPath(path, ink)
            c.drawPath(flags, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF131A22.toInt() })
        }
        c.drawCircle(cx, cy, 8 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.cat.color.toInt() })
        c.drawCircle(cx, cy, 8 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFFFFFFFF.toInt() })
        val tf = try { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.barlow_condensed_bold) } catch (_: Exception) { null }
        fun txt(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size * d; typeface = tf; textAlign = Paint.Align.RIGHT }
        val outline = { p: Paint -> Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xE6FFFFFF.toInt() } }
        m.tempC?.let { val p = txt(0xFFB8460B.toInt(), 13f); val t = String.format(java.util.Locale.US, "%.0f", it); c.drawText(t, cx - 11 * d, cy - 3 * d, outline(p)); c.drawText(t, cx - 11 * d, cy - 3 * d, p) }
        m.dewpC?.let { val p = txt(0xFF1565C0.toInt(), 13f); val t = String.format(java.util.Locale.US, "%.0f", it); c.drawText(t, cx - 11 * d, cy + 12 * d, outline(p)); c.drawText(t, cx - 11 * d, cy + 12 * d, p) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EEF3.toInt(); textSize = 12.5f * d; typeface = tf }
        val gust = Paint(label).apply { color = 0xFFFFB347.toInt() }
        val gs = if (m.wgstKt > 0) " G${m.wgstKt}" else ""
        val lw = label.measureText(m.icao) + gust.measureText(gs) + 8 * d
        val lx = cx + 11 * d; val ly = cy + 3 * d
        c.drawRoundRect(RectF(lx, ly, lx + lw, ly + 18 * d), 4 * d, 4 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9131A22.toInt() })
        c.drawText(m.icao, lx + 4 * d, ly + 13.5f * d, label)
        if (gs.isNotEmpty()) c.drawText(gs, lx + 4 * d + label.measureText(m.icao), ly + 13.5f * d, gust)
        cache.put(key, b)
        return Triple(b, cx / w, cy / h)
    }

    fun own(ctx: Context, courseDeg: Double?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (34 * d).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawCircle(s / 2f, s / 2f, s / 2f - d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF131A22.toInt() })
        c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xFF3CE68A.toInt() })
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3CE68A.toInt() }
        if (courseDeg == null) { c.drawCircle(s / 2f, s / 2f, 5 * d, g); return b }
        c.save(); c.rotate(courseDeg.toFloat(), s / 2f, s / 2f)
        val p = android.graphics.Path().apply {
            moveTo(s / 2f, 7 * d); lineTo(s / 2f + 7 * d, s - 8 * d); lineTo(s / 2f, s - 12 * d); lineTo(s / 2f - 7 * d, s - 8 * d); close()
        }
        c.drawPath(p, g); c.restore()
        return b
    }
}

/** What a touched shape on the map shows: title, rows and source. */
internal data class MapCard(val title: String, val color: Int, val rows: List<Pair<String, String>>, val source: String,
                           val lat: Double? = null, val lon: Double? = null, val mono: List<Pair<String, String>> = emptyList())

/**
 * Day/night shade drawn directly, immune to osmdroid's polygon-fill of a world-spanning band (which shaded the wrong
 * side on real devices). For every screen column it takes the longitude there, computes the terminator latitude and
 * projects it, then fills from that curve to the edge on the dark-pole side (top when the sun is south, bottom when
 * north). All-day and all-night views fall out of the same fill.
 */
internal class NightOverlay(private val timeMs: Long, fillColor: Int) : Overlay() {
    private val fill = android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL; color = fillColor; isAntiAlias = true }
    private val stroke = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE; color = 0xCCFFB347.toInt(); strokeWidth = 2f; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 10f), 0f)
    }

    // osmdroid 6.1.x calls draw(Canvas, Projection); keep the deprecated 3-arg too, delegating, for other versions.
    override fun draw(c: android.graphics.Canvas, mapView: MapView, shadow: Boolean) { if (!shadow) draw(c, mapView.projection) }

    override fun draw(c: android.graphics.Canvas, proj: org.osmdroid.views.Projection) {
        val w = c.width; val h = c.height
        if (w == 0 || h == 0) return
        val (dec0, sunLon) = Terminator.subsolar(timeMs)
        val dec = if (kotlin.math.abs(dec0) < 0.01) 0.01 else dec0
        val n = 128
        val xs = FloatArray(n + 1); val ys = FloatArray(n + 1)
        val pt = android.graphics.Point()
        for (i in 0..n) {
            val x = w.toFloat() * i / n
            val lon = proj.fromPixels(x.toInt(), h / 2).longitude
            val termLat = Math.toDegrees(kotlin.math.atan(-kotlin.math.cos(Math.toRadians(lon - sunLon)) / kotlin.math.tan(Math.toRadians(dec))))
            proj.toPixels(GeoPoint(termLat.coerceIn(-85.0, 85.0), lon), pt)
            xs[i] = x; ys[i] = pt.y.toFloat()
        }
        val edgeY = if (dec < 0) 0f else h.toFloat()                           // dark pole: north when the sun is south
        val path = android.graphics.Path(); path.moveTo(xs[0], ys[0])
        for (i in 1..n) path.lineTo(xs[i], ys[i])
        path.lineTo(xs[n], edgeY); path.lineTo(xs[0], edgeY); path.close()
        c.drawPath(path, fill)
        val curve = android.graphics.Path(); curve.moveTo(xs[0], ys[0])
        for (i in 1..n) curve.lineTo(xs[i], ys[i])
        c.drawPath(curve, stroke)
    }
}

private class MapHolder(val map: MapView) {
    var layerId = ""
    var esriKey = ""
    var seamarks: TilesOverlay? = null
    /** Tile overlays on the map, by overlay id, with the name of their source (it changes when a key or the layer names change). */
    val tiles = LinkedHashMap<String, Pair<String, TilesOverlay>>()
    val dynamic = ArrayList<Overlay>()
    var centered = false
    var focused: String? = null
}

@Composable
fun MapScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val all = rememberStationViews()
    val gnss by AppState.gnss.collectAsState()
    val rotorPos by AppState.rotorPos.collectAsState()
    val rotor by AppState.rotor.collectAsState()
    var layer by remember { mutableStateOf(settings.mapLayer) }
    var sea by remember { mutableStateOf(settings.mapSeamarks) }
    var rings by remember { mutableStateOf(settings.mapRings) }
    var beam by remember { mutableStateOf(settings.mapBeam) }
    var minutes by remember { mutableStateOf(settings.mapMinutes) }
    var key by remember { mutableStateOf(settings.arcgisKey) }
    var aemetKey by remember { mutableStateOf(settings.aemetKey) }
    var aemet by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var aemetOpen by remember { mutableStateOf(false) }
    var aemetMsg by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(false) }
    var ovOn by remember { mutableStateOf(MapOverlays.parseOn(settings.mapOverlays)) }
    var ovOp by remember { mutableStateOf(MapOverlays.parseOpacity(settings.mapOpacity)) }
    var owmKey by remember { mutableStateOf(settings.owmKey) }
    var card by remember { mutableStateOf<MapCard?>(null) }
    var aemetTitle by remember { mutableStateOf("AEMET · national radar composite") }
    val layersVersion by MapOverlays.layersVersion.collectAsState()
    val official by AppState.officialAlerts.collectAsState()
    val aviation by AviationFetcher.data.collectAsState()
    if ("metar" in ovOn || "sigmet" in ovOn) LaunchedEffect(ovOn) { AviationFetcher.maybeFetch(ctx, settings) }
    val active = MapOverlays.available.filter { it.id in ovOn }
    fun opacity(id: String) = ovOp[id] ?: MapOverlays.byId(id)?.defaultOpacity ?: 100
    val now = rememberNow(30_000)
    val shown = all.filter { it.lat != null && it.lon != null && now - it.lastMs <= minutes * 60_000L }
    val me = Own.position()
    val focus = MapFocus.call
    // layers that follow OpenHamClock (HAM, SPACE, EVENTS): downloads while the map is open
    val ham by HamLayersFetcher.data.collectAsState()
    val aircraft by AppState.aircraft.collectAsState()
    val beacons by AppState.beacons.collectAsState()
    val gcTarget by HamLayersFetcher.gcTarget.collectAsState()
    val qsos by AppState.qsoLog.collectAsState()
    val ovAvail = ovOn.filter { id -> MapOverlays.available.any { it.id == id } }.toSet()
    val hamNow = rememberNow(if ("ham_ibp" in ovAvail) 5_000 else 60_000)
    if (ovAvail.any { it.startsWith("ham_") || it.startsWith("sp_") || it.startsWith("ev_") }) LaunchedEffect(ovAvail) {
        while (true) { HamLayersFetcher.tick(ctx, settings, ovAvail); kotlinx.coroutines.delay(30_000) }
    }
    KeepHeardFresh(30_000)

    val holder = remember {
        MapLayers.configure(ctx)
        MapHolder(MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(11.0)
            minZoomLevel = 3.0
            isTilesScaledToDpi = true
        })
    }
    DisposableEffect(Unit) {
        holder.map.onResume()
        onDispose { holder.map.onPause() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ holder.map }, Modifier.fillMaxSize()) { map ->
            val effLayer = if (layer == "esri" && key.isBlank()) "osm" else layer
            if (holder.layerId != effLayer || holder.esriKey != key) {
                map.setTileSource(MapLayers.source(effLayer, key))
                map.overlayManager.tilesOverlay.setColorFilter(if (effLayer == "dark") MapLayers.NIGHT else null)
                holder.layerId = effLayer; holder.esriKey = key
            }
            val oldSea = holder.seamarks
            if (sea && oldSea == null) {
                val o = TilesOverlay(MapTileProviderBasic(ctx, MapLayers.SEAMARKS), ctx).apply {
                    setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT); setLoadingLineColor(android.graphics.Color.TRANSPARENT)
                }
                holder.seamarks = o
                map.overlays.add(0, o)
            } else if (!sea && oldSea != null) {
                map.overlays.remove(oldSea); oldSea.onDetach(map); holder.seamarks = null
            }
            // tile overlays (charts, satellite, forecast maps), in the order of the list, under the shapes and the stations
            layersVersion.let { }
            val wantTiles = active.filter { it.tile }
            for (id in holder.tiles.keys.toList()) {
                val src = MapOverlays.byId(id)?.let { o -> if (o in wantTiles) MapOverlays.source(ctx, o, owmKey) else null }
                if (src == null || src.name() != holder.tiles[id]!!.first) {
                    holder.tiles.remove(id)?.second?.let { map.overlays.remove(it); it.onDetach(map) }
                }
            }
            var at = if (holder.seamarks != null) 1 else 0
            for (o in wantTiles) {
                val src = MapOverlays.source(ctx, o, owmKey) ?: continue
                val cur = holder.tiles[o.id]
                val t = cur?.second ?: TilesOverlay(MapTileProviderBasic(ctx, src), ctx).apply {
                    setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT); setLoadingLineColor(android.graphics.Color.TRANSPARENT)
                }.also { holder.tiles[o.id] = src.name() to it; map.overlays.add(at.coerceAtMost(map.overlays.size), it) }
                t.setColorFilter(MapLayers.alpha(opacity(o.id)))
                at++
            }
            map.overlays.removeAll(holder.dynamic.toSet()); holder.dynamic.clear()
            val nowMs = System.currentTimeMillis()
            // Night shade: a custom overlay that fills from the terminator curve to the dark pole edge, column by column.
            // osmdroid's Polygon fill of a world-spanning band shaded the wrong side on real devices; this cannot invert.
            if ("night" in ovOn) holder.dynamic.add(NightOverlay(nowMs, android.graphics.Color.argb(opacity("night") * 255 / 100, 5, 9, 14)))
            if ("alerts" in ovOn) for (a in official.filter { !it.src.quake && it.lat != null && it.lon != null && it.activeAt(nowMs / 1000) }) {
                val bg = when (a.level) { 3 -> 0xFFE53935.toInt(); 2 -> 0xFFFF8C1A.toInt(); 1 -> 0xFFFDD835.toInt(); else -> 0xFF28B5E1.toInt() }
                holder.dynamic.add(Marker(map).apply {
                    position = GeoPoint(a.lat!!, a.lon!!)
                    icon = BitmapDrawable(ctx.resources, MarkerArt.tag(ctx, a.src.title + " · " + a.event.take(22), bg, 0xFF131A22.toInt()))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); alpha = opacity("alerts") / 100f
                    setOnMarkerClickListener { _, _ -> card = MapCard(a.src.title + " · " + a.levelName, bg, listOf("Event" to a.event, "Area" to a.area,
                        "Text" to a.text.take(300)).filter { it.second.isNotBlank() }, "Official alerts: ${a.src.title}"); true }
                })
            }
            if ("quakes" in ovOn) for (a in official.filter { it.src.quake && it.lat != null && it.lon != null }) {
                val m = a.magnitude() ?: 0.0
                val a8 = opacity("quakes") * 255 / 100
                holder.dynamic.add(Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(a.lat!!, a.lon!!), 4_000.0 * Math.pow(2.0, (m - 3.0).coerceIn(0.0, 5.0)))
                    fillPaint.color = android.graphics.Color.argb(a8 / 2, 229, 57, 53); outlinePaint.color = android.graphics.Color.argb(a8, 229, 57, 53); outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false }
                })
                holder.dynamic.add(Marker(map).apply {
                    position = GeoPoint(a.lat!!, a.lon!!)
                    icon = BitmapDrawable(ctx.resources, MarkerArt.tag(ctx, String.format(java.util.Locale.US, "M %.1f", m), 0xD9131A22.toInt(), 0xFFFF8A80.toInt()))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); alpha = opacity("quakes") / 100f
                    setOnMarkerClickListener { _, _ -> card = MapCard(a.event, 0xFFFF8A80.toInt(), listOf("Where" to a.area, "When" to Fmt.ago(a.sent * 1000), "Depth" to a.text)
                        .filter { it.second.isNotBlank() }, a.src.title + when (a.src) { AlertSrc.EMSC -> "-CSEM SeismicPortal"; AlertSrc.SHAKE -> " (Raspberry Shake)"; else -> "" }); true }
                })
            }
            if ("sigmet" in ovOn) for (g in aviation.sigmets.filter { it.to == 0L || it.to * 1000 > nowMs }) {
                val a8 = opacity("sigmet") * 255 / 100
                if (g.points.size >= 3) holder.dynamic.add(Polygon(map).apply {
                    points = g.points.map { GeoPoint(it.first, it.second) }
                    fillPaint.color = android.graphics.Color.argb(a8 * 22 / 100, 255, 82, 82); outlinePaint.color = android.graphics.Color.argb(a8, 229, 57, 53)
                    outlinePaint.strokeWidth = 3f; outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
                    setOnClickListener { _, _, _ -> false }
                })
                val cla = g.points.map { it.first }.average(); val clo = g.points.map { it.second }.average()
                val fl = if (g.baseFt >= 0 || g.topFt >= 0) String.format(java.util.Locale.US, " · FL%03d/%03d", g.baseFt.coerceAtLeast(0) / 100, g.topFt.coerceAtLeast(0) / 100) else ""
                holder.dynamic.add(Marker(map).apply {
                    position = GeoPoint(cla, clo)
                    icon = BitmapDrawable(ctx.resources, MarkerArt.tag(ctx, "SIGMET ${g.id} · ${g.hazard}$fl", 0xE6131A22.toInt(), 0xFFFF6B6B.toInt()))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); alpha = opacity("sigmet") / 100f
                    setOnMarkerClickListener { _, _ ->
                        card = MapCard("SIGMET ${g.id} · ${Aviation.hazardName(g.hazard)}", 0xFFFF6B6B.toInt(), listOf(
                            "FIR" to g.firName.ifEmpty { g.fir }, "Severity" to g.qualifier,
                            "Levels" to fl.removePrefix(" · "),
                            "Valid" to listOf(g.from, g.to).filter { it > 0 }.joinToString(" – ") { (Fmt.hhmm(it * 1000, utc = true) + " UTC") },
                            "Moving" to if (g.dir.isNotEmpty()) g.dir + (if (g.spdKt > 0) " at ${g.spdKt} kn" else "") else "",
                        ).filter { it.second.isNotBlank() }, "SIGMET: NOAA aviationweather.gov", mono = listOf("Text" to g.raw)); true }
                })
            }
            if ("metar" in ovOn) for (m in aviation.metars) {
                val art = MarkerArt.metar(ctx, m)
                holder.dynamic.add(Marker(map).apply {
                    position = GeoPoint(m.lat, m.lon)
                    icon = BitmapDrawable(ctx.resources, art.first)
                    setAnchor(art.second, art.third); alpha = opacity("metar") / 100f
                    setOnMarkerClickListener { _, _ ->
                        val taf = aviation.tafs.firstOrNull { it.icao == m.icao }?.raw
                        val name = m.name.substringBefore(',')
                        card = MapCard(listOf(m.cat.label, m.icao, name).filter { it.isNotEmpty() }.joinToString(" · "), m.cat.color.toInt(), listOf(
                            "Observed" to if (m.obsTime > 0) Fmt.hhmm(m.obsTime * 1000, utc = true) + " UTC" + " · " + Fmt.ago(m.obsTime * 1000) else "",
                            "Wind" to Aviation.windText(m), "Sky" to Aviation.skyText(m), "Air" to Aviation.tempText(m),
                        ).filter { it.second.isNotBlank() }, "METAR / TAF: NOAA aviationweather.gov", m.lat, m.lon,
                            listOfNotNull("METAR" to m.raw, taf?.let { "TAF" to it })); true }
                })
            }
            val center = me?.let { GeoPoint(it.first, it.second) }
            if (center != null && rings) for (r in listOf(10_000.0, 25_000.0, 50_000.0)) {
                holder.dynamic.add(Polygon(map).apply {
                    points = Polygon.pointsAsCircle(center, r)
                    outlinePaint.color = 0xCC28B5E1.toInt(); outlinePaint.strokeWidth = 3f; outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 16f), 0f)
                    fillPaint.color = android.graphics.Color.TRANSPARENT
                    setOnClickListener { _, _, _ -> false }
                    title = "${(r / 1000).toInt()} km"
                })
            }
            val az = rotorPos?.azDeg ?: rotor?.azDeg
            if (center != null && beam && az != null) {
                val pts = ArrayList<GeoPoint>()
                pts.add(center)
                for (a in -12..12 step 3) pts.add(center.destinationPoint(60_000.0, az + a))
                pts.add(center)
                holder.dynamic.add(Polygon(map).apply {
                    points = pts
                    fillPaint.color = 0x2EFFB347; outlinePaint.color = 0xCCFFB347.toInt(); outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false }
                })
            }
            // trail of the stations that move: where each one has been in the last 3 h; a dot at every position heard for the chosen one
            val dm = ctx.resources.displayMetrics.density
            for (s in shown) if (s.trail.size >= 2) {
                val sel = s.call == focus
                holder.dynamic.add(Polyline(map).apply {
                    setPoints(s.trail.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = if (sel) 0xF028B5E1.toInt() else 0xA028B5E1.toInt(); outlinePaint.strokeWidth = (if (sel) 3.5f else 2.5f) * dm
                    outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
                    setOnClickListener { _, _, _ -> false }
                })
                if (sel) for (pt in s.trail.dropLast(1)) holder.dynamic.add(Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(pt.lat, pt.lon), 1.0)
                    outlinePaint.color = 0xFF28B5E1.toInt(); outlinePaint.strokeWidth = 5f * dm; outlinePaint.strokeCap = Paint.Cap.ROUND
                    setOnClickListener { _, _, _ -> false }
                })
            }
            // the way of the last frame of the chosen station, as aprs.fi draws it: station -> each digipeater or iGate whose
            // position we know -> our receiver
            shown.firstOrNull { it.call == focus }?.let { st ->
                val f = st.lastFrame ?: return@let
                val pts = ArrayList<GeoPoint>()
                pts.add(GeoPoint(st.lat!!, st.lon!!))
                for (hop in es.ea7jrs.aprslorap.core.PacketExplain.hops(f).drop(1)) {
                    val v = all.firstOrNull { it.call == hop.who && it.lat != null && it.lon != null } ?: continue
                    pts.add(GeoPoint(v.lat!!, v.lon!!))
                }
                if (center != null) pts.add(center)
                if (pts.size >= 2) holder.dynamic.add(Polyline(map).apply {
                    setPoints(pts)
                    outlinePaint.color = 0xE6FFB347.toInt(); outlinePaint.strokeWidth = 2.5f * dm
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * dm, 7f * dm), 0f)
                    setOnClickListener { _, _, _ -> false }
                })
            }
            for (s in shown) {
                val sel = s.call == holder.focused
                holder.dynamic.add(Marker(map).apply {
                    position = GeoPoint(s.lat!!, s.lon!!)
                    icon = BitmapDrawable(ctx.resources, MarkerArt.station(ctx, s.symbol, s.call, sel, s.emergency, s.wx?.mapLabel(Fmt.units),
                        course = s.courseDeg.takeIf { s.code == '>' && (s.speedKmh ?: 0.0) >= 3.0 }))
                    val d = ctx.resources.displayMetrics.density
                    setAnchor((15 * d) / icon.intrinsicWidth, 0.5f)
                    setOnMarkerClickListener { _, _ -> nav.go("STATION:" + s.call); true }
                })
            }
            if (center != null) holder.dynamic.add(Marker(map).apply {
                position = center
                icon = BitmapDrawable(ctx.resources, MarkerArt.own(ctx, gnss?.courseDeg?.takeIf { (gnss?.speedKmh ?: 0.0) > 3 }))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> true }
            })
            val gcTo = gcTarget ?: shown.firstOrNull { it.call == focus }?.let { it.lat!! to it.lon!! }
            holder.dynamic.addAll(0, hamOverlays(map, ctx, ovAvail, { opacity(it) }, ham, me, gcTo, qsos, maxOf(hamNow, System.currentTimeMillis()), aircraft, beacons) { card = it })
            map.overlays.addAll(holder.dynamic)
            if (focus != null && holder.focused != focus) {
                shown.firstOrNull { it.call == focus }?.let { map.controller.animateTo(GeoPoint(it.lat!!, it.lon!!)); holder.centered = true }
                holder.focused = focus
            } else if (!holder.centered) {
                (center ?: shown.firstOrNull()?.let { GeoPoint(it.lat!!, it.lon!!) })?.let { map.controller.setCenter(it); holder.centered = true }
            }
            map.invalidate()
        }

        // top bar
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FloatKey("‹") { MapFocus.call = null; if (!nav.back()) nav.go("RADIO") }
            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(Alp.Bg.copy(alpha = 0.92f)).border(1.dp, Alp.KeyBorder, RoundedCornerShape(12.dp))
                .clickable { nav.go("STATIONS") }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} stations", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Alp.Text)
                Text(" · last ${if (minutes >= 60) "${minutes / 60} h" else "$minutes min"}", fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
            }
            FloatKey("≡", on = panel) { panel = !panel }
        }

        if (panel) Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 66.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Bg.copy(alpha = 0.97f))
            .border(1.dp, Alp.KeyBorder, RoundedCornerShape(14.dp)).heightIn(max = 560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("BASE MAP")
            for (row in MapLayers.BASE.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in row) RadioKey(l.label, Modifier.weight(1f).height(44.dp), labelSize = 15, bg = if (l.id == layer) Alp.Cyan else Alp.Key,
                    color = if (l.id == layer) Alp.Bg else Alp.Text, border = if (l.id == layer) Alp.Cyan else Alp.KeyBorder) { layer = l.id; settings.mapLayer = l.id }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            if (layer == "esri") {
                OutlinedTextField(key, { key = it.trim(); settings.arcgisKey = key }, label = { Text("Your ArcGIS API key (Esri World Imagery)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (key.isBlank()) Text("Esri imagery needs an ArcGIS account (free tier, developers.arcgis.com). Without a key the map shows OpenStreetMap.", fontSize = 12.sp, color = Alp.Muted)
            }
            SectionLabel("OVERLAYS")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("OpenSeaMap", sea) { sea = !sea; settings.mapSeamarks = sea }
                Toggle("Range rings", rings, color = Alp.Cyan) { rings = !rings; settings.mapRings = rings }
                Toggle("Rotor beam", beam, color = Alp.Amber) { beam = !beam; settings.mapBeam = beam }
            }
            fun toggle(id: String) { ovOn = if (id in ovOn) ovOn - id else ovOn + id; settings.mapOverlays = ovOn.joinToString(",") }
            for (g in MapOverlays.Group.entries) {
                val items = MapOverlays.available.filter { it.group == g }
                if (items.isEmpty()) continue
                SectionLabel(g.title)
                for (row in items.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in row) Toggle(o.label, o.id in ovOn, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color(o.color)) { toggle(o.id) }
                    if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                if (g == MapOverlays.Group.FORECAST) {
                    OutlinedTextField(owmKey, { owmKey = it.trim(); settings.owmKey = owmKey }, label = { Text("Your OpenWeather API key (the same as internet weather)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (owmKey.isBlank() && active.any { it.needsOwmKey }) Text("The forecast maps need your free OpenWeather key (openweathermap.org).", fontSize = 12.sp, color = Alp.Muted)
                }
            }
            if (active.isNotEmpty()) {
                SectionLabel("TRANSPARENCY OF EACH LAYER")
                for (o in active) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(o.label, Modifier.widthIn(min = 110.dp, max = 130.dp), fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = androidx.compose.ui.graphics.Color(o.color))
                    androidx.compose.material3.Slider(opacity(o.id).toFloat(), { v -> ovOp = ovOp + (o.id to v.toInt()) }, Modifier.weight(1f),
                        valueRange = 10f..100f, onValueChangeFinished = { settings.mapOpacity = MapOverlays.formatOpacity(ovOp) })
                    Text("${opacity(o.id)} %", fontFamily = TechMono, fontSize = 13.sp, color = Alp.Text)
                }
                Text("100 % = solid; lower values let the base map show through.", fontSize = 12.sp, color = Alp.Muted)
            }
            SectionLabel("AEMET (SPAIN, IMAGES)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioKey("Radar", Modifier.height(40.dp), labelSize = 14, enabled = aemetKey.isNotBlank()) {
                    aemetOpen = true; aemetTitle = "AEMET · national radar composite"; aemetMsg = "Downloading the AEMET national radar…"; aemet = null
                    WxRadarSource.aemetNational(aemetKey) { r -> r.onSuccess { aemet = it; aemetMsg = "" }.onFailure { aemetMsg = it.message ?: "AEMET: error" } }
                }
                RadioKey("Lightning", Modifier.height(40.dp), labelSize = 14, enabled = aemetKey.isNotBlank()) {
                    aemetOpen = true; aemetTitle = "AEMET · lightning"; aemetMsg = "Downloading the AEMET lightning map…"; aemet = null
                    WxRadarSource.aemetLightning(aemetKey) { r -> r.onSuccess { aemet = it; aemetMsg = "" }.onFailure { aemetMsg = it.message ?: "AEMET: error" } }
                }
            }
            OutlinedTextField(aemetKey, { aemetKey = it.filterNot { c -> c.isWhitespace() }; settings.aemetKey = aemetKey }, label = { Text("Your AEMET OpenData API key (free, opendata.aemet.es)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("AEMET gives the national radar and the lightning as images of Spain, not as map tiles (source: AEMET).",
                fontSize = 12.sp, color = Alp.Muted)
            SectionLabel("STATIONS HEARD IN THE LAST")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in listOf(30, 60, 180, 720, 1440)) Chip(if (m >= 60) "${m / 60} h" else "$m min", m == minutes, height = 32.dp) { minutes = m; settings.mapMinutes = m }
            }
        }

        val attr = (MapLayers.BASE.firstOrNull { it.id == (if (layer == "esri" && key.isBlank()) "osm" else layer) }?.attribution ?: "") +
            (if (sea) " · Seamarks © OpenSeaMap" else "") +
            active.map { it.attribution }.filter { it.isNotBlank() }.distinct().joinToString("") { " · $it" } + " · APRS symbols aprs.fi"
        Text(attr, fontSize = 11.sp, color = Alp.SubText, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp, end = 76.dp)
            .widthIn(max = 300.dp).clip(RoundedCornerShape(6.dp)).background(Alp.Bg.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 3.dp))
        if (aemetOpen) androidx.compose.ui.window.Dialog(onDismissRequest = { aemetOpen = false }) {
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Alp.Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(aemetTitle, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Alp.Text)
                aemet?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "AEMET national radar", Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth) }
                if (aemetMsg.isNotBlank()) Text(aemetMsg, fontSize = 14.sp, color = Alp.Muted)
                Text("Source: AEMET (Agencia Estatal de Meteorología)", fontSize = 11.sp, color = Alp.Muted)
                RadioKey("CLOSE", Modifier.fillMaxWidth().height(44.dp)) { aemetOpen = false }
            }
        }
        card?.let { c ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { card = null }) {
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, androidx.compose.ui.graphics.Color(c.color), RoundedCornerShape(14.dp))
                    .padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.title, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = androidx.compose.ui.graphics.Color(c.color))
                    for ((k, v) in c.rows) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(k, Modifier.widthIn(min = 70.dp), fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                        Text(v, fontSize = 14.sp, color = Alp.Text)
                    }
                    for ((k, v) in c.mono) {
                        Text(k, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                        Text(v, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Alp.Bg).padding(8.dp), fontFamily = TechMono, fontSize = 13.sp, color = Alp.Text)
                    }
                    Text(c.source, fontSize = 11.sp, color = Alp.Muted)
                    if (c.lat != null && c.lon != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioKey("GO TO", Modifier.weight(1f).height(44.dp)) { goToPos(ctx, c.lat, c.lon, c.title); card = null }
                        RadioKey("ROTOR", Modifier.weight(1f).height(44.dp)) { aimRotorAt(c.lat, c.lon, c.title); card = null }
                    }
                    RadioKey("CLOSE", Modifier.fillMaxWidth().height(44.dp)) { card = null }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp).clip(RoundedCornerShape(24.dp)).background(Alp.Bg).border(1.dp, Alp.KeyBorder, RoundedCornerShape(24.dp))
            .clickable { me?.let { holder.map.controller.animateTo(GeoPoint(it.first, it.second)) } ?: run { AppState.notice.value = "No own position yet (tracker GNSS or locator)" } },
            contentAlignment = Alignment.Center) { Text("◎", fontSize = 22.sp, color = Alp.Green) }
    }
}

@Composable
private fun FloatKey(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Alp.Cyan else Alp.Bg.copy(alpha = 0.92f)).border(1.dp, Alp.KeyBorder, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (on) Alp.Bg else Alp.Text)
    }
}
