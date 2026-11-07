/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Spots
import java.util.Locale

/** Modes > Spotting: PSK Reporter, WSPRnet, WSJT-X UDP and the CW skimmer port. */
@Composable
internal fun SpottingSection() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    val st by AppState.spotter.collectAsState()
    var psk by remember { mutableStateOf(s.spotPsk) }
    var wsprnet by remember { mutableStateOf(s.spotWsprnet) }
    var udp by remember { mutableStateOf(s.spotUdp) }
    var udpHost by remember { mutableStateOf(s.spotUdpHost) }
    var udpPort by remember { mutableStateOf(s.spotUdpPort.toString()) }
    var telnet by remember { mutableStateOf(s.spotTelnet) }
    var telnetPort by remember { mutableStateOf(s.spotTelnetPort.toString()) }
    var loc by remember { mutableStateOf(s.spotLocator) }
    var antenna by remember { mutableStateOf(s.spotAntenna) }
    var dial by remember { mutableStateOf(if (s.spotDialHz > 0) String.format(Locale.US, "%.6f", s.spotDialHz / 1e6) else "") }

    Section("Spotting (PSK Reporter, WSPRnet, WSJT-X, RBN)")
    Text("What the app decodes goes out with your callsign ${Spots.stationCall(s.callsign)} and locator ${st.locator.ifEmpty { "(none yet)" }}: " +
        "FT8, FT4 and CW to PSK Reporter; WSPR and FST4W to WSPRnet. CW comes from the decoder above and from the tracker's CW listening (Tracker tab).",
        fontSize = 11.sp)
    if (!Licence.isValid(s)) Text("PSK Reporter and WSPRnet need the callsign validated (Connect tab).", fontSize = 11.sp)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("PSK Reporter", fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(psk, { psk = it; s.spotPsk = it; Spotter.reconfigure() })
    }
    Text(st.psk, fontSize = 11.sp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("WSPRnet", fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(wsprnet, { wsprnet = it; s.spotWsprnet = it; Spotter.reconfigure() })
    }
    Text(st.wsprnet, fontSize = 11.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(loc, { loc = it.trim().take(8) }, label = { Text("Locator (empty = GNSS)") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(antenna, { antenna = it.take(60) }, label = { Text("Antenna") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(dial, { dial = it.filter { c -> c.isDigit() || c == '.' }.take(12) }, label = { Text("Radio dial MHz (microphone)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            s.spotLocator = if (Spots.isGrid(loc)) loc.take(2).uppercase() + loc.drop(2).take(2) + loc.drop(4).lowercase() else ""
            loc = s.spotLocator
            s.spotAntenna = antenna
            s.spotDialHz = dial.toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: 0L
            Spotter.reconfigure()
        }) { Text("Save") }
    }
    Text("With the RTL-SDR receiver the frequency is the receiver's; with the microphone, the dial of the radio (USB) written here.", fontSize = 11.sp)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("WSJT-X UDP (GridTracker, JTAlert, RBN Aggregator)", fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(udp, { udp = it; s.spotUdp = it; s.spotUdpHost = udpHost; s.spotUdpPort = udpPort.toIntOrNull() ?: 2237; Spotter.reconfigure() })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(udpHost, { udpHost = it.trim(); s.spotUdpHost = udpHost }, label = { Text("Computer IP") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(udpPort, { udpPort = it.filter(Char::isDigit).take(5); udpPort.toIntOrNull()?.let { p -> s.spotUdpPort = p } }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp))
    }
    Text(st.udp, fontSize = 11.sp)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("CW skimmer port (telnet, RBN Aggregator)", fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(telnetPort, { telnetPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp))
        Switch(telnet, { telnet = it; s.spotTelnet = it; s.spotTelnetPort = telnetPort.toIntOrNull()?.coerceIn(1024, 65535) ?: 7300; Spotter.reconfigure() })
    }
    Text(st.telnet, fontSize = 11.sp)
    if (st.lastCw.isNotEmpty()) Text("Last CW: ${st.lastCw}", fontSize = 11.sp)
    Text("The RBN takes spots only through RBN Aggregator (Windows) on a computer: FT8 / FT4 through the WSJT-X UDP above, CW through the " +
        "skimmer port (as from a CW Skimmer Server): Aggregator connects to this phone's IP and that port; the UDP goes from this phone to the computer.", fontSize = 11.sp)
}
