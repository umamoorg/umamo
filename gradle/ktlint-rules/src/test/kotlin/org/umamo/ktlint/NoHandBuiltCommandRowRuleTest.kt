package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoHandBuiltCommandRowRule through the real ktlint engine, alone, so each case shows which chord lookups
 * it reads as a command's row being built by hand.
 */
class NoHandBuiltCommandRowRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoHandBuiltCommandRowRule() }))

	/**
	 * Lints a snippet with the rule alone and reduces each violation to where it is.
	 *
	 * @param String code Kotlin source.
	 * @return List<String> One "line:column" per violation.
	 */
	private fun reported(code: String): List<String> {
		val positions = ArrayList<String>()
		engine.lint(Code.fromSnippet(code)) { error -> positions.add("${error.line}:${error.col}") }

		return positions
	}

	@Test
	fun reportsAChordLookedUpInAWidget() {
		val code =
			"""
			package org.umamo.ui.workspace.spaces.uv

			internal fun mirrorRow(keymap: Keymap, commands: CommandRegistry): MenuItem =
				MenuItem.Action(
					label = "Mirror X",
					onSelect = { commands.invoke("uv.mirrorU") },
					shortcut = keymap.chordFor("uv.mirrorU")?.let { chord -> formatAccelerator(chord) },
				)
			""".trimIndent()

		assertEquals(listOf("7:21"), reported(code))
	}

	@Test
	fun reportsEveryWayTheLookupIsWritten() {
		val code =
			"""
			package org.umamo.ui.properties

			internal fun chords(keymap: Keymap?, ids: List<String>): List<Any?> {
				val safe = keymap?.chordFor("keyform.insert")
				val scoped = with(checkNotNull(keymap)) { chordFor("keyform.delete") }
				val mapped = ids.map(keymap::chordFor)

				return listOf(safe, scoped) + mapped
			}
			""".trimIndent()

		assertEquals(listOf("4:21", "5:44", "6:31"), reported(code))
	}

	@Test
	fun leavesThePackagesThatShowCommandsWithTheirChordsAlone() {
		val builder =
			"""
			package org.umamo.ui.action

			internal fun shortcutOf(keymap: Keymap, commandId: String): String? = keymap.chordFor(commandId)?.let { chord -> formatAccelerator(chord) }
			""".trimIndent()
		val editor =
			"""
			package org.umamo.ui.settings

			internal fun boundChord(keymap: Keymap, commandId: String): KeyChord? = keymap.chordFor(commandId)
			""".trimIndent()
		val hints =
			"""
			package org.umamo.ui.workspace.statusbar

			internal fun hintChords(keymap: Keymap, ids: List<String>): List<KeyChord> = ids.mapNotNull { id -> keymap.chordFor(id) }
			""".trimIndent()

		assertEquals(emptyList(), reported(builder))
		assertEquals(emptyList(), reported(editor))
		assertEquals(emptyList(), reported(hints))
	}

	@Test
	fun readsAPackageUnderOneOfThemAsItsOwn() {
		val code =
			"""
			package org.umamo.ui.action.menu

			internal fun shortcutOf(keymap: Keymap, commandId: String): KeyChord? = keymap.chordFor(commandId)
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun reportsAPackageThatOnlyStartsWithTheSameLetters() {
		val code =
			"""
			package org.umamo.ui.actions

			internal fun shortcutOf(keymap: Keymap, commandId: String): KeyChord? = keymap.chordFor(commandId)
			""".trimIndent()

		assertEquals(listOf("3:80"), reported(code))
	}

	@Test
	fun leavesOtherNamesAndADeclarationAlone() {
		val code =
			"""
			package org.umamo.ui.workspace.shell

			internal class TabChords(private val chordFor: Map<String, String>) {
				fun chordFor(tabId: String): String? = chordFor[tabId]

				fun chordForRow(rowId: String): String? = chordFor[rowId]

				fun labels(ids: List<String>): List<String?> = ids.map { id -> chordForRow(id) }
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}
}