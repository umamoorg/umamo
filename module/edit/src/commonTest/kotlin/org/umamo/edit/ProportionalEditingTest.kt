package org.umamo.edit

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Tests for the proportional-editing math: the falloff curves and the per-mesh weight map.

class ProportionalEditingTest {
	private val allFalloffs = ProportionalFalloff.entries

	@Test
	fun everyFalloffIsFullAtZeroAndZeroAtRadiusEdge() {
		for (falloff in allFalloffs) {
			assertEquals(1f, proportionalWeight(falloff, 0f), "$falloff at distance 0")
			assertEquals(0f, proportionalWeight(falloff, 1f), "$falloff at the radius edge")
			assertEquals(0f, proportionalWeight(falloff, 1.5f), "$falloff beyond the radius")
			assertEquals(1f, proportionalWeight(falloff, -0.5f), "$falloff clamps negative distances to full weight")
		}
	}

	@Test
	fun falloffCurvesMatchTheirFormulasAtMidpoint() {
		// fade = 0.5 at the midpoint; each curve's closed form evaluated by hand.
		assertEquals(0.5f, proportionalWeight(ProportionalFalloff.Smooth, 0.5f), 1e-6f, "smoothstep is 0.5 at its midpoint")
		assertEquals(sqrt(0.75f), proportionalWeight(ProportionalFalloff.Sphere, 0.5f), 1e-6f, "sphere is sqrt(1 - 0.25)")
		assertEquals(sqrt(0.5f), proportionalWeight(ProportionalFalloff.Root, 0.5f), 1e-6f, "root is sqrt(fade)")
		assertEquals(0.25f, proportionalWeight(ProportionalFalloff.Sharp, 0.5f), 1e-6f, "sharp is fade squared")
		assertEquals(0.5f, proportionalWeight(ProportionalFalloff.Linear, 0.5f), 1e-6f, "linear is fade")
		assertEquals(1f, proportionalWeight(ProportionalFalloff.Constant, 0.5f), "constant is full inside the radius")
	}

	@Test
	fun everyFalloffIsMonotonicallyNonIncreasing() {
		val sampleCount = 20
		for (falloff in allFalloffs) {
			var previousWeight = proportionalWeight(falloff, 0f)
			for (sampleIndex in 1..sampleCount) {
				val weight = proportionalWeight(falloff, sampleIndex / sampleCount.toFloat())
				assertTrue(weight <= previousWeight + 1e-6f, "$falloff must not increase with distance (sample $sampleIndex)")
				previousWeight = weight
			}
		}
	}

	// A horizontal strip of vertices spaced 10 units apart: (0,0), (10,0), (20,0), (30,0), (40,0).
	private val stripPositions = floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f, 40f, 0f)

	@Test
	fun weightsExcludeCoveredVerticesAndThoseOutsideTheRadius() {
		val weights = proportionalWeights(stripPositions, setOf(0), radiusWorld = 25f, falloff = ProportionalFalloff.Linear)
		assertFalse(0 in weights, "the covered vertex moves at full strength through its pivot group, not the weight map")
		assertEquals(setOf(1, 2), weights.keys, "only the vertices strictly inside the radius take a weight")
	}

	@Test
	fun weightsMeasureDistanceToTheNearestCoveredVertex() {
		// Covering both ends, the middle vertex is 20 from either end; the second and fourth are 10 from
		// their nearer end - the nearest covered vertex, not the first one iterated.
		val weights = proportionalWeights(stripPositions, setOf(0, 4), radiusWorld = 25f, falloff = ProportionalFalloff.Linear)
		assertEquals(1f - 10f / 25f, weights.getValue(1), 1e-6f, "vertex 1 is 10 from covered vertex 0")
		assertEquals(1f - 20f / 25f, weights.getValue(2), 1e-6f, "vertex 2 is 20 from either covered end")
		assertEquals(1f - 10f / 25f, weights.getValue(3), 1e-6f, "vertex 3 is 10 from covered vertex 4")
	}

	@Test
	fun weightsAreEmptyWithNoCoverageOrNoRadius() {
		assertTrue(proportionalWeights(stripPositions, emptySet(), 25f, ProportionalFalloff.Smooth).isEmpty())
		assertTrue(proportionalWeights(stripPositions, setOf(0), 0f, ProportionalFalloff.Smooth).isEmpty())
	}

	@Test
	fun influencesCarryTheNearestCoveredVertexIdentity() {
		// The identity is the Individual Origins ownership key: vertex 1 belongs with covered end 0,
		// vertex 3 with covered end 4 (each measured to its NEARER end).
		val influences = proportionalInfluences(stripPositions, setOf(0, 4), radiusWorld = 25f, falloff = ProportionalFalloff.Linear)
		assertEquals(0, influences.getValue(1).nearestCoveredIndex, "vertex 1's nearest covered vertex is 0")
		assertEquals(4, influences.getValue(3).nearestCoveredIndex, "vertex 3's nearest covered vertex is 4")
		assertEquals(1f - 10f / 25f, influences.getValue(1).weight, 1e-6f, "the weight rides along unchanged")
	}

	@Test
	fun connectedOnlyMeasuresAlongEdgesAndSkipsUnreachableIslands() {
		// A bent sheet: 0=(0,0), 1=(0,4), 2=(3,0), 3=(3,4); triangles (0,1,2) and (1,2,3).  Vertex 3 is
		// 5 from vertex 0 straight-line but 7 along edges (0-1-3 or 0-2-3; there is no 0-3 edge).
		val positions = floatArrayOf(0f, 0f, 0f, 4f, 3f, 0f, 3f, 4f)
		val indices = intArrayOf(0, 1, 2, 1, 2, 3)
		val straight = proportionalInfluences(positions, setOf(0), radiusWorld = 6f, falloff = ProportionalFalloff.Linear)
		val connected = proportionalInfluencesConnected(positions, setOf(0), radiusWorld = 6f, falloff = ProportionalFalloff.Linear, triangleIndices = indices)
		assertTrue(3 in straight, "straight-line influence reaches vertex 3 (distance 5, radius 6)")
		assertFalse(3 in connected, "along edges vertex 3 is 7 away - outside the radius")
		assertEquals(1f - 4f / 6f, connected.getValue(1).weight, 1e-6f, "a direct edge matches the straight-line distance")
		assertEquals(0, connected.getValue(1).nearestCoveredIndex, "the geodesic source rides along")

		// A nearby-but-unconnected island takes no weight at ANY radius (the feature's point).
		val twoIslands = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 5f, 0f, 7f, 0f, 5f, 2f)
		val islandIndices = intArrayOf(0, 1, 2, 3, 4, 5)
		val islandConnected =
			proportionalInfluencesConnected(twoIslands, setOf(0), radiusWorld = 100f, falloff = ProportionalFalloff.Constant, triangleIndices = islandIndices)
		assertEquals(setOf(1, 2), islandConnected.keys, "influence never leaps to the unconnected island")
	}

	@Test
	fun influencesPartitionByTheOwningPivotGroup() {
		// Two single-vertex "islands" at the strip's ends: each influenced vertex must follow the group
		// owning its nearest covered vertex, so the halos turn about their own island pivots.
		val influences = proportionalInfluences(stripPositions, setOf(0, 4), radiusWorld = 25f, falloff = ProportionalFalloff.Linear)
		val groups =
			listOf(
				TransformPivotGroup(setOf(0), pivotX = 0f, pivotY = 0f),
				TransformPivotGroup(setOf(4), pivotX = 40f, pivotY = 0f),
			)
		val partitions = TransformPivots.partitionInfluencesByGroup(influences, groups)
		assertEquals(setOf(1, 2), partitions[0].keys, "vertices nearest end 0 follow the first island (2 ties to 0, iterated first)")
		assertEquals(setOf(3), partitions[1].keys, "vertex 3 follows the second island")
		assertEquals(influences.getValue(3).weight, partitions[1].getValue(3), "weights carry into the partitions")
	}
}
