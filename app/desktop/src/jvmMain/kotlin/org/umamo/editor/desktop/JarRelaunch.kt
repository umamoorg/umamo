package org.umamo.editor.desktop

import org.umamo.ui.app.JAR_HEAP_OPTION
import org.umamo.ui.app.LOW_HEAP_NOTICE_BELOW_BYTES
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

/*
 * A jar started with too little memory starts itself again with enough (docs/plan/distribution.md D13).
 *
 * `java -jar` gets a quarter of the machine's memory, which a large export can run out of, and a jar cannot
 * carry a heap option the way the installed launcher does.  Intel Macs run the jar for good (D12), and a
 * double-clicked jar cannot be given a flag at all, so rather than only telling the rigger what to type, the
 * jar re-executes the same Java with JAR_HEAP_OPTION and waits for that child.  The first thing main does,
 * before anything touches AWT (a second Dock icon on macOS) or opens the session log (the short-lived parent
 * would spend one of the ten).  Whatever gets in the way - a limit the rigger chose, a debugger, no path to the
 * running Java - leaves the launch as it is, where the low-memory alert still covers it.
 */

/** The system property a relaunched child carries: the first launch's heap limit in bytes, for its log. */
internal const val RELAUNCHED_PROPERTY = "umamo.relaunched"

/** Environment variables whose options the JVM already folded into the input arguments the child is given. */
private val OPTION_ENVIRONMENT_VARIABLES = setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")

/**
 * What a jar launch knows about itself, gathered once so the decision is a pure function of it.
 *
 * @property String       jarPath        The absolute path of the running jar.
 * @property String?      javaCommand    The Java executable running it, or null when the OS will not say.
 * @property List<String> inputArguments The JVM options it was started with, including those from the
 *   JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, and _JAVA_OPTIONS environment variables.
 * @property Long         maxHeapBytes   The most its heap may grow to.
 * @property String?      relaunchedFrom The [RELAUNCHED_PROPERTY] value when this is already a relaunched child.
 */
internal class JarLaunchFacts(
	val jarPath: String,
	val javaCommand: String?,
	val inputArguments: List<String>,
	val maxHeapBytes: Long,
	val relaunchedFrom: String?,
)

/** Why a jar launch runs as it is rather than relaunching. */
internal enum class RunInProcessReason {
	/** This is the relaunched child. */
	AlreadyRelaunched,

	/** The heap is already at least [LOW_HEAP_NOTICE_BELOW_BYTES]. */
	HeapLargeEnough,

	/** The rigger gave a heap limit of their own, which is theirs to keep. */
	ExplicitHeapLimit,

	/** A debugger agent is attached; a second JVM would fight it for its port. */
	DebuggerAttached,

	/** The OS did not say which Java is running, so there is nothing to start again. */
	NoJavaCommand,
}

/** Whether a jar launch starts itself again, and with what command. */
internal sealed interface JarRelaunchDecision {
	/**
	 * Start [command] and wait for it.
	 *
	 * @property List<String> command The child's full command line.
	 */
	class Relaunch(val command: List<String>) : JarRelaunchDecision

	/**
	 * Run in this process.
	 *
	 * @property RunInProcessReason reason Why.
	 */
	class RunInProcess(val reason: RunInProcessReason) : JarRelaunchDecision
}

/**
 * Whether the JVM options already set a heap limit: `-Xmx`, `-XX:MaxHeapSize`, `-XX:MaxRAMPercentage`, or
 * `-XX:MaxRAMFraction`.  `-XX:MaxRAM` does not count - it describes the machine, not the heap's share of it.
 *
 * @param List inputArguments The JVM options.
 * @return Boolean Whether one of them limits the heap.
 */
internal fun hasExplicitHeapLimit(inputArguments: List<String>): Boolean =
	inputArguments.any { argument ->
		argument.startsWith("-Xmx") ||
			argument.startsWith("-XX:MaxHeapSize=") ||
			argument.startsWith("-XX:MaxRAMPercentage=") ||
			argument.startsWith("-XX:MaxRAMFraction=")
	}

/**
 * Whether a JDWP debugger agent is attached, which a relaunched child would start a second time.
 *
 * @param List inputArguments The JVM options.
 * @return Boolean Whether one of them attaches a debugger.
 */
internal fun hasDebuggerAgent(inputArguments: List<String>): Boolean =
	inputArguments.any { argument -> argument.startsWith("-agentlib:jdwp") || argument.startsWith("-Xrunjdwp") }

/**
 * Decides whether a jar launch relaunches itself, and builds the child's command when it does: the same Java,
 * the same JVM options, then [JAR_HEAP_OPTION], the first launch's limit as [RELAUNCHED_PROPERTY], and the same
 * jar and program arguments.
 *
 * @param JarLaunchFacts facts            What the launch knows about itself.
 * @param List           programArguments The arguments `main` received.
 * @return JarRelaunchDecision The decision.
 */
internal fun decideJarRelaunch(facts: JarLaunchFacts, programArguments: List<String>): JarRelaunchDecision {
	val javaCommand = facts.javaCommand
	return when {
		facts.relaunchedFrom != null -> JarRelaunchDecision.RunInProcess(RunInProcessReason.AlreadyRelaunched)
		facts.maxHeapBytes >= LOW_HEAP_NOTICE_BELOW_BYTES -> JarRelaunchDecision.RunInProcess(RunInProcessReason.HeapLargeEnough)
		hasExplicitHeapLimit(facts.inputArguments) -> JarRelaunchDecision.RunInProcess(RunInProcessReason.ExplicitHeapLimit)
		hasDebuggerAgent(facts.inputArguments) -> JarRelaunchDecision.RunInProcess(RunInProcessReason.DebuggerAttached)
		javaCommand == null -> JarRelaunchDecision.RunInProcess(RunInProcessReason.NoJavaCommand)
		else ->
			JarRelaunchDecision.Relaunch(
				listOf(javaCommand) +
					facts.inputArguments +
					listOf(JAR_HEAP_OPTION, "-D$RELAUNCHED_PROPERTY=${facts.maxHeapBytes}", "-jar", facts.jarPath) +
					programArguments,
			)
	}
}

/**
 * Gathers the facts of a jar launch, or null when this is not one.  The installed launcher is ruled out first:
 * its bundled runtime has no java.management, so the management bean must never be asked there.
 *
 * @return JarLaunchFacts? The facts, or null for the installed launcher or a development class path.
 */
internal fun gatherJarLaunchFacts(): JarLaunchFacts? {
	if (System.getProperty(PACKAGED_VERSION_PROPERTY) != null) {
		return null
	}
	val jarPath = launchedJarPath(System.getProperty("java.class.path").orEmpty()) ?: return null
	return JarLaunchFacts(
		jarPath = jarPath,
		javaCommand = ProcessHandle.current().info().command().orElse(null),
		inputArguments = ManagementFactory.getRuntimeMXBean().inputArguments,
		maxHeapBytes = Runtime.getRuntime().maxMemory(),
		relaunchedFrom = System.getProperty(RELAUNCHED_PROPERTY),
	)
}

/**
 * Relaunches a jar started with too little heap, and does not return when it does: this process waits for
 * the child and exits with its code, so a terminal launch behaves as before.  The child's environment drops
 * the option variables, because their options already reach it through the input arguments and would
 * otherwise apply twice.  A shutdown hook takes the child down with this process.
 *
 * @param Array<String> programArguments The arguments `main` received.
 * @return String? A note for the log when a relaunch was due but could not start, else null.
 */
internal fun relaunchForHeapIfDue(programArguments: Array<String>): String? {
	val facts = gatherJarLaunchFacts() ?: return null
	val decision = decideJarRelaunch(facts, programArguments.toList())
	if (decision !is JarRelaunchDecision.Relaunch) {
		return null
	}
	val builder = ProcessBuilder(decision.command).inheritIO()
	builder.environment().keys.removeAll(OPTION_ENVIRONMENT_VARIABLES)
	val child =
		try {
			builder.start()
		} catch (failure: IOException) {
			return "could not relaunch with $JAR_HEAP_OPTION, so this launch keeps its ${facts.maxHeapBytes / BYTES_PER_MEBIBYTE} MiB limit: ${failure.message}"
		}
	Runtime.getRuntime().addShutdownHook(
		Thread {
			if (child.isAlive) {
				child.destroy()
			}
		},
	)
	exitProcess(child.waitFor())
}

/**
 * The log line a relaunched child writes about how it came to be.
 *
 * @param String? relaunchedFrom The [RELAUNCHED_PROPERTY] value, or null when this is not a relaunched child.
 * @return String? The line, or null.
 */
internal fun relaunchLogLine(relaunchedFrom: String?): String? {
	val firstLimitBytes = relaunchedFrom?.toLongOrNull() ?: return null
	return "relaunched from the jar with $JAR_HEAP_OPTION; the first launch's heap could grow to ${firstLimitBytes / BYTES_PER_MEBIBYTE} MiB"
}

/**
 * The jar the editor was started from, read off the class path: `java -jar` leaves exactly that one jar on it,
 * where the installed launcher and a development run list many entries.
 *
 * @param String classPath     The `java.class.path` value.
 * @param String pathSeparator The class path's entry separator.
 * @return String? The jar's absolute path, or null when the class path is not a single jar.
 */
internal fun launchedJarPath(classPath: String, pathSeparator: String = File.pathSeparator): String? {
	val entries = classPath.split(pathSeparator).filter { entry -> entry.isNotBlank() }
	val onlyEntry = entries.singleOrNull() ?: return null
	if (!onlyEntry.endsWith(".jar", ignoreCase = true)) {
		return null
	}
	return File(onlyEntry).absolutePath
}

private const val BYTES_PER_MEBIBYTE = 1024L * 1024