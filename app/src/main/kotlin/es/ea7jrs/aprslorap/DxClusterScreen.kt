/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.CallsignCheck
import es.ea7jrs.aprslorap.core.Cty
import es.ea7jrs.aprslorap.core.DxCluster
import es.ea7jrs.aprslorap.core.Flags
import java.util.Locale

@Composable
fun DxClusterScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val st by DxLink.state.collectAsState()
    var nodes by remember { mutableStateOf(DxCluster.DEFAULT_NODES + settings.dxNodes) }
    var nodeAddr by rememberSaveable { mutableStateOf(settings.dxNode) }
    val node = nodes.firstOrNull { it.address == nodeAddr } ?: nodes.first()
    var nodesOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var consoleOpen by remember { mutableStateOf(false) }
    var spotOpen by remember { mutableStateOf(false) }
    var origins by rememberSaveable { mutableStateOf(settings.dxOrigins) }
    var band by rememberSaveable { mutableStateOf(settings.dxBand) }
    var group by rememberSaveable { mutableStateOf(settings.dxGroup) }
    var act by rememberSaveable { mutableStateOf(settings.dxActivity) }
    var sdrOn by rememberSaveable { mutableStateOf(settings.dxSdr) }
    var catOn by rememberSaveable { mutableStateOf(settings.dxCat) }
    var auto by remember { mutableStateOf(DxLink.auto) }
    var lp by rememberSaveable { mutableStateOf(false) }
    var selKey by rememberSaveable { mutableStateOf<String?>(null) }
    val validated = Licence.isValid(settings)
    val ctyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val ok = try { ctx.contentResolver.openInputStream(it)?.use { s -> DxLink.importCty(ctx, s.readBytes()) } ?: false } catch (_: Exception) { false }
            if (ok) AppState.notice.value = "cty.dat loaded" else AppState.error.value = "Not a country file: choose cty.dat, CTY_WT.DAT, CTY_WT_MOD.DAT or the cty zip"
        }
    }

    val filter = DxCluster.Filter(
        origins = origins.split(',').mapNotNull { o -> DxCluster.Origin.entries.firstOrNull { it.name == o } }.toSet(),
        band = band, group = DxCluster.Group.entries.firstOrNull { it.name == group }, activity = act,
    )
    LaunchedEffect(filter, sdrOn, catOn, lp) {
        settings.dxOrigins = origins; settings.dxBand = band; settings.dxGroup = group; settings.dxActivity = act; settings.dxSdr = sdrOn; settings.dxCat = catOn
        DxLink.autoFilter = filter; DxLink.autoSdr = sdrOn; DxLink.autoCat = catOn; DxLink.autoLongPath = lp
    }
    LaunchedEffect(Unit) { DxLink.loadCty(ctx) }
    val spots = st.spots.filter { filter.passes(it) }
    val sel = spots.firstOrNull { it.key == selKey } ?: spots.firstOrNull()
    val me = Own.position()
    val path = sel?.let { Cty.path(DxLink.cty, it.dx, me?.first, me?.second) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenHeader("DX CLUSTER", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, if (st.connected) Alp.GreenDim else Alp.KeyBorder, RoundedCornerShape(16.dp))
                .clickable { nodesOpen = true }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Lamp(st.connected)
                Text(node.name + " ▾", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (st.connected) Alp.Green else Alp.Text)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Alp.KeyBorder, RoundedCornerShape(8.dp)).clickable { consoleOpen = true }
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { Text("⋯", fontSize = 18.sp, color = Alp.Text) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (o in DxCluster.Origin.entries) {
                val on = o.name in origins.split(',')
                Chip(o.label, on, Modifier.weight(1f), height = 32.dp) {
                    val set = origins.split(',').filter { it.isNotEmpty() }.toMutableSet()
                    if (on) set.remove(o.name) else set.add(o.name)
                    origins = set.joinToString(",")
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in DxCluster.BANDS) Chip(b.removeSuffix("m").ifEmpty { b }, b == band, height = 32.dp) { band = b }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("ALL", group.isEmpty(), height = 32.dp) { group = "" }
            for (g in DxCluster.Group.entries) Chip(g.label, group == g.name, height = 32.dp) { group = g.name }
            Spacer(Modifier.weight(1f))
            Toggle("ACT: $act", act != "ANY") { act = DxCluster.ACTIVITIES[(DxCluster.ACTIVITIES.indexOf(act) + 1) % DxCluster.ACTIVITIES.size] }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (spots.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    !validated -> "The cluster is entered with your callsign: validate it first (Connect)."
                    !st.connected -> "Not connected. Choose a node above (EA4URE is the URE cluster) and touch CONNECT."
                    st.spots.isEmpty() -> "Waiting for spots..."
                    else -> "No spot passes the filters."
                }, color = Alp.Muted)
                if (!st.connected) RadioKey("CONNECT", Modifier.fillMaxWidth().height(48.dp), enabled = validated, border = Alp.GreenDim, color = Alp.Green) {
                    DxLink.connect(ctx, node, CallsignCheck.base(settings.callsign) ?: settings.callsign)
                }
            }
            LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize().padding(4.dp)) {
                items(spots, key = { it.key + it.receivedMs }) { s ->
                    val on = s.key == sel?.key
                    val p = if (on) path else Cty.path(DxLink.cty, s.dx, me?.first, me?.second)
                    val mc = when (s.group) { DxCluster.Group.CW -> Alp.Cyan; DxCluster.Group.PHONE -> Alp.Amber; else -> Alp.Green }
                    Column(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Alp.Selected else Color.Transparent)
                        .border(2.dp, if (on) Alp.Amber else Color.Transparent, RoundedCornerShape(10.dp)).clickable { selKey = s.key }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(DxCluster.formatKhz(s.freqKhz), fontFamily = TechMono, fontSize = 17.sp, color = Alp.SubText, modifier = Modifier.width(92.dp), textAlign = TextAlign.End)
                            Text(s.mode, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = mc,
                                modifier = Modifier.border(1.dp, mc, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                            val flag = Flags.of(p?.entity)
                            if (flag.isNotEmpty()) Text(flag, fontSize = 15.sp)
                            Text(s.dx, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Alp.Text, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(s.utc, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
                            BearingArrow(p?.shortDeg?.toDouble(), Alp.Amber, 14.dp)
                            Text(p?.let { "${it.shortDeg}°" } ?: "", fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Text, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(p?.entity?.name ?: "", fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted, modifier = Modifier.width(92.dp), textAlign = TextAlign.End,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.tags.isNotEmpty()) Text(s.tags.joinToString(" "), fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Alp.Green)
                            Text(s.comment + " · de " + s.spotter, fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Panel(Modifier.fillMaxWidth(), border = if (sel != null) Alp.AmberDim else Alp.PanelBorder, padding = 10.dp) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sel?.dx ?: "—", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Alp.Text)
                Text(listOfNotNull(path?.entity?.let { (Flags.of(it) + " " + it.name).trim() }, path?.let { String.format(Locale.US, "%,d km", if (lp) it.longKm else it.shortKm) }).joinToString(" · "),
                    fontSize = 14.sp, color = Alp.Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sel?.let { DxCluster.formatKhz(it.freqKhz) } ?: "", fontFamily = TechMono, fontSize = 18.sp, color = Alp.Green)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip("SP " + (path?.shortDeg?.let { "$it°" } ?: "—"), !lp, color = Alp.Amber, height = 30.dp) { lp = false }
                Chip("LP " + (path?.longDeg?.let { "$it°" } ?: "—"), lp, color = Alp.Amber, height = 30.dp) { lp = true }
                Spacer(Modifier.weight(1f))
                Toggle("SDR", sdrOn) { sdrOn = !sdrOn }
                Toggle("CAT", catOn) { catOn = !catOn }
                Toggle("AUTO", auto) { auto = !auto; DxLink.auto = auto }
            }
            Text(st.status, fontSize = 13.sp, color = Alp.Muted, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (st.ctyStatus.isNotEmpty()) Text(st.ctyStatus, fontSize = 12.sp, color = if (st.ctyStatus.contains("failed") || st.ctyStatus.contains("not downloaded")) Alp.Amber else Alp.Muted,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey("TUNE", Modifier.weight(1f).height(54.dp), sub = "SDR · CAT", enabled = sel != null, border = Alp.GreenDim, color = Alp.Green) {
                sel?.let { DxLink.tune(it, sdrOn, catOn) }
            }
            RadioKey("ROTOR", Modifier.weight(1f).height(54.dp), sub = if (lp) "long path" else "short path", enabled = sel != null, border = Alp.AmberDim, color = Alp.Amber) {
                sel?.let { DxLink.aim(it, lp) }
            }
            RadioKey("LOG", Modifier.weight(1f).height(54.dp), enabled = sel != null) {
                sel?.let { AppState.logDraft.value = DxLink.logDraft(ctx, it); nav.tool(Tool.LOG) }
            }
            RadioKey("SPOT", Modifier.weight(1f).height(54.dp), sub = "send", enabled = st.connected && validated) { spotOpen = true }
        }
    }

    if (nodesOpen) AlertDialog(
        onDismissRequest = { nodesOpen = false },
        title = { Text("Cluster node · telnet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Login: " + (CallsignCheck.base(settings.callsign) ?: "—") + if (validated) "" else " (not validated: validate it in Connect)", fontSize = 13.sp, color = Alp.Muted)
                for (n in nodes) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (n.address == node.address) Alp.GreenBg else Alp.Key)
                    .border(1.dp, if (n.address == node.address) Alp.Green else Alp.KeyBorder, RoundedCornerShape(10.dp))
                    .clickable { nodeAddr = n.address; settings.dxNode = n.address }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Alp.Text)
                        Text(n.address, fontFamily = TechMono, fontSize = 13.sp, color = Alp.Muted)
                    }
                    Text(n.info, fontFamily = BarlowCondensed, fontSize = 13.sp, color = Alp.Muted)
                    if (settings.dxNodes.any { it.address == n.address }) TextButton(onClick = {
                        settings.dxNodes = settings.dxNodes.filter { it.address != n.address }; nodes = DxCluster.DEFAULT_NODES + settings.dxNodes
                    }) { Text("✕") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioKey("+ ADD NODE", Modifier.weight(1f).height(42.dp), labelSize = 14) { addOpen = true }
                    RadioKey("NODE LIST ↗", Modifier.weight(1f).height(42.dp), labelSize = 14, color = Alp.Cyan) {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DxCluster.NODE_LIST_URL))) } catch (_: Exception) {}
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioKey(if (st.connected) "DISCONNECT" else "CONNECT", Modifier.weight(1f).height(46.dp), enabled = validated || st.connected,
                        border = if (st.connected) Alp.AmberDim else Alp.GreenDim, color = if (st.connected) Alp.Amber else Alp.Green) {
                        if (st.connected) DxLink.disconnect() else DxLink.connect(ctx, node, CallsignCheck.base(settings.callsign) ?: settings.callsign)
                        nodesOpen = false
                    }
                    RadioKey("cty.dat FILE", Modifier.weight(1f).height(46.dp), labelSize = 14) { ctyPicker.launch(arrayOf("*/*")) }
                    RadioKey("cty.dat UPDATE", Modifier.weight(1f).height(46.dp), labelSize = 14, color = Alp.Cyan) { DxLink.updateCty(ctx) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { nodesOpen = false }) { Text("Close") } },
        containerColor = Alp.Panel,
    )

    if (addOpen) {
        var addr by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        val n = DxLink.nodeFrom(addr, name)
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Add a node") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(addr, { addr = it.trim() }, label = { Text("host:port (e.g. ea4ure.com:7300)") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = n != null, onClick = {
                    n?.let { settings.dxNodes = settings.dxNodes.filter { x -> x.address != it.address } + it; nodes = DxCluster.DEFAULT_NODES + settings.dxNodes; nodeAddr = it.address; settings.dxNode = it.address }
                    addOpen = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } },
            containerColor = Alp.Panel,
        )
    }

    if (consoleOpen) {
        var cmd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { consoleOpen = false },
            title = { Text("Console · ${node.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val lines = st.console.takeLast(60)
                    LazyColumn(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(8.dp)).background(Alp.Bg).padding(6.dp), reverseLayout = true) {
                        items(lines.asReversed()) { l -> Text(l, fontFamily = TechMono, fontSize = 11.sp, color = if (l.startsWith(">")) Alp.Cyan else Alp.SubText) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (q in listOf("sh/dx 30", "sh/wwv", "sh/filter", "sh/users")) Chip(q, false, height = 30.dp) { DxLink.send(q) }
                    }
                    OutlinedTextField(cmd, { cmd = it }, label = { Text("Command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = cmd.isNotBlank() && st.connected, onClick = { DxLink.send(cmd.trim()); cmd = "" }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { consoleOpen = false }) { Text("Close") } },
            containerColor = Alp.Panel,
        )
    }

    if (spotOpen) {
        val sdr by AppState.sdr.collectAsState()
        val dial = Rig.state?.freqHz ?: sdr.freqHz
        var freq by remember { mutableStateOf(dial?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: sel?.let { String.format(Locale.US, "%.1f", it.freqKhz) } ?: "") }
        var call by remember { mutableStateOf(sel?.dx ?: "") }
        var comment by remember { mutableStateOf("") }
        val khz = freq.replace(',', '.').toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { spotOpen = false },
            title = { Text("Send a spot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(freq, { freq = it }, label = { Text("kHz") }, singleLine = true)
                    OutlinedTextField(call, { call = it.uppercase().trim() }, label = { Text("DX callsign") }, singleLine = true)
                    OutlinedTextField(comment, { comment = it.take(30) }, label = { Text("Comment (30)") }, singleLine = true)
                    Text("Spots are seen by everybody on the cluster network: only real, worked or heard stations.", fontSize = 12.sp, color = Alp.Muted)
                }
            },
            confirmButton = {
                TextButton(enabled = khz != null && call.length >= 3, onClick = { DxLink.send(DxCluster.spotCommand(khz!!, call, comment)); spotOpen = false }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { spotOpen = false }) { Text("Cancel") } },
            containerColor = Alp.Panel,
        )
    }
}
