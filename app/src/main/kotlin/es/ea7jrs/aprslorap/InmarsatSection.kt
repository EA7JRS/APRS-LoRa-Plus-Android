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
import androidx.compose.material3.OutlinedTextField
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

/**
 * SDR > Inmarsat-C / STD-C (EGC / SafetyNET): TUNE the NCS TDM channel, LISTEN with the app's RTL-SDR, and the EGC messages
 * received (MSI / SAR broadcasts). L-band, so it needs a 1.5 GHz patch antenna and an LNA. The frame layout is a working
 * model pending a real recording; the DSP chain is tested.
 */
@Composable
fun InmarsatSection(rtl: Boolean) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val list by InmarsatHub.messages.collectAsState()
    val st by InmarsatHub.status.collectAsState()
    var mhz by remember { mutableStateOf(settings.stdcMhz) }
    var info by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(-1) }
    LaunchedEffect(rtl) { while (true) { InmarsatHub.ensure(); kotlinx.coroutines.delay(2_000) } }

    fun tuneHz() = ((mhz.toDoubleOrNull() ?: 1537.70) * 1e6).toLong()

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("Inmarsat-C / STD-C (EGC · SafetyNET)")
        Text("EGC broadcasts of the Inmarsat-C NCS TDM: SafetyNET (NAVAREA / MET warnings, SAR) and FleetNET. BPSK 1200 sym/s. " +
            "L-band (~1.5 GHz): needs a patch antenna and an LNA. TUNE the NCS channel, then LISTEN with the app's RTL-SDR.", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(mhz, { mhz = it.filter { c -> c.isDigit() || c == '.' }.take(9); settings.stdcMhz = mhz },
                label = { Text("NCS TDM MHz") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { SdrControl.mode("NFM", 20_000); SdrControl.tune(tuneHz()); info = "Tuned to $mhz MHz." }) { Text("TUNE") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!st.listening) Button(enabled = rtl, onClick = {
                SdrControl.mode("NFM", 20_000); SdrControl.tune(tuneHz())
                info = if (InmarsatHub.start()) "Listening." else "The app's RTL-SDR receiver is not connected."
            }) { Text("LISTEN") }
            else Button(onClick = { InmarsatHub.stop(); info = "" }) { Text("STOP") }
        }
        if (!rtl) Text("LISTEN needs the app's own RTL-SDR receiver (type rtl_tcp above).", fontSize = 11.sp, color = Alp.Muted)
        if (st.listening) Text("Frames ${st.frames} · messages ${st.messages}", fontSize = 12.sp, fontFamily = TechMono)
        if (info.isNotEmpty()) Text(info, fontSize = 11.sp, color = Alp.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("EGC messages (${list.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) TextButton(onClick = { InmarsatHub.clear() }) { Text("Clear") }
        }
        if (list.isEmpty()) Text("None yet.", fontSize = 12.sp, color = Alp.Muted)
        for ((i, m) in list.take(40).withIndex()) {
            val isOpen = open == i
            Column(Modifier.fillMaxWidth().clickable { open = if (isOpen) -1 else i }.padding(vertical = 4.dp)) {
                Text(String.format(java.util.Locale.US, "EGC #%04X · priority %d · service %d", m.id, m.priority, m.service),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(if (isOpen) m.text else m.text.take(80) + (if (m.text.length > 80) "…" else ""), fontSize = 12.sp, fontFamily = TechMono)
            }
            HorizontalDivider()
        }
        Text("Receiving only. The frame / EGC layout is a working model (scytale-C / JAERO behaviour) and needs a real STD-C " +
            "recording to confirm; the demodulator, Viterbi and descrambler are tested.", fontSize = 10.sp, color = Alp.Muted)
    }
}
