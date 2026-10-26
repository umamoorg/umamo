package org.umamo.ui.l10n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text shown in the UI that may come from outside the app's own string catalogs: a label a contributor hands
 * the editor, such as a sidebar tab's.  Umamo's own labels are catalog resources; a plugin configured outside
 * Kotlin has no catalog, so it passes plain text, or text per language that follows the UI language live.
 */
sealed interface UiText {
	/**
	 * A string from Umamo's own catalogs, translated by them.
	 *
	 * @property StringResource resource The catalog entry.
	 */
	data class Resource(val resource: StringResource) : UiText

	/**
	 * Text shown as it is, whatever the UI language.
	 *
	 * @property String text The text.
	 */
	data class Literal(val text: String) : UiText

	/**
	 * Text per UI language, keyed by bare language tags like the UI's own ("en", "ja"): the UI language picks
	 * its entry, then English, then the first entry given.  A regional key ("ja-JP") is never matched by a
	 * bare UI language, so a contributor keys by language alone.
	 *
	 * @property Map textsByLanguage The text for each language tag, in the contributor's order.
	 */
	data class Localized(val textsByLanguage: Map<String, String>) : UiText {
		init {
			require(textsByLanguage.isNotEmpty()) { "Localized text needs at least one language" }
		}
	}
}

/**
 * The entry of [UiText.Localized] for [languageTag]: the best match among its keys (the whole tag, then its
 * language), else its English entry, else its first.
 *
 * @param String languageTag The UI language's BCP-47 tag.
 * @return String The text to show.
 */
fun UiText.Localized.textFor(languageTag: String): String {
	val matchedTag = matchUiLanguage(listOf(languageTag), textsByLanguage.keys)

	return textsByLanguage[matchedTag] ?: textsByLanguage[FALLBACK_LOCALE_TAG] ?: textsByLanguage.values.first()
}

/**
 * This text in the current UI language, following a live language switch.
 *
 * @return String The text to show.
 */
@Composable
fun UiText.resolve(): String =
	when (this) {
		is UiText.Resource -> stringResource(resource)
		is UiText.Literal -> text
		is UiText.Localized -> textFor(LocalAppLocale.current)
	}