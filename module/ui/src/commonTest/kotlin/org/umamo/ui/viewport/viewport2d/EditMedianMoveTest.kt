package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.edit.withParameter
import org.umamo.ui.transform.WorldAxis
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.coveredVerticesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The area the strip registers in. */
private const val STRIP_AREA = "area-1"

/**
 * A typed Vertex / Median value as an Edit-mode move: the covered vertices of every mesh in the edit move rigidly
 * so their median lands on it, as one move step the operation strip adjusts the way it adjusts a viewport Grab,
 * without the side effect of switching proportional editing off.  The rig's quad has vertices 0 and 1 at world
 * (0, 0) and (20, 0), the triangle's vertex 0 is at (40, 0), and with those three covered the median is (20, 0).
 */
class EditMedianMoveTest {
	/**
	 * An Edit session over the quad and the triangle, with quad vertices 0 and 1 and triangle vertex 0 selected.
	 *
	 * @return EditorSession The session.
	 */
	private fun session(): EditorSession {
		val session = gizmoEditSession(editing = listOf(RIG_QUAD, RIG_OTHER))
		session.setMeshSelection(
			session.meshSelection.value.copy(
				elementsByDrawable =
					mapOf(
						RIG_QUAD to setOf(MeshElement.Vertex(0), MeshElement.Vertex(1)),
						RIG_OTHER to setOf(MeshElement.Vertex(0)),
					),
			),
		)

		return session
	}

	/**
	 * The move of the session's covered vertices, captured from its committed model as the sidebar panel captures it.
	 *
	 * @param EditorSession session The session.
	 * @return CoveredMedianMove The move.
	 */
	private fun moveOf(session: EditorSession): CoveredMedianMove {
		val model = session.model.value
		val selection = session.meshSelection.value
		val covered =
			coveredVerticesOf(
				selection,
				captureDrawableWorlds(model, emptyMap(), selection.drawableIds),
				model.drawables.associate { drawable -> drawable.id to drawable.mesh!!.indices },
			)

		return assertNotNull(coveredMedianMove(covered))
	}

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

	/** A typed X moves the covered vertices together by the median's change, and nothing else. */
	@Test
	fun aTypedValueMovesTheCoveredVerticesRigidly() {
		val session = session()
		val move = moveOf(session)
		assertEquals(20f, move.median.worldX)
		assertEquals(3, move.median.vertexCount)

		session.moveCoveredMedianAdjustable(move, WorldAxis.X, 50f, STRIP_AREA)

		assertEquals(listOf(30f, 0f, 50f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(listOf(70f, 0f, 60f, 0f, 40f, 20f), rigPositionsOf(session, RIG_OTHER))
	}

	/** The move is one undo step, filed as a move, and undo takes it back. */
	@Test
	fun theMoveIsOneMoveStep() {
		val session = session()
		val stepsBefore = session.historyView.value.steps.size

		session.moveCoveredMedianAdjustable(moveOf(session), WorldAxis.Z, 10f, STRIP_AREA)

		val steps = session.historyView.value.steps
		assertEquals(stepsBefore + 1, steps.size)
		assertEquals("change.mesh.move", steps.last().labelKey)
		session.undo()
		assertEquals(listOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
	}

	/** Typing the value the median already has records nothing. */
	@Test
	fun theValueAlreadyThereRecordsNothing() {
		val session = session()
		val before = session.model.value
		val stepsBefore = session.historyView.value.steps.size

		session.moveCoveredMedianAdjustable(moveOf(session), WorldAxis.X, 20f, STRIP_AREA)

		assertSame(before, session.model.value)
		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertNull(session.adjustableOperation.value, "and registers no strip")
	}

	/** The pivot mode a viewport transform would use does not bend the move: Individual Origins still moves rigidly. */
	@Test
	fun individualOriginsStillMovesRigidly() {
		val session = session()
		session.setPivotMode(TransformPivotMode.IndividualOrigins)

		session.moveCoveredMedianAdjustable(moveOf(session), WorldAxis.Z, 10f, STRIP_AREA)

		// World z up is local y down, so the covered vertices' y drops by 10.
		assertEquals(listOf(0f, -10f, 20f, -10f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(listOf(40f, -10f, 60f, 0f, 40f, 20f), rigPositionsOf(session, RIG_OTHER))
	}

	/** The strip shows the move as a Grab, in the panel's area, and an adjustment replays it from the base. */
	@Test
	fun theStripReplaysTheMoveFromTheBase() {
		val session = session()

		session.moveCoveredMedianAdjustable(moveOf(session), WorldAxis.X, 50f, STRIP_AREA)

		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals(STRIP_AREA, record.areaId)
		assertEquals(30f, (record.parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_X } as OperatorParameter.FloatParameter).value)
		assertEquals(0f, (record.parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_Z } as OperatorParameter.FloatParameter).value)
		val stepsBefore = session.historyView.value.steps.size

		adjustFloat(session, TransformParameterKeys.MOVE_X, 5f)

		assertEquals(listOf(5f, 0f, 25f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(listOf(45f, 0f, 60f, 0f, 40f, 20f), rigPositionsOf(session, RIG_OTHER))
		assertEquals(stepsBefore, session.historyView.value.steps.size, "the adjustment amends the step rather than adding one")
	}

	/**
	 * With proportional editing on, the typed move takes no falloff, and adjusting a Move row on the strip leaves
	 * proportional editing on: the strip's proportional rows started off for this move, and only setting one of
	 * them hands proportional editing back to the session.
	 */
	@Test
	fun adjustingAMoveRowLeavesProportionalEditingOn() {
		val session = session()
		session.toggleProportionalEdit()
		val proportional = assertNotNull(session.proportionalEdit.value)

		session.moveCoveredMedianAdjustable(moveOf(session), WorldAxis.X, 50f, STRIP_AREA)
		assertEquals(listOf(30f, 0f, 50f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "the uncovered vertices took no falloff")
		adjustFloat(session, TransformParameterKeys.MOVE_X, 5f)

		assertEquals(proportional, session.proportionalEdit.value)
	}

	/** Turning proportional editing on from the strip brings back the rigger's remembered curve and Connected Only. */
	@Test
	fun turningProportionalOnFromTheStripKeepsTheRememberedSettings() {
		val session = session()
		session.setProportionalFalloff(ProportionalFalloff.Sharp)
		session.toggleProportionalConnected()
		assertNull(session.proportionalEdit.value, "precondition: proportional editing is off")

		session.moveCoveredMedianAdjustable(moveOf(session), WorldAxis.X, 50f, STRIP_AREA)
		val record = assertNotNull(session.adjustableOperation.value)
		val toggle = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL } as OperatorParameter.BooleanParameter
		session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.PROPORTIONAL, toggle.copy(value = true)), adjustedKey = TransformParameterKeys.PROPORTIONAL)

		val state = assertNotNull(session.proportionalEdit.value)
		assertEquals(ProportionalFalloff.Sharp, state.falloff)
		assertTrue(state.connectedOnly)
	}

	/** A preview shows the moved model without committing anything. */
	@Test
	fun aPreviewCommitsNothing() {
		val session = session()
		val before = session.model.value
		val stepsBefore = session.historyView.value.steps.size

		val preview = assertNotNull(session.previewCoveredMedianMove(moveOf(session), WorldAxis.X, 50f))

		assertEquals(30f, preview.drawables.first { drawable -> drawable.id == RIG_QUAD }.mesh!!.positions[0])
		assertSame(before, session.model.value)
		assertEquals(stepsBefore, session.historyView.value.steps.size)
	}

	/** While a modal transform owns the meshes, the panel neither previews nor commits. */
	@Test
	fun nothingLandsWhileAModalTransformRuns() {
		val session = session()
		val move = moveOf(session)
		session.beginMeshOperator(MeshOperatorKind.Grab, STRIP_AREA)
		assertNotNull(session.activeMeshOperator.value, "precondition: the operator latched")
		val before = session.model.value

		assertNull(session.previewCoveredMedianMove(move, WorldAxis.X, 50f))
		session.moveCoveredMedianAdjustable(move, WorldAxis.X, 50f, STRIP_AREA)

		assertSame(before, session.model.value)
	}
}