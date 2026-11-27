/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import es.ea7jrs.aprslorap.core.ChatMessage
import es.ea7jrs.aprslorap.core.MessageEngine
import es.ea7jrs.aprslorap.core.MessageStatus
import es.ea7jrs.aprslorap.core.Nav
import es.ea7jrs.aprslorap.core.Station

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fmt.units = Settings(this).units
        Spotter.init(this)
        AlertCenter.init(this)
        NavtexHub.init(this)
        NavtexNet.apply(Settings(this))                         // NAVTEX from a receiver on the network, if it is set up
        DscHub.init(this)
        HabHub.init(this)
        Beacon406Hub.init(this)
        LogStore.init(this)
        DxLink.loadCty(this)                          // cty.dat: read it, and a new one if it is more than a week old
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            AlpTheme {
                // Welcome screen first (logo; callsign + DMR ID check if not validated yet), then the app
                var entered by rememberSaveable { mutableStateOf(false) }
                if (entered) App() else WelcomeScreen(onEnter = { entered = true })
            }
        }
    }

    /**
     * Keyboard-type key adapters (VBand, Vail in keyboard mode, Bluetooth paddles): while keying is on, their keys go to the
     * keyer instead of the screen. Only while this screen is in front (Android sends key events to the focused window).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (CwEngine.active && CwEngine.source == "keyboard") {
            val line = es.ea7jrs.aprslorap.core.KeyboardAdapter.line(event.keyCode)
            if (line != null) {
                when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) CwEngine.setLine(line, true)
                    android.view.KeyEvent.ACTION_UP -> CwEngine.setLine(line, false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** "Go to": opens the route to the station in Google Maps (or any maps app if it is not installed). */
fun goTo(ctx: Context, s: Station) {
    val lat = s.lat ?: return
    val lon = s.lon ?: return
    goToPos(ctx, lat, lon, s.call)
}

internal fun requiredPermissions(): Array<String> {
    val l = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        l.add(Manifest.permission.BLUETOOTH_SCAN)
        l.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
    return l.toTypedArray()
}

/** Only the Bluetooth permissions are essential; without notification permission the app works but shows no alerts. */
internal fun bluetoothGranted(ctx: Context): Boolean = requiredPermissions()
    .filter { it != Manifest.permission.POST_NOTIFICATIONS }
    .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun ConnectTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var call by rememberSaveable { mutableStateOf(settings.callsign) }
    var trackerCall by rememberSaveable { mutableStateOf(settings.trackerCallsign) }
    var device by rememberSaveable { mutableStateOf(settings.deviceAddress) }
    var deviceName by rememberSaveable { mutableStateOf(settings.deviceName) }
    var igate by rememberSaveable { mutableStateOf(settings.igateEnabled) }
    var beacon by rememberSaveable { mutableStateOf(settings.phoneBeacon) }
    var beaconSym by rememberSaveable { mutableStateOf(settings.phoneBeaconSymbol) }
    var beaconText by rememberSaveable { mutableStateOf(settings.phoneBeaconComment) }
    var beaconMin by rememberSaveable { mutableStateOf(settings.phoneBeaconMinutes) }
    val beaconState by AppState.phoneBeacon.collectAsState()
    val locationAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { beacon = false; AppState.error.value = "Without the location permission the phone cannot send its position" }
    }
    var host by rememberSaveable { mutableStateOf(settings.isHost) }
    var port by rememberSaveable { mutableStateOf(settings.isPort.toString()) }
    var dapUser by rememberSaveable { mutableStateOf(settings.dapnetUser) }
    var dapPass by rememberSaveable { mutableStateOf(settings.dapnetPassword) }
    var dapGroup by rememberSaveable { mutableStateOf(settings.dapnetGroup) }
    var nmeaOn by rememberSaveable { mutableStateOf(settings.nmeaServer) }
    var nmeaPort by rememberSaveable { mutableStateOf(settings.nmeaPort.toString()) }
    var mockOn by rememberSaveable { mutableStateOf(settings.mockLocation) }
    var licence by remember { mutableStateOf(Licence.summary(settings)) }
    var checking by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    var extras by remember { mutableStateOf(settings.extraReceivers) }
    var rxAddr by rememberSaveable { mutableStateOf("") }
    var rxName by rememberSaveable { mutableStateOf("") }
    val extraStatus by AppState.extraStatus.collectAsState()
    val scanner = remember { BleScanner(ctx) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val ble by AppState.bleStatus.collectAsState()
    val isStatus by AppState.igateStatus.collectAsState()
    val running by AppState.running.collectAsState()
    val log by AppState.log.collectAsState()

    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (bluetoothGranted(ctx)) pending?.invoke() else AppState.error.value = "Bluetooth permission is needed"
        pending = null
    }
    fun withPermissions(action: () -> Unit) {
        if (bluetoothGranted(ctx) && (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            action()
        } else {
            pending = action
            launcher.launch(requiredPermissions())
        }
    }
    fun save() {
        settings.callsign = call
        settings.trackerCallsign = trackerCall
        settings.deviceAddress = device
        settings.deviceName = deviceName
        settings.igateEnabled = igate
        settings.phoneBeacon = beacon
        settings.phoneBeaconSymbol = beaconSym
        settings.phoneBeaconComment = beaconText
        settings.phoneBeaconMinutes = beaconMin
        settings.isHost = host
        settings.isPort = port.toIntOrNull() ?: 14580
        settings.dapnetUser = dapUser
        settings.dapnetPassword = dapPass
        settings.dapnetGroup = dapGroup
        settings.nmeaServer = nmeaOn
        settings.nmeaPort = nmeaPort.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 10110
        settings.mockLocation = mockOn
        licence = Licence.summary(settings)
    }
    fun validate() {
        save()
        checking = true
        Licence.validate(settings) { err ->
            checking = false
            licence = Licence.summary(settings)
            if (err != null) AppState.error.value = err else AppState.notice.value = licence
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(call, { call = it.uppercase() }, label = { Text("Your callsign with SSID (EA7JRS-7)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(licence, Modifier.weight(1f), fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !checking, onClick = { validate() }) { Text(if (checking) "Checking..." else "Validate") }
        }
        Text("The callsign needs a DMR ID / CCS7 in RadioID.net. Until it is validated the app only listens.", fontSize = 11.sp)
        OutlinedTextField(trackerCall, { trackerCall = it.uppercase() }, label = { Text("Tracker callsign (as in its WebUI)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(
            if (MessageEngine.trackerSendsAcks(call, trackerCall)) "Same as yours: the tracker sends the ACKs, the app does not"
            else "Different or not set: the app sends the ACKs",
            fontSize = 12.sp,
        )
        Text("APRS path is in App settings > TNC.", fontSize = 11.sp, color = Alp.SubText)

        Text("Tracker (Bluetooth LE, KISS)", fontWeight = FontWeight.Bold)
        Text(if (device.isEmpty()) "No tracker chosen" else "Chosen: " + (if (deviceName.isNotEmpty()) "$deviceName  ($device)" else device))
        Text(
            "The first time, Android asks for the tracker's Control PIN (WebUI > Bluetooth > Control PIN, 123456 until you change it). " +
                "APRS (KISS) works without it.",
            fontSize = 11.sp,
        )
        if (device.isNotEmpty()) {
            OutlinedButton(enabled = !running, onClick = {
                withPermissions {
                    if (forgetTrackerPairing(ctx, device)) AppState.notice.value = "Pairing forgotten: the PIN is asked again at the next start"
                    else AppState.error.value = "Could not forget it here: do it in Android's Bluetooth settings"
                }
            }) { Text("Forget pairing (after changing the PIN)") }
        }
        OutlinedButton(onClick = {
            withPermissions {
                found.clear()
                val ok = scanner.start { n, a ->
                    val i = found.indexOfFirst { it.second == a }
                    if (i < 0) found.add(n to a)
                    else if (found[i].first.isEmpty() && n.isNotEmpty()) found[i] = n to a      // the name came later
                }
                if (!ok) AppState.error.value = "Bluetooth is off"
            }
        }) { Text("Scan for trackers") }
        for ((n, a) in found) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (n.isNotEmpty()) n else "(name not received yet)") + "  ($a)",
                    Modifier.weight(1f).clickable { device = a; deviceName = n; scanner.stop() }.padding(vertical = 6.dp),
                )
                TextButton(onClick = {
                    es.ea7jrs.aprslorap.core.Receivers.entry(a, n)?.let { e ->
                        if (extras.none { it.key == e.key }) { extras = extras + e; settings.extraReceivers = extras }
                    }
                }) { Text("+ receiver", fontSize = 11.sp) }
            }
        }

        // ---- more trackers as receivers
        Text("More trackers as receivers", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("Each one listens in its own mode and frequency (APRS LoRa, APRS FSK...) and what it hears goes to Stations and Messages. " +
            "The tracker chosen above keeps the control and the transmitting. Bluetooth: KISS only, no PIN. WiFi: the tracker's KISS server (port 8001).", fontSize = 11.sp)
        for (e in extras) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.label + "  (" + (if (e.kind == es.ea7jrs.aprslorap.core.Receivers.Kind.BLE) "Bluetooth " else "WiFi ") + e.address + ")", fontSize = 13.sp)
                    Text(if (!e.enabled) "Disabled" else extraStatus[e.key] ?: if (running) "Starting..." else "Starts with the link", fontSize = 11.sp)
                }
                Switch(e.enabled, { on -> extras = extras.map { if (it.key == e.key) it.copy(enabled = on) else it }; settings.extraReceivers = extras })
                TextButton(onClick = { extras = extras.filter { it.key != e.key }; settings.extraReceivers = extras }) { Text("Remove", fontSize = 11.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(rxAddr, { rxAddr = it.trim() }, label = { Text("IP[:port] or Bluetooth address") }, singleLine = true, modifier = Modifier.weight(1.4f))
            OutlinedTextField(rxName, { rxName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        val newRx = es.ea7jrs.aprslorap.core.Receivers.entry(rxAddr, rxName)
        OutlinedButton(onClick = { newRx?.let { e -> if (extras.none { it.key == e.key }) { extras = extras + e; settings.extraReceivers = extras }; rxAddr = ""; rxName = "" } },
            enabled = newRx != null) { Text("Add receiver") }
        if (running && extras.isNotEmpty()) Text("Changes apply when the link is restarted.", fontSize = 11.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = beacon, onCheckedChange = {
                beacon = it
                if (it && !PhoneBeaconSource.permitted(ctx)) locationAsk.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            })
            Spacer(Modifier.width(8.dp))
            Text("Position beacons from the phone (its GNSS, your callsign)")
        }
        if (beacon) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AprsSymbolIcon(es.ea7jrs.aprslorap.core.AprsSymbols.of(beaconSym.getOrNull(0), beaconSym.getOrNull(1)), 34.dp, Alp.SymbolBg)
                OutlinedTextField(beaconSym, { beaconSym = it.take(2) }, label = { Text("Symbol") }, singleLine = true, modifier = Modifier.width(96.dp))
                OutlinedTextField(beaconText, { beaconText = it.take(43) }, label = { Text("Comment") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, sym) in listOf("PHONE" to "/$", "CAR" to "/>", "PERSON" to "/[", "BIKE" to "/b", "BOAT" to "/Y", "HOME" to "/-"))
                    Chip(label, beaconSym == sym, height = 32.dp) { beaconSym = sym }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, min) in listOf("SMART" to 0, "1 min" to 1, "5 min" to 5, "10 min" to 10, "30 min" to 30))
                    Chip(label, beaconMin == min, height = 32.dp) { beaconMin = min }
            }
            Text(if (beaconMin == 0) "SmartBeaconing: every 30 min when still, down to 90 s at 100 km/h, and at once on a turn."
                 else "A beacon every $beaconMin min, moving or not.", fontSize = 12.sp, color = Alp.SubText)
            if (MessageEngine.trackerSendsAcks(call, trackerCall))
                Text("Same callsign and SSID as the tracker: the tracker already sends its own beacons with it. Use another SSID here (-7 handheld, -9 car) or turn the tracker's beacon off.",
                    fontSize = 12.sp, color = Alp.Amber)
            Text(if (running) beaconState else "Applies when the link is restarted.", fontSize = 12.sp, color = Alp.SubText)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = igate, onCheckedChange = { igate = it })
            Spacer(Modifier.width(8.dp))
            Text("iGate: send what the tracker hears to APRS-IS")
        }
        if (igate) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp),
                )
            }
        }

        Text("DAPNET account (to send pages)", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(dapUser, { dapUser = it.lowercase() }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(
                dapPass, { dapPass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        OutlinedTextField(dapGroup, { dapGroup = it.lowercase() }, label = { Text("Transmitter groups (e.g. ea-all)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("The DAPNET API uses plain HTTP: user and password are sent without encryption.", fontSize = 11.sp)

        Text("NMEA and location for other apps", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = nmeaOn, onCheckedChange = { nmeaOn = it })
            Spacer(Modifier.width(8.dp))
            Text("NMEA TCP server (OpenCPN, Navionics...)", Modifier.weight(1f))
            OutlinedTextField(
                nmeaPort, { nmeaPort = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = mockOn, onCheckedChange = { mockOn = it })
            Spacer(Modifier.width(8.dp))
            Text("Tracker GNSS as the phone location (choose this app in Developer options > Select mock location app)")
        }
        Text("These settings apply when the link starts.", fontSize = 11.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running, onClick = { save(); withPermissions { TrackerService.start(ctx) } }) { Text("Start") }
            OutlinedButton(enabled = running, onClick = { TrackerService.stop(ctx) }) { Text("Stop") }
        }
        Text("Tracker: $ble")
        Text("APRS-IS: $isStatus")
        HorizontalDivider()
        for (l in log.takeLast(25)) Text(l, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
