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
    val palette: PaletteMode = PaletteMode.DYNAMIC,
    val motionLevel: MotionLevel = MotionLevel.FULL,
    val hapticLevel: HapticLevel = HapticLevel.STANDARD,
    val showReasoning: Boolean = true,
    val showToolCalls: Boolean = true,
    val streamingHaptics: Boolean = true,
    val sendOnEnter: Boolean = false,
    val activeProfileId: String? = null,
)
