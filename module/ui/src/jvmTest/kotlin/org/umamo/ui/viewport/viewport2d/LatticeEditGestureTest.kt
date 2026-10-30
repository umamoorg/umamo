package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.OperatorParameter
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SnapKind
import org.umamo.edit.setMeshSelectMode
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.edit.withParameter
import org.umamo.runtime.model.ElementOwner
import org.umamo.ui.assertArrayNear
import org.umamo.ui.transform.WorldAxis
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.dragIn
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins Edit mode on a warp's lattice through the 2D overlay itself: points, grid lines, and whole cells select with
 * the mesh kit's gestures; a drag previews the cage alone - no model reaches the renderer, so the art holds still -
 * and lands as one reshape step that keeps the warp's children in place; and every other write (the strip's rerun,
 * a slide along a grid line, Shift+S, a typed median) lands the same way.
 *
 * The lattice rig's body warp spans world x 0..40 and z -40..0: points 0 (0, 0), 1 (40, 0), 2 (0, -40), and
 * 3 (40, -40), one cell between them; the head warp sits inside it.
 */
@OptIn(ExperimentalTestApi::class)
class LatticeEditGestureTest {
	private val body: ElementOwner = ElementOwner.Deformer(LATTICE_RIG_BODY)

	/** Where the pointer rests before a gesture latches: the gesture measures from here. */
	private val gestureStart = LATTICE_RIG_GESTURE_START

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = LATTICE_RIG_TEN_UNITS_RIGHT

	/**
	 * The lattice rig in Edit mode on the body warp, with [points] selected and [active] the active one.
	 *
	 * @param Set<Int> points The selected points.
	 * @param Int? active The active point, or null.
	 * @return EditorSession The session.
	 */
	private fun latticeEditSession(points: Set<Int> = emptySet(), active: Int? = null): EditorSession {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.setMode(EditorMode.Edit)
		assertEquals(listOf(body), session.meshSelection.value.owners, "Tab on the warp edits its lattice")

		session.selectLatticePoints(body, points, active)

		return session
	}

	@Test
	fun pointsBoxesAndCellsSelectWithTheMeshKit() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeEditSession())
			val session = fixture.session

			clickIn(LEFT_AREA, rigScreenOf(40f, 0f))
			assertEquals(setOf<MeshElement>(MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(body), "a click picks the point under it")

			dragIn(LEFT_AREA, rigScreenOf(-5f, 5f), listOf(rigScreenOf(45f, -5f)))
			assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(body), "a box picks the top row")

			session.setMeshSelectMode(MeshSelectMode.Face)
			waitForIdle()
			clickIn(LEFT_AREA, rigScreenOf(21f, -21f))
			assertEquals(setOf<MeshElement>(MeshElement.Face(0)), session.meshSelection.value.elementsOf(body), "a click in the cell picks the whole cell")
		}

	@Test
	fun aDragMovesTheCageAloneAndLandsAsOneReshape() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeEditSession(points = setOf(3), active = 3))
			val session = fixture.session
			val headBefore = latticeRigRestWorld(session, LATTICE_RIG_HEAD)
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size
			val pushesBefore = fixture.service.pushedModels.size

			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			assertEquals(pushesBefore, fixture.service.pushedModels.size, "a lattice drag pushes no model, so the art holds still")
			val live = assertNotNull(fixture.livePreview.latticeWorldByDeformer?.get(LATTICE_RIG_BODY), "the cage follows the drag")
			assertEquals(50f, live[6], 1e-3f)
			assertEquals(-40f, live[7], 1e-3f)

			clickIn(LEFT_AREA, tenUnitsRight)

			assertNull(session.activeMeshOperator.value, "the confirm cleared the operator")
			assertNull(fixture.livePreview.latticeWorldByDeformer, "and the live cage with it")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals("change.lattice.move", session.historyView.value.steps.last().labelKey)
			val landed = latticeRigRestWorld(session, LATTICE_RIG_BODY)
			assertEquals(50f, landed[6], 1e-3f, "the point landed where it was dragged")
			assertEquals(-40f, landed[7], 1e-3f)
			assertArrayNear(headBefore, latticeRigRestWorld(session, LATTICE_RIG_HEAD), 1e-3f, "the warp inside the cage stays where it rested")
			assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId, "the strip shows in the gesture's area")

			// The strip's Move X reruns the drag from the step's base and amends it.
			val record = assertNotNull(session.adjustableOperation.value)
			val row = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_X } as OperatorParameter.FloatParameter
			session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.MOVE_X, row.copy(value = 4f)), adjustedKey = TransformParameterKeys.MOVE_X)
			assertEquals(44f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[6], 1e-3f, "the rerun lands four units right instead")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "amending the same step")
			assertArrayNear(headBefore, latticeRigRestWorld(session, LATTICE_RIG_HEAD), 1e-3f, "and still keeps the warp inside in place")

			session.undo()
			assertEquals(40f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[6], 1e-3f, "undo puts the lattice back")
		}

	@Test
	fun aRightClickCancelsTheDragAndTheCageGoesBack() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeEditSession(points = setOf(3), active = 3))
			val session = fixture.session
			val before = session.model.value
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)

			assertNull(session.activeMeshOperator.value)
			assertNull(fixture.livePreview.latticeWorldByDeformer, "the cage is back on the rest lattice")
			assertSame(before, session.model.value, "nothing committed")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
		}

	@Test
	fun aSlideKeepsThePointOnItsGridLine() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeEditSession(points = setOf(0), active = 0))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(rigScreenOf(0f, 0f)))
			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()
			assertTrue(session.activeMeshOperator.value != null, "a lattice point has grid-line neighbors to slide toward")
			moveIn(LEFT_AREA, listOf(rigScreenOf(20f, 0f)))

			clickIn(LEFT_AREA, rigScreenOf(20f, 0f))

			val landed = latticeRigRestWorld(session, LATTICE_RIG_BODY)
			assertTrue(landed[0] > 1f && landed[0] < 40f, "the point slid along the top line: x ${landed[0]}")
			assertEquals(0f, landed[1], 1e-3f, "and stayed on it")
			assertEquals("change.lattice.slide", session.historyView.value.steps.last().labelKey)
		}

	@Test
	fun ripRefusesOnALattice() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeEditSession(points = setOf(3), active = 3))
			val session = fixture.session
			val before = session.model.value
			moveIn(LEFT_AREA, listOf(rigScreenOf(40f, -40f)))

			session.requestRip(LEFT_AREA)
			waitForIdle()

			assertSame(before, session.model.value, "the lattice keeps its grid")
			assertEquals("notice.edit.latticeTopologyFixed", session.notice.value?.messageKey)
			assertNull(session.activeMeshOperator.value, "and no grab follows")
		}

	@Test
	fun shiftSAndATypedMedianLandAsReshapes() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeEditSession(points = setOf(3), active = 3))
			val session = fixture.session
			session.setCursor2d(30f, -30f)

			session.requestSnap(SnapKind.SelectionToCursor, LEFT_AREA)
			waitForIdle()

			val snapped = latticeRigRestWorld(session, LATTICE_RIG_BODY)
			assertEquals(30f, snapped[6], 1e-3f, "the point is on the cursor")
			assertEquals(-30f, snapped[7], 1e-3f)
			assertEquals("change.lattice.move", session.historyView.value.steps.last().labelKey)

			val owners = editOwnerGeometries(session.model.value, session.meshSelection.value.owners)
			val covered = coveredVerticesOf(session.meshSelection.value, owners.map { geometry -> geometry.worldGeometry }, owners.associate { geometry -> geometry.owner to geometry.topology })
			val move = assertNotNull(coveredMedianMove(covered))
			val preview = assertNotNull(session.previewCoveredMedianMove(move, WorldAxis.X, 10f))
			assertSame(session.model.value, preview.folded, "a lattice scrub folds nothing into the model")
			assertEquals(10f, assertNotNull(preview.latticeWorld[LATTICE_RIG_BODY])[6], 1e-3f, "it moves the cage")

			session.moveCoveredMedianAdjustable(move, WorldAxis.X, 10f, LEFT_AREA)
			assertEquals(10f, latticeRigRestWorld(session, LATTICE_RIG_BODY)[6], 1e-3f, "the typed median lands the point")
			assertEquals("change.lattice.move", session.historyView.value.steps.last().labelKey)
		}
}