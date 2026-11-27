package org.umamo.editor.desktop

import org.umamo.storage.LogLevel
import org.umamo.storage.UmamoLog
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the two launch facts the desktop reads off the JVM that a bug report depends on: which jar the editor
 * was started from, which the heap alerts name, and that a crash's reason reaches the log before the handler
 * that was there before sees it.  Also that the folder opener behind Open Log Folder neither hangs on its own
 * output nor outlives its timeout.
 */
class LaunchDiagnosticsTest {
	@Test
	fun aSingleJarOnTheClassPathIsTheLaunchedJar() {
		assertEquals("umamo-linux-x64-0.4.0.jar", launchedJarFileName("umamo-linux-x64-0.4.0.jar", ":"))
		assertEquals("umamo-linux-x64-0.4.0.jar", launchedJarFileName("/home/rigger/Downloads/umamo-linux-x64-0.4.0.jar", ":"))
		// Windows' separator and an upper-case extension; forward slashes, which java.io.File reads on every OS.
		assertEquals("umamo-windows-x64-0.4.0.JAR", launchedJarFileName("C:/Users/rigger/umamo-windows-x64-0.4.0.JAR", ";"))
	}

	@Test
	fun theLauncherAndADevelopmentRunNameNoJar() {
		// The installed launcher and `:desktop:run` both list many entries.
		assertNull(launchedJarFileName("/opt/umamo/lib/app/ui-jvm.jar:/opt/umamo/lib/app/format-jvm.jar", ":"))
		assertNull(launchedJarFileName("/work/umamo/app/desktop/build/classes/kotlin/jvm/main", ":"))
		assertNull(launchedJarFileName("", ":"))
	}

	@Test
	fun aChattyFolderOpenerStillFinishes() {
		if (!File("/bin/sh").exists()) {
			skipTest("skipping: no POSIX shell to stand in for xdg-open")
		}

		// A megabyte of output is far past what a pipe holds: left in a pipe nothing reads, the opener would block
		// on its write and never exit.
		assertTrue(runFolderOpener(listOf("/bin/sh", "-c", "head -c 1048576 /dev/zero"), timeoutSeconds = 10))
		assertFalse(runFolderOpener(listOf("/bin/sh", "-c", "exit 3"), timeoutSeconds = 10), "a failing opener reports failure")
	}

	@Test
	fun aFolderOpenerThatHangsIsStoppedAtTheTimeout() {
		if (!File("/bin/sh").exists()) {
			skipTest("skipping: no POSIX shell to stand in for xdg-open")
		}

		val started = System.nanoTime()

		assertFalse(runFolderOpener(listOf("/bin/sh", "-c", "sleep 30"), timeoutSeconds = 1))
		assertTrue(System.nanoTime() - started < 10_000_000_000L, "it returns at the timeout rather than waiting the opener out")
	}

	@Test
	fun anUncaughtFailureIsLoggedBeforeThePreviousHandlerSeesIt() {
		val original = Thread.getDefaultUncaughtExceptionHandler()
		val delegated = ArrayList<Throwable>()
		val marker = "uncaught-${System.nanoTime()}"

		try {
			val stub = Thread.UncaughtExceptionHandler { _, throwable -> delegated.add(throwable) }
			Thread.setDefaultUncaughtExceptionHandler(stub)

			assertSame(stub, installUncaughtExceptionLogging(), "the handler that was there before is handed back")
			val failure = IllegalStateException(marker)
			Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), failure)

			assertEquals(listOf<Throwable>(failure), delegated, "the previous handler still reports it")
			assertTrue(UmamoLog.entries.value.any { entry -> entry.level == LogLevel.Error && entry.message.contains(marker) }, "the log has it")
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(original)
		}
	}
}