/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PROJECT_URL = "https://github.com/EA7JRS/APRS-LoRa-Plus"

/** Third-party work the app uses or ports: name, author, licence, link. */
private val CREDITS = listOf(
    Triple("LoRa APRS Tracker firmware (base of the APRS LoRa+ tracker)", "CA2RXU, GPL-3.0", "https://github.com/richonguzman/LoRa_APRS_Tracker"),
    Triple("dsd-fme + mbelib (digital voice)", "lwvmobile (ISC, parts GPL-2.0), szechyjs (ISC)", "https://github.com/lwvmobile/dsd-fme"),
    Triple("ft8_lib (FT8 / FT4)", "Kārlis Goba, MIT (KISS FFT, BSD-3-Clause)", "https://github.com/kgoba/ft8_lib"),
    Triple("rtlsdr-wsprd (WSPR)", "K1JT, K9AN, Guenael Jouchet VA2GKA; GPL-3.0", "https://github.com/Guenael/rtlsdr-wsprd"),
    Triple("WSJT-X (FST4W decoder ported to C, UDP and PSK Reporter formats)", "Joe Taylor K1JT, Steven Franke K9AN and the WSJT-X team, GPL-3.0", "https://wsjt.sourceforge.io/"),
    Triple("fldigi (PSK, RTTY, CW and NAVTEX modems ported to Kotlin; NAVTEX station list)", "W1HKJ, F4ECW, AB1KW and contributors, GPL-3.0", "https://www.w1hkj.org/"),
    Triple("Artemis database (from sigidwiki.com)", "AresValley", "https://github.com/AresValley/Artemis"),
    Triple("TrustedQSL / TaffyQSL (LoTW signing format)", "ARRL; TaffyQSL GPL-3.0", "https://lotw.arrl.org/"),
    Triple("usb-serial-for-android", "mik3y, MIT", "https://github.com/mik3y/usb-serial-for-android"),
    Triple("Bouncy Castle", "Legion of the Bouncy Castle, MIT", "https://www.bouncycastle.org/"),
    Triple("aprs.fi APRS symbol set", "Heikki Hannikainen OH7LZB and the authors in its COPYRIGHT file (based on WA8LMF, G4IDE, KH4G)", "https://github.com/hessu/aprs-symbols"),
    Triple("osmdroid (map)", "osmdroid team, Apache-2.0", "https://github.com/osmdroid/osmdroid"),
    Triple("Map data and tiles", "© OpenStreetMap contributors (ODbL); IGN Spain (CC BY 4.0); OpenSeaMap (CC BY-SA); Esri (with your ArcGIS key)", "https://www.openstreetmap.org/copyright"),
    Triple("Country files (cty.dat; a copy comes with the app)", "Jim Reisert AD1C, permission notice in licenses/cty.dat_copyright.txt", "https://www.country-files.com/"),
    Triple("HYG star database v4.1 (Hipparcos), CC BY-SA 4.0", "David Nash, astronexus", "https://github.com/astronexus/HYG-Database"),
    Triple("LR-FHSS receiver (frame format as built by RadioLib's SX126x driver, after Semtech's SWDM001)", "Jan Gromeš, MIT; Semtech, Clear BSD", "https://github.com/jgromes/RadioLib"),
    Triple("ADS-B / Mode S decoder (message formats and worked examples of \"The 1090 MHz Riddle\"; pulse detection after dump1090)", "Junzi Sun (TU Delft); Salvatore Sanfilippo, BSD", "https://mode-s.org/1090mhz/"),
    Triple("OpenHamClock (ideas and feed formats of the HAM, SPACE and EVENTS map layers)", "OpenHamClock contributors, MIT", "https://github.com/accius/openhamclock"),
    Triple("CQ and ITU zone boundaries (hamradio-zones-geojson)", "HB9HIL, MIT", "https://github.com/HB9HIL/hamradio-zones-geojson"),
    Triple("Weather, space and event feeds", "NOAA (AWC, SWPC, NHC, NDBC) and NASA (EONET, GIBS): public domain", "https://www.noaa.gov/"),
    Triple("Barlow and Share Tech Mono fonts", "Jeremy Tribby (Barlow), Carrois Type Design (Share Tech); SIL Open Font License 1.1", "https://fonts.google.com/"),
)

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        AppState.error.value = "No browser found"
    }
}

/** About / info: version, copyright, project links and credits (same text as the tracker's WebUI). */
@Composable
fun AboutTab() {
    val ctx = LocalContext.current
    val (ver, code) = remember {
        try {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val c = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
            (p.versionName ?: "?") to c
        } catch (_: Exception) {
            "?" to 0L
        }
    }
    val link = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = "APRS LoRa+", modifier = Modifier.size(72.dp))
            Column {
                Text("APRS LoRa+", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Android $ver", fontSize = 14.sp)
            }
        }
        Text(
            "APRS LoRa+ for Android $ver ($code) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",
            fontSize = 12.sp,
        )
        Text("APRS LoRa+ · © 2026 Manuel - EA7JRS", fontWeight = FontWeight.Bold)
        Text("Licensed under the GNU General Public License, version 3 or later. This program comes with ABSOLUTELY NO WARRANTY.", fontSize = 12.sp)
        Text("APRS LoRa+ — EA7JRS: tracker firmware, Android app, rotor and gateway.")
        Text(
            "github.com/EA7JRS/APRS-LoRa-Plus",
            color = link,
            modifier = Modifier.clickable { openUrl(ctx, PROJECT_URL) },
        )
        Text("Same version number in every APRS LoRa+ project (tracker, rotor, app, gateway).", fontSize = 12.sp)
        HorizontalDivider()
        Text("Credits", fontWeight = FontWeight.Bold)
        CREDITS.forEach { (name, who, url) ->
            Column(Modifier.clickable { openUrl(ctx, url) }.padding(vertical = 2.dp)) {
                Text(name, color = link, fontSize = 14.sp)
                Text(who, fontSize = 12.sp)
            }
        }
        Text("Callsign check: RadioID.net. Repeaters: RepeaterBook. Paging: DAPNET (hampager.de).", fontSize = 12.sp)
    }
}
