/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import es.ea7jrs.aprslorap.core.Navtex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Modes > NAVTEX: frequency, the stations within reach with their next broadcast, and the messages received (positions to the map / GO TO). */
@Composable
fun NavtexSection() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val msgs by NavtexHub.messages.collectAsState()
    var khz by remember { mutableStateOf(settings.navtexKhz) }
    var alarm by remember { mutableStateOf(settings.navtexAlarm) }
    var iec by remember { mutableStateOf(settings.navtexNrxIec) }
    var note by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf<Char?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val now = rememberNow(30_000)
    val utc = remember { SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    val day = remember { SimpleDateFormat("dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("NAVTEX")
        Text("Radio in USB 1 kHz below the channel (517.0 kHz for 518, 489.0 for 490, 4208.5 for 4209.5): the two tones sit at 915 and 1085 Hz. " +
            "An RTL-SDR needs direct sampling or an upconverter for 518 kHz.", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (f in listOf(518.0, 490.0, 4209.5)) Chip(if (f == 4209.5) "4209.5" else f.toInt().toString(), khz == f) { khz = f; settings.navtexKhz = f }
            Text("kHz", fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alarm on search and rescue (D)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(alarm, { alarm = it; settings.navtexAlarm = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NRX to the plotter in the IEC 61097-6 form (off: Furuno form)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(iec, { iec = it; settings.navtexNrxIec = it })
        }
        // a search and rescue message waits to be acknowledged: only by hand
        val pending = msgs.filter { it.alarm }
        if (pending.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("ALARM · search and rescue not acknowledged: " + pending.joinToString(", ") { it.msg.id }, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = Alp.Red, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { NavtexHub.acknowledge() }) { Text("ACKNOWLEDGE") }
        }

        // stations within reach and their next slots (letter × 10 min, every 4 hours)
        val near = NavtexHub.nearby(khz)
        if (near.isEmpty()) Text(if (Own.position() == null) "Stations: a position is needed (tracker GNSS or your locator in Spotting)." else "No station on $khz kHz within 900 km.", fontSize = 12.sp, color = Alp.Muted)
        else {
            Text("Stations within reach (next broadcast, UTC)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            for ((s, km) in near.take(8)) {
                val next = Navtex.nextSlotMs(s.id, now)
                val onAir = next != null && next <= now
                Text(String.format(Locale.US, "%c  %s · %s · %s", s.id, s.name, Fmt.km(km), when {
                    next == null -> "—"; onAir -> "ON AIR now"; else -> utc.format(Date(next))
                }), fontSize = 12.sp, color = if (onAir) Alp.Green else Alp.Text)
            }
        }

        // messages
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Messages (${msgs.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (msgs.isNotEmpty()) TextButton(onClick = {
                try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, NavtexHub.export()), "NAVTEX")) }
                catch (_: Exception) { note = "No app to share the text with." }
            }) { Text("Share") }
            if (msgs.isNotEmpty()) TextButton(onClick = { NavtexHub.clear() }) { Text("Clear") }
        }
        if (note.isNotEmpty()) Text(note, fontSize = 11.sp, color = Alp.Amber)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("ALL", subject == null, height = 30.dp) { subject = null }
            for (c in listOf('A', 'B', 'D', 'E', 'L')) Chip(c.toString(), subject == c, height = 30.dp) { subject = if (subject == c) null else c }
        }
        val shown = msgs.filter { subject == null || it.msg.subject == subject }
        if (shown.isEmpty()) Text("None yet.", fontSize = 12.sp, color = Alp.Muted)
        for (e in shown.take(60)) {
            val key = e.msg.id + e.msg.timeMs
            val isOpen = open == key
            Column(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else key }.padding(vertical = 4.dp)) {
                Text(String.format(Locale.US, "%s  %s  %s", if (e.msg.lostHeader) "????" else e.msg.id, day.format(Date(e.msg.timeMs)), e.stationName),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (e.msg.urgent) Alp.Red else Alp.Text)
                Text(e.msg.subjectName + (if (e.kept) " · kept" else "") + (if (e.msg.lostTrailer) " · incomplete" else if (!e.msg.lostHeader && !e.msg.satisfactory) " · with errors" else "") +
                    (if (e.positions.isNotEmpty()) " · ${e.positions.size} position(s)" else ""), fontSize = 11.sp, color = Alp.Muted)
                Text(if (isOpen) e.msg.text else e.msg.text.lineSequence().take(2).joinToString(" "), fontSize = 12.sp, fontFamily = TechMono)
                if (isOpen) {
                    val facts = ArrayList<String>()
                    if (e.msg.bad > 0) facts.add(String.format(Locale.US, "%d character(s) not received (the asterisks): %.1f %%", e.msg.bad, e.msg.errorRate))
                    if (!e.msg.lostHeader && !e.msg.lostTrailer && !e.msg.satisfactory) facts.add("4 % of errors or more: a better repeat will replace this copy")
                    if (e.heardMs - e.msg.timeMs > 600_000L) facts.add("still being broadcast, last heard " + day.format(Date(e.heardMs)))
                    if (e.inSlot == false) facts.add("outside the time slot of letter ${e.msg.station}: maybe a distant station")
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 11.sp, color = Alp.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { note = if (NavtexHub.keep(e, !e.kept)) "" else "${NavtexHub.MAX_KEPT} messages are kept already: stop keeping one."; open = null }) {
                            Text(if (e.kept) "STOP KEEPING" else "KEEP")
                        }
                        OutlinedButton(onClick = { NavtexHub.delete(e); open = null }) { Text("DELETE") }
                    }
                }
                if (isOpen && e.positions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val p = e.positions.first()
                    val d = Own.to(p.first, p.second)
                    OutlinedButton(onClick = { goToPos(ctx, p.first, p.second, "NAVTEX ${e.msg.id}") }) { Text("GO TO") }
                    OutlinedButton(onClick = { aimRotorAt(p.first, p.second, "NAVTEX ${e.msg.id}") }) { Text("ROTOR") }
                    d?.let { Text(Fmt.km(it.first) + " · " + Fmt.deg(it.second), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)) }
                }
            }
            HorizontalDivider()
        }
        Text("Station letters and positions: fldigi's NAVTEX station list (may be out of date). NAVTEX is no substitute for the official receiver on board. " +
            "Rules of IMO MSC.508(105) and the NAVTEX Manual: a repeat is not shown again for 72 hours, and a deleted message is remembered that long.",
            fontSize = 10.sp, color = Alp.Muted)
    }
}
