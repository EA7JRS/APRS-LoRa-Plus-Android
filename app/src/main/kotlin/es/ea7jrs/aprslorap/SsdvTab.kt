/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 *
 * SSDV tool (OPERATE > SSDV image). Send an image to the tracker and have it transmit as SSDV packets: take a photo with the
 * phone, pick one from the gallery, or build an eQSL-style QSL card. View / save / share the images the tracker has received
 * (downloaded over BLE with the "ssdvrx" command). The tracker (RadioLib SSDVClient) does the SSDV packetisation and the RX
 * reconstruction; the app only hands it a baseline JPEG and downloads the received one.
 */
package es.ea7jrs.aprslorap

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import es.ea7jrs.aprslorap.core.Ssdv
import es.ea7jrs.aprslorap.core.TrackerMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** SSDV image mode tool: send a photo / gallery image / QSL card, and view / save / share the images the tracker received. */
@Composable
fun SsdvTab() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val c = AppState.controller
    val st by AppState.ssdvState.collectAsState()
    val upBusy by AppState.ssdvUploadBusy.collectAsState()
    val upMsg by AppState.ssdvUploadMessage.collectAsState()
    val rxBusy by AppState.ssdvRxBusy.collectAsState()
    val rxMsg by AppState.ssdvRxMessage.collectAsState()
    val rxImage by AppState.ssdvRxImage.collectAsState()
    val info by AppState.trackerInfo.collectAsState()

    var fmtIdx by rememberSaveable { mutableStateOf(0) }
    var quality by rememberSaveable { mutableStateOf(Ssdv.QUALITY_DEFAULT) }
    var txAfter by rememberSaveable { mutableStateOf(true) }
    var confirmMode by rememberSaveable { mutableStateOf(false) }
    var showQsl by rememberSaveable { mutableStateOf(false) }
    val format = Ssdv.FORMATS[fmtIdx.coerceIn(0, Ssdv.FORMATS.size - 1)]
    val validated = Licence.isValid(settings)

    fun sendBitmap(bmp: Bitmap?) {
        if (bmp == null) { AppState.error.value = "Cannot read the image"; return }
        val jpeg = SsdvImg.toJpeg(bmp, format, quality)
        if (jpeg == null || jpeg.isEmpty()) { AppState.error.value = "Cannot encode the image"; return }
        c?.ssdvImageWrite(jpeg, startAfter = txAfter && validated)
    }

    val camFile = remember { File(ctx.cacheDir, "share").apply { mkdirs() }.let { File(it, "ssdv_camera.jpg") } }
    val camUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", camFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) sendBitmap(SsdvImg.decodeFile(camFile.absolutePath, format))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendBitmap(SsdvImg.decodeUri(ctx, uri, format))
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("SSDV image")
            Text("Slow-scan digital video: the tracker sends a JPEG as SSDV packets over LoRa/FSK and reconstructs received ones. " +
                "Send a photo, a gallery image or a QSL card; the radio parameters and the image source are in Config, group \"ssdv\".", fontSize = 11.sp)

            val s = st
            if (s != null) {
                Field("Mode", if (s.inMode) (if (s.busy) "transmitting" else "ready") else "not in SSDV mode")
                if (s.totalPackets > 0) Field("Progress", "${s.sentPackets}/${s.totalPackets} packets")
                if (s.rxImages > 0 || s.rxPackets > 0) Field("Received", "${s.rxImages} image(s) · ${s.rxPackets} pkt in progress")
                if (s.status.isNotBlank()) Text(s.status, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            if (info?.hasSsdv != true) Text("This tracker's firmware has no SSDV mode.", fontSize = 12.sp, color = Alp.Amber)
            if (st?.inMode != true && info?.hasSsdv == true)
                OutlinedButton(onClick = { confirmMode = true }, modifier = Modifier.fillMaxWidth()) { Text("Switch the tracker to SSDV mode") }

            Section("Send an image")
            Text("Size (multiples of 16): a smaller image is fewer packets and a shorter transmission.", fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ssdv.FORMATS.forEachIndexed { i, f -> Chip(f.label, i == fmtIdx, height = 32.dp) { fmtIdx = i } }
            }
            Text("JPEG quality: ${Ssdv.qualityLabel(quality)}", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Slider(value = quality.toFloat(), onValueChange = { quality = it.toInt() },
                valueRange = Ssdv.QUALITY_MIN.toFloat()..Ssdv.QUALITY_MAX.toFloat(), steps = Ssdv.QUALITY_MAX - Ssdv.QUALITY_MIN - 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(txAfter, { txAfter = it }, enabled = validated)
                Text(if (validated) "transmit after upload" else "transmit after upload (needs a validated callsign)", fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { try { camera.launch(camUri) } catch (_: Exception) { AppState.error.value = "No camera app" } }, enabled = !upBusy) { Text("Take a photo") }
                OutlinedButton(onClick = { try { gallery.launch("image/*") } catch (_: Exception) { AppState.error.value = "No gallery app" } }, enabled = !upBusy) { Text("From gallery") }
                OutlinedButton(onClick = { showQsl = true }, enabled = !upBusy) { Text("QSL card") }
            }
            if (upMsg.isNotBlank()) Text(upMsg, fontSize = 12.sp)

            Section("Transmit")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { c?.ssdvTx(true) }, enabled = !upBusy && validated) { Text("Transmit") }
                OutlinedButton(onClick = { c?.ssdvTx(false) }) { Text("Stop") }
                OutlinedButton(onClick = { c?.ssdvStatus() }) { Text("Status") }
            }
            if (!validated) Text("Transmitting needs a validated callsign (App settings > User).", fontSize = 11.sp, color = Alp.Amber)

            Section("Received images")
            Text("Download the last image the tracker received, to view, save or share it.", fontSize = 11.sp)
            Button(onClick = { c?.ssdvRxDownload() }, enabled = !rxBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (rxBusy) "Downloading…" else "Download last received image")
            }
            if (rxMsg.isNotBlank()) Text(rxMsg, fontSize = 12.sp)
            val img = rxImage
            val bmp = remember(img) { img?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = "Received SSDV image", modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (SsdvImg.saveToGallery(ctx, img!!)) AppState.notice.value = "Saved to the gallery" else AppState.error.value = "Could not save" }) { Text("Save") }
                    OutlinedButton(onClick = { SsdvImg.share(ctx, img!!) }) { Text("Share") }
                }
            }
        }
    }

    if (confirmMode) AlertDialog(
        onDismissRequest = { confirmMode = false },
        title = { Text("Switch to SSDV mode") },
        text = { Text("The tracker restarts to change the radio mode. SSDV is a non-APRS mode: it stays until the next restart.") },
        confirmButton = { TextButton(onClick = { c?.control("mode", "m" to TrackerMode.SSDV); confirmMode = false }) { Text("Switch") } },
        dismissButton = { TextButton(onClick = { confirmMode = false }) { Text("Cancel") } },
    )

    if (showQsl) QslDialog(settings, onDismiss = { showQsl = false }) { toCall, rst, band, mode, comment ->
        showQsl = false
        sendBitmap(SsdvImg.qslCard(format, settings.callsign, toCall, rst, band, mode, comment))
    }
}

/** eQSL-style QSL card form: the contact details go onto a card the tracker transmits as SSDV. */
@Composable
private fun QslDialog(settings: Settings, onDismiss: () -> Unit, onSend: (toCall: String, rst: String, band: String, mode: String, comment: String) -> Unit) {
    var toCall by rememberSaveable { mutableStateOf("") }
    var rst by rememberSaveable { mutableStateOf("59") }
    var band by rememberSaveable { mutableStateOf("70cm") }
    var mode by rememberSaveable { mutableStateOf("SSDV") }
    var comment by rememberSaveable { mutableStateOf("73 and thanks for the QSO") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QSL card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A card from ${settings.callsign.ifEmpty { "your station" }} with today's UTC date and time.", fontSize = 12.sp)
                OutlinedTextField(toCall, { toCall = it.uppercase() }, label = { Text("To callsign") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(rst, { rst = it.take(4) }, label = { Text("RST") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(band, { band = it.take(8) }, label = { Text("Band") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(mode, { mode = it.take(8) }, label = { Text("Mode") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(comment, { comment = it.take(60) }, label = { Text("Comment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSend(toCall.trim(), rst.trim(), band.trim(), mode.trim(), comment.trim()) }) { Text("Build and send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Image helpers: decode, crop/scale to a valid SSDV size, JPEG-encode, the QSL card, and save / share. */
private object SsdvImg {
    fun toJpeg(src: Bitmap, fmt: Ssdv.Format, quality: Int): ByteArray? = try {
        val crop = Ssdv.centreCrop(src.width, src.height, fmt.w, fmt.h)
        val cropped = Bitmap.createBitmap(src, crop[0], crop[1], crop[2], crop[3])
        val scaled = Bitmap.createScaledBitmap(cropped, fmt.w, fmt.h, true)
        val jpegQ = 13 + quality.coerceIn(0, 7) * (100 - 13) / 7          // SSDV quality 0..7 -> JPEG 13..100
        java.io.ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, jpegQ, out); out.toByteArray() }
    } catch (_: Exception) { null }

    /** Decode a file, downsampling to roughly twice the target so the crop/scale is cheap and cannot run out of memory. */
    fun decodeFile(path: String, fmt: Ssdv.Format): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opt = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, fmt) }
        BitmapFactory.decodeFile(path, opt)
    } catch (_: Exception) { null }

    fun decodeUri(ctx: android.content.Context, uri: Uri, fmt: Ssdv.Format): Bitmap? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opt = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, fmt) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
    } catch (_: Exception) { null }

    private fun sampleSize(w: Int, h: Int, fmt: Ssdv.Format): Int {
        var s = 1
        while (w / (s * 2) >= fmt.w * 2 && h / (s * 2) >= fmt.h * 2) s *= 2
        return s
    }

    fun qslCard(fmt: Ssdv.Format, from: String, to: String, rst: String, band: String, mode: String, comment: String): Bitmap {
        val bmp = Bitmap.createBitmap(fmt.w, fmt.h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val w = fmt.w.toFloat(); val h = fmt.h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawColor(AColor.rgb(0x13, 0x1A, 0x22))                        // app background
        val band1 = h * 0.26f
        p.color = AColor.rgb(0x28, 0xB5, 0xE1); cv.drawRect(0f, 0f, w, band1, p)   // cyan header band
        p.color = AColor.rgb(0x3C, 0xE6, 0x8A); cv.drawRect(0f, band1, w, band1 + h * 0.012f, p)  // green rule
        p.color = AColor.WHITE; p.textSize = band1 * 0.5f; p.isFakeBoldText = true
        cv.drawText(from.ifEmpty { "QSL" }, w * 0.04f, band1 * 0.62f, p)
        p.textSize = band1 * 0.24f; p.isFakeBoldText = false
        cv.drawText("APRS LoRa+ SSDV", w * 0.04f, band1 * 0.9f, p)
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val lines = listOf(
            "To: ${to.ifEmpty { "----" }}",
            "Date: ${df.format(java.util.Date())}",
            "RST: ${rst.ifEmpty { "59" }}   Band: ${band.ifEmpty { "-" }}   Mode: ${mode.ifEmpty { "SSDV" }}",
            "Confirming our 2-way contact.",
            comment,
        )
        p.color = AColor.rgb(0xDF, 0xE6, 0xEC); p.textSize = (h * 0.072f)
        var y = band1 + h * 0.14f
        for (l in lines) { if (l.isNotBlank()) cv.drawText(l, w * 0.04f, y, p); y += h * 0.13f }
        p.color = AColor.rgb(0xFF, 0xB3, 0x47); p.textSize = h * 0.07f
        cv.drawText("73", w * 0.86f, h * 0.95f, p)
        return bmp
    }

    fun saveToGallery(ctx: android.content.Context, jpeg: ByteArray): Boolean = try {
        val name = "SSDV_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/APRS LoRa+")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }; true } else false
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "APRS LoRa+").apply { mkdirs() }
            File(dir, name).outputStream().use { it.write(jpeg) }
            true
        }
    } catch (_: Exception) { false }

    fun share(ctx: android.content.Context, jpeg: ByteArray) {
        try {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "ssdv_rx.jpg")
            f.outputStream().use { it.write(jpeg) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(send, "Share the SSDV image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { AppState.error.value = "Could not share the image" }
    }
}
