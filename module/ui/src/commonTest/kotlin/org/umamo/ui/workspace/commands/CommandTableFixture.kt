package org.umamo.ui.workspace.commands

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.model.EditorModeHandle
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.workspace.AreaCameraHub
import org.umamo.ui.workspace.AreaDragController
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.OperationStripState
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SplitterDragCancelController
import org.umamo.ui.workspace.WorkspaceLayoutController
import org.umamo.ui.workspace.defaultLayout
import org.umamo.ui.workspace.rowdrag.RowDragCancelController
import kotlin.test.assertEquals

/*
 * The fixtures the command-table tests share: a session parked in a given mode, and the whole command
 * table the way the shell, the settings-backed shell, and the app register it.
 *
 * Registration happens inside composables, so no test can read the list the shell really registers.
 * Holding the test-side copy once at least keeps every table-wide assertion looking at the same set.
 */

/**
 * A session in [mode] with one mesh drawable selected - Edit mode is refused with nothing to edit.
 *
 * @param EditorMode mode The mode to leave the session in.
 * @return EditorSession The session, verified to be in [mode].
 */
internal fun commandFixtureSession(mode: EditorMode): EditorSession {
	val drawable =
		Drawable(
			id = DrawableId("a"),
			name = "a",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)
	val session =
		EditorSession(
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(drawable),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)
	val target = SelectionTarget.Drawable(DrawableId("a"))
	session.setSelection(Selection(setOf(target), target))
	session.setMode(mode)
	// A case reading the mode through a command's availability would pass its "is hidden" half against a
	// fixture that quietly stayed in Object mode, while testing nothing.
	assertEquals(mode, session.mode.value, "the fixture must really be in the mode the case names")
	return session
}

/**
 * The selection and mode handles over [session], reading the session directly where the app's handle
 * reads a Compose mirror of it.
 *
 * @property EditorSession session The session both handles read and write.
 */
private class SessionHandles(private val session: EditorSession) : SelectionHandle, EditorModeHandle {
	override val selection: Selection get() = session.selection.value

	/**
	 * Routes a selection to the session.
	 *
	 * @param Selection selection The new selection.
	 */
	override fun set(selection: Selection) {
		session.setSelection(selection)
	}

	override val mode: EditorMode get() = session.mode.value

	/**
	 * Routes a mode change to the session.
	 *
	 * @param EditorMode mode The new mode.
	 */
	override fun set(mode: EditorMode) {
		session.setMode(mode)
	}
}

/**
 * Settings over an in-memory filesystem, for the tables that write a key.
 *
 * @return Settings Empty settings that persist nowhere real.
 */
internal fun inMemorySettings(): Settings {
	val configDirectory = "/config".toPath()
	val fileSystem = FakeFileSystem()
	fileSystem.createDirectories(configDirectory)
	return Settings.load(OkioAppStorage(fileSystem, configDirectory, "/data".toPath()), "{}")
}

/**
 * Every command table the shell, the settings-backed shell, and the app register, in the shell's
 * registration order.
 *
 * @param EditorSession? session The session the document-scoped tables close over, or null for the
 *   no-document tables (every availability then answers for a closed document).
 * @return List<Command> Every command.
 */
internal fun everyCommandTable(session: EditorSession? = null): List<Command> {
	val overlays = ShellOverlayState()
	val workspaces = WorkspaceLayoutController(defaultLayout()) {}
	val routing = CommandRouting { null }
	val availability = SessionAvailability(session)
	val sheets = KeyformSheetViews()
	val handles = session?.let { live -> SessionHandles(live) }
	return chromeCommands(overlays, AreaDragController(), SplitterDragCancelController(), RowDragCancelController(), workspaces) {} +
		workspaceCommands(workspaces, overlays, "Workspace") +
		documentCommands(overlays) +
		viewCommands(AreaCameraHub(), routing, viewportPresent = false) +
		frameCommands(CommandRegistry(), routing) +
		modeCommands(handles, handles) +
		historyCommands(session, availability, OperationStripState()) +
		objectCommands(session, handles, availability) +
		transformCommands(session, routing, availability) +
		selectCommands(session, routing, sheets, availability) +
		snapCommands(session, routing, availability) +
		uvCommands(session, routing, availability) +
		topologyCommands(session, routing, availability) +
		proportionalCommands(session, availability) +
		displayCommands(session, availability) +
		atlasCommands(availability, routing, null) +
		fileArtworkCommands(routing) { null } +
		fileImageExportCommands(routing) { null } +
		keyformCommands(session, { null }, routing, sheets, availability) +
		viewportChromeCommands(inMemorySettings()) +
		workspaceFileCommands({}, {}, {}) +
		logCommands {} +
		fileCommands({}, {}, {}, {}, { true }, {}, {}, {}, {}) +
		fileExportCommands({ true }, {}, {})
}