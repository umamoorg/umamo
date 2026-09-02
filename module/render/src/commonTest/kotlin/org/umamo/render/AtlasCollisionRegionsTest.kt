package org.umamo.render

import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.AtlasPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the collision regions the placement gizmo warns over: a tile's opaque mask marks exactly the
 * texels at or above the threshold; a mesh mask covers a triangle's pixels plus one pixel of margin
 * and leaves the corners of a thin diagonal mesh's box clear; a page occupancy finds painted pixels
 * only where a sampled region actually covers them (not in a strand's empty corner, nor in a spot a
 * mover is leaving); a sampled region hits a placed mask only on opaque texels or their extrusion
 * band; and two tiles whose boxes overlap while their triangles and pixels stay apart never collide.
 */
class AtlasCollisionRegionsTest {
	private fun raster(width: Int, height: Int, opaque: (Int, Int) -> Boolean): DecodedImage {
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) {
			for (x in 0 until width) {
				if (opaque(x, y)) {
					val offset = (y * width + x) * 4
					rgba[offset] = 0x40
					rgba[offset + 3] = 0xFF.toByte()
				}
			}
		}
		return DecodedImage(rgba, width, height)
	}

	private fun placement(x: Float, y: Float, rotationDegrees: Float = 0f): AtlasPlacement =
		AtlasPlacement(0, x, y, 1f, 1f, rotationDegrees)

	private fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): FloatArray =
		floatArrayOf(x0, y0, x1, y1, x2, y2)

	/** A mesh covering the axis-aligned rectangle [left, right) by [top, bottom) with two triangles. */
	private fun quadMesh(left: Float, top: Float, right: Float, bottom: Float): TileMeshMask =
		assertNotNull(
			TileMeshMask.of(
				listOf(triangle(left, top, right, top, right, bottom), triangle(left, top, right, bottom, left, bottom)),
			),
		)

	@Test
	fun theOpaqueMaskMarksTexelsAtTheThreshold() {
		val art = raster(6, 6) { x, y -> x == y }
		val mask = TileOpaqueMask.of(art, LayerBounds(1, 1, 4, 4), alphaThreshold = 1)
		assertTrue(mask.isOpaque(2, 2), "a diagonal texel inside the trim")
		assertFalse(mask.isOpaque(2, 3), "an off-diagonal texel")
		assertFalse(mask.isOpaque(0, 0), "outside the trim, even though the raster is opaque there")
	}

	@Test
	fun theMeshMaskCoversATriangleAndOnePixelAround() {
		// A right triangle with the right angle at (10, 10), legs of 8 along +x and +y.
		val mask = assertNotNull(TileMeshMask.of(listOf(triangle(10f, 10f, 18f, 10f, 10f, 18f))))
		assertTrue(mask.isCovered(11, 11), "inside")
		assertTrue(mask.isCovered(9, 9), "the one-pixel margin around a corner")
		assertFalse(mask.isCovered(17, 17), "the far corner of the bounding box, past the hypotenuse")
		assertFalse(mask.isCovered(7, 7), "two pixels out is beyond the margin")
		assertNull(TileMeshMask.of(emptyList()), "no triangles, no mask")
	}

	@Test
	fun aThinDiagonalMeshLeavesItsBoxCornersClear() {
		// A sliver along the diagonal of a 40x40 box.
		val strand = assertNotNull(TileMeshMask.of(listOf(triangle(0f, 0f, 2f, 0f, 40f, 40f), triangle(0f, 0f, 40f, 40f, 38f, 40f))))
		assertTrue(strand.isCovered(20, 20), "on the diagonal")
		assertFalse(strand.isCovered(35, 5), "the box's top-right corner")
		assertFalse(strand.isCovered(5, 35), "the box's bottom-left corner")
		assertEquals(-1, strand.bounds.left, "the box carries the margin")
	}

	@Test
	fun theOccupancyFindsPaintedPixelsInsideASampledRegionOnly() {
		// A diagonal strand across a 32x32 page: painted only where x == y.
		val page = raster(32, 32) { x, y -> x == y }
		val occupancy = PageOccupancy.of(page, excluded = emptyList())
		assertTrue(occupancy.isPainted(10, 10))
		assertFalse(occupancy.isPainted(10, 11))
		// A mesh in the strand's empty corner samples nothing, although the strand's box is the whole page.
		val corner = SampledRegion(placement(20f, 2f), quadMesh(0f, 0f, 6f, 6f))
		assertNull(occupancy.firstPaintedPixelIn(corner), "a region in the strand's empty corner is clear")
		val across = SampledRegion(placement(12f, 12f), quadMesh(1f, 1f, 5f, 5f))
		assertEquals(PixelRect(12, 12, 13, 13), occupancy.firstPaintedPixelIn(across), "a region across the strand hits its first painted pixel (the margin reaches one pixel out)")
	}

	@Test
	fun anExcludedRectangleReadsAsEmpty() {
		val page = raster(16, 16) { x, y -> x in 4 until 8 && y in 4 until 8 }
		val occupancy = PageOccupancy.of(page, excluded = listOf(PixelRect(4, 4, 8, 8)))
		assertFalse(occupancy.isPainted(5, 5), "the mover's old spot reads as empty")
		assertTrue(occupancy.blockMayBePainted(0, 0), "the block bit ignores exclusions and the exact test decides")
		assertNull(occupancy.firstPaintedPixelIn(SampledRegion(placement(3f, 3f), quadMesh(0f, 0f, 6f, 6f))))
	}

	@Test
	fun aSampledRegionKeepsItsShapeUnderRotation() {
		// A 10x4 mesh turned a quarter stands 4 wide and 10 tall (plus the margin), and the box's empty
		// corners stay outside the region.
		val region = SampledRegion(placement(20f, 20f, rotationDegrees = 90f), quadMesh(0f, 0f, 10f, 4f))
		assertTrue(region.contains(18f, 25f), "inside the turned rectangle")
		assertFalse(region.contains(13f, 25f), "beyond its short side and margin")
		assertTrue(region.bounds.right - region.bounds.left in 5.9f..6.1f, "the bounds are 4 wide plus the margin")
		assertTrue(region.bounds.bottom - region.bounds.top in 11.9f..12.1f, "and 10 tall plus the margin")
	}

	@Test
	fun aSampledRegionHitsAPlacedMaskOnlyOnOpaqueTexelsOrTheirBand() {
		// A painter whose only opaque texel is at (2, 2) of a 5x5 trim, placed at (10, 10): opaque
		// page pixel (12, 12).
		val painter = TileOpaqueMask.of(raster(5, 5) { x, y -> x == 2 && y == 2 }, LayerBounds(0, 0, 5, 5), alphaThreshold = 1)
		val painterAt = placement(10f, 10f)
		val point = quadMesh(0f, 0f, 1f, 1f)
		// The mesh's margin reaches one pixel around (10, 10): pixels 9..11, all transparent.
		val overTransparentCorner = SampledRegion(placement(10f, 10f), point)
		assertFalse(sampledRegionHitsMask(overTransparentCorner, painterAt, painter, extrude = 2), "a transparent texel of the painter's rectangle is not paint")
		val overTheTexel = SampledRegion(placement(12f, 12f), point)
		assertTrue(sampledRegionHitsMask(overTheTexel, painterAt, painter, extrude = 0), "the opaque texel is paint")
		// The band: a mesh two pixels past the trim's right edge, beside an opaque edge texel.
		val bandRegion = SampledRegion(placement(16f, 12f), point)
		assertFalse(sampledRegionHitsMask(bandRegion, painterAt, painter, extrude = 0), "without a band the edge is transparent")
		val edgePainter = TileOpaqueMask.of(raster(5, 5) { x, _ -> x == 4 }, LayerBounds(0, 0, 5, 5), alphaThreshold = 1)
		assertTrue(sampledRegionHitsMask(bandRegion, painterAt, edgePainter, extrude = 2), "an opaque edge texel's band counts as paint")
	}

	@Test
	fun overlappingBoxesWithDisjointTrianglesAndPixelsNeverCollide() {
		// The screenshot: a wide tile whose single triangular mesh overhangs its art and fills only the
		// upper-left half of its box, and a strand whose sliver mesh hugs its diagonal pixels.  Placed
		// so the strand's pixels sit inside the wide mesh's BOX (its lower-right corner) while no
		// triangle touches the other tile's pixels or its extrusion band.
		val wideArt = raster(40, 20) { x, y -> x < 20 && y in 4 until 16 }
		val wideMask = TileOpaqueMask.of(wideArt, LayerBounds(0, 4, 20, 12), alphaThreshold = 1)
		val wideMesh = assertNotNull(TileMeshMask.of(listOf(triangle(0f, 2f, 24f, 2f, 0f, 18f))))
		val wideAt = placement(0f, 0f)
		val strandArt = raster(30, 30) { x, y -> x == y }
		val strandMask = TileOpaqueMask.of(strandArt, LayerBounds(0, 0, 30, 30), alphaThreshold = 1)
		val strandMesh = assertNotNull(TileMeshMask.of(listOf(triangle(0f, -1f, 30f, 29f, 0f, 1f), triangle(0f, 1f, 30f, 29f, 30f, 31f))))
		// The strand's first pixels land at page (24, 9), (25, 10): inside the wide mesh's box (x to 25,
		// y to 19), while the triangle's coverage at those rows ends near x = 16 - clear of the strand's
		// two-pixel band even with the mesh's one-pixel margin - and past the wide art's own band.
		val strandNear = placement(24f, 9f)
		assertTrue(SampledRegion(wideAt, wideMesh).bounds.overlaps(placementFootprint(strandNear, strandMask.trim, reserve = null)), "the boxes do overlap")
		assertFalse(sampledRegionHitsMask(SampledRegion(wideAt, wideMesh), strandNear, strandMask, extrude = 2), "the wide mesh's box holds the strand's pixels but its triangle does not reach them")
		assertFalse(sampledRegionHitsMask(SampledRegion(strandNear, strandMesh), wideAt, wideMask, extrude = 2), "the strand's triangles never reach the wide tile's pixels or band")
		// Slide the strand under the triangle and it collides.
		val strandTouching = placement(4f, 6f)
		assertTrue(sampledRegionHitsMask(SampledRegion(wideAt, wideMesh), strandTouching, strandMask, extrude = 2), "an overhanging triangle over the strand's pixels collides")
		val strandFar = placement(30f, 0f)
		assertFalse(sampledRegionHitsMask(SampledRegion(wideAt, wideMesh), strandFar, strandMask, extrude = 2), "far apart, nothing")
	}

	@Test
	fun aMeshCoveringHalfItsTileIgnoresPaintUnderTheOtherHalf() {
		// A bystander whose mesh covers only the left half of its 20x10 tile; a mover's opaque pixel
		// under its right half is paint nobody samples.
		val halfMesh = quadMesh(0f, 0f, 10f, 10f)
		val bystanderAt = placement(0f, 0f)
		val dot = TileOpaqueMask.of(raster(3, 3) { x, y -> x == 1 && y == 1 }, LayerBounds(0, 0, 3, 3), alphaThreshold = 1)
		assertFalse(sampledRegionHitsMask(SampledRegion(bystanderAt, halfMesh), placement(15f, 4f), dot, extrude = 0), "paint under the unmeshed half")
		assertTrue(sampledRegionHitsMask(SampledRegion(bystanderAt, halfMesh), placement(4f, 4f), dot, extrude = 0), "paint under the meshed half")
	}
}