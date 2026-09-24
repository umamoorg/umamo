package org.umamo.editor.desktop.packaging

import org.umamo.ui.app.JAR_HEAP_OPTION
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds every place that states the editor's heap option to the one the in-app alerts print.  The installed
 * launcher gets it from the build script, while a jar user can only learn it from README, RELEASING, and the
 * release notes - five statements of one flag that no compiler connects.  A heap flag that drifted between
 * them is how a jar user ended up running out of memory in an export: the docs said one thing, the launcher
 * another, and the jar nothing.
 */
class LauncherHeapOptionTest {
	/** Gradle runs a module's tests from the module directory, which is what these paths are relative to. */
	private val buildScript = File("build.gradle.kts")
	private val readme = File("../../README.md")
	private val releasing = File("../../RELEASING.md")
	private val releaseWorkflow = File("../../.github/workflows/release.yml")

	@Test
	fun theLauncherCarriesTheOptionAndNoFixedHeap() {
		val script = buildScript.readText()

		assertTrue(script.contains("jvmArgs.add(\"$JAR_HEAP_OPTION\")"), "the installed launcher starts with $JAR_HEAP_OPTION")
		assertFalse(script.contains("jvmArgs.add(\"-Xmx"), "a fixed -Xmx would override the percentage")
	}

	@Test
	fun everyJarInstructionPrintsTheSameOption() {
		for (document in listOf(readme, releasing, releaseWorkflow)) {
			val text = document.readText()
			assertTrue(text.contains("java $JAR_HEAP_OPTION -jar"), "${document.name} tells jar users to start with $JAR_HEAP_OPTION")
			assertFalse(text.contains("java -Xmx"), "${document.name} still prints a fixed -Xmx")
		}
	}

	@Test
	fun theReleaseChecksAssertTheLaunchersOption() {
		for (document in listOf(releasing, releaseWorkflow)) {
			assertTrue(document.readText().contains("java-options=$JAR_HEAP_OPTION"), "${document.name} checks umamo.cfg for $JAR_HEAP_OPTION")
		}
	}
}