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
 * Drives NoCommentEmphasisRule through the real ktlint engine, alone, so each case shows exactly what
 * the rule reads as emphasis and what it leaves in place.
 */
class NoCommentEmphasisRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoCommentEmphasisRule() }))

	/**
	 * Formats a snippet with the rule alone.
	 *
	 * @param String code Kotlin source.
	 * @return String The formatted source.
	 */
	private fun format(code: String): String = engine.format(Code.fromSnippet(code)) { _ -> AutocorrectDecision.ALLOW_AUTOCORRECT }

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
		assertEquals(emptyList(), lint(code))
		assertEquals(code, format(code))
	}

	/**
	 * Asserts the rule rewrites a snippet to the expected source, and that the result is clean.
	 *
	 * @param String code Kotlin source holding emphasis in a comment.
	 * @param String expected The source with the markers gone.
	 */
	private fun assertStripped(code: String, expected: String) {
		assertEquals(expected, format(code))
		assertEquals(emptyList(), lint(expected))
	}

	@Test
	fun stripsBoldAndItalicsFromADocblock() {
		assertStripped(
			"""
			/**
			 * Packs the pages.  **Note:** the order is *not* stable, and _never_ was.
			 *
			 * @param Int count The ***exact*** page count.
			 */
			fun pack(count: Int) = count
			""".trimIndent(),
			"""
			/**
			 * Packs the pages.  Note: the order is not stable, and never was.
			 *
			 * @param Int count The exact page count.
			 */
			fun pack(count: Int) = count
			""".trimIndent(),
		)
	}

	@Test
	fun stripsEmphasisFromLineAndBlockComments() {
		assertStripped(
			"""
			fun pack(count: Int): Int {
				// **Important:** the count is (*always*) positive.
				/* The pages are _immutable_ here. */
				/*
				 * A longer note, with **two words** in bold.
				 */
				return count
			}
			""".trimIndent(),
			"""
			fun pack(count: Int): Int {
				// Important: the count is (always) positive.
				/* The pages are immutable here. */
				/*
				 * A longer note, with two words in bold.
				 */
				return count
			}
			""".trimIndent(),
		)
	}

	@Test
	fun stripsEmphasisAroundADocblockLink() {
		assertStripped(
			"""
			/**
			 * Reads the header.  See **[pack]** for the writer, and *[unpack] too*.
			 */
			fun read() = 0
			""".trimIndent(),
			"""
			/**
			 * Reads the header.  See [pack] for the writer, and [unpack] too.
			 */
			fun read() = 0
			""".trimIndent(),
		)
	}

	@Test
	fun leavesArithmeticAlone() {
		assertUnchanged(
			"""
			/**
			 * Blends as p = base + w*d, or a * b * c written out, or (a)*(b)*(c) written tight.
			 * The area is (x1 - x0)*(y1 - y0)*0.5, and 2 ** 3 is eight.
			 */
			fun blend(): Int {
				// total *= 2 and then total *= 3
				return 0
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesGlobsPathsAndNamesAlone() {
		assertUnchanged(
			"""
			/**
			 * Reads snake_case_name, MAX_VALUE, __init__, the _ placeholder, and both _state and _events.
			 * Passes *args and *rest through, as in call(*args, *rest), and matches *.kt and *.kts.
			 */
			fun scan(): Int {
				// Scans **/build/** and module/*/src/commonMain/**/*.kt, and src/*/a beside src/*/b.
				return 0
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesCodeSpansAndFencedBlocksAlone() {
		assertUnchanged(
			"""
			/**
			 * Write `**kwargs` or `_x_` in a span, and `*text*` to show the marker itself.
			 *
			 * ```
			 * val bold = "**not prose**"
			 * val italic = "*also not prose*"
			 * ```
			 */
			fun describe(): Int {
				// The marker is `*text*`, and an inline note is `/* */`, never prose.
				return 0
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesBannersListsAndDocblockFramingAlone() {
		assertUnchanged(
			"""
			// ************************************
			// *** Section ***
			// ************************************

			/**
			 * A list:
			 *
			 *  * first
			 *  * second
			 *  - third
			 */
			fun list() = 0

			/** A one-line docblock. */
			fun one() = 1
			""".trimIndent(),
		)
	}

	@Test
	fun leavesMarkersInCodeAndStringsAlone() {
		assertUnchanged(
			"""
			fun render(values: IntArray): String {
				val product = values[0] * values[1] * values[2]
				val spread = listOf(*values.toTypedArray())

				return "**bold** and *italic* and _underscored_ " + product + spread
			}
			""".trimIndent(),
		)
	}

	@Test
	fun reportsEachRunAtItsOpeningMarker() {
		val errors =
			lint(
				"""
				/**
				 * Packs the pages.  **Note:** the order is *not* stable.
				 */
				fun pack() = 0
				""".trimIndent(),
			)

		assertEquals(listOf(2 to 22, 2 to 45), errors.map { error -> error.line to error.col })
		assertEquals("A comment takes no Markdown emphasis; drop the `**` markers", errors[0].detail)
		assertEquals("A comment takes no Markdown emphasis; drop the `*` markers", errors[1].detail)
		assertTrue(errors.all { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:no-comment-emphasis" })
	}
}