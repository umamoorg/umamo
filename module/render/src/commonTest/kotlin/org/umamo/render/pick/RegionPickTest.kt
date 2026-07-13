package org.umamo.render.pick

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the pure Object-mode region enclosure: centroids reduce a drawable to a single test point, and box
 * / circle enclosure select exactly the drawables whose centroid falls inside - including the decisive case
 * that a large layer whose centroid sits outside a grazing region is NOT grabbed (centroid-inside, not
 * any-vertex-touching).
 */
class RegionPickTest {
	// A small square drawable centred at (1, 1).
	private fun square(): FloatArray = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 2f, 2f)

	// A full-canvas layer spanning (-100, -100)..(100, 100); its centroid is the origin.
	private fun canvasLayer(): FloatArray = floatArrayOf(-100f, -100f, 100f, -100f, -100f, 100f, 100f, 100f)

	/** A drawable's centroid is the mean of its interleaved vertices; an empty array is skipped. */
	@Test
	fun centroidIsVertexMean() {
		val centroids = drawableCentroids(mapOf(DrawableId("s") to square(), DrawableId("blank") to FloatArray(0)))
		assertEquals(1f, centroids.getValue(DrawableId("s"))[0], "centroid x")
		assertEquals(1f, centroids.getValue(DrawableId("s"))[1], "centroid y")
		assertTrue(DrawableId("blank") !in centroids, "an empty array has no centroid")
	}

	/** Box select selects a drawable whose centroid is inside and excludes one whose centroid is outside. */
	@Test
	fun boxSelectsByCentroid() {
		val centroids = drawableCentroids(mapOf(DrawableId("in") to square(), DrawableId("out") to canvasLayer()))
		// A box around (1,1) that does NOT contain the origin.
		val enclosed = drawablesInBox(centroids, minX = 0.5f, minY = 0.5f, maxX = 1.5f, maxY = 1.5f)
		assertEquals(setOf(DrawableId("in")), enclosed, "only the drawable centred in the box is selected")
	}

	/** A box grazing a full-canvas layer's edge does not grab it - its centroid (the origin) is far outside. */
	@Test
	fun grazingBoxDoesNotGrabFullCanvasLayer() {
		val centroids = drawableCentroids(mapOf(DrawableId("canvas") to canvasLayer()))
		// A box in the top-right corner that overlaps the layer's geometry but not its centroid at the origin.
		val enclosed = drawablesInBox(centroids, minX = 50f, minY = 50f, maxX = 90f, maxY = 90f)
		assertTrue(enclosed.isEmpty(), "centroid-inside means a grazing box does not select the whole layer")
	}

	/** Circle select selects by centroid-within-radius; a centroid outside the disc is excluded. */
	@Test
	fun circleSelectsByCentroidWithinRadius() {
		// "near" centroid (1,1) sits at distance sqrt(2) ~= 1.41 from (1,1)'s... the canvas centroid is the origin.
		val centroids = drawableCentroids(mapOf(DrawableId("near") to square(), DrawableId("far") to canvasLayer()))

		// Centre at (1,1), radius 0.5: the square's centroid (1,1) is inside, the origin (distance ~1.41) is not.
		assertEquals(setOf(DrawableId("near")), drawablesInCircle(centroids, centerX = 1f, centerY = 1f, radius = 0.5f))

		// A radius of 1.5 (> the ~1.41 distance to the origin) also encloses the canvas layer's centroid.
		val reaching = drawablesInCircle(centroids, centerX = 1f, centerY = 1f, radius = 1.5f)
		assertTrue(DrawableId("far") in reaching, "a centroid within the radius is enclosed")
		assertTrue(DrawableId("near") in reaching, "the closer centroid is enclosed too")
	}

	/** Empty geometry encloses nothing. */
	@Test
	fun emptyGeometryEnclosesNothing() {
		assertTrue(drawablesInBox(emptyMap(), 0f, 0f, 1f, 1f).isEmpty())
		assertTrue(drawablesInCircle(emptyMap(), 0f, 0f, 1f).isEmpty())
	}
}
