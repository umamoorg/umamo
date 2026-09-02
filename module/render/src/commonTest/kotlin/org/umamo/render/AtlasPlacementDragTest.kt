package org.umamo.render

import org.umamo.format.art.LayerBounds
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.runtime.model.AtlasPlacement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the placement footprint the gizmo warns over: an upright placement's footprint is its trim at
 * its position, a mesh reserve widens it, a turned placement's footprint is the turned rectangle's
 * bounding box, and the overlap / off-page tests read those edges the way the composer will paint
 * them.
 */
class AtlasPlacementDragTest {
	private val trim = LayerBounds(2, 3, 8, 6)

	private fun placement(x: Float, y: Float, rotationDegrees: Float = 0f, scale: Float = 1f): AtlasPlacement =
		AtlasPlacement(0, x, y, scale, scale, rotationDegrees)

	private fun assertClose(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) < 1e-3f, "$message: expected $expected, was $actual")
	}

	private fun assertFootprint(expected: PlacementFootprint, actual: PlacementFootprint) {
		assertClose(expected.left, actual.left, "left")
		assertClose(expected.top, actual.top, "top")
		assertClose(expected.right, actual.right, "right")
		assertClose(expected.bottom, actual.bottom, "bottom")
	}

	@Test
	fun anUprightFootprintIsTheTrimAtThePosition() {
		assertFootprint(PlacementFootprint(12f, 23f, 20f, 29f), placementFootprint(placement(10f, 20f), trim, reserve = null))
	}

	@Test
	fun aMeshReserveWidensTheFootprint() {
		val reserve = AtlasPackReserve(left = -1, top = 0, right = 12, bottom = 5)
		assertFootprint(PlacementFootprint(9f, 20f, 22f, 29f), placementFootprint(placement(10f, 20f), trim, reserve))
	}

	@Test
	fun aScaledFootprintScalesAboutTheTileOrigin() {
		assertFootprint(PlacementFootprint(11f, 21.5f, 15f, 24.5f), placementFootprint(placement(10f, 20f, scale = 0.5f), trim, reserve = null))
	}

	@Test
	fun aQuarterTurnedFootprintIsTheTurnedRectanglesBounds() {
		// A 90 degree placement maps (x, y) to (-y, x) about the tile origin: the 8x6 trim becomes 6 wide by 8 tall.
		val footprint = placementFootprint(placement(10f, 20f, rotationDegrees = 90f), trim, reserve = null)
		assertClose(6f, footprint.right - footprint.left, "turned width")
		assertClose(8f, footprint.bottom - footprint.top, "turned height")
		assertClose(10f - 9f, footprint.left, "the trim's bottom edge (y = 9) turns to x = -9")
		assertClose(20f + 2f, footprint.top, "the trim's left edge (x = 2) turns to y = 2")
	}

	@Test
	fun overlapAndPageTestsReadTheEdges() {
		val one = PlacementFootprint(0f, 0f, 10f, 10f)
		assertTrue(one.overlaps(PlacementFootprint(9f, 9f, 20f, 20f)), "shared area overlaps")
		assertFalse(one.overlaps(PlacementFootprint(10f, 0f, 20f, 10f)), "a touching edge is not an overlap")
		assertTrue(one.expanded(2f).overlaps(PlacementFootprint(11f, 0f, 20f, 10f)), "the gutter margin reaches a neighbor one pixel away")
		assertFalse(one.exceeds(10, 10), "a footprint filling the page exactly stays on it")
		assertTrue(one.expanded(1f).exceeds(10, 10), "growing past the edge spills")
		assertTrue(PlacementFootprint(-0.5f, 0f, 5f, 5f).exceeds(64, 64), "a fractional overhang spills")
	}
}