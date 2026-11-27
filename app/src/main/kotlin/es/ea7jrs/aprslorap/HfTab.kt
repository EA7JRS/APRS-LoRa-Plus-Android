// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// HF & propagation (Operate): band conditions now, with the same assessment as EA7JRS/sat-aprs (HfProp.bandCards /
// calculateBandStatus). Two views: Summary (local/DX bands, the measured ionosphere, the sun and magnetic field, the L1 solar
// wind) and Band conditions (a card per band, 160 m to 6 m). The MUF is the measured one from the nearest ionosonde when there is
// a trustworthy reading, otherwise the estimate from the solar flux, the sun's elevation here and the X-rays; the geomagnetic
// level is Hp30 / Kp or, when worse, the L1 solar-wind estimate (DSCOVR / ACE). Tapping a band tunes the app's SDR to it.
package es.ea7jrs.aprslorap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Astro
import es.ea7jrs.aprslorap.core.HfProp
import es.ea7jrs.aprslorap.core.OfficialAlerts
import java.util.Locale

/** Centre frequency (Hz) to tune for each band id, used when a band card is tapped. */
private val BAND_TUNE = mapOf(
    "160m" to 1_840_000L, "80m" to 3_700_000L, "60m" to 5_360_000L, "40m" to 7_100_000L, "30m" to 10_130_000L,
    "20m" to 14_150_000L, "17m" to 18_130_000L, "15m" to 21_250_000L, "12m" to 24_950_000L, "11m" to 27_205_000L,
    "10m" to 28_500_000L, "8m" to 40_100_000L, "6m" to 50_150_000L)

private fun qColor(q: HfProp.Quality): Color = when (q) {
    HfProp.Quality.SLATE -> Alp.Muted; HfProp.Quality.AMBER -> Alp.Amber
    HfProp.Quality.EMERALD -> Alp.Green; HfProp.Quality.CYAN -> Alp.Cyan; HfProp.Quality.RED -> Alp.Red
}

@Composable
fun HfTab(onTune: (Long) -> Unit) {
    val ctx = LocalContext.current
    val settings = Settings(ctx)
    val w by AppState.spaceWx.collectAsState()
    val iono by AppState.ionosonde.collectAsState()
    var view by rememberSaveable { mutableStateOf(0) }           // 0 Summary, 1 Band conditions
    var cat by rememberSaveable { mutableStateOf("all") }
    LaunchedEffect(Unit) { OfficialAlertsFetcher.fetchNow(ctx, settings); HfFetcher.fetchNow(ctx, settings) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("HF & propagation", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Button(onClick = { OfficialAlertsFetcher.fetchNow(ctx, settings); HfFetcher.fetchNow(ctx, settings, force = true) }) { Text("Update") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("Summary", Modifier.weight(1f).height(40.dp), bg = if (view == 0) Alp.Cyan else Alp.Key) { view = 0 }
            RadioKey("Band conditions", Modifier.weight(1f).height(40.dp), bg = if (view == 1) Alp.Cyan else Alp.Key) { view = 1 }
        }

        val sfi = w?.sfi
        val pos = Own.position()
        if (w == null || sfi == null) {
            Text("No space weather yet. Tap Update to download it with the phone's internet.", fontSize = 13.sp, color = Alp.Muted)
            IonoSettings(settings)
            return@Column
        }
        val sw = w!!
        val nowMs = System.currentTimeMillis()
        val elev = pos?.let { Astro.sunPosition(nowMs, it.first, it.second).altDeg } ?: 0.0
        val est = HfProp.estimate(sfi, elev, HfProp.xrayFlux(sw.xray), sw.bz, sw.windKms)
        val measured = iono?.muf3000 != null
        val muf = iono?.muf3000 ?: est.muf                        // measured MUF when there is a trustworthy ionosonde
        val luf = est.luf
        val geo = OfficialAlerts.geomagNow(sw, nowMs / 1000)
        val kp = HfProp.effectiveKp(geo?.first ?: 0.0, sw.bz, sw.windKms).coerceAtMost(9.0)

        if (view == 0) SummaryView(sw, pos, elev, est, muf, luf, kp, measured, iono, geo?.second ?: "Kp")
        else BandConditionsView(sfi, muf, luf, kp, measured, iono, cat, { cat = it }, onTune)

        IonoSettings(settings)
    }
}

@Composable
private fun SummaryView(sw: es.ea7jrs.aprslorap.core.SpaceWx, pos: Pair<Double, Double>?, elev: Double,
                        est: HfProp.Estimate, muf: Double, luf: Double, kp: Double, measured: Boolean,
                        iono: HfProp.Ionosonde?, geoName: String) {
    // foF2: measured when there is an ionosonde, otherwise estimated from the MUF (MUF3000 ~ 3.1 x foF2)
    val fof2 = iono?.fof2 ?: (muf / 3.1)
    val dx = HfProp.Estimate(muf, luf)
    val nvis = HfProp.Estimate(fof2, luf)
    Section("Bands now")
    Text("Local / regional (NVIS) by foF2, long distance (3000 km) by MUF.", fontSize = 12.sp, color = Alp.Muted)
    for ((name, mhz) in HfProp.BANDS) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.width(56.dp), fontSize = 13.sp, color = Alp.Text)
            StateChip(HfProp.state(mhz, nvis, kp), Modifier.weight(1f))
            StateChip(HfProp.state(mhz, dx, kp), Modifier.weight(1f))
        }
    }
    val best = HfProp.bestDx(dx, kp)
    Text("MUF ${f1(muf)} · LUF ${f1(luf)} MHz" + (if (measured) " (measured)" else " (estimated)") +
        (best?.let { " · best DX $it" } ?: "") + (if (elev > 0) " · day" else " · night"), fontSize = 13.sp, color = Alp.Text)

    if (iono != null) {
        Section("Measured ionosphere")
        val ageMin = ((System.currentTimeMillis() - iono.whenMs) / 60000L).coerceAtLeast(0)
        Text("${iono.name} (${iono.code})" + (iono.distanceKm?.let { ", ${Math.round(it)} km" } ?: "") + " · $ageMin min ago", fontSize = 12.sp, color = Alp.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("foF2", "${f1(iono.fof2)} MHz")
            iono.muf3000?.let { Tile("MUF 3000", "${f1(it)} MHz") }
            iono.hmf2?.let { Tile("F2 height", "${Math.round(it)} km") }
        }
    }

    Section("Sun & magnetic field")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("Solar flux", String.format(Locale.US, "%.0f", sw.sfi))
        sw.ssn?.let { Tile("Sunspots", String.format(Locale.US, "%.0f", it)) }
        Tile(geoName, f1(kp))
        if (sw.xray.isNotEmpty()) Tile("X-rays", sw.xray)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        sw.windKms?.let { Tile("Solar wind", String.format(Locale.US, "%.0f km/s", it)) }
        sw.bz?.let { Tile("IMF Bz", String.format(Locale.US, "%+.1f nT", it)) }
    }

    // L1 solar wind (DSCOVR / ACE): lead time and effect
    if (sw.bz != null) {
        val lead = sw.windKms?.let { Math.round(1_500_000.0 / it / 60.0) }
        val storm = HfProp.windStorm(sw.bz, sw.windKms)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Alp.Panel).padding(10.dp)) {
            Text("L1 · solar wind (DSCOVR / ACE): Bz ${String.format(Locale.US, "%+.1f", sw.bz)} nT" +
                (sw.windKms?.let { ", ${Math.round(it)} km/s" } ?: "") +
                (lead?.let { " → ~$it min to Earth" } ?: "") +
                (if (storm > 0) " · disturbance level ${f1(storm)}" else "") +
                ". A southward Bz with a fast wind raises the LUF before the 3-hour Kp catches up.",
                fontSize = 12.sp, color = Alp.SubText)
        }
    }
    Text("Estimated from the solar flux, the sun's elevation here, X-rays, $geoName and the L1 solar wind" +
        (if (iono != null) ", with the measured foF2 / MUF of the nearest ionosonde." else "; no nearby ionosonde."), fontSize = 11.sp, color = Alp.Muted)
}

@Composable
private fun BandConditionsView(sfi: Double, muf: Double, luf: Double, kp: Double, measured: Boolean,
                               iono: HfProp.Ionosonde?, cat: String, setCat: (String) -> Unit, onTune: (Long) -> Unit) {
    Text("Dynamic estimate from the solar flux (SFU ${String.format(Locale.US, "%.0f", sfi)}) and the geomagnetic level (${f1(kp)}). " +
        "MUF ${f1(muf)} MHz" + (if (measured) " measured (${iono?.name})." else " estimated."), fontSize = 12.sp, color = Alp.Muted)
    val cats = listOf("all" to "All (13)", "low" to "Low", "medium" to "Medium", "high" to "High", "vhf" to "VHF")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, label) in cats) RadioKey(label, Modifier.height(36.dp), bg = if (cat == id) Alp.Cyan else Alp.Key) { setCat(id) }
    }
    val cards = HfProp.bandCards(sfi, muf, luf, kp)
    val best = HfProp.BANDS.lastOrNull { HfProp.state(it.second, HfProp.Estimate(muf, luf), kp) == HfProp.State.OPEN }?.first
    for (c in cards) {
        if (cat != "all" && c.category != cat) continue
        BandCardView(c, best == c.id, onTune)
    }
}

@Composable
private fun BandCardView(c: HfProp.BandCard, isBest: Boolean, onTune: (Long) -> Unit) {
    val col = qColor(c.quality)
    val tune = BAND_TUNE[c.id]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(col.copy(alpha = 0.10f))
        .let { if (tune != null) it.clickable { onTune(tune) } else it }
        .padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Alp.Text)
            if (isBest) Text("BEST", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Alp.Cyan,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Alp.Cyan.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp))
            Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(col))
        }
        Text(c.status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = col)
        Text(c.desc, fontSize = 11.sp, color = Alp.SubText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Suitability SFU (min ${c.sfiMin})", Modifier.weight(1f), fontSize = 10.sp, color = Alp.Muted)
            Text("${c.pct}%", fontSize = 10.sp, color = Alp.Muted)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Alp.Key)) {
            Box(Modifier.fillMaxWidth(c.pct / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Alp.Green))
        }
        Text(c.sfiLabel, fontSize = 9.sp, color = Alp.Muted)
    }
}

@Composable
private fun StateChip(st: HfProp.State, mod: Modifier) {
    val (txt, col) = when (st) {
        HfProp.State.OPEN -> "Open" to Alp.Green
        HfProp.State.FAIR -> "Fair" to Alp.Amber
        HfProp.State.POOR -> "Poor" to officialColor(2)
        HfProp.State.ABSORBED -> "Absorbed" to Alp.Muted
        HfProp.State.SHUT -> "Shut" to Alp.Muted
    }
    Box(mod.padding(end = 6.dp)) {
        Text(txt, fontSize = 12.sp, color = col, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(col.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Tile(k: String, v: String) {
    Column(Modifier.clip(RoundedCornerShape(9.dp)).background(Alp.Panel).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(k, fontSize = 10.sp, color = Alp.Muted)
        Text(v, fontSize = 16.sp, color = Alp.Text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IonoSettings(s: Settings) {
    var on by rememberSaveable { mutableStateOf(s.ionosondesOn) }
    val ctx = LocalContext.current
    Section("Ionosondes")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Measured foF2 / MUF (GIRO via KC2G)", Modifier.weight(1f), fontSize = 14.sp)
        androidx.compose.material3.Switch(on, { on = it; s.ionosondesOn = it; if (it) HfFetcher.fetchNow(ctx, s, force = true) else AppState.ionosonde.value = null })
    }
    Text("Data: GIRO network (Lowell GIRO Data Center), via the KC2G propagation service (prop.kc2g.com). " +
        "The phone downloads the station list and keeps the nearest trustworthy reading.", fontSize = 11.sp, color = Alp.Muted)
}

private fun f1(x: Double): String = String.format(Locale.US, "%.1f", x)
