package org.umamo.ui.viewport

import org.umamo.edit.EditorSession
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalRows
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.withParameter
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.captureDrawableWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the geometry-bound half of the transform gestures' strip registration: the slide factor
 * clamps to its edge, and a registered Grab re-lands the base mesh from the record's base when its
 * Move row is edited (the rows themselves are pinned in :edit's TransformAdjustTest).
 */
class TransformAdjustRegistrationTest {
	private val drawableId = DrawableId("a")

	@Test
	fun theSlideFactorClampsToTheEdge() {
		val world = floatArrayOf(0f, 0f, 10f, 0f)
		val slid = slideVertexByFactor(world, vertexIndex = 0, neighborIndex = 1, factor = 1.5f)
		assertEquals(10f, slid[0], "past the far endpoint clamps onto it")
		assertEquals(10f, slid[2], "the neighbor itself never moves")
		val stayed = slideVertexByFactor(world, vertexIndex = 0, neighborIndex = 1, factor = -1f)
		assertEquals(0f, stayed[0], "before the start clamps to the vertex")
	}

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "a",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), FloatArray(6), intArrayOf(0, 1, 2)),
						geometryGrid = null,
					),
				),
			rootChildren = listOf(OrgChild.Drawable(drawableId)),
			rootPartId = null,
		)

	/**
	 * A registered Grab of the whole triangle, committed the way the Edit overlay commits it: the
	 * frozen capture, the moved world shape inverted onto the base, one step, then the registration.
	 */
	@Test
	fun aRegisteredGrabReLandsFromTheBaseWhenItsMoveRowIsEdited() {
		val session = EditorSession(model())
		val geometry = assertNotNull(captureDrawableWorld(session.model.value, emptyMap(), drawableId), "a direct drawable captures")
		val transform =
			assertNotNull(
				buildModalTransformCapture(
					sources = listOf(ModalCaptureSource(drawableId, geometry.world.copyOf(), intArrayOf(0, 1, 2), setOf(0, 1, 2))),
					pivotMode = TransformPivotMode.MedianPoint,
					individualOriginScope = IndividualOriginScope.ConnectivityIsland,
					operatorKind = MeshOperatorKind.Grab,
					activeAnchor = null,
					cursorAnchor = null,
				),
			)
		val landed = TransformGestureParameters(10f, 0f, 1f, 1f, 0f)
		val entry = transform.entries.single()
		val world = applyOperator(MeshOperatorKind.Grab, entry.positions, entry.groups, landed, emptyMap())
		session.commitMeshPositions(MeshChange.TransformVertices(mapOf(drawableId to listOf(0, 1, 2)), MeshOperatorKind.Grab), mapOf(drawableId to geometry.worldToBase(world, entry.movedIndices)))
		assertEquals(10f, session.model.value.drawables.single().mesh!!.positions[0], "the grab landed")
		var writtenBack: ProportionalEditState? = null
		var writeBacks = 0
		val record =
			assertNotNull(
				registerMeshTransformAdjustment(session, "area-1", transform, mapOf(drawableId to geometry), landed, ProportionalRows.of(null, 200f)) { state ->
					writtenBack = state
					writeBacks++
				},
			)
		val stepsBefore = session.historyView.value.steps.size

		session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.MOVE_X, OperatorParameter.FloatParameter(TransformParameterKeys.MOVE_X, TransformParameterKeys.MOVE_X, 25f, -100f, 100f)))

		val positions = session.model.value.drawables.single().mesh!!.positions
		assertEquals(25f, positions[0], "the rerun moved from the BASE by the new delta, not from the landed shape")
		assertEquals(35f, positions[2])
		assertEquals(10f, positions[5], "y is untouched")
		assertEquals(stepsBefore, session.historyView.value.steps.size, "the step was amended in place")
		assertEquals(1, writeBacks, "the proportional rows were written back once")
		assertNull(writtenBack, "and they said off")
		session.undo()
		assertEquals(0f, session.model.value.drawables.single().mesh!!.positions[0], "one undo returns to the base")
	}
}