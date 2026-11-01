package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.duplicateSelectedObjects
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.beginObjectOperator
import org.umamo.edit.withParameter
import org.umamo.runtime.model.DeformerId
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins Object-mode G on warps through the 2D overlay itself: a warp drag shows through the renderer's lattice preview
 * and pushes no model, so its art follows without a re-pose of the whole rig; the confirm lands one "Move Objects"
 * step with the warp inside it following; a selected child moves once, with its warp; art and a warp move in one
 * step; and the strip, cancel, and Shift+D behave as they do for drawables.
 *
 * The lattice rig's body warp spans world x 0..40 and z -40..0 (point 0 at the world origin); the head warp sits
 * inside it, and a quad of art sits at the root over x 0..20.
 */
@OptIn(ExperimentalTestApi::class)
class ObjectWarpGestureTest {
	/** Where the pointer rests before a gesture latches: the gesture measures from here. */
	private val gestureStart = LATTICE_RIG_GESTURE_START

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = LATTICE_RIG_TEN_UNITS_RIGHT

	/**
	 * Drags the latched Grab ten world units right in the left area, from [gestureStart].
	 *
	 * @param EditorSession session The session.
	 */
	private fun ComposeUiTest.grabTenUnitsRight(session: EditorSession) {
		moveIn(LEFT_AREA, listOf(gestureStart))
		session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
		waitForIdle()
		moveIn(LEFT_AREA, listOf(tenUnitsRight))
	}

	@Test
	fun aWarpDragPreviewsItsLatticeAndLandsWithItsArt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			val session = fixture.session
			val headBefore = latticeRigRestWorld(session, LATTICE_RIG_HEAD)
			val stepsBefore = session.historyView.value.steps.size
			val pushesBefore = fixture.service.pushedModels.size

			grabTenUnitsRight(session)

			assertEquals(pushesBefore, fixture.service.pushedModels.size, "a warp drag pushes no model")
			val preview = assertNotNull(fixture.service.pushedLatticePreviews.lastOrNull()?.get(LATTICE_RIG_BODY), "it previews the lattice")
			assertEquals(10f, preview.controlPoints[0], 1e-3f, "ten units right, in the warp's parent space")

			clickIn(LEFT_AREA, tenUnitsRight)

			assertNull(session.activeObjectOperator.value, "the confirm cleared the operator")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals("change.object.move", session.historyView.value.steps.last().labelKey)
			assertEquals(10f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[0], 1e-3f, "the warp landed where it was dragged")
			val headAfter = latticeRigRestWorld(session, LATTICE_RIG_HEAD)
			assertEquals(headBefore[0] + 10f, headAfter[0], 1e-3f, "and the warp inside it followed")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer")
			assertTrue(fixture.service.pushedLatticePreviews.last().isEmpty(), "and then cleared the preview")

			// The strip's Move X reruns the move from the step's base and amends it.
			val record = assertNotNull(session.adjustableOperation.value)
			assertEquals(LEFT_AREA, record.areaId)
			val row = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_X } as OperatorParameter.FloatParameter
			session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.MOVE_X, row.copy(value = 4f)), adjustedKey = TransformParameterKeys.MOVE_X)
			assertEquals(4f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[0], 1e-3f, "the rerun lands four units right instead")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "amending the same step")

			session.undo()
			assertEquals(0f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[0], 1e-3f, "undo puts the warp back")
		}

	@Test
	fun aSelectedChildMovesOnceWithItsWarp() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD), SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			val session = fixture.session
			val headBefore = latticeRigRestWorld(session, LATTICE_RIG_HEAD)

			grabTenUnitsRight(session)
			clickIn(LEFT_AREA, tenUnitsRight)

			assertEquals(headBefore[0] + 10f, latticeRigRestWorld(session, LATTICE_RIG_HEAD)[0], 1e-3f, "ten units with its parent, not twenty")
		}

	@Test
	fun artAndAWarpMoveTogether() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Drawable(LATTICE_RIG_ART), SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			val session = fixture.session
			val pushesBefore = fixture.service.pushedModels.size

			grabTenUnitsRight(session)

			assertTrue(fixture.service.pushedModels.size > pushesBefore, "the art previews through its positions")
			assertNotNull(fixture.service.pushedLatticePreviews.lastOrNull()?.get(LATTICE_RIG_BODY), "and the warp through its lattice")

			clickIn(LEFT_AREA, tenUnitsRight)

			assertEquals(10f, session.model.value.drawables.single { drawable -> drawable.id == LATTICE_RIG_ART }.mesh!!.positions[0], 1e-3f, "the art moved")
			assertEquals(10f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[0], 1e-3f, "and so did the warp, in the same step")
			assertEquals("change.object.move", session.historyView.value.steps.last().labelKey)
		}

	@Test
	fun aRightClickCancelsAndClearsThePreview() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			val session = fixture.session
			val before = session.model.value
			val stepsBefore = session.historyView.value.steps.size

			grabTenUnitsRight(session)
			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)

			assertNull(session.activeObjectOperator.value)
			assertSame(before, session.model.value, "nothing committed")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertTrue(fixture.service.pushedLatticePreviews.last().isEmpty(), "the preview is gone")
		}

	@Test
	fun aDuplicatedWarpIsGrabbedAwayFromItsOriginal() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			val session = fixture.session

			session.duplicateSelectedObjects()
			grabTenUnitsRight(session)
			clickIn(LEFT_AREA, tenUnitsRight)

			assertEquals(10f, latticeRigRestWorld(session, DeformerId("body.001"))[0], 1e-3f, "the copy moved")
			assertEquals(0f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[0], 1e-3f, "the original stayed")
		}
}