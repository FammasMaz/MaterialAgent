package com.materialagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font as ResourceFont

/*
 * Type for MaterialAgent.
 *
 * The bundled Roboto Flex variable font is driven *by axis*, not by weight
 * alone: the expressive display styles narrow the width axis (92) and open the
 * counter/terminal axes, which is what gives Material 3 Expressive headlines
 * their confident, slightly-condensed poster voice. Body text keeps the neutral
 * default axes so long agent output stays comfortable to read.
 *
 * Everything is offline — no downloadable-font dependency, no runtime fetch.
 */

private const val EXPRESSIVE_WIDTH = 92f
private const val EXPRESSIVE_XTRA = 560f
private const val EXPRESSIVE_YOPQ = 96f
private const val EXPRESSIVE_YTLC = 512f

@OptIn(ExperimentalTextApi::class)
private fun variable(
    weight: Int,
    expressive: Boolean = false,
) = ResourceFont(
    resId = com.materialagent.R.font.robotoflex_variable,
    weight = FontWeight(weight),
    variationSettings = if (expressive) {
        FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(EXPRESSIVE_WIDTH),
            FontVariation.Setting("XTRA", EXPRESSIVE_XTRA),
            FontVariation.Setting("YOPQ", EXPRESSIVE_YOPQ),
            FontVariation.Setting("YTLC", EXPRESSIVE_YTLC),
        )
    } else {
        FontVariation.Settings(FontVariation.weight(weight))
    },
)

/** Neutral voice: agent output, lists, sheets. */
val BodyFamily = FontFamily(
    variable(300), variable(400), variable(500), variable(600), variable(700),
)

/**
 * Expressive voice: screen titles and hero numerics. Narrower, tighter
 * counters, heavier — the M3E "poster" register.
 */
val DisplayFamily = FontFamily(
    variable(600, expressive = true),
    variable(700, expressive = true),
    variable(800, expressive = true),
)

private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

private fun display(
    size: Int,
    lineHeight: Int,
    tracking: Double,
    weight: FontWeight = FontWeight.ExtraBold,
) = TextStyle(
    fontFamily = DisplayFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = NoFontPadding,
)

private fun body(
    size: Int,
    lineHeight: Int,
    tracking: Double = 0.0,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = BodyFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = NoFontPadding,
)

val MaterialAgentTypography = Typography(
    // Display — the biggest hero moments, e.g. the connect screen wordmark.
    displayLarge = display(56, 60, -0.6),
    displayMedium = display(44, 50, -0.4),
    displaySmall = display(36, 42, -0.2),

    // Headline — screen titles and section openers.
    headlineLarge = display(32, 40, -0.2, FontWeight.ExtraBold),
    headlineMedium = display(28, 36, -0.1, FontWeight.Bold),
    headlineSmall = display(24, 32, 0.0, FontWeight.SemiBold),

    // Title — app bars, card headers, message headers.
    titleLarge = body(22, 28, 0.0, FontWeight.SemiBold),
    titleMedium = body(17, 24, 0.1, FontWeight.SemiBold),
    titleSmall = body(15, 20, 0.1, FontWeight.Medium),

    // Body — agent output and prose.
    bodyLarge = body(16, 25, 0.3),
    bodyMedium = body(14, 21, 0.25),
    bodySmall = body(12, 17, 0.35),

    // Label — buttons, chips, tool chips, metadata.
    labelLarge = body(15, 20, 0.1, FontWeight.SemiBold),
    labelMedium = body(12, 16, 0.5, FontWeight.SemiBold),
    labelSmall = body(11, 15, 0.6, FontWeight.SemiBold),
)

/** Monospace voice for code blocks, tool arguments and diffs. */
val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
    platformStyle = NoFontPadding,
)
