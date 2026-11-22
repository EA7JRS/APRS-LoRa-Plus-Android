/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.Adif
import es.ea7jrs.aprslorap.core.Qso
import java.util.Locale

/** Log tab: contacts by hand or from CW, ADIF in (qFT8) and out, QSL services (QRZ.com, LoTW, eQSL, WRL). */
@Composable
fun LogTab(onCircuit: () -> Unit = {}) {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    val log by AppState.qsoLog.collectAsState()
    val status by AppState.logStatus.collectAsState()
    var editing by remember { mutableStateOf<Qso?>(null) }
    var services by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { LogStore.import(ctx, it) } }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { LogStore.saveTo(ctx, it) } }
    val draft by AppState.logDraft.collectAsState()
    androidx.compose.runtime.LaunchedEffect(draft) { draft?.let { editing = it; AppState.logDraft.value = null } }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item {
            Section("Log: ${log.size} contacts · confirmed LoTW ${log.count { it.lotwConfirmed }}, QRZ ${log.count { it.qrzConfirmed }}, eQSL ${log.count { it.eqslConfirmed }}")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { editing = newQso(s) }) { Text("New QSO") }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import ADIF") }
                OutlinedButton(onClick = { LogStore.share(ctx) }) { Text("Share ADIF") }
                OutlinedButton(onClick = { saver.launch("log.adi") }) { Text("Save ADIF") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { LogStore.uploadAll() }) { Text("Upload") }
                OutlinedButton(onClick = { LogStore.syncConfirmations() }) { Text("Confirmations") }
                TextButton(onClick = { services = !services }) { Text(if (services) "Hide services" else "Services") }
            }
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp)
            Text("qFT8: export its log as ADIF and import it here; to take this log to qFT8 use Share or Save ADIF and import it there. " +
                "Letters: Q = QRZ, L = LoTW, E = eQSL, W = WRL; small = sent, capital = confirmed.", fontSize = 11.sp)
        }
        if (services) item { ServicesSection(s) }
        editing?.let { q -> item(key = "edit") { QsoForm(s, q, onCircuit, onDone = { editing = null }) } }
        items(log, key = { it.id.ifEmpty { it.call + it.date + it.time } }) { q ->
            Column(Modifier.fillMaxWidth().clickable { editing = q }.padding(vertical = 4.dp)) {
                Text(String.format(Locale.US, "%s %s  %-10s %s %s  %s/%s", q.date.drop(2), q.time.take(4), q.call, q.freqMHz.ifEmpty { q.band },
                    q.modeLabel, q["RST_SENT"], q["RST_RCVD"]), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Row {
                    Text(listOfNotNull(q["NAME"].takeIf { it.isNotEmpty() }, q["GRIDSQUARE"].takeIf { it.isNotEmpty() }, q["COMMENT"].takeIf { it.isNotEmpty() })
                        .joinToString(" · "), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    val flags = listOf(
                        Triple("Q", q.qrzSent, q.qrzConfirmed), Triple("L", q.lotwSent, q.lotwConfirmed),
                        Triple("E", q.eqslSent, q.eqslConfirmed), Triple("W", q.wrlSent, false),
                    )
                    for ((l, sent, conf) in flags) Text(
                        if (conf) l else if (sent) l.lowercase() else "·", fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        color = if (conf) Alp.Green else Color.Unspecified, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

private fun newQso(s: Settings): Qso =
    Qso.create(LogStore.newId(), "", System.currentTimeMillis(), 0, "CW", "", "599", "599", s.callsign, Spotter.locator(), txPwr = s.logPower)

@Composable
private fun QsoForm(s: Settings, q0: Qso, onCircuit: () -> Unit, onDone: () -> Unit) {
    val isNew = AppState.qsoLog.value.none { it.id == q0.id }
    var call by remember(q0) { mutableStateOf(q0.call) }
    var date by remember(q0) { mutableStateOf(q0.date) }
    var time by remember(q0) { mutableStateOf(q0.time.take(4)) }
    var freq by remember(q0) { mutableStateOf(q0.freqMHz) }
    var label by remember(q0) { mutableStateOf(Adif.MODES.firstOrNull { it.second == (q0.mode to q0.submode) }?.first ?: q0.modeLabel) }
    var rstS by remember(q0) { mutableStateOf(q0["RST_SENT"]) }
    var rstR by remember(q0) { mutableStateOf(q0["RST_RCVD"]) }
    var name by remember(q0) { mutableStateOf(q0["NAME"]) }
    var grid by remember(q0) { mutableStateOf(q0["GRIDSQUARE"]) }
    var comment by remember(q0) { mutableStateOf(q0["COMMENT"]) }
    var confirmDelete by remember(q0) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Section(if (isNew) "New contact" else "Contact")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(call, { call = it.uppercase().trim() }, label = { Text("Call") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(freq, { freq = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("MHz") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(date, { date = it.filter(Char::isDigit).take(8) }, label = { Text("Date UTC") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(time, { time = it.filter(Char::isDigit).take(4) }, label = { Text("HHMM UTC") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val n = Qso.create("", "X", System.currentTimeMillis(), 0, "CW", "", "", "", "", "")
                date = n.date; time = n.time.take(4)
            }) { Text("Now") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((l, ms) in Adif.MODES) {
                val go = {
                    if (rstS.isEmpty() || rstS == Adif.defaultRst(Adif.MODES.firstOrNull { it.first == label }?.second?.first ?: "")) rstS = Adif.defaultRst(ms.first)
                    if (rstR.isEmpty() || rstR == Adif.defaultRst(Adif.MODES.firstOrNull { it.first == label }?.second?.first ?: "")) rstR = Adif.defaultRst(ms.first)
                    label = l
                }
                if (l == label) Button(onClick = go) { Text(l, fontSize = 11.sp) } else OutlinedButton(onClick = go) { Text(l, fontSize = 11.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(rstS, { rstS = it.take(8) }, label = { Text("RST sent") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(rstR, { rstR = it.take(8) }, label = { Text("RST rcvd") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(grid, { grid = it.trim().take(8) }, label = { Text("Locator") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(comment, { comment = it.take(120) }, label = { Text("Comment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                Spotter.trackerCwFreqHz()?.let { freq = String.format(Locale.US, "%.6f", it / 1e6) }
                label = "CW"; if (rstS.isEmpty() || rstS.startsWith("5") && rstS.length == 2) rstS = "599"; if (rstR.isEmpty() || rstR.startsWith("5") && rstR.length == 2) rstR = "599"
            }) { Text("Tracker CW") }
            OutlinedButton(onClick = { AppState.sdr.value.freqHz?.let { freq = String.format(Locale.US, "%.6f", it / 1e6) } }) { Text("Receiver freq") }
            Spotter.micDialHz()?.let { d -> OutlinedButton(onClick = { freq = String.format(Locale.US, "%.6f", d / 1e6) }) { Text("Radio dial") } }
            // the VOACAP prediction of the Wefax computer for this station (its locator if written, else its callsign)
            OutlinedButton(enabled = call.isNotBlank() || grid.isNotBlank(), onClick = {
                es.ea7jrs.aprslorap.core.Wefax.ask(call, grid, freq.replace(',', '.').toDoubleOrNull()?.let { it * 1000.0 })?.let { AppState.circuitAsk.value = it; onCircuit() }
            }) { Text("HF path") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val (mode, sub) = Adif.MODES.firstOrNull { it.first == label }?.second ?: (q0.mode to q0.submode)
                val f = freq.toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: 0L
                val m = LinkedHashMap(q0.fields)
                m["CALL"] = call; m["QSO_DATE"] = date; m["TIME_ON"] = time.padEnd(4, '0') + q0.time.drop(4).take(2).ifEmpty { "00" }
                if (f > 0) { m["FREQ"] = String.format(Locale.US, "%.6f", f / 1e6); Adif.band(f)?.let { m["BAND"] = it } }
                m["MODE"] = mode; if (sub.isNotEmpty()) m["SUBMODE"] = sub else m.remove("SUBMODE")
                m["RST_SENT"] = rstS; m["RST_RCVD"] = rstR
                for ((k, v) in listOf("NAME" to name, "GRIDSQUARE" to grid, "COMMENT" to comment)) if (v.isNotBlank()) m[k] = v.trim() else m.remove(k)
                if (call.isBlank() || date.length != 8) AppState.logStatus.value = "Call and date are needed"
                else {
                    val q = Qso(m)
                    if (isNew) LogStore.add(q) else LogStore.update(q)
                    onDone()
                }
            }) { Text(if (isNew) "Log it" else "Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
            if (!isNew) OutlinedButton(onClick = { if (confirmDelete) { LogStore.delete(q0.id); onDone() } else confirmDelete = true }) {
                Text(if (confirmDelete) "Sure? Delete" else "Delete")
            }
        }
        if (!isNew && (q0.lotwSent || q0.qrzSent || q0.eqslSent)) Text("Already sent: a change here is not sent again (edit it on the service too).", fontSize = 11.sp)
    }
}

@Composable
private fun ServicesSection(s: Settings) {
    val ctx = LocalContext.current
    val cert by AppState.lotwCert.collectAsState()
    var qrzKey by remember { mutableStateOf(s.qrzKey) }
    var p12Pass by remember { mutableStateOf(s.lotwP12Password) }
    var lotwUser by remember { mutableStateOf(s.lotwUser) }
    var lotwPass by remember { mutableStateOf(s.lotwPassword) }
    var cqz by remember { mutableStateOf(s.lotwCqz) }
    var ituz by remember { mutableStateOf(s.lotwItuz) }
    var iota by remember { mutableStateOf(s.lotwIota) }
    var eqslUser by remember { mutableStateOf(s.eqslUser) }
    var eqslPass by remember { mutableStateOf(s.eqslPassword) }
    var eqslNick by remember { mutableStateOf(s.eqslNick) }
    var wrl by remember { mutableStateOf(s.wrlUdp) }
    var wrlHost by remember { mutableStateOf(s.wrlHost.ifEmpty { s.spotUdpHost }) }
    var wrlPort by remember { mutableStateOf(s.wrlPort.toString()) }
    var power by remember { mutableStateOf(s.logPower) }
    val p12 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { LogStore.importCertificate(ctx, it, p12Pass) } }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Section("QSL services")
        if (!Licence.isValid(s)) Text("Uploads need the callsign validated (Connect tab).", fontSize = 11.sp)
        Text("QRZ.com logbook (API key from QRZ: Logbook > Settings; needs the XML subscription). World Radio League can also take the contacts from QRZ.", fontSize = 11.sp)
        OutlinedTextField(qrzKey, { qrzKey = it.trim() }, label = { Text("QRZ logbook API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("LoTW: the callsign certificate saved from TrustedQSL as .p12 (Callsign Certificates > Save). The app signs the contacts with it.", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(p12Pass, { p12Pass = it }, label = { Text(".p12 password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { p12.launch(arrayOf("*/*")) }) { Text(if (cert == null) "Load .p12" else "Replace") }
        }
        cert?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Certificate ${c.call}, DXCC ${c.dxcc}" + (c.notAfter?.let { " · valid to " + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) } ?: ""),
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { LogStore.removeCertificate() }) { Text("Remove") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(cqz, { cqz = it.filter(Char::isDigit).take(2) }, label = { Text("CQ zone") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(ituz, { ituz = it.filter(Char::isDigit).take(2) }, label = { Text("ITU zone") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(iota, { iota = it.uppercase().take(6) }, label = { Text("IOTA") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Text("Locator for LoTW, QRZ and eQSL: ${Spotter.locator().ifEmpty { "none (Modes > Spotting)" }}. Spain: CQ 14, ITU 37.", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(lotwUser, { lotwUser = it.trim() }, label = { Text("LoTW web user") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(lotwPass, { lotwPass = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        }

        Text("eQSL.cc", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(eqslUser, { eqslUser = it.trim().uppercase() }, label = { Text("eQSL user") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(eqslPass, { eqslPass = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(eqslNick, { eqslNick = it }, label = { Text("eQSL QTH nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("World Radio League app on a computer (WSJT-X UDP)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(wrl, { wrl = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(wrlHost, { wrlHost = it.trim() }, label = { Text("Computer IP") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(wrlPort, { wrlPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(90.dp))
        }
        Text("Each new contact goes to that app as WSJT-X sends it (\"QSO Logged\" and \"Logged ADIF\"); set the same port in WRL Integrations.", fontSize = 11.sp)
        OutlinedTextField(power, { power = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Power for new contacts (W)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            s.qrzKey = qrzKey; s.lotwP12Password = p12Pass; s.lotwUser = lotwUser; s.lotwPassword = lotwPass
            s.lotwCqz = cqz; s.lotwItuz = ituz; s.lotwIota = iota; s.eqslUser = eqslUser; s.eqslPassword = eqslPass; s.eqslNick = eqslNick
            s.wrlUdp = wrl; s.wrlHost = wrlHost; s.wrlPort = wrlPort.toIntOrNull() ?: 2237; s.logPower = power
            AppState.logStatus.value = "Services saved"
        }) { Text("Save services") }
    }
}
