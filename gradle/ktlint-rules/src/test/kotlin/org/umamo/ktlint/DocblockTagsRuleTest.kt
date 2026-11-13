package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives DocblockTagsRule through the real ktlint engine, alone, under each of its two modes: `strict`,
 * the default, and `drift_only`, which an .editorconfig section turns on for a set of files.
 */
class DocblockTagsRuleTest {
	private val strictEngine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { DocblockTagsRule() }))

	private val driftOnlyEngine =
		KtLintRuleEngine(
			ruleProviders = setOf(RuleProvider { DocblockTagsRule() }),
			editorConfigOverride = EditorConfigOverride.from(DOCBLOCK_TAGS_MODE_PROPERTY to DocblockTagsMode.drift_only),
		)

	/**
	 * Lints a snippet with the rule alone.
	 *
	 * @param String code Kotlin source.
	 * @param KtLintRuleEngine linter The engine to lint with, which decides the mode.
	 * @return List<LintError> Every violation the rule reports.
	 */
	private fun lint(code: String, linter: KtLintRuleEngine = strictEngine): List<LintError> {
		val errors = ArrayList<LintError>()
		linter.lint(Code.fromSnippet(code)) { error -> errors.add(error) }

		return errors
	}

	/**
	 * Lints a snippet and reduces each violation to where it is and what it says.
	 *
	 * @param String code Kotlin source.
	 * @param KtLintRuleEngine linter The engine to lint with, which decides the mode.
	 * @return List<String> One "line:column message" per violation.
	 */
	private fun problems(code: String, linter: KtLintRuleEngine = strictEngine): List<String> =
		lint(code, linter).map { error -> "${error.line}:${error.col} ${error.detail}" }

	@Test
	fun acceptsACompleteTagBlock() {
		val code =
			"""
			/**
			 * Scales a length.
			 *
			 * @param Float length The length.
			 * @param Float factor The scale.
			 * @return Float The scaled length.
			 * @see unscale
			 */
			fun scale(length: Float, factor: Float): Float = length * factor

			/**
			 * Logs a message.
			 *
			 * @param String message The text.
			 */
			fun log(message: String) {
				println(message)
			}

			/** Clears the log. */
			fun clear() {
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
		assertEquals(emptyList(), problems(code, driftOnlyEngine))
	}

	@Test
	fun leavesAFunctionWithoutADocblockAlone() {
		val code =
			"""
			fun scale(length: Float, factor: Float): Float = length * factor
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun strictModeWantsEveryTagOfASummaryOnlyDocblock() {
		val code =
			"""
			/** Scales a length. */
			fun scale(length: Float, factor: Float): Float = length * factor
			""".trimIndent()

		assertEquals(
			listOf(
				"2:11 The docblock has no `@param` for `length`",
				"2:26 The docblock has no `@param` for `factor`",
				"2:42 The docblock has no `@return` for the `Float` this function returns",
			),
			problems(code),
		)
	}

	@Test
	fun driftOnlyModeAcceptsASummaryOnlyDocblock() {
		val code =
			"""
			/** Scales a length. */
			fun scale(length: Float, factor: Float): Float = length * factor
			""".trimIndent()

		assertEquals(emptyList(), problems(code, driftOnlyEngine))
	}

	@Test
	fun reportsAParameterTheTagsLeaveOutInBothModes() {
		val code =
			"""
			/**
			 * Draws a ring.
			 *
			 * @param Float radius The ring's radius.
			 * @param Function drawStroke Draws one stroke.
			 */
			fun ring(radius: Float, dashLength: Float, drawStroke: () -> Unit) {
				drawStroke()
			}
			""".trimIndent()
		val expected = listOf("7:25 The docblock has no `@param` for `dashLength`")

		assertEquals(expected, problems(code))
		assertEquals(expected, problems(code, driftOnlyEngine))
	}

	@Test
	fun reportsATagForAParameterThatIsGoneInBothModes() {
		val code =
			"""
			/**
			 * Draws a ring.
			 *
			 * @param Model model The model.
			 * @param Float radius The ring's radius.
			 */
			fun ring(radius: Float) {
				println(radius)
			}
			""".trimIndent()
		val expected = listOf("4:4 `@param` names `model`, which is not a parameter of this function")

		assertEquals(expected, problems(code))
		assertEquals(expected, problems(code, driftOnlyEngine))
	}

	@Test
	fun reportsTagsOutOfSignatureOrder() {
		val code =
			"""
			/**
			 * Scales a length.
			 *
			 * @param Float factor The scale.
			 * @param Float length The length.
			 * @return Float The scaled length.
			 */
			fun scale(length: Float, factor: Float): Float = length * factor
			""".trimIndent()

		assertEquals(listOf("4:4 `@param` tags follow the signature order; `factor` is out of place"), problems(code))
	}

	@Test
	fun reportsATagWrittenWithoutItsType() {
		val code =
			"""
			/**
			 * Logs a message.
			 *
			 * @param message The text.
			 */
			fun log(message: String) {
				println(message)
			}
			""".trimIndent()

		assertEquals(listOf("4:4 `@param` is written `@param Type name description`; `message` is missing its type"), problems(code))
	}

	@Test
	fun reportsAParameterDocumentedTwice() {
		val code =
			"""
			/**
			 * Logs a message.
			 *
			 * @param String message The text.
			 * @param String message The text again.
			 */
			fun log(message: String) {
				println(message)
			}
			""".trimIndent()

		assertEquals(listOf("5:4 `@param` for `message` appears more than once"), problems(code))
	}

	@Test
	fun reportsAReturnTagOnAFunctionThatReturnsNothingInBothModes() {
		val code =
			"""
			/**
			 * Logs a message.
			 *
			 * @param String message The text.
			 * @return Unit Nothing.
			 */
			fun log(message: String) {
				println(message)
			}

			/**
			 * Clears the log.
			 *
			 * @return Unit Nothing.
			 */
			fun clear(): Unit = println()

			interface Log {
				/**
				 * Closes the log.
				 *
				 * @return Boolean Whether it closed.
				 */
				fun close()
			}
			""".trimIndent()
		val expected =
			listOf(
				"5:4 `@return` documents a value, and this function returns none",
				"14:4 `@return` documents a value, and this function returns none",
				"22:5 `@return` documents a value, and this function returns none",
			)

		assertEquals(expected, problems(code))
		assertEquals(expected, problems(code, driftOnlyEngine))
	}

	@Test
	fun leavesTheReturnOfAnUndeclaredExpressionBodyToTheWriter() {
		val code =
			"""
			/**
			 * Reads the area.
			 *
			 * @param Float width The width.
			 * @return Float The area.
			 */
			fun area(width: Float) = width * width

			/**
			 * Reads the area.
			 *
			 * @param Float width The width.
			 */
			fun areaAgain(width: Float) = width * width

			/**
			 * Gives up.
			 *
			 * @param String reason Why.
			 */
			fun fail(reason: String): Nothing = throw IllegalStateException(reason)
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun readsTheNameAfterATypeThatHoldsSpaces() {
		val code =
			"""
			/**
			 * Samples a mask.
			 *
			 * @param Map<String, Float> pose The pose.
			 * @param (Int, Int) -> Boolean opaque Whether a pixel is opaque.
			 * @param suspend (Int) -> Unit work The work to run.
			 * @param @Composable () -> Unit content The content.
			 * @param ((Int) -> Unit)? onChange Called on a change.
			 * @param (Int) -> (Int) -> Unit curried A curried callback.
			 * @param Function   onDone  Called at the end.
			 */
			fun sample(
				pose: Map<String, Float>,
				opaque: (Int, Int) -> Boolean,
				work: suspend (Int) -> Unit,
				content: @Composable () -> Unit,
				onChange: ((Int) -> Unit)?,
				curried: (Int) -> (Int) -> Unit,
				onDone: () -> Unit,
			) {
				onDone()
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun letsATagDocumentATypeParameter() {
		val code =
			"""
			/**
			 * Wraps an item.
			 *
			 * @param T The item's type.
			 * @param T item The item.
			 * @return List<T> The item, alone in a list.
			 */
			fun <T> wrap(item: T): List<T> = listOf(item)
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun readsTheReturnTypeAfterTheParametersNotTheReceiver() {
		val code =
			"""
			/**
			 * Pads a string.
			 *
			 * @param Int width The width to reach.
			 */
			fun String.pad(width: Int) {
				println(padEnd(width))
			}

			/**
			 * Pads a string.
			 *
			 * @param Int width The width to reach.
			 */
			fun String.padded(width: Int): String = padEnd(width)
			""".trimIndent()

		assertEquals(listOf("15:32 The docblock has no `@return` for the `String` this function returns"), problems(code))
	}

	@Test
	fun reportsTheFirstTagOutOfTheHouseOrderInBothModes() {
		val code =
			"""
			/**
			 * Scales a length.
			 *
			 * @return Float The scaled length.
			 * @param Float length The length.
			 */
			fun scale(length: Float): Float = length * 2

			/**
			 * Halves a length.
			 *
			 * @param Float length The length.
			 * @see scale
			 * @return Float The halved length.
			 */
			fun halve(length: Float): Float = length / 2
			""".trimIndent()
		val expected =
			listOf(
				"5:4 Docblock tags go `@param`, then `@return`, then the rest; `@param` is out of place",
				"14:4 Docblock tags go `@param`, then `@return`, then the rest; `@return` is out of place",
			)

		assertEquals(expected, problems(code))
		assertEquals(expected, problems(code, driftOnlyEngine))
	}

	@Test
	fun readsNoTagOutOfACodeSample() {
		val code =
			"""
			/**
			 * Shows a panel.  Use it as:
			 *
			 * ```
			 * @Composable
			 * @param is not a tag here
			 * fun Screen() = Panel("title")
			 * ```
			 *
			 * @param String title The panel's title.
			 */
			fun panel(title: String) {
				println(title)
			}
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun readsTheTagsOfAOneLineDocblock() {
		val code =
			"""
			/** @return Int The count. */
			fun count(): Int = 0

			/** @return Int The count. */
			fun reset() {
			}
			""".trimIndent()

		assertEquals(listOf("4:5 `@return` documents a value, and this function returns none"), problems(code))
	}

	@Test
	fun driftOnlyModeAsksForNoReturnTag() {
		val code =
			"""
			/**
			 * Scales a length.
			 *
			 * @param Float length The length.
			 */
			fun scale(length: Float): Float = length * 2
			""".trimIndent()

		assertEquals(listOf("6:27 The docblock has no `@return` for the `Float` this function returns"), problems(code))
		assertEquals(emptyList(), problems(code, driftOnlyEngine))
	}

	@Test
	fun reportsATagThatSaysNothingAfterItsTypeAndNameInBothModes() {
		val code =
			"""
			/**
			 * Scales a length.
			 *
			 * @param Float length
			 * @param Map<String, Float> factors
			 * @param (Float) -> Float easing The easing curve.
			 * @return Float
			 */
			fun scale(length: Float, factors: Map<String, Float>, easing: (Float) -> Float): Float = easing(length)

			/** @return (Float) -> Float */
			fun easing(): (Float) -> Float = { value -> value }
			""".trimIndent()

		val expected =
			listOf(
				"4:4 `@param` is written `@param Type name description`; `length` has no description",
				"5:4 `@param` is written `@param Type name description`; `factors` has no description",
				"7:4 `@return` is written `@return Type description`; it has no description",
				"11:5 `@return` is written `@return Type description`; it has no description",
			)

		assertEquals(expected, problems(code))
		assertEquals(expected, problems(code, driftOnlyEngine))
	}

	@Test
	fun readsADescriptionThatStartsOnTheLineBelowItsTag() {
		val code =
			"""
			/**
			 * Scales a length.
			 *
			 * @param Float length
			 *     The length, in canvas units.
			 * @param (Float) -> Float easing
			 *     The easing curve.
			 * @return Float
			 *     The scaled length.
			 */
			fun scale(length: Float, easing: (Float) -> Float): Float = easing(length)
			""".trimIndent()

		assertEquals(emptyList(), problems(code))
	}

	@Test
	fun reportsATagWithNeitherTypeNorDescriptionOnce() {
		val code =
			"""
			/**
			 * Logs a line.
			 *
			 * @param message
			 */
			fun log(message: String) {
			}
			""".trimIndent()

		assertEquals(listOf("4:4 `@param` is written `@param Type name description`; `message` is missing its type"), problems(code))
	}

	@Test
	fun reportsEveryViolationAsNotCorrectableUnderItsOwnId() {
		val errors =
			lint(
				"""
				/** Scales a length. */
				fun scale(length: Float): Float = length * 2
				""".trimIndent(),
			)

		assertEquals(2, errors.size)
		assertTrue(errors.none { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:docblock-tags" })
	}
}