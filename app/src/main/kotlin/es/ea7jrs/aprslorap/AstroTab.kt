/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import es.ea7jrs.aprslorap.core.Celnav
import es.ea7jrs.aprslorap.core.Current
import es.ea7jrs.aprslorap.core.Deviation
import es.ea7jrs.aprslorap.core.Wmm
import java.util.Locale
import kotlin.math.abs

/** Sights kept while the app runs (they survive leaving the screen). */
/** Departure marked for the current from dead reckoning (kept while the app lives). */
object CurrentMark {
    var lat = 0.0; var lon = 0.0; var timeMs = 0L
}

object AstroSights {
    data class Lop(val sight: Celnav.Sight, val body: String, val timeMs: Long, val atLat: Double, val atLon: Double)
    val list = mutableStateOf(listOf<Lop>())
    const val MAX = 4
}

/**
 * Celestial navigation with a sextant, as Carta > Astro of the APRS LoRa+ plotter: the Sun or the Moon (lower or upper limb), Venus, Mars, Jupiter, Saturn or one of the
 * 157 stars (a list of what is visible now, with the best three for a fix), the altitude corrections, the computed altitude, azimuth and intercept, and the fix from
 * two or more lines (earlier lines are advanced for the run since they were taken). Computed on the phone to almanac precision.
 */
@Composable
fun AstroTab() {
    var body by rememberSaveable { mutableStateOf(0) }            // 0 / 1 Sun lower / upper limb, 2 / 3 Moon, 4-7 Venus, Mars, Jupiter, Saturn, 8 star
    var star by rememberSaveable { mutableStateOf(0) }
    var showSky by rememberSaveable { mutableStateOf(false) }
    var deg by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var index by rememberSaveable { mutableStateOf("0") }
    var eye by rememberSaveable { mutableStateOf("2") }
    var timeMs by rememberSaveable { mutableStateOf(0L) }
    var hms by rememberSaveable { mutableStateOf("") }
    var out by rememberSaveable { mutableStateOf("Measure the altitude with the sextant, press NOW at the instant of the sight and type degrees and minutes.") }
    var fixText by rememberSaveable { mutableStateOf("") }
    val lops by AstroSights.list
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val card = remember { Deviation.Card().apply { decode(settings.devCard) } }
    var cardRev by rememberSaveable { mutableStateOf(0) }       // redraws the card after a change
    var bearing by rememberSaveable { mutableStateOf("") }
    var heading by rememberSaveable { mutableStateOf("") }
    var ctOut by rememberSaveable { mutableStateOf("Take the compass bearing of the body, press NOW at that instant and type the bearing and the boat's compass heading.") }
    var ctLast by remember { mutableStateOf<Pair<Double, Double>?>(null) }   // compass heading and deviation of the last valid check

    fun num(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    fun dm(v: Double): String { val a = abs(v); val d = a.toInt(); return String.format(Locale.US, "%d° %04.1f'", d, (a - d) * 60) }
    fun pos(lat: Double, lon: Double) = dm(lat) + (if (lat >= 0) " N  " else " S  ") + dm(lon) + (if (lon >= 0) " E" else " W")
    fun utc(ms: Long): String { val s = (ms / 1000) % 86400; return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
    val planets = Celnav.Planet.values()
    val bodyNames = listOf("Sun (lower limb)", "Sun (upper limb)", "Moon (lower limb)", "Moon (upper limb)", "Venus", "Mars", "Jupiter", "Saturn", "Star")
    val shortNames = listOf("Sun", "Sun", "Moon", "Moon", "Venus", "Mars", "Jupiter", "Saturn")
    /** GHA, declination, semi-diameter and horizontal parallax (arc minutes) of the chosen body */
    fun ephem(jd: Double): DoubleArray = when {
        body < 2 -> { val g = Celnav.sunGhaDec(jd); doubleArrayOf(g.gha, g.dec, Celnav.sunSemiDiameterArcmin(jd), 0.15) }
        body < 4 -> { val m = Celnav.moonGhaDec(jd); doubleArrayOf(m.gha, m.dec, Celnav.moonSdArcmin(m.dist), Celnav.moonHpArcmin(m.dist)) }
        body < 8 -> { val b = Celnav.planetGhaDec(planets[body - 4], jd); doubleArrayOf(b.gha, b.dec, 0.0, Celnav.planetHpArcmin(b.dist)) }
        else -> { val p = Celnav.starShaDec(Celnav.STARS[star], jd); doubleArrayOf((Celnav.ariesGha(jd) + p.sha) % 360.0, p.dec, 0.0, 0.0) }
    }
    fun now() { timeMs = System.currentTimeMillis(); hms = utc(timeMs).replace(":", "") }

    fun fix(me: Pair<Double, Double>, list: List<AstroSights.Lop>) {
        val ref = list.map { Celnav.referTo(Celnav.advanceSight(it.sight, it.atLat, it.atLon, me.first, me.second), me.first, me.second) }
        val f = Celnav.fixFromSights(me.first, me.second, ref)
        fixText = when {
            f != null -> "Fix from ${list.size} lines: ${pos(f.first, f.second)}"
            list.size >= 2 -> "Lines nearly parallel: no fix."
            else -> ""
        }
    }

    fun compute() {
        val me = Own.position()
        if (me == null) { out = "An assumed position is needed: the tracker's GNSS or your locator in Modes > Spotting."; return }
        if (hms.length != 6) { out = "Type the UTC time as hhmmss or press NOW."; return }
        val hh = hms.substring(0, 2).toInt(); val mm = hms.substring(2, 4).toInt(); val ss = hms.substring(4, 6).toInt()
        if (hh > 23 || mm > 59 || ss > 59) { out = "Invalid UTC time."; return }
        val nowMs = System.currentTimeMillis()
        var t = nowMs - nowMs % 86_400_000L + (hh * 3600 + mm * 60 + ss) * 1000L
        if (t > nowMs + 3_600_000L) t -= 86_400_000L              // a time "in the future" is yesterday's
        val hs = num(deg) + num(min) / 60.0
        if (hs <= 0 || hs >= 90) { out = "Sextant altitude between 0 and 90 degrees."; return }
        val jd = Celnav.julianDay(t)
        val g = ephem(jd)
        var sd = g[2]
        if (body == 2 || body == 3) sd *= 1.0 + kotlin.math.sin(Math.toRadians(hs)) * kotlin.math.sin(Math.toRadians(g[3] / 60.0))   // the Moon is nearer when high
        val ho = Celnav.observedAltitude(hs, num(index), num(eye),
            if (body >= 4) Celnav.Limb.CENTER else if (body % 2 == 0) Celnav.Limb.LOWER else Celnav.Limb.UPPER, sd, g[3])
        val s = Celnav.reduceSight(me.first, me.second, g[0], g[1], ho)
        if (s.hc < 0 || abs(s.interceptNm) > 120) {
            out = String.format(Locale.US, "It does not fit: at that time the body would be at %.0f° and the sight gives %.0f° (%.0f nm apart). Check the body, the UTC time and the altitude.", s.hc, ho, abs(s.interceptNm))
            return
        }
        val name = if (body < 8) shortNames[body] else Celnav.STARS[star].name
        val list = (AstroSights.list.value + AstroSights.Lop(s, name, t, me.first, me.second)).takeLast(AstroSights.MAX)
        AstroSights.list.value = list
        out = String.format(Locale.US, "%s %s UTC · observed %s · computed %s · azimuth %03.0f°\nIntercept: %.1f nm %s.",
            name, utc(t), dm(ho), dm(s.hc), s.zn, abs(s.interceptNm), if (s.interceptNm >= 0) "towards the body" else "away from the body")
        fix(me, list)
    }

    /** Latitude from the same sight: by the meridian, by Polaris or (with a warning) off the meridian; and when the body crosses the meridian. */
    fun latitude() {
        val me = Own.position()
        if (me == null) { out = "An assumed position is needed: the tracker's GNSS or your locator in Modes > Spotting."; return }
        if (hms.length != 6) { out = "Type the UTC time as hhmmss or press NOW."; return }
        val hh = hms.substring(0, 2).toInt(); val mm = hms.substring(2, 4).toInt(); val ss = hms.substring(4, 6).toInt()
        if (hh > 23 || mm > 59 || ss > 59) { out = "Invalid UTC time."; return }
        val nowMs = System.currentTimeMillis()
        var t = nowMs - nowMs % 86_400_000L + (hh * 3600 + mm * 60 + ss) * 1000L
        if (t > nowMs + 3_600_000L) t -= 86_400_000L
        val hs = num(deg) + num(min) / 60.0
        if (hs <= 0 || hs >= 90) { out = "Sextant altitude between 0 and 90 degrees."; return }
        val jd = Celnav.julianDay(t)
        val g = ephem(jd)
        var sd = g[2]
        if (body == 2 || body == 3) sd *= 1.0 + kotlin.math.sin(Math.toRadians(hs)) * kotlin.math.sin(Math.toRadians(g[3] / 60.0))
        val ho = Celnav.observedAltitude(hs, num(index), num(eye),
            if (body >= 4) Celnav.Limb.CENTER else if (body % 2 == 0) Celnav.Limb.LOWER else Celnav.Limb.UPPER, sd, g[3])
        val polaris = body == 8 && Celnav.STARS[star].name == "Polaris"
        val lha = Math.IEEEremainder(g[0] + me.second, 360.0)
        val lat = Celnav.latitudeFromAltitude(ho, g[1], lha, me.first)
        val sb = StringBuilder()
        if (lat == null) sb.append("No latitude gives that altitude at that time: check the body, the UTC time and the altitude.")
        else {
            val how = if (polaris) "by Polaris" else if (abs(lha) <= 2.5) "by meridian altitude" else "from the altitude (off the meridian)"
            val dn = (lat - me.first) * 60.0
            sb.append(String.format(Locale.US, "Latitude %s: %s %s · assumed %s %s (%.1f nm %s)", how, dm(lat), if (lat >= 0) "N" else "S",
                dm(me.first), if (me.first >= 0) "N" else "S", abs(dn), if (dn >= 0) "north" else "south"))
            if (polaris) sb.append(String.format(Locale.US, "\nPolaris correction at that time: %s%s", if (lat - ho < 0) "-" else "+", dm(lat - ho)))
            else if (abs(lha) > 2.5) sb.append(String.format(Locale.US, "\nLocal hour angle %.1f°: it depends on the assumed longitude. The good one is at meridian passage.", abs(lha)))
        }
        if (!polaris) {
            val tp = Celnav.meridianPassageJd(jd, me.second) { ephem(it)[0] }
            val d = ephem(tp)[1]
            val hmax = 90.0 - abs(me.first - d)
            val ms = Math.round((tp - 2440587.5) * 86_400_000.0)
            if (hmax > 0) sb.append(String.format(Locale.US, "\nMeridian passage: %s UTC, true altitude %s bearing %s.", utc(ms), dm(hmax), if (d < me.first) "S" else "N"))
            else sb.append("\nThat body does not rise above the horizon at meridian passage.")
        }
        out = sb.toString()
    }

    fun sign(v: Double) = if (v >= 0) "E" else "W"
    var curCog by rememberSaveable { mutableStateOf("") }
    var curStw by rememberSaveable { mutableStateOf("") }
    var curLw by rememberSaveable { mutableStateOf("0") }
    var curSet by rememberSaveable { mutableStateOf("") }
    var curDrift by rememberSaveable { mutableStateOf("") }
    var curRv by rememberSaveable { mutableStateOf("") }
    var curOut by rememberSaveable { mutableStateOf("Course to steer: the course you want to make good, the speed through the water, the leeway (+ to starboard) and the current.") }

    var bm1Lat by rememberSaveable { mutableStateOf("") }
    var bm1Lon by rememberSaveable { mutableStateOf("") }
    var bm2Lat by rememberSaveable { mutableStateOf("") }
    var bm2Lon by rememberSaveable { mutableStateOf("") }
    var bB1 by rememberSaveable { mutableStateOf("") }
    var bB2 by rememberSaveable { mutableStateOf("") }
    var bT1 by rememberSaveable { mutableStateOf(0L) }
    var bT2 by rememberSaveable { mutableStateOf(0L) }
    var bCourse by rememberSaveable { mutableStateOf("") }
    var bSpeed by rememberSaveable { mutableStateOf("") }
    var bCt by rememberSaveable { mutableStateOf("0") }
    var bOut by rememberSaveable { mutableStateOf("Two bearings of known points (the same point twice for a running fix). Positions as 36 31.8N / 006 17.0W or decimal degrees.") }

    /** Fix from two bearings, simultaneous or a running fix, as the plotter's Carta > Demoras. */
    fun bearingFix() {
        val la1 = Current.parseCoord(bm1Lat, true); val lo1 = Current.parseCoord(bm1Lon, false)
        if (la1 == null || lo1 == null) { bOut = "Type the position of point 1."; return }
        val la2 = if (bm2Lat.isBlank()) la1 else Current.parseCoord(bm2Lat, true)
        val lo2 = if (bm2Lon.isBlank()) lo1 else Current.parseCoord(bm2Lon, false)
        if (la2 == null || lo2 == null) { bOut = "Point 2 does not read (leave it empty to use point 1 again)."; return }
        val b1 = bB1.replace(',', '.').toDoubleOrNull(); val b2 = bB2.replace(',', '.').toDoubleOrNull()
        if (b1 == null || b2 == null || b1 < 0 || b1 >= 360 || b2 < 0 || b2 >= 360) { bOut = "Type both bearings (0 to 359°)."; return }
        val t2 = if (bT2 != 0L) bT2 else System.currentTimeMillis()
        var run = 0.0
        val course = bCourse.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (bT1 != 0L && t2 > bT1) {
            val sp = bSpeed.replace(',', '.').toDoubleOrNull()
            if (bCourse.isBlank() || sp == null) { bOut = "Bearings at different times: type the course and speed made good between them."; return }
            run = sp * (t2 - bT1) / 3_600_000.0
        }
        val ct = bCt.replace(',', '.').toDoubleOrNull() ?: 0.0
        val f = Current.fixFromBearings(la1, lo1, (b1 + ct + 360) % 360, la2, lo2, (b2 + ct + 360) % 360, course, run)
        if (f == null) {
            val cut = Current.cutAngle(b1, b2)
            bOut = if (cut < 10) String.format(Locale.US, "The lines are nearly parallel (cut %.0f°): pick points further apart or wait for the bearing to change.", cut)
                   else "The lines meet behind the points: check the bearings and the total correction."
            return
        }
        val sb = StringBuilder("Fix: " + pos(f.lat, f.lon) + String.format(Locale.US, " · %.1f nm from point 2 · cut %.0f°%s", f.dist2Nm, f.cutDeg, if (f.cutDeg < 30) " (poor cut)" else ""))
        if (run > 0) sb.append(String.format(Locale.US, "\nRun between the bearings: %.1f nm at %03.0f° (first line advanced).", run, course))
        Own.position()?.let { me -> sb.append(String.format(Locale.US, "\nThe tracker's GNSS is %.2f nm from that fix.", Current.rhumbDistanceNm(f.lat, f.lon, me.first, me.second))) }
        bOut = sb.toString()
    }

    /** True course to steer with current and leeway, the compass course (variation and deviation card) and the speed made good. */
    fun steer() {
        val cog = curCog.replace(',', '.').toDoubleOrNull()
        val stw = curStw.replace(',', '.').toDoubleOrNull()
        if (cog == null || cog < 0 || cog >= 360) { curOut = "Type the course you want to make good (0 to 359°)."; return }
        if (stw == null || stw <= 0) { curOut = "Type the speed through the water (knots)."; return }
        val c = Current.Vec(curSet.replace(',', '.').toDoubleOrNull() ?: 0.0, curDrift.replace(',', '.').toDoubleOrNull() ?: 0.0)
        val r = Current.courseToSteer(cog, stw, curLw.replace(',', '.').toDoubleOrNull() ?: 0.0, c)
        if (r == null) { curOut = "With that current the course cannot be made: it crosses faster than the boat or stops it altogether."; return }
        val sb = StringBuilder(String.format(Locale.US, "True course to steer: %05.1f° (corrects %+.0f°) · speed made good %.1f kn",
            r.first, Math.IEEEremainder(r.first - cog, 360.0), r.second))
        Own.position()?.let { me ->
            val dm = Wmm.declination(me.first, me.second, System.currentTimeMillis())
            var ra = ((r.first - dm) % 360 + 360) % 360
            var dev = 0.0
            if (card.terms > 0) repeat(4) { dev = card.at(ra); ra = ((r.first - dm - dev) % 360 + 360) % 360 }
            sb.append(String.format(Locale.US, "\nCompass course: %05.1f° (variation %.1f° %s%s)", ra, abs(dm), sign(dm),
                if (card.terms > 0) String.format(Locale.US, ", deviation %+.1f°", dev) else ", no deviation card"))
        }
        curOut = sb.toString()
    }

    /** Current from the departure marked and the position now (the tracker's GNSS). */
    fun fromDr() {
        val me = Own.position()
        if (CurrentMark.timeMs == 0L) { curOut = "Press Mark departure at a known point first, then steer a steady course and speed."; return }
        if (me == null) { curOut = "A position is needed now: the tracker's GNSS."; return }
        val rv = curRv.replace(',', '.').toDoubleOrNull()
        val stw = curStw.replace(',', '.').toDoubleOrNull()
        if (rv == null || rv < 0 || rv >= 360) { curOut = "Type the true course steered since the departure."; return }
        if (stw == null || stw < 0) { curOut = "Type the speed through the water (knots)."; return }
        val h = (System.currentTimeMillis() - CurrentMark.timeMs) / 3_600_000.0
        if (h < 0.1) { curOut = "Less than 6 minutes since the departure: wait longer for the current to show."; return }
        val r = Current.fromDr(CurrentMark.lat, CurrentMark.lon, rv, stw, curLw.replace(',', '.').toDoubleOrNull() ?: 0.0, h, me.first, me.second)!!
        curSet = String.format(Locale.US, "%.0f", r.current.setDeg)
        curDrift = String.format(Locale.US, "%.1f", r.current.driftKn)
        val mins = Math.round(h * 60)
        curOut = String.format(Locale.US, "DR after %d h %02d min: %s.\nThe fix is %.1f nm %03.0f° from the DR: current setting %03.0f°, %.1f kn (copied above for the course to steer).",
            mins / 60, mins % 60, pos(r.drLat, r.drLon), r.current.driftKn * h, r.current.setDeg, r.current.setDeg, r.current.driftKn)
    }

    /** Total correction from the compass bearing of the chosen body (the limb does not matter) and the deviation for the compass heading. */
    fun computeCt() {
        ctLast = null
        val me = Own.position()
        if (me == null) { ctOut = "A position is needed: the tracker's GNSS or your locator in Modes > Spotting."; return }
        if (hms.length != 6) { ctOut = "Type the UTC time above as hhmmss or press NOW."; return }
        val hh = hms.substring(0, 2).toInt(); val mm = hms.substring(2, 4).toInt(); val ss = hms.substring(4, 6).toInt()
        if (hh > 23 || mm > 59 || ss > 59) { ctOut = "Invalid UTC time."; return }
        val nowMs = System.currentTimeMillis()
        var t = nowMs - nowMs % 86_400_000L + (hh * 3600 + mm * 60 + ss) * 1000L
        if (t > nowMs + 3_600_000L) t -= 86_400_000L
        val zc = bearing.replace(',', '.').toDoubleOrNull()
        val hc = heading.replace(',', '.').toDoubleOrNull()
        if (zc == null || zc < 0 || zc >= 360) { ctOut = "Type the compass bearing of the body (0 to 359.9°)."; return }
        if (hc == null || hc < 0 || hc >= 360) { ctOut = "Type the boat's compass heading when the bearing was taken (0 to 359°)."; return }
        val g = ephem(Celnav.julianDay(t))
        val aa = Celnav.bodyAltAz(g[0], g[1], me.first, me.second)
        val name = if (body < 8) shortNames[body] else Celnav.STARS[star].name
        if (aa.alt < -1) { ctOut = String.format(Locale.US, "At that time %s is below the horizon (%.0f°). Check the body and the UTC time.", name, aa.alt); return }
        val ct = Deviation.totalCorrection(aa.az, zc)
        val dm = Wmm.declination(me.first, me.second, t)
        val dev = Deviation.deviationFromCt(ct, dm)
        val sb = StringBuilder(String.format(Locale.US, "%s %s UTC · true azimuth %05.1f° (altitude %.0f°) · compass bearing %05.1f°\nTotal correction %+.1f° (%s) · variation %.1f° %s · deviation %+.1f° (%s) on compass heading %03.0f°",
            name, utc(t), aa.az, aa.alt, zc, ct, sign(ct), abs(dm), sign(dm), dev, sign(dev), hc))
        if (card.terms > 0) sb.append(String.format(Locale.US, "\nThe card gave %+.1f° on that heading.", card.at(hc)))
        if (abs(dev) > 20) sb.append("\nVery large deviation: check the bearing, the body and the time.")
        else if (aa.alt > 60) sb.append("\nWith the body this high the bearing is imprecise: better below 30°.")
        ctOut = sb.toString()
        if (abs(dev) <= 20) ctLast = hc to dev
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("Line of position (sextant)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            val me = Own.position()
            Text(if (me != null) "Assumed position: " + pos(me.first, me.second) else "No position: tracker GNSS without a fix and no locator in Spotting",
                fontSize = 12.sp, color = if (me != null) Alp.Muted else Alp.Amber)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
                OutlinedButton(onClick = { body = (body + 1) % 9 }) { Text(bodyNames[body]) }
                OutlinedButton(onClick = { body = 8; star = (star + Celnav.STARS.size - 1) % Celnav.STARS.size }) { Text("<") }
                OutlinedButton(onClick = { body = 8; star = (star + 1) % Celnav.STARS.size }) { Text(">") }
                OutlinedButton(onClick = { showSky = !showSky }) { Text(if (showSky) "Hide" else "Visible") }
                OutlinedButton(onClick = { Celnav.STARS.indexOfFirst { it.name == "Polaris" }.let { if (it >= 0) { body = 8; star = it } } }) { Text("Polaris") }
            }
            if (showSky) {
                if (me == null) Text("Needs a position to know what is above the horizon.", fontSize = 12.sp, color = Alp.Amber)
                else {
                    val jdNow = Celnav.julianDay(System.currentTimeMillis())
                    val sky = Celnav.visibleBodies(jdNow, me.first, me.second)
                    val trio = Celnav.bestStarTriple(sky)?.let { setOf(it.first, it.second, it.third) } ?: emptySet()
                    val sun = Celnav.sunGhaDec(jdNow).let { Celnav.bodyAltAz(it.gha, it.dec, me.first, me.second).alt }
                    val phase = when {
                        sun > -3 -> "Daylight: only the brightest stars can be seen, with difficulty."
                        sun < -12 -> "Full night: without a visible horizon the altitudes are not reliable."
                        else -> "Twilight: the right time for star sights."
                    }
                    Text("Between 10° and 80°, by azimuth. $phase" + if (trio.isNotEmpty()) " Amber: the three stars that best spread round the horizon." else "",
                        fontSize = 12.sp, color = Alp.Muted, modifier = Modifier.padding(top = 4.dp))
                    if (sky.isEmpty()) Text("Nothing between 10° and 80°.", fontSize = 13.sp)
                    for ((i, it) in sky.withIndex()) {
                        val main = it.kind < 3 || it.index < Celnav.NAV_STARS
                        Text(String.format(Locale.US, "%-14s  %4.0f°  %03.0f°", it.name, it.alt, it.az),
                            fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = if (i in trio) FontWeight.Bold else FontWeight.Normal,
                            color = if (i in trio) Alp.Amber else if (main) androidx.compose.ui.graphics.Color.Unspecified else Alp.Muted,
                            modifier = Modifier.fillMaxWidth().clickable {
                                body = when (it.kind) { 0 -> 0; 1 -> 2; 2 -> 4 + it.index; else -> 8 }
                                if (it.kind == 3) star = it.index
                                showSky = false
                            }.padding(vertical = 4.dp))
                    }
                }
            }
            if (body >= 2) {
                val hint = me?.let {
                    val g = ephem(Celnav.julianDay(System.currentTimeMillis()))
                    val aa = Celnav.bodyAltAz(g[0], g[1], it.first, it.second)
                    if (aa.alt > 0) String.format(Locale.US, " · now: altitude %.0f°, azimuth %03.0f°", aa.alt, aa.az) else " · now below the horizon"
                } ?: ""
                val label = if (body < 8) shortNames[body] else String.format(Locale.US, "%s (mag %.1f)", Celnav.STARS[star].name, Celnav.STARS[star].mag)
                Text(label + hint, fontSize = 13.sp, color = Alp.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(deg, { deg = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Degrees") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(min, { min = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(4) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(index, { index = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }.take(5) }, label = { Text("Index error, '") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                OutlinedTextField(eye, { eye = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(4) }, label = { Text("Eye height, m") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
                OutlinedTextField(hms, { hms = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("UTC hhmmss") }, singleLine = true, modifier = Modifier.width(150.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton(onClick = { now() }) { Text("NOW") }
                Button(onClick = { compute() }) { Text("Compute") }
                OutlinedButton(onClick = { latitude() }) { Text("Latitude") }
            }
            Text(out, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (fixText.isNotEmpty()) Text(fixText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Alp.Green, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Lines (${lops.size} of ${AstroSights.MAX})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (lops.isNotEmpty()) OutlinedButton(onClick = { AstroSights.list.value = emptyList(); fixText = ""; out = "Lines cleared." }) { Text("Clear") }
            }
            HorizontalDivider()
        }
        items(lops) { l ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(String.format(Locale.US, "%s  %s UTC  ·  azimuth %03.0f°  ·  %.1f nm %s", l.body, utc(l.timeMs), l.sight.zn, abs(l.sight.interceptNm),
                    if (l.sight.interceptNm >= 0) "towards" else "away"), fontSize = 13.sp)
                Text("Through " + pos(l.sight.lat, l.sight.lon), fontSize = 12.sp, color = Alp.Muted)
            }
            HorizontalDivider()
        }
        item {
            Text("Compass check (total correction)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("Uses the body and the UTC time above. A low body is best: the Sun rising or setting, or Polaris.", fontSize = 12.sp, color = Alp.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { Celnav.STARS.indexOfFirst { it.name == "Polaris" }.let { if (it >= 0) { body = 8; star = it } } }) { Text("Polaris") }
                OutlinedTextField(bearing, { bearing = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, label = { Text("Compass bearing, °") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(heading, { heading = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Compass heading, °") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = { computeCt() }) { Text("Compute Ct") }
                OutlinedButton(enabled = ctLast != null, onClick = {
                    ctLast?.let { (h, d) -> card.add(h, d); settings.devCard = card.encode(); cardRev++
                        ctOut = String.format(Locale.US, "Added to the card: compass heading %03.0f°, deviation %+.1f° (%d observations).", h, d, card.points.size) }
                    ctLast = null
                }) { Text("To the card") }
            }
            Text(ctOut, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            @Suppress("UNUSED_VARIABLE") val rev = cardRev           // read so the card below follows changes
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Deviation card (${card.points.size} of ${Deviation.Card.MAX})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (card.points.isNotEmpty()) OutlinedButton(onClick = { card.clear(); settings.devCard = ""; cardRev++ }) { Text("Clear") }
            }
            if (card.terms > 0) {
                val how = when (card.terms) { 5 -> "A, B, C, D and E"; 3 -> "A, B and C (5 well spread headings are needed for D and E)"; else -> "the mean deviation A only" }
                Text(String.format(Locale.US, "Curve with %s · A %+.1f  B %+.1f  C %+.1f  D %+.1f  E %+.1f · residual %.1f°", how,
                    card.coef[0], card.coef[1], card.coef[2], card.coef[3], card.coef[4], card.rmsDeg), fontSize = 12.sp)
                Text((0 until 360 step 30).joinToString("   ") { String.format(Locale.US, "%03d° %+.1f", it, card.at(it.toDouble())) }, fontSize = 12.sp,
                    fontFamily = TechMono, modifier = Modifier.padding(top = 2.dp))
            } else Text("No observations yet: check the compass on headings spread round the circle (every 45°).", fontSize = 12.sp, color = Alp.Muted)
            card.points.forEachIndexed { i, pt ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(String.format(Locale.US, "%03.0f°   %+.1f° %s", pt.compassHdg, pt.dev, sign(pt.dev)), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { card.remove(i); settings.devCard = card.encode(); cardRev++ }) { Text("Remove") }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        item {
            Text("Current and course to steer", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            fun digits(v: String, n: Int, neg: Boolean = false) = v.filter { c -> c.isDigit() || c == '.' || c == ',' || (neg && c == '-') }.take(n)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(curCog, { curCog = digits(it, 5) }, label = { Text("Course made good, °") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedTextField(curStw, { curStw = digits(it, 4) }, label = { Text("Speed in water, kn") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = dec)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(curLw, { curLw = digits(it, 5, true) }, label = { Text("Leeway, ° (+ stbd)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                OutlinedTextField(curSet, { curSet = digits(it, 5) }, label = { Text("Current set, °") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedTextField(curDrift, { curDrift = digits(it, 4) }, label = { Text("Drift, kn") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = dec)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
                Button(onClick = { steer() }) { Text("Course to steer") }
                OutlinedButton(onClick = {
                    val me = Own.position()
                    if (me == null) curOut = "A position is needed to mark the departure: the tracker's GNSS."
                    else {
                        CurrentMark.lat = me.first; CurrentMark.lon = me.second; CurrentMark.timeMs = System.currentTimeMillis()
                        curOut = "Departure marked at " + pos(me.first, me.second) + " (" + utc(CurrentMark.timeMs) + " UTC). Steer a steady course and speed and press From DR after a while (an hour or more is best)."
                    }
                }) { Text("Mark departure") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(curRv, { curRv = digits(it, 5) }, label = { Text("True course steered, °") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedButton(onClick = { fromDr() }) { Text("From DR") }
            }
            Text(curOut, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        item {
            Text("Fix from two bearings", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            val txt = KeyboardOptions(keyboardType = KeyboardType.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(bm1Lat, { bm1Lat = it.take(16) }, label = { Text("Point 1 lat") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = txt)
                OutlinedTextField(bm1Lon, { bm1Lon = it.take(16) }, label = { Text("Point 1 lon") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = txt)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(bB1, { bB1 = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, label = { Text("Bearing 1, °") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedButton(onClick = { bT1 = System.currentTimeMillis() }) { Text("NOW") }
                Text(if (bT1 != 0L) utc(bT1) else "no time", fontSize = 12.sp, color = Alp.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(bm2Lat, { bm2Lat = it.take(16) }, label = { Text("Point 2 lat (empty = 1)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = txt)
                OutlinedTextField(bm2Lon, { bm2Lon = it.take(16) }, label = { Text("Point 2 lon") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = txt)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(bB2, { bB2 = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, label = { Text("Bearing 2, °") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedButton(onClick = { bT2 = System.currentTimeMillis() }) { Text("NOW") }
                Text(if (bT2 != 0L) utc(bT2) else "now", fontSize = 12.sp, color = Alp.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(bCourse, { bCourse = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, label = { Text("Course made good, °") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedTextField(bSpeed, { bSpeed = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(4) }, label = { Text("Speed, kn") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = dec)
                OutlinedTextField(bCt, { bCt = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }.take(5) }, label = { Text("Ct, ° (+E)") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = txt)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { bearingFix() }) { Text("Fix") }
                OutlinedButton(onClick = { bT1 = 0L; bT2 = 0L; bOut = "Times cleared." }) { Text("Clear times") }
            }
            Text(bOut, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        item {
            Text("Sun, Aries and stars are computed on the phone to almanac precision (0.1'), planets to 0.1' and the Moon to 0.3'. The phone clock must be right: one second is up to 0.25 nm. " +
                "Earlier lines are advanced by the change of the assumed position since they were taken.", fontSize = 11.sp, color = Alp.Muted, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
