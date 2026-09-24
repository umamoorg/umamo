package org.umamo.ui.app

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_export_out_of_memory
import org.umamo.ui.resources.alert_export_out_of_memory_jar
import org.umamo.ui.resources.alert_low_memory_jar
import org.umamo.ui.resources.dialog_dont_show_again
import org.umamo.ui.workspace.AlertRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins who is told about a small memory limit and what they are told: only a jar launch, only below the
 * threshold, only until they ask not to be, and always with a command they can paste.
 */
class HostHeapTest {
	private val twoGibibytes = 2L * 1024 * 1024 * 1024
	private val jarLaunch = HostHeap(twoGibibytes, packagedLaunch = false, jarFileName = "umamo-linux-x64-0.4.0.jar")

	/**
	 * Settings over an empty in-memory config directory.
	 *
	 * @return Settings The settings.
	 */
	private fun freshSettings(): Settings {
		val fileSystem = FakeFileSystem()
		val configDirectory = "/config".toPath()
		fileSystem.createDirectories(configDirectory)
		return Settings.load(OkioAppStorage(fileSystem, configDirectory, "/data".toPath()), "{}")
	}

	@Test
	fun onlyAJarLaunchBelowTheThresholdIsDue() {
		assertTrue(lowHeapNoticeDue(jarLaunch, noticeEnabled = true))
		assertFalse(lowHeapNoticeDue(jarLaunch, noticeEnabled = false), "turned off")
		assertFalse(lowHeapNoticeDue(HostHeap(twoGibibytes, packagedLaunch = true, jarFileName = null), noticeEnabled = true), "the launcher sets its own limit")
		assertFalse(lowHeapNoticeDue(HostHeap(LOW_HEAP_NOTICE_BELOW_BYTES, packagedLaunch = false, jarFileName = null), noticeEnabled = true), "at the threshold")
		assertFalse(lowHeapNoticeDue(HostHeap(Long.MAX_VALUE, packagedLaunch = false, jarFileName = null), noticeEnabled = true), "no limit at all")
		assertFalse(lowHeapNoticeDue(null, noticeEnabled = true), "a host that reports none")
	}

	@Test
	fun limitsReadAsGigabytesToOneDecimal() {
		assertEquals("2.0 GB", describeHeapLimit(twoGibibytes))
		assertEquals("1.5 GB", describeHeapLimit(1536L * 1024 * 1024))
		assertEquals("0.9 GB", describeHeapLimit(910L * 1024 * 1024))
		// The no-limit sentinel must not overflow into nonsense.
		assertEquals("8589934592.0 GB", describeHeapLimit(Long.MAX_VALUE))
	}

	@Test
	fun theCommandNamesTheJarWithTheLaunchersOption() {
		assertEquals("java -XX:MaxRAMPercentage=50 -jar umamo-linux-x64-0.4.0.jar", jarLaunch.jarLaunchCommand())
		assertEquals("java -XX:MaxRAMPercentage=50 -jar umamo-<target>-<version>.jar", HostHeap(twoGibibytes, packagedLaunch = false, jarFileName = null).jarLaunchCommand())
	}

	@Test
	fun dontShowAgainTurnsTheNoticeOffForGood() {
		val settings = freshSettings()

		val notice = assertNotNull(lowMemoryNoticeRequest(jarLaunch, settings))
		assertEquals(Res.string.alert_low_memory_jar, notice.message)
		assertEquals(listOf("2.0 GB", "java -XX:MaxRAMPercentage=50 -jar umamo-linux-x64-0.4.0.jar"), notice.arguments)
		val alternative = assertNotNull(notice.alternative)
		assertEquals(Res.string.dialog_dont_show_again, alternative.label)

		alternative.onSelect()

		assertEquals(false, settings.getBoolean(SHOW_LOW_MEMORY_NOTICE_KEY))
		assertNull(lowMemoryNoticeRequest(jarLaunch, settings))
	}

	@Test
	fun noNoticeWhenItIsNotDue() {
		assertNull(lowMemoryNoticeRequest(HostHeap(twoGibibytes, packagedLaunch = true, jarFileName = null), freshSettings()))
		assertNull(lowMemoryNoticeRequest(null, freshSettings()))
	}

	@Test
	fun anExportThatRunsOutTellsAJarLaunchHowToRaiseTheLimit() {
		assertEquals(
			AlertRequest(Res.string.alert_export_out_of_memory_jar, listOf("rig.cmo3", "2.0 GB", "java -XX:MaxRAMPercentage=50 -jar umamo-linux-x64-0.4.0.jar")),
			exportOutOfMemoryAlert("rig.cmo3", jarLaunch),
		)
		// The installed launcher already uses half of the machine's memory, and Android has no option to offer.
		val plain = AlertRequest(Res.string.alert_export_out_of_memory, listOf("rig.cmo3"))
		assertEquals(plain, exportOutOfMemoryAlert("rig.cmo3", HostHeap(twoGibibytes, packagedLaunch = true, jarFileName = null)))
		assertEquals(plain, exportOutOfMemoryAlert("rig.cmo3", null))
	}
}