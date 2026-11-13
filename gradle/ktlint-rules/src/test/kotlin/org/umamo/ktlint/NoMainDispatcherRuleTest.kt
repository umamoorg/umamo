package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoMainDispatcherRule through the real ktlint engine, alone, so each case shows where it reads the
 * main dispatcher as named in library code.
 */
class NoMainDispatcherRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoMainDispatcherRule() }))

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
	fun reportsTheMainDispatcherInALibrary() {
		val code =
			"""
			package org.umamo.ui.document

			import kotlinx.coroutines.Dispatchers
			import kotlinx.coroutines.withContext

			internal suspend fun deliver(block: () -> Unit) {
				withContext(Dispatchers.Main) { block() }
				withContext(Dispatchers.Main.immediate) { block() }
				withContext(Dispatchers.Default) { block() }
			}
			""".trimIndent()

		assertEquals(listOf("7:14", "8:14"), reported(code))
	}

	@Test
	fun reportsItImportedOrWrittenOutInFull() {
		val code =
			"""
			package org.umamo.reimport

			import kotlinx.coroutines.Dispatchers.Main

			internal val writtenOut = kotlinx.coroutines.Dispatchers.Main
			""".trimIndent()

		assertEquals(listOf("3:1", "5:27"), reported(code))
	}

	@Test
	fun leavesSettingTheMainDispatcherAlone() {
		val code =
			"""
			package org.umamo.ui.document

			import kotlinx.coroutines.Dispatchers

			internal fun install() {
				Dispatchers.setMain(Dispatchers.Unconfined)
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesTheApplicationsAlone() {
		val android =
			"""
			package org.umamo.editor.android

			import kotlinx.coroutines.Dispatchers

			internal val uiDispatcher = Dispatchers.Main
			""".trimIndent()
		val cli =
			"""
			package org.umamo.cli

			import kotlinx.coroutines.Dispatchers

			internal val uiDispatcher = Dispatchers.Main
			""".trimIndent()

		assertEquals(emptyList(), reported(android))
		assertEquals(emptyList(), reported(cli))
	}
}