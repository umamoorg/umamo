package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INSERT_FINAL_NEWLINE_PROPERTY
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.ec4j.core.model.PropertyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives StatementIfBracesRule through the real ktlint engine: alone, so each case shows exactly what the
 * rule changes, and beside the standard rules and the rest of the Umamo set, whose formatting its output
 * has to settle into within one format call.
 */
class StatementIfBracesRuleTest {
	private val engine =
		KtLintRuleEngine(
			ruleProviders = setOf(RuleProvider { StatementIfBracesRule() }),
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
	 * @param Boolean script Whether the snippet is a Kotlin script.
	 * @return String The formatted source.
	 */
	private fun format(code: String, formatter: KtLintRuleEngine = engine, script: Boolean = false): String =
		formatter.format(Code.fromSnippet(code, script)) { _ -> AutocorrectDecision.ALLOW_AUTOCORRECT }

	/**
	 * Lints a snippet with the given engine.
	 *
	 * @param String code Kotlin source.
	 * @param KtLintRuleEngine linter The engine to lint with.
	 * @return List<LintError> Every violation reported.
	 */
	private fun lint(code: String, linter: KtLintRuleEngine = engine): List<LintError> {
		val errors = ArrayList<LintError>()
		linter.lint(Code.fromSnippet(code)) { error -> errors.add(error) }

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

	/**
	 * Asserts the rule rewrites a snippet to the expected source, and that the result is stable.
	 *
	 * @param String code Kotlin source holding a braceless statement `if`.
	 * @param String expected The source with every such branch braced.
	 */
	private fun assertBraced(code: String, expected: String) {
		assertEquals(expected, format(code))
		assertEquals(expected, format(expected))
		assertEquals(emptyList(), lint(expected))
	}

	@Test
	fun bracesAnIfWithNoElse() {
		assertBraced(
			"""
			fun guard(value: Int) {
				if (value < 0) return
				println(value)
			}
			""".trimIndent(),
			"""
			fun guard(value: Int) {
				if (value < 0) {
					return
				}
				println(value)
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesBothBranchesOfAnIfElseStatement() {
		assertBraced(
			"""
			fun report(value: Int) {
				if (value < 0) println("negative") else println("positive")
				println("done")
			}
			""".trimIndent(),
			"""
			fun report(value: Int) {
				if (value < 0) {
					println("negative")
				} else {
					println("positive")
				}
				println("done")
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesTheLastStatementOfAFunctionBody() {
		assertBraced(
			"""
			fun report(value: Int) {
				println("start")
				if (value < 0) println("negative") else println("positive")
			}
			""".trimIndent(),
			"""
			fun report(value: Int) {
				println("start")
				if (value < 0) {
					println("negative")
				} else {
					println("positive")
				}
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesEveryBranchOfAnElseIfChain() {
		assertBraced(
			"""
			fun sign(value: Int) {
				if (value < 0) println("negative") else if (value > 0) println("positive") else println("zero")
				println("done")
			}
			""".trimIndent(),
			"""
			fun sign(value: Int) {
				if (value < 0) {
					println("negative")
				} else if (value > 0) {
					println("positive")
				} else {
					println("zero")
				}
				println("done")
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesTheBracelessBranchOfAHalfBracedIf() {
		assertBraced(
			"""
			fun report(value: Int) {
				if (value < 0) {
					println("negative")
				} else println("positive")
				println("done")
			}
			""".trimIndent(),
			"""
			fun report(value: Int) {
				if (value < 0) {
					println("negative")
				} else {
					println("positive")
				}
				println("done")
			}
			""".trimIndent(),
		)
	}

	@Test
	fun carriesATrailingCommentIntoTheBlock() {
		assertBraced(
			"""
			fun guard(value: Int) {
				if (value < 0) return // nothing to draw
				println(value)
			}
			""".trimIndent(),
			"""
			fun guard(value: Int) {
				if (value < 0) {
					return // nothing to draw
				}
				println(value)
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesInsideLoopsInitializersAndAccessors() {
		assertBraced(
			"""
			class Counter(values: List<Int>) {
				var total = 0
					set(value) {
						if (value < 0) field = 0 else field = value
					}

				init {
					for (value in values) {
						if (value == 0) continue
						total += value
					}

					for (value in values) if (value < 0) total -= value
				}
			}
			""".trimIndent(),
			"""
			class Counter(values: List<Int>) {
				var total = 0
					set(value) {
						if (value < 0) {
							field = 0
						} else {
							field = value
						}
					}

				init {
					for (value in values) {
						if (value == 0) {
							continue
						}
						total += value
					}

					for (value in values) if (value < 0) {
						total -= value
					}
				}
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesAnIfWithNoElseWhereverItStands() {
		assertBraced(
			"""
			fun visit(values: List<Int>, kind: Int) {
				values.forEach { value ->
					if (value < 0) return@forEach
				}

				when (kind) {
					0 -> if (values.isEmpty()) println("empty")
					else -> println("other")
				}
			}
			""".trimIndent(),
			"""
			fun visit(values: List<Int>, kind: Int) {
				values.forEach { value ->
					if (value < 0) {
						return@forEach
					}
				}

				when (kind) {
					0 -> if (values.isEmpty()) {
						println("empty")
					}
					else -> println("other")
				}
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesTheLastStatementOfAStatementBranch() {
		assertBraced(
			"""
			fun report(value: Int, loud: Boolean) {
				if (loud) {
					if (value < 0) println("NEGATIVE") else println("POSITIVE")
				}

				when (value) {
					0 -> {
						if (loud) println("ZERO") else println("zero")
					}
				}

				try {
					if (loud) println("TRY") else println("try")
				} finally {
					if (loud) println("FINALLY") else println("finally")
				}
			}
			""".trimIndent(),
			"""
			fun report(value: Int, loud: Boolean) {
				if (loud) {
					if (value < 0) {
						println("NEGATIVE")
					} else {
						println("POSITIVE")
					}
				}

				when (value) {
					0 -> {
						if (loud) {
							println("ZERO")
						} else {
							println("zero")
						}
					}
				}

				try {
					if (loud) {
						println("TRY")
					} else {
						println("try")
					}
				} finally {
					if (loud) {
						println("FINALLY")
					} else {
						println("finally")
					}
				}
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesAValueIfAlone() {
		assertUnchanged(
			"""
			fun pick(flag: Boolean, values: List<Int>): Int {
				val first = if (flag) 1 else 2
				val second =
					if (flag) 3 else 4
				val third = values.map { value -> if (value < 0) 0 else value }
				val fourth = maxOf(if (flag) 5 else 6, first)
				val fifth = (if (flag) 7 else 8) + second

				return if (flag) third.size + fourth else fifth
			}

			fun short(flag: Boolean): Int = if (flag) 1 else 2
			""".trimIndent(),
		)
	}

	@Test
	fun leavesTheLastStatementOfALambdaAlone() {
		assertUnchanged(
			"""
			fun pick(values: List<Int>): List<Int> =
				values.map { value ->
					val doubled = value * 2
					if (doubled < 0) 0 else doubled
				}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesTheLastStatementOfAValueBranchAlone() {
		assertUnchanged(
			"""
			fun pick(flag: Boolean, value: Int): Int {
				val fromIf =
					if (flag) {
						println("flagged")
						if (value < 0) 0 else value
					} else {
						1
					}
				val fromWhen =
					when (value) {
						0 -> {
							println("zero")
							if (flag) 1 else 2
						}

						else -> if (flag) 3 else 4
					}
				val fromTry =
					try {
						if (flag) 5 else 6
					} catch (error: IllegalStateException) {
						if (flag) 7 else 8
					}

				return fromIf + fromWhen + fromTry
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesAChainWhoseValueAnOperatorReadsAlone() {
		assertUnchanged(
			"""
			fun pick(flag: Boolean, other: Boolean): String {
				val name =
					if (flag) {
						"flag"
					} else if (other) {
						null
					} else {
						null
					} ?: "neither"

				return name
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesBracedBranchesAlone() {
		assertUnchanged(
			"""
			fun report(value: Int) {
				if (value < 0) {
					println("negative")
				} else if (value > 0) {
					println("positive")
				} else {
					println("zero")
				}
			}
			""".trimIndent(),
		)
	}

	@Test
	fun bracesATopLevelScriptStatement() {
		val code =
			"""
			val enabled = true
			if (enabled) println("on") else println("off")
			""".trimIndent()
		val expected =
			"""
			val enabled = true
			if (enabled) {
				println("on")
			} else {
				println("off")
			}
			""".trimIndent()

		assertEquals(expected, format(code, script = true))
	}

	@Test
	fun reportsEachBracelessBranchAtItsBody() {
		val errors =
			lint(
				"""
				fun report(value: Int) {
					if (value < 0) println("negative") else println("positive")
					println("done")
				}
				""".trimIndent(),
			)

		assertEquals(listOf(2 to 17, 2 to 42), errors.map { error -> error.line to error.col })
		assertTrue(errors.all { error -> error.detail == "A statement `if` takes braces around each branch, with the body on its own line" })
		assertTrue(errors.all { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:statement-if-braces" })
	}

	@Test
	fun settlesWithTheOtherRulesInOneFormatCall() {
		val code =
			"""
			class Point {
				var x = 0

				override fun equals(other: Any?): Boolean {
					if (this === other) return true
					if (other !is Point) return false
					return x == other.x
				}

				fun visit(values: List<Int>) {
					values.forEach { value -> if (value < 0) return }
					for (value in values) if (value == 0) println("zero")
				}
			}
			""".trimIndent()
		val expected =
			"""
			class Point {
				var x = 0

				override fun equals(other: Any?): Boolean {
					if (this === other) {
						return true
					}

					if (other !is Point) {
						return false
					}

					return x == other.x
				}

				fun visit(values: List<Int>) {
					values.forEach { value ->
						if (value < 0) {
							return
						}
					}

					for (value in values) {
						if (value == 0) {
							println("zero")
						}
					}
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code, engineWithEveryRule))
		assertEquals(expected, format(expected, engineWithEveryRule))
		assertEquals(emptyList(), lint(expected, engineWithEveryRule))
	}
}