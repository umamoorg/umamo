package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.paletteCommands
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_select_all
import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the command palette lists over each kind of surface, against the real command tables.
 *
 * The case that matters is the one the space dimension exists for: Mirror UVs is an Edit-mode command,
 * so availability alone lists it while a mesh is being edited in the 2D viewport, where it does nothing.
 */
class PaletteCommandsTest {
	/**
	 * A session in [mode] with one mesh drawable selected - Edit mode is refused with nothing to edit.
	 *
	 * @param EditorMode mode The mode to leave the session in.
	 * @return EditorSession The session, verified to be in [mode].
	 */
	private fun session(mode: EditorMode): EditorSession {
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
		// Every case below reads the mode through a command's availability, so a fixture that quietly
		// stayed in Object mode would pass the "is hidden" cases while testing nothing.
		assertEquals(mode, session.mode.value, "the fixture must really be in the mode the case names")
		return session
	}

	/**
	 * The transform and texture-coordinate tables over [session] - one work-surface group and the UV-only group.
	 *
	 * @param EditorSession session The session the tables close over.
	 * @return List<Command> The two tables, concatenated.
	 */
	private fun tables(session: EditorSession): List<Command> {
		val routing = CommandRouting { null }
		val availability = SessionAvailability(session)
		return transformCommands(session, routing, availability) + uvCommands(session, routing, availability)
	}

	private fun List<Command>.ids(): List<String> = map { command -> command.id }

	/** Editing a mesh in the 2D viewport, the palette offers the transforms and no texture-coordinate command. */
	@Test
	fun overAViewportInEditModeNoUvCommandIsListed() {
		val listed = paletteCommands(tables(session(EditorMode.Edit)), SpaceKind.Viewport2D).ids()

		assertTrue("mesh.grab" in listed, "the transforms belong to the viewport")
		assertTrue(listed.none { id -> id.startsWith("uv.") }, "no uv command belongs there, but got $listed")
	}

	/** Over the UV editor the same session lists them, so the case above is the scope at work and not the mode. */
	@Test
	fun overAUvEditorInEditModeTheUvCommandsAreListed() {
		val listed = paletteCommands(tables(session(EditorMode.Edit)), SpaceKind.UvEditor).ids()

		assertTrue("uv.mirrorU" in listed)
		assertTrue("uv.mirrorV" in listed)
		assertTrue("mesh.grab" in listed, "the transforms belong to the UV editor too")
	}

	/** The space does not override the mode: over the UV editor in Object mode, Mirror UVs is still hidden. */
	@Test
	fun availabilityStillHidesACommandInsideItsSpace() {
		val listed = paletteCommands(tables(session(EditorMode.Object)), SpaceKind.UvEditor).ids()

		assertFalse("uv.mirrorU" in listed, "Mirror UVs is an Edit-mode command")
		assertTrue("uv.pinPlacement" in listed, "while the Object-mode pin is listed")
	}

	/** Over a panel neither group is listed: nothing in them acts there. */
	@Test
	fun overAPanelNoWorkSurfaceCommandIsListed() {
		assertEquals(emptyList(), paletteCommands(tables(session(EditorMode.Edit)), SpaceKind.Outliner).ids())
	}

	/** Before the pointer has touched any surface, only commands that belong everywhere are listed. */
	@Test
	fun beforeAnySurfaceIsTouchedOnlyUnscopedCommandsAreListed() {
		val everywhere = Command("test.everywhere", title = Res.string.cmd_select_all, handler = {})
		val listed = paletteCommands(tables(session(EditorMode.Edit)) + everywhere, null).ids()

		assertEquals(listOf("test.everywhere"), listed)
	}

	/** An untitled command is never a palette entry, and an unavailable one is hidden - the two rules the filter kept. */
	@Test
	fun untitledAndUnavailableCommandsStayHidden() {
		val commands =
			listOf(
				Command("test.untitled", title = null, handler = {}),
				Command("test.unavailable", title = Res.string.cmd_select_all, availability = CommandAvailability { false }, handler = {}),
				Command("test.listed", title = Res.string.cmd_select_all, handler = {}),
			)

		assertEquals(listOf("test.listed"), paletteCommands(commands, SpaceKind.Viewport2D).ids())
	}
}