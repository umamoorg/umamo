package org.umamo.editor.desktop.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what every installed copy of Umamo depends on staying the same.  An MSI finds the install it replaces by its
 * upgrade code, and a package manager by its package name; move either, or the folder the files go to, and the next
 * release installs BESIDE the old one instead of over it - two Umamos, and an uninstall that removes the wrong one.
 * The release workflow's upgrade test cannot catch that, since the older installer it upgrades from is built by the
 * same script.  These values change only on purpose, together with docs/plan/distribution.md's identities.
 */
class InstallerIdentityTest {
	/** Gradle runs a module's tests from the module directory, which is what these paths are relative to. */
	private val buildScript = File("build.gradle.kts")
	private val rpmSpec = File("packaging/linux/umamo.spec")
	private val debControl = File("packaging/linux/control")
	private val debPostinst = File("packaging/linux/postinst")
	private val debPrerm = File("packaging/linux/prerm")
	private val releaseWorkflow = File("../../.github/workflows/release.yml")

	/** The MSI's upgrade code, minted once on 2026-09-26. */
	private val upgradeUuid = "4de3a745-151a-4a49-b7bb-1ba57a9006d5"

	@Test
	fun theMsiKeepsItsUpgradeCodeAndItsPerUserFolder() {
		val script = buildScript.readText()

		assertTrue("upgradeUuid = \"$upgradeUuid\"" in script, "the upgrade code every later MSI finds this one by")
		assertTrue("perUserInstall = true" in script, "a per-user install, with no administrator prompt")
		assertTrue(
			"installationPath = \"Programs" + "\\".repeat(4) + "Umamo\"" in script,
			"under %LOCALAPPDATA%\\Programs, clear of the data folder; the separator doubled for jpackage's @argfile",
		)
		assertTrue("menuGroup = \"Umamo\"" in script, "a Start-menu entry in an Umamo folder")
		assertTrue("dirChooser = false" in script, "no folder page: the plugin would offer one")
		assertTrue("shortcut = false" in script, "no desktop shortcut")
		assertTrue(buildScript.readText().contains("buildTarget.startsWith(\"windows-\")"), "Windows builds are named Umamo")
	}

	@Test
	fun theWorkflowChecksTheSameUpgradeCode() {
		assertTrue(upgradeUuid.uppercase() in releaseWorkflow.readText().uppercase(), "the built MSI is checked against this upgrade code")
	}

	@Test
	fun theLinuxPackagesKeepTheirNameFolderMaintainerAndLicense() {
		val script = buildScript.readText()

		assertTrue("\"--linux-package-name\" to \"umamo\"" in script, "the package name every later package upgrades")
		assertTrue("\"--install-dir\" to \"/opt\"" in script, "installed as /opt/umamo")
		assertTrue("\"--linux-deb-maintainer\" to \"umamo@proton.me\"" in script)
		assertTrue("val appLicenseIdentifier = \"GPL-3.0-only\"" in script)
		assertTrue("\"--linux-rpm-license-type\" to appLicenseIdentifier" in script)
	}

	@Test
	fun anUpgradeFixtureNeverReachesTheAppImage() {
		val script = buildScript.readText()

		assertTrue("packageVersion = umamoVersionNumeric" in script, "the image always carries the real version")
		assertFalse("packageVersion = installerVersion" in script, "the fixture version never reaches the image")
		assertTrue("msiPackageVersion = installerVersion" in script, "only the installers take it")
		assertTrue("\"--app-version\" to installerVersion" in script)
	}

	@Test
	fun theRpmUpgradeKeepsTheDesktopIntegration() {
		val spec = rpmSpec.readText()

		assertEquals(1, Regex("DESKTOP_COMMANDS_UNINSTALL").findAll(spec).count(), "the desktop uninstall runs from one place")
		assertTrue(
			"if [ \"$1\" = 0 ]; then\n  ( true; DESKTOP_COMMANDS_UNINSTALL\n  ) || true\nfi" in spec,
			"and only on a real removal: an upgrade runs the old package's preun after the new package's post",
		)
	}

	@Test
	fun theDesktopRegistrationNeverBlocksAnInstallOrARemoval() {
		assertTrue("( true; DESKTOP_COMMANDS_INSTALL\n) || true" in rpmSpec.readText(), "the RPM's post")
		assertTrue("( true; DESKTOP_COMMANDS_INSTALL\n) || true" in debPostinst.readText(), "the DEB's postinst")
		assertTrue("( true; DESKTOP_COMMANDS_UNINSTALL\n) || true" in debPrerm.readText(), "the DEB's prerm")
		for ((script, placeholder) in listOf(rpmSpec to "DESKTOP_COMMANDS_INSTALL", debPostinst to "DESKTOP_COMMANDS_INSTALL", debPrerm to "DESKTOP_COMMANDS_UNINSTALL")) {
			assertEquals(1, Regex(placeholder).findAll(script.readText()).count(), "${script.name} runs them from one place")
		}
	}

	@Test
	fun theDebInstallsOnBothUbuntuGenerations() {
		val depends = debControl.readLines().single { line -> line.startsWith("Depends: ") }

		assertTrue("libasound2t64 | libasound2" in depends, "24.04's renamed package, or 22.04's")
		assertTrue("xdg-utils" in depends, "the menu entry and the .uma registration are installed with xdg-utils")
		assertFalse("PACKAGE_DEFAULT_DEPENDENCIES" in depends, "not the list jpackage would compute on the build machine")
	}
}