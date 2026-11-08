package org.umamo.ui.viewport.viewport2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the depth inset's floor: the pivot climbs toward the horizon as it is pushed away and drops in front of the
 * art plane when pulled toward the viewer, the receding lines converge on the vanishing point, and a deep push widens
 * the framing rather than crowding it with lines.
 */
class PivotDepthInsetGeometryTest {
	private val width = 160f
	private val height = 80f

	/** A deeper pivot sits higher in the inset, beyond the art plane and short of the horizon. */
	@Test
	fun aDeeperPivotClimbsTowardTheHorizon() {
		val shallow = pivotDepthInsetGeometry(100f, 100f, width, height)
		val deep = pivotDepthInsetGeometry(400f, 100f, width, height)

		assertTrue(deep.pivot.y < shallow.pivot.y, "a deeper pivot sits higher, nearer the horizon")
		assertTrue(shallow.pivot.y < shallow.artCenter.y, "a pushed pivot sits beyond the art plane")
		assertTrue(deep.pivot.y > deep.horizonY, "and never past the horizon")
	}

	/** A pivot at depth 0 sits on the art plane, in the middle of the inset. */
	@Test
	fun aPivotOnTheArtSitsOnTheArtPlane() {
		val geometry = pivotDepthInsetGeometry(0f, 100f, width, height)

		assertEquals(geometry.artCenter, geometry.pivot)
		assertEquals(width / 2f, geometry.pivot.x, 1e-4f, "the depth line runs up the middle")
	}

	/** A pivot pulled toward the viewer sits in front of the art plane and stays inside the inset. */
	@Test
	fun aPulledPivotSitsInFrontOfTheArtPlane() {
		val geometry = pivotDepthInsetGeometry(-150f, 100f, width, height)

		assertTrue(geometry.pivot.y > geometry.artCenter.y, "a pulled pivot is nearer the viewer than the art")
		assertTrue(geometry.pivot.y <= height, "and still inside the inset")
	}

	/** Every receding floor line meets the horizon at the inset's vanishing point. */
	@Test
	fun theRecedingLinesConvergeOnTheVanishingPoint() {
		val geometry = pivotDepthInsetGeometry(200f, 100f, width, height)
		val receding = geometry.gridLines.filter { line -> line.start.x != line.end.x || line.start.y != line.end.y }.filter { line -> line.start.y != line.end.y }

		assertTrue(receding.isNotEmpty())

		for (line in receding) {
			// Extend the line to the horizon: it must meet the vanishing point at the inset's middle.
			val share = (geometry.horizonY - line.start.y) / (line.end.y - line.start.y)
			val horizonX = line.start.x + (line.end.x - line.start.x) * share
			assertEquals(width / 2f, horizonX, 1e-2f)
		}
	}

	/** A very deep pivot widens the floor grid's step, so the inset never crowds with lines. */
	@Test
	fun aDeepPushWidensTheFramingInsteadOfCrowdingIt() {
		val geometry = pivotDepthInsetGeometry(50_000f, 100f, width, height)
		val across = geometry.gridLines.count { line -> line.start.y == line.end.y }

		assertTrue(across in 1..9, "the framing holds at most eight steps across, held $across")
		assertTrue(geometry.pivot.y > geometry.horizonY)
	}
}