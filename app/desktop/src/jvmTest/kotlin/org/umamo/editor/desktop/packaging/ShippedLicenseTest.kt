package org.umamo.editor.desktop.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds the license to where it ships: inside the app image, so the archive and every installer made from the image
 * carry the GPL's text, and never as an installer page to agree to - the plugin's licenseFile would put an
 * Agree/Disagree dialog on the DMG, and `--license-file` in packageWindowsMsi an "I accept" page in the MSI - since
 * the GPL asks no one to accept it to run the program.  The DEB and RPM tasks pass `--license-file` on purpose: there
 * it installs the copyright file and asks nothing.  The release workflow checks the built image for the file, and
 * the macOS install test fails on a DMG that stops to ask.
 */
class ShippedLicenseTest {
	/** Gradle runs a module's tests from the module directory, which is what these paths are relative to. */
	private val buildScript = File("build.gradle.kts")

	@Test
	fun theLicenseShipsInsideTheAppImage() {
		assertTrue(
			"syncTask.name == \"prepareAppResources\" }\n\t.configureEach {\n\t\tfrom(projectLicense)\n\t}" in buildScript.readText(),
			"the app image's resources carry LICENSE",
		)
	}

	@Test
	fun noInstallerAsksForAgreement() {
		assertFalse(
			Regex("""licenseFile\s*(\.set\(|=)""").containsMatchIn(buildScript.readText()),
			"the plugin's licenseFile would put an agreement on the DMG",
		)
	}

	@Test
	fun theMsiShowsNoLicensePage() {
		val script = buildScript.readText()
		val taskStart = script.indexOf("tasks.register<Exec>(\"packageWindowsMsi\") {")
		assertTrue(taskStart >= 0, "packageWindowsMsi is registered where this test looks for it")
		val taskEnd = script.indexOf("commandLine(arguments)", taskStart)
		assertTrue(taskEnd > taskStart, "packageWindowsMsi ends in the commandLine this test reads up to")

		assertFalse(
			Regex(""""--license-file"\s+to""").containsMatchIn(script.substring(taskStart, taskEnd)),
			"--license-file would put an \"I accept\" page in the MSI",
		)
	}
}