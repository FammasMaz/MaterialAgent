package com.materialagent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The MaterialAgent identity: "Hermes" — winged messenger, night sky, gilded
 * accents. Three families, each a real Material 3 tonal ramp:
 *
 *   primary   indigo        the mark, the brand, the thing users recognise
 *   secondary neutral-violet  the quiet support colour for surfaces and pills
 *   tertiary  gold          the accent that makes the app feel gilded, and the
 *                           colour the expressive FAB wears
 *
 * Tones follow the M3 structure (40/90/10 in light, 80/30/20 in dark) so
 * contrast holds without hand-checking every pair. Dynamic colour is available
 * but off by default: this scheme is the product's identity, not a fallback.
 */

// ── Light ───────────────────────────────────────────────────────────────────
private val Light = lightColorScheme(
    primary = Color(0xFF4F5488),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF070B47),
    inversePrimary = Color(0xFFBAC3FF),

    secondary = Color(0xFF5C5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),

    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF261A00),

    // M3 Expressive's "fixed" roles are tone-invariant: they keep their light
    // values in dark mode so an accent can stay put while everything around it
    // changes. `lightColorScheme` would otherwise leave them at the M3 baseline
    // purple, which is not this app's indigo at all.
    primaryFixed = Color(0xFFDFE0FF),
    primaryFixedDim = Color(0xFFBAC3FF),
    onPrimaryFixed = Color(0xFF070B47),
    onPrimaryFixedVariant = Color(0xFF383E71),
    secondaryFixed = Color(0xFFE1E0F9),
    secondaryFixedDim = Color(0xFFC5C4DD),
    onSecondaryFixed = Color(0xFF191A2C),
    onSecondaryFixedVariant = Color(0xFF444559),
    tertiaryFixed = Color(0xFFFFDF9E),
    tertiaryFixedDim = Color(0xFFF5C24B),
    onTertiaryFixed = Color(0xFF261A00),
    onTertiaryFixedVariant = Color(0xFF5C4300),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),

    surfaceDim = Color(0xFFDBD9E0),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),

    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2F0F7),
)

// ── Dark ────────────────────────────────────────────────────────────────────
private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF1E2461),
    primaryContainer = Color(0xFF353B73),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF4F5488),

    secondary = Color(0xFFC5C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),

    tertiary = Color(0xFFF5C24B),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9E),

    // Identical to the light scheme on purpose — see the note above.
    primaryFixed = Color(0xFFDFE0FF),
    primaryFixedDim = Color(0xFFBAC3FF),
    onPrimaryFixed = Color(0xFF070B47),
    onPrimaryFixedVariant = Color(0xFF383E71),
    secondaryFixed = Color(0xFFE1E0F9),
    secondaryFixedDim = Color(0xFFC5C4DD),
    onSecondaryFixed = Color(0xFF191A2C),
    onSecondaryFixedVariant = Color(0xFF444559),
    tertiaryFixed = Color(0xFFFFDF9E),
    tertiaryFixedDim = Color(0xFFF5C24B),
    onTertiaryFixed = Color(0xFF261A00),
    onTertiaryFixedVariant = Color(0xFF5C4300),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),

    surfaceDim = Color(0xFF131318),
    surfaceBright = Color(0xFF39383F),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292930),
    surfaceContainerHighest = Color(0xFF34343A),

    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
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
            primaryContainer = parsedAccent.copy(alpha = 0.28f).compositeOver(Dark.surface),
            onPrimaryContainer = parsedAccent,
            tertiary = parsedWarn ?: Dark.tertiary,
            secondary = parsedOk ?: Dark.secondary,
        )
    } else {
        Light.copy(
            primary = parsedAccent,
            onPrimary = readableOn(parsedAccent),
            primaryContainer = parsedAccent.copy(alpha = 0.24f).compositeOver(Light.surface),
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
    return if (luminance > 0.6f) Color(0xFF1B1B21) else Color.White
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
