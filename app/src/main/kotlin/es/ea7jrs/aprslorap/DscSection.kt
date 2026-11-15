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
import es.ea7jrs.aprslorap.core.Dsc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Modes > DSC: channel 70 or an MF / HF DSC channel, TUNE, the alarm, and the calls received (position to GO TO / ROTOR). */
@Composable
fun DscSection(vhf: Boolean) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val calls by DscHub.calls.collectAsState()
    var khz by remember { mutableStateOf(settings.dscKhz.also { Settings.dscKhzNow = it }) }
    var alarm by remember { mutableStateOf(settings.dscAlarm) }
    var onlyDistress by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    val day = remember { SimpleDateFormat("dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    fun tune(hz: Long, mode: String) {
        val done = ArrayList<String>()
        if (AppState.sdr.value.connected) { SdrControl.mode(mode, if (mode == "NFM") 12_500 else 0); SdrControl.tune(hz); done.add("SDR") }
        done.addAll(Rig.tune(hz, mode))
        info = if (done.isEmpty()) "No SDR or CAT radio linked: tune the receiver by hand." else "Tuned: " + done.joinToString(", ")
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("DSC")
        if (vhf) {
            Text("Channel 70 (156.525 MHz) in FM; the receiver's FM audio (App receiver or the microphone). Both tones, 1300 and 2100 Hz, must pass the audio filter.", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { tune(Dsc.CH70_HZ, "NFM") }) { Text("TUNE CH 70") }
            }
        } else {
            Text("MF / HF DSC: radio in USB 1.7 kHz below the channel; the tones sit at 1615 and 1785 Hz (tap the waterfall to move them).", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (f in Dsc.HF_KHZ) Chip(if (f % 1.0 == 0.0) f.toInt().toString() else f.toString(), khz == f, height = 30.dp) { khz = f; settings.dscKhz = f }
            }
            OutlinedButton(onClick = { tune(Math.round(khz * 1000) - 1700, "USB") }) { Text(String.format(Locale.US, "TUNE %.1f kHz USB", khz - 1.7)) }
        }
        if (info.isNotEmpty()) Text(info, fontSize = 11.sp, color = Alp.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alarm on distress (alert, relay, acknowledgement)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(alarm, { alarm = it; settings.dscAlarm = it })
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Calls (${calls.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Chip("DISTRESS", onlyDistress, height = 30.dp) { onlyDistress = !onlyDistress }
            if (calls.isNotEmpty()) TextButton(onClick = { DscHub.clear() }) { Text("Clear") }
        }
        val shown = calls.filter { !onlyDistress || it.call.distress }
        if (shown.isEmpty()) Text("None yet.", fontSize = 12.sp, color = Alp.Muted)
        for (e in shown.take(60)) {
            val c = e.call
            val key = c.key + c.timeMs
            val isOpen = open == key
            Column(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else key }.padding(vertical = 4.dp)) {
                Text(String.format(Locale.US, "%s  %s  %s%s", day.format(Date(c.timeMs)), Dsc.formatName(c.format),
                    if (c.format == Dsc.FMT_DISTRESS) "" else Dsc.categoryName(c.category), if (e.repeats > 1) "  ×${e.repeats}" else ""),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = when (c.category) { Dsc.CAT_DISTRESS -> Alp.Red; Dsc.CAT_URGENCY -> Alp.Amber; else -> Alp.Text })
                Text("from ${c.selfId} (${Dsc.mmsiKind(c.selfId)})" + (c.address?.let { " to $it" } ?: "") + (c.distressShip?.takeIf { it != c.selfId }?.let { " · ship in distress $it" } ?: ""),
                    fontSize = 12.sp, fontFamily = TechMono)
                Text(c.summary().substringAfter(" · from ${c.selfId}").removePrefix(" · ").ifBlank { "—" }, fontSize = 12.sp, color = Alp.Muted)
                val la = c.lat; val lo = c.lon
                if (isOpen) {
                    Text("Symbols: " + c.symbols.joinToString(" ") + (if (c.errors > 0) " · ${c.errors} copies repaired" else ""), fontSize = 10.sp, fontFamily = TechMono, color = Alp.Muted)
                    if (la != null && lo != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val d = Own.to(la, lo)
                        OutlinedButton(onClick = { goToPos(ctx, la, lo, "DSC ${c.distressShip ?: c.selfId}") }) { Text("GO TO") }
                        OutlinedButton(onClick = { aimRotorAt(la, lo, "DSC ${c.distressShip ?: c.selfId}") }) { Text("ROTOR") }
                        d?.let { Text(Fmt.km(it.first) + " · " + Fmt.deg(it.second), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically)) }
                    }
                }
            }
            HorizontalDivider()
        }
        Text("Each call also goes out of the NMEA server as \$CDDSC, as a DSC radio sends it (plotter, OpenCPN). Listening only: the app never " +
            "transmits DSC, and it is no substitute for the DSC radio on board.", fontSize = 10.sp, color = Alp.Muted)
    }
}
