package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoCommandOutsideTablesRule through the real ktlint engine, alone, so each case shows which
 * constructor calls it reads as a command built outside the command tables.
 */
class NoCommandOutsideTablesRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoCommandOutsideTablesRule() }))

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
	fun leavesTheCommandTablesAlone() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			import org.umamo.ui.action.Command

			internal val meshCommands = listOf(Command("mesh.subdivide"), Command("mesh.merge"))
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun reportsACommandBuiltInAWidget() {
		val code =
			"""
			package org.umamo.ui.workspace.spaces.outliner

			import org.umamo.ui.action.Command

			internal fun rowMenu() = listOf(Command("outliner.rename"))
			""".trimIndent()

		assertEquals(listOf("5:33"), reported(code))
	}

	@Test
	fun followsAnAliasAndAnOnDemandImport() {
		val aliased =
			"""
			package org.umamo.ui.kit

			import org.umamo.ui.action.Command as EditorCommand

			internal val stray = EditorCommand("kit.stray")
			""".trimIndent()
		val onDemand =
			"""
			package org.umamo.ui.kit

			import org.umamo.ui.action.*

			internal val stray = Command("kit.stray")
			""".trimIndent()

		assertEquals(listOf("5:22"), reported(aliased))
		assertEquals(listOf("5:22"), reported(onDemand))
	}

	@Test
	fun reportsOneBuiltBesideTheClassItself() {
		val code =
			"""
			package org.umamo.ui.action

			class Command(val id: String)

			internal val fallback = Command("action.fallback")
			""".trimIndent()

		assertEquals(listOf("5:25"), reported(code))
	}

	@Test
	fun leavesAnotherClassOfTheSameNameAlone() {
		val code =
			"""
			package org.umamo.cli

			class Command(val name: String)

			internal val dump = Command("dump")
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesAMentionThatBuildsNothingAlone() {
		val code =
			"""
			package org.umamo.ui.workspace.shell

			import org.umamo.ui.action.Command

			internal fun describe(command: Command): String = command.id

			internal val commands: List<Command> = emptyList()
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}
}