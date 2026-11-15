/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Navaids
import kotlinx.coroutines.delay
import java.util.Locale

/** The VOR list (OurAirports), loaded once from the assets. */
object VorList {
    @Volatile private var cache: List<Navaids.Vor>? = null
    fun get(ctx: android.content.Context): List<Navaids.Vor> = cache ?: try {
        ctx.assets.open("navaids/vor.csv").bufferedReader().use { Navaids.parse(it.readText()) }
    } catch (_: Exception) { emptyList() }.also { cache = it }
}

/**
 * SDR > VOR: the stations within reach (OurAirports) to aim the rotor and to calibrate the receiver. TUNE puts the SDR on the VOR in
 * AM; with the app's own RTL-SDR, MEASURE finds the carrier for 10 s and gives the error in ppm, and APPLY sets the correction.
 */
@Composable
fun VorSection(rtl: Boolean) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val all = remember { VorList.get(ctx) }
    var measuring by remember { mutableStateOf<Navaids.Vor?>(null) }
    var result by remember { mutableStateOf("") }
    var suggest by remember { mutableStateOf<Int?>(null) }
    val meter = remember { Navaids.PpmMeter() }
    val me = Own.position()
    val near = remember(me?.first?.let { Math.round(it * 100) }, me?.second?.let { Math.round(it * 100) }) {
        if (me == null) emptyList() else Navaids.nearest(all, me.first, me.second, AppState.gnss.value?.altitudeM ?: 0.0)
    }

    LaunchedEffect(measuring) {
        val v = measuring ?: return@LaunchedEffect
        val link = SdrControl.rtl() ?: run { result = "The app's RTL-SDR receiver is not connected."; measuring = null; return@LaunchedEffect }
        synchronized(meter) { meter.reset() }
        link.baseband("vor") { re, im, n -> synchronized(meter) { meter.feed(re, im, n) } }
        delay(10_000)
        link.baseband("vor", null)
        val r = synchronized(meter) { meter.result() }
        if (r == null || r.snrDb < 10) {
            result = String.format(Locale.US, "%s: no clear carrier (%s). Check the antenna, the gain and that the VOR is in range.", v.ident, r?.let { "%.0f dB".format(Locale.US, it.snrDb) } ?: "no data")
            suggest = null
        } else {
            val residual = Navaids.ppm(r.offsetHz, v.hz.toDouble())
            val now = settings.sdrPpm
            val next = (now + Math.round(residual)).toInt().coerceIn(-200, 200)
            result = String.format(Locale.US, "%s %.2f MHz: carrier %+.0f Hz off (%.0f dB over the noise, %d frames) = %+.1f ppm residual. Correction now %d, suggested %d.",
                v.ident, v.khz / 1000.0, r.offsetHz, r.snrDb, r.frames, residual, now, next)
            suggest = next
        }
        measuring = null
    }
    DisposableEffect(Unit) { onDispose { SdrControl.rtl()?.baseband("vor", null) } }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("VOR: aim the rotor, calibrate the SDR")
        Text("VOR beacons (108–118 MHz) transmit all the time from a published position. TUNE puts the SDR on one in AM. With the app's RTL-SDR, " +
            "MEASURE finds its carrier for 10 s and works out the error in ppm.", fontSize = 11.sp)
        if (me == null) Text("A position is needed (tracker GNSS or your locator in Spotting).", fontSize = 12.sp, color = Alp.Muted)
        else if (near.isEmpty()) Text("No VOR within 400 km.", fontSize = 12.sp, color = Alp.Muted)
        for (n in near.take(12)) {
            val v = n.vor
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(String.format(Locale.US, "%s  %.2f MHz  %s", v.ident, v.khz / 1000.0, v.name), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (n.inRange) Alp.Text else Alp.Muted)
                Text(String.format(Locale.US, "%s · %s · elevation %.1f° · %s", Fmt.km(n.km), Fmt.deg(n.bearing), n.elevationDeg,
                    if (n.inRange) "in radio range" else "beyond the radio horizon"), fontSize = 11.sp, color = Alp.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { SdrControl.mode("AM", 0); SdrControl.tune(v.hz) }) { Text("TUNE") }
                    OutlinedButton(onClick = { aimRotorAt(v.lat, v.lon, "VOR ${v.ident}") }) { Text("ROTOR") }
                    if (rtl) Button(enabled = measuring == null, onClick = {
                        SdrControl.mode("AM", 0); SdrControl.tune(v.hz); result = "Measuring ${v.ident}…"; suggest = null; measuring = v
                    }) { Text(if (measuring == v) "…" else "MEASURE") }
                }
            }
            HorizontalDivider()
        }
        if (result.isNotEmpty()) Text(result, fontSize = 12.sp)
        suggest?.let { s ->
            Button(onClick = { settings.sdrPpm = s; SdrControl.rtl()?.setPpm(s); result += " Applied: $s ppm (measure again to check)."; suggest = null }) { Text("APPLY $s ppm") }
        }
        Text("List: OurAirports (public domain). Some entries lag behind the AIP (frequency or position): check the national AIP for yours.",
            fontSize = 10.sp, color = Alp.Muted)
    }
}
