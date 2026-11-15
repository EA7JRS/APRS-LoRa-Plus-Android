/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import es.ea7jrs.aprslorap.core.Sun
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** One set of colours of the app. */
class Palette(
    val dark: Boolean,
    val bg: Long, val scrim: Long, val panel: Long, val panelBorder: Long, val subPanel: Long, val subBorder: Long, val key: Long, val keyBorder: Long,
    val selected: Long, val green: Long, val greenDim: Long, val greenBg: Long, val cyan: Long, val amber: Long, val amberDim: Long, val red: Long,
    val redSoft: Long, val text: Long, val muted: Long, val faint: Long, val subText: Long, val subMuted: Long, val off: Long, val symbolBg: Long,
    val outline: Long, val skyBg: Long, val bandB: Long,
    /** Background of band B: the panel with a touch of its colour, so the two bands are told apart at a glance. */
    val bandBBg: Long = subPanel,
)

/** Dark: the logo's palette (dark blue-grey, neon green, cyan; amber for what is active or selected). */
val DARK = Palette(
    true, 0xFF131A22, 0xFF0B1016, 0xFF18222D, 0xFF263443, 0xFF161F29, 0xFF222F3C, 0xFF1E2A36, 0xFF2E3E4F,
    0xFF202D3A, 0xFF3CE68A, 0xFF2F8F5D, 0xFF1D3A30, 0xFF28B5E1, 0xFFFFB347, 0xFFA8763A, 0xFFFF6B6B,
    0xFFFF8A80, 0xFFE8EEF3, 0xFF9FB0BF, 0xFF6F8293, 0xFFC9D4DE, 0xFF8FA3B6, 0xFF4A5D70, 0xFFD9E1E8,
    0xFF6A7F94, 0xFF000000, 0xFF28B5E1, 0xFF142230,          // band B in cyan (Manuel, 2026-11-15); band A is green
)

/** Light: the same hues, darkened enough to read on white in daylight. */
val LIGHT = Palette(
    false, 0xFFF2F5F8, 0xFF0B1016, 0xFFFFFFFF, 0xFFD3DCE5, 0xFFEEF2F6, 0xFFD8E0E8, 0xFFE7EDF3, 0xFFC0CCD8,
    0xFFE2EEF8, 0xFF0B9A52, 0xFF6CC79A, 0xFFDDF4E7, 0xFF0779AB, 0xFFB86200, 0xFFE0AE70, 0xFFD32F2F,
    0xFFC62828, 0xFF14202B, 0xFF52616F, 0xFF7E8D9B, 0xFF2C3A47, 0xFF56687A, 0xFFA7B4C0, 0xFFE9EEF3,
    0xFF7A8C9D, 0xFFFFFFFF, 0xFF0779AB, 0xFFE6F2F9,
)

/** Colours of the app; they follow the palette in use, so the screens redraw when the theme changes. */
object Alp {
    var pal: Palette by mutableStateOf(DARK)
    private fun c(v: Long) = Color(v)
    val dark: Boolean get() = pal.dark
    val Bg: Color get() = c(pal.bg)
    val Scrim: Color get() = c(pal.scrim)
    val Panel: Color get() = c(pal.panel)
    val PanelBorder: Color get() = c(pal.panelBorder)
    val SubPanel: Color get() = c(pal.subPanel)
    val SubBorder: Color get() = c(pal.subBorder)
    val Key: Color get() = c(pal.key)
    val KeyBorder: Color get() = c(pal.keyBorder)
    val Selected: Color get() = c(pal.selected)
    val Green: Color get() = c(pal.green)
    val GreenDim: Color get() = c(pal.greenDim)
    val GreenBg: Color get() = c(pal.greenBg)
    val Cyan: Color get() = c(pal.cyan)
    val Amber: Color get() = c(pal.amber)
    val AmberDim: Color get() = c(pal.amberDim)
    val Red: Color get() = c(pal.red)
    val RedSoft: Color get() = c(pal.redSoft)
    val Text: Color get() = c(pal.text)
    val Muted: Color get() = c(pal.muted)
    val Faint: Color get() = c(pal.faint)
    val SubText: Color get() = c(pal.subText)
    val SubMuted: Color get() = c(pal.subMuted)
    val Off: Color get() = c(pal.off)
    val SymbolBg: Color get() = c(pal.symbolBg)
    val Outline: Color get() = c(pal.outline)
    val SkyBg: Color get() = c(pal.skyBg)            // background of the GPS sky plot (black / white, as GNSS status apps)
    val BandB: Color get() = c(pal.bandB)
    val BandBBg: Color get() = c(pal.bandBBg)
}

/** Theme chosen by the user: dark, light, as Android (system) or automatic (light by day, dark by night at our position). */
object ThemeMode {
    val MODES = listOf("dark" to "DARK", "light" to "LIGHT", "system" to "SYSTEM", "auto" to "AUTO")
    var mode: String? by mutableStateOf(null)

    fun set(settings: Settings, m: String) { mode = m; settings.uiTheme = m }

    /** For AUTO: day at our position (tracker GNSS or locator); without a position, 07:00 to 20:00 local time. */
    fun isDayNow(nowMs: Long): Boolean {
        val pos = try { Own.position() } catch (_: Exception) { null }
        if (pos != null) return Sun.isDay(pos.first, pos.second, nowMs)
        val h = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.HOUR_OF_DAY)
        return h in 7..19
    }
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

private fun scheme(p: Palette) = if (p.dark) darkColorScheme(
    primary = Alp.Cyan, onPrimary = Alp.Bg, primaryContainer = Alp.Key, onPrimaryContainer = Alp.Text,
    secondary = Alp.Green, onSecondary = Alp.Bg, secondaryContainer = Alp.GreenBg, onSecondaryContainer = Alp.Green,
    tertiary = Alp.Amber, onTertiary = Alp.Bg,
    background = Alp.Bg, onBackground = Alp.Text, surface = Alp.Bg, onSurface = Alp.Text,
    surfaceVariant = Alp.Key, onSurfaceVariant = Alp.Muted, surfaceContainer = Alp.Panel, surfaceContainerHigh = Alp.Key,
    surfaceContainerHighest = Alp.Key, surfaceContainerLow = Alp.Panel, surfaceContainerLowest = Alp.Bg,
    outline = Alp.Outline, outlineVariant = Alp.PanelBorder, error = Alp.RedSoft, onError = Alp.Bg,   // text field and switch borders visible
) else lightColorScheme(
    primary = Alp.Cyan, onPrimary = Color.White, primaryContainer = Alp.Key, onPrimaryContainer = Alp.Text,
    secondary = Alp.Green, onSecondary = Color.White, secondaryContainer = Alp.GreenBg, onSecondaryContainer = Alp.Text,
    tertiary = Alp.Amber, onTertiary = Color.White,
    background = Alp.Bg, onBackground = Alp.Text, surface = Alp.Bg, onSurface = Alp.Text,
    surfaceVariant = Alp.Key, onSurfaceVariant = Alp.Muted, surfaceContainer = Alp.Panel, surfaceContainerHigh = Alp.Key,
    surfaceContainerHighest = Alp.Key, surfaceContainerLow = Alp.Panel, surfaceContainerLowest = Color.White,
    outline = Alp.Outline, outlineVariant = Alp.PanelBorder, error = Alp.RedSoft, onError = Color.White,
)

/**
 * Theme of the whole app (dark, light, system or automatic); the older screens (Tracker, SAT, SDR...) get the same colours
 * through Material 3. The status and navigation bar icons follow it.
 */
@Composable
fun AlpTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    if (ThemeMode.mode == null) ThemeMode.mode = settings.uiTheme
    val systemDark = isSystemInDarkTheme()
    val now = rememberNow(60_000)
    val dark = when (ThemeMode.mode) {
        "light" -> false
        "system" -> systemDark
        "auto" -> !ThemeMode.isDayNow(now)
        else -> true
    }
    val pal = if (dark) DARK else LIGHT
    if (Alp.pal !== pal) Alp.pal = pal
    val activity = ctx as? androidx.activity.ComponentActivity
    LaunchedEffect(dark) {
        val t = android.graphics.Color.TRANSPARENT
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
            navigationBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
        )
    }
    MaterialTheme(
        colorScheme = scheme(pal),
        typography = Typography().withFont(Barlow),
    ) {
        // the content colour of the older screens (Config, Connect, Tracker...) comes from a Material surface: without one it
        // would be black, unreadable on the dark background
        androidx.compose.material3.Surface(color = Alp.Bg, contentColor = Alp.Text, content = content)
    }
}
