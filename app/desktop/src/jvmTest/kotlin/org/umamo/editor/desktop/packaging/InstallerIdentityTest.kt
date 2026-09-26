package org.umamo.editor.desktop.packaging

import java.io.File
import java.util.UUID
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
	private val msiTemplate = File("packaging/windows/main.wxs")
	private val releaseWorkflow = File("../../.github/workflows/release.yml")

	/** The MSI's upgrade code, minted once on 2026-09-26. */
	private val upgradeUuid = "bed289f4-0c1c-41c6-a315-70e44eeede4a"

	@Test
	fun theMsiKeepsItsUpgradeCodeAndItsPerUserFolder() {
		val script = buildScript.readText()

		assertTrue("val windowsUpgradeUuid = \"$upgradeUuid\"" in script, "the upgrade code every later MSI finds this one by")
		assertTrue("\"--win-upgrade-uuid\" to windowsUpgradeUuid" in script)
		assertTrue("val windowsInstallDirectory = \"Programs\\\\umamo\"" in script, "in %LOCALAPPDATA%\\Programs\\umamo")
		assertTrue("\"--install-dir\" to windowsInstallDirectory" in script)
		assertTrue("\"--win-per-user-install\"" in script, "a per-user install, with no administrator prompt")
		assertTrue("\"--win-menu-group\" to \"Umamo\"" in script, "a Start-menu entry in an Umamo folder")
		assertFalse("--win-dir-chooser" in script, "no folder page")
		assertFalse("--win-shortcut" in script, "no desktop shortcut")
		assertTrue(script.contains("buildTarget.startsWith(\"windows-\")"), "Windows builds are named Umamo")
	}

	/**
	 * The MSI template removes %LOCALAPPDATA%\Programs, the folder above the install folder, which jpackage leaves out
	 * and a per-user MSI may not (WiX's ICE64).  It names the folder by the id jpackage gives it, recomputed here as
	 * jpackage 21 computes it, so moving the install folder without moving the template fails this test rather than
	 * the Windows build.
	 */
	@Test
	fun theMsiTemplateRemovesTheFolderAboveTheInstallFolder() {
		val parentFolder = "TARGETDIR\\LocalAppDataFolder\\Programs"
		val folderId = "dir" + UUID.nameUUIDFromBytes("Folder@${parentFolder.lowercase()}".toByteArray()).toString().replace("-", "")
		val template = msiTemplate.readText()

		assertTrue("val windowsInstallDirectory = \"Programs\\\\" in buildScript.readText(), "the install folder sits in Programs")
		assertTrue("<DirectoryRef Id=\"$folderId\">" in template, "the template names Programs by jpackage's id for it")
		assertTrue("<RemoveFolder Id=\"UmamoRemoveProgramsFolder\" On=\"uninstall\"/>" in template)
		assertTrue("<ComponentRef Id=\"UmamoProgramsFolder\"/>" in template, "and installs the component")
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
		assertEquals(2, Regex("\"--app-version\" to installerVersion").findAll(script).count(), "only the installers take it: the MSI, and the DEB and RPM")
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
		assertTrue("libegl1" in depends, "Skiko's arm64 build links EGL")
		assertTrue("\"libEGL.so.1()(64bit)\"" in buildScript.readText(), "and so does the RPM's list")
		assertFalse("PACKAGE_DEFAULT_DEPENDENCIES" in depends, "not the list jpackage would compute on the build machine")
	}
}