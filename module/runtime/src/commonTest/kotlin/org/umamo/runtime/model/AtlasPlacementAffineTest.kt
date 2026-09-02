package org.umamo.runtime.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the placement's matrix form against its pointwise reference, and the affine composition the
 * export's transform rewrites lean on: forward-composed-with-inverse must be the identity for every
 * placement shape the corpus contains.
 */
class AtlasPlacementAffineTest {
	private fun placementOf(
		positionX: Float = 0f,
		positionY: Float = 0f,
		scaleX: Float = 1f,
		scaleY: Float = 1f,
		rotationDegrees: Float = 0f,
	): AtlasPlacement = AtlasPlacement(0, positionX, positionY, scaleX, scaleY, rotationDegrees)

	private val shapes =
		listOf(
			placementOf(),
			placementOf(positionX = 137.5f, positionY = -12.25f),
			placementOf(rotationDegrees = 90f, positionX = 40f),
			placementOf(rotationDegrees = 33.7f, scaleX = 0.5f, scaleY = 2f, positionX = 7f, positionY = 9f),
			placementOf(scaleX = -1f, positionY = 512f),
		)

	private fun applyAffine(affine: FloatArray, x: Float, y: Float): FloatArray =
		floatArrayOf(
			affine[0] * x + affine[1] * y + affine[2],
			affine[3] * x + affine[4] * y + affine[5],
		)

	@Test
	fun placementAffineMatchesAtlasPixelOf() {
		val probePoints = listOf(0f to 0f, 12f to 34f, -5f to 100f, 250.5f to 0.25f)
		for (placement in shapes) {
			val affine = placementAffine(placement)
			for ((layerX, layerY) in probePoints) {
				val pointwise = atlasPixelOf(placement, layerX, layerY)
				val viaMatrix = applyAffine(affine, layerX, layerY)
				assertTrue(
					abs(pointwise[0] - viaMatrix[0]) < 1e-3f && abs(pointwise[1] - viaMatrix[1]) < 1e-3f,
					"$placement at ($layerX, $layerY): pointwise (${pointwise[0]}, ${pointwise[1]})" +
						" vs matrix (${viaMatrix[0]}, ${viaMatrix[1]})",
				)
			}
		}
	}

	@Test
	fun composeWithInverseIsIdentity() {
		for (placement in shapes) {
			val forward = placementAffine(placement)
			val inverse = assertNotNull(inversePlacementAffine(placement), "$placement inverts")
			val composed = composeAffine(forward, inverse)
			val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
			for (componentIndex in identity.indices) {
				assertTrue(
					abs(composed[componentIndex] - identity[componentIndex]) < 1e-3f,
					"$placement component $componentIndex: ${composed[componentIndex]}",
				)
			}
		}
	}
}