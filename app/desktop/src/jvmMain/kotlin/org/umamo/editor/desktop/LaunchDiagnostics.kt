package org.umamo.editor.desktop

import okio.FileSystem
import okio.IOException
import org.umamo.storage.AppStorage
import org.umamo.storage.SessionLogFile
import org.umamo.storage.UmamoLog
import org.umamo.ui.app.HostHeap
import org.umamo.ui.help.ProjectInfo
import java.io.File
import kotlin.time.Clock

/*
 * What the desktop host sets up and records before anything else runs: the session log every later line
 * lands in, the handler that gets an uncaught failure into it, and the memory limit this JVM started with.
 * A bug report needs all three, and only the first lines of main run early enough to catch everything.
 */

/** The logs directory's name under the app's data directory. */
private const val LOG_DIRECTORY_NAME = "logs"

/** The system property the installed launcher sets (its umamo.cfg); a jar or development launch has none. */
private const val PACKAGED_VERSION_PROPERTY = "jpackage.app-version"

private const val BYTES_PER_MEBIBYTE = 1024L * 1024

/**
 * Starts this session's log file under the data directory and routes every logged line into it for the rest
 * of the process.
 *
 * @param AppStorage storage The app's directories.
 * @return SessionLogFile? The open session log, or null when it could not be created; the editor runs on
 *   without one.
 */
internal fun attachSessionLog(storage: AppStorage): SessionLogFile? =
	try {
		SessionLogFile.open(FileSystem.SYSTEM, storage.dataDirectory / LOG_DIRECTORY_NAME, Clock.System.now()).also { sessionLog -> UmamoLog.addSink(sessionLog) }
	} catch (failure: IOException) {
		UmamoLog.warn("no session log this time: ${failure.message}")
		null
	}

/**
 * Routes every uncaught failure into the log, with its stack, before the handler that was there before sees
 * it.  That covers the window's own exception handling too: Compose shows its error dialog, asks the window to
 * close, and rethrows, which lands here - so the session log holds the reason for that error.
 *
 * @return Thread.UncaughtExceptionHandler? The handler that was installed before, or null when there was none.
 */
internal fun installUncaughtExceptionLogging(): Thread.UncaughtExceptionHandler? {
	val previous = Thread.getDefaultUncaughtExceptionHandler()
	Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
		try {
			UmamoLog.error("uncaught exception on thread ${thread.name}", throwable)
		} catch (_: Throwable) {
			// Logging must never stand between a failure and the handler that reports it.
		}
		if (previous != null) {
			previous.uncaughtException(thread, throwable)
		} else {
			// With no default handler the JVM prints the trace itself; installing one takes that over.
			System.err.print("Exception in thread \"${thread.name}\" ")
			throwable.printStackTrace()
		}
	}
	return previous
}

/**
 * The memory limit this JVM started with, and how it was started.
 *
 * @return HostHeap The launch's heap.
 */
internal fun detectHostHeap(): HostHeap =
	HostHeap(
		maxBytes = Runtime.getRuntime().maxMemory(),
		packagedLaunch = System.getProperty(PACKAGED_VERSION_PROPERTY) != null,
		jarFileName = launchedJarFileName(System.getProperty("java.class.path").orEmpty()),
	)

/**
 * The jar the editor was started from, read off the class path: `java -jar` leaves exactly that one jar on it,
 * where the installed launcher and a development run list many entries.
 *
 * @param String classPath     The `java.class.path` value.
 * @param String pathSeparator The class path's entry separator.
 * @return String? The jar's file name, or null when the class path is not a single jar.
 */
internal fun launchedJarFileName(classPath: String, pathSeparator: String = File.pathSeparator): String? {
	val entries = classPath.split(pathSeparator).filter { entry -> entry.isNotBlank() }
	val onlyEntry = entries.singleOrNull() ?: return null
	if (!onlyEntry.endsWith(".jar", ignoreCase = true)) {
		return null
	}
	return File(onlyEntry).name
}

/**
 * Logs what a bug report needs to know about this launch: the version, the Java and the OS it runs on, how it
 * was started and the memory limit that gave it, and where this session's log is - which also puts the log's
 * location in the Logs panel.
 *
 * @param HostHeap        hostHeap   The launch's heap.
 * @param SessionLogFile? sessionLog The session log, or null when there is none.
 */
internal fun logLaunchFacts(hostHeap: HostHeap, sessionLog: SessionLogFile?) {
	UmamoLog.info(
		"Umamo ${ProjectInfo.VERSION} on Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}), " +
			"${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
	)
	val launch =
		when {
			hostHeap.packagedLaunch -> "the installed launcher"
			hostHeap.jarFileName != null -> "the jar ${hostHeap.jarFileName}"
			else -> "a development class path"
		}
	UmamoLog.info("started from $launch; the heap may grow to ${hostHeap.maxBytes / BYTES_PER_MEBIBYTE} MiB")
	sessionLog?.let { openLog -> UmamoLog.info("session log: ${openLog.path}") }
}