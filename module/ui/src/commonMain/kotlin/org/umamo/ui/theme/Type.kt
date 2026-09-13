package org.umamo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource
import org.umamo.ui.l10n.LocalAppLocale
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.inter_regular
import org.umamo.ui.resources.noto_sans_cjk_jp_regular
import org.umamo.ui.resources.noto_sans_cjk_kr_regular

/*
 * The custom type scale. The 15 role names mirror the familiar scale (displayLarge … labelSmall) so call
 * sites only swap the accessor (MaterialTheme.typography.X → LocalUmamoTypography.current.X). Inter for
 * Latin, a Noto Sans CJK cut chosen by UI language for CJK; Regular only (heavier weights synthesize).
 */

/**
 * The UI font family: Inter first, then the Noto Sans CJK cut for the active UI language.  Pairing them
 * in one family lets the layout fall back to Noto for any glyph Inter lacks, so CJK renders from the
 * bundled font rather than the platform's chance system coverage.
 *
 * @return FontFamily The Inter + locale-appropriate Noto family.
 */
@Composable
fun rememberUiFontFamily(): FontFamily =
	FontFamily(
		Font(Res.font.inter_regular, FontWeight.Normal),
		Font(cjkFontFor(LocalAppLocale.current), FontWeight.Normal),
	)

/**
 * Picks the Noto Sans CJK cut for a UI language tag.  Pure (no composition), so the mapping is assertable
 * without a composition, the same split [umamoTypographyWith] makes for the type scale.
 *
 * @param String languageTag The active BCP-47 tag, bare ("ko") or regional ("ko-KR").
 * @return FontResource The cut to pair with Inter.
 */
internal fun cjkFontFor(languageTag: String): FontResource =
	// Match the language subtag alone, so a regional tag resolves like the bare one.
	when (languageTag.substringBefore('-').lowercase()) {
		"ko" -> Res.font.noto_sans_cjk_kr_regular
		else -> Res.font.noto_sans_cjk_jp_regular
	}

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