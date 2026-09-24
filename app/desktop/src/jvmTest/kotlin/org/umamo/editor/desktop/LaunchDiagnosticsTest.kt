package org.umamo.editor.desktop

import org.umamo.storage.LogLevel
import org.umamo.storage.UmamoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the two launch facts the desktop reads off the JVM that a bug report depends on: which jar the editor
 * was started from, which the heap alerts name, and that a crash's reason reaches the log before the handler
 * that was there before sees it.
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