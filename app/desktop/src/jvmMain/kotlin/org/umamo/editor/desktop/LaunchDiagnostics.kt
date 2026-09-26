package org.umamo.editor.desktop

import okio.FileSystem
import okio.IOException
import okio.Path
import org.umamo.storage.AppStorage
import org.umamo.storage.SessionLogFile
import org.umamo.storage.UmamoLog
import org.umamo.ui.app.HostHeap
import org.umamo.ui.help.ProjectInfo
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/*
 * What the desktop host sets up and records before anything else runs: the session log every later line
 * lands in, the handler that gets an uncaught failure into it, and the memory limit this JVM started with.
 * A bug report needs all three, and only the first lines of main run early enough to catch everything.
 */

/** The logs directory's name under the app's data directory. */
private const val LOG_DIRECTORY_NAME = "logs"

/** How long `xdg-open` may take to hand the log folder to the file manager before it counts as failed. */
private const val XDG_OPEN_TIMEOUT_SECONDS = 10L

/** The system property the installed launcher sets (its umamo.cfg); a jar or development launch has none. */
internal const val PACKAGED_VERSION_PROPERTY = "jpackage.app-version"

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
		SessionLogFile.open(FileSystem.SYSTEM, logDirectoryOf(storage), Clock.System.now()).also { sessionLog -> UmamoLog.addSink(sessionLog) }
	} catch (failure: IOException) {
		UmamoLog.warn("no session log this time: ${failure.message}")
		null
	}

/**
 * The folder the session logs are written to, under the app's data directory.
 *
 * @param AppStorage storage The app's directories.
 * @return Path The logs folder.
 */
internal fun logDirectoryOf(storage: AppStorage): Path = storage.dataDirectory / LOG_DIRECTORY_NAME

/**
 * Hands the session-log folder to the desktop's file manager: Help > Open Log Folder, for the rigger attaching a log
 * to a bug report.  Java's own desktop integration opens it where the platform supports that, and on Linux
 * `xdg-open` does where it does not (a desktop Java has no integration for).  Runs off the UI thread: either can wait
 * on the file manager.
 *
 * @param AppStorage storage The app's directories.
 * @return String? Null once the folder was handed over; otherwise its path, which the failure alert names.
 */
internal fun openLogFolder(storage: AppStorage): String? {
	val folder = logDirectoryOf(storage).toFile()
	return try {
		folder.mkdirs()
		if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			Desktop.getDesktop().open(folder)
		} else {
			check(System.getProperty("os.name").orEmpty().startsWith("Linux")) { "this desktop cannot open a folder" }
			val opener = ProcessBuilder("xdg-open", folder.absolutePath).redirectErrorStream(true).start()
			check(opener.waitFor(XDG_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS) && opener.exitValue() == 0) { "xdg-open did not open it" }
		}
		null
	} catch (failure: Exception) {
		UmamoLog.warn("could not open the log folder ${folder.absolutePath}: ${failure.message}")
		folder.absolutePath
	}
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
		heapOptionApplied = System.getProperty(RELAUNCHED_PROPERTY) != null,
	)

/**
 * The file name of the jar the editor was started from (see [launchedJarPath]).
 *
 * @param String classPath     The `java.class.path` value.
 * @param String pathSeparator The class path's entry separator.
 * @return String? The jar's file name, or null when the class path is not a single jar.
 */
internal fun launchedJarFileName(classPath: String, pathSeparator: String = File.pathSeparator): String? = launchedJarPath(classPath, pathSeparator)?.let { jarPath -> File(jarPath).name }

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

	val bytesPerMebibyte = 1024L * 1024
	UmamoLog.info("started from $launch; the heap may grow to ${hostHeap.maxBytes / bytesPerMebibyte} MiB")
	relaunchLogLine(System.getProperty(RELAUNCHED_PROPERTY))?.let { relaunchLine -> UmamoLog.info(relaunchLine) }
	sessionLog?.let { openLog -> UmamoLog.info("session log: ${openLog.path}") }
}