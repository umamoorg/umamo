package org.umamo.editor.desktop.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds the license to where it ships: inside the app image, so the archive and every installer made from the image
 * carry the GPL's text, and never as the plugin's licenseFile, which would put an Agree/Disagree dialog on the DMG
 * and an "I accept" page in the MSI - the GPL asks no one to accept it to run the program.  The release workflow
 * checks the built image for the file, and the macOS install test fails on a DMG that stops to ask.
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
			"the plugin's licenseFile would put an agreement on the DMG and a license page in the MSI",
		)
	}
}