package org.umamo.ui.workspace.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.testkit.skipTest
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.l10n.ProvideAppLocale
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.repack.AtlasRepackRefusal
import org.umamo.ui.model.repack.AtlasRepackRefusalReason
import org.umamo.ui.model.repack.AtlasRepackReport
import org.umamo.ui.progress.LocalProgressTracker
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTracker
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.workspace_new_name
import org.umamo.ui.theme.LocalRestingPointerIcon
import org.umamo.ui.workspace.SpaceDescriptor
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.shellCommandTables
import org.umamo.ui.workspace.layout.InterfaceLayout
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Mounts the whole shell and reads back what it wires: the commands it registers, in the order the palette
 * lists them, the modal overlays a command raises, and the resting pointer it hands the area tree.
 *
 * The command tables are tested one by one elsewhere; what only a mounted shell shows is the order its
 * registration effects run in, which is the order every command reaches the registry - and so the palette -
 * and what a registered command still sees after the shell's language changes under it.
 */
@OptIn(ExperimentalTestApi::class)
class EditorShellWiringTest {
	/** With a document open, the shell registers every one of its tables, in the fixture's order. */
	@Test
	fun theShellRegistersItsTablesInOrder() =
		runComposeUiTest {
			val session = commandFixtureSession(EditorMode.Object)
			val registry = CommandRegistry()
			mount(this, registry, session)
			assertEquals(
				shellCommandTables(session).map { command -> command.id },
				registry.all().map { command -> command.id },
			)
		}

	/** The palette command opens the palette over the shell, with its search field. */
	@Test
	fun thePaletteCommandOpensThePalette() =
		runComposeUiTest {
			val registry = CommandRegistry()
			mount(this, registry, commandFixtureSession(EditorMode.Object))
			val searchFieldsBefore = onAllNodesWithText(SEARCH_HINT).fetchSemanticsNodes().size
			runOnIdle { registry.invoke("palette.toggle") }
			waitForIdle()
			assertEquals(searchFieldsBefore + 1, onAllNodesWithText(SEARCH_HINT).fetchSemanticsNodes().size)
		}

	/** A repack refusal report raises its alert, naming each refused tile and why. */
	@Test
	fun aRepackReportRaisesItsAlert() =
		runComposeUiTest {
			val registry = CommandRegistry()
			mount(this, registry, commandFixtureSession(EditorMode.Object))
			val report = AtlasRepackReport(listOf(AtlasRepackRefusal("Hair", AtlasRepackRefusalReason.Undecodable)))
			runOnIdle { registry.invoke("document.repackReport", report) }
			waitForIdle()
			assertEquals(1, onAllNodesWithText(REFUSAL_LINE, substring = true).fetchSemanticsNodes().size)
		}

	/**
	 * After a live switch of the UI language, Workspace > New names the workspace in the new language - the
	 * same base name the tab strip's "+" button uses.
	 */
	@Test
	fun aNewWorkspaceIsNamedInTheLanguageSwitchedTo() {
		// ProvideAppLocale applies a language through the process-wide default locale.
		val defaultLocale = Locale.getDefault()

		try {
			runComposeUiTest {
				val registry = CommandRegistry()
				val settings = bundledSettings()
				var languageTag by mutableStateOf("en")
				var currentBaseName: String? = null
				var latestLayout: InterfaceLayout? = null
				setContent {
					CompositionLocalProvider(LocalSettings provides settings) {
						EditorShell(
							commandRegistry = registry,
							languageTag = languageTag,
							onLayoutChange = { layout -> latestLayout = layout },
						)
						ProvideAppLocale(languageTag) {
							currentBaseName = stringResource(Res.string.workspace_new_name)
						}
					}
				}
				waitForIdle()
				val englishBaseName = currentBaseName

				languageTag = "ja"
				waitForIdle()
				assertNotEquals(englishBaseName, currentBaseName, "the switch has to change the base name for this case to test anything")
				runOnIdle { registry.invoke("workspace.new") }
				waitForIdle()

				assertEquals(currentBaseName, latestLayout?.activeWorkspace()?.name)
			}
		} finally {
			Locale.setDefault(defaultLocale)
		}
	}

	/**
	 * A tracked operation past its grace hands every area the wait pointer as its resting pointer, so the
	 * regions that hand back the plain pointer show it too, and the operation's end hands back the plain one.
	 */
	@Test
	fun aBusyOperationHandsTheAreasTheWaitPointer() =
		runComposeUiTest {
			val waitPointer = ShellCursorClaim.Busy.pointerIcon()

			if (waitPointer == PointerIcon.Default) {
				skipTest("the toolkit bakes no custom cursor here, so the wait pointer cannot be told from the plain one")
			}

			val settings = bundledSettings()
			val tracker = ProgressTracker()
			var restingSeen: PointerIcon? = null
			val base = defaultSpaceRegistry()
			val probeSpaces =
				SpaceKind.entries.associateWith { kind ->
					val descriptor = base.descriptor(kind)
					SpaceDescriptor(kind, descriptor.title, descriptor.icon) {
						val resting = LocalRestingPointerIcon.current
						SideEffect { restingSeen = resting }
					}
				}
			setContent {
				CompositionLocalProvider(LocalSettings provides settings, LocalProgressTracker provides tracker) {
					EditorShell(spaceOverrides = probeSpaces)
				}
			}
			waitForIdle()
			assertEquals(PointerIcon.Default, runOnIdle { restingSeen }, "with nothing running the areas rest on the plain pointer")

			val task = tracker.begin(ProgressLabels.MODEL_EXPORTING, listOf("rig.cmo3"))
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS + GRACE_MARGIN_MILLIS)
			assertEquals(waitPointer, runOnIdle { restingSeen }, "past the grace the areas rest on the wait pointer")

			task.finish()
			waitForIdle()
			assertEquals(PointerIcon.Default, runOnIdle { restingSeen }, "the task's end hands back the plain pointer")
		}

	/**
	 * Mounts the shell over the bundled default settings, with [session] open.
	 *
	 * @param ComposeUiTest   test     The running UI test.
	 * @param CommandRegistry registry The registry the shell registers into.
	 * @param EditorSession   session  The open document's session.
	 */
	private fun mount(test: ComposeUiTest, registry: CommandRegistry, session: EditorSession) {
		val settings = bundledSettings()
		test.setContent {
			CompositionLocalProvider(
				LocalSettings provides settings,
				LocalEditorSession provides session,
				LocalPuppet provides session.model.value,
			) {
				EditorShell(commandRegistry = registry)
			}
		}
		test.waitForIdle()
	}

	/**
	 * Settings over the bundled defaults and an empty in-memory config directory - a first run's.
	 *
	 * @return Settings The loaded settings.
	 */
	private fun bundledSettings(): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())

		return Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), runBlocking { defaultSettingsJson() })
	}

	private companion object {
		/** The palette's search placeholder in English; other panels' search fields share it. */
		const val SEARCH_HINT = "Search"

		/** The repack alert's line for the one refused tile, in English. */
		const val REFUSAL_LINE = "Hair - Source artwork layer could not be decoded."

		/** How far past the busy pointer's grace the clock steps: two frames, for the flip and its recomposition. */
		const val GRACE_MARGIN_MILLIS = 32L
	}
}