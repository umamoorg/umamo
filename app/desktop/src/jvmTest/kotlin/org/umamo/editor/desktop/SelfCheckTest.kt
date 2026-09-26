package org.umamo.editor.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The self-check passes where the editor itself runs, and says so the way an installer test reads it: one line per
 * check, each OK, the same lines in the report file as on the output, and exit code 0.  The installer tests in CI run
 * the same checks against each packaged runtime; this keeps a broken check from reaching them first.
 */
class SelfCheckTest {
	@Test
	fun everyCheckPassesAndTheReportMatchesTheOutput() {
		val report = File.createTempFile("umamo-self-check", ".txt")
		try {
			val lines = ArrayList<String>()

			val exitCode = runSelfCheck(report.absolutePath, output = { line -> lines += line })

			assertEquals(0, exitCode, lines.joinToString("\n"))
			assertEquals(selfChecks().size + 1, lines.size, "one line per check, then the summary")
			for ((check, line) in selfChecks().zip(lines)) {
				assertTrue(line.startsWith("${check.name}: OK "), line)
			}
			assertEquals(lines, report.readLines(), "the report file carries the same lines")
		} finally {
			report.delete()
		}
	}

	@Test
	fun aFailingCheckFailsTheRunAndNamesItsReason() {
		val lines = ArrayList<String>()
		val checks = listOf(SelfCheck("sound") { "fine" }, SelfCheck("broken") { error("the reason") })

		val exitCode = runSelfCheck(null, checks) { line -> lines += line }

		assertEquals(1, exitCode)
		assertEquals(
			listOf("sound: OK fine", "broken: FAILED java.lang.IllegalStateException: the reason", "self-check: 1 check(s) failed"),
			lines,
		)
	}
}