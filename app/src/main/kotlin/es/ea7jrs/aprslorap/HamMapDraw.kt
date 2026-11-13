/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.HamLayers
import es.ea7jrs.aprslorap.core.Qso
import es.ea7jrs.aprslorap.core.Spots
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.floor

/** Coloured lat / lon rectangles (aurora, D-RAP, worked grids): south, west, north, east and ARGB of each cell. */
private class CellsOverlay(private val cells: List<DoubleArray>, private val colors: IntArray) : Overlay() {
    private val paint = Paint()
    private val a = Point(); private val b = Point()
    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        for (i in cells.indices) {
            val r = cells[i]
            pj.toPixels(GeoPoint(r[2].coerceIn(-85.0, 85.0), r[1]), a); pj.toPixels(GeoPoint(r[0].coerceIn(-85.0, 85.0), r[3]), b)
            paint.color = colors[i]
            c.drawRect(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat(), maxOf(a.x, b.x).toFloat() + 1, maxOf(a.y, b.y).toFloat() + 1, paint)
        }
    }
}

/** Maidenhead grid: fields, squares or subsquares by zoom, with the locator in each cell when it is big enough. */
private class GridOverlay(private val alpha: Int, density: Float) : Overlay() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (alpha shl 24) or 0x7A5FBF; strokeWidth = 1.2f * density; style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (alpha shl 24) or 0x5A3FA0; textSize = 12 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val a = Point(); private val b = Point()
    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val bb = map.boundingBox
        val (dLon, dLat) = HamLayers.gridStep(map.zoomLevelDouble)
        val west = floor(maxOf(-180.0, bb.lonWest) / dLon) * dLon; val east = minOf(180.0, bb.lonEast)
        val south = floor(maxOf(-85.0, bb.latSouth) / dLat) * dLat; val north = minOf(85.0, bb.latNorth)
        if ((east - west) / dLon > 200 || (north - south) / dLat > 200) return
        val pj = map.projection
        var lon = west
        while (lon <= east + 1e-9) { pj.toPixels(GeoPoint(north, lon), a); pj.toPixels(GeoPoint(south, lon), b); c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line); lon += dLon }
        var lat = south
        while (lat <= north + 1e-9) { pj.toPixels(GeoPoint(lat, west), a); pj.toPixels(GeoPoint(lat, east), b); c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line); lat += dLat }
        pj.toPixels(GeoPoint(south, west), a); pj.toPixels(GeoPoint(south + dLat, west + dLon), b)
        if (kotlin.math.abs(b.x - a.x) < 34) return                                  // too small to write in
        val chars = when (dLon) { 20.0 -> 2; 2.0 -> 4; else -> 6 }
        lon = west
        while (lon < east) {
            lat = south
            while (lat < north) {
                val loc = Spots.locator(lat + dLat / 2, lon + dLon / 2, if (chars == 2) 4 else chars).take(chars)
                pj.toPixels(GeoPoint(lat + dLat / 2, lon + dLon / 2), a)
                c.drawText(loc, a.x.toFloat(), a.y + 4f, text)
                lat += dLat
            }
            lon += dLon
        }
    }
}

private fun band(c: String) = when (c) { "160m", "80m" -> 0xFFB39DDB; "40m" -> 0xFF4FC3F7; "30m", "20m" -> 0xFF3CE68A; "17m", "15m" -> 0xFFF2D35B; "12m", "10m" -> 0xFFFF8C1A; else -> 0xFFE8EEF3 }.toInt()

/**
 * The overlays of the HAM, SPACE and EVENTS layer groups for the current data. [gcTo]: far end of the great circle (long
 * press, else the focused station). [onCard]: what a touched mark shows.
 */
internal fun hamOverlays(
    map: MapView, ctx: Context, on: Set<String>, opacity: (String) -> Int, d: HamLayersFetcher.Data, me: Pair<Double, Double>?,
    gcTo: Pair<Double, Double>?, qsos: List<Qso>, nowMs: Long, aircraft: List<es.ea7jrs.aprslorap.core.Adsb.Aircraft>, onCard: (MapCard) -> Unit,
): List<Overlay> {
    val out = ArrayList<Overlay>()
    val dens = ctx.resources.displayMetrics.density
    fun a255(id: String) = opacity(id) * 255 / 100
    fun tag(p: GeoPoint, text: String, bg: Int, fg: Int, id: String, card: (() -> MapCard)?) = Marker(map).apply {
        position = p
        icon = BitmapDrawable(ctx.resources, MarkerArt.tag(ctx, text, bg, fg))
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); alpha = opacity(id) / 100f
        setOnMarkerClickListener { _, _ -> card?.let { onCard(it()) }; true }
    }
    val bb = map.boundingBox
    fun inView(lat: Double, lon: Double) = lat in (bb.latSouth - 1)..(bb.latNorth + 1) && lon in (bb.lonWest - 1)..(bb.lonEast + 1)
    val dark = 0xE6131A22.toInt()

    if ("ham_gc" in on) out.add(MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?) = false
        override fun longPressHelper(p: GeoPoint?): Boolean { p ?: return false; HamLayersFetcher.gcTarget.value = p.latitude to p.longitude; return true }
    }))

    d.aurora?.takeIf { "sp_aurora" in on }?.let { au ->
        out.add(CellsOverlay(au.cells.map { doubleArrayOf(it.first - 0.5, it.second - 0.5, it.first + 0.5, it.second + 0.5) },
            au.cells.map { HamLayers.auroraColor(it.third, a255("sp_aurora")) }.toIntArray()))
    }
    d.drap?.takeIf { "sp_drap" in on && it.lats.size > 1 && it.lons.size > 1 }?.let { dr ->
        val dLat = kotlin.math.abs(dr.lats[0] - dr.lats[1]); val dLon = kotlin.math.abs(dr.lons[1] - dr.lons[0])
        val cells = ArrayList<DoubleArray>(); val cols = ArrayList<Int>()
        for (i in dr.lats.indices) for (j in dr.lons.indices) {
            val f = dr.freqs[i].getOrNull(j) ?: continue
            val col = HamLayers.drapColor(f, a255("sp_drap")); if (col == 0) continue
            cells.add(doubleArrayOf(dr.lats[i] - dLat / 2, dr.lons[j] - dLon / 2, dr.lats[i] + dLat / 2, dr.lons[j] + dLon / 2)); cols.add(col)
        }
        out.add(CellsOverlay(cells, cols.toIntArray()))
    }
    if ("ham_worked" in on) {
        val grids = qsos.map { it["GRIDSQUARE"].take(4).uppercase(Locale.US) }.filter { it.length == 4 }.groupingBy { it }.eachCount()
        val cells = grids.keys.mapNotNull { HamLayers.gridBounds(it) }
        out.add(CellsOverlay(cells, IntArray(cells.size) { (a255("ham_worked") shl 24) or 0x3CE68A }))
    }
    if ("ham_grid" in on) out.add(GridOverlay(a255("ham_grid"), dens))
    for ((id, zones) in listOf("ham_cq" to d.cq, "ham_itu" to d.itu)) if (id in on) for (z in zones) {
        val col = if (id == "ham_cq") 0x28B5E1 else 0xFFB347
        for (r in z.rings) out.add(Polygon(map).apply {
            points = r.map { GeoPoint(it.first.coerceIn(-85.0, 85.0), it.second) }
            fillPaint.color = 0; outlinePaint.color = (a255(id) shl 24) or col; outlinePaint.strokeWidth = 1.6f * dens
            setOnClickListener { _, _, _ -> false }
        })
        out.add(tag(GeoPoint(z.labelLat, z.labelLon), (if (id == "ham_cq") "CQ " else "ITU ") + z.number, dark, (0xFF shl 24) or col, id) {
            MapCard((if (id == "ham_cq") "CQ zone " else "ITU zone ") + z.number, (0xFF shl 24) or col, listOf("Name" to z.name), "HB9HIL hamradio-zones-geojson (MIT)")
        })
    }
    if ("ham_gc" in on && me != null && gcTo != null) {
        val km = Geo.distanceKm(me.first, me.second, gcTo.first, gcTo.second); val brg = Geo.bearingDeg(me.first, me.second, gcTo.first, gcTo.second)
        for ((long, segs) in listOf(false to HamLayers.greatCircle(me.first, me.second, gcTo.first, gcTo.second), true to HamLayers.greatCircle(me.first, me.second, gcTo.first, gcTo.second, longPath = true)))
            for (s in segs) out.add(Polyline(map).apply {
                setPoints(s.map { GeoPoint(it.first.coerceIn(-85.0, 85.0), it.second) })
                outlinePaint.color = (a255("ham_gc") * (if (long) 60 else 100) / 100 shl 24) or 0x28B5E1
                outlinePaint.strokeWidth = (if (long) 2f else 3f) * dens
                if (long) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10 * dens, 7 * dens), 0f)
            })
        val sp = String.format(Locale.US, "SP %03.0f° · %,.0f km", brg, km); val lp = String.format(Locale.US, "LP %03.0f° · %,.0f km", (brg + 180) % 360, 40_030 - km)
        out.add(tag(GeoPoint(gcTo.first, gcTo.second), "$sp  ·  $lp", dark, 0xFF28B5E1.toInt(), "ham_gc") {
            MapCard("Great circle", 0xFF28B5E1.toInt(), listOf("Short path" to sp.removePrefix("SP "), "Long path" to lp.removePrefix("LP "),
                "Locator" to Spots.locator(gcTo.first, gcTo.second), "Note" to "Long press on the map to move the far end."), "Computed", gcTo.first, gcTo.second)
        })
    }
    if ("ham_ibp" in on) {
        val now = HamLayers.ibpNow(nowMs).associate { it.second.call to it.first }
        val left = HamLayers.ibpSecondsLeft(nowMs)
        for (b in HamLayers.BEACONS) {
            val band = now[b.call]
            out.add(tag(GeoPoint(b.lat, b.lon), b.call + (band?.let { " · " + it.label } ?: ""), if (band != null) 0xFF3CE68A.toInt() else dark,
                if (band != null) 0xFF131A22.toInt() else 0xFF3CE68A.toInt(), "ham_ibp") {
                MapCard(b.call + " · " + b.location, 0xFF3CE68A.toInt(), listOf("Locator" to b.grid,
                    "Now" to (band?.let { String.format(Locale.US, "%.3f MHz (%s), %d s left", it.mhz, it.label, left) } ?: "silent"),
                    "On the air" to HamLayers.ibpNow(nowMs).joinToString(" · ") { it.first.label + " " + it.second.call },
                    "Bearing" to (me?.let { String.format(Locale.US, "%03.0f° · %,.0f km", Geo.bearingDeg(it.first, it.second, b.lat, b.lon), Geo.distanceKm(it.first, it.second, b.lat, b.lon)) } ?: "")
                ).filter { it.second.isNotBlank() }, "NCDXF / IARU International Beacon Project: 14.100 → 18.110 → 21.150 → 24.930 → 28.200, 10 s each", b.lat, b.lon)
            })
        }
    }
    if ("ev_cyclones" in on) for (s in d.storms) {
        if (s.cone.size >= 3) out.add(Polygon(map).apply {
            points = s.cone.map { GeoPoint(it.first, it.second) }
            fillPaint.color = (a255("ev_cyclones") * 15 / 100 shl 24) or 0xFFFFFF; outlinePaint.color = (a255("ev_cyclones") shl 24) or 0xE8EEF3; outlinePaint.strokeWidth = 1.5f * dens
            setOnClickListener { _, _, _ -> false }
        })
        if (s.track.size >= 2) out.add(Polyline(map).apply {
            setPoints(s.track.map { GeoPoint(it.first, it.second) }); outlinePaint.color = (a255("ev_cyclones") shl 24) or 0xFF6B6B; outlinePaint.strokeWidth = 3f * dens
        })
        for (f in s.forecast.filter { (it.hour ?: 0) > 0 }) out.add(tag(GeoPoint(f.lat, f.lon), "+${f.hour} h" + (f.windKt?.let { " · $it kn" } ?: ""), dark, 0xFFFFB347.toInt(), "ev_cyclones", null))
        val title = "${s.label} ${s.name}" + (if (s.category > 0) " · Cat ${s.category}" else "")
        out.add(tag(GeoPoint(s.lat, s.lon), "🌀 ${s.name} " + (s.windKt?.let { "$it kn" } ?: ""), 0xFFE53935.toInt(), 0xFFFFFFFF.toInt(), "ev_cyclones") {
            MapCard(title, 0xFFFF6B6B.toInt(), listOf("Wind" to (s.windKt?.let { "$it kn" } ?: ""), "Pressure" to (s.pressureMb?.let { "$it hPa" } ?: ""),
                "Moving" to (s.movementDeg?.let { String.format(Locale.US, "%03d° at %s kn", it, s.movementKt ?: "?") } ?: ""),
                "From you" to (me?.let { String.format(Locale.US, "%,.0f km at %03.0f°", Geo.distanceKm(it.first, it.second, s.lat, s.lon), Geo.bearingDeg(it.first, it.second, s.lat, s.lon)) } ?: ""),
                "Forecast" to s.forecast.filter { (it.hour ?: 0) > 0 }.joinToString(" · ") { "+${it.hour} h ${it.windKt ?: "?"} kn" },
                "Updated" to s.lastUpdate.take(16).replace('T', ' ')).filter { it.second.isNotBlank() }, "NOAA National Hurricane Center", s.lat, s.lon)
        })
    }
    for ((id, list, icon, col) in listOf(Quad("ev_fires", d.fires, "🔥", 0xFFFF8C1A.toInt()), Quad("ev_floods", d.floods, "≈", 0xFF4FC3F7.toInt()))) if (id in on) for (e in list) {
        out.add(tag(GeoPoint(e.lat, e.lon), "$icon " + e.title.take(26), dark, col, id) {
            MapCard(e.title, col, listOf("Type" to e.category, "Size" to e.magnitude, "Last seen" to Fmt.ago(e.timeMs)).filter { it.second.isNotBlank() }, "NASA EONET", e.lat, e.lon)
        })
    }
    if ("ev_buoys" in on) for (b in d.buoys.filter { inView(it.lat, it.lon) }.take(150)) {
        val txt = listOfNotNull(b.waveM?.let { String.format(Locale.US, "%.1f m", it) }, b.windMs?.let { String.format(Locale.US, "%.0f kn", it * 1.94384) }).joinToString(" · ")
        out.add(tag(GeoPoint(b.lat, b.lon), b.id + (if (txt.isNotEmpty()) " · $txt" else ""), dark, 0xFF4FC3F7.toInt(), "ev_buoys") {
            MapCard("Buoy " + b.id, 0xFF4FC3F7.toInt(), listOf(
                "Wind" to listOfNotNull(b.windDeg?.let { String.format(Locale.US, "%03d°", it) }, b.windMs?.let { String.format(Locale.US, "%.0f kn", it * 1.94384) }, b.gustMs?.let { String.format(Locale.US, "gusts %.0f kn", it * 1.94384) }).joinToString(" "),
                "Waves" to listOfNotNull(b.waveM?.let { String.format(Locale.US, "%.1f m", it) }, b.periodS?.let { String.format(Locale.US, "%.0f s", it) }).joinToString(" · "),
                "Pressure" to (b.pressureHpa?.let { String.format(Locale.US, "%.1f hPa", it) } ?: ""),
                "Air / water" to listOfNotNull(b.airC?.let { String.format(Locale.US, "%.1f °C", it) }, b.waterC?.let { String.format(Locale.US, "%.1f °C", it) }).joinToString(" / "),
                "Observed" to Fmt.ago(b.timeMs)).filter { it.second.isNotBlank() }, "NOAA National Data Buoy Center", b.lat, b.lon)
        })
    }
    if ("ev_balloons" in on) for (b in d.balloons) {
        if (b.track.size >= 2) out.add(Polyline(map).apply { setPoints(b.track.map { GeoPoint(it.first, it.second) }); outlinePaint.color = (a255("ev_balloons") * 70 / 100 shl 24) or 0xE8EEF3; outlinePaint.strokeWidth = 2f * dens })
        out.add(tag(GeoPoint(b.lat, b.lon), "🎈 " + b.call + (b.altM?.let { String.format(Locale.US, " %.1f km", it / 1000) } ?: ""), dark, 0xFFE8EEF3.toInt(), "ev_balloons") {
            MapCard("Balloon " + b.call, 0xFFE8EEF3.toInt(), listOf("Altitude" to (b.altM?.let { String.format(Locale.US, "%,.0f m", it) } ?: ""), "Heard" to Fmt.ago(b.timeMs), "Comment" to b.comment)
                .filter { it.second.isNotBlank() }, "SondeHub amateur", b.lat, b.lon)
        })
    }
    if ("ev_aircraft" in on) for (p in aircraft.take(300)) {
        val la = p.lat ?: continue; val lo = p.lon ?: continue
        if (!inView(la, lo)) continue
        val col = if (p.emergency != null) 0xFFFF6B6B.toInt() else 0xFFC9D4DE.toInt()
        out.add(tag(GeoPoint(la, lo), "✈ ${p.name}" + (p.flightLevel()?.let { " $it" } ?: ""), dark, col, "ev_aircraft") {
            MapCard(p.name + (p.emergency?.let { " · $it" } ?: ""), col, listOf("Altitude" to (p.altFt?.let { String.format(Locale.US, "%,d ft", it) } ?: ""),
                "Speed" to (p.gsKt?.let { String.format(Locale.US, "%.0f kn", it) } ?: ""), "Track" to (p.trackDeg?.let { String.format(Locale.US, "%03.0f°", it) } ?: ""),
                "Vertical" to (p.vrFpm?.let { String.format(Locale.US, "%+d ft/min", it) } ?: ""), "Squawk" to (p.squawk?.let { String.format(Locale.US, "%04d", it) } ?: ""),
                "ICAO" to p.hex, "Position" to Fmt.ago(p.posMs))
                .filter { it.second.isNotBlank() }, "ADS-B received by this app", la, lo)
        })
    }
    return out
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
