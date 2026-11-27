package org.umamo.editor.desktop.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds the launch options - the ones that keep a JDK 24 or later runtime quiet about native access and
 * sun.misc.Unsafe - to the release workflow's checks of what it packaged: the app image's umamo.cfg, and the
 * uber jar's manifest, which carries the same two plus Multi-Release.  The build script states them and the
 * workflow asserts them line by line, and nothing but this test connects the two: an option renamed in one
 * place would pass the build, fail the release, or, worse, ship unchecked.
 */
class LauncherJvmOptionsTest {
	/** Gradle runs a module's tests from the module directory, which is what these paths are relative to. */
	private val buildScript = File("build.gradle.kts")
	private val releaseWorkflow = File("../../.github/workflows/release.yml")

	/** The options every launcher carries, in the `=` form that keeps each one line of umamo.cfg. */
	private val launcherOptions =
		listOf(
			"--enable-native-access=ALL-UNNAMED",
			"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
		)

	/** The manifest attributes the uber jar carries, as the build script spells each key and value. */
	private val manifestAttributes =
		listOf(
			"Multi-Release" to "true",
			"Add-Exports" to "java.base/jdk.internal.misc",
			"Enable-Native-Access" to "ALL-UNNAMED",
		)

	@Test
	fun theLauncherCarriesEachOption() {
		val script = buildScript.readText()

		for (option in launcherOptions) {
			assertTrue(script.contains("jvmArgs.add(\"$option\")"), "the installed launcher starts with $option")
		}
	}

	@Test
	fun theReleaseChecksEachOptionInThePackagedCfg() {
		val workflow = releaseWorkflow.readText()

		for (option in launcherOptions) {
			assertTrue(workflow.contains("java-options=$option"), "the release workflow checks umamo.cfg for $option")
		}
	}

	@Test
	fun theUberJarsManifestCarriesEachAttribute() {
		val script = buildScript.readText()

		for ((attribute, value) in manifestAttributes) {
			assertTrue(script.contains("\"$attribute\" to \"$value\""), "the uber jar's manifest carries $attribute: $value")
		}
	}

	@Test
	fun theReleaseChecksEachAttributeInTheBuiltJar() {
		val workflow = releaseWorkflow.readText()

		for ((attribute, value) in manifestAttributes) {
			assertTrue(workflow.contains("'$attribute: $value'"), "the release workflow checks the uber jar for $attribute: $value")
		}
	}
}