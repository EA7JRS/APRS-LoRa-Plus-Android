/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.RotorProto
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotor tab: the APRS LoRa+ Rotor box (ESP32, ESP32-S3, Pico 2 W, Raspberry Pi) over BLE or WiFi: position, go to, manual
 * moves, park, following the tracker's pointing, calibration of the direct drive, the whole configuration and restart.
 */
@Composable
fun RotorTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val status by AppState.rotorLinkStatus.collectAsState()
    val info by AppState.rotorInfo.collectAsState()
    val pos by AppState.rotorPos.collectAsState()
    val authed by AppState.rotorAuthed.collectAsState()
    val msg by AppState.rotorMessage.collectAsState()
    val trackerRot by AppState.rotor.collectAsState()

    var transport by rememberSaveable { mutableStateOf(settings.rotorTransport) }
    var address by rememberSaveable { mutableStateOf(settings.rotorAddress) }
    var name by rememberSaveable { mutableStateOf(settings.rotorName) }
    var host by rememberSaveable { mutableStateOf(settings.rotorHost) }
    var port by rememberSaveable { mutableStateOf(settings.rotorPort.toString()) }
    var pin by rememberSaveable { mutableStateOf(settings.rotorPin) }
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    val scanner = remember { BleScanner(ctx, UUID.fromString(RotorProto.SERVICE)) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (bluetoothGranted(ctx)) pending?.invoke() else AppState.error.value = "Bluetooth permission is needed"
        pending = null
    }
    fun withBt(action: () -> Unit) { if (bluetoothGranted(ctx)) action() else { pending = action; launcher.launch(requiredPermissions()) } }

    val linkOn = status != "Off"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---------------------------------------------------------------- link
        Section("Rotor box (APRS LoRa+ Rotor)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(transport == 0, { transport = 0 }, enabled = !linkOn); Text("Bluetooth", Modifier.clickable(enabled = !linkOn) { transport = 0 })
            RadioButton(transport == 1, { transport = 1 }, enabled = !linkOn); Text("WiFi (TCP)", Modifier.clickable(enabled = !linkOn) { transport = 1 })
        }
        if (transport == 0) {
            Text(if (address.isEmpty()) "No rotor box chosen" else "Chosen: " + (if (name.isNotEmpty()) "$name  ($address)" else address), fontSize = 13.sp)
            if (!linkOn) {
                OutlinedButton(onClick = {
                    withBt {
                        found.clear()
                        val ok = scanner.start { n, a ->
                            val i = found.indexOfFirst { it.second == a }
                            if (i < 0) found.add(n to a) else if (found[i].first.isEmpty() && n.isNotEmpty()) found[i] = n to a
                        }
                        if (!ok) AppState.error.value = "Bluetooth is off"
                    }
                }) { Text("Scan for rotor boxes") }
                for ((n, a) in found) {
                    Text((if (n.isNotEmpty()) n else "(name not received yet)") + "  ($a)",
                        Modifier.fillMaxWidth().clickable { address = a; name = n; scanner.stop() }.padding(vertical = 6.dp))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Address (APRS-Rotor.local, IP)") }, singleLine = true,
                    modifier = Modifier.weight(2f), enabled = !linkOn)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.weight(1f), enabled = !linkOn, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(pin, { pin = it.filter(Char::isLetterOrDigit).take(16) }, label = { Text("Rotor PIN") }, singleLine = true,
                modifier = Modifier.weight(1f), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (linkOn) OutlinedButton(onClick = { settings.rotorPin = pin; RotorLink.send(RotorProto.auth(pin)) }) { Text("Send PIN") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!linkOn) {
                Button(onClick = {
                    settings.rotorTransport = transport; settings.rotorPin = pin
                    if (transport == 0) {
                        if (address.isEmpty()) AppState.error.value = "Scan and choose a rotor box first"
                        else {
                            settings.rotorAddress = address; settings.rotorName = name
                            scanner.stop()
                            withBt { RotorLink.connectBle(ctx, address) }
                        }
                    } else {
                        if (host.isEmpty()) AppState.error.value = "Type the address of the rotor box"
                        else {
                            val p = port.toIntOrNull() ?: RotorProto.DEFAULT_TCP_PORT
                            settings.rotorHost = host; settings.rotorPort = p
                            RotorLink.connectTcp(ctx, host, p)
                        }
                    }
                }) { Text("Connect") }
            } else {
                OutlinedButton(onClick = { RotorLink.disconnect() }) { Text("Disconnect") }
            }
        }
        Text(status, fontSize = 12.sp)
        if (info?.pinRequired == true) Text(if (authed) "PIN accepted: control allowed" else "The box needs its PIN to move or change anything", fontSize = 12.sp,
            color = if (authed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, fontWeight = FontWeight.Medium)

        val i = info
        if (i == null) Text("The box answers by Bluetooth (BLE mode) or on the WiFi, port 4534. Its PIN is 123456 until you change it in its WebUI or below in Configuration.", fontSize = 11.sp)
        else RotorBody(i, pos, !i.pinRequired || authed, settings, trackerRot)
    }
}

@Composable
private fun RotorBody(i: RotorProto.Info, pos: RotorProto.Position?, canControl: Boolean, settings: Settings, trackerRot: es.ea7jrs.aprslorap.core.RotorPointing?) {

        // ---------------------------------------------------------------- position
        Section("Position")
        RotorCompass(pos, i)
        val p = pos
        if (p != null) {
            Field("Azimuth", RotorProto.deg(p.azDeg) + (p.mechAzDeg?.takeIf { it >= 360 || it < 0 }?.let { " (mechanical ${RotorProto.deg(it)})" } ?: ""))
            if (i.hasEl) {
                Field("Elevation", RotorProto.deg(p.elDeg))
                LinearProgressIndicator(progress = { ((p.elDeg ?: 0.0) / i.limits.maxEl.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (p.targetAzDeg != null) Field("Target", RotorProto.deg(p.targetAzDeg) + (p.targetElDeg?.let { " / " + RotorProto.deg(it) } ?: ""))
            Field("State", RotorProto.statusText(p.status).ifEmpty { "still" })
            Field("Fault", p.fault)
            p.aprs?.let { Text("APRS: $it", fontSize = 12.sp) }
        }

        // ---------------------------------------------------------------- control
        Section("Control")
        var gAz by rememberSaveable { mutableStateOf("") }
        var gEl by rememberSaveable { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(gAz, { gAz = it }, label = { Text("Az °") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (i.hasEl) OutlinedTextField(gEl, { gEl = it }, label = { Text("El °") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Button(enabled = canControl, onClick = {
                val a = gAz.replace(',', '.').toDoubleOrNull()
                if (a == null) AppState.error.value = "Type the azimuth"
                else RotorLink.command(RotorProto.pos(a, if (i.hasEl) gEl.replace(',', '.').toDoubleOrNull() else null))
            }) { Text("Go") }
        }
        if (i.canMove) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("⟲ CCW", canControl, Modifier.weight(1f)) { RotorProto.move(RotorProto.Move.CCW) }
                HoldButton("CW ⟳", canControl, Modifier.weight(1f)) { RotorProto.move(RotorProto.Move.CW) }
            }
            if (i.hasEl) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("▲ Up", canControl, Modifier.weight(1f)) { RotorProto.move(RotorProto.Move.UP) }
                HoldButton("▼ Down", canControl, Modifier.weight(1f)) { RotorProto.move(RotorProto.Move.DOWN) }
            }
            Text("Keep a button pressed to move by hand; it stops when you let go.", fontSize = 11.sp)
        }
        Button(onClick = { RotorLink.command(RotorProto.stop()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("STOP", fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = canControl, onClick = { RotorLink.command(RotorProto.park()) }) { Text("Park") }
            OutlinedButton(enabled = canControl && p?.ok == false, onClick = { RotorLink.command(RotorProto.clear()) }) { Text("Clear fault") }
        }

        // ---------------------------------------------------------------- follow
        RotorFollowSection(settings, trackerRot)

        // ---------------------------------------------------------------- calibration
        if (i.canCalibrate) RotorCalibration(i, canControl)

        // ---------------------------------------------------------------- configuration
        RotorConfigSection(canControl)

        // ---------------------------------------------------------------- about
        Section("Rotor box")
        Field("Name", i.name)
        Field("Firmware", "${i.device} ${i.version}")
        Field("Board", if (i.chip.isNotEmpty()) "${i.board} (${i.chip})" else i.board)
        Field("Drive", i.driver)
        if (i.callsign.isNotEmpty()) Field("APRS callsign", i.callsign)
        if (i.ip.isNotEmpty()) Field("IP", i.ip)
        Field("rotctld (Hamlib)", if (i.rotctldPort > 0) "port ${i.rotctldPort}" else "off")
        Field("Range", String.format(Locale.US, "az %.0f–%.0f°%s", i.limits.minAz, i.limits.maxAz,
            if (i.hasEl) String.format(Locale.US, ", el %.0f–%.0f°", i.limits.minEl, i.limits.maxEl) else ""))
        Field("Links", listOfNotNull("WiFi".takeIf { i.wifi }, "BLE".takeIf { i.ble }, "SPP".takeIf { i.spp }, "LoRa APRS".takeIf { i.lora }).joinToString(", "))
        i.hamlib?.let { Field("Hamlib rotctld", it) }
        OutlinedButton(enabled = canControl, onClick = { RotorLink.command(RotorProto.reboot()) }) { Text("Restart the rotor box") }
}

/** Compass: red = where the antenna points, blue = target; north up. */
@Composable
private fun RotorCompass(p: RotorProto.Position?, i: RotorProto.Info) {
    val ring = MaterialTheme.colorScheme.outline
    val north = MaterialTheme.colorScheme.error
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val r = size.minDimension / 2f - 6f
            val c = Offset(size.width / 2f, size.height / 2f)
            fun at(deg: Double, rad: Float) = Offset(c.x + (sin(Math.toRadians(deg)) * rad).toFloat(), c.y - (cos(Math.toRadians(deg)) * rad).toFloat())
            drawCircle(ring, radius = r, center = c, style = Stroke(3f))
            for (a in 0 until 360 step 30) drawLine(if (a == 0) north else ring, at(a.toDouble(), r - if (a % 90 == 0) 22f else 12f), at(a.toDouble(), r), strokeWidth = if (a % 90 == 0) 5f else 3f)
            // Mechanical stops of the azimuth when the range is not a full turn
            if (i.limits.maxAz - i.limits.minAz < 360) {
                drawLine(Color(0xFFFF9800), c, at(i.limits.minAz, r), strokeWidth = 2f)
                drawLine(Color(0xFFFF9800), c, at(i.limits.maxAz, r), strokeWidth = 2f)
            }
            p?.targetAzDeg?.let { drawLine(Color(0xFF2196F3), c, at(it, r - 16f), strokeWidth = 5f) }
            p?.let { drawLine(Color(0xFFE53935), c, at(it.azDeg, r - 8f), strokeWidth = 8f) }
            drawCircle(ring, radius = 6f, center = c)
        }
        Text("N", Modifier.align(Alignment.TopCenter), fontSize = 12.sp)
    }
}

/** Button that sends a move while pressed and STOP when released. */
@Composable
private fun HoldButton(label: String, enabled: Boolean, modifier: Modifier, move: () -> String) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier.heightIn(min = 48.dp).border(1.dp, color, RoundedCornerShape(24.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    RotorLink.command(move())
                    try { tryAwaitRelease() } finally { RotorLink.send(RotorProto.stop()) }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontWeight = FontWeight.Medium) }
}

@Composable
private fun RotorCalibration(i: RotorProto.Info, canControl: Boolean) {
    val raw by AppState.rotorRaw.collectAsState()
    var axis by rememberSaveable { mutableStateOf("az") }
    var deg by rememberSaveable { mutableStateOf("") }
    // Raw readings every 2 s while this section is on screen
    LaunchedEffect(Unit) { while (true) { RotorLink.send(RotorProto.raw()); delay(2000) } }
    Section("Calibration (direct drive)")
    val unit = raw?.unit?.let { when (it) { "mV" -> " mV"; "ads" -> " (ADS1115)"; else -> " (ADC)" } } ?: ""
    Field("Raw reading", "az ${raw?.az?.let { String.format(Locale.US, "%.0f", it) } ?: "—"}" +
        (if (i.hasEl) " · el ${raw?.el?.let { String.format(Locale.US, "%.0f", it) } ?: "—"}" else "") + unit)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(axis == "az", { axis = "az" }); Text("Azimuth", Modifier.clickable { axis = "az" })
        if (i.hasEl) { RadioButton(axis == "el", { axis = "el" }); Text("Elevation", Modifier.clickable { axis = "el" }) }
    }
    OutlinedTextField(deg, { deg = it }, label = { Text("Degrees where the rotor is now") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    val d = deg.replace(',', '.').toDoubleOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = canControl && d != null, onClick = { RotorLink.command(RotorProto.calibrate(axis, "a", d!!)) }) { Text("Point A") }
        OutlinedButton(enabled = canControl && d != null, onClick = { RotorLink.command(RotorProto.calibrate(axis, "b", d!!)) }) { Text("Point B") }
        OutlinedButton(enabled = canControl && d != null, onClick = { RotorLink.command(RotorProto.here(axis, d!!)) }) { Text("Offset") }
    }
    Text("Take the rotor by hand to one end (e.g. 0°), type it and press Point A; then to the other end (e.g. 450°) and Point B. " +
        "Offset only shifts the reading so that the present position is the degrees typed (north set-up).", fontSize = 11.sp)
}

@Composable
private fun RotorConfigSection(canControl: Boolean) {
    val cfg by AppState.rotorConfig.collectAsState()
    val busy by AppState.rotorBusy.collectAsState()
    var text by remember { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(cfg) { cfg?.let { text = prettyJson(it) } }
    Section("Configuration")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = canControl && !busy, onClick = { shown = true; RotorLink.readConfig() }) { Text("Read") }
        Button(enabled = canControl && !busy && shown && text.isNotBlank(), onClick = { RotorLink.writeConfig(text) }) { Text("Save and restart") }
    }
    if (shown) {
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
        Text("All the settings of the box (WiFi, PIN, pins, ranges, serial gateway, LoRa, APRS...). Passwords are shown: do not share screenshots. " +
            "Reference: docs/CONFIG.md of APRS-LoRa-Plus-Rotor.", fontSize = 11.sp)
    }
}

/** Indents the compact JSON of the box for editing (strings are left as they are). */
private fun prettyJson(s: String): String {
    val sb = StringBuilder()
    var depth = 0
    var inStr = false
    var esc = false
    fun nl() { sb.append('\n'); repeat(depth) { sb.append("  ") } }
    for (c in s) {
        if (inStr) {
            sb.append(c)
            if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            continue
        }
        when (c) {
            '"' -> { inStr = true; sb.append(c) }
            '{', '[' -> { sb.append(c); depth++; nl() }
            '}', ']' -> { depth--; nl(); sb.append(c) }
            ',' -> { sb.append(c); nl() }
            ':' -> sb.append(": ")
            else -> if (!c.isWhitespace()) sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * What the rotor box follows on its own from the app: the tracker's pointing (SAT, GO TO, fixed targets...), an APRS station
 * from the tracker's heard list (aimed where it is now when it moves) or the last CW beacon with locator. Angles from the
 * tracker's GPS.
 */
@Composable
private fun RotorFollowSection(settings: Settings, trackerRot: es.ea7jrs.aprslorap.core.RotorPointing?) {
    val ctx = LocalContext.current
    var follow by remember { mutableStateOf(settings.rotorFollow) }
    var mode by remember { mutableStateOf(settings.rotorFollowMode) }
    var step by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.1f", settings.rotorStep)) }
    var call by rememberSaveable { mutableStateOf(settings.rotorFollowCall) }
    var filter by rememberSaveable { mutableStateOf(settings.rotorFollowFilter) }
    var maxMin by rememberSaveable { mutableStateOf(settings.rotorFollowMaxMin.toString()) }
    var predict by remember { mutableStateOf(settings.rotorPredict) }
    var predictS by rememberSaveable { mutableStateOf(settings.rotorPredictMaxS.toString()) }
    val sent by AppState.rotorFollowSent.collectAsState()
    val heard by AppState.heard.collectAsState()
    val gnss by AppState.gnss.collectAsState()
    fun save() {
        settings.rotorStep = step.replace(',', '.').toDoubleOrNull() ?: 2.0
        settings.rotorFollowCall = call
        settings.rotorFollowFilter = filter
        settings.rotorFollowMaxMin = maxMin.toIntOrNull() ?: 30
        settings.rotorPredict = predict
        settings.rotorPredictMaxS = predictS.toIntOrNull() ?: 300
        RotorLink.applyFollow(ctx)
    }

    Section("Follow")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("The rotor box follows a target from this app", Modifier.weight(1f), fontSize = 14.sp)
        Switch(follow, { on -> follow = on; settings.rotorFollow = on; RotorLink.applyFollow(ctx) })
    }
    val modes = listOf("Tracker's pointing", "APRS station", "CW beacon")
    Row(verticalAlignment = Alignment.CenterVertically) {
        modes.forEachIndexed { i, label ->
            RadioButton(mode == i, { mode = i; settings.rotorFollowMode = i; RotorLink.applyFollow(ctx) })
            Text(label, Modifier.clickable { mode = i; settings.rotorFollowMode = i; RotorLink.applyFollow(ctx) }, fontSize = 13.sp)
        }
    }
    when (mode) {
        0 -> {
            trackerRot?.let { Text(String.format(Locale.US, "Tracker points to %.1f° / %.1f°%s", it.azDeg, it.elDeg, it.name?.let { n -> " ($n)" } ?: ""), fontSize = 12.sp) }
                ?: Text("No pointing from the tracker yet (needs the tracker link and \"Send the rot event\" on in its WebUI).", fontSize = 12.sp)
            Text("What the tracker itself follows (satellite, GO TO, fixed target, last heard, CW beacon) is chosen in the SAT tab > Rotor.", fontSize = 11.sp)
        }
        1 -> {
            OutlinedTextField(call, { call = it.uppercase().trim() }, label = { Text("Station (empty = the last heard with position)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val withPos = heard.filter { it.lat != null && it.lon != null }.take(8)
            if (withPos.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (h in withPos.take(4)) OutlinedButton(onClick = { call = h.call; save() }) { Text(h.call, fontSize = 11.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(filter, { filter = it.uppercase() }, label = { Text("Filter (EA7JRS, EA7*)") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(maxMin, { maxMin = it.filter(Char::isDigit).take(3) }, label = { Text("Max min") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Moving stations: aim where they are now (speed and course)", Modifier.weight(1f), fontSize = 13.sp)
                Switch(predict, { predict = it; save() })
            }
            OutlinedTextField(predictS, { predictS = it.filter(Char::isDigit).take(4) }, label = { Text("Predict up to (s)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Stations heard by the tracker (APRS LoRa and FSK, Mic-E included); the list is asked again every 10 s while following. \"~\" = estimated position.", fontSize = 11.sp)
        }
        else -> Text("The last callsign + locator decoded in CW (\"VVV DE ED7YAA IM76HR\"): from the tracker's CW listening (Tracker tab, MORSE mode) or from the phone's CW decoder (Modes tab). The rotor points to the centre of the square.", fontSize = 11.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(step, { step = it }, label = { Text("Minimum step °") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedButton(onClick = { save() }) { Text("Save") }
    }
    if (mode != 0) Text(gnss?.let { g -> if (g.lat != null && g.lon != null) String.format(Locale.US, "From the tracker's GPS: %.5f, %.5f", g.lat, g.lon) else null }
        ?: "No tracker GPS position yet: the angles are computed from it.", fontSize = 12.sp)
    if (sent.isNotBlank()) Text(sent, fontSize = 12.sp)
    Text("Do not let the tracker drive the same rotor at the same time by WiFi or SPP: choose one way.", fontSize = 11.sp)
}
