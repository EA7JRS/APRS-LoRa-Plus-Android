/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

// The public MarineTraffic page of the vessel inside the app (Manuel, 2026-11-15), to check the maritime call sign by hand:
// the person signs in on the page itself if they want (the session cookie stays for next time) and confirms what they see.
// The app never reads the page, fills the sign-in or keeps the password: MarineTraffic's terms (Kpler, clause 7) forbid
// automated access to the website; only its normal interface, used by the person, is allowed.

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarineTrafficPage(url: String, mmsi: Long, call: String, onConfirm: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()                     // keep the person's sign-in for next time
            web?.destroy()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("MarineTraffic · MMSI ${String.format("%09d", mmsi)}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Check that the call sign on the page is ${call.ifBlank { "—" }}. Sign in on the page if you need to; the app does not read it.",
                    fontSize = 12.sp)
            }
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true                   // the site needs it to show the vessel
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)   // its sign-in
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val s = request.url.scheme ?: return true
                                if (s == "http" || s == "https") return false   // stays in this page
                                try { c.startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: ActivityNotFoundException) {}
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        loadUrl(url)
                        web = this
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onConfirm(); onClose() }) { Text("Call sign matches", fontSize = 13.sp) }
                OutlinedButton(onClick = {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: url))) } catch (e: ActivityNotFoundException) {}
                }, modifier = Modifier.padding(start = 8.dp)) { Text("Browser", fontSize = 13.sp) }
                OutlinedButton(onClick = onClose, modifier = Modifier.padding(start = 8.dp)) { Text("Close", fontSize = 13.sp) }
            }
        }
    }
}
