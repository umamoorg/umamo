package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.RuleExecution
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.createRuleExecutionEditorConfigProperty
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoPrintAndReturnGateRule through the real ktlint engine, alone, so each case shows which shapes
 * it reads as a test announcing that it cannot run and then returning.
 */
class NoPrintAndReturnGateRuleTest {
	// The repository's .editorconfig turns the rule on for test sources only, and a snippet is not one.
	private val engine =
		KtLintRuleEngine(
			ruleProviders = setOf(RuleProvider { NoPrintAndReturnGateRule() }),
			editorConfigOverride = EditorConfigOverride.from(RuleId("umamo:no-print-and-return-gate").createRuleExecutionEditorConfigProperty() to RuleExecution.enabled),
		)

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
	fun reportsAPrintAndAReturnInAnIfBody() {
		val code =
			"""
			fun readsTheSample() {
				val file = sample

				if (file == null) {
					println("cmo3.sample not present; skipping")

					return
				}

				check(file.isFile)
			}
			""".trimIndent()

		assertEquals(listOf("5:3"), reported(code))
	}

	@Test
	fun reportsTheGateInARunBlockAfterAnElvis() {
		val code =
			"""
			fun readsTheSample() =
				runBlocking {
					val file =
						sample ?: run {
							println("cmo3.sample not present; skipping")
							// Nothing to read.
							return@runBlocking
						}

					check(file.isFile)
				}
			""".trimIndent()

		assertEquals(listOf("5:5"), reported(code))
	}

	@Test
	fun reportsAReturnOfThePrintItself() {
		val code =
			"""
			fun readsTheSample() {
				val file = sample ?: return println("cmo3.sample not present; skipping")

				check(file.isFile)
			}

			fun readsTheOtherSample() =
				runBlocking {
					val file = sample ?: return@runBlocking println("moc3.sample not present; skipping")

					check(file.isFile)
				}
			""".trimIndent()

		assertEquals(listOf("2:23", "9:24"), reported(code))
	}

	@Test
	fun leavesAReturnThatCarriesAValueAlone() {
		val code =
			"""
			fun measure(file: File): Tally? {
				if (!file.isFile) {
					println("the sample is not on disk; skipped")

					return null
				}

				return Tally(file.length())
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesAPrintThatNoReturnFollowsAlone() {
		val code =
			"""
			fun checksEverySample() {
				for (file in samples) {
					if (!file.isFile) {
						println("the sample is not on disk; skipping")

						continue
					}

					println("checked the sample")
					check(file.length() > 0)
				}

				log.println("done")

				return
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesASkipAlone() {
		val code =
			"""
			fun readsTheSample() {
				val file = sample ?: skipTest("cmo3.sample not present")

				if (!file.isFile) {
					skipTest("the sample is not a file")
				}

				check(file.length() > 0)
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}
}