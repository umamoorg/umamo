package org.umamo.editor.desktop

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.Version
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import org.umamo.editor.desktop.viewport.configureLwjglNatives
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.storage.desktopAppStorage
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.document.umamoWriterInfo
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager

/** The command-line flag that runs the checks below instead of the editor, optionally followed by a report file. */
internal const val SELF_CHECK_FLAG = "--self-check"

/** How long the checks may take before the watchdog gives up on them: a hung check must still end the process. */
private const val SELF_CHECK_TIMEOUT_MILLIS = 60_000L

/** The exit code of a run the watchdog ended. */
private const val SELF_CHECK_TIMED_OUT_EXIT_CODE = 3

/**
 * One self-check: a name for the report, and the work, which returns a short description of what it found or throws
 * to fail.
 *
 * @property String   name  The check's name in the report.
 * @property Function check The check itself.
 */
internal class SelfCheck(
	val name: String,
	val check: () -> String,
)

/**
 * Everything an installed Umamo needs from its bundled runtime that an ordinary launch does not touch until a rigger
 * opens the right file: the runtime's own modules, the SQLite driver and its native library (a CLIP import), the XML
 * stack and the reflective serializer (every CMO3), the codecs, Skia, LWJGL's natives, the bundled resources, and the
 * operating system's login module (the file dialogs on Linux).  None of them opens a window, reads or writes a
 * setting, or writes a log; the only files they leave are the native libraries a normal launch unpacks as well.
 *
 * @return List The checks, in report order.
 */
internal fun selfChecks(): List<SelfCheck> =
	listOf(
		SelfCheck("runtime") {
			val javaHome = System.getProperty("java.home")
			// Under a jpackage launcher the runtime must be the one the image carries, not a JDK on the machine that
			// happens to satisfy every other check.
			if (System.getProperty("jpackage.app-version") != null) {
				val launcherPath = checkNotNull(System.getProperty("jpackage.app-path")) { "the launcher did not say where it is" }
				val imageRoot = Path.of(launcherPath).parent.parent
				check(Path.of(javaHome).startsWith(imageRoot)) { "the runtime at $javaHome is not the app's own, under $imageRoot" }
			}
			"Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) at $javaHome"
		},
		SelfCheck("modules") {
			val required = listOf("java.desktop", "java.instrument", "java.logging", "java.sql", "java.xml", "jdk.security.auth", "jdk.unsupported")
			val missing = required.filter { moduleName -> ModuleLayer.boot().findModule(moduleName).isEmpty }
			check(missing.isEmpty()) { "missing ${missing.joinToString()}" }
			// jdk.unsupported's one class every native library user here leans on.
			Class.forName("sun.misc.Unsafe")
			required.joinToString()
		},
		SelfCheck("sqlite") {
			DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
				connection.createStatement().use { statement ->
					statement.executeQuery("select sqlite_version()").use { result ->
						check(result.next()) { "the query returned no row" }
						"SQLite ${result.getString(1)}"
					}
				}
			}
		},
		SelfCheck("cmo3") {
			val puppet =
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = emptyList(),
					rootChildren = emptyList(),
					rootPartId = null,
					canvasWidth = 64f,
					canvasHeight = 64f,
					worldOriginX = 32f,
					worldOriginZ = -32f,
					runtimeTarget = RuntimeTarget.Cubism53,
				)
			// The conversion itself parses the graph it assembles, and the second read parses what the writer made.
			val converted = Cmo3Conversion.freshCmo3(puppet, emptyList(), emptyMap(), "self-check", nowMillis = 0L, obfuscateKey = 0)
			val bytes = Cmo3.write(converted.model)
			Cmo3.read(bytes)
			"an empty model wrote ${bytes.size} bytes and read back"
		},
		SelfCheck("uma") {
			val bytes = Uma.write(UmaModel.create(umamoWriterInfo()))
			Uma.read(bytes)
			"an empty document wrote ${bytes.size} bytes and read back"
		},
		SelfCheck("png") {
			val raster = RasterImage(4, 4, ByteArray(4 * 4 * 4) { index -> (index * 13).toByte() })
			check(PngCodec.read(PngCodec.write(raster)).rgba.contentEquals(raster.rgba)) { "the pixels changed on the way through" }
			"4x4 round trip"
		},
		SelfCheck("skia") {
			val pixels = ByteArray(4 * 4 * 4) { 0x7F }
			val image = Image.makeRaster(ImageInfo(4, 4, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), pixels, 4 * 4)
			val encoded = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Skia encoded nothing" }
			"encoded a raster as ${encoded.size} bytes"
		},
		SelfCheck("lwjgl") {
			configureLwjglNatives()
			MemoryStack.stackPush().use { stack -> stack.malloc(16) }
			// Loads GLFW's native library without starting GLFW, which would need a display.
			"LWJGL ${Version.getVersion()}, GLFW ${GLFW.glfwGetVersionString()}"
		},
		SelfCheck("resources") {
			val defaults = runBlocking { defaultSettingsJson() }
			check(defaults.trimStart().startsWith("{")) { "the default settings are not a JSON object" }
			"default settings, ${defaults.length} characters"
		},
		SelfCheck("login-module") {
			// jdk.security.auth, which the file dialogs' D-Bus connection authenticates through on Linux.  Each OS has
			// its own class, so the check names it rather than linking to one.
			val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
			val className = if (windows) "com.sun.security.auth.module.NTSystem" else "com.sun.security.auth.module.UnixSystem"
			val system = Class.forName(className).getDeclaredConstructor().newInstance()
			val identity = if (windows) system.javaClass.getMethod("getName").invoke(system) else system.javaClass.getMethod("getUid").invoke(system)
			"${className.substringAfterLast('.')} reports $identity"
		},
		SelfCheck("storage") {
			val storage = desktopAppStorage("umamo")
			"settings in ${storage.configDirectory}, data in ${storage.dataDirectory}"
		},
	)

/**
 * Runs every check and reports each on its own line, `name: OK detail` or `name: FAILED reason`, to [output] and, when
 * given, to [reportPath] - where a caller that cannot read the process's output (a Windows GUI launcher) finds them.
 * The report is English diagnostics, like the session log, not user interface.
 *
 * @param String?  reportPath The file to write the report to, replaced if it exists; null for none.
 * @param List     checks     The checks to run; every one of [selfChecks] unless a test says otherwise.
 * @param Function output     Receives each report line.
 * @return Int 0 when every check passed, 1 otherwise: the process's exit code.
 */
internal fun runSelfCheck(
	reportPath: String?,
	checks: List<SelfCheck> = selfChecks(),
	output: (String) -> Unit = ::println,
): Int {
	val reportFile = reportPath?.let { path -> File(path) }
	reportFile?.writeText("")
	var failures = 0
	for (selfCheck in checks) {
		val line =
			try {
				"${selfCheck.name}: OK ${selfCheck.check()}"
			} catch (failure: Throwable) {
				failures++
				"${selfCheck.name}: FAILED ${failure::class.java.name}: ${failure.message}"
			}
		output(line)
		reportFile?.appendText(line + "\n")
	}
	val summary = if (failures == 0) "self-check: every check passed" else "self-check: $failures check(s) failed"
	output(summary)
	reportFile?.appendText(summary + "\n")
	return if (failures == 0) 0 else 1
}

/**
 * Prepares the process for [runSelfCheck] as a command: no display is used, and a daemon watchdog ends the process
 * with [SELF_CHECK_TIMED_OUT_EXIT_CODE] if the checks hang, so an installer test waiting on it always gets an answer.
 */
internal fun prepareSelfCheckProcess() {
	System.setProperty("java.awt.headless", "true")
	val watchdog =
		Thread {
			Thread.sleep(SELF_CHECK_TIMEOUT_MILLIS)
			System.err.println("self-check: timed out after ${SELF_CHECK_TIMEOUT_MILLIS / 1000} seconds")
			Runtime.getRuntime().halt(SELF_CHECK_TIMED_OUT_EXIT_CODE)
		}
	watchdog.isDaemon = true
	watchdog.name = "self-check watchdog"
	watchdog.start()
}