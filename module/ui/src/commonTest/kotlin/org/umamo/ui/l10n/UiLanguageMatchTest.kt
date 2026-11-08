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
		assertEquals("fr", matchUiLanguage(listOf("fr-FR"), shipped))
		// Every French region shares the one catalog.
		assertEquals("fr", matchUiLanguage(listOf("fr-CA"), shipped))
	}

	@Test
	fun theJvmUnderscoreSpellingAndCaseDoNotMatter() {
		assertEquals("ja", matchUiLanguage(listOf("ja_JP"), shipped))
		assertEquals("ko", matchUiLanguage(listOf("KO"), shipped))
		assertEquals("fr", matchUiLanguage(listOf("FR_be"), shipped))
	}

	@Test
	fun theFirstPreferredLanguageWithACatalogWins() {
		// German has no catalog, so the next preference is taken rather than falling back to English.
		assertEquals("ko", matchUiLanguage(listOf("de-DE", "ko-KR", "ja-JP"), shipped))
		assertEquals("fr", matchUiLanguage(listOf("de-CH", "fr-CH", "ja-JP"), shipped))
	}

	@Test
	fun noCatalogFallsBackToEnglish() {
		assertEquals("en", matchUiLanguage(listOf("pt-BR"), shipped))
		assertEquals("en", matchUiLanguage(listOf("de-DE", "es"), shipped))
	}

	/**
	 * Every Chinese tag lands on the catalog of its script: the region catalogs are the only Chinese ones, so a
	 * script-only tag, a foreign region, a parent region, and the bare language all take the script fallback.
	 */
	@Test
	fun chineseTagsFindTheirScriptsCatalog() {
		for (simplifiedTag in listOf("zh-CN", "zh_cn", "zh-Hans", "zh-Hans-US", "zh-SG", "zh-MY", "zh", "zh-US")) {
			assertEquals("zh-CN", matchUiLanguage(listOf(simplifiedTag), shipped), simplifiedTag)
		}

		for (traditionalTag in listOf("zh-TW", "zh-Hant", "zh-Hant-TW", "zh-HK", "zh-MO", "zh-Hant-SG", "ZH_hk")) {
			assertEquals("zh-TW", matchUiLanguage(listOf(traditionalTag), shipped), traditionalTag)
		}
	}

	/**
	 * The script fallback is the last candidate for its tag, and it never crosses scripts: Traditional with no
	 * Traditional catalog moves on to the next preferred language rather than taking Simplified.
	 */
	@Test
	fun theScriptFallbackNeverCrossesScripts() {
		assertEquals("zh-TW", matchUiLanguage(listOf("zh-Hant-HK", "ja-JP"), shipped))

		val simplifiedOnly = listOf("en", "ja", "zh-CN")
		assertEquals("ja", matchUiLanguage(listOf("zh-Hant", "ja-JP"), simplifiedOnly))
		assertEquals("en", matchUiLanguage(listOf("zh-HK"), simplifiedOnly))
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