/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Colours of the app (the logo's palette: dark blue-grey, neon green, cyan; amber for what is active or selected). */
object Alp {
    val Bg = Color(0xFF131A22)
    val Scrim = Color(0xFF0B1016)
    val Panel = Color(0xFF18222D)
    val PanelBorder = Color(0xFF263443)
    val SubPanel = Color(0xFF161F29)
    val SubBorder = Color(0xFF222F3C)
    val Key = Color(0xFF1E2A36)
    val KeyBorder = Color(0xFF2E3E4F)
    val Selected = Color(0xFF202D3A)
    val Green = Color(0xFF3CE68A)
    val GreenDim = Color(0xFF2F8F5D)
    val GreenBg = Color(0xFF1D3A30)
    val Cyan = Color(0xFF28B5E1)
    val Amber = Color(0xFFFFB347)
    val AmberDim = Color(0xFFA8763A)
    val Red = Color(0xFFFF6B6B)
    val RedSoft = Color(0xFFFF8A80)
    val Text = Color(0xFFE8EEF3)
    val Muted = Color(0xFF9FB0BF)
    val Faint = Color(0xFF6F8293)
    val SubText = Color(0xFFC9D4DE)
    val SubMuted = Color(0xFF8FA3B6)
    val Off = Color(0xFF4A5D70)
    val SymbolBg = Color(0xFFD9E1E8)
}

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)
val TechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))

private fun Typography.withFont(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

/** Dark theme for the whole app; the older screens (Tracker, SAT, SDR...) get the same colours through Material 3. */
@Composable
fun AlpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Alp.Cyan, onPrimary = Alp.Bg, primaryContainer = Alp.Key, onPrimaryContainer = Alp.Text,
            secondary = Alp.Green, onSecondary = Alp.Bg, secondaryContainer = Alp.GreenBg, onSecondaryContainer = Alp.Green,
            tertiary = Alp.Amber, onTertiary = Alp.Bg,
            background = Alp.Bg, onBackground = Alp.Text, surface = Alp.Bg, onSurface = Alp.Text,
            surfaceVariant = Alp.Key, onSurfaceVariant = Alp.Muted, surfaceContainer = Alp.Panel, surfaceContainerHigh = Alp.Key,
            surfaceContainerHighest = Alp.Key, surfaceContainerLow = Alp.Panel, surfaceContainerLowest = Alp.Bg,
            outline = Color(0xFF6A7F94), outlineVariant = Alp.PanelBorder, error = Alp.RedSoft, onError = Alp.Bg,   // text field and switch borders visible on the dark background
        ),
        typography = Typography().withFont(Barlow),
    ) {
        // the content colour of the older screens (Config, Connect, Tracker...) comes from a Material surface: without one it
        // would be black, unreadable on the dark background
        androidx.compose.material3.Surface(color = Alp.Bg, contentColor = Alp.Text, content = content)
    }
}
