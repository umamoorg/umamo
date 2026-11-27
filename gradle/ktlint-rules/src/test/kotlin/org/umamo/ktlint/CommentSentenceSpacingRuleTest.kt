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
 * Drives CommentSentenceSpacingRule through the real ktlint engine, alone, so each case shows exactly
 * which periods it reads as the end of a sentence.
 */
class CommentSentenceSpacingRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { CommentSentenceSpacingRule() }))

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
	 * Asserts the rule rewrites a snippet to the expected source, and that the result is clean and stable.
	 *
	 * @param String code Kotlin source with a single space after a sentence.
	 * @param String expected The source with each such gap widened.
	 */
	private fun assertWidened(code: String, expected: String) {
		assertEquals(expected, format(code))
		assertEquals(expected, format(expected))
		assertEquals(emptyList(), lint(expected))
	}

	@Test
	fun widensTheGapAfterASentenceInEveryKindOfComment() {
		assertWidened(
			"""
			/**
			 * Packs the pages. The order is stable. Nothing else changes.
			 *
			 * @param Int count The page count. Zero packs nothing.
			 * @return Int The pages written. Never negative.
			 */
			fun pack(count: Int): Int {
				// Count the pages. Then write them.
				/* Write once. Read many times. */
				return count
			}
			""".trimIndent(),
			"""
			/**
			 * Packs the pages.  The order is stable.  Nothing else changes.
			 *
			 * @param Int count The page count.  Zero packs nothing.
			 * @return Int The pages written.  Never negative.
			 */
			fun pack(count: Int): Int {
				// Count the pages.  Then write them.
				/* Write once.  Read many times. */
				return count
			}
			""".trimIndent(),
		)
	}

	@Test
	fun readsASentenceThatEndsOrOpensOnABracketOrACodeSpan() {
		assertWidened(
			"""
			/**
			 * Reads the header (all of it). Then the body [see pack]. The tail is `raw`. It is
			 * small. [pack] writes it. `unpack` reads it. (This is rare.) The GPU does the rest. Done.
			 */
			fun read() = 0
			""".trimIndent(),
			"""
			/**
			 * Reads the header (all of it).  Then the body [see pack].  The tail is `raw`.  It is
			 * small.  [pack] writes it.  `unpack` reads it.  (This is rare.) The GPU does the rest.  Done.
			 */
			fun read() = 0
			""".trimIndent(),
		)
	}

	@Test
	fun leavesAbbreviationsAndInitialsAlone() {
		assertUnchanged(
			"""
			/**
			 * Reads several formats, e.g. PSD and CLIP, i.e. Layered art, etc. Flat rasters too, vs. Krita's
			 * own (cf. KRA), a.k.a. The open one, in the U.S. And elsewhere, after J. Shewchuk, approx. Ten.
			 */
			fun read() = 0
			""".trimIndent(),
		)
	}

	@Test
	fun leavesListMarkersAlone() {
		assertUnchanged(
			"""
			/**
			 * Three steps:
			 *
			 *  1. Read the header.
			 *  2. Read the body.
			 * 10. Read the tail.
			 *  a. A lettered item.
			 */
			fun read(): Int {
				// 1. First pass.
				return 0
			}
			""".trimIndent(),
		)
	}

	@Test
	fun widensAfterANumberAPathOrAFileName() {
		assertWidened(
			"""
			// The format moved on in version 5. The reader follows it. It lives in module/format. And it reads main.xml. That is all.
			val version = 5
			""".trimIndent(),
			"""
			// The format moved on in version 5.  The reader follows it.  It lives in module/format.  And it reads main.xml.  That is all.
			val version = 5
			""".trimIndent(),
		)
	}

	@Test
	fun leavesNullableTypesAndOtherMarksAlone() {
		assertUnchanged(
			"""
			/**
			 * Is it ready? Then read it! Otherwise: Wait.
			 *
			 * @param String? name The name, or null.
			 * @return Int? The size, or null.
			 */
			fun read(name: String?): Int? = name?.length
			""".trimIndent(),
		)
	}

	@Test
	fun leavesGapsThatAreAlreadyWideOrNotBreaksAlone() {
		assertUnchanged(
			"""
			/**
			 * Already wide.  Also wide.   Even wider.
			 * A lowercase follow-up. see the reader. 3 things. "Quoted" next.
			 * An ellipsis... Then more.
			 * Ends the line.
			 * Next line.
			 */
			fun read() = 0
			""".trimIndent(),
		)
	}

	@Test
	fun leavesCodeSpansAndFencedBlocksAlone() {
		assertUnchanged(
			"""
			/**
			 * Write `first. Second` in a span, or `a. B`.
			 *
			 * ```
			 * val text = "One. Two."
			 * ```
			 */
			fun describe(): Int {
				// The literal is `One. Two.` in the sample.
				return 0
			}
			""".trimIndent(),
		)
	}

	@Test
	fun leavesStringsAndCodeAlone() {
		assertUnchanged(
			"""
			fun text(): String = "One. Two. Three."
			""".trimIndent(),
		)
	}

	@Test
	fun reportsEachGapAtItsSpace() {
		val errors =
			lint(
				"""
				/**
				 * Packs the pages. The order is stable. Done.
				 */
				fun pack() = 0
				""".trimIndent(),
			)

		assertEquals(listOf(2 to 20, 2 to 41), errors.map { error -> error.line to error.col })
		assertTrue(errors.all { error -> error.detail == "A sentence in a comment ends with two spaces after its period" })
		assertTrue(errors.all { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:comment-sentence-spacing" })
	}
}