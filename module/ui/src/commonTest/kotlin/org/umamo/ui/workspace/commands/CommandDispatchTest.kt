package org.umamo.ui.workspace.commands

import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.Command
import org.umamo.ui.viewport.CameraController
import org.umamo.ui.workspace.AreaCameraHub
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.KeyformSheetSurface
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the command handlers themselves against a real session, one test per routing rule whose failure
 * mode is silent.
 *
 * Every case here is a rule that looks wrong on first reading and is not: a hovered UV editor that
 * refuses a transform outright rather than falling back, an Object-mode duplicate that ignores the
 * hovered surface its Edit-mode twin respects, a Box Select that must not reach for a keyform sheet the
 * pointer is nowhere near.  Each would break invisibly - a key that quietly does nothing, or acts in an
 * area the user is not looking at - so each is pinned rather than left to review.
 */
class CommandDispatchTest {
	private val viewportArea = "viewport-1"
	private val uvArea = "uv-1"
	private val sheetArea = "sheet-1"

	private fun meshDrawable(id: String): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)

	/**
	 * A session in [mode] with one mesh drawable selected.
	 *
	 * @param EditorMode mode The mode to leave the session in.
	 * @return EditorSession The session.
	 */
	private fun session(mode: EditorMode): EditorSession {
		val session =
			EditorSession(
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = listOf(meshDrawable("a")),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			)
		val drawable = SelectionTarget.Drawable(DrawableId("a"))
		session.setSelection(Selection(setOf(drawable), drawable))
		session.setMode(mode)
		return session
	}

	private fun routing(hovered: HoveredSurface?): CommandRouting = CommandRouting({ hovered }, { viewportArea })

	private fun List<Command>.run(id: String) {
		first { command -> command.id == id }.handler.run(null)
	}

	/**
	 * A sheet surface recording whether its marquee was armed, so a Box Select that reaches the wrong
	 * surface is visible rather than merely absent from the session.
	 */
	private class RecordingSheet {
		var armed = false

		val surface: KeyformSheetSurface =
			KeyformSheetSurface(
				selectedTracks = { emptyList() },
				hasSelection = { false },
				frameAll = {},
				armBoxSelect = { armed = true },
				boxSelectArmed = { armed },
				disarmBoxSelect = { armed = false },
				nudgeSelection = {},
			)
	}

	/**
	 * G over a UV editor in Object mode latches NOTHING.  The UV operator refuses outside Edit mode and
	 * the routing deliberately offers no viewport fallback, so the press is a no-op - grabbing the
	 * pointer's last viewport instead would move geometry the user is not looking at.
	 */
	@Test
	fun grabOverAUvEditorInObjectModeLatchesNothing() {
		val session = session(EditorMode.Object)
		val commands = transformCommands(session, routing(HoveredSurface(uvArea, SpaceKind.UvEditor)), SessionAvailability(session))

		commands.run("mesh.grab")

		assertNull(session.activeUvOperator.value, "the UV operator refuses outside Edit mode")
		assertNull(session.activeMeshOperator.value)
		assertNull(session.activeObjectOperator.value, "and it must not fall back to the pointer's viewport")
	}

	/** The same press in Edit mode does reach the UV operator, so the test above is not vacuous. */
	@Test
	fun grabOverAUvEditorInEditModeLatchesTheUvOperator() {
		val session = session(EditorMode.Edit)
		session.selectAllMeshElements()
		val commands = transformCommands(session, routing(HoveredSurface(uvArea, SpaceKind.UvEditor)), SessionAvailability(session))

		commands.run("mesh.grab")

		assertEquals(uvArea, session.activeUvOperator.value?.areaId)
	}

	/**
	 * B over a viewport arms the VIEWPORT's box select even with a keyform sheet open elsewhere.
	 *
	 * The sheet registry resolves a lone open sheet when handed no area, which is right for the sheet's
	 * own commands and catastrophic here: asking it unconditionally would hijack B in every viewport for
	 * any layout that happens to include a keyform sheet.
	 */
	@Test
	fun boxSelectOverAViewportIgnoresALoneOpenSheet() {
		val session = session(EditorMode.Edit)
		val sheets = KeyformSheetViews()
		val sheet = RecordingSheet()
		sheets.register(sheetArea, sheet.surface)
		val commands =
			selectCommands(session, routing(HoveredSurface(viewportArea, SpaceKind.Viewport2D)), sheets, SessionAvailability(session))

		commands.run("mesh.boxSelect")

		assertFalse(sheet.armed, "the sheet is open but the pointer is not over it")
		assertEquals(viewportArea, (session.activeSelectTool.value as? ActiveSelectTool.BoxArmed)?.areaId)
	}

	/** B over the sheet itself arms the sheet's marquee and leaves the session's tool alone. */
	@Test
	fun boxSelectOverASheetArmsThatSheet() {
		val session = session(EditorMode.Edit)
		val sheets = KeyformSheetViews()
		val sheet = RecordingSheet()
		sheets.register(sheetArea, sheet.surface)
		val commands =
			selectCommands(session, routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)), sheets, SessionAvailability(session))

		commands.run("mesh.boxSelect")

		assertTrue(sheet.armed)
		assertNull(session.activeSelectTool.value, "the viewport's box select stays unarmed")
	}

	/**
	 * C over a keyform sheet arms in the pointer's viewport.  Circle Select has no sheet branch at all,
	 * unlike Box Select - a sheet has no circle brush, so falling through beats doing nothing.
	 */
	@Test
	fun circleSelectOverASheetArmsTheViewport() {
		val session = session(EditorMode.Edit)
		val commands =
			selectCommands(session, routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)), KeyformSheetViews(), SessionAvailability(session))

		commands.run("mesh.circleSelect")

		assertEquals(viewportArea, (session.activeSelectTool.value as? ActiveSelectTool.Circle)?.areaId)
	}

	/**
	 * Shift+D in Object mode auto-grabs in the pointer's viewport even over a hovered UV editor - the
	 * Edit-mode branch skips its auto-grab there, the Object branch never checked.  Preserved verbatim:
	 * reconciling the two changes behavior and belongs in its own commit.
	 */
	@Test
	fun objectDuplicateAutoGrabsInTheViewportEvenOverAUvEditor() {
		val session = session(EditorMode.Object)
		val commands = topologyCommands(session, routing(HoveredSurface(uvArea, SpaceKind.UvEditor)), SessionAvailability(session))

		commands.run("mesh.duplicate")

		assertEquals(MeshOperatorKind.Grab, session.activeObjectOperator.value?.kind)
		assertEquals(viewportArea, session.activeObjectOperator.value?.areaId)
	}

	/**
	 * The camera lookup is kind-agnostic: it resolves whatever controller the hovered area registered,
	 * with no per-space branch.  Pinned against a rewrite that "tidies" it into a Viewport2D/UvEditor
	 * check, which would silently lock out any camera-bearing space added later.
	 */
	@Test
	fun viewCommandsResolveWhicheverAreaRegisteredACamera() {
		var fitCount = 0
		val camera =
			object : CameraController {
				override fun fit() {
					fitCount++
				}

				override fun actualSize() = Unit

				override fun zoomIn(coarse: Boolean) = Unit

				override fun zoomOut(coarse: Boolean) = Unit

				override fun armZoomRegion() = Unit

				override fun frameSelected() = Unit
			}
		val cameras = AreaCameraHub()
		cameras.register(sheetArea, camera)
		val commands = viewCommands(cameras, routing(HoveredSurface(sheetArea, SpaceKind.KeyformSheet)), viewportPresent = true)

		commands.run("view.fit")

		assertEquals(1, fitCount, "the hovered area's controller resolved on its area id alone")
	}
}
