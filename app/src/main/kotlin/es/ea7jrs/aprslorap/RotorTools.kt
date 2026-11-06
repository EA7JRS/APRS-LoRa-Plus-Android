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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.PhoneMath
import es.ea7jrs.aprslorap.core.RepeaterBook
import es.ea7jrs.aprslorap.core.RotCtl
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** The phone's IPv4 addresses on WiFi / hotspot / Ethernet, for setting up GPredict on a PC. */
internal fun phoneAddresses(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
        .filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "no network" }
} catch (_: Exception) { "?" }

/** Phone sensors in the Sensors tab. */
@Composable
fun PhoneSensorsSection() {
    val ctx = LocalContext.current
    val r by AppState.phoneSensors.collectAsState()
    val gnss by AppState.gnss.collectAsState()
    var showAll by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { PhoneSensors.use(ctx, "sensors"); onDispose { PhoneSensors.release("sensors") } }
    Section("Phone sensors")
    Field("Tilt (face up)", if (r.pitchDeg != null && r.rollDeg != null) String.format(Locale.US, "pitch %.1f°, roll %.1f°", r.pitchDeg, r.rollDeg) else "— (phone not face up or no sensor)")
    Field("Compass (true north)", r.headingDeg?.let { String.format(Locale.US, "%.0f° (declination %s)", it, r.declinationDeg?.let { d -> String.format(Locale.US, "%+.1f°", d) } ?: "unknown") } ?: "—")
    val p = r.pressureHpa
    Field("Pressure", fmt(p, "%.1f", " hPa"))
    if (p != null) {
        Field("Barometric altitude (1013.25)", fmt(PhoneMath.baroAltitude(p), "%.0f", " m"))
        val alt = gnss?.altitudeM
        if (alt != null) Field("QNH (tracker GNSS altitude)", fmt(PhoneMath.qnh(p, alt), "%.1f", " hPa"))
    }
    if (r.available.isNotEmpty()) {
        OutlinedButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide the phone's sensor list" else "Phone's sensor list (${r.available.size})") }
        if (showAll) for (n in r.available) Text(n, fontSize = 11.sp)
    }
}

/** Rotor tools in the SAT tab: inclinometer, north check, rotctld. */
@Composable
fun RotorPhoneTools() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val info by AppState.trackerInfo.collectAsState()
    val rot by AppState.rotor.collectAsState()
    val r by AppState.phoneSensors.collectAsState()
    val attStatus by AppState.attStatus.collectAsState()
    val cStatus by AppState.rotClientStatus.collectAsState()
    val cActual by AppState.rotClientActual.collectAsState()
    val sStatus by AppState.rotServerStatus.collectAsState()
    val sLast by AppState.rotServerLast.collectAsState()
    var compassOn by rememberSaveable { mutableStateOf(false) }
    var zeroP by remember { mutableStateOf(settings.attZeroPitch) }
    var zeroR by remember { mutableStateOf(settings.attZeroRoll) }
    var cOn by remember { mutableStateOf(settings.rotClient) }
    var cHost by rememberSaveable { mutableStateOf(settings.rotClientHost) }
    var cPort by rememberSaveable { mutableStateOf(settings.rotClientPort.toString()) }
    var cStep by rememberSaveable { mutableStateOf(settings.rotClientStep.toString()) }
    var sOn by remember { mutableStateOf(settings.rotServer) }
    var sPort by rememberSaveable { mutableStateOf(settings.rotServerPort.toString()) }
    val attOn = attStatus != "Off"
    DisposableEffect(compassOn) {
        if (compassOn) PhoneSensors.use(ctx, "compass")
        onDispose { PhoneSensors.release("compass") }
    }
    val hasRotor = info?.hasRotor == true

    if (!hasRotor) Text("The rotor is off in the tracker (WebUI > Antenna rotor) or its firmware is older: inclinometer and rotctld server need it.", fontSize = 11.sp)

    // ---- inclinometer
    Section("Phone as the mount inclinometer")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Send the tilt to the tracker every second (att)", Modifier.weight(1f), fontSize = 14.sp)
        Switch(attOn, { on -> if (on) PhoneSensors.startAtt(ctx) else PhoneSensors.stopAtt() }, enabled = hasRotor || attOn)
    }
    Text(attStatus, fontSize = 12.sp)
    if (r.pitchDeg != null && r.rollDeg != null)
        Text(String.format(Locale.US, "Phone now: pitch %.1f°, roll %.1f° · zero: %.1f°, %.1f°", r.pitchDeg, r.rollDeg, zeroP, zeroR), fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { PhoneSensors.setZero(ctx); zeroP = settings.attZeroPitch; zeroR = settings.attZeroRoll }, enabled = r.pitchDeg != null) { Text("The mount is level now") }
        OutlinedButton(onClick = { settings.attZeroPitch = 0.0; settings.attZeroRoll = 0.0; zeroP = 0.0; zeroR = 0.0 }) { Text("Zero = 0") }
    }
    Text("Fix the phone face up on the rotor mount, its top edge to the front. Needs \"Tilt compensation\" on in the WebUI. The phone keeps sending while the link is up, also in the background.", fontSize = 11.sp)

    // ---- north check
    Section("North of the rotor (phone compass)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Use the compass", Modifier.weight(1f), fontSize = 14.sp)
        Switch(compassOn, { compassOn = it })
    }
    if (compassOn) {
        val h = r.headingDeg
        Field("The top of the phone points to", h?.let { String.format(Locale.US, "%.0f° true (declination %s)", it, r.declinationDeg?.let { d -> String.format(Locale.US, "%+.1f°", d) } ?: "unknown") } ?: "—")
        val p = rot
        val rotorAz = p?.actualAzDeg ?: p?.azDeg
        if (h != null && p != null && rotorAz != null && !p.mobile)
            Field("Azimuth offset for the WebUI", String.format(Locale.US, "%+.0f° (rotor %.0f° − antenna %.0f°)", RotCtl.angleDiff(h, rotorAz), rotorAz, h))
        Text("Lay the phone along the antenna boom, top edge towards where the antenna points, away from the motor and metal. With the rotor still, the offset is what to put in WebUI > Antenna rotor > Azimuth offset.", fontSize = 11.sp)
    }

    // ---- rotctld client
    Section("rotctld client (rotor on the network)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Send the tracker's pointing to a rotctld", Modifier.weight(1f), fontSize = 14.sp)
        Switch(cOn, { on -> cOn = on; settings.rotClient = on; RotctlLink.apply(settings) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(cHost, { cHost = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(cPort, { cPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(cStep, { cStep = it }, label = { Text("Step °") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    Button(onClick = {
        settings.rotClientHost = cHost; settings.rotClientPort = cPort.toIntOrNull() ?: RotCtl.DEFAULT_PORT
        settings.rotClientStep = cStep.replace(',', '.').toDoubleOrNull() ?: 1.0
        RotctlLink.apply(settings)
    }) { Text("Save") }
    Text(cStatus, fontSize = 12.sp)
    cActual?.let { Text(String.format(Locale.US, "The rotor reports %.1f° / %.1f°", it.first, it.second), fontSize = 12.sp) }
    Text("Needs \"Send the rot event\" on in the WebUI. Do not let the tracker drive the same rotor over WiFi at the same time.", fontSize = 11.sp)

    // ---- rotctld server
    Section("rotctld server (Look4Sat, GPredict...)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Accept rotor programs on this phone", Modifier.weight(1f), fontSize = 14.sp)
        Switch(sOn, { on -> sOn = on; settings.rotServer = on; RotctlLink.apply(settings) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(sPort, { sPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Button(onClick = { settings.rotServerPort = sPort.toIntOrNull() ?: RotCtl.DEFAULT_PORT; RotctlLink.apply(settings) }) { Text("Save") }
    }
    Text(sStatus, fontSize = 12.sp)
    sLast?.let { Text(String.format(Locale.US, "Last asked: %.1f° / %.1f° (sent to the tracker)", it.first, it.second), fontSize = 12.sp) }
    Text("On this phone: 127.0.0.1, port ${sPort.ifEmpty { "4533" }}. From a PC on the same WiFi: ${phoneAddresses()}. In GPredict: rotator, host and port; in Look4Sat: rotator control on. The program's position wins over the tracker's own target while it keeps sending (15 s).", fontSize = 11.sp)
}

/** RepeaterBook search for fixed targets. [onFill] puts name and frequency in the "Add a target" form (for results without position). */
@Composable
fun RepeaterBookSearch(onFill: (name: String, mhz: String) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val busy by AppState.rbBusy.collectAsState()
    val msg by AppState.rbMessage.collectAsState()
    val results by AppState.rbResults.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    var token by remember { mutableStateOf(settings.rbToken) }
    var agent by remember { mutableStateOf(settings.rbUserAgent) }
    var na by remember { mutableStateOf(settings.rbNorthAmerica) }
    var call by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("Spain") }
    var freq by rememberSaveable { mutableStateOf("") }
    var showSetup by rememberSaveable { mutableStateOf(settings.rbToken.isEmpty()) }

    Section("Repeaters from RepeaterBook")
    if (showSetup) {
        OutlinedTextField(token, { token = it.trim() }, label = { Text("Your token (rbuapp_...)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(agent, { agent = it }, label = { Text("User-Agent with your contact e-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("North America (USA, Canada, Mexico)", Modifier.weight(1f), fontSize = 13.sp)
            Switch(na, { na = it })
        }
        Button(onClick = { settings.rbToken = token; settings.rbUserAgent = agent; settings.rbNorthAmerica = na; showSetup = false }) { Text("Save") }
        Text("RepeaterBook gives each user a personal token (free for personal use) at ${RepeaterBook.TOKEN_PAGE}, and asks for a User-Agent that names the app and has a contact e-mail; it must match the one approved with the token. Results are only shown, not stored.", fontSize = 11.sp)
    } else {
        TextButtonLike("Token and User-Agent...") { showSetup = true }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(call, { call = it.uppercase() }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(freq, { freq = it }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(city, { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(country, { country = it }, label = { Text("Country") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Button(onClick = { RepeaterSearch.search(settings, call, city, country, freq) }, enabled = !busy) { Text("Search") }
    if (msg.isNotBlank()) Text(msg, fontSize = 12.sp)
    for (rp in results) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rp.callsign + "  " + (rp.freqMhz?.let { String.format(Locale.US, "%.4f", it) } ?: "?") + " MHz", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                val shift = if (rp.freqMhz != null && rp.inputMhz != null) String.format(Locale.US, "in %.4f", rp.inputMhz) else ""
                Text(listOf(shift, rp.tone.takeIf { it.isNotEmpty() }?.let { "tone $it" } ?: "", rp.city, rp.region, rp.status, rp.use)
                    .filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 11.sp)
                Text(if (rp.hasPosition) String.format(Locale.US, "%.5f, %.5f", rp.lat, rp.lon) else "no position in RepeaterBook: type it below", fontSize = 11.sp)
            }
            if (rp.hasPosition)
                OutlinedButton(onClick = {
                    AppState.controller?.control("tgt", "n" to rp.targetName, "lat" to rp.lat, "lon" to rp.lon, "alt" to 0, "f" to rp.freqHz, "sel" to true)
                }, enabled = info?.hasRotor == true) { Text("Follow") }
            else
                OutlinedButton(onClick = { onFill(rp.targetName, rp.freqMhz?.let { String.format(Locale.US, "%.4f", it) } ?: "") }) { Text("Use") }
        }
    }
    if (results.isNotEmpty()) Text(RepeaterBook.ATTRIBUTION, fontSize = 11.sp)
}

@Composable
private fun TextButtonLike(text: String, onClick: () -> Unit) = androidx.compose.material3.TextButton(onClick = onClick) { Text(text) }
