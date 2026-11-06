/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.FirmwareSite

/** Firmware of the tracker: installed and published versions, update over Bluetooth or the tracker's WiFi, web installer. */
@Composable
fun FirmwareTab() {
    val ctx = LocalContext.current
    val ready by AppState.controlReady.collectAsState()
    val info by AppState.trackerInfo.collectAsState()
    val latest by AppState.fwLatest.collectAsState()
    val busy by AppState.fwBusy.collectAsState()
    val progress by AppState.fwProgress.collectAsState()
    val message by AppState.fwMessage.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val i = info

    LaunchedEffect(i?.board) { if (i != null && AppState.fwLatest.value == null) FirmwareUpdater.check(i.board) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Installed", fontWeight = FontWeight.Bold)
        if (!ready || i == null) {
            Text("Connect to the tracker to see its version (Connect tab). The web installer below works without the link.", fontSize = 13.sp)
        } else {
            Text("Version ${i.firmware}, board ${i.board}")
            Text(
                if (i.hasSecondSlot) "Second firmware slot: it can be updated from here, over Bluetooth."
                else "No second firmware slot: update it with the web installer (USB cable).",
                fontSize = 13.sp,
            )
        }

        Text("Published", fontWeight = FontWeight.Bold)
        val l = latest
        if (l != null) {
            val newer = i != null && FirmwareSite.isNewer(l.version, i.firmware)
            Text("Version ${l.version} (${l.size / 1024} KB)" + when {
                i == null -> ""
                newer -> " · newer than the installed one"
                FirmwareSite.isNewer(i.firmware, l.version) -> " · older than the installed one"
                else -> " · already installed"
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = i != null && !busy, onClick = { i?.let { FirmwareUpdater.check(it.board) } }) { Text("Check again") }
            OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FirmwareSite.RELEASES))) } catch (_: Exception) {}
            }) { Text("Release notes") }
        }

        if (i != null && i.hasSecondSlot) {
            Text("Update over Bluetooth", fontWeight = FontWeight.Bold)
            Text("The phone downloads the published firmware for this board with its own internet, checks it and sends it to the tracker " +
                "over Bluetooth (1-3 minutes). The tracker has no internet client of its own for this. " +
                "The tracker checks it again and only then switches to it; if anything fails it keeps the current one.", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ready && !busy && latest != null, onClick = { confirm = true }) { Text("Install ${latest?.version ?: ""}") }
                if (busy) OutlinedButton(onClick = { FirmwareUpdater.cancel() }) { Text("Cancel") }
            }
        }
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message, fontSize = 13.sp)

        Text("Web installer (USB cable)", fontWeight = FontWeight.Bold)
        Text("For every board, for the first installation or to recover a tracker. Connect the tracker to the phone with a USB OTG " +
            "cable, open the installer in Chrome and press \"Detect board\". It keeps the configuration.", fontSize = 13.sp)
        Button(onClick = { FirmwareUpdater.openInstaller(ctx) }) { Text("Open the web installer") }
    }

    if (confirm) {
        val l = latest
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Install ${l?.version} on the tracker?") },
            text = { Text("Keep the phone near the tracker and the tracker powered until it restarts.") },
            confirmButton = { TextButton(onClick = { confirm = false; FirmwareUpdater.startBle() }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
