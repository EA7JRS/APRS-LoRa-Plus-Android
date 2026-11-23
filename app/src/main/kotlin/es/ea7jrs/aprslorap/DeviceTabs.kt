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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.FmQsy
import es.ea7jrs.aprslorap.core.HeardStation
import es.ea7jrs.aprslorap.core.Json
import es.ea7jrs.aprslorap.core.JsonEdit
import es.ea7jrs.aprslorap.core.TrackerAction
import es.ea7jrs.aprslorap.core.TrackerMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Screens that show and command what the tracker itself holds: its messages, stations it heard, its screen and menu, its configuration.

private fun msgState(s: Int) = when (s) {
    0 -> "new"; 1 -> "read"; 2 -> "sent"; 3 -> "waiting for ack"; 4 -> "acked"; 5 -> "failed"; else -> "?"
}

private fun ageText(s: Long) = when {
    s < 90 -> "${s} s"
    s < 5400 -> "${s / 60} min"
    s < 172_800 -> "${s / 3600} h"
    else -> "${s / 86_400} d"
}

private fun time(epoch: Long) =
    if (epoch > 1_000_000_000L) SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(epoch * 1000)) else ""

@Composable
private fun MessageRow(m: TrackerMessage) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            (if (m.incoming) "← " else "→ ") + m.call + "  ·  " + msgState(m.state) + (if (time(m.epoch).isNotEmpty()) "  ·  " + time(m.epoch) else ""),
            fontSize = 12.sp, fontWeight = if (m.incoming && m.state == 0) FontWeight.Bold else FontWeight.Normal,
        )
        Text(m.text)
    }
}

@Composable
private fun HeardRow(h: HeardStation) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${h.call}   ${h.symbol}  ${h.kind}", fontWeight = FontWeight.Medium)
        val parts = ArrayList<String>()
        parts.add("${ageText(h.ageS)} ago")
        parts.add("${h.packets} pkts")
        h.rssi?.let { parts.add("$it dBm") }
        h.snr?.let { parts.add(String.format(Locale.US, "SNR %.1f", it)) }
        if (h.lat != null && h.lon != null) parts.add(String.format(Locale.US, "%.4f, %.4f", h.lat, h.lon))
        h.altitudeM?.let { if (h.lat != null) parts.add("$it m") }
        h.device?.let { parts.add(it) }
        Text(parts.joinToString("  ·  "), fontSize = 12.sp)
        h.path?.let { Text("via $it", fontSize = 12.sp) }
        h.wx?.let { w ->
            val t = StringBuilder()
            for ((k, v) in w) t.append(k).append('=').append(v).append(' ')
            Text("WX " + t.toString().trim(), fontSize = 12.sp)
        }
        h.status?.let { Text(it, fontSize = 12.sp) }
        h.comment?.let { Text(it, fontSize = 12.sp) }
        // T-TWR: the station announces a voice frequency ("146.940MHz T100 -060"): QSY with the tracker's FM radio
        val info = AppState.trackerInfo.collectAsState().value
        val mhz = FmQsy.announcedMHz(h.call, h.comment)
        if (info?.hasFm == true && mhz != null && FmQsy.inBand(mhz, info.sa868?.uhf == true)) {
            OutlinedButton(onClick = { AppState.controller?.control("qsy", "c" to (h.comment ?: ""), "name" to h.call) }) {
                Text(String.format(Locale.US, "QSY %.3f MHz (FM)", mhz), fontSize = 12.sp)
            }
        }
    }
}

/** Stations heard by the tracker and the tracker's own APRS message log (with a form to send from it). */
@Composable
fun HeardTab() {
    val ready by AppState.controlReady.collectAsState()
    if (!ready) { NoControl(); return }
    val heard by AppState.heard.collectAsState()
    val heardCounts by AppState.heardCounts.collectAsState()
    val msgs by AppState.trackerMessages.collectAsState()
    val msgCounts by AppState.trackerMessageCounts.collectAsState()
    var to by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var ack by rememberSaveable { mutableStateOf(true) }
    val c = AppState.controller

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("Send a message from the tracker (APRS modes)")
            OutlinedTextField(to, { to = it.uppercase().take(9) }, label = { Text("To (callsign)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it.take(67) }, label = { Text("Text (${text.length}/67)") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ack, onCheckedChange = { ack = it })
                Text("Ask for ack", Modifier.weight(1f))
                Button(enabled = to.length >= 3 && text.isNotBlank(), onClick = { c?.control("msg", "to" to to, "t" to text.trim(), "ack" to ack); text = "" }) { Text("Send") }
            }
            Section("Tracker message log: ${msgCounts.first} (${msgCounts.second} unread)")
            OutlinedButton(onClick = { c?.control("msgs", "cnt" to 20) }) { Text("Refresh") }
        }
        items(msgs) { m -> MessageRow(m); HorizontalDivider() }
        item {
            Section("Stations heard: ${heardCounts.first}, packets: ${heardCounts.second}")
            OutlinedButton(onClick = { c?.control("heard", "cnt" to 12) }) { Text("Refresh") }
        }
        items(heard) { h -> HeardRow(h); HorizontalDivider() }
    }
}

/** The tracker's screen mirrored as text, page buttons and every menu option of the tracker. */
@Composable
fun RemoteTab() {
    val ready by AppState.controlReady.collectAsState()
    if (!ready) { NoControl(); return }
    val screen by AppState.screen.collectAsState()
    val pages by AppState.actions.collectAsState()
    var pending by remember { mutableStateOf<TrackerAction?>(null) }
    val c = AppState.controller

    DisposableEffect(Unit) {
        AppState.controller?.control("sub", "screen" to 1)
        AppState.controller?.control("screen")
        AppState.controller?.control("actlist")
        onDispose { AppState.controller?.control("sub", "screen" to 0) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("Tracker screen")
            val s = screen
            if (s == null) Text("Waiting for the tracker...") else {
                Text(s.title + (if (s.right.isNotEmpty()) "   " + s.right else ""), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                for (row in s.rows) Text(row.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                if (!s.displayOn) Text("(display off)", fontSize = 12.sp)
            }
            // With a firmware that has it, ◀ ▶ are the tracker's own button: the views of a page first, then the pages, skipping those
            // this mode does not have. Older firmware: page number ± 1 (it stops at a page the mode does not have; no views).
            val keys = AppState.trackerInfo.collectAsState().value?.hasRemoteKeys == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (keys) c?.control("screen", "k" to -1) else c?.control("screen", "p" to maxOf(0, (screen?.current ?: 1) - 1)) }) { Text("◀") }
                OutlinedButton(onClick = { if (keys) c?.control("screen", "k" to 1) else c?.control("screen", "p" to (screen?.current ?: 0) + 1) }) { Text("▶") }
                if (keys) OutlinedButton(onClick = { c?.control("screen", "k" to 0) }) { Text("Back") }
                OutlinedButton(onClick = { c?.control("screen", "p" to 0) }) { Text("First") }
            }
            if (!keys) Text("This firmware moves by page number only: update the tracker to browse the views of each page too.", fontSize = 11.sp)
            Text("The mirror shows the text of the last page drawn; graphics (compass, maps) are not mirrored.", fontSize = 11.sp)
        }
        for (p in pages) {
            item(key = "h${p.page}") { Section("${p.title}") }
            items(p.actions, key = { "${p.page}/${it.name}" }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = a.allowed) { pending = a }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.label.ifBlank { a.name }, Modifier.weight(1f), fontSize = 14.sp)
                    Text(if (a.allowed) "▶" else "—", fontSize = 12.sp)
                }
            }
        }
    }

    pending?.let { a ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(a.label.ifBlank { a.name }) },
            text = { Text("Run this option on the tracker? (${a.name})") },
            confirmButton = { TextButton(onClick = { c?.control("act", "a" to a.name); pending = null }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private fun display(v: Any?): String = when (v) {
    null -> ""
    is Double -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    else -> v.toString()
}

@Composable
private fun ConfigNode(name: String, path: List<Any>, value: Any?, epoch: Int, depth: Int, onSet: (List<Any>, Any?) -> Unit) {
    when (value) {
        is Map<*, *> -> {
            var open by remember(epoch, path.toString()) { mutableStateOf(false) }
            Text(
                (if (open) "▼ " else "▶ ") + name, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(start = (depth * 8).dp, top = 6.dp, bottom = 6.dp),
            )
            if (open) for ((k, v) in value) ConfigNode(k.toString(), path + k.toString(), v, epoch, depth + 1, onSet)
        }
        is List<*> -> {
            var open by remember(epoch, path.toString()) { mutableStateOf(false) }
            Text(
                (if (open) "▼ " else "▶ ") + name + " [${value.size}]", fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(start = (depth * 8).dp, top = 6.dp, bottom = 6.dp),
            )
            if (open) value.forEachIndexed { i, v -> ConfigNode("[$i]", path + i, v, epoch, depth + 1, onSet) }
        }
        is Boolean -> Row(Modifier.fillMaxWidth().padding(start = (depth * 8).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = value, onCheckedChange = { onSet(path, it) })
        }
        else -> {
            var text by remember(epoch, path.toString()) { mutableStateOf(display(value)) }
            val secret = value is String && JsonEdit.isSecret(name)
            OutlinedTextField(
                value = text,
                onValueChange = { t ->
                    text = t
                    val typed = JsonEdit.typed(value, t)
                    if (typed != null) onSet(path, typed)
                },
                label = { Text(name) },
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().padding(start = (depth * 8).dp),
            )
        }
    }
}

/** Result of the configuration check: counters, what was found with its safe fix, and what passed. */
@Composable
private fun ConfigCheckPanel(res: es.ea7jrs.aprslorap.core.ConfigCheck.Result, fixed: Map<String, String>, showPassed: Boolean, togglePassed: () -> Unit,
                             applyFixes: (List<es.ea7jrs.aprslorap.core.ConfigCheck.Finding>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Configuration check", fontWeight = FontWeight.Bold)
            Text("${res.errors} errors", fontSize = 13.sp, color = if (res.errors > 0) Alp.RedSoft else Alp.Muted)
            Text("${res.warnings} warnings", fontSize = 13.sp, color = if (res.warnings > 0) Alp.Amber else Alp.Muted)
            Text("${res.passed.size} passed", fontSize = 13.sp, color = Alp.Green)
        }
        val fixable = res.findings.filter { it.fix != null }
        if (fixable.size > 1) OutlinedButton(onClick = { applyFixes(fixable) }) { Text("Apply ${fixable.size} safe fixes") }
        if (res.findings.isEmpty()) Text("Nothing to fix in what the check looks at.", fontSize = 12.sp, color = Alp.SubText)
        for (f in res.findings) {
            val color = when (f.severity) { es.ea7jrs.aprslorap.core.ConfigCheck.Severity.ERROR -> Alp.RedSoft; es.ea7jrs.aprslorap.core.ConfigCheck.Severity.WARN -> Alp.Amber; else -> Alp.Cyan }
            Column(Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text((when (f.severity) { es.ea7jrs.aprslorap.core.ConfigCheck.Severity.ERROR -> "ERROR"; es.ea7jrs.aprslorap.core.ConfigCheck.Severity.WARN -> "WARNING"; else -> "NOTE" }) + " · " + f.where, fontSize = 11.sp, color = color)
                Text(f.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(f.why, fontSize = 12.sp, color = Alp.SubText)
                f.fix?.let { fix -> TextButton(onClick = { applyFixes(listOf(f)) }) { Text(fix.label) } }
            }
        }
        // what a fix put: a new password or PIN has to be read before it is stored
        for ((id, note) in fixed) if (res.findings.none { it.id == id }) Text("Fixed · $note", fontSize = 12.sp, color = Alp.Green)
        TextButton(onClick = togglePassed) { Text(if (showPassed) "Hide passed checks" else "Show passed checks") }
        if (showPassed) for (p in res.passed) Text("✓ $p", fontSize = 12.sp, color = Alp.SubText)
        Text("Same rules as Config check in the tracker's WebUI. Errors do not stop the store here, but it asks first.", fontSize = 11.sp, color = Alp.Muted)
    }
}

/** Every setting of the tracker (the same file the web interface edits): read it, change it, store it (the tracker restarts). */
@Composable
fun ConfigTab() {
    val ready by AppState.controlReady.collectAsState()
    if (!ready) { NoControl(); return }
    val text by AppState.config.collectAsState()
    val busy by AppState.configBusy.collectAsState()
    val message by AppState.configMessage.collectAsState()
    var epoch by remember { mutableStateOf(0) }
    var root by remember { mutableStateOf<Any?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    val c = AppState.controller
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val info by AppState.trackerInfo.collectAsState()

    // copy of the configuration in a file of the phone (or Drive...): what is on screen, edits included
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(Json.encode(root).toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("cannot write there")
            AppState.configMessage.value = "Configuration saved to the file (it holds your passwords: keep it safe)"
        } catch (e: Exception) { AppState.configMessage.value = "Not saved: ${e.message}" }
    }
    val opener = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read it")
            if (bytes.size > 200_000) throw IllegalStateException("too large for a tracker configuration")
            val parsed = Json.parse(String(bytes, Charsets.UTF_8).trim().removePrefix("\uFEFF"))
            if (parsed !is Map<*, *> || parsed.isEmpty()) throw IllegalStateException("not a tracker configuration (a JSON object was expected)")
            val now = root as? Map<*, *>
            val missing = now?.keys?.count { it !in parsed.keys } ?: 0
            root = parsed; dirty = true; epoch++
            AppState.configMessage.value = "File loaded (${parsed.size} groups" + (if (missing > 0) ", $missing of this tracker's groups are not in it: is it from another board or version?" else "") +
                "). Check it and press \"Store in tracker\"."
        } catch (e: Exception) { AppState.configMessage.value = "File not loaded: ${e.message}" }
    }

    @Suppress("UNCHECKED_CAST")
    val cfgMap = root as? Map<String, Any?>
    val cfgCheck = remember(root) { cfgMap?.let { try { es.ea7jrs.aprslorap.core.ConfigCheck.check(it) } catch (_: Exception) { null } } }
    var fixedNotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showPassed by remember { mutableStateOf(false) }

    // a new file from the tracker replaces what is being edited
    val loaded = remember(text) { text?.let { try { Json.parse(it) } catch (e: Exception) { null } } }
    DisposableEffect(loaded) {
        root = loaded; dirty = false; epoch++
        onDispose { }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy, onClick = { c?.configRead() }) { Text("Read from tracker") }
            Button(enabled = !busy && dirty, onClick = { askSave = true }) { Text("Store in tracker") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = root is Map<*, *>, onClick = {
                val board = (info?.board ?: "tracker").replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').ifEmpty { "tracker" }
                val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                saver.launch("tracker_conf_${board}_$day.json")
            }) { Text("Save to a file") }
            OutlinedButton(enabled = !busy, onClick = { opener.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Load a file") }
        }
        if (message.isNotEmpty()) Text(message, fontSize = 12.sp)
        Text(
            "Same settings as the web interface. Passwords are hidden on screen but travel over the paired, encrypted link. " +
                "Storing replaces the whole file and restarts the tracker; anything out of range is replaced by its default at start-up. " +
                "A saved file is the whole configuration, passwords included, as a copy or to bring it back later.",
            fontSize = 11.sp,
        )
        HorizontalDivider()
        val r = root
        if (text != null && r !is Map<*, *>) Text("The file could not be read as JSON.")
        // configuration check: the same rules as the tracker's WebUI, on what is on screen
        cfgCheck?.let { res ->
            ConfigCheckPanel(res, fixedNotes, showPassed, { showPassed = !showPassed }) { fixes ->
                var m = cfgMap ?: return@ConfigCheckPanel
                val notes = HashMap(fixedNotes)
                for (f in fixes) {
                    val fix = f.fix ?: continue
                    val (next, value) = es.ea7jrs.aprslorap.core.ConfigCheck.apply(m, fix)
                    m = next
                    notes[f.id] = when (fix.random) {
                        "password" -> "New WiFi AP password: $value. Write it down before storing."
                        "pin" -> "New control PIN: $value. After storing: Connect > Forget pairing, and pair again with it."
                        else -> fix.label.replace(Regex("^Set |^Use "), "Set to ") + "."
                    }
                }
                fixedNotes = notes
                root = m; dirty = true; epoch++
            }
            HorizontalDivider()
        }
        if (r is Map<*, *>) {
            for ((k, v) in r) {
                ConfigNode(k.toString(), listOf(k.toString()), v, epoch, 0) { path, value ->
                    root = JsonEdit.set(root, path, value)
                    dirty = true
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(if ((cfgCheck?.errors ?: 0) > 0) "The check found ${cfgCheck?.errors} error" + (if (cfgCheck?.errors == 1) "" else "s") else "Store the configuration?") },
            text = { Text((if ((cfgCheck?.errors ?: 0) > 0) "Fix what the configuration check lists in red first, or store it as it is. " else "") +
                "The tracker keeps these settings and restarts; the link comes back by itself.") },
            confirmButton = { TextButton(onClick = { c?.configWrite(Json.encode(root)); askSave = false; dirty = false }) { Text(if ((cfgCheck?.errors ?: 0) > 0) "Store anyway" else "Store") } },
            dismissButton = { TextButton(onClick = { askSave = false }) { Text("Cancel") } },
        )
    }
}
