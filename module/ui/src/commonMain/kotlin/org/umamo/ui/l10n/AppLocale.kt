package org.umamo.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The active UI language tag (BCP-47, e.g. "en" / "ja") for the composition, driven by the
 * localization.locale setting rather than the OS locale. Descendants that need the raw tag read
 * `LocalAppLocale.current`; most code just calls stringResource() and lets the catalogs resolve.
 *
 * コンポジションで有効な UI 言語タグ。OS ロケールではなく localization.locale 設定で決まる。
 */
val LocalAppLocale = staticCompositionLocalOf { "en" }

/**
 * Applies [languageTag] as the process resource locale on the current platform. Compose
 * Multiplatform has no first-party "override the resource locale at runtime" API yet (JetBrains issue
 * #4197), so the portable lever is the JVM default locale, which the desktop/Android resource
 * environment derives from. expect/actual keeps java.util.Locale out of commonMain (absent there).
 *
 * 指定言語タグをプラットフォームのロケールに適用する。CMP には実行時ロケール上書きの公式 API が
 * まだ無いため、JVM 既定ロケールを介する。
 *
 * @param String languageTag The BCP-47 language tag to apply.
 */
expect fun applyAppLocale(languageTag: String)

/**
 * Provides [languageTag] to the subtree and makes stringResource() resolve against it. Two effects:
 * [applyAppLocale] sets the platform locale before children compose (via remember keyed on the tag,
 * so it re-runs only when the language actually changes), and `key(languageTag)` forces the subtree -
 * including Compose's cached resource environment - to recompose on a switch so the new catalog is read.
 *
 * 言語タグを子ツリーに供給し、stringResource() がそれを参照するようにする。言語切替時のみ再適用・再構成。
 *
 * @param String languageTag The BCP-47 language tag to make active.
 * @param Function content The subtree to render under this locale.
 */
@Composable
fun ProvideAppLocale(languageTag: String, content: @Composable () -> Unit) {
	// remember(key) runs once per distinct tag - applies the locale before the keyed content composes.
	remember(languageTag) {
		applyAppLocale(languageTag)
		languageTag
	}
	CompositionLocalProvider(LocalAppLocale provides languageTag) {
		key(languageTag) {
			content()
		}
	}
}
