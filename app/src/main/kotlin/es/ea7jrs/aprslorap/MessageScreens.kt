/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Aprs
import es.ea7jrs.aprslorap.core.ChatMessage
import es.ea7jrs.aprslorap.core.MessageStatus
import kotlinx.coroutines.launch

private data class StatusLook(val icon: String, val iconColor: Color, val text: String, val color: Color)

private fun look(m: ChatMessage, isNew: Boolean): StatusLook = if (!m.outgoing) {
    if (isNew) StatusLook("RX", Alp.Amber, "NEW", Alp.Amber) else StatusLook("RX", Alp.Muted, "read", Alp.Muted)
} else when (m.status) {
    MessageStatus.PENDING -> StatusLook("TX", Alp.Cyan, "sending · try ${m.attempts} of 3", Alp.Cyan)
    MessageStatus.ACKED -> StatusLook("TX", Alp.Green, if (m.msgNo == null) "sent (no ACK asked)" else "✓ ACK received", Alp.Green)
    MessageStatus.FAILED -> StatusLook("TX", Alp.RedSoft, "✗ no ACK (stopped)", Alp.RedSoft)
    MessageStatus.REJECTED -> StatusLook("TX", Alp.RedSoft, "✗ rejected", Alp.RedSoft)
    MessageStatus.RECEIVED -> StatusLook("TX", Alp.Muted, "", Alp.Muted)
}

@Composable
fun MessageListScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val msgs by AppState.messages.collectAsState()
    val pages by AppState.pages.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }
    var newTo by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_VARIABLE") val v = Unread.version
    val seen = settings.msgSeen
    val unread = Unread.count(msgs, settings)
    val shown = msgs.asReversed().filter { when (tab) { 1 -> !it.outgoing; 2 -> it.outgoing; else -> true } }
    val selected = shown.firstOrNull { it.id == sel }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("APRS MESSAGES", onBack = { if (!nav.back()) nav.go("RADIO") }) {
            if (unread > 0) Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(Alp.Amber), contentAlignment = Alignment.Center) {
                Text("$unread", fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Alp.Bg)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL", "RX", "TX", "PAGER").forEachIndexed { i, n -> Chip(n, i == tab) { tab = i } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (tab == 3) {
                if (pages.isEmpty()) Text("No DAPNET pages. The tracker receives them in DAPNET mode; send them from DAPNET.", color = Alp.Muted, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                    items(pages) { p ->
                        MsgRow("PGR", Alp.Cyan, "RIC ${p.page.ric}", Fmt.hhmm(p.atMs), p.page.text,
                            listOfNotNull(if (p.page.own) "for me" else null, "RSSI ${p.page.rssi}", if (p.page.corrupt) "errors" else null).joinToString(" · "),
                            Alp.Muted, false, bold = p.page.own) { nav.tool(Tool.DAPNET) }
                    }
                }
            } else {
                if (shown.isEmpty()) Text("No messages yet. NEW writes one.", color = Alp.Muted, modifier = Modifier.padding(16.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    items(shown, key = { it.id }) { m ->
                        val l = look(m, Unread.isNew(m, seen))
                        MsgRow(l.icon, l.iconColor, m.peer + if (m.outgoing) "  to" else "  from", Fmt.hhmm(m.timeMs), m.text, l.text, l.color, m.id == sel,
                            bold = l.text == "NEW") { if (m.id == sel) nav.go("THREAD:" + m.peer) else sel = m.id }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("RADIO") }
            RadioKey("TOP", Modifier.weight(1f).height(54.dp)) { scope.launch { listState.animateScrollToItem(0) }; sel = shown.firstOrNull()?.id }
            RadioKey("OPEN", Modifier.weight(1f).height(54.dp), enabled = selected != null, border = Alp.Amber, color = Alp.Amber) { selected?.let { nav.go("THREAD:" + it.peer) } }
            RadioKey("NEW", Modifier.weight(1f).height(54.dp), border = Alp.GreenDim, color = Alp.Green) { newTo = "" }
            RadioKey("TxCLR", Modifier.weight(1f).height(54.dp), sub = "stop retries", enabled = msgs.any { it.status == MessageStatus.PENDING }) {
                AppState.controller?.cancelMessages()
            }
        }
    }

    newTo?.let { to ->
        AlertDialog(
            onDismissRequest = { newTo = null },
            title = { Text("New message") },
            text = {
                OutlinedTextField(to, { newTo = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(9) }, label = { Text("To (callsign-SSID)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            },
            confirmButton = { TextButton(enabled = to.length >= 3, onClick = { newTo = null; nav.go("THREAD:$to") }) { Text("Write") } },
            dismissButton = { TextButton(onClick = { newTo = null }) { Text("Cancel") } },
            containerColor = Alp.Panel,
        )
    }
}

@Composable
private fun MsgRow(icon: String, iconColor: Color, title: String, time: String, text: String, status: String, statusColor: Color, selected: Boolean,
                   bold: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Alp.Selected else Color.Transparent)
            .border(2.dp, if (selected) Alp.Amber else Color.Transparent, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Alp.PanelBorder), contentAlignment = Alignment.Center) {
            Text(icon, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = iconColor)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = BarlowCondensed, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, fontSize = 17.sp, color = Alp.Text, modifier = Modifier.weight(1f))
                Text(time, fontFamily = BarlowCondensed, fontSize = 14.sp, color = Alp.Muted)
            }
            Text(text, fontSize = 14.sp, color = if (bold) Alp.Text else Alp.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status.isNotEmpty()) Text(status, fontFamily = BarlowCondensed, fontSize = 12.sp, color = statusColor)
        }
    }
}

private val QUICK = listOf("QSL 73", "QRV", "QRX 5", "On my way", "OK")

@Composable
fun ThreadScreen(nav: Navigator, peerIn: String) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val peer = peerIn.uppercase()
    val msgs by AppState.messages.collectAsState()
    val ble by AppState.bleStatus.collectAsState()
    val running by AppState.running.collectAsState()
    val all = rememberStationViews()
    val st = all.firstOrNull { it.call == peer }
    val conv = msgs.filter { it.peer == peer }
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(peer, conv.size) {
        Unread.markRead(settings, peer)
        if (conv.isNotEmpty()) listState.scrollToItem(conv.size - 1)
    }
    val validated = Licence.isValid(settings)
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val c = AppState.controller ?: run { AppState.error.value = "Start the link first (Connect)"; return }
        c.sendMessage(peer, t)
        draft = ""
    }
    val d = Own.to(st?.lat, st?.lon)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { if (!nav.back()) nav.go("MESSAGES") }, contentAlignment = Alignment.Center) {
                Text("‹", fontSize = 30.sp, color = Alp.Text, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).clickable(enabled = st != null) { nav.go("STATION:$peer") }) {
                Text(peer, fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Alp.Text)
                Text(listOfNotNull(d?.let { Fmt.km(it.first) + " · " + Fmt.deg(it.second) }, st?.let { "heard " + Fmt.ago(it.lastMs) }).joinToString(" · ").ifEmpty { "not heard yet" },
                    fontSize = 13.sp, color = Alp.Muted)
            }
            Text(if (running) "via tracker" else "link off", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = if (running) Alp.Green else Alp.Amber,
                modifier = Modifier.border(1.dp, if (running) Alp.GreenDim else Alp.AmberDim, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Alp.Panel).border(1.dp, Alp.PanelBorder, RoundedCornerShape(14.dp))) {
            if (conv.isEmpty()) Text("No messages with $peer yet.", color = Alp.Muted, modifier = Modifier.padding(16.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(conv, key = { it.id }) { m ->
                    val l = look(m, false)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                        Text(m.text, fontSize = 16.sp, color = Alp.Text, lineHeight = 21.sp,
                            modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(12.dp)).background(if (m.outgoing) Alp.GreenBg else Alp.Key)
                                .border(1.dp, if (m.outgoing) Alp.GreenDim else Alp.KeyBorder, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
                        Text(Fmt.hhmm(m.timeMs, utc = true) + "Z" + (if (m.outgoing && l.text.isNotEmpty()) " · " + l.text else "") + (m.msgNo?.let { " · #$it" } ?: ""),
                            fontFamily = BarlowCondensed, fontSize = 13.sp, color = if (m.outgoing) l.color else Alp.Muted)
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth(), border = if (draft.isNotEmpty()) Alp.Green else Alp.Cyan, padding = 10.dp) {
            BasicTextField(
                draft, { draft = it.filter { c -> c.code in 32..126 && c != '|' && c != '~' && c != '{' }.take(Aprs.MAX_MESSAGE_TEXT) },
                textStyle = TextStyle(fontFamily = Barlow, fontSize = 16.sp, color = Alp.Text), cursorBrush = SolidColor(Alp.Green),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) Text(if (validated) "Reply to $peer" else "Callsign not validated: the app only listens", color = Alp.Faint, fontSize = 16.sp)
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in QUICK.take(3)) Box(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Alp.KeyBorder, RoundedCornerShape(14.dp))
                    .clickable { draft = (if (draft.isEmpty()) q else "$draft $q").take(Aprs.MAX_MESSAGE_TEXT) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(q, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Alp.SubText)
                }
                Spacer(Modifier.weight(1f))
                Text("${draft.length} / ${Aprs.MAX_MESSAGE_TEXT}", fontFamily = BarlowCondensed, fontSize = 14.sp, color = if (draft.length > 60) Alp.Amber else Alp.Muted)
            }
        }
        Row(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioKey("BACK", Modifier.weight(1f).height(54.dp), color = Alp.RedSoft) { if (!nav.back()) nav.go("MESSAGES") }
            val last = conv.lastOrNull { it.outgoing }
            RadioKey("RE-TX", Modifier.weight(1f).height(54.dp), sub = "last", enabled = last != null && validated) { last?.let { send(it.text) } }
            RadioKey("POS", Modifier.weight(1f).height(54.dp), enabled = st != null) { nav.go("STATION:$peer") }
            RadioKey("GO TO", Modifier.weight(1f).height(54.dp), sub = "Maps", enabled = st?.lat != null, border = Alp.GreenDim, color = Alp.Green) {
                st?.let { s -> if (s.lat != null && s.lon != null) goToPos(ctx, s.lat, s.lon, s.call) }
            }
            RadioKey("SEND", Modifier.weight(1f).height(54.dp), enabled = draft.isNotBlank() && validated, labelSize = 17,
                bg = if (draft.isNotBlank() && validated) Alp.Green else Alp.Key, border = Alp.Green, color = if (draft.isNotBlank() && validated) Alp.Bg else Alp.Green) {
                send(draft)
            }
        }
        if (!running) Text("The link is stopped: messages go out when it is started (Connect). Tracker: $ble", fontSize = 12.sp, color = Alp.Amber)
    }
}
