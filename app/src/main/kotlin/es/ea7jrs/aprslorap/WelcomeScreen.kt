/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.ea7jrs.aprslorap.core.CallsignCheck
import kotlinx.coroutines.delay

/** Colours of the logo: slate background, neon green, cyan. */
private val WelcomeBg = Color(0xFF131A22)
private val NeonGreen = Color(0xFF3CE68A)
private val Cyan = Color(0xFF28B5E1)
private val Soft = Color(0xFFB8C4CE)

/**
 * Welcome screen, dark. Callsign already validated: the logo full screen for 1.5 s, then the app. Not validated: the
 * logo in the top half and the callsign + DMR ID / CCS7 form; RadioID must have that ID for that callsign. "Listen only"
 * opens the app without validating (it then only listens, as before).
 */
@Composable
fun WelcomeScreen(onEnter: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val validated = remember { Licence.isValid(settings) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(900)) }

    if (validated) {
        LaunchedEffect(Unit) { delay(1500); onEnter() }
        Box(Modifier.fillMaxSize().background(WelcomeBg), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.welcome_logo), contentDescription = "APRS LoRa+",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(24.dp).alpha(fade.value))
        }
        return
    }

    var call by remember { mutableStateOf(settings.callsign.takeIf { CallsignCheck.base(it) != null } ?: "") }
    var dmrId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val canGo = !busy && CallsignCheck.base(call) != null && dmrId.length == 7

    fun go() {
        if (!canGo) return
        focus.clearFocus()
        busy = true; error = null
        settings.callsign = call
        Licence.validate(settings, dmrId.toLong()) { err ->
            // RadioID answers on its own thread: back to the main one for the screen
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                if (err == null) onEnter() else error = err
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeonGreen, unfocusedBorderColor = Cyan.copy(alpha = 0.6f),
        focusedLabelColor = NeonGreen, unfocusedLabelColor = Soft, cursorColor = NeonGreen,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    )

    Column(
        Modifier.fillMaxSize().background(WelcomeBg).imePadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.welcome_logo), contentDescription = "APRS LoRa+", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height((ctx.resources.configuration.screenHeightDp / 2).dp).alpha(fade.value))
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).alpha(fade.value), verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your callsign must have a DMR ID / CCS7 in RadioID.net: that is how the app knows it is a licensed amateur.",
                color = Soft, fontSize = 13.sp, textAlign = TextAlign.Center)
            OutlinedTextField(call, { call = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(9); error = null },
                label = { Text("Callsign") }, singleLine = true, colors = fieldColors, enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(dmrId, { dmrId = it.filter(Char::isDigit).take(7); error = null },
                label = { Text("DMR ID / CCS7") }, singleLine = true, colors = fieldColors, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { go() }),
                supportingText = { Text("7 digits", color = Soft) },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { go() }, enabled = canGo, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = WelcomeBg,
                    disabledContainerColor = NeonGreen.copy(alpha = 0.35f), disabledContentColor = WelcomeBg.copy(alpha = 0.7f))) {
                if (busy) CircularProgressIndicator(color = WelcomeBg, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                else Text("Validate and enter", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Center) }
            TextButton(onClick = onEnter, enabled = !busy) { Text("Listen only (no transmitting)", color = Cyan) }
        }
    }
}
