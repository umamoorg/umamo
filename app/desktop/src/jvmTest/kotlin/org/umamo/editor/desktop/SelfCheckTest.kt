package org.umamo.editor.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

	@Test
	fun aFailureNamesTheCauseUnderIt() {
		val lines = ArrayList<String>()
		val missingLibrary = UnsatisfiedLinkError("libEGL.so.1: cannot open shared object file")
		val checks = listOf(SelfCheck("skia") { throw ExceptionInInitializerError(missingLibrary) })

		runSelfCheck(null, checks) { line -> lines += line }

		assertEquals(
			"skia: FAILED java.lang.ExceptionInInitializerError: null <- java.lang.UnsatisfiedLinkError: libEGL.so.1: cannot open shared object file",
			lines.first(),
		)
	}

	@Test
	fun theImagesRuntimeIsFoundThroughASymbolicLink() {
		val root = Files.createTempDirectory("umamo-self-check-image")
		try {
			val realImage = Files.createDirectories(root.resolve("private/umamo"))
			Files.createDirectories(realImage.resolve("bin"))
			val launcher = Files.createFile(realImage.resolve("bin/umamo"))
			val runtime = Files.createDirectories(realImage.resolve("lib/runtime"))
			val otherRuntime = Files.createDirectories(root.resolve("elsewhere/jdk"))
			val linkedImage = Files.createSymbolicLink(root.resolve("umamo"), realImage)

			assertTrue(runtimeBelongsToImage(linkedImage.resolve("bin/umamo"), runtime), "the launcher reached through the link")
			assertTrue(runtimeBelongsToImage(launcher, runtime))
			assertFalse(runtimeBelongsToImage(linkedImage.resolve("bin/umamo"), otherRuntime), "a runtime outside the image")
		} finally {
			root.toFile().deleteRecursively()
		}
	}
}