package org.umamo.ui.workspace.commands

import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.workspace.AreaCameraHub
import org.umamo.ui.workspace.AreaDragController
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.OperationStripState
import org.umamo.ui.workspace.RowDragCancelController
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SplitterDragCancelController
import org.umamo.ui.workspace.WorkspaceLayoutController
import org.umamo.ui.workspace.defaultLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins every command table's contents AND the order the shell registers them in.
 *
 * Two things ride on this.  A command silently dropped by a refactor is otherwise invisible until a user
 * reaches for it - a menu row, a palette entry, or a chord that does nothing at all - because nothing else
 * in the tree enumerates the tables.  And [CommandRegistry] is insertion-ordered, which the command palette
 * shows verbatim for a blank query, so the order here IS the order the user reads; a regrouping that
 * shuffles it should be a deliberate edit to these lists, not a surprise.
 *
 * Every builder takes its collaborators as plain values and resolves the rest at dispatch time, so a
 * table builds with no document, no renderer, and no composition - which is what makes this test
 * possible at all.
 */
class CommandTableOrderTest {
	private fun overlays(): ShellOverlayState = ShellOverlayState()

	private fun workspaces(): WorkspaceLayoutController = WorkspaceLayoutController(defaultLayout()) {}

	private fun routing(): CommandRouting = CommandRouting { null }

	/** The shell-chrome table: overlay toggles, the drag cancels, workspace tab navigation. */
	@Test
	fun chromeTableIsComplete() {
		val commands =
			chromeCommands(
				overlays(),
				AreaDragController(),
				SplitterDragCancelController(),
				RowDragCancelController(),
				workspaces(),
			)
		assertEquals(
			listOf(
				"palette.toggle",
				"area.dragCancel",
				"row.dragCancel",
				"edit.preferences",
				"help.about",
				"help.credits",
				"workspace.prev",
				"workspace.next",
			),
			commands.map { command -> command.id },
		)
	}

	/** The workspace-management and document-report tables, in the order the shell concatenates them. */
	@Test
	fun workspaceAndDocumentTablesAreComplete() {
		val commands = workspaceCommands(workspaces(), overlays(), "Workspace") + documentCommands(overlays())
		assertEquals(
			listOf(
				"workspace.new",
				"workspace.reset",
				"workspace.applyLayout",
				"workspace.appendWorkspace",
				"document.openFailed",
				"document.confirmReplace",
				"document.exportReport",
				"document.repackReport",
				"document.exportOptionsMoc3",
				"document.confirm",
			),
			commands.map { command -> command.id },
		)
	}

	/**
	 * The viewport navigation table and the context-aware frame command.  Built with no render service,
	 * which only flips the availability gate - the tables themselves are the same either way.
	 */
	@Test
	fun viewTablesAreComplete() {
		val commands = viewCommands(AreaCameraHub(), routing(), viewportPresent = false)
		assertEquals(
			listOf(
				"view.fit",
				"view.zoomActualSize",
				"view.zoomIn",
				"view.zoomOut",
				"view.zoomInCoarse",
				"view.zoomOutCoarse",
				"view.zoomRegion",
				"view.frameSelected",
			),
			commands.map { command -> command.id },
		)
		assertEquals(listOf("frame.all"), frameCommands(CommandRegistry(), routing()).map { command -> command.id })
	}

	/** The selection-clear and editor-mode table. */
	@Test
	fun modeTableIsComplete() {
		assertEquals(
			listOf("select.clear", "mode.toggleEdit", "mode.object", "mode.edit"),
			modeCommands(null, null).map { command -> command.id },
		)
	}

	/**
	 * The document-scoped groups in the order the shell concatenates them - by far the largest set, and
	 * the one a split most easily loses a command from.  The six proportional-falloff commands are
	 * appended by a loop over the enum, so they trail their group's hand-written pair.
	 */
	@Test
	fun sessionTablesAreComplete() {
		val availability = SessionAvailability(null)
		val routing = routing()
		val sheets = KeyformSheetViews()
		val commands =
			historyCommands(null, availability, OperationStripState()) +
				objectCommands(null, null, availability) +
				transformCommands(null, routing, availability) +
				selectCommands(null, routing, sheets, availability) +
				snapCommands(null, routing, availability) +
				uvCommands(null, routing, availability) +
				topologyCommands(null, routing, availability) +
				proportionalCommands(null, availability) +
				displayCommands(null, availability) +
				atlasCommands(availability, routing, null)
		assertEquals(
			listOf(
				"edit.undo",
				"edit.redo",
				"edit.adjustLastOperation",
				"object.toggleVisibility",
				"outliner.selectHierarchy",
				"mesh.grab",
				"mesh.scale",
				"mesh.rotate",
				"mesh.modalCancel",
				"mesh.selectMode.vertex",
				"mesh.selectMode.edge",
				"mesh.selectMode.face",
				"select.all",
				"select.invert",
				"mesh.boxSelect",
				"mesh.circleSelect",
				"mesh.circleSelect.grow",
				"mesh.circleSelect.shrink",
				"mesh.selectLinkedAtCursor",
				"mesh.selectLinked",
				"edit.switchObjectUnderCursor",
				"transform.pivotPie",
				"transform.pivot.median",
				"transform.pivot.individual",
				"transform.pivot.active",
				"transform.pivot.cursor",
				"snap.pie",
				"snap.cursorToWorldOrigin",
				"snap.cursorToGrid",
				"snap.cursorToSelected",
				"snap.cursorToActive",
				"snap.selectionToGrid",
				"snap.selectionToCursor",
				"snap.selectionToCursorOffset",
				"snap.selectionToActive",
				"uv.mirrorU",
				"uv.mirrorV",
				"uv.snap.selectionToPixels",
				"uv.snap.selectionToCursor",
				"uv.snap.selectionToCursorOffset",
				"uv.snap.selectionToGrid",
				"uv.snap.cursorToPixels",
				"uv.snap.cursorToSelected",
				"uv.snap.cursorToGrid",
				"uv.page.next",
				"uv.page.previous",
				"uv.page.followSelection",
				"uv.pinPlacement",
				"uv.unpinPlacement",
				"mesh.duplicate",
				"mesh.merge",
				"mesh.merge.atCenter",
				"mesh.merge.atFirst",
				"mesh.merge.atLast",
				"mesh.rip",
				"mesh.connect",
				"mesh.vertexSlide",
				"mesh.proportional.toggle",
				"mesh.proportional.connectedToggle",
				"mesh.proportional.falloff.smooth",
				"mesh.proportional.falloff.sphere",
				"mesh.proportional.falloff.root",
				"mesh.proportional.falloff.sharp",
				"mesh.proportional.falloff.linear",
				"mesh.proportional.falloff.constant",
				"document.toggleSourceArtworkDisplay",
				"document.repackAtlas",
			),
			commands.map { command -> command.id },
		)
	}

	/**
	 * The app-registered file and log tables, in the order EditorApp concatenates them.  Their actions are
	 * plain lambdas, which is what lets a commonMain test build them at all - the document layer they
	 * actually call into is jvmAndroidMain.
	 */
	@Test
	fun fileAndLogTablesAreComplete() {
		val commands = fileCommands({}, {}, {}) + logCommands {}
		assertEquals(listOf("file.importArtwork", "file.importCmo3", "file.importMoc3", "logs.export"), commands.map { command -> command.id })
		assertEquals(
			listOf("file.exportCmo3", "file.exportMoc3"),
			fileExportCommands({ true }, {}, {}).map { command -> command.id },
		)
	}

	/**
	 * Export hides itself when no puppet document is open, and asks LIVE rather than at registration - the
	 * palette must not offer an export that would no-op.
	 */
	@Test
	fun exportAvailabilityFollowsTheOpenDocument() {
		var exportable = false
		val export = fileExportCommands({ exportable }, {}, {}).first()
		assertFalse(export.availability.isAvailable(), "nothing to export with no document open")
		exportable = true
		assertTrue(export.availability.isAvailable(), "the tier is queried per call, not sampled at registration")
	}

	/** The keyform-authoring table. */
	@Test
	fun keyformTableIsComplete() {
		val commands = keyformCommands(null, { null }, routing(), KeyformSheetViews(), SessionAvailability(null))
		assertEquals(
			listOf(
				"keyform.insert",
				"keyform.delete",
				"keyform.deleteSelectedKeys",
				"keyform.nudgeKeyLeft",
				"keyform.nudgeKeyRight",
				"keyform.frameAll",
			),
			commands.map { command -> command.id },
		)
	}
}