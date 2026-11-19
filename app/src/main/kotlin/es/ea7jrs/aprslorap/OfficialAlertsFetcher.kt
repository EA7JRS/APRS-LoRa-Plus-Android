// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Official alerts with the phone's internet, at the tracker's GNSS position (or the phone's last one): weather (AEMET in Spain: the
// public Meteoalerta RSS and the .tar.gz of every CAP, checked against each polygon; MeteoAlarm elsewhere in Europe, filtered by the
// zones the user writes; NWS in the US, per point), tsunamis (tsunami.gov), earthquakes (USGS worldwide, EMSC and ShakeNet near) and space weather
// (NOAA SWPC). Every [Settings.alertMinutes]; the new ones open the alert window, and all of them go to the tracker ("alert",
// "alertdel", "spacewx") when its firmware knows them. SAME headers (NOAA Weather Radio) come from the tracker (T-TWR) or the app's
// RTL-SDR receiver ([onSame]).
package es.ea7jrs.aprslorap

import android.content.Context
import android.location.Geocoder
import es.ea7jrs.aprslorap.core.AlertSrc
import es.ea7jrs.aprslorap.core.Control
import es.ea7jrs.aprslorap.core.OfficialAlert
import es.ea7jrs.aprslorap.core.OfficialAlerts
import es.ea7jrs.aprslorap.core.Same
import es.ea7jrs.aprslorap.core.SpaceWx
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

object OfficialAlertsFetcher {
    @Volatile private var busy = false
    @Volatile private var lastMs = 0L
    private val bySource = HashMap<AlertSrc, List<OfficialAlert>>()
    private val levels = HashMap<String, Int>()                  // id -> level already shown (a raise opens the window again)
    private var aemetBundle = ""                                 // URL of the last AEMET .tar.gz read
    private var aemetCaps: List<Pair<OfficialAlert, List<Pair<Double, Double>>>> = emptyList()
    private val sentToTracker = HashMap<String, Int>()           // tracker id -> level sent
    private val fromTracker = HashSet<String>()                  // SAME alerts the tracker decoded itself (not sent back)
    private var spaceSentMs = 0L
    private var ssnValue: Double? = null                         // sunspot number (SWPC; refreshed every 6 h)
    private var ssnAtMs = 0L
    private var dailyFlux: Double? = null                        // 10.7 cm flux of the daily indices (if the summary fails)
    private var country = ""                                    // ISO code of the position (cached)
    private var countryAt: Pair<Double, Double>? = null

    private const val UA = "APRSLoRaPlus-Android (+https://ea7jrs.github.io/APRS-LoRa-Plus/)"

    private fun get(url: String, limit: Int = 4_000_000, accept: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        try {
            val code = c.responseCode
            if (code == 204) return ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > limit) throw IllegalStateException("answer too big") }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun text(url: String, accept: String? = null) = String(get(url, accept = accept), Charsets.UTF_8)

    /** Country of the position: the Android geocoder, else a box for Spain / the US, else the phone's locale. */
    private fun countryOf(ctx: Context, lat: Double, lon: Double): String {
        Settings(ctx).alertCountry.takeIf { it.length == 2 }?.let { return it }
        countryAt?.let { if (Geo.near(it, lat, lon) && country.isNotEmpty()) return country }
        var iso = ""
        try {
            @Suppress("DEPRECATION")
            if (Geocoder.isPresent()) iso = Geocoder(ctx, Locale.US).getFromLocation(lat, lon, 1)?.firstOrNull()?.countryCode ?: ""
        } catch (_: Exception) {}
        if (iso.isEmpty()) iso = when {
            (lat in 35.9..43.9 && lon in -9.5..4.5) || (lat in 27.5..29.5 && lon in -18.3..-13.3) -> "ES"
            (lat in 24.0..50.0 && lon in -125.0..-66.0) || (lat in 51.0..72.0 && lon in -170.0..-129.0) || (lat in 18.5..22.5 && lon in -160.5..-154.5) -> "US"
            else -> Locale.getDefault().country
        }
        country = iso.uppercase(Locale.ROOT); countryAt = lat to lon
        return country
    }

    private object Geo {
        fun near(a: Pair<Double, Double>, lat: Double, lon: Double) = kotlin.math.abs(a.first - lat) < 0.5 && kotlin.math.abs(a.second - lon) < 0.5
    }

    private fun within(pos: Pair<Double, Double>?, a: OfficialAlert, km: Double): Boolean {
        val la = a.lat ?: return false
        val lo = a.lon ?: return false
        return pos != null && es.ea7jrs.aprslorap.core.Geo.distanceKm(pos.first, pos.second, la, lo) < km
    }

    /** Controller tick: downloads when the interval is due. */
    fun maybeFetch(ctx: Context, settings: Settings) {
        if (!settings.alertsOn || busy) return
        val every = settings.alertMinutes.coerceAtLeast(10) * 60_000L
        if (lastMs != 0L && System.currentTimeMillis() - lastMs < every) return
        fetchNow(ctx, settings)
    }

    fun fetchNow(ctx: Context, settings: Settings) {
        if (busy) return
        busy = true
        lastMs = System.currentTimeMillis()
        Thread({
            val errors = ArrayList<String>()
            try {
                val pos = NetWeatherFetcher.position(ctx)
                val nowS = System.currentTimeMillis() / 1000
                fun run(name: String, f: () -> Unit) = try { f() } catch (e: Exception) { errors.add("$name: ${e.message}") }
                if (settings.alertWeather && pos != null) {
                    val iso = countryOf(ctx, pos.first, pos.second)
                    if (iso == "ES") run("AEMET") { bySource[AlertSrc.AEMET] = aemet(pos.first, pos.second); bySource.remove(AlertSrc.METEOALARM) }
                    else {
                        bySource.remove(AlertSrc.AEMET)
                        val feed = OfficialAlerts.meteoAlarmFeedName(iso)
                        if (feed != null) run("MeteoAlarm") {
                            bySource[AlertSrc.METEOALARM] = OfficialAlerts.xmlBlocks(text(OfficialAlerts.METEOALARM_FEED + feed), "entry")
                                .mapNotNull { OfficialAlerts.meteoAlarmEntry(it) }
                                .filter { it.level >= 1 && it.activeAt(nowS) && OfficialAlerts.zoneMatch(it.area, settings.alertZones) }
                        } else bySource.remove(AlertSrc.METEOALARM)
                    }
                    if (iso == "US") run("NWS") {
                        bySource[AlertSrc.NWS] = OfficialAlerts.nws(text(OfficialAlerts.NWS_ACTIVE + String.format(Locale.US, "%.2f,%.2f", pos.first, pos.second), "application/geo+json"))
                            .filter { it.activeAt(nowS) }
                    } else bySource.remove(AlertSrc.NWS)
                } else if (!settings.alertWeather) { bySource.remove(AlertSrc.AEMET); bySource.remove(AlertSrc.METEOALARM); bySource.remove(AlertSrc.NWS) }
                if (settings.alertTsunami) run("tsunami.gov") {
                    val t = (OfficialAlerts.xmlBlocks(text(OfficialAlerts.TSUNAMI_PTWC), "entry") + OfficialAlerts.xmlBlocks(text(OfficialAlerts.TSUNAMI_NTWC), "entry"))
                        .mapNotNull { OfficialAlerts.tsunamiEntry(it) }.filter { it.activeAt(nowS) }
                    // information messages only near (they are for every quake of M6.5+ around the ocean)
                    bySource[AlertSrc.TSUNAMI] = t.filter { a -> a.level >= 1 || within(pos, a, 1500.0) }
                } else bySource.remove(AlertSrc.TSUNAMI)
                if (settings.alertQuakes) run("earthquakes") {
                    val q = ArrayList<OfficialAlert>()
                    runCatching { q.addAll(OfficialAlerts.usgs(text(OfficialAlerts.USGS_45_DAY))) }.onFailure { errors.add("USGS: ${it.message}") }
                    if (pos != null) runCatching { q.addAll(OfficialAlerts.emsc(text(OfficialAlerts.emscUrl(pos.first, pos.second, nowS - 86400)))) }.onFailure { errors.add("EMSC: ${it.message}") }
                    if (pos != null) runCatching { q.addAll(OfficialAlerts.fdsnText(text(OfficialAlerts.shakeUrl(pos.first, pos.second, nowS - 86400)), AlertSrc.SHAKE)) }.onFailure { errors.add("ShakeNet: ${it.message}") }
                    val all = OfficialAlerts.quakeLevels(q, pos?.first, pos?.second)
                    // the list keeps the ones that matter here: yellow or more, and the information ones closer than 1000 km
                    val near = all.filter { a -> a.level >= 1 || within(pos, a, 1000.0) }
                    bySource[AlertSrc.USGS] = near.filter { it.src == AlertSrc.USGS }
                    bySource[AlertSrc.EMSC] = near.filter { it.src == AlertSrc.EMSC }
                    bySource[AlertSrc.SHAKE] = near.filter { it.src == AlertSrc.SHAKE }
                } else { bySource.remove(AlertSrc.USGS); bySource.remove(AlertSrc.EMSC); bySource.remove(AlertSrc.SHAKE) }
                if (settings.alertSpace) run("SWPC") {
                    var w = SpaceWx()
                    OfficialAlerts.swpcScales(text(OfficialAlerts.SWPC_SCALES), w)?.let { w = it }
                    runCatching { OfficialAlerts.swpcKp(text(OfficialAlerts.SWPC_KP), w)?.let { w = it } }
                    for (u in listOf(OfficialAlerts.SWPC_FLUX, OfficialAlerts.SWPC_MAG, OfficialAlerts.SWPC_WIND)) runCatching { OfficialAlerts.swpcSummary(text(u), w)?.let { w = it } }
                    // real-time solar wind (1 min, as sat-aprs): over the summaries when it answers
                    runCatching { OfficialAlerts.rtswWind(text(OfficialAlerts.SWPC_RTSW_WIND), w)?.let { w = it } }
                    runCatching { OfficialAlerts.rtswMag(text(OfficialAlerts.SWPC_RTSW_MAG), w)?.let { w = it } }
                    runCatching { OfficialAlerts.swpcXray(text(OfficialAlerts.SWPC_XRAY), w)?.let { w = it } }
                    if (System.currentTimeMillis() - ssnAtMs > 3_600_000L || ssnValue == null) {          // once an hour, as sat-aprs
                        val daily = runCatching { OfficialAlerts.swpcDailyIndices(text(OfficialAlerts.SWPC_DAILY_INDICES)) }.getOrNull()
                        val v = daily?.second ?: runCatching { OfficialAlerts.swpcSsn(text(OfficialAlerts.SWPC_SSN_MONTHLY)) }.getOrNull()
                        if (v != null) { ssnValue = v; ssnAtMs = System.currentTimeMillis() }
                        if (daily?.first != null) dailyFlux = daily.first
                    }
                    w = w.copy(ssn = ssnValue, sfi = w.sfi ?: dailyFlux)
                    AppState.spaceWx.value = w.copy(updatedMs = System.currentTimeMillis())
                    bySource[AlertSrc.SWPC] = OfficialAlerts.swpcAlerts(text(OfficialAlerts.SWPC_ALERTS), nowS)
                } else bySource.remove(AlertSrc.SWPC)
                publish(settings, nowS)
                AppState.officialMessage.value = (if (errors.isEmpty()) "Updated" else errors.joinToString("; ")) +
                    " at " + java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date()) + if (pos == null) " (no position: only worldwide sources)" else ""
            } catch (e: Exception) {
                AppState.officialMessage.value = "Alerts failed: ${e.message}"
            } finally { busy = false }
        }, "official-alerts").start()
    }

    /** AEMET: the RSS (small) and, only when it points at a new bundle, the .tar.gz with every CAP; the ones whose polygon has us inside. */
    private fun aemet(lat: Double, lon: Double): List<OfficialAlert> {
        val rssUrl = if (lat < 30) OfficialAlerts.AEMET_RSS_CANARIAS else OfficialAlerts.AEMET_RSS_PENINSULA
        val rss = text(rssUrl)
        val bundle = OfficialAlerts.xmlBlocks(rss, "item").map { OfficialAlerts.xmlText(it, "link") }.firstOrNull { it.endsWith(".tar.gz") } ?: ""
        if (bundle.isNotEmpty() && bundle != aemetBundle) {
            val tar = GZIPInputStream(get(bundle, 2_000_000).inputStream()).use { it.readBytes() }
            aemetCaps = OfficialAlerts.tarFiles(tar) { it.endsWith(".xml") }.flatMap { (_, b) -> OfficialAlerts.capParse(String(b, Charsets.UTF_8), "en", AlertSrc.AEMET) }
            aemetBundle = bundle
        }
        val nowS = System.currentTimeMillis() / 1000
        if (aemetCaps.isNotEmpty()) return aemetCaps.filter { (a, poly) -> a.level >= 1 && a.activeAt(nowS) && poly.size > 2 && OfficialAlerts.pointInPolygon(lat, lon, poly) }
            .map { it.first }.distinctBy { it.id }
        // without the bundle: the RSS items (no polygons): the zone filter
        return OfficialAlerts.xmlBlocks(rss, "item").mapNotNull { OfficialAlerts.aemetRssItem(it) }
            .filter { it.level >= 1 && it.activeAt(nowS) && OfficialAlerts.zoneMatch(it.area, Settings(AppState.appContext ?: return emptyList()).alertZones) }
    }

    /** A SAME header (NOAA Weather Radio): from the tracker (event "alert") or the app's receiver. */
    fun onSame(a: OfficialAlert, byTracker: Boolean = false) {
        val s = AppState.appContext?.let { Settings(it) } ?: return
        if (!s.alertSame) return
        if (byTracker) synchronized(this) { fromTracker.add(a.id) }
        bySource[AlertSrc.SAME] = (bySource[AlertSrc.SAME] ?: emptyList()).filter { it.id != a.id } + a
        publish(s, System.currentTimeMillis() / 1000)
    }

    fun onSameHeader(h: String) {
        if (h == "NNNN") return
        Same.parse(h)?.let { onSame(Same.toAlert(it, System.currentTimeMillis() / 1000)) }
    }

    @Synchronized
    private fun publish(settings: Settings, nowS: Long) {
        val all = bySource.values.flatten().filter { it.activeAt(nowS) }
            .sortedWith(compareByDescending<OfficialAlert> { it.level }.thenBy { it.onset })
        AppState.officialAlerts.value = all
        // new or raised: the window (MeteoAlarm without zones: the whole country, so only red)
        for (a in all) {
            val was = levels[a.id]
            levels[a.id] = a.level
            if (was != null && a.level <= was) continue
            val min = if (a.src == AlertSrc.METEOALARM && settings.alertZones.isBlank()) 3 else settings.alertPopupLevel
            if (settings.alertPopupLevel <= 3 && a.level >= min) AlertCenter.onOfficial(a, a.level >= settings.alertBuzzLevel && settings.alertBuzzLevel <= 3)
        }
        levels.keys.retainAll(all.map { it.id }.toSet())
        syncTracker()
    }

    /** The tracker gets every alert (it decides its own window and buzzer) and the space weather. */
    @Synchronized
    fun syncTracker() {
        val c = AppState.controller ?: return
        if (AppState.trackerInfo.value?.hasAlerts != true) return
        val now = AppState.officialAlerts.value.filter { it.id !in fromTracker }.associateBy { OfficialAlerts.trackerId(it) }
        for ((id, a) in now) {
            if (sentToTracker[id] == a.level) continue
            c.sendOfficial(Control.command("alert", *OfficialAlerts.command(a).toList().toTypedArray()))
            sentToTracker[id] = a.level
        }
        for (id in sentToTracker.keys - now.keys) c.sendOfficial(Control.command("alertdel", "id" to id))
        sentToTracker.keys.retainAll(now.keys)
        val w = AppState.spaceWx.value
        if (w != null && w.valid && w.updatedMs != spaceSentMs) {
            c.sendOfficial(Control.command("spacewx", *OfficialAlerts.spaceCommand(w).toList().toTypedArray()))
            spaceSentMs = w.updatedMs
        }
    }

    /** A new link (or tracker restart): everything again. */
    @Synchronized
    fun trackerConnected() {
        sentToTracker.clear()
        spaceSentMs = 0
        syncTracker()
    }
}
