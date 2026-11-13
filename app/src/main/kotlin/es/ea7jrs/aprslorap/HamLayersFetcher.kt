/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import es.ea7jrs.aprslorap.core.HamLayers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads for the map layers that follow OpenHamClock (accius/openhamclock, MIT): each one only while its layer is on and
 * the map is open ([tick] from MapScreen every 30 s), at most as often as its source asks (KC2G 10 min, PSK Reporter 5 min,
 * The Space Devs free tier...). The computed layers (grid, zones, great circle, IBP, worked grids) need no download.
 */
object HamLayersFetcher {
    data class Data(
        val aurora: HamLayers.Aurora? = null,
        val drap: HamLayers.Drap? = null,
        val storms: List<HamLayers.Storm> = emptyList(),
        val ionosondes: List<HamLayers.Ionosonde> = emptyList(),
        val fires: List<HamLayers.Event> = emptyList(),
        val floods: List<HamLayers.Event> = emptyList(),
        val buoys: List<HamLayers.Buoy> = emptyList(),
        val balloons: List<HamLayers.Balloon> = emptyList(),
        val aircraft: List<HamLayers.Aircraft> = emptyList(),
        val launches: List<HamLayers.Launch> = emptyList(),
        val wspr: List<HamLayers.Spot> = emptyList(),
        val psk: List<HamLayers.Spot> = emptyList(),
        val cq: List<HamLayers.Zone> = emptyList(),
        val itu: List<HamLayers.Zone> = emptyList(),
    )

    val data = MutableStateFlow(Data())
    /** Far end of the great circle: a long press on the map (null = the focused station). */
    val gcTarget = MutableStateFlow<Pair<Double, Double>?>(null)

    private val last = HashMap<String, Long>()
    private fun set(f: (Data) -> Data) = data.update(f)
    private val busy = HashSet<String>()
    private const val UA = "APRSLoRaPlus-Android (+https://ea7jrs.github.io/APRS-LoRa-Plus/; ea7jrs.manuel@gmail.com)"

    private val EVERY = mapOf(
        "sp_aurora" to 15, "sp_drap" to 10, "ev_cyclones" to 30, "ham_muf" to 10, "ev_fires" to 60, "ev_floods" to 60, "ev_buoys" to 30,
        "ev_balloons" to 5, "ev_aircraft" to 1, "ev_launches" to 60, "ham_wspr" to 10, "ham_psk" to 5,
    )

    @Synchronized
    fun tick(ctx: Context, settings: Settings, on: Set<String>) {
        val app = ctx.applicationContext
        if ("ham_cq" in on && data.value.cq.isEmpty()) once("ham_cq") { val z = zones(app, "cq", false); set { it.copy(cq = z) } }
        if ("ham_itu" in on && data.value.itu.isEmpty()) once("ham_itu") { val z = zones(app, "itu", true); set { it.copy(itu = z) } }
        val now = System.currentTimeMillis()
        for ((id, minutes) in EVERY) {
            if (id !in on) continue
            val t = last[id] ?: 0L
            if (t != 0L && now - t < minutes * 60_000L) continue
            last[id] = now
            once(id) { fetch(app, settings, id) }
        }
    }

    private fun once(id: String, block: () -> Unit) {
        synchronized(busy) { if (!busy.add(id)) return }
        Thread({
            try { block() } catch (e: Exception) {
                AppState.addLog("Map layer $id: ${e.message}")
                synchronized(this) { last[id] = System.currentTimeMillis() - (EVERY[id] ?: 10) * 60_000L + 120_000L }   // again in 2 min
            } finally { synchronized(busy) { busy.remove(id) } }
        }, "ham-$id").apply { isDaemon = true; start() }
    }

    private fun zones(ctx: Context, name: String, itu: Boolean): List<HamLayers.Zone> =
        HamLayers.parseZones(ctx.assets.open("zones/$name.geojson").use { String(it.readBytes(), Charsets.UTF_8) }, itu) ?: emptyList()

    private fun fetch(ctx: Context, settings: Settings, id: String) {
        when (id) {
            "sp_aurora" -> { val v = HamLayers.parseOvation(text(HamLayers.OVATION_URL)) ?: throw IllegalStateException("not OVATION data"); set { it.copy(aurora = v) } }
            "sp_drap" -> { val v = HamLayers.parseDrap(text(HamLayers.DRAP_URL)) ?: throw IllegalStateException("not D-RAP data"); set { it.copy(drap = v) } }
            "ev_cyclones" -> {
                val storms = (HamLayers.parseNhc(text(HamLayers.NHC_URL)) ?: throw IllegalStateException("not NHC data")).map { s ->
                    val tr = s.trackKmz?.let { runCatching { HamLayers.nhcTrack(kml(it)) }.getOrNull() }
                    val cone = s.coneKmz?.let { runCatching { HamLayers.nhcCone(kml(it)) }.getOrNull() }
                    s.copy(track = tr?.first ?: emptyList(), forecast = tr?.second ?: emptyList(), cone = cone ?: emptyList())
                }
                set { it.copy(storms = storms) }
            }
            "ham_muf" -> { val v = HamLayers.parseKc2g(text(HamLayers.KC2G_URL), System.currentTimeMillis()) ?: throw IllegalStateException("not KC2G data"); set { it.copy(ionosondes = v) } }
            "ev_fires" -> { val v = HamLayers.parseEonet(text(HamLayers.eonetUrl("wildfires"))) ?: throw IllegalStateException("not EONET data"); set { it.copy(fires = v) } }
            "ev_floods" -> { val v = HamLayers.parseEonet(text(HamLayers.eonetUrl("floods", 30))) ?: throw IllegalStateException("not EONET data"); set { it.copy(floods = v) } }
            "ev_buoys" -> { val v = HamLayers.parseNdbc(text(HamLayers.NDBC_URL, "text/plain")) ?: throw IllegalStateException("not NDBC data"); set { it.copy(buoys = v) } }
            "ev_balloons" -> { val v = HamLayers.parseSondehub(text(HamLayers.SONDEHUB_URL)) ?: throw IllegalStateException("not SondeHub data"); set { it.copy(balloons = v) } }
            "ev_aircraft" -> {
                val here = NetWeatherFetcher.position(ctx) ?: return
                val v = HamLayers.parseAdsb(text(HamLayers.adsbUrl(here.first, here.second))) ?: throw IllegalStateException("not adsb.lol data"); set { it.copy(aircraft = v) }
            }
            "ev_launches" -> { val v = HamLayers.parseLaunches(text(HamLayers.LAUNCHES_URL)) ?: throw IllegalStateException("not Launch Library data"); set { it.copy(launches = v) } }
            "ham_wspr" -> {
                val call = settings.callsign.substringBefore('-').trim().ifEmpty { return }
                val v = HamLayers.parseWsprLive(text(HamLayers.wsprLiveUrl(call))) ?: throw IllegalStateException("not wspr.live data"); set { it.copy(wspr = v) }
            }
            "ham_psk" -> {
                val call = settings.callsign.substringBefore('-').trim().ifEmpty { return }
                val v = HamLayers.parsePskReporter(text(HamLayers.pskReporterUrl(call), "application/xml")); set { it.copy(psk = v) }
            }
        }
    }

    private fun bytes(url: String, accept: String, limit: Int = 25_000_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException(if (code == 429) "too many requests (HTTP 429)" else "HTTP $code")
            return c.inputStream.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16_384)
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > limit) throw IllegalStateException("answer too big") }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun text(url: String, accept: String = "application/json") = String(bytes(url, accept), Charsets.UTF_8)

    /** The KML inside an NHC .kmz (a zip). */
    private fun kml(url: String): String {
        ZipInputStream(bytes(url, "application/vnd.google-earth.kmz", 5_000_000).inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".kml", ignoreCase = true)) return String(z.readBytes(), Charsets.UTF_8)
            }
        }
        throw IllegalStateException("no KML in the KMZ")
    }
}
