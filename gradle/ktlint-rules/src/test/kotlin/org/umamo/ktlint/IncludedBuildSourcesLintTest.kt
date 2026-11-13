package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the sources of both included builds to every rule the repository runs, this rule set's included.
 *
 * The root build cannot do this.  Its ktlint tasks read the root build's own projects, and neither
 * included build is one of them: this build's jar is what those tasks load as their rule set, and the
 * build logic is compiled before the root build is configured at all.  This test reads the files through
 * the engine instead, which makes it the lint of both.
 */
class IncludedBuildSourcesLintTest {
	private val engine = KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders() + UmamoRuleSetProvider().getRuleProviders())

	/**
	 * Lints one file and lists what the engine reports in it.
	 *
	 * @param File sourceFile A Kotlin source or script.
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

	/**
	 * Lints an included build: everything Kotlin under its `src`, and the Gradle scripts at its root.
	 *
	 * @param File buildDirectory The included build's root directory.
	 * @param Int minimumSourceCount How many sources `src` holds at the least.  A wrong working directory
	 *                               would find nothing, and nothing lints clean.
	 * @return List<String> The violations, one per line of a ktlint report.
	 */
	private fun violationsUnder(buildDirectory: File, minimumSourceCount: Int): List<String> {
		val sources = File(buildDirectory, "src").walkTopDown().filter { file -> file.isFile && (file.extension == "kt" || file.extension == "kts") }.sortedBy { file -> file.path }.toList()
		val scripts = buildDirectory.listFiles { file -> file.isFile && file.name.endsWith(".gradle.kts") }.orEmpty().sortedBy { file -> file.name }

		assertTrue(sources.size >= minimumSourceCount, "expected at least $minimumSourceCount sources under ${File(buildDirectory, "src").absolutePath}, found ${sources.size}")
		assertEquals(2, scripts.size, "expected the build's two Gradle scripts in ${buildDirectory.absolutePath}")

		return (sources + scripts).flatMap { sourceFile -> violationsIn(sourceFile) }
	}

	@Test
	fun theRuleSetFollowsTheRulesItShips() {
		val violations = violationsUnder(File("."), minimumSourceCount = 10)

		assertEquals(emptyList(), violations, "the rule set breaks its own rules:\n" + violations.joinToString("\n"))
	}

	@Test
	fun theBuildLogicFollowsThemToo() {
		val violations = violationsUnder(File("../build-logic"), minimumSourceCount = 6)

		assertEquals(emptyList(), violations, "the build logic breaks the rules:\n" + violations.joinToString("\n"))
	}
}