/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The screens that existed before the radio-style interface: all of them stay, opened from [F], the side menu or a touch key. */
enum class Tool(val title: String) {
    TRACKER("Tracker"), SENSORS("Sensors"), HEARD("Heard by the tracker"), SAT("Satellites"), ROTOR("Rotor box"), SDR("SDR receivers"),
    MODES("Digital modes"), AIS("AIS ships"), LOG("Log & QSL"), SIGID("Signal ID"), REMOTE("Remote screen"), CONFIG("Tracker configuration"),
    DAPNET("DAPNET"), FIRMWARE("Firmware"), CONNECT("Connect"), ABOUT("About"), ALERTS("Official alerts"), CAT("Radio (CAT)"), ADSB("Aircraft (ADS-B)"), BEACONS("Beacons heard (SDR)"),
    VESSEL("My vessel (AIS)"), ASTRO("Celestial navigation"), WEFAX("Weather fax & HF prediction"), HAB("Balloons (HAB)"),
    SETTINGS("App settings"),
}

/** Where the app is: "RADIO", "DISP", "MAP", "STATIONS", "WX", "IBP", "STATION:<call>", "MESSAGES", "THREAD:<call>", "DX", "FMENU", "TOOL:<name>". */
class Navigator(initial: List<String>) {
    val stack = mutableStateListOf<String>().apply { addAll(initial.ifEmpty { listOf("RADIO") }) }
    val top: String get() = stack.last()
    val kind: String get() = top.substringBefore(':')
    val arg: String get() = top.substringAfter(':', "")

    fun go(dest: String) {
        if (dest == "RADIO") { stack.clear(); stack.add("RADIO"); return }
        if (top == dest) return
        if (top == "FMENU") stack.removeAt(stack.size - 1)      // the function menu closes when a key is used
        stack.remove(dest)
        stack.add(dest)
        while (stack.size > 12) stack.removeAt(0)
    }

    fun tool(t: Tool) = go("TOOL:" + t.name)

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    companion object {
        val Saver = listSaver<Navigator, String>(save = { it.stack.toList() }, restore = { Navigator(it) })
    }
}

/** One key of the function menu (and of the touch keys): label, a short live text below it, and what it does. */
class FKey(val id: String, val label: String, val sub: String?, val lit: Int = 0, val action: () -> Unit)

@Composable
private fun functionKeys(nav: Navigator, ctx: Context): Map<String, FKey> {
    val stations by AppState.stations.collectAsState()
    val wxNearest = remember(stations) {
        val me = Own.position()
        val wx = stations.filter { it.wx != null }
        if (me == null) wx.maxByOrNull { it.wxMs ?: 0L }
        else wx.minByOrNull { s -> val la = s.lat; val lo = s.lon; if (la == null || lo == null) Double.MAX_VALUE else es.ea7jrs.aprslorap.core.Geo.distanceKm(me.first, me.second, la, lo) }
    }
    val msgs by AppState.messages.collectAsState()
    val status by AppState.trackerStatus.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val gnss by AppState.gnss.collectAsState()
    val igate by AppState.igateStatus.collectAsState()
    val sat by AppState.satState.collectAsState()
    val rotorPos by AppState.rotorPos.collectAsState()
    val sdr by AppState.sdr.collectAsState()
    val phoneCat by AppState.phoneCat.collectAsState()
    val adsb by AdsbLink.state.collectAsState()
    val aircraftCount = AppState.aircraft.collectAsState().value.size
    val lrfhss by LrFhssLink.state.collectAsState()
    val beaconCount = AppState.beacons.collectAsState().value.size
    val habCount = HabHub.balloons.collectAsState().value.size
    val wsjt by AppState.wsjt.collectAsState()
    val extra by AppState.extraStatus.collectAsState()
    val spot by AppState.spotter.collectAsState()
    val dx by DxLink.state.collectAsState()
    val ais by AppState.aisVessels.collectAsState()
    val oa by AppState.officialAlerts.collectAsState()
    val settings = remember { Settings(ctx) }
    val unread = Unread.count(msgs, settings)
    val spotOn = listOf(spot.psk, spot.wsprnet, spot.udp, spot.telnet).any { it != "Off" }
    val profile = info?.profiles?.getOrNull(status?.profile ?: -1)?.label
    val keys = listOf(
        FKey("SLIST", "S.LIST", "${stations.size} heard") { nav.go("STATIONS") },
        FKey("WX", "WX", wxNearest?.let { n -> n.wx?.tempC?.let { String.format(java.util.Locale.US, "%.0f° %s", it, n.call) } } ?: "${stations.count { it.wx != null }} stations",
            if (stations.any { it.wx != null }) 1 else 0) { nav.go("WX") },
        FKey("MSG", "MSG", if (unread > 0) "$unread new" else "${msgs.size} msgs", if (unread > 0) 2 else 0) { nav.go("MESSAGES") },
        FKey("BEACON", "BEACON", if (status?.smartBeacon == true) "SMART" else profile ?: "profile", if (status?.smartBeacon == true) 2 else 0) { nav.tool(Tool.TRACKER) },
        FKey("BCN", "BCN", "TX now") { AppState.controller?.control("beacon") ?: run { AppState.error.value = "Start the link first (Connect)" } },
        FKey("GPS", "GPS", gnss?.let { if (it.fix) "${it.satellites ?: 0} sats" else "no fix" } ?: "—", if (gnss?.fix == true) 1 else 0) {
            settings.uiDispPage = 1; DispState.page = 1; nav.go("DISP")
        },
        FKey("LOG", "LOG", "QSO book") { nav.tool(Tool.LOG) },
        FKey("IGATE", "iGATE", if (settings.igateEnabled) "ON" else "OFF", if (settings.igateEnabled) 1 else 0) { nav.tool(Tool.CONNECT) },
        FKey("DAPNET", "DAPNET", "pager") { nav.tool(Tool.DAPNET) },
        FKey("DX", "DX", if (dx.connected) "${dx.spots.size} spots" else "cluster", if (dx.connected) 1 else 0) { nav.go("DX") },
        FKey("RBN", "RBN", "${DxLink.rbn.value.size} skimmer spots", if (DxLink.rbn.value.isNotEmpty()) 1 else 0) { nav.go("RBN") },
        FKey("IBP", "IBP", es.ea7jrs.aprslorap.core.HamLayers.ibpNow(System.currentTimeMillis())[0].second.call + " 20m") { nav.go("IBP") },
        FKey("SAT", "SAT", sat?.name ?: "passes", if (sat?.ok == true) 1 else 0) { nav.tool(Tool.SAT) },
        FKey("ROTOR", "ROTOR", rotorPos?.let { String.format(java.util.Locale.US, "%03.0f° / %.0f°", it.azDeg, it.elDeg ?: 0.0) } ?: "box") { nav.tool(Tool.ROTOR) },
        FKey("CAT", "CAT", phoneCat?.takeIf { it.link }?.let { es.ea7jrs.aprslorap.core.RotorProto.freq(it.freqHz) } ?: "USB radio", if (phoneCat?.link == true) 1 else 0) { nav.tool(Tool.CAT) },
        FKey("BEACONS", "BEACONS", if (beaconCount > 0) "$beaconCount heard" else "LR-FHSS", if (lrfhss.running) 1 else 0) { nav.tool(Tool.BEACONS) },
        FKey("HAB", "HAB", if (habCount > 0) "$habCount balloons" else "telemetry", if (habCount > 0) 1 else 0) { nav.tool(Tool.HAB) },
        FKey("ADSB", "ADS-B", if (adsb.running) "${aircraftCount} aircraft" else "1090 MHz", if (adsb.running) 1 else 0) { nav.tool(Tool.ADSB) },
        FKey("SDR", "SDR", if (sdr.connected) sdr.device.ifEmpty { "on" } else "receivers", if (sdr.connected) 1 else 0) { nav.tool(Tool.SDR) },
        FKey("MODES", "MODES", if (wsjt.running) "decoding" else "FT8 · CW", if (wsjt.running) 1 else 0) { nav.tool(Tool.MODES) },
        FKey("CWKEY", "CW KEY", "paddle") { nav.tool(Tool.TRACKER) },
        FKey("SPOTS", "SPOTS", if (spotOn) "ON" else "PSK · WSPR", if (spotOn) 1 else 0) { nav.tool(Tool.MODES) },
        FKey("AIS", "AIS", if (ais.isEmpty()) "ships" else "${ais.size} ships", if (ais.isNotEmpty()) 1 else 0) { nav.tool(Tool.AIS) },
        FKey("ALERTS", "ALERTS", if (oa.isEmpty()) "official" else "${oa.size} in force", if ((oa.maxOfOrNull { it.level } ?: 0) >= 2) 1 else 0) { nav.tool(Tool.ALERTS) },
        FKey("WEFAX", "WEFAX", "charts · VOACAP") { nav.tool(Tool.WEFAX) },
        FKey("RXPLUS", "RX+", "${extra.size} receivers") { nav.tool(Tool.CONNECT) },
        FKey("REMOTE", "REMOTE", "screen") { nav.tool(Tool.REMOTE) },
        FKey("CONFIG", "CONFIG", "tracker") { nav.tool(Tool.CONFIG) },
        FKey("FIRMWARE", "FIRMWARE", info?.firmware ?: "update") { nav.tool(Tool.FIRMWARE) },
        FKey("CONNECT", "CONNECT", "BLE · WiFi") { nav.tool(Tool.CONNECT) },
        FKey("SENSORS", "SENSORS", "tracker · phone") { nav.tool(Tool.SENSORS) },
        FKey("SIGID", "SIGID", "signals") { nav.tool(Tool.SIGID) },
        FKey("HEARD", "HEARD", "by tracker") { nav.tool(Tool.HEARD) },
        FKey("ABOUT", "ABOUT", "APRS LoRa+") { nav.tool(Tool.ABOUT) },
        FKey("ASTRO", "ASTRO", "sextant") { nav.tool(Tool.ASTRO) },
        FKey("MAP", "MAP", "layers") { nav.go("MAP") },
        FKey("DISP", "DISP", "compass") { nav.go("DISP") },
    )
    return keys.associateBy { it.id }
}

val FMENU_PAGES = listOf(
    "APRS" to listOf("SLIST", "MSG", "BEACON", "BCN", "GPS", "LOG", "IGATE", "DAPNET", "WX", "MAP", "DISP", "HEARD"),
    "RADIO" to listOf("DX", "RBN", "IBP", "SAT", "ROTOR", "CAT", "SDR", "MODES", "CWKEY", "BEACONS", "AIS", "ADSB"),
    "SYSTEM" to listOf("REMOTE", "CONFIG", "FIRMWARE", "CONNECT", "SENSORS", "SIGID", "RXPLUS", "ABOUT", "ALERTS", "SPOTS", "ASTRO", "WEFAX", "HAB"),
)

/** The app after the welcome screen: radio-style screens, touch keys, [F] menu and side menu. */
@Composable
fun App() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val nav: Navigator = rememberSaveable(saver = Navigator.Saver) { Navigator(listOf("RADIO")) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var touchKeys by remember { mutableStateOf(settings.uiKeys) }
    var assign by remember { mutableStateOf<FKey?>(null) }
    val keys = functionKeys(nav, ctx)

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && nav.stack.size > 1) { nav.back() }

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Alp.Scrim.copy(alpha = 0.7f),
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Alp.Bg, drawerShape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp), modifier = Modifier.width(318.dp)) {
                SideMenu(nav, settings) { scope.launch { drawer.close() } }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(Alp.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Banners()
            val openDrawer = { scope.launch { drawer.open() }; Unit }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (nav.kind) {
                    "RADIO" -> RadioScreen(nav, openDrawer)
                    "FMENU" -> FunctionMenu(nav, keys, openDrawer) { assign = it }
                    "DISP" -> DispScreen(nav, openDrawer)
                    "MAP" -> MapScreen(nav)
                    "STATIONS" -> StationListScreen(nav)
                    "WX" -> WxScreen(nav)
                    "STATION" -> StationDetailScreen(nav, nav.arg)
                    "MESSAGES" -> MessageListScreen(nav)
                    "THREAD" -> ThreadScreen(nav, nav.arg)
                    "DX" -> DxClusterScreen(nav)
                    "IBP" -> IbpScreen(nav)
                    "RBN" -> RbnScreen(nav)
                    "TOOL" -> ToolScreen(nav, Tool.entries.firstOrNull { it.name == nav.arg } ?: Tool.CONNECT)
                    else -> RadioScreen(nav, openDrawer)
                }
            }
            if (nav.kind in setOf("RADIO", "FMENU", "DISP", "MAP", "TOOL")) TouchKeys(nav, keys, touchKeys)
        }
    }

    AlertOverlay(nav)

    assign?.let { k ->
        AlertDialog(
            onDismissRequest = { assign = null },
            title = { Text("Put ${k.label} on a touch key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose which of the four keys next to [F] it replaces.", color = Alp.Muted, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        touchKeys.forEachIndexed { i, id ->
                            RadioKey(keys[id]?.label ?: id, Modifier.weight(1f).height(52.dp), sub = "key ${i + 1}") {
                                touchKeys = touchKeys.toMutableList().also { it[i] = k.id }
                                settings.uiKeys = touchKeys
                                assign = null
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assign = null }) { Text("Cancel") } },
            containerColor = Alp.Panel,
        )
    }
}

@Composable
private fun Banners() {
    val error by AppState.error.collectAsState()
    val notice by AppState.notice.collectAsState()
    error?.let {
        Text(it, color = Alp.Bg, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().background(Alp.RedSoft)
            .clickable { AppState.error.value = null }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
    notice?.let {
        Text(it, color = Alp.Bg, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().background(Alp.Cyan)
            .clickable { AppState.notice.value = null }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/** [F] and the four touch keys at the bottom. */
@Composable
private fun TouchKeys(nav: Navigator, keys: Map<String, FKey>, ids: List<String>) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 6.dp).height(58.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val fOn = nav.kind == "FMENU"
        Box(
            Modifier.width(56.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(if (fOn) Alp.Amber else Alp.Cyan)
                .clickable { if (fOn) nav.back() else nav.go("FMENU") },
            contentAlignment = Alignment.Center,
        ) { Text("F", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Alp.Bg) }
        for (id in ids) {
            val k = keys[id] ?: continue
            val active = when (id) { "SLIST" -> nav.kind == "STATIONS"; "MSG" -> nav.kind == "MESSAGES"; "MAP" -> nav.kind == "MAP"; "DISP" -> nav.kind == "DISP"; "DX" -> nav.kind == "DX"; "WX" -> nav.kind == "WX"; "IBP" -> nav.kind == "IBP"; "RBN" -> nav.kind == "RBN"; else -> false }
            val sub = when (id) { "BCN" -> "TX"; "MSG" -> k.sub?.takeIf { k.lit == 2 }; else -> null }
            RadioKey(k.label, Modifier.weight(1f).fillMaxHeight(), sub = sub, border = if (active) Alp.Amber else Alp.KeyBorder,
                color = if (active) Alp.Amber else Alp.Text, subColor = if (k.lit == 2) Alp.Amber else Alp.Muted, onClick = k.action)
        }
    }
}

/** Top row of the radio screens: side menu, callsign, link / GNSS / battery, UTC clock and DISP. */
@Composable
fun StatusRow(nav: Navigator, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val running by AppState.running.collectAsState()
    val control by AppState.controlReady.collectAsState()
    val gnss by AppState.gnss.collectAsState()
    val status by AppState.trackerStatus.collectAsState()
    val sensors by AppState.sensors.collectAsState()
    val now = rememberNow()
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = openDrawer), contentAlignment = Alignment.Center) {
            Text("☰", fontSize = 22.sp, color = Alp.Text)
        }
        Text(settings.callsign.ifEmpty { "NOCALL" }, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Alp.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clickable { nav.tool(Tool.CONNECT) }) {
            Lamp(running, if (control) Alp.Green else Alp.Cyan); Text("BLE", fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Lamp(gnss?.fix == true); Text("GPS", fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted)
        }
        val pct = sensors?.batteryPct
        val volts = status?.batteryV ?: sensors?.batteryV
        if (pct != null || volts != null) Text(if (pct != null) "$pct%" else String.format(java.util.Locale.US, "%.2fV", volts),
            fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if ((pct ?: 100) < 20) Alp.Amber else Alp.Text)
        Text(Fmt.hhmm(now, utc = true) + "Z", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Alp.Text)
        Box(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, if (nav.kind == "DISP") Alp.Amber else Alp.KeyBorder, RoundedCornerShape(8.dp))
            .clickable { if (nav.kind == "DISP") nav.back() else nav.go("DISP") }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text("DISP", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (nav.kind == "DISP") Alp.Amber else Alp.Text)
        }
    }
}

// ------------------------------------------------------------------ [F] function menu

@Composable
private fun FunctionMenu(nav: Navigator, keys: Map<String, FKey>, openDrawer: () -> Unit, onAssign: (FKey) -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow(nav, openDrawer)
        BandSummary(nav)
        Panel(Modifier.fillMaxWidth().weight(1f), padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FMENU_PAGES.forEachIndexed { i, (name, _) -> Chip(name, i == page) { page = i } }
                Spacer(Modifier.weight(1f))
                Text("${page + 1} / ${FMENU_PAGES.size}", color = Alp.Muted, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            val ids = FMENU_PAGES[page].second
            for (row in ids.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in row) {
                        val k = keys[id] ?: continue
                        RadioKey(k.label, Modifier.weight(1f).height(74.dp), sub = k.sub, labelSize = 17,
                            border = when (k.lit) { 1 -> Alp.GreenDim; 2 -> Alp.AmberDim; else -> Alp.KeyBorder },
                            subColor = when (k.lit) { 1 -> Alp.Green; 2 -> Alp.Amber; else -> Alp.Muted },
                            onLongClick = { onAssign(k) }, onClick = k.action)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioKey("‹ BACK", Modifier.weight(1f).height(52.dp), bg = Alp.Bg, labelSize = 17) { page = (page + FMENU_PAGES.size - 1) % FMENU_PAGES.size }
                RadioKey("FWD ›", Modifier.weight(1f).height(52.dp), bg = Alp.Bg, labelSize = 17) { page = (page + 1) % FMENU_PAGES.size }
            }
            Spacer(Modifier.weight(1f))
            Text("Touch a key to open it. Hold it to put it on one of the four touch keys below.", color = Alp.Muted, fontSize = 14.sp)
        }
    }
}

// ------------------------------------------------------------------ side menu

@Composable
private fun SideMenu(nav: Navigator, settings: Settings, close: () -> Unit) {
    val stations by AppState.stations.collectAsState()
    val msgs by AppState.messages.collectAsState()
    val ble by AppState.bleStatus.collectAsState()
    val running by AppState.running.collectAsState()
    val rotor by AppState.rotorLinkStatus.collectAsState()
    val rotorPos by AppState.rotorPos.collectAsState()
    val extra by AppState.extraStatus.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val dx by DxLink.state.collectAsState()
    val unread = Unread.count(msgs, settings)
    fun open(d: String) { nav.go(d); close() }
    fun tool(t: Tool) { nav.tool(t); close() }

    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(48.dp))
            Column {
                Text(buildAnnotated("APRS ", "LoRa+"), fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text((settings.callsign.ifEmpty { "NOCALL" }) + " · " + (if (Licence.isValid(settings)) "validated" else "listen only"),
                    fontSize = 14.sp, color = if (Licence.isValid(settings)) Alp.Green else Alp.Amber)
            }
        }
        MenuGroup("OPERATE") {
            MenuItem("Radio", active = nav.kind == "RADIO") { open("RADIO") }
            MenuItem("Map", active = nav.kind == "MAP") { open("MAP") }
            MenuItem("IBP beacons", active = nav.kind == "IBP", indent = true) { open("IBP") }
            MenuItem("RBN map", active = nav.kind == "RBN", indent = true) { open("RBN") }
            MenuItem("Stations", right = "${stations.size}", active = nav.kind == "STATIONS") { open("STATIONS") }
            MenuItem("Weather stations", right = "${stations.count { it.wx != null }}", active = nav.kind == "WX") { open("WX") }
            MenuItem("Messages", badge = unread, active = nav.kind == "MESSAGES") { open("MESSAGES") }
            MenuItem("DX cluster", right = if (dx.connected) "${dx.spots.size}" else "", active = nav.kind == "DX") { open("DX") }
            MenuItem("Log & QSL") { tool(Tool.LOG) }
        }
        MenuGroup("DEVICES") {
            MenuItem("Tracker", right = info?.board ?: if (running) ble else "off", lamp = running) { tool(Tool.TRACKER) }
            MenuItem("Rotor box", right = rotor, lamp = rotorPos != null) { tool(Tool.ROTOR) }
            MenuItem("Radio (CAT)", right = AppState.phoneCat.collectAsState().value?.takeIf { it.link }?.rig?.substringBefore(" ·") ?: "USB", lamp = AppState.phoneCat.collectAsState().value?.link == true) { tool(Tool.CAT) }
            MenuItem("Extra receivers", right = "${extra.size}", lamp = extra.isNotEmpty() && running) { tool(Tool.CONNECT) }
            MenuItem("Connect & settings") { tool(Tool.CONNECT) }
        }
        MenuGroup("RADIO TOOLS") {
            val grid = listOf(
                "Satellites" to Tool.SAT, "SDR" to Tool.SDR, "Digital modes" to Tool.MODES, "Spotting" to Tool.MODES, "CW key" to Tool.TRACKER,
                "DAPNET" to Tool.DAPNET, "Signal ID" to Tool.SIGID, "Sensors" to Tool.SENSORS, "Heard" to Tool.HEARD, "AIS ships" to Tool.AIS, "My vessel (AIS)" to Tool.VESSEL, "Sextant (celestial)" to Tool.ASTRO, "Aircraft (ADS-B)" to Tool.ADSB, "Beacons (SDR)" to Tool.BEACONS, "Balloons (HAB)" to Tool.HAB, "Official alerts" to Tool.ALERTS, "Weather fax · VOACAP" to Tool.WEFAX, "Compass · GPS" to null,
            )
            for (row in grid.chunked(2)) {
                Row {
                    for ((label, t) in row) {
                        Text(label, fontSize = 16.sp, color = Alp.Text, modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .clickable { if (t == null) open("DISP") else tool(t) }.padding(horizontal = 10.dp, vertical = 9.dp))
                    }
                }
            }
        }
        MenuGroup("APP") {
            MenuItem("Settings (theme, units, alerts)") { tool(Tool.SETTINGS) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, t) in listOf("Remote" to Tool.REMOTE, "Config" to Tool.CONFIG, "Firmware" to Tool.FIRMWARE, "About" to Tool.ABOUT)) {
                RadioKey(label, Modifier.weight(1f).height(44.dp), bg = Color.Transparent, labelSize = 14) { tool(t) }
            }
        }
    }
}

/** App settings (theme, units, emergency alerts): moved out of the side menu into their own screen (EA7JRS). */
@Composable
private fun AppSettingsScreen() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuGroup("THEME") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                for ((id, label) in ThemeMode.MODES) Chip(label, ThemeMode.mode == id, Modifier.weight(1f), height = 34.dp) { ThemeMode.set(settings, id) }
            }
            Text(when (ThemeMode.mode) {
                "light" -> "Light, for daylight."
                "system" -> "As Android's dark theme setting."
                "auto" -> "Light from sunrise to sunset at our position (tracker GNSS or locator), dark at night."
                else -> "Dark, for night and the car."
            }, fontSize = 12.sp, color = Alp.Muted, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        }
        MenuGroup("UNITS") {
            var units by remember { mutableStateOf(settings.units) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                listOf("METRIC", "NAUTICAL", "IMPERIAL").forEachIndexed { i, name ->
                    Chip(name, units == i, Modifier.weight(1f)) { units = i; settings.units = i }
                }
            }
            Text("Distances, speeds and temperatures: km and km/h, nautical miles and knots, or miles, mph and °F.",
                fontSize = 12.sp, color = Alp.Muted, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        }
        MenuGroup("EMERGENCY ALERTS") {
            var snd by remember { mutableStateOf(settings.alertSound) }
            var vib by remember { mutableStateOf(settings.alertVibrate) }
            var torch by remember { mutableStateOf(settings.alertTorch) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Chip("SOUND", snd, Modifier.weight(1f), color = Alp.Amber) { snd = !snd; settings.alertSound = snd }
                Chip("VIBRATE", vib, Modifier.weight(1f), color = Alp.Amber) { vib = !vib; settings.alertVibrate = vib }
                Chip("TORCH", torch, Modifier.weight(1f), color = Alp.Amber) { torch = !torch; settings.alertTorch = torch }
            }
            Text("The phone as a buzzer: alarm tone (sounds in silent mode, alarm volume), vibration and the torch flashing.",
                fontSize = 12.sp, color = Alp.Muted, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            // Android 14+: full-screen alerts over the lock screen only if the user allows them (Google grants them by default
            // only to calling and alarm apps); without it the alert is an ordinary notification
            var fsiOk by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                while (true) {
                    fsiOk = android.os.Build.VERSION.SDK_INT < 34 ||
                        ctx.getSystemService(android.app.NotificationManager::class.java)?.canUseFullScreenIntent() != false
                    kotlinx.coroutines.delay(2000)
                }
            }
            if (!fsiOk) RadioKey("ALLOW FULL-SCREEN ALERTS", Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).height(44.dp), labelSize = 14,
                color = Alp.Amber, border = Alp.Amber) {
                try {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { AppState.error.value = "Open Android Settings > Apps > APRS LoRa+ > Notifications to allow full-screen alerts" }
            }
        }
    }
}

private fun buildAnnotated(a: String, b: String) = androidx.compose.ui.text.buildAnnotatedString {
    append(a)
    pushStyle(androidx.compose.ui.text.SpanStyle(color = Alp.Green)); append(b); pop()
}

@Composable
private fun MenuGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(title, Modifier.padding(start = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun MenuItem(label: String, right: String = "", badge: Int = 0, lamp: Boolean? = null, active: Boolean = false, indent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp)).background(if (active) Alp.Key else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (lamp != null) Lamp(lamp)
        Text((if (indent) "   " else "") + label, fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) Alp.Green else Alp.Text,
            modifier = Modifier.weight(1f))
        if (right.isNotEmpty()) Text(right, fontSize = 14.sp, color = Alp.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
        if (badge > 0) Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(Alp.Amber), contentAlignment = Alignment.Center) {
            Text("$badge", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Alp.Bg)
        }
    }
}

// ------------------------------------------------------------------ the older screens, inside the new frame

@Composable
private fun ToolScreen(nav: Navigator, tool: Tool) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 8.dp)) { ScreenHeader(tool.title.uppercase(), onBack = { if (!nav.back()) nav.go("RADIO") }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tool) {
                Tool.TRACKER -> TrackerTab()
                Tool.SENSORS -> SensorsTab()
                Tool.HEARD -> HeardTab()
                Tool.SAT -> SatTab()
                Tool.ROTOR -> RotorTab()
                Tool.SDR -> SdrTab()
                Tool.MODES -> DigimodesTab()
                Tool.AIS -> AisTab()
                Tool.LOG -> LogTab { nav.tool(Tool.WEFAX) }
                Tool.SIGID -> SigIdTab()
                Tool.REMOTE -> RemoteTab()
                Tool.CONFIG -> ConfigTab()
                Tool.DAPNET -> DapnetTab()
                Tool.FIRMWARE -> FirmwareTab()
                Tool.CONNECT -> ConnectTab()
                Tool.ABOUT -> AboutTab()
                Tool.ALERTS -> AlertsTab()
                Tool.WEFAX -> WefaxTab()
                Tool.CAT -> CatTab()
                Tool.ADSB -> AdsbTab()
                Tool.BEACONS -> BeaconsTab { nav.go("MAP") }
                Tool.HAB -> HabTab { nav.go("MAP") }
                Tool.VESSEL -> VesselTab()
                Tool.ASTRO -> AstroTab()
                Tool.SETTINGS -> AppSettingsScreen()
            }
        }
    }
}

/** Unread messages: incoming ones newer than the last time their conversation was opened. */
object Unread {
    /** Bumped when a conversation is read, so that the counters on screen are drawn again. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)

    fun count(msgs: List<es.ea7jrs.aprslorap.core.ChatMessage>, settings: Settings): Int {
        @Suppress("UNUSED_VARIABLE") val v = version
        val seen = settings.msgSeen
        return msgs.count { !it.outgoing && it.timeMs > (seen[it.peer] ?: 0L) }
    }

    fun isNew(m: es.ea7jrs.aprslorap.core.ChatMessage, seen: Map<String, Long>) = !m.outgoing && m.timeMs > (seen[m.peer] ?: 0L)

    fun markRead(settings: Settings, peer: String) {
        settings.msgSeen = settings.msgSeen + (peer.uppercase() to System.currentTimeMillis())
        version++
    }
}
