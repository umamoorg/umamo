package org.umamo.runtime.keyform

import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.identityAffine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the whole-lattice transforms: each reaches every grid cell and every blend-shape form and nothing else,
 * and refuses input of the wrong shape.  That the evaluated lattice follows (old + delta, affine of old) is the
 * evaluator's to show, and the render module's tests do.
 */
class WarpLatticeTransformsTest {
	private val angle = ParameterId("ParamAngleX")
	private val smile = ParameterId("ParamSmile")

	/** The four control points of a 1x1 lattice, offset by [offset] so every form differs. */
	private fun square(offset: Float): FloatArray = floatArrayOf(offset, offset, offset + 2f, offset, offset, offset + 2f, offset + 2f, offset + 2f)

	/** A 1x1 warp keyed on two keys with a two-key blend shape whose neutral form is absent. */
	private fun warp(): Deformer.Warp =
		Deformer.Warp(
			id = DeformerId("w"),
			name = "w",
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(angle, floatArrayOf(-1f, 1f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(square(0f))), KeyformCell(intArrayOf(1), WarpLatticeForm(square(10f)))),
				),
			blendShapes =
				listOf(
					BlendShapeBinding(
						smile,
						floatArrayOf(0f, 1f),
						neutralIndex = 0,
						forms = listOf(null, WarpForm(square(20f), opacity = 0.5f, multiplyColor = ColorRgb(0.5f, 0.25f, 1f))),
					),
				),
		)

	/** The shift adds the delta to both grid cells and the blend form, and keeps the form's channels. */
	@Test
	fun theShiftReachesEveryCellAndBlendForm() {
		val delta = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
		val shifted = warp().withLatticeShifted(delta)

		val cells = shifted.geometryGrid!!.cells
		assertContentEquals(square(0f).zip(delta.toList()) { point, offset -> point + offset }.toFloatArray(), cells[0].form.controlPoints)
		assertContentEquals(square(10f).zip(delta.toList()) { point, offset -> point + offset }.toFloatArray(), cells[1].form.controlPoints)
		val form = shifted.blendShapes.single().forms[1]!!
		assertContentEquals(square(20f).zip(delta.toList()) { point, offset -> point + offset }.toFloatArray(), form.controlPoints)
		assertEquals(0.5f, form.opacity)
		assertEquals(ColorRgb(0.5f, 0.25f, 1f), form.multiplyColor)
		assertNull(shifted.blendShapes.single().forms[0], "an absent neutral form stays absent")
		assertContentEquals(intArrayOf(1), cells[1].coordinate, "coordinates are untouched")
	}

	/** Mapping every lattice through an affine reaches every point of every form: a quarter turn plus a translation here. */
	@Test
	fun theAffineReachesEveryCellAndBlendForm() {
		val quarterTurn = floatArrayOf(0f, -1f, 100f, 1f, 0f, 50f)
		val transformed = warp().withEveryLatticeMapped { points -> applyAffine(points, quarterTurn) }

		val expected = { points: FloatArray ->
			FloatArray(points.size) { componentIndex ->
				val x = points[componentIndex - componentIndex % 2]
				val y = points[componentIndex - componentIndex % 2 + 1]
				if (componentIndex % 2 == 0) -y + 100f else x + 50f
			}
		}
		assertContentEquals(expected(square(0f)), transformed.geometryGrid!!.cells[0].form.controlPoints)
		assertContentEquals(expected(square(10f)), transformed.geometryGrid!!.cells[1].form.controlPoints)
		assertContentEquals(expected(square(20f)), transformed.blendShapes.single().forms[1]!!.controlPoints)
	}

	/** The identity affine and a zero shift change no value. */
	@Test
	fun identitiesChangeNothing() {
		val original = warp()

		for (moved in listOf(original.withEveryLatticeMapped { points -> applyAffine(points, identityAffine()) }, original.withLatticeShifted(FloatArray(8)))) {
			assertContentEquals(original.geometryGrid!!.cells[1].form.controlPoints, moved.geometryGrid!!.cells[1].form.controlPoints)
			assertContentEquals(original.blendShapes.single().forms[1]!!.controlPoints, moved.blendShapes.single().forms[1]!!.controlPoints)
		}
	}

	/** A shift and its negation come back within a few ULP, however often they repeat. */
	@Test
	fun aShiftAndItsNegationReturnWithinBoundedUlp() {
		val delta = floatArrayOf(0.1f, -0.3f, 7.25f, 0.001f, -12.5f, 3.3f, 0.7f, -0.9f)
		val negated = FloatArray(delta.size) { componentIndex -> -delta[componentIndex] }
		var moved = warp()

		repeat(100) {
			moved = moved.withLatticeShifted(delta).withLatticeShifted(negated)
		}

		val original = warp().geometryGrid!!.cells[1].form.controlPoints
		val returned = moved.geometryGrid!!.cells[1].form.controlPoints

		for (componentIndex in original.indices) {
			assertTrue(abs(returned[componentIndex] - original[componentIndex]) < 1e-4f, "component $componentIndex drifted to ${returned[componentIndex]}")
		}
	}

	/** Input of the wrong shape changes nothing. */
	@Test
	fun inputOfTheWrongShapeIsRefused() {
		val original = warp()

		assertSame(original, original.withLatticeShifted(FloatArray(6)))
	}
}