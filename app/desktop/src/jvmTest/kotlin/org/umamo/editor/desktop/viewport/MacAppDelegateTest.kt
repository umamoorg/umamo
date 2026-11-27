package org.umamo.editor.desktop.viewport

import org.umamo.testkit.skipTest
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** The probe's report that AWT's delegate is in place after glfwInit. */
private const val DELEGATE_KEPT = "kept"

/** The prefix of the probe's report that it could not run, followed by the reason. */
private const val PROBE_SKIPPED = "skip: "

/**
 * The second process [MacAppDelegateTest] runs: starts AWT and then GLFW in the order the editor does, and reports
 * on stdout whether AWT's NSApplication delegate is the delegate afterwards.
 */
object MacAppDelegateProbe {
	/**
	 * Runs the probe and prints its one-line report.
	 *
	 * @param Array<String> args Unused.
	 */
	@JvmStatic
	fun main(args: Array<String>) {
		println(probe())
		System.out.flush()
		Runtime.getRuntime().halt(0)
	}

	/**
	 * Starts AWT, then GLFW the way the viewport does, and compares the delegates.
	 *
	 * @return String [DELEGATE_KEPT], a skip reason after [PROBE_SKIPPED], or the delegate that replaced AWT's.
	 */
	private fun probe(): String {
		if (GraphicsEnvironment.isHeadless()) {
			return PROBE_SKIPPED + "a headless JVM has no AWT application delegate"
		}

		// Desktop starts AWT's toolkit, which installs the JDK's ApplicationDelegate, as the editor's main does.
		Desktop.getDesktop()
		val awtDelegate = currentAppKitDelegate()

		if (awtDelegate == 0L) {
			return PROBE_SKIPPED + "AWT installed no NSApplication delegate"
		}

		val context = createOffscreenGlContext()

		if (!context.createAndMakeCurrent()) {
			return PROBE_SKIPPED + "no GL context: ${context.failureReason()}"
		}

		// The restore runs on the AppKit thread without the caller waiting, so give it a moment to land.
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

		while (currentAppKitDelegate() != awtDelegate && System.nanoTime() < deadline) {
			Thread.sleep(10)
		}

		return if (currentAppKitDelegate() == awtDelegate) DELEGATE_KEPT else "replaced by ${className(currentAppKitDelegate())}"
	}
}

/**
 * The viewport's GLFW must leave AWT's NSApplication delegate in place, since that delegate is how a file opened
 * from Finder and the Dock's Quit reach a running Umamo.  Runs in a process of its own: the editor always starts AWT
 * before GLFW, and a test JVM whose other tests have already started GLFW cannot start AWT afterwards.  macOS only.
 */
class MacAppDelegateTest {
	/** The delegate AWT installed is the delegate again once the offscreen context has started GLFW. */
	@Test
	fun theOffscreenContextLeavesTheAwtDelegateInPlace() {
		if (!isMacOs()) {
			skipTest("skipping: the NSApplication delegate exists only on macOS")
		}

		val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
		val probe =
			ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), MacAppDelegateProbe::class.java.name)
				.redirectErrorStream(true)
				.start()

		if (!probe.waitFor(60, TimeUnit.SECONDS)) {
			probe.destroyForcibly()
			fail("the probe process did not finish within 60 seconds")
		}

		val lines = probe.inputStream.bufferedReader().readLines()
		val report = lines.lastOrNull { line -> line == DELEGATE_KEPT || line.startsWith(PROBE_SKIPPED) || line.startsWith("replaced by ") }

		if (report?.startsWith(PROBE_SKIPPED) == true) {
			skipTest("skipping: ${report.removePrefix(PROBE_SKIPPED)}")
		}

		assertEquals(DELEGATE_KEPT, report, "the probe's output:\n${lines.joinToString("\n")}")
	}
}