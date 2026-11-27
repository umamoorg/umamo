package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives NoSingleLetterNameRule through the real ktlint engine, alone, so each case shows exactly which
 * declared names it reports.
 */
class NoSingleLetterNameRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoSingleLetterNameRule() }))

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
	fun reportsSingleLetterParametersAndLocals() {
		val code =
			"""
			fun blend(a: Float, weight: Float): Float {
				val t = weight * 2
				var n = 0

				return a * t + n
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"1:11 `a` is a single-letter name; spell out what it is",
				"2:6 `t` is a single-letter name; spell out what it is",
				"3:6 `n` is a single-letter name; spell out what it is",
			),
			problems(code),
		)
	}

	@Test
	fun reportsLoopLambdaCatchAndDestructuredNames() {
		val code =
			"""
			fun walk(values: List<Pair<Int, Int>>): Int {
				var total = 0

				for (i in values.indices) {
					total += i
				}

				for ((k, w) in values) {
					total += k + w
				}

				val (h, width) = values.first()
				values.forEach { p -> total += p.first }
				values.sortedWith { a, second -> a.first - second.first }

				try {
					total += h + width
				} catch (e: IllegalStateException) {
					total = 0
				}

				return total
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"4:7 `i` is a single-letter name; spell out what it is",
				"8:8 `k` is a single-letter name; spell out what it is",
				"8:11 `w` is a single-letter name; spell out what it is",
				"12:7 `h` is a single-letter name; spell out what it is",
				"13:19 `p` is a single-letter name; spell out what it is",
				"14:22 `a` is a single-letter name; spell out what it is",
				"18:11 `e` is a single-letter name; spell out what it is",
			),
			problems(code),
		)
	}

	@Test
	fun reportsPropertiesConstructorParametersAndFunctionNames() {
		val code =
			"""
			val q = 1

			class Size(val w: Int, h: Int) {
				val area = w * h

				fun f(): Int = area
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"1:5 `q` is a single-letter name; spell out what it is",
				"3:16 `w` is a single-letter name; spell out what it is",
				"3:24 `h` is a single-letter name; spell out what it is",
				"6:6 `f` is a single-letter name; spell out what it is",
			),
			problems(code),
		)
	}

	@Test
	fun permitsCoordinateAndColorChannelLetters() {
		val code =
			"""
			class Vertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

			fun pack(r: Int, g: Int, b: Int): Int {
				val (x, y) = r to g

				return x + y + b
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun leavesTypeParametersUnderscoresAndLongerNamesAlone() {
		val code =
			"""
			fun <T> first(items: List<T>, id: Int): T {
				items.forEach { _ -> println(id) }

				return items[id]
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun leavesAnOverridesParametersAlone() {
		val code =
			"""
			class ByLength : Comparator<String> {
				override fun compare(a: String, b: String): Int = a.length - b.length
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun leavesNamesThatAreOnlyUsedAlone() {
		val code =
			"""
			fun area(size: Size): Int = size.w * size.h + size.f(a = 1)
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun honorsASuppressionOnAMathFunction() {
		val code =
			"""
			@Suppress("ktlint:umamo:no-single-letter-name")
			fun twoProductSum(a: Double, b: Double, c: Double, d: Double): Double {
				val p = a * b
				val q = c * d

				return p + q
			}

			fun scale(a: Double): Double = a * 2
			""".trimIndent()

		assertEquals(listOf("9:11 `a` is a single-letter name; spell out what it is"), problems(code))
	}

	@Test
	fun reportsEveryViolationAsNotCorrectableUnderItsOwnId() {
		val errors = lint("fun scale(a: Double): Double = a * 2")

		assertEquals(1, errors.size)
		assertTrue(errors.none { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:no-single-letter-name" })
	}
}