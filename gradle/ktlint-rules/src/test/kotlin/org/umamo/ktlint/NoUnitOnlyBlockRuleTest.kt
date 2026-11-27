package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_STYLE_PROPERTY
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.ec4j.core.model.PropertyType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoUnitOnlyBlockRule through the real ktlint engine: alone, so each case shows exactly what the
 * rule changes, and beside the standard when-entry-bracing rule whose output it exists to clean up.
 */
class NoUnitOnlyBlockRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoUnitOnlyBlockRule() }))

	private val engineWithWhenEntryBracing =
		KtLintRuleEngine(
			ruleProviders =
				StandardRuleSetProvider()
					.getRuleProviders()
					.filter { provider -> provider.ruleId.value == "standard:when-entry-bracing" }
					.toSet() + RuleProvider { NoUnitOnlyBlockRule() },
			editorConfigOverride = EditorConfigOverride.from(INDENT_STYLE_PROPERTY to PropertyType.IndentStyleValue.tab),
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
	 * Lints a snippet with the rule alone.
	 *
	 * @param String code Kotlin source.
	 * @return List<LintError> Every violation the rule reports.
	 */
	private fun lint(code: String): List<LintError> {
		val errors = ArrayList<LintError>()
		engine.lint(Code.fromSnippet(code)) { error -> errors.add(error) }

		return errors
	}

	/**
	 * Asserts the rule leaves a snippet exactly as written and reports nothing in it.
	 *
	 * @param String code Kotlin source the rule has no business with.
	 */
	private fun assertUnchanged(code: String) {
		assertEquals(code, format(code))
		assertEquals(emptyList(), lint(code))
	}

	@Test
	fun emptiesADoNothingWhenBranch() {
		val code =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						println("zero")
					}

					else -> {
						Unit
					}
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						println("zero")
					}

					else -> {}
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
		assertEquals(expected, format(expected))
	}

	@Test
	fun reportsTheUnitItRemoves() {
		val code =
			"""
			fun nothing() {
				Unit
			}
			""".trimIndent()

		val errors = lint(code)

		assertEquals(listOf(2 to 2), errors.map { error -> error.line to error.col })
		assertEquals("A block holding only `Unit` leaves it unused; write `{}`", errors.single().detail)
		assertEquals("umamo:no-unit-only-block", errors.single().ruleId.value)
	}

	@Test
	fun emptiesFunctionBodiesIfBranchesAndOneLineBlocks() {
		val code =
			"""
			fun nothing() {
				Unit
			}

			fun branch(flag: Boolean) {
				if (flag) {
					println("on")
				} else {
					Unit
				}

				if (!flag) { Unit }
			}
			""".trimIndent()
		val expected =
			"""
			fun nothing() {}

			fun branch(flag: Boolean) {
				if (flag) {
					println("on")
				} else {}

				if (!flag) {}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun keepsACommentAboveTheUnit() {
		val code =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						// Zero is handled by the caller.
						Unit
					}

					else -> {
						println(value)
					}
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						// Zero is handled by the caller.
					}

					else -> {
						println(value)
					}
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun keepsACommentTrailingTheUnitOnItsOwnLine() {
		val code =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						Unit // Zero is handled by the caller.
					}

					else -> {
						println(value)
					}
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						// Zero is handled by the caller.
					}

					else -> {
						println(value)
					}
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun leavesLambdasAndBlocksWithOtherStatementsAlone() {
		assertUnchanged(
			"""
			val callback: () -> Unit = { Unit }

			fun busy() {
				println("busy")
				Unit
			}

			fun done() = run { Unit }
			""".trimIndent(),
		)
	}

	@Test
	fun cleansUpAfterWhenEntryBracing() {
		val code =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						println("zero")
					}
					else -> Unit
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun pick(value: Int) {
				when (value) {
					0 -> {
						println("zero")
					}
					else -> {}
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code, engineWithWhenEntryBracing))
	}

	@Test
	fun runsAfterWhenEntryBracing() {
		val provider = RuleProvider { NoUnitOnlyBlockRule() }

		assertEquals(listOf("standard:when-entry-bracing"), provider.runAfterRules.map { runAfter -> runAfter.ruleId.value })
	}
}