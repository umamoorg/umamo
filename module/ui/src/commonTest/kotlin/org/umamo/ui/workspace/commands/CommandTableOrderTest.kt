package org.umamo.ui.workspace.commands

import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.workspace.AreaCameraHub
import org.umamo.ui.workspace.AreaDragController
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.RowDragCancelController
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.WorkspaceLayoutController
import org.umamo.ui.workspace.defaultLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every command table's contents AND the order the shell registers them in.
 *
 * Two things ride on this.  A command silently dropped by a refactor is otherwise invisible until a user
 * presses the key that no longer does anything - nothing else in the tree enumerates the tables.  And
 * [CommandRegistry] is insertion-ordered, which the command palette shows verbatim for a blank query, so
 * the order here IS the order the user reads; a regrouping that shuffles it should be a deliberate edit
 * to these lists, not a surprise.
 *
 * Every builder takes its collaborators as plain values and resolves the rest at dispatch time, so a
 * table builds with no document, no renderer, and no composition - which is what makes this test
 * possible at all.
 */
class CommandTableOrderTest {
	private fun overlays(): ShellOverlayState = ShellOverlayState()

	private fun workspaces(): WorkspaceLayoutController = WorkspaceLayoutController(defaultLayout()) {}

	private fun routing(): CommandRouting = CommandRouting({ null }, { null })

	/** The shell-chrome table: overlay toggles, the drag cancels, workspace tab navigation. */
	@Test
	fun chromeTableIsComplete() {
		val commands = chromeCommands(overlays(), AreaDragController(), RowDragCancelController(), workspaces())
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
			historyCommands(null, availability) +
				objectCommands(null, null, availability) +
				transformCommands(null, routing, availability) +
				selectCommands(null, routing, sheets, availability) +
				snapCommands(null, routing, availability) +
				uvCommands(null, routing, availability) +
				topologyCommands(null, routing, availability) +
				proportionalCommands(null, availability)
		assertEquals(
			listOf(
				"edit.undo",
				"edit.redo",
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
			),
			commands.map { command -> command.id },
		)
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
