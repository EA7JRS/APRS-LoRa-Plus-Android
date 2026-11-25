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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Geo
import es.ea7jrs.aprslorap.core.RotorProto
import java.util.Locale

/**
 * Balloons (HAB): the UKHAS telemetry sentences ($$CALL,...*CRC) the app decodes from RTTY with its RTL-SDR. Start an
 * RTTY modem in Modes on the balloon's frequency; the valid sentences land here with position, distance and bearing,
 * GO TO (Google Maps) and ROTOR. [onMap] opens the map.
 */
@Composable
fun HabTab(onMap: () -> Unit) {
    val ctx = LocalContext.current
    val list by HabHub.balloons.collectAsState()
    val me = remember(list.size) { NetWeatherFetcher.position(ctx) }
    var open by rememberSaveable { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("Balloons (${list.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (list.isNotEmpty()) OutlinedButton(onClick = { HabHub.clear() }) { Text("Clear") }
            }
            Text("High-altitude balloon telemetry (UKHAS \$\$CALL,...*CRC). Start an RTTY modem in Modes on the balloon's frequency and shift; valid sentences appear here. LoRa-ASCII HAB packets are parsed too. Horus binary (4FSK) is not decoded.",
                fontSize = 11.sp, color = Alp.Muted, modifier = Modifier.padding(vertical = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        items(list, key = { it.tlm.callsign }) { e ->
            val t = e.tlm
            val la = t.lat; val lo = t.lon                 // locales: evita el problema de smart-cast entre modulos (core vs app)
            val d = if (me != null && la != null && lo != null) Geo.distanceKm(me.first, me.second, la, lo) else null
            val brg = if (me != null && la != null && lo != null) Geo.bearingDeg(me.first, me.second, la, lo) else null
            Column(Modifier.fillMaxWidth().clickable { open = if (open == t.callsign) "" else t.callsign }.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.callsign, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(120.dp))
                    Text(t.altM?.let { String.format(Locale.US, "%,.0f m", it) } ?: "—", fontFamily = TechMono, fontSize = 13.sp, color = Alp.Green, modifier = Modifier.width(84.dp))
                    Text(if (d != null && brg != null) String.format(Locale.US, "%.0f km %03.0f°", d, brg) else (t.timeUtc ?: Fmt.ago(e.lastMs)),
                        fontFamily = TechMono, fontSize = 12.5.sp, color = if (d != null) Alp.Green else Alp.Muted, modifier = Modifier.weight(1f))
                }
                if (open == t.callsign) {
                    Text(listOfNotNull(
                        t.counter?.let { "msg #$it" },
                        t.timeUtc?.let { "$it UTC" },
                        if (la != null && lo != null) Fmt.latLon(la, true) + " " + Fmt.latLon(lo, false) else "no position",
                        "heard " + Fmt.ago(e.lastMs), "${e.count} msgs",
                        e.khz.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f kHz", it) }
                    ).joinToString(" · "), fontSize = 12.sp, color = Alp.Muted)
                    if (t.extra.isNotEmpty()) Text("fields: " + t.extra.joinToString(", "), fontFamily = TechMono, fontSize = 12.sp)
                    Text(t.raw, fontFamily = TechMono, fontSize = 11.5.sp, color = Alp.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (la != null && lo != null) OutlinedButton(onClick = { goToPos(ctx, la, lo, t.callsign) }) { Text("GO TO") }
                        if (brg != null) OutlinedButton(onClick = {
                            AppState.error.value = if (RotorLink.command(RotorProto.pos(brg, null))) String.format(Locale.US, "Rotor box → %03.0f°", brg) else "Rotor box not connected"
                        }) { Text("ROTOR") }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
