package com.materialagent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialagent.core.model.Skin
import com.materialagent.data.HapticLevel
import com.materialagent.data.MotionLevel
import com.materialagent.data.PaletteMode
import com.materialagent.data.ThemeMode

/** The live skin the server advertised, if any. */
val LocalSkin = staticCompositionLocalOf<Skin?> { null }

/**
 * Haptic intensity, readable from any composable so a control can buzz without
 * threading settings through its whole call chain.
 */
val LocalHapticLevel = staticCompositionLocalOf { HapticLevel.STANDARD }

/** Whether bouncy springs are allowed. */
val LocalMotionLevel = staticCompositionLocalOf { MotionLevel.FULL }

/** Whether the user wants to see the agent's reasoning blocks. */
val LocalShowReasoning = staticCompositionLocalOf { true }

/** Whether the user wants to see individual tool invocations. */
val LocalShowToolCalls = staticCompositionLocalOf { true }

/** Whether streaming output should tick the haptics engine. */
val LocalStreamingHaptics = staticCompositionLocalOf { true }

/**
 * Whether a scroll tick fires while lists move. Its own setting, separate from
 * [LocalStreamingHaptics]: a tick under the finger every few millimetres is a
 * matter of taste, and a user can reasonably want one and not the other.
 */
val LocalScrollHaptics = staticCompositionLocalOf { true }

/** Whether the composer's IME action key sends instead of adding a newline. */
val LocalSendOnEnter = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaterialAgentTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: PaletteMode = PaletteMode.DYNAMIC,
    motionLevel: MotionLevel = MotionLevel.FULL,
    hapticLevel: HapticLevel = HapticLevel.STANDARD,
    showReasoning: Boolean = true,
    showToolCalls: Boolean = true,
    streamingHaptics: Boolean = true,
    scrollHaptics: Boolean = true,
    sendOnEnter: Boolean = false,
    skin: Skin? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val scheme = resolveScheme(palette, dark, skin)

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        if (activity != null) {
            SideEffect {
                val window = activity.window
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalHapticLevel provides hapticLevel,
        LocalMotionLevel provides motionLevel,
        LocalShowReasoning provides showReasoning,
        LocalShowToolCalls provides showToolCalls,
        LocalStreamingHaptics provides streamingHaptics,
        LocalScrollHaptics provides scrollHaptics,
        LocalSendOnEnter provides sendOnEnter,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialAgentTypography,
            shapes = MaterialAgentShapes,
            motionScheme = if (motionLevel == MotionLevel.REDUCED) {
                // The theme's own motion scheme drives every Material component
                // (switches, buttons, progress, the connected tray). Choosing
                // "Reduced" used to leave all of them expressive, because only
                // the app's own springs were branched on the setting.
                MotionScheme.standard()
            } else {
                MotionScheme.expressive()
            },
            content = content,
        )
    }
}

@Composable
private fun resolveScheme(palette: PaletteMode, dark: Boolean, skin: Skin?): ColorScheme {
    val context = LocalContext.current
    return when (effectivePalette(palette, supportsDynamicColor(), skin != null)) {
        PaletteMode.DYNAMIC ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        PaletteMode.HERMES_SKIN ->
            skin?.let { skinScheme(it.colors, dark) } ?: hermesScheme(dark)

        PaletteMode.HERMES -> hermesScheme(dark)
    }
}

/**
 * The palette actually applied, given what this device and this server can honour.
 *
 * Dynamic colour needs API 31, and the server's skin needs a server that sent one,
 * so the stored preference and the live palette are not the same thing. Resolving
 * that in one place is what lets Settings highlight the palette in force: it used to
 * highlight the *stored* one, which meant a device without dynamic colour — or an
 * app with no skin — showed a choice the app was not wearing.
 *
 * One case it cannot see: a skin whose colours fail to parse still falls back to
 * Hermes, so a corrupt skin payload shows "Server" as active while rendering
 * Hermes. That is a malformed-payload path, not a capability gap.
 */
fun effectivePalette(
    palette: PaletteMode,
    dynamicSupported: Boolean,
    hasSkin: Boolean,
): PaletteMode = when {
    palette == PaletteMode.DYNAMIC && !dynamicSupported -> PaletteMode.HERMES
    palette == PaletteMode.HERMES_SKIN && !hasSkin -> PaletteMode.HERMES
    else -> palette
}

/**
 * The palettes this device can actually wear, in the order Settings offers them.
 *
 * Separate from [effectivePalette] on purpose: that one answers "what am I wearing",
 * this one answers "what may I choose", and the Settings tray needs both. Picking a
 * palette it must not offer is how the tray used to present "Dynamic" on a device
 * that cannot honour it — a control that silently does nothing.
 */
fun availablePalettes(dynamicSupported: Boolean, hasSkin: Boolean): List<PaletteMode> = buildList {
    if (dynamicSupported) add(PaletteMode.DYNAMIC)
    add(PaletteMode.HERMES)
    if (hasSkin) add(PaletteMode.HERMES_SKIN)
}

fun hermesScheme(dark: Boolean): ColorScheme = if (dark) HermesDarkScheme else HermesLightScheme

/** True when the platform can tint the app from the wallpaper. */
fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
