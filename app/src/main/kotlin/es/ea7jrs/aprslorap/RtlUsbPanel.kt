/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.RtlUsb
import es.ea7jrs.aprslorap.core.Sdr

/** What Android sees on the USB port and what is missing to use an RTL-SDR on this phone (127.0.0.1 = the driver app). */
@Composable
fun RtlUsbPanel() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { tick++ } }
        val f = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        } catch (_: Exception) {}
        onDispose { try { ctx.unregisterReceiver(r) } catch (_: Exception) {} }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
    val hostOk = ctx.packageManager.hasSystemFeature("android.hardware.usb.host")
    val devices = try { usb?.deviceList?.values?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
    val rtl = devices.firstOrNull { RtlUsb.find(it.vendorId, it.productId) != null }
    val driver = try {
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(Sdr.RtlTcp.DRIVER_PACKAGE, 0).versionName ?: "?"
    } catch (_: Exception) { null }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("USB on this phone", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (!hostOk) Text("✗ This phone does not offer USB host (OTG): an RTL-SDR cannot work here; use an rtl_tcp server on the network.", fontSize = 12.sp, color = Alp.RedSoft)
        when {
            devices.isEmpty() -> Text(
                "✗ Android sees no USB device. Check: OTG adapter or USB-C cable that carries data (not a charge-only one); the OTG switch in Settings " +
                    "(Samsung, Xiaomi, OPPO / realme turn it off after a few minutes); try the dongle without hubs. An RTL-SDR Blog V4 draws about 300 mA.",
                fontSize = 12.sp, color = Alp.RedSoft)
            rtl == null -> {
                Text("✗ No RTL-SDR among the USB devices Android sees:", fontSize = 12.sp, color = Alp.Amber)
                for (d in devices) Text("  · " + RtlUsb.id(d.vendorId, d.productId) + "  " + listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifEmpty { "unknown device" },
                    fontSize = 12.sp)
                Text("If it is an RTL2832U with another identifier, tell me the number above.", fontSize = 12.sp, color = Alp.Muted)
            }
            else -> {
                val k = RtlUsb.find(rtl.vendorId, rtl.productId)!!
                Text("✓ RTL-SDR seen: " + RtlUsb.id(rtl.vendorId, rtl.productId) + " " + k.name +
                    (listOfNotNull(rtl.manufacturerName, rtl.productName).joinToString(" ").let { if (it.isEmpty()) "" else " · $it" }), fontSize = 12.sp, color = Alp.Green)
            }
        }
        if (driver == null) {
            Text("✗ The \"RTL-SDR driver\" app (${Sdr.RtlTcp.DRIVER_PACKAGE}) is not installed: it is what opens the dongle for this app.", fontSize = 12.sp, color = Alp.RedSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { open(ctx, "market://details?id=" + Sdr.RtlTcp.DRIVER_PACKAGE, "https://play.google.com/store/apps/details?id=" + Sdr.RtlTcp.DRIVER_PACKAGE) }) { Text("Google Play", fontSize = 12.sp) }
                OutlinedButton(onClick = { open(ctx, "https://f-droid.org/packages/" + Sdr.RtlTcp.DRIVER_PACKAGE + "/", null) }) { Text("F-Droid", fontSize = 12.sp) }
            }
        } else {
            Text("✓ RTL-SDR driver installed (version $driver).", fontSize = 12.sp, color = Alp.Green)
            if (rtl != null) Text("Touch Connect: the driver asks for permission to use the dongle (tick \"always\"). If it says it cannot open it, unplug and plug it again.",
                fontSize = 12.sp, color = Alp.Muted)
        }
        OutlinedButton(onClick = { tick++ }) { Text("Check again", fontSize = 12.sp) }
    }
}

private fun open(ctx: Context, uri: String, fallback: String?) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { if (fallback != null) try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
}
