package org.umamo.ui.workspace

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.FilePicker
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.action.CommandRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Pins what a workspace export writes when the layout on screen is newer than the one in settings.
 *
 * Layout edits reach settings through a debounce, so for a moment after a split the saved layout is the
 * one from before it.  An export pressed in that moment must still write the layout the rigger is looking
 * at - and must get there through the debounce's own pacer, so its rules hold: nothing is written while a
 * splitter drag is held.
 */
class WorkspaceLayoutFilesTest {
	/** Minimal defaults: the empty interface.layout baseline that triggers seeding. */
	private val defaultJson = """{"interface":{"layout":{"workspaces":[]}},"localization":{"locale":"en"}}"""

	/**
	 * The shell's wiring in miniature: settings, the live layout, the pacer that writes it, and the file
	 * commands' collaborator committing through that pacer.
	 */
	private class Fixture(defaultJson: String) {
		val settings: Settings

		/** The layout on screen, which the debounce has not necessarily written yet. */
		var latestLayout: InterfaceLayout

		val pacer: LayoutSavePacer

		val files: WorkspaceLayoutFiles

		init {
			val fileSystem = FakeFileSystem()
			val configDirectory = "/config".toPath()
			fileSystem.createDirectories(configDirectory)
			settings = Settings.load(OkioAppStorage(fileSystem, configDirectory, "/data".toPath()), defaultJson)
			latestLayout = loadLayout(settings)
			pacer = LayoutSavePacer(latestLayout) { layout -> saveLayout(settings, layout) }
			files = WorkspaceLayoutFiles(settings, CancelledFilePicker, CoroutineScope(Job()), CommandRegistry()) { pacer.saveDebounced(latestLayout) }
		}

		/**
		 * Splits the active workspace's first area on screen, without the debounce having fired.
		 *
		 * @return InterfaceLayout The layout now on screen.
		 */
		fun splitOnScreen(): InterfaceLayout {
			val root = assertNotNull(latestLayout.activeWorkspace()).root
			val split = latestLayout.withActiveRoot(reduce(root, AreaCommand.SplitArea(firstLeaf(root).id, SplitOrientation.Vertical)))
			assertNotEquals(latestLayout, split, "the split must change the tree")
			latestLayout = split
			return split
		}

		/**
		 * The first leaf of a tree, reached down the leading children.
		 *
		 * @param AreaNode node The tree to descend.
		 * @return LeafArea The first leaf.
		 */
		private fun firstLeaf(node: AreaNode): LeafArea =
			when (node) {
				is LeafArea -> node
				is SplitNode -> firstLeaf(node.first)
			}
	}

	@Test
	fun anExportWritesTheLayoutOnScreenNotTheOneTheDebounceHasYetToSave() {
		val fixture = Fixture(defaultJson)
		val seeded = fixture.latestLayout
		val split = fixture.splitOnScreen()
		assertEquals(seeded, decodeLayoutText(assertNotNull(exportLayoutText(fixture.settings))), "settings still hold the layout from before the split")

		val exported = decodeLayoutText(assertNotNull(fixture.files.allWorkspacesText()))

		assertEquals(split, exported, "the file is the layout on screen")
		assertEquals(split, loadLayout(fixture.settings), "because the export committed it, which the debounce was about to do anyway")
	}

	@Test
	fun exportingOneWorkspaceWritesItAsItIsOnScreenToo() {
		val fixture = Fixture(defaultJson)
		val split = fixture.splitOnScreen()

		assertEquals(split.activeWorkspace(), fixture.files.activeWorkspaceToExport())
	}

	@Test
	fun anExportNeverWritesALayoutHeldByASplitterDrag() {
		val fixture = Fixture(defaultJson)
		val seeded = fixture.latestLayout
		fixture.pacer.setDragActive(true, seeded)
		fixture.splitOnScreen()

		val exported = decodeLayoutText(assertNotNull(fixture.files.allWorkspacesText()))

		assertEquals(seeded, exported, "a drag in flight holds every write, an export's included; its release commits the layout")
	}
}

/** A picker whose every dialog is cancelled: these cases stop before a file. */
private object CancelledFilePicker : FilePicker {
	override suspend fun openFile(extensions: List<String>): PlatformFile? = null

	override suspend fun saveFile(suggestedName: String, extension: String): PlatformFile? = null
}