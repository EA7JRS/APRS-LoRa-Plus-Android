/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.NavWarn
import es.ea7jrs.aprslorap.core.Wms
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL

/**
 * Map overlays brought from the plotter, each with its own transparency. Terms (2026-11-13): IHM services and NAVAREA
 * warnings are for non-commercial reuse only, so the Google Play build hides them (BuildConfig.PLAY); EUMETSAT imagery is
 * CC BY 4.0; OpenWeather maps use the user's own key; USGS is public domain and EMSC CC BY 4.0; the day / night line is computed;
 * METAR, TAF and SIGMET come from the NOAA Aviation Weather Center (public domain, AviationFetcher).
 */
object MapOverlays {
    enum class Group(val title: String) { SEA("SEA"), WARNINGS("WARNINGS"), AVIATION("AVIATION · NOAA AVIATIONWEATHER.GOV"), SKY("SKY"), SATELLITE("SATELLITE"), FORECAST("FORECAST MAPS (OPENWEATHER, YOUR KEY)") }

    /** [tile]: a tile layer (null = drawn by the app: markers or shapes). [play]: allowed in the paid Google Play build. */
    data class Ov(
        val id: String, val label: String, val group: Group, val color: Long, val attribution: String, val defaultOpacity: Int,
        val play: Boolean, val tile: Boolean, val needsOwmKey: Boolean = false,
    )

    val ALL = listOf(
        Ov("ihm_enc", "IHM electronic chart", Group.SEA, 0xFF7FD3F0, "Electronic chart © Instituto Hidrográfico de la Marina (not for navigation)", 55, play = false, tile = true),
        Ov("ihm_paper", "IHM paper chart", Group.SEA, 0xFF7FD3F0, "Paper chart © Instituto Hidrográfico de la Marina (not for navigation)", 55, play = false, tile = true),
        Ov("ihm_lights", "Lights", Group.SEA, 0xFFFFD54A, "Lights © IHM", 100, play = false, tile = true),
        Ov("ihm_tides", "Tide stations", Group.SEA, 0xFF5AA9F0, "Tides © IHM", 100, play = false, tile = true),
        Ov("ihm_limits", "Maritime limits", Group.SEA, 0xFFC77DDB, "Maritime limits © IHM", 80, play = false, tile = true),
        Ov("navarea", "NAVAREA warnings", Group.WARNINGS, 0xFFFFB347, "NAVAREA III: Armada (IHM)", 100, play = false, tile = false),
        Ov("alerts", "Official alerts", Group.WARNINGS, 0xFFFF8C1A, "Alerts: AEMET, MeteoAlarm, NWS", 80, play = true, tile = false),
        Ov("quakes", "Earthquakes", Group.WARNINGS, 0xFFFF8A80, "Earthquakes: USGS, EMSC-CSEM SeismicPortal", 80, play = true, tile = false),
        Ov("metar", "METAR + TAF", Group.AVIATION, 0xFF3CE68A, "METAR / TAF: NOAA aviationweather.gov", 100, play = true, tile = false),
        Ov("sigmet", "SIGMET", Group.AVIATION, 0xFFFF6B6B, "SIGMET: NOAA aviationweather.gov", 80, play = true, tile = false),
        Ov("night", "Day / night line", Group.SKY, 0xFFFFB347, "", 40, play = true, tile = false),
        Ov("meteosat", "Clouds (Meteosat IR)", Group.SATELLITE, 0xFFE8EEF3, "Contains modified EUMETSAT Meteosat data", 55, play = true, tile = true),
        Ov("owm_clouds", "Cloud cover", Group.FORECAST, 0xFFC9D4DE, "Weather data provided by OpenWeather", 50, play = true, tile = true, needsOwmKey = true),
        Ov("owm_rain", "Precipitation", Group.FORECAST, 0xFF3CE68A, "Weather data provided by OpenWeather", 70, play = true, tile = true, needsOwmKey = true),
        Ov("owm_temp", "Temperature", Group.FORECAST, 0xFFF2D35B, "Weather data provided by OpenWeather", 45, play = true, tile = true, needsOwmKey = true),
        Ov("owm_wind", "Wind", Group.FORECAST, 0xFFB3E5FC, "Weather data provided by OpenWeather", 70, play = true, tile = true, needsOwmKey = true),
        Ov("owm_pressure", "Pressure", Group.FORECAST, 0xFFE8EEF3, "Weather data provided by OpenWeather", 70, play = true, tile = true, needsOwmKey = true),
    )

    /** The overlays this build offers. */
    val available: List<Ov> get() = ALL.filter { it.play || !BuildConfig.PLAY }

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    fun parseOn(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() && byId(it) != null }.toSet()
    fun parseOpacity(s: String): Map<String, Int> = s.split(';').mapNotNull {
        val k = it.substringBefore('=').trim(); val v = it.substringAfter('=', "").trim().toIntOrNull()
        if (k.isEmpty() || v == null) null else k to v.coerceIn(10, 100)
    }.toMap()
    fun formatOpacity(m: Map<String, Int>) = m.entries.joinToString(";") { it.key + "=" + it.value }

    // ------------------------------------------------------------------ tile sources

    private const val IHM = "https://ideihm.covam.es"
    private val WMS_BASE = mapOf(
        "ihm_enc" to "$IHM/wms/enc", "ihm_paper" to "$IHM/wms/CartaOF", "ihm_lights" to "$IHM/wms/lucesIHM",
        "ihm_tides" to "$IHM/wms/mareas", "ihm_limits" to "$IHM/ihm-inspire/wms-unidadesmaritimas",
        "meteosat" to "https://view.eumetsat.int/geoserver/wms",
    )
    /** Known layer names; the IHM ones are read from each GetCapabilities the first time (Wms.layers) and kept. */
    private val FIXED_LAYERS = mapOf("meteosat" to "msg_fes:ir108")
    private val OWM = mapOf("owm_clouds" to "clouds_new", "owm_rain" to "precipitation_new", "owm_temp" to "temp_new", "owm_wind" to "wind_new", "owm_pressure" to "pressure_new")

    /** Changes when a service's layer names arrive, so the map redraws. */
    val layersVersion = MutableStateFlow(0)

    private class WmsSource(name: String, private val base: String, private val layers: String, private val transparent: Boolean) :
        OnlineTileSourceBase(name, 1, 18, 256, ".png", arrayOf(base)) {
        override fun getTileURLString(index: Long): String =
            Wms.tileUrl(base, layers, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index), transparent)
    }

    private class OwmSource(private val layer: String, private val key: String) :
        OnlineTileSourceBase("OWM-$layer", 1, 18, 256, ".png", arrayOf("https://tile.openweathermap.org/map/")) {
        override fun getTileURLString(index: Long): String =
            baseUrl + "$layer/${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}.png?appid=$key"
    }

    /** Tile source of a tile overlay, or null while its layer names are not known yet (or the OpenWeather key is missing). */
    fun source(ctx: Context, ov: Ov, owmKey: String): ITileSource? {
        OWM[ov.id]?.let { return if (owmKey.isBlank()) null else OwmSource(it, owmKey) }
        val base = WMS_BASE[ov.id] ?: return null
        val layers = FIXED_LAYERS[ov.id] ?: knownLayers(ctx, ov.id) ?: run { fetchLayers(ctx, ov.id, base); return null }
        return WmsSource("WMS-${ov.id}-${layers.hashCode()}", base, layers, transparent = ov.id != "meteosat")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wms_layers", Context.MODE_PRIVATE)
    private fun knownLayers(ctx: Context, id: String): String? = prefs(ctx).getString(id, null)
    private val asking = HashSet<String>()

    private fun fetchLayers(ctx: Context, id: String, base: String) {
        synchronized(asking) { if (!asking.add(id)) return }
        val app = ctx.applicationContext
        Thread({
            try {
                val text = String(get(base + (if ('?' in base) "&" else "?") + "SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"), Charsets.UTF_8)
                val layers = Wms.layers(text) ?: throw IllegalStateException("no layers in the answer")
                prefs(app).edit().putString(id, layers).apply()
                layersVersion.value = layersVersion.value + 1
            } catch (e: Exception) {
                AppState.error.value = "Map layer ${byId(id)?.label}: ${e.message}"
            } finally {
                synchronized(asking) { asking.remove(id) }
            }
        }, "wms-caps").apply { isDaemon = true; start() }
    }

    fun get(url: String, timeoutMs: Int = 20_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "APRSLoRaPlus/${BuildConfig.VERSION_NAME} (Android)")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}

/** NAVAREA III warnings in force, read from the IHM file at most once an hour while the layer is on. */
object NavAreas {
    val warnings = MutableStateFlow<List<NavWarn.Warning>>(emptyList())
    @Volatile private var lastMs = 0L
    @Volatile private var busy = false

    fun refresh() {
        if (busy || System.currentTimeMillis() - lastMs < 3_600_000L) return
        busy = true
        Thread({
            try {
                val bytes = MapOverlays.get(NavWarn.IHM_URL, 30_000)
                val head = String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1)
                val cs = if (head.contains("encoding=\"UTF-8\"", true) || head.contains("encoding='UTF-8'", true)) Charsets.UTF_8 else Charsets.ISO_8859_1
                warnings.value = NavWarn.parseIhm(String(bytes, cs)) ?: throw IllegalStateException("not the NAVAREA file")
                lastMs = System.currentTimeMillis()
            } catch (e: Exception) {
                AppState.error.value = "NAVAREA warnings: ${e.message}"
                lastMs = System.currentTimeMillis() - 3_000_000L          // try again in 10 min
            } finally { busy = false }
        }, "navarea").apply { isDaemon = true; start() }
    }
}
