package org.umamo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.LocalSettings

/** The settings key (and built-in fallback) for the UI theme mode: "dark" | "light" | "system". */
private const val THEME_SETTING_KEY = "interface.theme"
private const val DEFAULT_THEME_MODE = "dark"

/**
 * The active theme mode ("dark" | "light" | "system") for the composition. Mirrors [LocalAppLocale]: a
 * static local with a sensible default, so [UmamoTheme] is safe to use even where settings are never
 * provided (a preview, or a subtree mounted outside the settings scope) - it simply renders the default
 * mode there. Every app entrypoint feeds the real value in from settings via [ProvideAppThemeFromSettings].
 *
 * コンポジションで有効なテーマモード。設定が無い場面でも既定値で動くよう静的ローカルにしている。
 */
val LocalThemeMode = staticCompositionLocalOf { DEFAULT_THEME_MODE }

/**
 * The Umamo theme: resolves the palette (dark or light per [LocalThemeMode]) and provides the custom design
 * tokens - [LocalUmamoColors], [LocalUmamoTypography], [LocalUmamoShapes] - to the subtree. There is no
 * Material here; the kit widgets ([org.umamo.ui.kit]) read these locals directly. "system" follows the OS
 * via [isSystemInDarkTheme]; any unrecognized mode falls back to dark.
 *
 * Umamo のテーマ。LocalThemeMode に従って配色を選び、独自トークンをコンポジションローカルで供給する。
 *
 * @param Function content The UI to render under the theme.
 */
@Composable
fun UmamoTheme(content: @Composable () -> Unit) {
	val useDarkScheme =
		when (LocalThemeMode.current) {
			"light" -> false
			"system" -> isSystemInDarkTheme()
			else -> true
		}
	CompositionLocalProvider(
		LocalUmamoColors provides if (useDarkScheme) umamoDarkColors else umamoLightColors,
		LocalUmamoTypography provides umamoTypography(),
		LocalUmamoShapes provides umamoShapes,
		content = content,
	)
}

/**
 * Resolves the theme mode from [LocalSettings] and provides it as [LocalThemeMode], reactively: the initial
 * value is read once, and a collector on the settings `changes` flow updates it whenever `interface.theme`
 * is written, so an in-app theme change re-themes the running app. Place this where settings are available;
 * a subtree without settings simply skips it and uses the [LocalThemeMode] default.
 *
 * 設定からテーマモードを解決し LocalThemeMode に供給する。変更フローを購読して実行中に再配色する。
 *
 * @param Function content The subtree to render under the resolved theme mode.
 */
@Composable
fun ProvideAppThemeFromSettings(content: @Composable () -> Unit) {
	val settings = LocalSettings.current
	var themeMode by remember { mutableStateOf(settings.getString(THEME_SETTING_KEY) ?: DEFAULT_THEME_MODE) }
	LaunchedEffect(settings) {
		settings.changes.collect { changedKey ->
			if (changedKey == THEME_SETTING_KEY) {
				themeMode = settings.getString(THEME_SETTING_KEY) ?: DEFAULT_THEME_MODE
			}
		}
	}
	CompositionLocalProvider(LocalThemeMode provides themeMode, content = content)
}
