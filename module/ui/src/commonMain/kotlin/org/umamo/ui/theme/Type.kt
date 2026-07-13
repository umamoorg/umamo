package org.umamo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.inter_regular
import org.umamo.ui.resources.noto_sans_cjk_jp_regular

/*
 * The custom type scale. The 15 role names mirror the familiar scale (displayLarge … labelSmall) so call
 * sites only swap the accessor (MaterialTheme.typography.X → LocalUmamoTypography.current.X). Inter for
 * Latin, Noto Sans CJK JP as the fallback for Japanese; Regular only (heavier weights synthesize).
 *
 * 独自の文字スケール。役割名は従来と同じにして移行を機械的にする。
 */

/**
 * The UI font family: Inter first, Noto Sans CJK JP second. Listing both in one family lets the text layout
 * fall back to Noto for any glyph Inter does not cover, so Japanese renders from the bundled font rather
 * than relying on the platform's chance system coverage. `@Composable` because the Compose-resources
 * [Font] loader resolves against the resource environment.
 *
 * @return FontFamily The composed Inter + Noto fallback family.
 */
@Composable
fun rememberUiFontFamily(): FontFamily =
	FontFamily(
		Font(Res.font.inter_regular, FontWeight.Normal),
		Font(Res.font.noto_sans_cjk_jp_regular, FontWeight.Normal),
	)

/**
 * The role-named text styles. Same 15 roles as the familiar scale, so migrating a call site is a one-token
 * change.
 */
data class UmamoTypography(
	val displayLarge: TextStyle,
	val displayMedium: TextStyle,
	val displaySmall: TextStyle,
	val headlineLarge: TextStyle,
	val headlineMedium: TextStyle,
	val headlineSmall: TextStyle,
	val titleLarge: TextStyle,
	val titleMedium: TextStyle,
	val titleSmall: TextStyle,
	val bodyLarge: TextStyle,
	val bodyMedium: TextStyle,
	val bodySmall: TextStyle,
	val labelLarge: TextStyle,
	val labelMedium: TextStyle,
	val labelSmall: TextStyle,
)

/**
 * Builds the type scale on [family]. Pure (no composition), so it backs both the composable [umamoTypography]
 * and the [LocalUmamoTypography] fallback. Sizes mirror the conventional scale; titles and labels use Medium
 * (synthesized from the bundled Regular).
 *
 * @param FontFamily family The family to apply to every style.
 * @return UmamoTypography The built scale.
 */
fun umamoTypographyWith(family: FontFamily): UmamoTypography {
	fun style(sizeSp: Int, lineSp: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
		TextStyle(fontFamily = family, fontSize = sizeSp.sp, lineHeight = lineSp.sp, fontWeight = weight)
	return UmamoTypography(
		displayLarge = style(57, 64),
		displayMedium = style(45, 52),
		displaySmall = style(36, 44),
		headlineLarge = style(32, 40),
		headlineMedium = style(28, 36),
		headlineSmall = style(24, 32),
		titleLarge = style(22, 28),
		titleMedium = style(16, 24, FontWeight.Medium),
		titleSmall = style(14, 20, FontWeight.Medium),
		bodyLarge = style(16, 24),
		bodyMedium = style(14, 20),
		bodySmall = style(12, 16),
		labelLarge = style(14, 20, FontWeight.Medium),
		labelMedium = style(12, 16, FontWeight.Medium),
		labelSmall = style(11, 16, FontWeight.Medium),
	)
}

/**
 * The type scale bound to the bundled Inter + Noto family.
 *
 * @return UmamoTypography The scale [UmamoTheme] provides.
 */
@Composable
fun umamoTypography(): UmamoTypography = umamoTypographyWith(rememberUiFontFamily())

/** The active type scale (defaults to the system family; [UmamoTheme] provides the Inter/Noto scale). */
val LocalUmamoTypography = staticCompositionLocalOf { umamoTypographyWith(FontFamily.Default) }
