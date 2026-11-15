/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

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
import es.ea7jrs.aprslorap.core.Celnav
import java.util.Locale
import kotlin.math.abs

/** Sights kept while the app runs (they survive leaving the screen). */
object AstroSights {
    data class Lop(val sight: Celnav.Sight, val body: String, val timeMs: Long, val atLat: Double, val atLon: Double)
    val list = mutableStateOf(listOf<Lop>())
    const val MAX = 4
}

/**
 * Celestial navigation with a sextant, as Carta > Astro of the APRS LoRa+ plotter: the Sun (lower or upper limb) or one of the
 * 57 navigational stars and Polaris, the altitude corrections, the computed altitude, azimuth and intercept, and the fix from
 * two or more lines (earlier lines are advanced for the run since they were taken). Computed on the phone to almanac precision.
 */
@Composable
fun AstroTab() {
    var body by rememberSaveable { mutableStateOf(0) }            // 0 Sun lower limb, 1 Sun upper limb, 2 star
    var star by rememberSaveable { mutableStateOf(0) }
    var deg by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var index by rememberSaveable { mutableStateOf("0") }
    var eye by rememberSaveable { mutableStateOf("2") }
    var timeMs by rememberSaveable { mutableStateOf(0L) }
    var hms by rememberSaveable { mutableStateOf("") }
    var out by rememberSaveable { mutableStateOf("Measure the altitude with the sextant, press NOW at the instant of the sight and type degrees and minutes.") }
    var fixText by rememberSaveable { mutableStateOf("") }
    val lops by AstroSights.list

    fun num(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    fun dm(v: Double): String { val a = abs(v); val d = a.toInt(); return String.format(Locale.US, "%d° %04.1f'", d, (a - d) * 60) }
    fun pos(lat: Double, lon: Double) = dm(lat) + (if (lat >= 0) " N  " else " S  ") + dm(lon) + (if (lon >= 0) " E" else " W")
    fun utc(ms: Long): String { val s = (ms / 1000) % 86400; return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
    fun ghaDec(jd: Double): Celnav.GhaDec =
        if (body < 2) Celnav.sunGhaDec(jd)
        else { val p = Celnav.starShaDec(Celnav.STARS[star], jd); Celnav.GhaDec((Celnav.ariesGha(jd) + p.sha) % 360.0, p.dec) }
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
        val g = ghaDec(jd)
        val sun = body < 2
        val ho = Celnav.observedAltitude(hs, num(index), num(eye),
            if (!sun) Celnav.Limb.CENTER else if (body == 0) Celnav.Limb.LOWER else Celnav.Limb.UPPER,
            if (sun) Celnav.sunSemiDiameterArcmin(jd) else 0.0, if (sun) 0.15 else 0.0)
        val s = Celnav.reduceSight(me.first, me.second, g.gha, g.dec, ho)
        if (s.hc < 0 || abs(s.interceptNm) > 120) {
            out = String.format(Locale.US, "It does not fit: at that time the body would be at %.0f° and the sight gives %.0f° (%.0f nm apart). Check the body, the UTC time and the altitude.", s.hc, ho, abs(s.interceptNm))
            return
        }
        val name = if (sun) "Sun" else Celnav.STARS[star].name
        val list = (AstroSights.list.value + AstroSights.Lop(s, name, t, me.first, me.second)).takeLast(AstroSights.MAX)
        AstroSights.list.value = list
        out = String.format(Locale.US, "%s %s UTC · observed %s · computed %s · azimuth %03.0f°\nIntercept: %.1f nm %s.",
            name, utc(t), dm(ho), dm(s.hc), s.zn, abs(s.interceptNm), if (s.interceptNm >= 0) "towards the body" else "away from the body")
        fix(me, list)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("Line of position (sextant)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            val me = Own.position()
            Text(if (me != null) "Assumed position: " + pos(me.first, me.second) else "No position: tracker GNSS without a fix and no locator in Spotting",
                fontSize = 12.sp, color = if (me != null) Alp.Muted else Alp.Amber)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { body = (body + 1) % 3 }) { Text(when (body) { 0 -> "Sun (lower limb)"; 1 -> "Sun (upper limb)"; else -> "Star" }) }
                OutlinedButton(onClick = { body = 2; star = (star + Celnav.STARS.size - 1) % Celnav.STARS.size }) { Text("<") }
                OutlinedButton(onClick = { body = 2; star = (star + 1) % Celnav.STARS.size }) { Text(">") }
            }
            if (body == 2) {
                val st = Celnav.STARS[star]
                val hint = me?.let {
                    val jd = Celnav.julianDay(System.currentTimeMillis()); val p = Celnav.starShaDec(st, jd)
                    val aa = Celnav.bodyAltAz((Celnav.ariesGha(jd) + p.sha) % 360.0, p.dec, it.first, it.second)
                    if (aa.alt > 0) String.format(Locale.US, " · now: altitude %.0f°, azimuth %03.0f°", aa.alt, aa.az) else " · now below the horizon"
                } ?: ""
                Text(String.format(Locale.US, "%s (mag %.1f)%s", st.name, st.mag, hint), fontSize = 13.sp, color = Alp.Muted)
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
            Text("Sun, Aries and stars are computed on the phone to almanac precision (0.1'). No Moon or planets. The phone clock must be right: one second is up to 0.25 nm. " +
                "Earlier lines are advanced by the change of the assumed position since they were taken.", fontSize = 11.sp, color = Alp.Muted, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
