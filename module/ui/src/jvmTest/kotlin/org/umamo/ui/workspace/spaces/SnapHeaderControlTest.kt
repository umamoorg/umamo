package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.Cursor2d
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.viewport.viewport2d.gizmoObjectSession
import org.umamo.ui.workspace.AreaOverlayHub
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.setAreaHeader
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.snapCommands
import org.umamo.ui.workspace.spaces.parameters.clickDescribed
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The snap menu on the two work-surface headers, driven through the REAL header strips: the 2D viewport's
 * lists the world snaps and a pick runs its command, the UV editor's lists the texture-space snaps and a
 * pick dispatches the UV command, and the UV editor offers its menu in Object mode too, where its snaps move
 * placed art.
 */
@OptIn(ExperimentalTestApi::class)
class SnapHeaderControlTest {
	/** The 2D viewport's menu lists the eight world snaps, and Cursor to World Origin puts the cursor there. */
	@Test
	fun theViewportMenuListsTheWorldSnapsAndAPickRunsIt() =
		runComposeUiTest {
			val session = gizmoEditSession()
			val commands = CommandRegistry()
			snapCommands(session, CommandRouting { null }, SessionAvailability(session), AreaOverlayHub())
				.filter { command -> command.id in VIEWPORT_SNAP_IDS }
				.forEach { command -> commands.register(command) }
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session, commands = commands)

			clickDescribed(SNAP)

			for (row in VIEWPORT_ROWS) {
				assertTrue(popupShows(row), "the $row row")
			}
			clickMenuEntry(CURSOR_TO_WORLD_ORIGIN)
			val model = session.model.value
			assertEquals(Cursor2d(model.worldOriginX, model.worldOriginZ), session.cursor2d.value, "the cursor sits on the world origin")
			assertFalse(popupShows(CURSOR_TO_WORLD_ORIGIN), "and the pick closed the menu")
		}

	/** The UV editor's menu lists the seven texture-space snaps, and a pick dispatches its UV command. */
	@Test
	fun theUvMenuListsTheTextureSnapsAndAPickDispatchesIt() =
		runComposeUiTest {
			val session = gizmoEditSession()
			val invoked = mutableListOf<String>()
			val commands = CommandRegistry()
			// Recording stand-ins: the real handlers post a request only a mounted UV overlay collects.
			for (commandId in UV_SNAP_IDS) {
				commands.register(Command(commandId, title = null) { invoked += commandId })
			}
			setAreaHeader(kind = SpaceKind.UvEditor, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session, commands = commands)

			clickDescribed(SNAP)

			for (row in UV_ROWS) {
				assertTrue(popupShows(row), "the $row row")
			}
			clickMenuEntry(CURSOR_TO_PIXELS)
			assertEquals(listOf("uv.snap.cursorToPixels"), invoked)
		}

	/** In Object mode the UV editor's header carries its snap menu too, whose snaps move placed art there. */
	@Test
	fun theUvHeaderOffersItsSnapMenuInObjectMode() =
		runComposeUiTest {
			val session = gizmoObjectSession()
			setAreaHeader(kind = SpaceKind.UvEditor, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)

			clickDescribed(SNAP)

			for (row in UV_ROWS) {
				assertTrue(popupShows(row), "the $row row")
			}
		}

	private companion object {
		/** The snap chip's accessible name. */
		const val SNAP = "Snap"

		/** The 2D viewport's Cursor to World Origin row. */
		const val CURSOR_TO_WORLD_ORIGIN = "Cursor to World Origin"

		/** The UV editor's Cursor to Pixels row. */
		const val CURSOR_TO_PIXELS = "Cursor to Pixels"

		/** The 2D viewport's rows. */
		val VIEWPORT_ROWS =
			listOf(
				CURSOR_TO_WORLD_ORIGIN,
				"Cursor to Grid",
				"Cursor to Selected",
				"Cursor to Active",
				"Selection to Grid",
				"Selection to Cursor",
				"Selection to Cursor (Keep Offset)",
				"Selection to Active",
			)

		/** The UV editor's rows. */
		val UV_ROWS =
			listOf(
				CURSOR_TO_PIXELS,
				"Cursor to Selected",
				"Cursor to Grid",
				"Selection to Pixels",
				"Selection to Cursor",
				"Selection to Cursor (Keep Offset)",
				"Selection to Grid",
			)

		/** The world snap commands the 2D viewport's rows dispatch. */
		val VIEWPORT_SNAP_IDS =
			setOf(
				"snap.cursorToWorldOrigin",
				"snap.cursorToGrid",
				"snap.cursorToSelected",
				"snap.cursorToActive",
				"snap.selectionToGrid",
				"snap.selectionToCursor",
				"snap.selectionToCursorOffset",
				"snap.selectionToActive",
			)

		/** The texture-space snap commands the UV editor's rows dispatch. */
		val UV_SNAP_IDS =
			listOf(
				"uv.snap.cursorToPixels",
				"uv.snap.cursorToSelected",
				"uv.snap.cursorToGrid",
				"uv.snap.selectionToPixels",
				"uv.snap.selectionToCursor",
				"uv.snap.selectionToCursorOffset",
				"uv.snap.selectionToGrid",
			)
	}
}