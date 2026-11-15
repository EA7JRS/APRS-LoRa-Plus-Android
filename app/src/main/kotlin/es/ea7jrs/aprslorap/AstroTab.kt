/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

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
import es.ea7jrs.aprslorap.core.Deviation
import es.ea7jrs.aprslorap.core.Wmm
import java.util.Locale
import kotlin.math.abs

/** Sights kept while the app runs (they survive leaving the screen). */
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

    fun sign(v: Double) = if (v >= 0) "E" else "W"
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { body = (body + 1) % 9 }) { Text(bodyNames[body]) }
                OutlinedButton(onClick = { body = 8; star = (star + Celnav.STARS.size - 1) % Celnav.STARS.size }) { Text("<") }
                OutlinedButton(onClick = { body = 8; star = (star + 1) % Celnav.STARS.size }) { Text(">") }
                OutlinedButton(onClick = { showSky = !showSky }) { Text(if (showSky) "Hide" else "Visible") }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(hms, { hms = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("UTC hhmmss") }, singleLine = true, modifier = Modifier.width(150.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton(onClick = { now() }) { Text("NOW") }
                Button(onClick = { compute() }) { Text("Compute") }
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
            Text("Sun, Aries and stars are computed on the phone to almanac precision (0.1'), planets to 0.1' and the Moon to 0.3'. The phone clock must be right: one second is up to 0.25 nm. " +
                "Earlier lines are advanced by the change of the assumed position since they were taken.", fontSize = 11.sp, color = Alp.Muted, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
