/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.Wms
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL

/**
 * Map overlays, each with its own transparency. Terms (2026-11-13): EUMETSAT imagery is CC BY 4.0; OpenWeather maps use the
 * user's own key (and a limit of tiles per minute); USGS is public domain, EMSC CC BY 4.0 and ShakeNet without published terms; the day / night line is computed;
 * METAR, TAF and SIGMET come from the NOAA Aviation Weather Center (public domain, AviationFetcher). The HAM, SPACE and EVENTS
 * layers follow OpenHamClock (MIT, HamLayersFetcher): NOAA and NASA feeds are public domain; wspr.live, PSK Reporter and
 * SondeHub are community services (Manuel, 2026-11-13: they stay, in every build; their terms for a paid app are still to be
 * read). The layers that could not go in the Google Play version (IHM charts, lights, tides and limits, NAVAREA warnings,
 * KC2G ionosondes, The Space Devs launches) were removed. Aircraft are those the app receives itself (AdsbLink).
 */
object MapOverlays {
    enum class Group(val title: String) { WARNINGS("WARNINGS"), AVIATION("AVIATION · NOAA AVIATIONWEATHER.GOV"), HAM("HAM RADIO · IDEAS FROM OPENHAMCLOCK"), SPACE("SPACE WEATHER"),
        EVENTS("NATURE, SEA, AIR AND SPACE"), SKY("SKY"), SATELLITE("SATELLITE"), FORECAST("FORECAST MAPS (OPENWEATHER, YOUR KEY)") }

    /** [tile]: a tile layer (null = drawn by the app: markers or shapes). [play]: allowed in the paid Google Play build. */
    data class Ov(
        val id: String, val label: String, val group: Group, val color: Long, val attribution: String, val defaultOpacity: Int,
        val play: Boolean, val tile: Boolean, val needsOwmKey: Boolean = false,
    )

    val ALL = listOf(
        Ov("alerts", "Official alerts", Group.WARNINGS, 0xFFFF8C1A, "Alerts: AEMET, MeteoAlarm, NWS", 80, play = true, tile = false),
        Ov("quakes", "Earthquakes", Group.WARNINGS, 0xFFFF8A80, "Earthquakes: USGS, EMSC-CSEM SeismicPortal, Raspberry Shake ShakeNet", 80, play = true, tile = false),
        Ov("metar", "METAR + TAF", Group.AVIATION, 0xFF3CE68A, "METAR / TAF: NOAA aviationweather.gov", 100, play = true, tile = false),
        Ov("sigmet", "SIGMET", Group.AVIATION, 0xFFFF6B6B, "SIGMET: NOAA aviationweather.gov", 80, play = true, tile = false),
        Ov("ham_grid", "Maidenhead grid", Group.HAM, 0xFFB39DDB, "", 70, play = true, tile = false),
        Ov("ham_cq", "CQ zones", Group.HAM, 0xFF28B5E1, "CQ / ITU zones: HB9HIL hamradio-zones-geojson (MIT)", 70, play = true, tile = false),
        Ov("ham_itu", "ITU zones", Group.HAM, 0xFFFFB347, "CQ / ITU zones: HB9HIL hamradio-zones-geojson (MIT)", 70, play = true, tile = false),
        Ov("ham_gc", "Great circle", Group.HAM, 0xFF28B5E1, "", 100, play = true, tile = false),
        Ov("ham_ibp", "IBP beacons (NCDXF)", Group.HAM, 0xFF3CE68A, "IBP schedule: NCDXF / IARU", 100, play = true, tile = false),
        Ov("ham_beacons", "Beacons heard by this app (SDR)", Group.HAM, 0xFF3CE68A, "", 100, play = true, tile = false),
        Ov("ham_worked", "Worked grids (log)", Group.HAM, 0xFF3CE68A, "", 45, play = true, tile = false),
        Ov("ham_wspr", "WSPR: who hears me", Group.HAM, 0xFFFF8A80, "WSPR spots: wspr.live", 90, play = true, tile = false),
        Ov("ham_psk", "PSK Reporter: who hears me", Group.HAM, 0xFFFFB347, "Spots: PSK Reporter (pskreporter.info)", 90, play = true, tile = false),
        Ov("sp_aurora", "Aurora (OVATION)", Group.SPACE, 0xFF3CE68A, "Aurora: NOAA SWPC OVATION", 60, play = true, tile = false),
        Ov("sp_drap", "HF absorption (D-RAP)", Group.SPACE, 0xFFFF8C1A, "D-RAP: NOAA SWPC", 55, play = true, tile = false),
        Ov("ev_cyclones", "Tropical cyclones (NHC)", Group.EVENTS, 0xFFFF6B6B, "Tropical cyclones: NOAA NHC", 90, play = true, tile = false),
        Ov("ev_fires", "Wildfires (NASA EONET)", Group.EVENTS, 0xFFFF8C1A, "Wildfires and floods: NASA EONET", 90, play = true, tile = false),
        Ov("ev_floods", "Floods (NASA EONET)", Group.EVENTS, 0xFF4FC3F7, "Wildfires and floods: NASA EONET", 90, play = true, tile = false),
        Ov("ev_buoys", "Buoys (NOAA NDBC)", Group.EVENTS, 0xFF4FC3F7, "Buoys: NOAA NDBC", 90, play = true, tile = false),
        Ov("ev_balloons", "Balloons (SondeHub)", Group.EVENTS, 0xFFE8EEF3, "Balloons: SondeHub amateur", 90, play = true, tile = false),
        Ov("ev_aircraft", "Aircraft (ADS-B, your own receiver)", Group.EVENTS, 0xFFC9D4DE, "", 80, play = true, tile = false),
        Ov("city_lights", "City lights (NASA)", Group.SKY, 0xFFF2D35B, "City lights: NASA GIBS (VIIRS 2012)", 60, play = true, tile = true),
        Ov("night", "Day / night line", Group.SKY, 0xFFFFB347, "", 40, play = true, tile = false),
        Ov("meteosat", "Clouds (Meteosat IR)", Group.SATELLITE, 0xFFE8EEF3, "Contains modified EUMETSAT Meteosat data", 55, play = true, tile = true),
        Ov("owm_clouds", "Cloud cover", Group.FORECAST, 0xFFC9D4DE, "Weather data provided by OpenWeather", 50, play = true, tile = true, needsOwmKey = true),
        Ov("owm_rain", "Precipitation", Group.FORECAST, 0xFF3CE68A, "Weather data provided by OpenWeather", 70, play = true, tile = true, needsOwmKey = true),
        Ov("owm_temp", "Temperature", Group.FORECAST, 0xFFF2D35B, "Weather data provided by OpenWeather", 45, play = true, tile = true, needsOwmKey = true),
        Ov("owm_wind", "Wind", Group.FORECAST, 0xFFB3E5FC, "Weather data provided by OpenWeather", 70, play = true, tile = true, needsOwmKey = true),
        Ov("owm_pressure", "Pressure", Group.FORECAST, 0xFFE8EEF3, "Weather data provided by OpenWeather", 70, play = true, tile = true, needsOwmKey = true),
    )

    /** The overlays this build offers (all of them: what could not go in the Google Play version was removed). */
    val available: List<Ov> get() = ALL

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    fun parseOn(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() && byId(it) != null }.toSet()
    fun parseOpacity(s: String): Map<String, Int> = s.split(';').mapNotNull {
        val k = it.substringBefore('=').trim(); val v = it.substringAfter('=', "").trim().toIntOrNull()
        if (k.isEmpty() || v == null) null else k to v.coerceIn(10, 100)
    }.toMap()
    fun formatOpacity(m: Map<String, Int>) = m.entries.joinToString(";") { it.key + "=" + it.value }

    // ------------------------------------------------------------------ tile sources

    private val WMS_BASE = mapOf("meteosat" to "https://view.eumetsat.int/geoserver/wms")
    /** Known layer names; any other would be read from the service's GetCapabilities the first time (Wms.layers) and kept. */
    private val FIXED_LAYERS = mapOf("meteosat" to "msg_fes:ir108")
    private val OWM = mapOf("owm_clouds" to "clouds_new", "owm_rain" to "precipitation_new", "owm_temp" to "temp_new", "owm_wind" to "wind_new", "owm_pressure" to "pressure_new")

    /** Changes when a service's layer names arrive, so the map redraws. */
    val layersVersion = MutableStateFlow(0)

    private class WmsSource(name: String, private val base: String, private val layers: String, private val transparent: Boolean) :
        OnlineTileSourceBase(name, 1, 18, 256, ".png", arrayOf(base)) {
        override fun getTileURLString(index: Long): String =
            Wms.tileUrl(base, layers, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index), transparent)
    }

    /**
     * OpenWeather's free key allows 60 calls a minute for everything done with it, and each map tile is a call: going over it
     * gets the key blocked. All the OpenWeather layers share this limit of 30 tiles a minute (the rest is left for the weather
     * of the tracker and for the user's other devices); the tiles that do not fit wait, so the layers fill in more slowly.
     */
    val owmLimiter = es.ea7jrs.aprslorap.core.RateLimiter(30)

    private class OwmSource(private val layer: String, private val key: String) :
        OnlineTileSourceBase("OWM-$layer", 1, 18, 256, ".png", arrayOf("https://tile.openweathermap.org/map/")) {
        override fun getTileURLString(index: Long): String {             // called from the download threads, never from the UI
            val wait = owmLimiter.waitMs(System.currentTimeMillis())
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return baseUrl + "$layer/${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}.png?appid=$key"
        }
    }

    private class GibsSource : OnlineTileSourceBase("GIBS-citylights", 1, 8, 256, ".jpg",
        arrayOf("https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_CityLights_2012/default/2012-03-12/GoogleMapsCompatible_Level8/")) {
        override fun getTileURLString(index: Long): String =
            baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}.jpg"
    }

    /** Tile source of a tile overlay, or null while its layer names are not known yet (or the OpenWeather key is missing). */
    fun source(ctx: Context, ov: Ov, owmKey: String): ITileSource? {
        if (ov.id == "city_lights") return GibsSource()
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
