package org.umamo.ui.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The per-language text a contributor hands the editor picks the UI language's entry and falls back sensibly. */
class UiTextTest {
	/** The text a plugin might give for one label. */
	private val boneTab = UiText.Localized(linkedMapOf("ja" to "ボーン物理", "en" to "Bone Physics"))

	/** The UI language's own entry wins. */
	@Test
	fun theUiLanguagesEntryIsShown() {
		assertEquals("ボーン物理", boneTab.textFor("ja"))
		assertEquals("Bone Physics", boneTab.textFor("en"))
	}

	/** A regional UI tag finds its language's entry, whatever the case. */
	@Test
	fun aRegionalUiTagFindsItsLanguage() {
		assertEquals("ボーン物理", boneTab.textFor("ja-JP"))
		assertEquals("ボーン物理", boneTab.textFor("JA_jp"))
	}

	/** A language with no entry falls back to English. */
	@Test
	fun aMissingLanguageFallsBackToEnglish() {
		assertEquals("Bone Physics", boneTab.textFor("ko"))
	}

	/** With no English entry either, the first entry given is shown. */
	@Test
	fun noEnglishFallsBackToTheFirstEntry() {
		val frenchFirst = UiText.Localized(linkedMapOf("fr" to "Physique des os", "ja" to "ボーン物理"))
		assertEquals("Physique des os", frenchFirst.textFor("ko"))
	}

	/** Text for no language at all is refused when it is made, not when it is shown. */
	@Test
	fun anEmptyMapIsRefused() {
		assertFailsWith<IllegalArgumentException> { UiText.Localized(emptyMap()) }
	}
}