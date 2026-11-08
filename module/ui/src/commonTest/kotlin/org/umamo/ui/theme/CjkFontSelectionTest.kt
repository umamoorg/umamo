package org.umamo.ui.theme

import org.umamo.ui.resources.Res
import org.umamo.ui.resources.noto_sans_cjk_hk_regular
import org.umamo.ui.resources.noto_sans_cjk_jp_regular
import org.umamo.ui.resources.noto_sans_cjk_kr_regular
import org.umamo.ui.resources.noto_sans_cjk_sc_regular
import org.umamo.ui.resources.noto_sans_cjk_tc_regular
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the UI-language to Noto Sans CJK cut mapping.
 *
 * The cut has to be selected rather than appended to the family: every language-specific Noto Sans CJK
 * cut carries the same codepoints, so a second cut listed after the first is unreachable - fallback
 * fires on a missing glyph, and nothing is missing.  A refactor that "adds Korean support" by appending
 * the KR face instead of selecting it renders identically to having no KR face at all, which no visual
 * check would catch.  This test fails that refactor.
 */
class CjkFontSelectionTest {
	/**
	 * Korean takes the KR cut; Japanese and English take JP.
	 */
	@Test
	fun koreanTakesTheKoreanCut() {
		assertEquals(Res.font.noto_sans_cjk_kr_regular, cjkFontFor("ko"), "ko")
		assertEquals(Res.font.noto_sans_cjk_jp_regular, cjkFontFor("ja"), "ja")
		assertEquals(Res.font.noto_sans_cjk_jp_regular, cjkFontFor("en"), "en")
	}

	/**
	 * Chinese takes the cut of its script or region: SC for Simplified and a bare zh, TC for Taiwan and the
	 * Hant script, HK for Hong Kong and Macau.  A Hans script outranks the region.
	 */
	@Test
	fun chineseTakesTheCutOfItsScriptOrRegion() {
		for (simplifiedTag in listOf("zh-CN", "zh", "zh-Hans", "zh-SG", "zh-Hans-HK")) {
			assertEquals(Res.font.noto_sans_cjk_sc_regular, cjkFontFor(simplifiedTag), simplifiedTag)
		}

		for (taiwanTag in listOf("zh-TW", "zh-Hant", "ZH_tw")) {
			assertEquals(Res.font.noto_sans_cjk_tc_regular, cjkFontFor(taiwanTag), taiwanTag)
		}

		for (hongKongTag in listOf("zh-HK", "zh-Hant-HK", "zh-MO")) {
			assertEquals(Res.font.noto_sans_cjk_hk_regular, cjkFontFor(hongKongTag), hongKongTag)
		}
	}

	/**
	 * A regional or oddly-cased tag resolves like its bare lowercase language subtag.
	 */
	@Test
	fun regionalAndCasedTagsResolveLikeTheBareLanguage() {
		assertEquals(Res.font.noto_sans_cjk_kr_regular, cjkFontFor("ko-KR"), "ko-KR")
		assertEquals(Res.font.noto_sans_cjk_kr_regular, cjkFontFor("KO"), "KO")
		assertEquals(Res.font.noto_sans_cjk_jp_regular, cjkFontFor("en-US"), "en-US")
	}

	/**
	 * An unknown or empty tag falls back to JP rather than throwing.
	 */
	@Test
	fun unknownTagsFallBackToJapanese() {
		assertEquals(Res.font.noto_sans_cjk_jp_regular, cjkFontFor("de"), "de")
		assertEquals(Res.font.noto_sans_cjk_jp_regular, cjkFontFor(""), "empty")
	}
}