package org.umamo.ui.workspace

import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies layout persistence over an in-memory filesystem: first run seeds + writes back the
 * defaults, and a later edit survives a reload (ids and all).
 */
class LayoutPersistenceTest {
	private val configDir: Path = "/config".toPath()

	/** Minimal defaults: the empty interface.layout baseline that should trigger seeding. */
	private val defaultJson = """{"interface":{"layout":{"workspaces":[]}},"localization":{"locale":"en"}}"""

	/** A fresh in-memory storage rooted at /config + /data. */
	private fun storage(): OkioAppStorage {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories(configDir)
		return OkioAppStorage(fileSystem, configDir, "/data".toPath())
	}

	/**
	 * loadLayout over an empty layout seeds the default workspaces and writes them back, so a fresh
	 * Settings over the same disk reads the identical seeded layout (proving the write-back).
	 */
	@Test
	fun seedsDefaultsAndWritesBack() {
		val storage = storage()
		val seeded = loadLayout(Settings.load(storage, defaultJson))
		assertEquals(listOf("modelling", "texture", "physics"), seeded.workspaces.map { workspace -> workspace.id })

		val reloaded = loadLayout(Settings.load(storage, defaultJson))
		assertEquals(seeded, reloaded, "the seeded layout must persist (including minted area ids)")
	}

	/**
	 * An edited layout (a split + active-workspace change) saved through saveLayout reloads intact.
	 */
	@Test
	fun editedLayoutSurvivesReload() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		val seeded = loadLayout(settings)

		val modellingRoot = seeded.workspaces.first { workspace -> workspace.id == "modelling" }.root
		val splitRoot = reduce(modellingRoot, AreaCommand.SplitArea((modellingRoot as LeafArea).id, SplitOrientation.Vertical))
		val edited =
			seeded
				.copy(activeWorkspaceId = "texture")
				.copy(workspaces = seeded.workspaces.map { workspace -> if (workspace.id == "modelling") workspace.copy(root = splitRoot) else workspace })
		saveLayout(settings, edited)

		val reloaded = loadLayout(Settings.load(storage, defaultJson))
		assertEquals(edited, reloaded)
	}

	/**
	 * Export then import round-trips the whole layout: exportLayoutText pretty-prints the persisted layout
	 * and decodeLayoutText reads it back to an equal InterfaceLayout (minted ids and all).
	 */
	@Test
	fun layoutExportRoundTrips() {
		val settings = Settings.load(storage(), defaultJson)
		val seeded = loadLayout(settings)
		val text = assertNotNull(exportLayoutText(settings), "a seeded layout must export")
		assertEquals(seeded, decodeLayoutText(text))
	}

	/**
	 * Export then import round-trips a single workspace through exportWorkspaceText / decodeWorkspaceText.
	 */
	@Test
	fun workspaceExportRoundTrips() {
		val seeded = loadLayout(Settings.load(storage(), defaultJson))
		val workspace = seeded.workspaces.first()
		assertEquals(workspace, decodeWorkspaceText(exportWorkspaceText(workspace)))
	}

	/**
	 * The two import shapes are unambiguous: a whole-layout file does not decode as a single workspace and a
	 * single-workspace file does not decode as a layout, so Import can try layout first, then workspace.
	 */
	@Test
	fun importShapesAreDistinct() {
		val settings = Settings.load(storage(), defaultJson)
		val seeded = loadLayout(settings)
		val layoutText = assertNotNull(exportLayoutText(settings))
		val workspaceText = exportWorkspaceText(seeded.workspaces.first())

		assertNull(decodeWorkspaceText(layoutText), "a full layout must not decode as a single workspace")
		assertNull(decodeLayoutText(workspaceText), "a single workspace must not decode as a full layout")
	}

	/**
	 * Malformed text never throws and yields null from both decoders (Import then reports an invalid file).
	 */
	@Test
	fun malformedTextDecodesToNull() {
		assertNull(decodeLayoutText("not json at all"))
		assertNull(decodeWorkspaceText("not json at all"))
	}

	/**
	 * A space key this build no longer knows (here "deformerlog", a removed space) degrades to a neutral
	 * Outliner leaf instead of failing the layout - the serializer's forward-compatible fallback, which is
	 * also what keeps saved layouts from older builds opening after a space is deleted.
	 */
	@Test
	fun unknownSpaceKeyDegradesToOutliner() {
		val workspaceText = """{"id":"legacy","root":{"type":"leaf","id":"a","space":"deformerlog"}}"""
		val workspace = assertNotNull(decodeWorkspaceText(workspaceText), "an unknown space key must not fail the decode")
		assertEquals(LeafArea("a", SpaceKind.Outliner), workspace.root)
	}
}
