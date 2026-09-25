package org.umamo.editor.desktop

import org.umamo.ui.app.JAR_HEAP_OPTION
import org.umamo.ui.app.LOW_HEAP_NOTICE_BELOW_BYTES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins when a jar relaunches itself with more memory, and the command it relaunches with: only a small, plain
 * jar launch does, and the child is the same launch in every other respect - so a rigger's own options, a
 * debugger, or a second pass are never overridden or doubled.
 */
class JarRelaunchTest {
	private val twoGibibytes = 2L * 1024 * 1024 * 1024

	/**
	 * The facts of a small jar launch, with any field overridden.
	 *
	 * @param List    inputArguments The JVM options.
	 * @param Long    maxHeapBytes   The heap limit.
	 * @param String? javaCommand    The running Java, or null.
	 * @param String? relaunchedFrom The relaunch property, or null.
	 * @return JarLaunchFacts The facts.
	 */
	private fun smallJarLaunch(
		inputArguments: List<String> = listOf("-Dfile.encoding=UTF-8"),
		maxHeapBytes: Long = twoGibibytes,
		javaCommand: String? = "/usr/lib/jvm/java-21/bin/java",
		relaunchedFrom: String? = null,
	): JarLaunchFacts = JarLaunchFacts("/home/rigger/umamo-linux-x64-0.4.0.jar", javaCommand, inputArguments, maxHeapBytes, relaunchedFrom)

	/**
	 * The reason a launch runs in process, failing when it would relaunch instead.
	 *
	 * @param JarRelaunchDecision decision The decision.
	 * @return RunInProcessReason The reason.
	 */
	private fun reasonOf(decision: JarRelaunchDecision): RunInProcessReason = assertIs<JarRelaunchDecision.RunInProcess>(decision).reason

	@Test
	fun aSmallJarLaunchRelaunchesAsTheSameLaunchWithTheOption() {
		val decision = decideJarRelaunch(smallJarLaunch(), listOf("/home/rigger/rig.uma"))

		assertEquals(
			listOf(
				"/usr/lib/jvm/java-21/bin/java",
				"-Dfile.encoding=UTF-8",
				JAR_HEAP_OPTION,
				"-D$RELAUNCHED_PROPERTY=$twoGibibytes",
				"-jar",
				"/home/rigger/umamo-linux-x64-0.4.0.jar",
				"/home/rigger/rig.uma",
			),
			assertIs<JarRelaunchDecision.Relaunch>(decision).command,
		)
	}

	@Test
	fun aLimitTheRiggerChoseIsKept() {
		for (option in listOf("-Xmx1g", "-XX:MaxHeapSize=1073741824", "-XX:MaxRAMPercentage=25", "-XX:MaxRAMFraction=4")) {
			assertEquals(RunInProcessReason.ExplicitHeapLimit, reasonOf(decideJarRelaunch(smallJarLaunch(inputArguments = listOf(option)), emptyList())), option)
		}
	}

	@Test
	fun aMachineSizeAloneIsNotAHeapLimit() {
		// -XX:MaxRAM says how big the machine is, not how much of it the heap may take, so it relaunches - and
		// the child keeps it, which is what lets an end-to-end check stand in for a small machine.
		val decision = decideJarRelaunch(smallJarLaunch(inputArguments = listOf("-XX:MaxRAM=4g")), emptyList())

		assertEquals("-XX:MaxRAM=4g", assertIs<JarRelaunchDecision.Relaunch>(decision).command[1])
	}

	@Test
	fun aDebuggedLaunchRunsInProcess() {
		for (agent in listOf("-agentlib:jdwp=transport=dt_socket,server=y,address=5005", "-Xrunjdwp:transport=dt_socket")) {
			assertEquals(RunInProcessReason.DebuggerAttached, reasonOf(decideJarRelaunch(smallJarLaunch(inputArguments = listOf(agent)), emptyList())), agent)
		}
	}

	@Test
	fun aRelaunchedChildNeverRelaunchesAgain() {
		assertEquals(RunInProcessReason.AlreadyRelaunched, reasonOf(decideJarRelaunch(smallJarLaunch(relaunchedFrom = "$twoGibibytes"), emptyList())))
	}

	@Test
	fun theThresholdIsTheLowMemoryAlertsThreshold() {
		assertEquals(RunInProcessReason.HeapLargeEnough, reasonOf(decideJarRelaunch(smallJarLaunch(maxHeapBytes = LOW_HEAP_NOTICE_BELOW_BYTES), emptyList())))
		assertIs<JarRelaunchDecision.Relaunch>(decideJarRelaunch(smallJarLaunch(maxHeapBytes = LOW_HEAP_NOTICE_BELOW_BYTES - 1), emptyList()))
	}

	@Test
	fun withoutTheRunningJavaThereIsNothingToStart() {
		assertEquals(RunInProcessReason.NoJavaCommand, reasonOf(decideJarRelaunch(smallJarLaunch(javaCommand = null), emptyList())))
	}

	@Test
	fun programArgumentsStayWholeElements() {
		// ProcessBuilder takes one element per argument, so nothing is split or quoted on the way through.
		val arguments = listOf("/home/rigger/My Rigs/リグ.uma", "--flag with spaces")

		val command = assertIs<JarRelaunchDecision.Relaunch>(decideJarRelaunch(smallJarLaunch(), arguments)).command

		assertEquals(arguments, command.takeLast(2))
	}

	@Test
	fun theJarPathIsMadeAbsolute() {
		assertEquals(File("umamo-linux-x64-0.4.0.jar").absolutePath, launchedJarPath("umamo-linux-x64-0.4.0.jar", ":"))
		assertEquals("/home/rigger/umamo-linux-x64-0.4.0.jar", launchedJarPath("/home/rigger/umamo-linux-x64-0.4.0.jar", ":"))
		assertNull(launchedJarPath("/opt/umamo/lib/app/ui-jvm.jar:/opt/umamo/lib/app/format-jvm.jar", ":"))
	}

	@Test
	fun theChildSaysHowItCameToBe() {
		assertEquals("relaunched from the jar with $JAR_HEAP_OPTION; the first launch's heap could grow to 2048 MiB", relaunchLogLine("$twoGibibytes"))
		assertNull(relaunchLogLine(null))
	}
}