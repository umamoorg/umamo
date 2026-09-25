package org.umamo.ui.app

import org.umamo.settings.Settings
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_export_out_of_memory
import org.umamo.ui.resources.alert_export_out_of_memory_jar
import org.umamo.ui.resources.alert_low_memory_jar
import org.umamo.ui.resources.dialog_dont_show_again
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.DialogAlternative

/**
 * The memory limit the editor's JVM started with, as the desktop host reads it at launch.  The installed
 * launcher sets that limit itself; a jar launch gets whatever Java picks, a quarter of the machine's memory,
 * which a large export can run out of.  A jar that starts that small relaunches itself with [JAR_HEAP_OPTION]
 * (the desktop's JarRelaunch.kt), so the shell warns only a jar that could not, and an export that runs out
 * tells only such a jar the command that raises it.  Android has no such choice and passes none.
 *
 * @property Long    maxBytes          The most the heap may grow to.
 * @property Boolean packagedLaunch    Whether the installed launcher started the editor; false for a jar or a
 *   development run.
 * @property String? jarFileName       The jar's file name when the editor was started from one, which the
 *   command the alerts print names; null otherwise.
 * @property Boolean heapOptionApplied Whether a jar already relaunched itself with [JAR_HEAP_OPTION]: its limit
 *   is then what the installed launcher would give it, and printing the option again would offer nothing.
 */
class HostHeap(
	val maxBytes: Long,
	val packagedLaunch: Boolean,
	val jarFileName: String?,
	val heapOptionApplied: Boolean = false,
)

/**
 * The JVM option that lets the editor use half of the machine's memory.  The installed launcher carries it
 * (`app/desktop/build.gradle.kts`), and the jar's instructions - the alerts here, README, RELEASING, and the
 * release notes - print the same one, which `LauncherHeapOptionTest` holds them to.
 */
const val JAR_HEAP_OPTION = "-XX:MaxRAMPercentage=50"

/**
 * The limit below which a jar launch relaunches itself with [JAR_HEAP_OPTION], or is warned when it cannot:
 * under it, a 3 × 8192² model's export can run out.
 */
const val LOW_HEAP_NOTICE_BELOW_BYTES = 3L * 1024 * 1024 * 1024

/** The setting the low-memory alert's Don't Show Again turns off. */
internal const val SHOW_LOW_MEMORY_NOTICE_KEY = "app.showLowMemoryNotice"

/** The jar's name in the printed command when the launch did not reveal it. */
private const val JAR_FILE_NAME_PLACEHOLDER = "umamo-<target>-<version>.jar"

/**
 * Whether the low-memory alert is due at launch: a jar launch below [LOW_HEAP_NOTICE_BELOW_BYTES] whose
 * rigger has not turned the alert off.  The installed launcher sets its own limit, and a jar that relaunched
 * with [JAR_HEAP_OPTION] has the same one, so neither is warned: the alert's command would change nothing.
 *
 * @param HostHeap? hostHeap      The launch's heap, or null where the host reports none.
 * @param Boolean   noticeEnabled Whether the alert is still wanted.
 * @return Boolean Whether to show it.
 */
internal fun lowHeapNoticeDue(hostHeap: HostHeap?, noticeEnabled: Boolean): Boolean = hostHeap != null && hostHeap.canRaiseHeapWithOption() && noticeEnabled && hostHeap.maxBytes < LOW_HEAP_NOTICE_BELOW_BYTES

/**
 * Whether starting with [JAR_HEAP_OPTION] would raise this launch's limit: only for a jar that does not have
 * it yet.  The installed launcher and a relaunched jar already run with it.
 *
 * @return Boolean Whether the option would help.
 */
internal fun HostHeap.canRaiseHeapWithOption(): Boolean = !packagedLaunch && !heapOptionApplied

/**
 * The command that starts this jar with [JAR_HEAP_OPTION].
 *
 * @return String The command, e.g. `java -XX:MaxRAMPercentage=50 -jar umamo-linux-x64-0.4.0.jar`.
 */
internal fun HostHeap.jarLaunchCommand(): String = "java $JAR_HEAP_OPTION -jar ${jarFileName ?: JAR_FILE_NAME_PLACEHOLDER}"

/**
 * A memory limit as the alerts print it: gigabytes to one decimal, rounded.
 *
 * @param Long maxBytes The limit in bytes.
 * @return String The limit, e.g. `2.0 GB`.
 */
internal fun describeHeapLimit(maxBytes: Long): String {
	// Mebibytes first, so the multiplication cannot overflow even for Long.MAX_VALUE's "no limit".
	val tenthsOfGigabyte = (maxBytes / (1024L * 1024) * 10 + 512) / 1024
	return "${tenthsOfGigabyte / 10}.${tenthsOfGigabyte % 10} GB"
}

/**
 * The launch-time low-memory alert, when it is due: the limit, the command that raises it, and a Don't Show
 * Again that turns it off for good.
 *
 * @param HostHeap? hostHeap The launch's heap, or null where the host reports none.
 * @param Settings  settings The settings holding [SHOW_LOW_MEMORY_NOTICE_KEY].
 * @return AlertRequest? The alert, or null when it is not due.
 */
internal fun lowMemoryNoticeRequest(hostHeap: HostHeap?, settings: Settings): AlertRequest? {
	// Absent reads as wanted, so a settings file from before the key existed still shows it.
	if (hostHeap == null || !lowHeapNoticeDue(hostHeap, settings.getBoolean(SHOW_LOW_MEMORY_NOTICE_KEY) != false)) {
		return null
	}
	return AlertRequest(
		message = Res.string.alert_low_memory_jar,
		arguments = listOf(describeHeapLimit(hostHeap.maxBytes), hostHeap.jarLaunchCommand()),
		alternative = DialogAlternative(Res.string.dialog_dont_show_again) { settings.setBoolean(SHOW_LOW_MEMORY_NOTICE_KEY, false) },
	)
}

/**
 * The alert for an export that ran out of memory.  A jar launch is told its limit and the command that
 * raises it; the installed launcher and a relaunched jar already use half of the machine's memory, so there is
 * no option to offer them.
 *
 * @param String    destinationName The file the export was writing.
 * @param HostHeap? hostHeap        The launch's heap, or null where the host reports none.
 * @return AlertRequest The alert.
 */
internal fun exportOutOfMemoryAlert(destinationName: String, hostHeap: HostHeap?): AlertRequest =
	if (hostHeap != null && hostHeap.canRaiseHeapWithOption()) {
		AlertRequest(Res.string.alert_export_out_of_memory_jar, listOf(destinationName, describeHeapLimit(hostHeap.maxBytes), hostHeap.jarLaunchCommand()))
	} else {
		AlertRequest(Res.string.alert_export_out_of_memory, listOf(destinationName))
	}