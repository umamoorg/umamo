package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives FunctionDocblockRule through the real ktlint engine, alone, so each case shows exactly which
 * declarations it asks a docblock of.
 */
class FunctionDocblockRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { FunctionDocblockRule() }))

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
	 * Lints a snippet and reduces each violation to where it is and what it says.
	 *
	 * @param String code Kotlin source.
	 * @return List<String> One "line:column message" per violation.
	 */
	private fun problems(code: String): List<String> = lint(code).map { error -> "${error.line}:${error.col} ${error.detail}" }

	@Test
	fun acceptsDocumentedFunctions() {
		val code =
			"""
			/** Scales a length. */
			fun scale(length: Float): Float = length * 2

			/**
			 * A length.
			 */
			class Length(val value: Float) {
				/** Doubles the length. */
				@Deprecated("use scale")
				fun doubled(): Float = value * 2
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun reportsATopLevelFunctionWithoutADocblock() {
		val code =
			"""
			fun scale(length: Float): Float = length * 2

			// A line comment is not a docblock.
			fun halve(length: Float): Float = length / 2

			/* Neither is a block comment. */
			@Suppress("unused")
			private fun String.pad(width: Int): String = padEnd(width)
			""".trimIndent()

		assertEquals(
			listOf(
				"1:5 Function `scale` has no docblock",
				"4:5 Function `halve` has no docblock",
				"8:20 Function `pad` has no docblock",
			),
			problems(code),
		)
	}

	@Test
	fun reportsMembersAndInterfaceFunctions() {
		val code =
			"""
			interface Shape {
				fun area(): Float
			}

			class Square(val side: Float) {
				operator fun plus(other: Square): Square = Square(side + other.side)

				companion object {
					fun unit(): Square = Square(1f)
				}
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"2:6 Function `area` has no docblock",
				"6:15 Function `plus` has no docblock",
				"9:7 Function `unit` has no docblock",
			),
			problems(code),
		)
	}

	@Test
	fun reportsALocalFunction() {
		val code =
			"""
			/** Sums a tree. */
			fun sum(values: List<Int>): Int {
				fun walk(index: Int): Int = if (index == values.size) 0 else values[index] + walk(index + 1)

				return walk(0)
			}
			""".trimIndent()

		assertEquals(listOf("3:6 Function `walk` has no docblock"), problems(code))
	}

	@Test
	fun leavesAnOverrideAlone() {
		val code =
			"""
			/** A shape. */
			interface Shape {
				/** The shape's area. */
				fun area(): Float
			}

			class Square(val side: Float) : Shape {
				override fun area(): Float = side * side

				override fun equals(other: Any?): Boolean {
					if (this === other) {
						return true
					}

					return other is Square && other.side == side
				}

				override fun hashCode(): Int = side.hashCode()
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun leavesLambdasAnonymousFunctionsAccessorsAndConstructorsAlone() {
		val code =
			"""
			class Counter(start: Int) {
				constructor() : this(0)

				var count: Int = start
					get() = field
					set(value) {
						field = value
					}

				val increment: (Int) -> Int = { step -> count + step }
				val decrement = fun(step: Int): Int = count - step
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun reportsEveryViolationAsNotCorrectableUnderItsOwnId() {
		val errors = lint("fun scale(length: Float): Float = length * 2")

		assertEquals(1, errors.size)
		assertTrue(errors.none { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:function-docblock" })
	}
}