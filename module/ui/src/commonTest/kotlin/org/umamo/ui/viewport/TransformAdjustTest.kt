package org.umamo.ui.viewport

import org.umamo.edit.EditorSession
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the transform gestures' face on the operation settings strip: the rows carry the gesture's
 * numbers in each space's convention (world y up as Z, page y down with the angle's sense flipped),
 * read back to the same parameters, the proportional rows appear only when the gesture could take
 * weights and read back as a state, the slide factor clamps, and a registered Grab re-lands the base
 * mesh from the record's base when its Move row is edited.
 */
class TransformAdjustTest {
	private val drawableId = DrawableId("a")

	private fun parameters(deltaX: Float = 0f, deltaY: Float = 0f, factorX: Float = 1f, factorY: Float = 1f, rotation: Float = 0f): TransformGestureParameters =
		TransformGestureParameters(deltaX, deltaY, factorX, factorY, rotation)

	private fun List<OperatorParameter>.float(key: String): Float = assertNotNull(firstOrNull { parameter -> parameter.key == key } as? OperatorParameter.FloatParameter, key).value

	private fun assertClose(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) < 1e-4f, "$message: expected $expected, got $actual")
	}

	@Test
	fun grabRowsReadTheVerticalAxisPerSpace() {
		val gesture = parameters(deltaX = 12f, deltaY = 5f)

		val worldRows = transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.World, proportional = null)
		assertEquals(listOf(TransformParameterKeys.MOVE_X, TransformParameterKeys.MOVE_Z), worldRows.map { row -> row.key }, "world space names the vertical axis Z")
		assertEquals(5f, worldRows.float(TransformParameterKeys.MOVE_Z), "world y is shown as-is (up)")

		val pageRows = transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.UvDisplay, proportional = null)
		assertEquals(listOf(TransformParameterKeys.MOVE_X, TransformParameterKeys.MOVE_Y), pageRows.map { row -> row.key }, "the page names it Y")
		assertEquals(-5f, pageRows.float(TransformParameterKeys.MOVE_Y), "page y reads down")

		for (space in TransformRowSpace.entries) {
			val rows = transformParameters(MeshOperatorKind.Grab, gesture, space, proportional = null)
			val back = transformGestureParametersOf(MeshOperatorKind.Grab, space, rows)
			assertEquals(12f, back.deltaX, "$space round-trips x")
			assertEquals(5f, back.deltaY, "$space round-trips y")
			assertEquals(1f, back.factorX)
			assertEquals(0f, back.rotationRadians)
		}
	}

	@Test
	fun rotateRowsFlipTheirSenseWithThePage() {
		val gesture = parameters(rotation = (PI / 2).toFloat())

		val worldRows = transformParameters(MeshOperatorKind.Rotate, gesture, TransformRowSpace.World, proportional = null)
		assertClose(90f, worldRows.float(TransformParameterKeys.ANGLE), "world degrees keep the gesture's sense")
		val pageRows = transformParameters(MeshOperatorKind.Rotate, gesture, TransformRowSpace.UvDisplay, proportional = null)
		assertClose(-90f, pageRows.float(TransformParameterKeys.ANGLE), "page degrees flip with the y axis")

		for (space in TransformRowSpace.entries) {
			val rows = transformParameters(MeshOperatorKind.Rotate, gesture, space, proportional = null)
			assertClose((PI / 2).toFloat(), transformGestureParametersOf(MeshOperatorKind.Rotate, space, rows).rotationRadians, "$space round-trips the angle")
		}
	}

	@Test
	fun scaleRowsNameTheVerticalAxisPerSpaceAndRoundTrip() {
		val gesture = parameters(factorX = 2f, factorY = 0.5f)

		val worldRows = transformParameters(MeshOperatorKind.Scale, gesture, TransformRowSpace.World, proportional = null)
		assertEquals(listOf(TransformParameterKeys.SCALE_X, TransformParameterKeys.SCALE_Z), worldRows.map { row -> row.key })
		val pageRows = transformParameters(MeshOperatorKind.Scale, gesture, TransformRowSpace.UvDisplay, proportional = null)
		assertEquals(listOf(TransformParameterKeys.SCALE_X, TransformParameterKeys.SCALE_Y), pageRows.map { row -> row.key })

		for (space in TransformRowSpace.entries) {
			val back = transformGestureParametersOf(MeshOperatorKind.Scale, space, transformParameters(MeshOperatorKind.Scale, gesture, space, proportional = null))
			assertEquals(2f, back.factorX, "$space round-trips x")
			assertEquals(0.5f, back.factorY, "$space round-trips y")
		}
		assertTrue(transformParameters(MeshOperatorKind.VertexSlide, gesture, TransformRowSpace.World, proportional = null).isEmpty(), "a slide has rows of its own")
	}

	@Test
	fun proportionalRowsFollowTheGestureAndReadBackAsAState() {
		val gesture = parameters(deltaX = 1f)
		val without = transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.World, proportional = null)
		assertNull(proportionalRowsOf(without), "no proportional rows registered, none read back")

		val rows = ProportionalRows.of(ProportionalEditState(ProportionalFalloff.Sharp, 40f, connectedOnly = true), radius = 40f)
		val with = transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.World, rows)
		assertEquals(
			listOf(TransformParameterKeys.MOVE_X, TransformParameterKeys.MOVE_Z, TransformParameterKeys.PROPORTIONAL, TransformParameterKeys.FALLOFF, TransformParameterKeys.PROPORTIONAL_SIZE, TransformParameterKeys.CONNECTED_ONLY),
			with.map { row -> row.key },
		)
		val read = assertNotNull(proportionalRowsOf(with))
		assertEquals(ProportionalEditState(ProportionalFalloff.Sharp, 40f, connectedOnly = true), read.asState(), "the rows read back as the state")

		val off = ProportionalRows.of(state = null, radius = 200f)
		val offRead = assertNotNull(proportionalRowsOf(transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.World, off)))
		assertNull(offRead.asState(), "an off flag is no state")
		assertEquals(ProportionalFalloff.Smooth, offRead.falloff, "the defaults stand in while off")
		assertEquals(200f, offRead.radius, "the radius the gesture would have used is shown")

		val switchedOn = with.withParameter(TransformParameterKeys.PROPORTIONAL, OperatorParameter.BooleanParameter(TransformParameterKeys.PROPORTIONAL, TransformParameterKeys.PROPORTIONAL, false))
		assertNull(assertNotNull(proportionalRowsOf(switchedOn)).asState(), "unticking the flag turns the halo off")
	}

	@Test
	fun theSlideFactorClampsToTheEdge() {
		val rows = slideParameters(0.25f)
		assertEquals(0.25f, rows.float(TransformParameterKeys.SLIDE_FACTOR))
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
		val landed = parameters(deltaX = 10f)
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