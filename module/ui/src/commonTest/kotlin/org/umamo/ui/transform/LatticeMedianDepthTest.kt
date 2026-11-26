package org.umamo.ui.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterSelection
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.withParameter
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.valuesFor
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_HIGH_DEPTH
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_LOW_DEPTH
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_PARAMETER
import org.umamo.ui.viewport.viewport2d.RIG_OTHER
import org.umamo.ui.viewport.viewport2d.RIG_QUAD
import org.umamo.ui.viewport.viewport2d.editOwnerGeometries
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.viewport.viewport2d.latticeRigModelWithBodyDepth
import org.umamo.ui.viewport.viewport2d.selectLatticePoints
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The area the strip registers in. */
private const val STRIP_AREA = "area-1"

/**
 * A typed Vertex / Median depth over lattice points: the median shows the covered points' mean depth, and a typed value
 * is a Grab in depth that shifts each covered point by the difference.  At rest every keyform's depth shifts alike;
 * at the pose the shifted depth is held pending.  The points themselves never move, and the strip's Move Depth row
 * reruns it.  The rig's body warp is deep ([latticeRigModelWithBodyDepth]), so the rest pose blends its two keys'
 * depths half and half.
 */
class LatticeMedianDepthTest {
	private val body = ElementOwner.Deformer(LATTICE_RIG_BODY)

	/**
	 * A session over the lattice rig with the body warp made deep, the warp selected.
	 *
	 * @return EditorSession The session, in Object mode.
	 */
	private fun deepSession(): EditorSession {
		val session = EditorSession(latticeRigModelWithBodyDepth())
		session.setSelection(Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)), SelectionTarget.Deformer(LATTICE_RIG_BODY)))

		return session
	}

	/**
	 * The deep session in Edit mode with [points] of the body's lattice selected.
	 *
	 * @param Set<Int> points The selected points.
	 * @return EditorSession The session.
	 */
	private fun editSession(points: Set<Int>): EditorSession {
		val session = deepSession()
		session.setMode(EditorMode.Edit)
		assertEquals(listOf(body), session.meshSelection.value.owners, "Tab on the warp edits its lattice")
		session.selectLatticePoints(body, points, points.first())

		return session
	}

	/**
	 * The deep session in Pose mode at the rest pose with [points] of the body's lattice selected.
	 *
	 * @param Set<Int> points The selected points.
	 * @return EditorSession The session.
	 */
	private fun poseSession(points: Set<Int>): EditorSession {
		val session = deepSession()
		session.setParameterSelection(ParameterSelection(setOf(LATTICE_RIG_PARAMETER), LATTICE_RIG_PARAMETER))
		session.togglePoseMode()
		assertEquals(EditorMode.Pose, session.mode.value)
		session.selectLatticePoints(body, points, points.first())

		return session
	}

	/**
	 * The move of the session's covered points, captured where the session stands, as the sidebar panel captures it.
	 *
	 * @param EditorSession session The session.
	 * @return CoveredMedianMove The move.
	 */
	private fun moveOf(session: EditorSession): CoveredMedianMove {
		val stage = elementEditStageOf(session.mode.value, session.pose.value, session.pendingLatticeEdits.value)
		val selection = session.meshSelection.value
		val edit = editOwnerGeometries(session.model.value, selection.owners, stage)

		return assertNotNull(coveredMedianMove(selection, edit.map { geometry -> geometry.worldGeometry }, edit.associate { geometry -> geometry.owner to geometry.topology }, stage))
	}

	/**
	 * The body warp's keyforms, in key order.
	 *
	 * @param EditorSession session The session.
	 * @return List<WarpLatticeForm> The forms.
	 */
	private fun keyForms(session: EditorSession): List<WarpLatticeForm> =
		assertNotNull((session.model.value.deformerById[LATTICE_RIG_BODY] as Deformer.Warp).geometryGrid).cells.map { cell -> cell.form }

	/**
	 * Sets one of the live strip record's float rows, as the strip does.
	 *
	 * @param EditorSession session The session.
	 * @param String key The row.
	 * @param Float value Its new value.
	 */
	private fun adjustFloat(session: EditorSession, key: String, value: Float) {
		val record = assertNotNull(session.adjustableOperation.value)
		val row = record.parameters.first { parameter -> parameter.key == key } as OperatorParameter.FloatParameter
		session.adjustLastOperation(record.parameters.withParameter(key, row.copy(value = value)), adjustedKey = key)
	}

	@Test
	fun theMedianShowsTheCoveredPointsMeanDepthAndAMeshShowsNone() {
		val lattice = moveOf(editSession(setOf(0, 1)))
		// At rest point 0 blends 0 and 10, point 1 blends 4 and 20.
		assertEquals(8.5f, assertNotNull(lattice.median.depth), "the mean of 5 and 12")

		val mesh = gizmoEditSession(editing = listOf(RIG_QUAD, RIG_OTHER))
		mesh.setMeshSelection(mesh.meshSelection.value.copy(elementsByOwner = mapOf(ElementOwner.Drawable(RIG_QUAD) to setOf(MeshElement.Vertex(0)))))
		val meshEdit = editOwnerGeometries(mesh.model.value, mesh.meshSelection.value.owners)
		val meshMedian =
			assertNotNull(coveredMedian(coveredVerticesOf(mesh.meshSelection.value, meshEdit.map { geometry -> geometry.worldGeometry }, meshEdit.associate { geometry -> geometry.owner to geometry.topology })))
		assertNull(meshMedian.depth, "a mesh's vertices carry no depth")
	}

	@Test
	fun aLiveDepthPreviewMovesTheMedianDepth() {
		val session = editSession(setOf(0, 1))
		val stage = elementEditStageOf(session.mode.value, session.pose.value, session.pendingLatticeEdits.value)
		val selection = session.meshSelection.value
		val edit = editOwnerGeometries(session.model.value, selection.owners, stage)
		val covered = coveredVerticesOf(selection, edit.map { geometry -> geometry.worldGeometry }, edit.associate { geometry -> geometry.owner to geometry.topology })

		val live = coveredMedian(covered, latticeDepthByDeformer = mapOf(LATTICE_RIG_BODY to LatticeDepth.PerPoint(floatArrayOf(1f, 3f, 0f, 0f))))

		assertEquals(2f, assertNotNull(live).depth, "the preview's depth stands in for the captured one")
	}

	@Test
	fun aTypedDepthAtRestShiftsEveryKeyformAlikeAndTheStripRerunsIt() {
		val session = editSession(setOf(0, 1))
		val formsBefore = keyForms(session)

		session.moveCoveredMedianAdjustable(moveOf(session), MedianCoordinate.Depth, 18.5f, STRIP_AREA)

		assertEquals("change.lattice.move", session.historyView.value.steps.last().labelKey)
		val forms = keyForms(session)
		assertContentEquals(floatArrayOf(10f, 14f, 8f, 12f), forms[0].depth.valuesFor(4), "the covered points sit 10 deeper at the first key")
		assertContentEquals(floatArrayOf(20f, 30f, 30f, 40f), forms[1].depth.valuesFor(4), "and at the second")

		for (keyIndex in forms.indices) {
			assertContentEquals(formsBefore[keyIndex].controlPoints, forms[keyIndex].controlPoints, "key $keyIndex's points never move")
		}

		val rows = assertNotNull(session.adjustableOperation.value).parameters
		assertEquals(listOf(TransformParameterKeys.MOVE_X, TransformParameterKeys.MOVE_Z, TransformParameterKeys.MOVE_DEPTH), rows.map { row -> row.key }.take(3))
		assertEquals(10f, (rows.first { row -> row.key == TransformParameterKeys.MOVE_DEPTH } as OperatorParameter.FloatParameter).value)

		adjustFloat(session, TransformParameterKeys.MOVE_DEPTH, 4f)
		assertContentEquals(floatArrayOf(4f, 8f, 8f, 12f), keyForms(session)[0].depth.valuesFor(4), "the strip reruns the move by its row")

		session.undo()
		assertContentEquals(LATTICE_RIG_LOW_DEPTH, keyForms(session)[0].depth.valuesFor(4), "undo restores the depth")
		assertContentEquals(LATTICE_RIG_HIGH_DEPTH, keyForms(session)[1].depth.valuesFor(4))
	}

	@Test
	fun aTypedDepthAtThePoseHoldsItPending() {
		val session = poseSession(setOf(3))
		val modelBefore = session.model.value
		val posedPoints = assertNotNull(warpLatticeAt(modelBefore, session.pose.value, LATTICE_RIG_BODY)).gridLocal

		session.moveCoveredMedianAdjustable(moveOf(session), MedianCoordinate.Depth, 0f, STRIP_AREA)

		assertEquals("change.pose.move", session.historyView.value.steps.last().labelKey)
		assertSame(modelBefore, session.model.value, "a posed depth is held pending")
		val pending = assertNotNull(session.pendingLatticeEdits.value[LATTICE_RIG_BODY])
		assertContentEquals(floatArrayOf(5f, 12f, 19f, 0f), pending.depth.valuesFor(4), "point 3 moved from 26 to 0, the rest kept the pose's depth")
		assertContentEquals(posedPoints, pending.controlPoints, "and no point moved")
		assertTrue(TransformParameterKeys.MOVE_DEPTH in assertNotNull(session.adjustableOperation.value).parameters.map { row -> row.key }, "a posed lattice Grab has the row too")
	}

	@Test
	fun aMeshGrabHasNoMoveDepthRow() {
		val session = gizmoEditSession(editing = listOf(RIG_QUAD))
		session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = mapOf(ElementOwner.Drawable(RIG_QUAD) to setOf(MeshElement.Vertex(0)))))
		val edit = editOwnerGeometries(session.model.value, session.meshSelection.value.owners)
		val move =
			assertNotNull(
				coveredMedianMove(session.meshSelection.value, edit.map { geometry -> geometry.worldGeometry }, edit.associate { geometry -> geometry.owner to geometry.topology }),
			)

		assertNull(move.parametersFor(MedianCoordinate.Depth, 10f), "a depth typed over a mesh moves nothing")
		session.moveCoveredMedianAdjustable(move, MedianCoordinate.X, 7f, STRIP_AREA)

		val rows = assertNotNull(session.adjustableOperation.value).parameters.map { row -> row.key }
		assertTrue(TransformParameterKeys.MOVE_DEPTH !in rows, "a mesh carries no depth, so its Grab has no depth row: $rows")
	}
}