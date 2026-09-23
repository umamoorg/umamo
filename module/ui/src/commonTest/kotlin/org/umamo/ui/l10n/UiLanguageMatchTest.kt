package org.umamo.ui.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

/** The operating-system-language to UI-catalog match a first run seeds localization.locale from. */
class UiLanguageMatchTest {
	private val shipped = UI_LANGUAGE_ENDONYMS.keys

	@Test
	fun aRegionTagMatchesItsLanguageCatalog() {
		assertEquals("ja", matchUiLanguage(listOf("ja-JP"), shipped))
		assertEquals("ko", matchUiLanguage(listOf("ko-KR"), shipped))
	}

	@Test
	fun theJvmUnderscoreSpellingAndCaseDoNotMatter() {
		assertEquals("ja", matchUiLanguage(listOf("ja_JP"), shipped))
		assertEquals("ko", matchUiLanguage(listOf("KO"), shipped))
	}

	@Test
	fun theFirstPreferredLanguageWithACatalogWins() {
		// French has no catalog, so the next preference is taken rather than falling back to English.
		assertEquals("ko", matchUiLanguage(listOf("fr-FR", "ko-KR", "ja-JP"), shipped))
	}

	@Test
	fun noCatalogFallsBackToEnglish() {
		assertEquals("en", matchUiLanguage(listOf("zh-TW"), shipped))
		assertEquals("en", matchUiLanguage(listOf("fr-FR", "de"), shipped))
	}

	@Test
	fun noSystemLanguageFallsBackToEnglish() {
		assertEquals("en", matchUiLanguage(emptyList(), shipped))
		assertEquals("en", matchUiLanguage(listOf(""), shipped))
	}

	@Test
	fun aRegionCatalogOutranksTheBareLanguage() {
		val withRegions = listOf("en", "zh", "zh-CN", "zh-TW")
		assertEquals("zh-CN", matchUiLanguage(listOf("zh-CN"), withRegions))
		// A script between the language and the region still finds the region catalog.
		assertEquals("zh-TW", matchUiLanguage(listOf("zh-Hant-TW"), withRegions))
		// A region with no catalog of its own takes the bare language.
		assertEquals("zh", matchUiLanguage(listOf("zh-SG"), withRegions))
	}

	@Test
	fun theMatchIsReturnedAsTheCatalogSpellsIt() {
		assertEquals("zh-CN", matchUiLanguage(listOf("zh_cn"), listOf("en", "zh-CN")))
	}
}