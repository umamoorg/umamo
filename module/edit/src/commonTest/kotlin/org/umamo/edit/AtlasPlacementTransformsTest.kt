package org.umamo.edit

import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.composeAffine
import org.umamo.runtime.model.placementAffine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the placement gizmo's algebra: composing a gesture affine onto a placement yields a placement
 * whose forward transform IS the product (so the preview and the commit agree to float precision),
 * each operator's page-space affine lands on the TRS component it means to, a mirrored placement keeps
 * its mirror, and the two products no placement can express - a page-axis non-uniform scale on a
 * rotated tile, and a collapsed axis - are refused rather than approximated.
 */
class AtlasPlacementTransformsTest {
	private fun placement(
		x: Float,
		y: Float,
		scaleX: Float = 1f,
		scaleY: Float = 1f,
		rotationDegrees: Float = 0f,
	): AtlasPlacement = AtlasPlacement(0, x, y, scaleX, scaleY, rotationDegrees)

	private fun radians(degrees: Float): Float = (degrees.toDouble() * PI / 180.0).toFloat()

	private fun assertClose(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) < 1e-3f, "$message: expected $expected, was $actual")
	}

	private fun assertAffineClose(expected: FloatArray, actual: FloatArray, message: String) {
		for (componentIndex in expected.indices) {
			assertClose(expected[componentIndex], actual[componentIndex], "$message (component $componentIndex)")
		}
	}

	/**
	 * Composes and asserts the load-bearing property: the result's forward affine is the product of the
	 * gesture and the original, so what the gizmo previewed is what the placement stores.
	 *
	 * @param AtlasPlacement original The placement before the gesture.
	 * @param FloatArray gesture The gesture's page-space affine.
	 * @return AtlasPlacement The composed placement.
	 */
	private fun assertComposesExactly(original: AtlasPlacement, gesture: FloatArray): AtlasPlacement {
		val composed = assertNotNull(original.composedWith(gesture), "the product is a placement")
		assertAffineClose(
			composeAffine(gesture, placementAffine(original)),
			placementAffine(composed),
			"the composed placement's forward affine is the gesture applied after the original",
		)
		return composed
	}

	@Test
	fun aTranslationMovesThePositionAndNothingElse() {
		val moved = assertComposesExactly(placement(10f, 20f, rotationDegrees = 30f), translationAffine(5f, -3f))
		assertClose(15f, moved.positionX, "x moved by the delta")
		assertClose(17f, moved.positionY, "y moved by the delta")
		assertClose(1f, moved.scaleX, "scale x untouched")
		assertClose(1f, moved.scaleY, "scale y untouched")
		assertClose(30f, moved.rotationDegrees, "rotation untouched")
	}

	@Test
	fun aRotationAboutAPivotAddsToTheAngleAndOrbitsThePosition() {
		val original = placement(10f, 20f, rotationDegrees = 30f)
		val rotated = assertComposesExactly(original, rotationAboutAffine(50f, 60f, radians(90f)))
		assertClose(120f, rotated.rotationDegrees, "the gesture angle adds to the placement's")
		assertClose(1f, rotated.scaleX, "scale x untouched")
		assertClose(1f, rotated.scaleY, "scale y untouched")
		// The origin orbits the pivot: (10, 20) is (-40, -40) from it, a quarter turn lands at (40, -40).
		assertClose(90f, rotated.positionX, "the origin orbited the pivot in x")
		assertClose(20f, rotated.positionY, "the origin orbited the pivot in y")
	}

	@Test
	fun aUniformScaleAboutAPivotMultipliesBothScalesAndKeepsTheAngle() {
		val original = placement(10f, 20f, rotationDegrees = 45f)
		val scaled = assertComposesExactly(original, scaleAboutAffine(50f, 60f, 2f, 2f))
		assertClose(2f, scaled.scaleX, "scale x doubled")
		assertClose(2f, scaled.scaleY, "scale y doubled")
		assertClose(45f, scaled.rotationDegrees, "rotation untouched")
		assertClose(-30f, scaled.positionX, "the origin moved away from the pivot in x")
		assertClose(-20f, scaled.positionY, "the origin moved away from the pivot in y")
	}

	@Test
	fun aLocalAxisScaleOnARotatedPlacementStaysExpressible() {
		val original = placement(10f, 20f, rotationDegrees = 30f)
		val gesture = localAxisScaleAboutAffine(original, 50f, 60f, 2f, 1f)
		val scaled = assertComposesExactly(original, gesture)
		assertClose(2f, scaled.scaleX, "the tile's own x axis scaled")
		assertClose(1f, scaled.scaleY, "the tile's own y axis untouched")
		assertClose(30f, scaled.rotationDegrees, "rotation untouched")
	}

	@Test
	fun aLocalAxisScaleOnAnUnrotatedPlacementIsThePageAxisScale() {
		val original = placement(10f, 20f)
		assertAffineClose(
			scaleAboutAffine(50f, 60f, 2f, 0.5f),
			localAxisScaleAboutAffine(original, 50f, 60f, 2f, 0.5f),
			"with no rotation the tile axes are the page axes",
		)
	}

	@Test
	fun aPageAxisNonUniformScaleOnARotatedPlacementIsRefused() {
		val original = placement(10f, 20f, rotationDegrees = 30f)
		assertNull(original.composedWith(scaleAboutAffine(50f, 60f, 2f, 1f)), "a shear is not a placement")
	}

	@Test
	fun aMirroredPlacementKeepsItsMirrorThroughAMove() {
		val original = placement(10f, 20f, scaleX = -1f, rotationDegrees = 15f)
		val moved = assertComposesExactly(original, translationAffine(4f, 4f))
		assertClose(-1f, moved.scaleX, "the mirror survives as a negative x scale, not as a half-turn")
		assertClose(1f, moved.scaleY, "scale y untouched")
		assertClose(15f, moved.rotationDegrees, "rotation untouched")
	}

	@Test
	fun aReducedImportedPlacementComposesAtItsOwnScale() {
		// The corpus packs art reduced (modelC scales most of its tiles); a nudge must keep that scale.
		val original = placement(100f, 200f, scaleX = 0.5f, scaleY = 0.5f, rotationDegrees = 270f)
		val moved = assertComposesExactly(original, translationAffine(-10f, 6f))
		assertClose(0.5f, moved.scaleX, "scale x kept")
		assertClose(0.5f, moved.scaleY, "scale y kept")
		assertClose(-90f, moved.rotationDegrees, "the angle comes back normalized to (-180, 180]")
	}

	@Test
	fun aDegenerateProductIsRefused() {
		assertNull(placement(10f, 20f, scaleX = 0f).composedWith(translationAffine(1f, 1f)), "a collapsed axis cannot be placed")
	}
}