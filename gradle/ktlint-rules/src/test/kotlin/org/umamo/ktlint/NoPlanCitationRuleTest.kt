package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoPlanCitationRule through the real ktlint engine, alone, so each case shows where it reads a
 * comment as citing a plan document.
 */
class NoPlanCitationRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoPlanCitationRule() }))

	/**
	 * Lints a snippet with the rule alone and reduces each violation to where it is.
	 *
	 * @param String code Kotlin source.
	 * @param Boolean script Whether the source is a Kotlin script, as a Gradle build file is.
	 * @return List<String> One "line:column" per violation.
	 */
	private fun reported(code: String, script: Boolean = false): List<String> {
		val positions = ArrayList<String>()
		engine.lint(Code.fromSnippet(code, script)) { error -> positions.add("${error.line}:${error.col}") }

		return positions
	}

	@Test
	fun reportsACitationInEveryKindOfComment() {
		val code =
			"""
			package org.umamo.interop

			/**
			 * Packs the pages (docs/plan/texture-authoring.md, Phase 3).
			 */
			internal fun pack() {
				// Half the machine's memory (docs/plan/distribution.md D6).
				val heap = 50

				/* See docs/plan/auto-mesh.md for the sweep. */
				val sweep = heap
			}
			""".trimIndent()

		assertEquals(listOf("4:21", "7:32", "10:9"), reported(code))
	}

	@Test
	fun reportsEachCitationAndBothSpellingsOfTheDirectory() {
		val code =
			"""
			package org.umamo.interop

			// Decided in docs/plan/uma-format.md and docs/plans/art-sourcing.md; `docs/plan` holds both.
			internal val decided = true
			""".trimIndent()

		assertEquals(listOf("3:15", "3:43", "3:72"), reported(code))
	}

	@Test
	fun leavesTheFormatDocumentsAndOtherPathsAlone() {
		val code =
			"""
			package org.umamo.interop

			/**
			 * Follows docs/format/CMO3.md §6.  The planning notes in docs/planning are something else, and so
			 * is a plan that names no path.
			 */
			internal val followed = true
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesCodeAlone() {
		val code =
			"""
			package org.umamo.buildlogic

			internal const val PLAN_DIRECTORY = "docs/plan/"

			internal fun planFor(name: String): String = "docs/plan/" + name
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun worksInABuildScript() {
		val code =
			"""
			// The name the app image takes (docs/plan/distribution.md D15).
			val packageBaseName = "umamo"
			""".trimIndent()

		assertEquals(listOf("1:34"), reported(code, script = true))
	}
}