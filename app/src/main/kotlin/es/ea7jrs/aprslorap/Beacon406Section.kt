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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import es.ea7jrs.aprslorap.core.Beacon406
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** SDR > 406 MHz beacons: TUNE, LISTEN with the app's RTL-SDR, the alarm, and the beacons decoded (position to GO TO / ROTOR). */
@Composable
fun Beacon406Section(rtl: Boolean) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val list by Beacon406Hub.beacons.collectAsState()
    val st by Beacon406Hub.status.collectAsState()
    var alarm by remember { mutableStateOf(settings.beacon406Alarm) }
    var open by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    val day = remember { SimpleDateFormat("dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
    LaunchedEffect(rtl) { while (true) { Beacon406Hub.ensure(); delay(2_000) } }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("406 MHz distress beacons")
        Text("Cospas-Sarsat EPIRB, ELT and PLB (first generation: 400 bit/s, a burst every 50 s). TUNE puts the SDR on 406.031 MHz with 30 kHz " +
            "of band, which covers every channel; LISTEN decodes with the app's RTL-SDR (correct its ppm with a VOR first).", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { SdrControl.mode("NFM", 30_000); SdrControl.tune(Beacon406.TUNE_HZ); info = "Tuned to 406.031 MHz, NFM 30 kHz." }) { Text("TUNE") }
            if (!st.listening) Button(enabled = rtl, onClick = {
                SdrControl.mode("NFM", 30_000); SdrControl.tune(Beacon406.TUNE_HZ)
                info = if (Beacon406Hub.start()) "Listening." else "The app's RTL-SDR receiver is not connected."
            }) { Text("LISTEN") }
            else Button(onClick = { Beacon406Hub.stop(); info = "" }) { Text("STOP") }
        }
        if (!rtl) Text("LISTEN needs the app's own RTL-SDR receiver (type rtl_tcp above).", fontSize = 11.sp, color = Alp.Muted)
        if (st.listening) Text(String.format(Locale.US, "Bursts %d · decoded %d%s", st.bursts, st.decoded,
            if (st.bursts > 0) String.format(Locale.US, " · last carrier %.3f MHz, %.0f dB", (Beacon406.TUNE_HZ + st.lastOffsetHz) / 1e6, st.lastSnrDb) else ""),
            fontSize = 12.sp, fontFamily = TechMono)
        if (info.isNotEmpty()) Text(info, fontSize = 11.sp, color = Alp.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alarm on a real alert (not self-tests)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(alarm, { alarm = it; settings.beacon406Alarm = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Beacons (${list.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) TextButton(onClick = { Beacon406Hub.clear() }) { Text("Clear") }
        }
        if (list.isEmpty()) Text("None yet.", fontSize = 12.sp, color = Alp.Muted)
        for (e in list.take(40)) {
            val m = e.msg
            val isOpen = open == m.hexId
            Column(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else m.hexId }.padding(vertical = 4.dp)) {
                Text(String.format(Locale.US, "%s  %s %s%s", day.format(Date(m.timeMs)), m.beaconType.ifEmpty { "Beacon" }, m.hexId, if (e.bursts > 1) "  ×${e.bursts}" else ""),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (m.distress) Alp.Red else Alp.Text)
                Text(listOfNotNull(if (m.selfTest) "SELF-TEST" else if (m.test) "TEST" else null, m.protocolName, "country ${m.country}", m.id.takeIf { it.isNotBlank() })
                    .joinToString(" · "), fontSize = 12.sp, color = Alp.Muted)
                val la = m.lat; val lo = m.lon
                Text(if (la != null && lo != null) Fmt.latLon(la, true) + "  " + Fmt.latLon(lo, false) + (m.positionSource?.let { " ($it GNSS)" } ?: "") else "No position in the message",
                    fontSize = 12.sp, fontFamily = TechMono)
                if (isOpen) {
                    Text("Message " + m.hex + String.format(Locale.US, " · carrier %.3f MHz · %.0f dB", (Beacon406.TUNE_HZ + m.offsetHz) / 1e6, m.snrDb),
                        fontSize = 10.sp, fontFamily = TechMono, color = Alp.Muted)
                    if (la != null && lo != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val d = Own.to(la, lo)
                        OutlinedButton(onClick = { goToPos(ctx, la, lo, "406 ${m.hexId}") }) { Text("GO TO") }
                        OutlinedButton(onClick = { aimRotorAt(la, lo, "406 ${m.hexId}") }) { Text("ROTOR") }
                        d?.let { Text(Fmt.km(it.first) + " · " + Fmt.deg(it.second), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)) }
                    }
                }
            }
            HorizontalDivider()
        }
        Text("Listening only: never transmit on 406 MHz (even a test). Second-generation beacons (spread spectrum) are not decoded. " +
            "A real alert also reaches the rescue services by satellite; this is no substitute for them.", fontSize = 10.sp, color = Alp.Muted)
    }
}
