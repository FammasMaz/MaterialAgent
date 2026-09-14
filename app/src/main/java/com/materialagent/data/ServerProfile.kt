package com.materialagent.data

import com.materialagent.core.AuthMode

/**
 * A saved gateway the user can switch between.
 *
 * [secretRef] is a key into the encrypted vault, never the credential itself —
 * this object is safe to serialise into preferences and to log.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authMode: AuthMode,
    val username: String = "",
    val profileName: String? = null,
    val lastConnectedAt: Long = 0L,
) {
    val isPasswordAuth: Boolean get() = authMode == AuthMode.PASSWORD
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Which palette the app wears. */
enum class PaletteMode { DYNAMIC, HERMES, HERMES_SKIN }

/** How much the app moves. */
enum class MotionLevel { FULL, REDUCED }

/** How much the app buzzes. */
enum class HapticLevel { OFF, SUBTLE, STANDARD, STRONG }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Dynamic colour is the default: it is what a fresh install wears, and what the
    // tray offers first. A device that cannot honour it falls back to Hermes (see
    // `effectivePalette`), and the tray hides the choice rather than offering one
    // that does nothing.
    val palette: PaletteMode = PaletteMode.DYNAMIC,
    val motionLevel: MotionLevel = MotionLevel.FULL,
    val hapticLevel: HapticLevel = HapticLevel.STANDARD,
    val showReasoning: Boolean = true,
    val showToolCalls: Boolean = true,
    val streamingHaptics: Boolean = true,
    val scrollHaptics: Boolean = true,
    // Automation runs arrive in look-alike bursts; folding them by default keeps
    // the inbox readable without hiding anything, since the group expands.
    val groupSessions: Boolean = true,
    val sendOnEnter: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    // The version the user asked not to be told about again. Null, not "": the
    // blank sentinel exists only on disk, where DataStore has no null.
    val skippedVersion: String? = null,
    val activeProfileId: String? = null,
)
