package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a mesh covers its art, pixel by pixel: art reaching into a concave mesh's notch is cut off
 * even inside the mesh's bounding box, a pixel center on an edge counts as inside, both windings count,
 * a flat triangle covers nothing, and art the caller excuses as already cut off does not count.
 */
class MeshCoverageTest {
	/** An L over a 20 x 20 frame: the square (0, 0)-(20, 20) with its top-right quarter (10..20, 0..10) cut away. */
	private val lPoints = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 20f, 10f, 20f, 20f, 0f, 20f)
	private val lIndices = intArrayOf(0, 1, 2, 0, 2, 5, 2, 3, 4, 2, 4, 5)

	@Test
	fun artInsideTheMeshIsCovered() {
		val inTheL = alphaOf(20, 20) { column, row -> column < 10 || row >= 10 }

		assertTrue(meshCoversOpaquePixels(inTheL, 1, lPoints, lIndices))
	}

	@Test
	fun artReachingIntoTheNotchIsCutOffInsideTheBoundingBox() {
		val filled = alphaOf(20, 20) { column, row -> column < 10 || row >= 10 || (column == 14 && row == 5) }

		assertFalse(meshCoversOpaquePixels(filled, 1, lPoints, lIndices), "one pixel in the notch, well inside the mesh's bounding box")
	}

	@Test
	fun aPixelCenterOnAnEdgeIsInsideAndBothWindingsCount() {
		// Two triangles sharing the diagonal of a 4 x 4 square; the pixel centers (0.5, 0.5) ... lie on it.
		val square = floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
		val whole = alphaOf(4, 4) { _, _ -> true }

		assertTrue(meshCoversOpaquePixels(whole, 1, square, intArrayOf(0, 1, 2, 0, 2, 3)), "counterclockwise in raw coordinates")
		assertTrue(meshCoversOpaquePixels(whole, 1, square, intArrayOf(0, 2, 1, 0, 3, 2)), "and clockwise")

		val diagonalOnly = floatArrayOf(0f, 0f, 4f, 4f, 0f, 4f)
		val onTheDiagonal = alphaOf(4, 4) { column, row -> column == row }
		assertTrue(meshCoversOpaquePixels(onTheDiagonal, 1, diagonalOnly, intArrayOf(0, 1, 2)), "centers on the hypotenuse are inside")
	}

	@Test
	fun aFlatTriangleCoversNothingAndFaintArtUnderTheThresholdDoesNotCount() {
		val line = floatArrayOf(0f, 0.5f, 4f, 0.5f, 2f, 0.5f)
		val row = alphaOf(4, 1) { _, _ -> true }

		assertFalse(meshCoversOpaquePixels(row, 1, line, intArrayOf(0, 1, 2)), "a triangle with no area")
		assertTrue(meshCoversOpaquePixels(alphaOf(4, 4, alpha = 10) { _, _ -> true }, 11, line, intArrayOf(0, 1, 2)), "nothing reaches the threshold")
	}

	@Test
	fun artAlreadyCutOffIsExcused() {
		val filled = alphaOf(20, 20) { column, row -> column < 10 || row >= 10 || (column == 14 && row == 5) }

		assertTrue(meshCoversOpaquePixels(filled, 1, lPoints, lIndices) { column, row -> column == 14 && row == 5 })
		assertFalse(meshCoversOpaquePixels(filled, 1, lPoints, lIndices) { column, row -> column == 15 && row == 5 }, "another pixel's excuse does not cover it")
	}

	/**
	 * An alpha channel at [alpha] where a predicate says so, transparent elsewhere.
	 *
	 * @param Int                   width  The width.
	 * @param Int                   height The height.
	 * @param Int                   alpha  The alpha of the art's pixels.
	 * @param (Int, Int) -> Boolean art    Whether a pixel is art.
	 * @return AlphaChannel The channel.
	 */
	private fun alphaOf(width: Int, height: Int, alpha: Int = 255, art: (Int, Int) -> Boolean): AlphaChannel =
		object : AlphaChannel {
			override val width: Int = width
			override val height: Int = height

			override fun alphaAt(column: Int, row: Int): Int = if (art(column, row)) alpha else 0
		}
}