package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.beginUvOperator
import org.umamo.edit.withParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The area the strip registers in. */
private const val STRIP_AREA = "uv-area"

/**
 * A typed UV Vertex / Median value as an Edit-mode move: the covered texture coordinates move rigidly so their
 * median lands on it, read in y-down texels, as one UV move step the operation strip adjusts the way it adjusts the
 * UV editor's own Grab, without switching proportional editing off.  With the quad's vertices 0 and 1 covered, at
 * display (100, 100) and (120, 100) on the 256-texel page, the median reads (110, 156) in y-down texels.
 */
class UvMedianMoveTest {
	/** The surface's proportional radius, unseeded until the first registration needs it. */
	private val radiusState: MutableState<Float?> = mutableStateOf(null)

	/**
	 * An Edit session over the quad with vertices 0 and 1 selected.
	 *
	 * @return EditorSession The session.
	 */
	private fun session(): EditorSession = uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))

	/**
	 * The move of the session's covered coordinates on the page, captured from its committed model.
	 *
	 * @param EditorSession session The session.
	 * @return UvMedianMove The move.
	 */
	private fun moveOf(session: EditorSession): UvMedianMove {
		val geometries = uvRigGeometries(session.model.value, uvRigPageFrame(), shown = listOf(UV_RIG_QUAD))

		return assertNotNull(uvMedianMove(uvCoveredVertices(session.meshSelection.value, geometries), uvRigPageFrame(), radiusState))
	}

	/**
	 * The quad's stored coordinates for display corners [display].
	 *
	 * @param FloatArray display The corners, interleaved (x, y), display y up.
	 * @return List<Float> The stored coordinates.
	 */
	private fun uvsAt(vararg display: Float): List<Float> = displayToUv(display, UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE).toList()

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

	/** A typed X moves the covered coordinates together by the median's change, and nothing else. */
	@Test
	fun aTypedXMovesTheCoveredCoordinatesRigidly() {
		val session = session()

		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.X, 130f, STRIP_AREA)

		assertEquals(uvsAt(120f, 100f, 140f, 100f, 120f, 120f, 100f, 120f), uvRigUvsOf(session, UV_RIG_QUAD))
	}

	/** A typed Y reads down from the top row: a smaller value moves the coordinates up the page. */
	@Test
	fun aTypedYReadsDownFromTheTop() {
		val session = session()

		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.Y, 136f, STRIP_AREA)

		assertEquals(uvsAt(100f, 120f, 120f, 120f, 120f, 120f, 100f, 120f), uvRigUvsOf(session, UV_RIG_QUAD))
	}

	/** The move is one undo step filed as a UV move, and typing the value already there records nothing. */
	@Test
	fun theMoveIsOneStepAndAnEmptyMoveIsNone() {
		val session = session()
		val before = session.model.value
		val stepsBefore = session.historyView.value.steps.size

		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.X, 110f, STRIP_AREA)
		assertSame(before, session.model.value, "the median was already there")
		assertNull(session.adjustableOperation.value, "and nothing registered")

		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.X, 130f, STRIP_AREA)
		val steps = session.historyView.value.steps
		assertEquals(stepsBefore + 1, steps.size)
		assertEquals("change.uv.move", steps.last().labelKey)
	}

	/** A preview leaves the session alone, and the release that follows lands the whole move on the strip. */
	@Test
	fun aPreviewCommitsNothingAndTheReleaseLandsTheWholeMove() {
		val session = session()
		val before = session.model.value
		val move = moveOf(session)

		assertNotNull(session.previewUvMedianMove(move, TexelAxis.X, 125f))
		assertSame(before, session.model.value)

		session.moveUvMedianAdjustable(move, TexelAxis.X, 130f, STRIP_AREA)
		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals(STRIP_AREA, record.areaId)
		assertEquals(20f, (record.parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_X } as OperatorParameter.FloatParameter).value)
	}

	/** A strip adjustment replays the move from the base. */
	@Test
	fun theStripReplaysTheMoveFromTheBase() {
		val session = session()
		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.X, 130f, STRIP_AREA)
		val stepsBefore = session.historyView.value.steps.size

		adjustFloat(session, TransformParameterKeys.MOVE_X, 5f)

		assertEquals(uvsAt(105f, 100f, 125f, 100f, 120f, 120f, 100f, 120f), uvRigUvsOf(session, UV_RIG_QUAD))
		assertEquals(stepsBefore, session.historyView.value.steps.size, "the adjustment amends the step")
	}

	/** Adjusting a Move row leaves the rigger's proportional editing on: the move registered its rows off. */
	@Test
	fun adjustingAMoveRowLeavesProportionalEditingOn() {
		val session = session()
		session.toggleProportionalEdit()
		val proportional = assertNotNull(session.proportionalEdit.value)

		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.X, 130f, STRIP_AREA)
		adjustFloat(session, TransformParameterKeys.MOVE_X, 5f)

		assertEquals(proportional, session.proportionalEdit.value)
	}

	/** Turning proportional editing on from the strip keeps the remembered curve, Connected Only, and world radius. */
	@Test
	fun turningProportionalOnFromTheStripKeepsTheRememberedSettings() {
		val session = session()
		session.setProportionalFalloff(ProportionalFalloff.Sharp)
		session.toggleProportionalConnected()
		session.setProportionalRadius(345f)
		assertNull(session.proportionalEdit.value, "precondition: proportional editing is off")

		session.moveUvMedianAdjustable(moveOf(session), TexelAxis.X, 130f, STRIP_AREA)
		assertEquals(uvProportionalRadiusSeed(UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE), radiusState.value, "the surface's radius is seeded for the rows")
		val record = assertNotNull(session.adjustableOperation.value)
		val toggle = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL } as OperatorParameter.BooleanParameter
		session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.PROPORTIONAL, toggle.copy(value = true)), adjustedKey = TransformParameterKeys.PROPORTIONAL)

		val state = assertNotNull(session.proportionalEdit.value)
		assertEquals(ProportionalFalloff.Sharp, state.falloff)
		assertTrue(state.connectedOnly)
		assertEquals(345f, state.radiusWorld, "the 2D radius is not reset to the default")
	}

	/** The UV write-back keeps the session's remembered world radius even while proportional editing is off. */
	@Test
	fun theWriteBackKeepsTheRememberedWorldRadius() {
		val session = session()
		session.setProportionalRadius(345f)
		assertNull(session.proportionalEdit.value, "precondition: proportional editing is off")

		session.writeBackUvProportional(radiusState, ProportionalEditState(ProportionalFalloff.Linear, 12f), 12f)

		assertEquals(12f, radiusState.value, "the texel radius lands on the surface")
		assertEquals(345f, assertNotNull(session.proportionalEdit.value).radiusWorld)
	}

	/** While a UV transform owns the coordinates, the panel neither previews nor commits. */
	@Test
	fun nothingLandsWhileAUvTransformRuns() {
		val session = session()
		val move = moveOf(session)
		session.beginUvOperator(MeshOperatorKind.Grab, STRIP_AREA)
		assertNotNull(session.activeUvOperator.value, "precondition: the operator latched")
		val before = session.model.value

		assertNull(session.previewUvMedianMove(move, TexelAxis.X, 130f))
		session.moveUvMedianAdjustable(move, TexelAxis.X, 130f, STRIP_AREA)

		assertSame(before, session.model.value)
	}
}