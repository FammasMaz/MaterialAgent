package com.materialagent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The branded palette: "Hermes gold", a warm gilded scheme with indigo for
 * support and violet for expressive contrast. Dynamic colour is the default on
 * Android 12+, so this is the fallback *and* the identity — when the wallpaper
 * palette is off (or the device is older) the app still looks deliberate rather
 * than like unthemed Material.
 *
 * Tones are lifted from the Material 3 tonal-palette structure (40/80/90/10 in
 * light, 80/30/30/90 in dark) so contrast ratios hold without hand-checking
 * every pair.
 */

// ── Light ───────────────────────────────────────────────────────────────────
private val Light = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    inversePrimary = Color(0xFFF5C24B),

    secondary = Color(0xFF4A5C92),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE1FF),
    onSecondaryContainer = Color(0xFF001945),

    tertiary = Color(0xFF6B4E7F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6D9F9),
    onTertiaryContainer = Color(0xFF2A0F33),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEEE0CE),
    onSurfaceVariant = Color(0xFF4F4539),

    surfaceDim = Color(0xFFE1D8CE),
    surfaceBright = Color(0xFFFFF8F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF2E9),
    surfaceContainer = Color(0xFFF5ECE3),
    surfaceContainerHigh = Color(0xFFF0E7DD),
    surfaceContainerHighest = Color(0xFFEAE1D7),

    outline = Color(0xFF817567),
    outlineVariant = Color(0xFFD3C4B4),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF34302A),
    inverseOnSurface = Color(0xFFF8EFE7),
)

// ── Dark ────────────────────────────────────────────────────────────────────
private val Dark = darkColorScheme(
    primary = Color(0xFFF5C24B),
    onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFDF9E),
    inversePrimary = Color(0xFF7A5900),

    secondary = Color(0xFFB4C5FF),
    onSecondary = Color(0xFF1A2E60),
    secondaryContainer = Color(0xFF324478),
    onSecondaryContainer = Color(0xFFDBE1FF),

    tertiary = Color(0xFFDBBDE0),
    onTertiary = Color(0xFF3F2845),
    tertiaryContainer = Color(0xFF573E5D),
    onTertiaryContainer = Color(0xFFF6D9F9),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF16130F),
    onBackground = Color(0xFFEAE1D9),
    surface = Color(0xFF16130F),
    onSurface = Color(0xFFEAE1D9),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),

    surfaceDim = Color(0xFF16130F),
    surfaceBright = Color(0xFF3D3832),
    surfaceContainerLowest = Color(0xFF110E0A),
    surfaceContainerLow = Color(0xFF1F1B16),
    surfaceContainer = Color(0xFF231F1A),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39332E),

    outline = Color(0xFF9B8F80),
    outlineVariant = Color(0xFF4F4539),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFEAE1D9),
    inverseOnSurface = Color(0xFF34302A),
)

val HermesLightScheme = Light
val HermesDarkScheme = Dark

/**
 * Builds a scheme from the server's own terminal skin (`gateway.ready`).
 *
 * Only the handful of skin keys that map cleanly onto Material roles are used;
 * the rest of the tonal structure stays derivative so contrast never breaks.
 * Returns null when the skin lacks usable colours.
 */
fun skinScheme(colors: Map<String, String>, dark: Boolean) = runCatching {
    val accent = colors["ui_accent"] ?: colors["banner_accent"]
    val warn = colors["ui_warn"]
    val ok = colors["ui_ok"]
    val parsedAccent = accent?.let(::parseHex) ?: return@runCatching null
    val parsedWarn = warn?.let(::parseHex)
    val parsedOk = ok?.let(::parseHex)

    if (dark) {
        Dark.copy(
            primary = parsedAccent,
            onPrimary = readableOn(parsedAccent),
            primaryContainer = parsedAccent.copy(alpha = 0.28f).compositeOver(Color(0xFF16130F)),
            onPrimaryContainer = parsedAccent,
            tertiary = parsedWarn ?: Dark.tertiary,
            secondary = parsedOk ?: Dark.secondary,
        )
    } else {
        Light.copy(
            primary = parsedAccent,
            onPrimary = readableOn(parsedAccent),
            primaryContainer = parsedAccent.copy(alpha = 0.24f).compositeOver(Color(0xFFFFF8F2)),
            onPrimaryContainer = parsedAccent,
            tertiary = parsedWarn ?: Light.tertiary,
            secondary = parsedOk ?: Light.secondary,
        )
    }
}.getOrNull()

private fun parseHex(value: String): Color? = runCatching {
    val hex = value.trim().removePrefix("#")
    when (hex.length) {
        6 -> Color(0xFF000000 or hex.toLong(16))
        8 -> Color(hex.toLong(16))
        else -> null
    }
}.getOrNull()

/** Picks black or white depending on which reads better on [background]. */
private fun readableOn(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) Color(0xFF1F1B16) else Color.White
}

private fun Color.compositeOver(background: Color): Color {
    val alpha = this.alpha
    return Color(
        red = this.red * alpha + background.red * (1 - alpha),
        green = this.green * alpha + background.green * (1 - alpha),
        blue = this.blue * alpha + background.blue * (1 - alpha),
        alpha = 1f,
    )
}
