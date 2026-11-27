package org.umamo.buildlogic

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VerificationSummaryTest {
	/**
	 * Runs a block against a fresh directory and removes the directory afterwards.
	 *
	 * @param Function block What to run, given the directory.
	 */
	private fun withResultDirectory(block: (File) -> Unit) {
		val resultDirectory = createTempDirectory("verification-summary").toFile()

		try {
			block(resultDirectory)
		} finally {
			resultDirectory.deleteRecursively()
		}
	}

	@Test
	fun aSkippedTaskIsNotRunWhenItWasAskedForByPath() {
		val skipped = FinishedTask(":ui:compileCommonMainKotlinMetadata", TaskOutcome.SKIPPED)
		val finishedTasks = listOf(skipped, FinishedTask(":format:compileCommonMainKotlinMetadata", TaskOutcome.EXECUTED))

		assertEquals(listOf(skipped), requestedButNotRun(finishedTasks, listOf(":ui:compileCommonMainKotlinMetadata")))
		assertEquals(listOf(skipped), requestedButNotRun(finishedTasks, listOf("ui:compileCommonMainKotlinMetadata")))
		assertEquals(emptyList(), requestedButNotRun(finishedTasks, listOf(":format:compileCommonMainKotlinMetadata")))
		assertEquals(emptyList(), requestedButNotRun(finishedTasks, listOf("build")))
	}

	@Test
	fun aBareNameIsNotRunOnlyWhenNoneOfItsTasksRan() {
		val withoutTests = FinishedTask(":cli:jvmTest", TaskOutcome.NO_SOURCE)
		val disabled = FinishedTask(":ui:jvmTest", TaskOutcome.SKIPPED)

		// One module with nothing to test is ordinary when the others ran.
		assertEquals(emptyList(), requestedButNotRun(listOf(withoutTests, FinishedTask(":ui:jvmTest", TaskOutcome.UP_TO_DATE)), listOf("jvmTest")))
		assertEquals(listOf(withoutTests, disabled), requestedButNotRun(listOf(disabled, withoutTests), listOf("jvmTest")))
		assertEquals(listOf(FinishedTask(":checkCommonSourcePurity", TaskOutcome.SKIPPED)), requestedButNotRun(listOf(FinishedTask(":checkCommonSourcePurity", TaskOutcome.SKIPPED)), listOf("checkCommonSourcePurity")))
	}

	@Test
	fun anOptionOrItsArgumentIsNotARequestedTask() {
		val finishedTasks = listOf(FinishedTask(":ui:jvmTest", TaskOutcome.SKIPPED), FinishedTask(":ui:compileTestKotlinJvm", TaskOutcome.SKIPPED))

		assertEquals(emptyList(), requestedButNotRun(finishedTasks, listOf("--tests", "*jvmTest", "TestKotlinJvm", "-x", "--continue")))
	}

	@Test
	fun aTallyCountsPassedFailedAndSkippedTests() {
		withResultDirectory { resultDirectory ->
			File(resultDirectory, "TEST-org.umamo.sample.FirstTest.xml").writeText(
				"""
				<?xml version="1.0" encoding="UTF-8"?>
				<testsuite name="org.umamo.sample.FirstTest" tests="3" skipped="1" failures="1" errors="0">
					<testcase name="passes[jvm]" classname="org.umamo.sample.FirstTest" time="0.01"/>
					<testcase name="fails[jvm]" classname="org.umamo.sample.FirstTest" time="0.01"><failure message="no">trace</failure></testcase>
					<testcase name="sitsOut[jvm]" classname="org.umamo.sample.FirstTest" time="0.0"><skipped/></testcase>
				</testsuite>
				""".trimIndent(),
			)
			File(resultDirectory, "TEST-org.umamo.sample.SecondTest.xml").writeText(
				"""
				<?xml version="1.0" encoding="UTF-8"?>
				<testsuite name="org.umamo.sample.SecondTest" tests="2" skipped="0" failures="0" errors="1">
					<testcase name="passes" classname="org.umamo.sample.SecondTest" time="0.01"/>
					<testcase name="throws" classname="org.umamo.sample.SecondTest" time="0.01"><error message="no">trace</error></testcase>
				</testsuite>
				""".trimIndent(),
			)
			// Anything that is not a result file is left alone.
			File(resultDirectory, "notes.txt").writeText("not a result")

			assertEquals(TestTally(2, listOf("FirstTest.fails[jvm]", "SecondTest.throws"), listOf("FirstTest.sitsOut[jvm]")), readTestTally(resultDirectory))
		}
	}

	@Test
	fun aDirectoryWithoutResultsHasNoTally() {
		withResultDirectory { resultDirectory ->
			assertNull(readTestTally(resultDirectory))
			assertNull(readTestTally(File(resultDirectory, "missing")))
		}
	}

	@Test
	fun aBuildThatRanNoTaskHasNoSummary() {
		assertEquals(emptyList(), renderVerificationSummary(emptyList(), listOf("help"), emptyMap()))
	}

	@Test
	fun aCleanBuildEndsOnAnOkVerdict() {
		val finishedTasks = listOf(FinishedTask(":ui:compileKotlinJvm", TaskOutcome.UP_TO_DATE), FinishedTask(":ui:jvmTest", TaskOutcome.EXECUTED))
		val lines = renderVerificationSummary(finishedTasks, listOf(":ui:jvmTest"), mapOf(":ui:jvmTest" to TestTally(40, emptyList(), emptyList())))

		assertEquals(listOf("Verification summary", "  :ui:jvmTest: 40 passed, 0 failed, 0 skipped", "VERDICT: OK - 2 tasks (1 ran), 40 tests passed, 0 failed, 0 skipped"), lines)
		assertEquals(listOf("VERDICT: OK - 1 tasks (1 ran), no tests"), renderVerificationSummary(listOf(FinishedTask(":help", TaskOutcome.EXECUTED)), listOf("help"), emptyMap()))
	}

	@Test
	fun aFailedTaskAndItsFailedTestsAreNamed() {
		val finishedTasks = listOf(FinishedTask(":ui:jvmTest", TaskOutcome.FAILED), FinishedTask(":edit:jvmTest", TaskOutcome.EXECUTED))
		val testTallies = mapOf(":ui:jvmTest" to TestTally(3, listOf("AreaTreeTest.splits"), listOf("GlTest.renders")), ":edit:jvmTest" to TestTally(5, emptyList(), emptyList()))
		val lines = renderVerificationSummary(finishedTasks, listOf("jvmTest"), testTallies)

		assertEquals(
			listOf(
				"Verification summary",
				"  failed tasks: :ui:jvmTest",
				"  :edit:jvmTest: 5 passed, 0 failed, 0 skipped",
				"  :ui:jvmTest: 3 passed, 1 failed, 1 skipped",
				"  failed tests: AreaTreeTest.splits",
				"  skipped tests: GlTest.renders",
				"VERDICT: FAILED - 1 tasks failed, 8 tests passed, 1 failed, 1 skipped",
			),
			lines,
		)
	}

	@Test
	fun aRequestedTaskThatDidNotRunMakesTheBuildIncomplete() {
		val finishedTasks =
			listOf(
				FinishedTask(":ui:compileCommonMainKotlinMetadata", TaskOutcome.SKIPPED),
				FinishedTask(":cli:jvmTest", TaskOutcome.NO_SOURCE),
				// Skipped, but nobody asked for it by name.
				FinishedTask(":ui:compileJvmMainJava", TaskOutcome.NO_SOURCE),
			)
		val lines = renderVerificationSummary(finishedTasks, listOf(":ui:compileCommonMainKotlinMetadata", ":cli:jvmTest"), emptyMap())

		assertEquals(
			listOf(
				"Verification summary",
				"  asked for but not run: :cli:jvmTest (NO-SOURCE), :ui:compileCommonMainKotlinMetadata (SKIPPED)",
				"VERDICT: INCOMPLETE - 2 requested tasks did not run, no tests",
			),
			lines,
		)
	}

	@Test
	fun aTestTaskThatDidNotRunAgainSaysItsResultsAreOld() {
		val lines = renderVerificationSummary(listOf(FinishedTask(":ui:jvmTest", TaskOutcome.UP_TO_DATE)), listOf("jvmTest"), mapOf(":ui:jvmTest" to TestTally(40, emptyList(), emptyList())))

		assertEquals("  :ui:jvmTest: 40 passed, 0 failed, 0 skipped (up to date: the results of an earlier run)", lines[1])
		assertEquals("VERDICT: OK - 1 tasks (0 ran), 40 tests passed, 0 failed, 0 skipped", lines.last())
	}

	@Test
	fun aLongListOfSkippedTestsIsCutOffAndCounted() {
		val skippedTests = (1..15).map { testNumber -> "GatedTest.sample$testNumber" }
		val lines = renderVerificationSummary(listOf(FinishedTask(":format:jvmTest", TaskOutcome.EXECUTED)), emptyList(), mapOf(":format:jvmTest" to TestTally(0, emptyList(), skippedTests)))

		assertEquals("  skipped tests: " + skippedTests.take(12).joinToString(", ") + ", and 3 more", lines[2])
		assertEquals("VERDICT: OK - 1 tasks (1 ran), 0 tests passed, 0 failed, 15 skipped", lines.last())
	}
}