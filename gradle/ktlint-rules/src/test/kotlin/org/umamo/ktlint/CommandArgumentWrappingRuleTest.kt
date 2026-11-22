package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INSERT_FINAL_NEWLINE_PROPERTY
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.ec4j.core.model.PropertyType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives CommandArgumentWrappingRule through the real ktlint engine: alone, so each case shows exactly what the
 * rule changes, and beside the standard rules and the rest of the Umamo set, whose formatting its output has to
 * settle into within one format call.
 */
class CommandArgumentWrappingRuleTest {
	private val engine =
		KtLintRuleEngine(
			ruleProviders = setOf(RuleProvider { CommandArgumentWrappingRule() }),
			editorConfigOverride = EditorConfigOverride.from(INDENT_STYLE_PROPERTY to PropertyType.IndentStyleValue.tab),
		)

	private val engineWithEveryRule =
		KtLintRuleEngine(
			// A snippet has no file name of its own for the standard filename rule to judge.
			ruleProviders =
				StandardRuleSetProvider()
					.getRuleProviders()
					.filter { provider -> provider.ruleId.value != "standard:filename" }
					.toSet() + UmamoRuleSetProvider().getRuleProviders(),
			editorConfigOverride =
				EditorConfigOverride.from(
					INDENT_STYLE_PROPERTY to PropertyType.IndentStyleValue.tab,
					INSERT_FINAL_NEWLINE_PROPERTY to false,
				),
		)

	/**
	 * Formats a snippet with the given engine.
	 *
	 * @param String code Kotlin source.
	 * @param KtLintRuleEngine formatter The engine to format with.
	 * @return String The formatted source.
	 */
	private fun format(code: String, formatter: KtLintRuleEngine = engine): String =
		formatter.format(Code.fromSnippet(code)) { _ -> AutocorrectDecision.ALLOW_AUTOCORRECT }

	/**
	 * Lints a snippet with the given engine and reduces each violation to where it is.
	 *
	 * @param String code Kotlin source.
	 * @param KtLintRuleEngine linter The engine to lint with.
	 * @return List<String> One "line:column" per violation, with its rule id.
	 */
	private fun reported(code: String, linter: KtLintRuleEngine = engine): List<String> {
		val positions = ArrayList<String>()
		linter.lint(Code.fromSnippet(code)) { error -> positions.add("${error.line}:${error.col} ${error.ruleId.value}") }

		return positions
	}

	/**
	 * Asserts the rule leaves a snippet exactly as written and reports nothing in it.
	 *
	 * @param String code Kotlin source the rule has no business with.
	 */
	private fun assertUnchanged(code: String) {
		assertEquals(code, format(code))
		assertEquals(emptyList(), reported(code))
	}

	/**
	 * Asserts the given engine rewrites a snippet to the expected source, and that the result is stable.
	 *
	 * @param String code Kotlin source holding a command not written one argument per line.
	 * @param String expected The source with every command wrapped.
	 * @param KtLintRuleEngine formatter The engine to format with.
	 */
	private fun assertWrapped(code: String, expected: String, formatter: KtLintRuleEngine = engine) {
		assertEquals(expected, format(code, formatter))
		assertEquals(expected, format(expected, formatter))
		assertEquals(emptyList(), reported(expected, formatter))
	}

	@Test
	fun wrapsAOneLineCommand() {
		assertWrapped(
			"""
			import org.umamo.ui.action.Command

			internal fun chromeCommands(toggle: () -> Unit) =
				listOf(
					Command("palette.toggle", title = null) { toggle() },
				)
			""".trimIndent(),
			"""
			import org.umamo.ui.action.Command

			internal fun chromeCommands(toggle: () -> Unit) =
				listOf(
					Command(
						"palette.toggle",
						title = null,
					) { toggle() },
				)
			""".trimIndent(),
		)
	}

	@Test
	fun keepsTheHandlerBodyAsWritten() {
		assertWrapped(
			"""
			import org.umamo.ui.action.Command

			internal fun helpCommands(overlays: Overlays) =
				listOf(
					Command("help.about", title = Res.string.menu_about, group = CommandSection.HelpAbout) {
						overlays.aboutVisible = !overlays.aboutVisible
					},
					Command("help.credits", title = Res.string.menu_credits, group = CommandSection.HelpAbout) { argument ->
						overlays.open(argument)
					},
				)
			""".trimIndent(),
			"""
			import org.umamo.ui.action.Command

			internal fun helpCommands(overlays: Overlays) =
				listOf(
					Command(
						"help.about",
						title = Res.string.menu_about,
						group = CommandSection.HelpAbout,
					) {
						overlays.aboutVisible = !overlays.aboutVisible
					},
					Command(
						"help.credits",
						title = Res.string.menu_credits,
						group = CommandSection.HelpAbout,
					) { argument ->
						overlays.open(argument)
					},
				)
			""".trimIndent(),
		)
	}

	@Test
	fun wrapsANamedHandlerLikeAnyOtherArgument() {
		assertWrapped(
			"""
			import org.umamo.ui.action.Command

			internal fun echoCommand(run: () -> Unit): Command = Command("test.echo", title = null, handler = { run() })
			""".trimIndent(),
			"""
			import org.umamo.ui.action.Command

			internal fun echoCommand(run: () -> Unit): Command = Command(
				"test.echo",
				title = null,
				handler = { run() },
			)
			""".trimIndent(),
		)
	}

	@Test
	fun completesAPartlyWrappedCommand() {
		assertWrapped(
			"""
			import org.umamo.ui.action.Command

			internal fun helpCommands(open: () -> Unit) =
				listOf(
					Command(
						"help.credits",
						title = Res.string.menu_credits) {
						open()
					},
					Command(
						"help.about", title = Res.string.menu_about
					) {
						open()
					},
				)
			""".trimIndent(),
			"""
			import org.umamo.ui.action.Command

			internal fun helpCommands(open: () -> Unit) =
				listOf(
					Command(
						"help.credits",
						title = Res.string.menu_credits,
					) {
						open()
					},
					Command(
						"help.about",
						title = Res.string.menu_about,
					) {
						open()
					},
				)
			""".trimIndent(),
		)
	}

	@Test
	fun leavesAWrappedCommandAlone() {
		assertUnchanged(
			"""
			import org.umamo.ui.action.Command

			internal fun helpCommands(open: () -> Unit) =
				listOf(
					Command(
						"help.credits",
						// Shown in the Help menu.
						title = Res.string.menu_credits,
						group = CommandSection.HelpAbout, // The About block.
					) { open() },
				)
			""".trimIndent(),
		)
	}

	@Test
	fun followsTheImportAndAnAlias() {
		val unrelated =
			"""
			import org.example.terminal.Command

			internal val shell = Command("ls", title = null)
			""".trimIndent()
		val aliased =
			"""
			import org.umamo.ui.action.Command as EditorCommand

			internal val stray = EditorCommand("kit.stray", title = null)
			""".trimIndent()
		val samePackage =
			"""
			package org.umamo.ui.action

			internal val stray = Command("kit.stray", title = null)
			""".trimIndent()

		assertUnchanged(unrelated)
		assertEquals(listOf("3:22 umamo:command-argument-wrapping"), reported(aliased))
		assertEquals(listOf("3:22 umamo:command-argument-wrapping"), reported(samePackage))
	}

	@Test
	fun reportsEachCommandOnce() {
		val code =
			"""
			import org.umamo.ui.action.Command

			internal fun meshCommands() =
				listOf(
					Command("mesh.subdivide", title = null, group = CommandSection.MeshTopology) {},
					Command("mesh.merge", title = null) {},
				)
			""".trimIndent()

		assertEquals(listOf("5:3 umamo:command-argument-wrapping", "6:3 umamo:command-argument-wrapping"), reported(code))
	}

	@Test
	fun settlesBesideEveryRule() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			import org.umamo.ui.action.Command

			/**
			 * The log commands.
			 *
			 * @param Function onExportLog Exports the log.
			 * @return List<Command> The commands to register.
			 */
			internal fun logCommands(onExportLog: () -> Unit): List<Command> =
				commandTable(CommandTable.Log, Command("logs.export", title = Res.string.logs_export, icon = LocalUmamoIcons.floppy) { onExportLog() })

			/**
			 * The help commands.
			 *
			 * @param Function about Opens the About dialog.
			 * @return Command The command to register.
			 */
			internal fun aboutCommand(about: () -> Unit): Command = Command("help.about", title = Res.string.menu_about, group = CommandSection.HelpAbout) { about() }
			""".trimIndent()
		val expected =
			"""
			package org.umamo.ui.workspace.commands

			import org.umamo.ui.action.Command

			/**
			 * The log commands.
			 *
			 * @param Function onExportLog Exports the log.
			 * @return List<Command> The commands to register.
			 */
			internal fun logCommands(onExportLog: () -> Unit): List<Command> =
				commandTable(
					CommandTable.Log,
					Command(
						"logs.export",
						title = Res.string.logs_export,
						icon = LocalUmamoIcons.floppy,
					) { onExportLog() },
				)

			/**
			 * The help commands.
			 *
			 * @param Function about Opens the About dialog.
			 * @return Command The command to register.
			 */
			internal fun aboutCommand(about: () -> Unit): Command =
				Command(
					"help.about",
					title = Res.string.menu_about,
					group = CommandSection.HelpAbout,
				) { about() }
			""".trimIndent()

		assertWrapped(code, expected, engineWithEveryRule)
	}
}