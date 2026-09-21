package org.umamo.ui.workspace.commands

import org.umamo.ui.action.CommandSpaces
import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins which commands are scoped to a space, and to which.
 *
 * A scope is read off what a handler's routing does, which no test can derive - so the table is pinned
 * whole instead.  A command that gains or loses a scope must change this map, which is the moment to
 * check the handler really is a no-op everywhere else: a scope narrower than its handler hides a
 * command that would have run, and the palette is the only place that shows.
 */
class CommandSpacesTableTest {
	/** The scoped commands, each with the spaces its handler acts in; every other command is Everywhere. */
	@Test
	fun theScopedCommandsAreExactlyThese() {
		val viewport = setOf(SpaceKind.Viewport2D)
		val uvEditor = setOf(SpaceKind.UvEditor)
		val workSurfaces = setOf(SpaceKind.Viewport2D, SpaceKind.UvEditor)
		val workSurfacesAndSheet = setOf(SpaceKind.Viewport2D, SpaceKind.UvEditor, SpaceKind.KeyformSheet)
		val keyableSurfaces = setOf(SpaceKind.KeyformSheet, SpaceKind.Properties)
		val expected =
			mapOf(
				"view.fit" to workSurfaces,
				"view.zoomActualSize" to workSurfaces,
				"view.zoomIn" to workSurfaces,
				"view.zoomOut" to workSurfaces,
				"view.zoomInCoarse" to workSurfaces,
				"view.zoomOutCoarse" to workSurfaces,
				"view.zoomRegion" to workSurfaces,
				"view.frameSelected" to workSurfaces,
				"frame.all" to workSurfacesAndSheet,
				"mesh.grab" to workSurfaces,
				"mesh.scale" to workSurfaces,
				"mesh.rotate" to workSurfaces,
				"mesh.boxSelect" to workSurfacesAndSheet,
				"mesh.circleSelect" to workSurfaces,
				"mesh.selectLinkedAtCursor" to workSurfaces,
				"mesh.selectLinked" to workSurfaces,
				"edit.switchObjectUnderCursor" to viewport,
				"snap.cursorToSelected" to viewport,
				"snap.cursorToActive" to viewport,
				"snap.selectionToGrid" to viewport,
				"snap.selectionToCursor" to viewport,
				"snap.selectionToCursorOffset" to viewport,
				"snap.selectionToActive" to viewport,
				"uv.mirrorU" to uvEditor,
				"uv.mirrorV" to uvEditor,
				"uv.snap.selectionToPixels" to uvEditor,
				"uv.snap.selectionToCursor" to uvEditor,
				"uv.snap.selectionToCursorOffset" to uvEditor,
				"uv.snap.selectionToGrid" to uvEditor,
				"uv.snap.cursorToPixels" to uvEditor,
				"uv.snap.cursorToSelected" to uvEditor,
				"uv.snap.cursorToGrid" to uvEditor,
				"uv.page.next" to uvEditor,
				"uv.page.previous" to uvEditor,
				"uv.page.followSelection" to uvEditor,
				"uv.pinPlacement" to uvEditor,
				"uv.unpinPlacement" to uvEditor,
				"mesh.rip" to viewport,
				"mesh.vertexSlide" to viewport,
				"keyform.insert" to keyableSurfaces,
				"keyform.delete" to keyableSurfaces,
			)
		val actual =
			everyCommandTable()
				.mapNotNull { command -> (command.spaces as? CommandSpaces.Only)?.let { scoped -> command.id to scoped.kinds } }
				.toMap()
		assertEquals(expected, actual)
	}

	/**
	 * Every texture-coordinate command is scoped.  The one leak this dimension exists to close is a uv.*
	 * command listed over a 2D viewport, so a new one that forgets its scope fails here by name rather
	 * than waiting to be noticed in the palette.
	 */
	@Test
	fun everyUvCommandIsScoped() {
		val uvCommands = everyCommandTable().filter { command -> command.id.startsWith("uv.") }
		assertTrue(uvCommands.isNotEmpty(), "the table must actually hold uv commands for this to mean anything")
		for (command in uvCommands) {
			assertEquals(CommandSpaces.UvEditor, command.spaces, "${command.id} must be scoped to the UV editor")
		}
	}

	/**
	 * The keyform sheet's own commands stay Everywhere on purpose: their sheet lookup falls back to the
	 * lone open sheet, so they run from the palette over a viewport, and scoping them to the sheet would
	 * hide commands that work.
	 */
	@Test
	fun theSheetCommandsWithAFallbackStayEverywhere() {
		val byId = everyCommandTable().associateBy { command -> command.id }
		for (id in listOf("keyform.deleteSelectedKeys", "keyform.nudgeKeyLeft", "keyform.nudgeKeyRight", "keyform.frameAll")) {
			assertEquals(CommandSpaces.Everywhere, byId.getValue(id).spaces, "$id reaches the lone open sheet from anywhere")
		}
	}
}