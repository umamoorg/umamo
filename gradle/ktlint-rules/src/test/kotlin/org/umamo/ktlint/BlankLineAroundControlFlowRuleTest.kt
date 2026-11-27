package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives BlankLineAroundControlFlowRule through the real ktlint engine, alone, so each case shows
 * exactly what the rule changes and nothing a standard rule would.
 */
class BlankLineAroundControlFlowRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { BlankLineAroundControlFlowRule() }))

	/**
	 * Formats a snippet with the rule.
	 *
	 * @param String code Kotlin source.
	 * @param Boolean script True to parse the snippet as a .kts script.
	 * @return String The formatted source.
	 */
	private fun format(code: String, script: Boolean = false): String = engine.format(Code.fromSnippet(code, script)) { _ -> AutocorrectDecision.ALLOW_AUTOCORRECT }

	/**
	 * Lints a snippet with the rule.
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
	 * @param String code Kotlin source that already follows the rule.
	 * @param Boolean script True to parse the snippet as a .kts script.
	 */
	private fun assertUnchanged(code: String, script: Boolean = false) {
		assertEquals(code, format(code, script))

		if (!script) {
			assertEquals(emptyList(), lint(code))
		}
	}

	@Test
	fun insertsBlankLinesAroundAnIfAndBeforeTheReturn() {
		val code =
			"""
			fun clamp(value: Int): Int {
				val limit = 10
				if (value > limit) {
					return limit
				}
				val doubled = value * 2
				return doubled
			}
			""".trimIndent()
		val expected =
			"""
			fun clamp(value: Int): Int {
				val limit = 10

				if (value > limit) {
					return limit
				}

				val doubled = value * 2

				return doubled
			}
			""".trimIndent()

		assertEquals(expected, format(code))
		assertEquals(expected, format(expected))
	}

	@Test
	fun reportsEachMissingLineAtTheStatementBelowIt() {
		val code =
			"""
			fun clamp(value: Int): Int {
				val limit = 10
				if (value > limit) {
					return limit
				}
				return value
			}
			""".trimIndent()

		val errors = lint(code)

		assertEquals(listOf(3, 6), errors.map { error -> error.line })
		assertEquals("Expected a blank line before this `if` block", errors[0].detail)
		assertEquals("Expected a blank line after the `if` block above", errors[1].detail)
		assertTrue(errors.all { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:blank-line-around-control-flow" })
	}

	@Test
	fun leavesTheFirstAndOnlyStatementsOfABlockAlone() {
		assertUnchanged(
			"""
			fun first(values: List<Int>): Int {
				if (values.isEmpty()) {
					return 0
				}

				for (value in values) {
					println(value)
				}

				return values.first()
			}
			""".trimIndent(),
		)
	}

	@Test
	fun treatsEveryControlFlowKeywordAsABlock() {
		val code =
			"""
			fun all(values: List<Int>) {
				var index = 0
				while (index < values.size) {
					index++
				}
				do {
					index--
				} while (index > 0)
				when (index) {
					0 -> println("zero")
					else -> println("other")
				}
				try {
					println(values[index])
				} catch (exception: IndexOutOfBoundsException) {
					println("none")
				}
				println("done")
			}
			""".trimIndent()
		val expected =
			"""
			fun all(values: List<Int>) {
				var index = 0

				while (index < values.size) {
					index++
				}

				do {
					index--
				} while (index > 0)

				when (index) {
					0 -> println("zero")
					else -> println("other")
				}

				try {
					println(values[index])
				} catch (exception: IndexOutOfBoundsException) {
					println("none")
				}

				println("done")
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun leavesValueIfsAndJumpsInsideExpressionsAlone() {
		assertUnchanged(
			"""
			fun pick(value: Int?, flag: Boolean): Int {
				val present = value ?: return 0
				val sign =
					if (flag) {
						1
					} else {
						-1
					}
				val scaled = present * sign
				println(scaled)

				return if (flag) scaled else -scaled
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesAOneLineIfExpressionAtTheEndOfALambdaAlone() {
		assertUnchanged(
			"""
			fun capped(values: List<Int>): List<Int> =
				values.map { value ->
					val doubled = value * 2
					if (doubled > 10) 10 else doubled
				}
			""".trimIndent(),
		)
	}

	@Test
	fun appliesInsideLambdaBodies() {
		val code =
			"""
			fun visit(values: List<Int>) {
				values.forEach { value ->
					val doubled = value * 2
					if (doubled > 10) {
						return@forEach
					}
					println(doubled)
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun visit(values: List<Int>) {
				values.forEach { value ->
					val doubled = value * 2

					if (doubled > 10) {
						return@forEach
					}

					println(doubled)
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun putsTheBlankLineAboveTheCommentsThatLeadAStatement() {
		val code =
			"""
			fun guarded(value: Int): Int {
				val limit = 10
				// Past the limit the value is clamped.
				// The clamp is inclusive.
				if (value > limit) {
					return limit
				}
				// The common case.
				return value
			}
			""".trimIndent()
		val expected =
			"""
			fun guarded(value: Int): Int {
				val limit = 10

				// Past the limit the value is clamped.
				// The clamp is inclusive.
				if (value > limit) {
					return limit
				}

				// The common case.
				return value
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun keepsATrailingCommentWithTheStatementItTrails() {
		val code =
			"""
			fun trailing(value: Int): Int {
				val limit = 10 // inclusive
				if (value > limit) {
					return limit
				} // clamped
				return value
			}
			""".trimIndent()
		val expected =
			"""
			fun trailing(value: Int): Int {
				val limit = 10 // inclusive

				if (value > limit) {
					return limit
				} // clamped

				return value
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun separatesEveryJumpKind() {
		val code =
			"""
			fun jumps(values: List<Int>) {
				for (value in values) {
					println(value)
					if (value < 0) {
						println("negative")
						throw IllegalStateException("negative")
					}
					if (value == 0) {
						println("zero")
						continue
					}
					println("positive")
					break
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun jumps(values: List<Int>) {
				for (value in values) {
					println(value)

					if (value < 0) {
						println("negative")

						throw IllegalStateException("negative")
					}

					if (value == 0) {
						println("zero")

						continue
					}

					println("positive")

					break
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun treatsALabeledLoopAsABlock() {
		val code =
			"""
			fun labeled(rows: List<List<Int>>) {
				var found = false
				outer@ for (row in rows) {
					for (cell in row) {
						found = cell == 0
						break@outer
					}
				}
				println(found)
			}
			""".trimIndent()
		val expected =
			"""
			fun labeled(rows: List<List<Int>>) {
				var found = false

				outer@ for (row in rows) {
					for (cell in row) {
						found = cell == 0

						break@outer
					}
				}

				println(found)
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun appliesInsideWhenBranchBlocks() {
		val code =
			"""
			fun branch(value: Int): String {
				when (value) {
					0 -> {
						println("zero")
						return "zero"
					}

					else -> return "other"
				}
			}
			""".trimIndent()
		val expected =
			"""
			fun branch(value: Int): String {
				when (value) {
					0 -> {
						println("zero")

						return "zero"
					}

					else -> return "other"
				}
			}
			""".trimIndent()

		assertEquals(expected, format(code))
	}

	@Test
	fun leavesStatementsSharingALineAlone() {
		assertUnchanged(
			"""
			fun shared(value: Int): Int {
				println(value); return value
			}
			""".trimIndent(),
		)
	}

	@Test
	fun appliesAtTheTopLevelOfAScript() {
		val code =
			"""
			val enabled = true
			if (enabled) {
				println("on")
			}
			println("done")
			""".trimIndent()
		val expected =
			"""
			val enabled = true

			if (enabled) {
				println("on")
			}

			println("done")
			""".trimIndent()

		assertEquals(expected, format(code, script = true))
	}
}