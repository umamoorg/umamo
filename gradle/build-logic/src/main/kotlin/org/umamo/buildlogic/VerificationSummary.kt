package org.umamo.buildlogic

import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

/** How a task ended, as the summary tells them apart. */
enum class TaskOutcome {
	EXECUTED,
	UP_TO_DATE,
	FROM_CACHE,
	SKIPPED,
	NO_SOURCE,
	FAILED,
}

/**
 * One task that finished in this build.
 *
 * @property String path The task's path, such as `:render:jvmTest`.
 * @property TaskOutcome outcome How it ended.
 */
data class FinishedTask(val path: String, val outcome: TaskOutcome)

/**
 * The results a test task left behind.
 *
 * @property Int passed The tests that ran and passed.
 * @property List<String> failedTests The tests that failed or errored, each as `Class.test`.
 * @property List<String> skippedTests The skipped tests, named the same way.
 */
data class TestTally(val passed: Int, val failedTests: List<String>, val skippedTests: List<String>)

/**
 * Finds the tasks that were asked for by name on the command line and did not run.
 *
 * A task that Gradle skips is only worth reporting when someone asked for that very task: the hundreds
 * a lifecycle task pulls in and finds nothing to do for are ordinary.  A path names one task.  A bare
 * name asks for that task in every module that has it, where a module with nothing for it to do is
 * ordinary too, so a bare name counts only when not one of its tasks ran.  An abbreviated name is not
 * recognized, and neither is an option or its argument, which the command line carries in the same list.
 *
 * @param List<FinishedTask> finishedTasks Every task that finished.
 * @param List<String> requestedNames The task names and options given to Gradle.
 * @return List<FinishedTask> The requested tasks that ended skipped or without sources, sorted by path.
 */
fun requestedButNotRun(finishedTasks: List<FinishedTask>, requestedNames: List<String>): List<FinishedTask> {
	val notRun = LinkedHashSet<FinishedTask>()

	for (requestedName in requestedNames) {
		if (requestedName.startsWith("-")) {
			continue
		}

		val matching =
			if (requestedName.contains(':')) {
				finishedTasks.filter { finishedTask -> finishedTask.path == ":" + requestedName.trimStart(':') }
			} else {
				finishedTasks.filter { finishedTask -> finishedTask.path.endsWith(":$requestedName") }
			}

		if (matching.isNotEmpty() && matching.all { finishedTask -> finishedTask.outcome == TaskOutcome.SKIPPED || finishedTask.outcome == TaskOutcome.NO_SOURCE }) {
			notRun.addAll(matching)
		}
	}

	return notRun.sortedBy { finishedTask -> finishedTask.path }
}

/**
 * Reads the JUnit result files a test task wrote.
 *
 * @param File resultDirectory The task's `build/test-results/<task>` directory.
 * @return TestTally? The totals, or null when the directory holds no result file, which is how a task that
 *                    is not a test task is told apart.
 */
fun readTestTally(resultDirectory: File): TestTally? {
	val resultFiles = resultDirectory.listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.name.endsWith(".xml") }.orEmpty().sortedBy { file -> file.name }

	if (resultFiles.isEmpty()) {
		return null
	}

	val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
	var passedCount = 0
	val failedTests = ArrayList<String>()
	val skippedTests = ArrayList<String>()

	for (resultFile in resultFiles) {
		val testCases = documentBuilder.parse(resultFile).documentElement.getElementsByTagName("testcase")

		for (caseIndex in 0 until testCases.length) {
			val testCase = testCases.item(caseIndex) as Element
			val testName = testCase.getAttribute("classname").substringAfterLast('.') + "." + testCase.getAttribute("name")

			when {
				testCase.getElementsByTagName("failure").length > 0 || testCase.getElementsByTagName("error").length > 0 -> failedTests.add(testName)
				testCase.getElementsByTagName("skipped").length > 0 -> skippedTests.add(testName)
				else -> passedCount++
			}
		}
	}

	return TestTally(passedCount, failedTests, skippedTests)
}

/**
 * Names the first few of a list and counts the rest, so one runaway list cannot bury the summary.
 *
 * @param List<String> names The names.
 * @return String The names joined by commas, cut off after [MAX_NAMED_TESTS] with the remainder counted.
 */
private fun namedUpToLimit(names: List<String>): String = names.take(MAX_NAMED_TESTS).joinToString(", ") + if (names.size > MAX_NAMED_TESTS) ", and ${names.size - MAX_NAMED_TESTS} more" else ""

/**
 * Writes the summary of a build: what failed, what was asked for and did not run, what each test task
 * found, and one verdict line.
 *
 * The verdict is one line that opens with [VERDICT_PREFIX], so a `grep` for it keeps the whole outcome
 * of a run whose output was piped away.  Gradle prints its own closing lines after the summary, and a
 * build that never reached a task has no verdict at all.
 *
 * @param List<FinishedTask> finishedTasks Every task that finished, in any order.
 * @param List<String> requestedNames The task names and options given to Gradle.
 * @param Map<String, TestTally> testTallies The results of each test task that finished, by task path.
 * @return List<String> The lines to print, empty when no task finished.
 */
fun renderVerificationSummary(finishedTasks: List<FinishedTask>, requestedNames: List<String>, testTallies: Map<String, TestTally>): List<String> {
	if (finishedTasks.isEmpty()) {
		return emptyList()
	}

	val outcomeByPath = finishedTasks.associate { finishedTask -> finishedTask.path to finishedTask.outcome }
	val failedTasks = finishedTasks.filter { finishedTask -> finishedTask.outcome == TaskOutcome.FAILED }.map { finishedTask -> finishedTask.path }.sorted()
	val notRun = requestedButNotRun(finishedTasks, requestedNames)
	val lines = ArrayList<String>()

	if (failedTasks.isNotEmpty()) {
		lines.add("  failed tasks: " + failedTasks.joinToString(", "))
	}

	if (notRun.isNotEmpty()) {
		lines.add("  asked for but not run: " + notRun.joinToString(", ") { finishedTask -> "${finishedTask.path} (${if (finishedTask.outcome == TaskOutcome.NO_SOURCE) "NO-SOURCE" else "SKIPPED"})" })
	}

	for ((taskPath, tally) in testTallies.toSortedMap()) {
		// A test task that did not run again still stands on the results of the run that last did.
		val reused = if (outcomeByPath[taskPath] == TaskOutcome.UP_TO_DATE || outcomeByPath[taskPath] == TaskOutcome.FROM_CACHE) " (up to date: the results of an earlier run)" else ""
		lines.add("  $taskPath: ${tally.passed} passed, ${tally.failedTests.size} failed, ${tally.skippedTests.size} skipped$reused")
	}

	val failedTestNames = testTallies.toSortedMap().values.flatMap { tally -> tally.failedTests }
	val skippedTests = testTallies.toSortedMap().values.flatMap { tally -> tally.skippedTests }

	if (failedTestNames.isNotEmpty()) {
		lines.add("  failed tests: " + namedUpToLimit(failedTestNames))
	}

	if (skippedTests.isNotEmpty()) {
		lines.add("  skipped tests: " + namedUpToLimit(skippedTests))
	}

	val failedTestCount = failedTestNames.size
	val ranCount = finishedTasks.count { finishedTask -> finishedTask.outcome == TaskOutcome.EXECUTED || finishedTask.outcome == TaskOutcome.FAILED }
	val testNote =
		if (testTallies.isEmpty()) {
			"no tests"
		} else {
			"${testTallies.values.sumOf { tally -> tally.passed }} tests passed, $failedTestCount failed, ${skippedTests.size} skipped"
		}
	val verdict =
		when {
			failedTasks.isNotEmpty() || failedTestCount > 0 -> "FAILED - ${failedTasks.size} tasks failed, $testNote"
			notRun.isNotEmpty() -> "INCOMPLETE - ${notRun.size} requested tasks did not run, $testNote"
			else -> "OK - ${finishedTasks.size} tasks ($ranCount ran), $testNote"
		}

	if (lines.isNotEmpty()) {
		lines.add(0, "Verification summary")
	}

	lines.add("$VERDICT_PREFIX $verdict")

	return lines
}

/** What the verdict line opens with, the one token to search a build's output for. */
const val VERDICT_PREFIX: String = "VERDICT:"

/** How many failed or skipped tests the summary names before it only counts the rest. */
private const val MAX_NAMED_TESTS = 12

/** Hands a plugin the listener registry, which Gradle gives out only by injection. */
interface BuildEventServices {
	/** Where a build service is registered to be told how each task ended. */
	@get:Inject
	val listenerRegistry: BuildEventsListenerRegistry
}

/**
 * Collects how every task of a build ended and prints the summary when the build closes.
 *
 * Gradle hands a build service each task's finish event and closes the service at the end of the build,
 * which is the one place a whole-build summary can be written that still works under the configuration
 * cache.
 */
abstract class VerificationSummaryService :
	BuildService<VerificationSummaryService.Parameters>,
	OperationCompletionListener,
	AutoCloseable {
	/** What the service is told when the build is configured. */
	interface Parameters : BuildServiceParameters {
		/** Each project's directory by project path, the included builds among them. */
		val projectDirectories: MapProperty<String, String>

		/** The task names and options given to Gradle. */
		val requestedTasks: ListProperty<String>

		/** Whether this is a dry run, which executes nothing and so has nothing to sum up. */
		val dryRun: Property<Boolean>
	}

	private val finishedTasks = ConcurrentLinkedQueue<FinishedTask>()

	/**
	 * Records how a task ended.
	 *
	 * @param FinishEvent event The finish event; anything but a task's is ignored.
	 */
	override fun onFinish(event: FinishEvent) {
		if (event !is TaskFinishEvent) {
			return
		}

		val result = event.result
		val outcome =
			when {
				result is TaskFailureResult -> TaskOutcome.FAILED
				result is TaskSkippedResult && result.skipMessage == "NO-SOURCE" -> TaskOutcome.NO_SOURCE
				result is TaskSkippedResult -> TaskOutcome.SKIPPED
				result is TaskSuccessResult && result.isFromCache -> TaskOutcome.FROM_CACHE
				result is TaskSuccessResult && result.isUpToDate -> TaskOutcome.UP_TO_DATE
				else -> TaskOutcome.EXECUTED
			}

		finishedTasks.add(FinishedTask(event.descriptor.taskPath, outcome))
	}

	/** Prints the summary of the build that just ended. */
	override fun close() {
		if (parameters.dryRun.get()) {
			return
		}

		val finished = finishedTasks.toList()
		val projectDirectories = parameters.projectDirectories.get()
		val testTallies = LinkedHashMap<String, TestTally>()

		for (finishedTask in finished) {
			if (finishedTask.outcome == TaskOutcome.SKIPPED || finishedTask.outcome == TaskOutcome.NO_SOURCE) {
				continue
			}

			val projectDirectory = projectDirectories[finishedTask.path.substringBeforeLast(':').ifEmpty { ":" }] ?: continue
			val tally = readTestTally(File(projectDirectory, "build/test-results/" + finishedTask.path.substringAfterLast(':'))) ?: continue
			testTallies[finishedTask.path] = tally
		}

		val logger = Logging.getLogger(VerificationSummaryService::class.java)

		// Lifecycle level, so a quiet run (`-q`, which the diagnostic CLI is run under) stays quiet.
		for (line in renderVerificationSummary(finished, parameters.requestedTasks.get(), testTallies)) {
			logger.lifecycle(line)
		}
	}
}