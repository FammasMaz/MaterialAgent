package com.materialagent.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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

private val BaselineTypography = Typography(
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

/**
 * The app's ladder, plus Material 3 Expressive's 15 emphasized styles derived from
 * it rather than repeated, so the two can never drift.
 *
 * Expresssive adds the emphasized voice for *actions, selection states and counts* —
 * the places where text is already saying something its neighbours are not. Stock M3
 * swaps in a brand font for them; this app has a single variable font, so the honest
 * equivalent is one step of weight in the same family, which [heavier] takes care of.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val MaterialAgentTypography = BaselineTypography.copy(
    displayLargeEmphasized = BaselineTypography.displayLarge.heavier(),
    displayMediumEmphasized = BaselineTypography.displayMedium.heavier(),
    displaySmallEmphasized = BaselineTypography.displaySmall.heavier(),
    headlineLargeEmphasized = BaselineTypography.headlineLarge.heavier(),
    headlineMediumEmphasized = BaselineTypography.headlineMedium.heavier(),
    headlineSmallEmphasized = BaselineTypography.headlineSmall.heavier(),
    titleLargeEmphasized = BaselineTypography.titleLarge.heavier(),
    titleMediumEmphasized = BaselineTypography.titleMedium.heavier(),
    titleSmallEmphasized = BaselineTypography.titleSmall.heavier(),
    bodyLargeEmphasized = BaselineTypography.bodyLarge.heavier(),
    bodyMediumEmphasized = BaselineTypography.bodyMedium.heavier(),
    bodySmallEmphasized = BaselineTypography.bodySmall.heavier(),
    labelLargeEmphasized = BaselineTypography.labelLarge.heavier(),
    labelMediumEmphasized = BaselineTypography.labelMedium.heavier(),
    labelSmallEmphasized = BaselineTypography.labelSmall.heavier(),
)

/** One step of weight, keeping the family and every other property untouched. */
private fun TextStyle.heavier(): TextStyle = copy(
    fontWeight = FontWeight(((fontWeight ?: FontWeight.SemiBold).weight + 100).coerceAtMost(900)),
)

/**
 * Markdown heading styles, H1 → H3 (and everything below).
 *
 * Material's [Typography] has no display entry between `headlineSmall` (24sp) and
 * `headlineMedium` (28sp), and `titleLarge` sits in [BodyFamily] — so a document's
 * H1→H2 used to fall off the display voice onto the body voice mid-page, which
 * reads as a rendering mistake rather than a hierarchy. These three keep every
 * heading in [DisplayFamily] and step 24 → 21 → 18, the ladder the audit asked
 * for without inventing a second type scale.
 */
private val MarkdownHeadingStyles = listOf(
    display(24, 32, 0.0, FontWeight.SemiBold),
    display(21, 28, 0.0, FontWeight.SemiBold),
    display(18, 25, 0.0, FontWeight.SemiBold),
)

/** Heading style for a markdown `#` level; levels 3–6 share the smallest step. */
fun markdownHeadingStyle(level: Int): TextStyle =
    MarkdownHeadingStyles[(level - 1).coerceIn(0, MarkdownHeadingStyles.lastIndex)]

/** Monospace voice for code blocks, tool arguments and diffs. */
val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
    platformStyle = NoFontPadding,
)
