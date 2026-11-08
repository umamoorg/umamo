package org.umamo.edit.transform

import org.umamo.edit.DepthSurface
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.withParameter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the transform gestures' rows on the operation settings strip: they carry the gesture's numbers
 * in each space's convention (world y up as Z, page y down with the angle's sense flipped), read back
 * to the same parameters, the proportional rows appear only when the gesture could take weights and
 * read back as a state, and the slide's one row carries its factor.
 */
class TransformAdjustTest {
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

	/** A Rotate that could rotate in depth adds its Axis, Depth, and Surface rows, and the rows read back as the same parameters. */
	@Test
	fun aDepthRotationAddsItsAxisDepthAndSurfaceRowsAndRoundTrips() {
		val gesture = TransformGestureParameters(0f, 0f, 1f, 1f, 0.5f, RotationAxis.Z, 140f, DepthSurface.Cylinder)

		val plain = transformParameters(MeshOperatorKind.Rotate, gesture, TransformRowSpace.World, proportional = null)
		assertEquals(listOf(TransformParameterKeys.ANGLE), plain.map { row -> row.key }, "a Rotate that cannot rotate in depth shows its angle alone")

		val rows = transformParameters(MeshOperatorKind.Rotate, gesture, TransformRowSpace.World, proportional = null, rotatesInDepth = true)
		assertEquals(
			listOf(TransformParameterKeys.ANGLE, TransformParameterKeys.AXIS, TransformParameterKeys.PIVOT_DEPTH, TransformParameterKeys.DEPTH_SURFACE),
			rows.map { row -> row.key },
		)

		val back = transformGestureParametersOf(MeshOperatorKind.Rotate, TransformRowSpace.World, rows)
		assertClose(0.5f, back.rotationRadians, "the angle round-trips")
		assertEquals(RotationAxis.Z, back.rotationAxis)
		assertEquals(140f, back.pivotDepth)
		assertEquals(DepthSurface.Cylinder, back.depthSurface)

		// Choosing the axis on the strip turns an in-plane Rotate in depth after the fact.
		val axisRow = rows.first { row -> row.key == TransformParameterKeys.AXIS } as OperatorParameter.ChoiceParameter
		val inPlane = rows.withParameter(TransformParameterKeys.AXIS, axisRow.copy(value = RotationAxis.Y.choiceKey))
		assertEquals(RotationAxis.Y, transformGestureParametersOf(MeshOperatorKind.Rotate, TransformRowSpace.World, inPlane).rotationAxis)

		val withoutDepthRows = transformGestureParametersOf(MeshOperatorKind.Rotate, TransformRowSpace.World, plain)
		assertEquals(RotationAxis.Y, withoutDepthRows.rotationAxis, "missing depth rows read as the in-plane turn")
		assertEquals(0f, withoutDepthRows.pivotDepth)
	}

	@Test
	fun scaleRowsNameTheVerticalAxisPerSpaceAndRoundTrip() {
		val gesture = parameters(factorX = 2f, factorY = 0.5f)

		val worldRows = transformParameters(MeshOperatorKind.Scale, gesture, TransformRowSpace.World, proportional = null)
		assertEquals(listOf(TransformParameterKeys.SCALE_X, TransformParameterKeys.SCALE_Z), worldRows.map { row -> row.key })
		val pageRows = transformParameters(MeshOperatorKind.Scale, gesture, TransformRowSpace.UvDisplay, proportional = null)
		assertEquals(listOf(TransformParameterKeys.SCALE_X, TransformParameterKeys.SCALE_Y), pageRows.map { row -> row.key })
		// Scale X names the vertical row as its ratio partner, so the strip can lock the two together.
		assertEquals(listOf(TransformParameterKeys.SCALE_Z, null), worldRows.map { row -> (row as OperatorParameter.FloatParameter).ratioPartnerKey })
		assertEquals(listOf(TransformParameterKeys.SCALE_Y, null), pageRows.map { row -> (row as OperatorParameter.FloatParameter).ratioPartnerKey })

		for (space in TransformRowSpace.entries) {
			val back = transformGestureParametersOf(MeshOperatorKind.Scale, space, transformParameters(MeshOperatorKind.Scale, gesture, space, proportional = null))
			assertEquals(2f, back.factorX, "$space round-trips x")
			assertEquals(0.5f, back.factorY, "$space round-trips y")
		}

		assertTrue(transformParameters(MeshOperatorKind.VertexSlide, gesture, TransformRowSpace.World, proportional = null).isEmpty(), "a slide has rows of its own")
		assertEquals(0.25f, slideParameters(0.25f).float(TransformParameterKeys.SLIDE_FACTOR))
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

		val switchedOff = with.withParameter(TransformParameterKeys.PROPORTIONAL, OperatorParameter.BooleanParameter(TransformParameterKeys.PROPORTIONAL, TransformParameterKeys.PROPORTIONAL, false))
		assertNull(assertNotNull(proportionalRowsOf(switchedOff)).asState(), "unticking the flag turns the halo off")
	}

	/** The halos re-derive on the capture from the rows: a wider radius pulls the bystander in, an off flag drops it. */
	@Test
	fun rederivingTheHalosFollowsTheRows() {
		// Two vertices 10 apart, the first covered: at radius 5 the second is outside, at 20 it is inside.
		val capture =
			assertNotNull(
				buildModalTransformCapture(
					sources = listOf(ModalCaptureSource(org.umamo.runtime.model.ElementOwner.Drawable(org.umamo.runtime.model.DrawableId("a")), floatArrayOf(0f, 0f, 10f, 0f), org.umamo.edit.ElementTopology.ofTriangles(2, intArrayOf(0, 1, 1)), setOf(0))),
					pivotMode = TransformPivotMode.MedianPoint,
					individualOriginScope = IndividualOriginScope.ConnectivityIsland,
					operatorKind = MeshOperatorKind.Grab,
					activeAnchor = null,
					cursorAnchor = null,
				),
			)
		val entry = capture.entries.single()
		val gesture = parameters(deltaX = 1f)
		assertNull(rederiveProportionalHalos(capture, transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.World, proportional = null)), "no rows, no re-derivation")

		val narrow = transformParameters(MeshOperatorKind.Grab, gesture, TransformRowSpace.World, ProportionalRows(enabled = true, ProportionalFalloff.Linear, radius = 5f, connectedOnly = false))
		assertNotNull(rederiveProportionalHalos(capture, narrow))
		assertEquals(setOf(0), entry.movedIndices, "outside the radius nothing joins")

		val wide = narrow.withParameter(TransformParameterKeys.PROPORTIONAL_SIZE, OperatorParameter.FloatParameter(TransformParameterKeys.PROPORTIONAL_SIZE, TransformParameterKeys.PROPORTIONAL_SIZE, 20f, 1f, 100f))
		assertNotNull(rederiveProportionalHalos(capture, wide))
		assertEquals(setOf(0, 1), entry.movedIndices, "inside the radius the bystander joins")
		assertClose(0.5f, assertNotNull(entry.influence[1]).weight, "linear falloff at half the radius")

		val off = wide.withParameter(TransformParameterKeys.PROPORTIONAL, OperatorParameter.BooleanParameter(TransformParameterKeys.PROPORTIONAL, TransformParameterKeys.PROPORTIONAL, false))
		assertNull(assertNotNull(rederiveProportionalHalos(capture, off)).asState())
		assertEquals(setOf(0), entry.movedIndices, "off clears the halo")
	}
}