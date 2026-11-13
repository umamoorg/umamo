package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the rule set's own sources to every rule the repository runs, its own included.
 *
 * The root build cannot do this.  Its ktlint tasks load this build's jar as their rule set, and an
 * included build is not one of the root's subprojects, so nothing there ever reads these files.  This
 * test reads them through the engine instead, which makes it the rule set's lint.
 */
class RuleSetSourcesLintTest {
	private val engine = KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders() + UmamoRuleSetProvider().getRuleProviders())

	/**
	 * Lints one file and lists what the engine reports in it.
	 *
	 * @param File sourceFile A Kotlin source or script of this build.
	 * @return List<String> One "path:line:column: message (rule)" per violation.
	 */
	private fun violationsIn(sourceFile: File): List<String> {
		val violations = ArrayList<String>()

		// The absolute path is what lets the engine find the repository's .editorconfig above the file.
		engine.lint(Code.fromFile(sourceFile.absoluteFile)) { error ->
			violations.add("${sourceFile.path}:${error.line}:${error.col}: ${error.detail} (${error.ruleId.value})")
		}

		return violations
	}

	@Test
	fun theRuleSetFollowsTheRulesItShips() {
		val sources = File("src").walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
		val scripts = File(".").listFiles { file -> file.isFile && file.name.endsWith(".gradle.kts") }.orEmpty().sortedBy { file -> file.name }

		// A wrong working directory would find nothing, and nothing lints clean.
		assertTrue(sources.size >= 10, "expected the rule set's sources under ${File("src").absolutePath}, found ${sources.size}")
		assertEquals(2, scripts.size, "expected this build's two Gradle scripts in ${File(".").absolutePath}")

		val violations = (sources + scripts).flatMap { sourceFile -> violationsIn(sourceFile) }

		assertEquals(emptyList(), violations, "the rule set breaks its own rules:\n" + violations.joinToString("\n"))
	}
}