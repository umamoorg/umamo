package org.umamo.edit.transform

import org.umamo.edit.ElementTopology
import org.umamo.edit.ProportionalFalloff
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Tests for the proportional-editing math: the falloff curves and the influence maps.

class ProportionalEditingTest {
	private val allFalloffs = ProportionalFalloff.entries

	@Test
	fun everyFalloffIsFullAtZeroAndZeroAtRadiusEdge() {
		for (falloff in allFalloffs) {
			assertEquals(1f, proportionalWeight(falloff, 0f, ANY_VERTEX), "$falloff at distance 0")
			assertEquals(0f, proportionalWeight(falloff, 1f, ANY_VERTEX), "$falloff at the radius edge")
			assertEquals(0f, proportionalWeight(falloff, 1.5f, ANY_VERTEX), "$falloff beyond the radius")
			assertEquals(1f, proportionalWeight(falloff, -0.5f, ANY_VERTEX), "$falloff clamps negative distances to full weight")
		}
	}

	@Test
	fun falloffCurvesMatchTheirFormulasAtMidpoint() {
		// fade = 0.5 at the midpoint; each curve's closed form evaluated by hand.
		assertEquals(0.5f, proportionalWeight(ProportionalFalloff.Smooth, 0.5f, ANY_VERTEX), 1e-6f, "smoothstep is 0.5 at its midpoint")
		assertEquals(sqrt(0.75f), proportionalWeight(ProportionalFalloff.Sphere, 0.5f, ANY_VERTEX), 1e-6f, "sphere is sqrt(1 - 0.25)")
		assertEquals(sqrt(0.5f), proportionalWeight(ProportionalFalloff.Root, 0.5f, ANY_VERTEX), 1e-6f, "root is sqrt(fade)")
		assertEquals(0.75f, proportionalWeight(ProportionalFalloff.InverseSquare, 0.5f, ANY_VERTEX), 1e-6f, "inverse square is 1 - distance squared")
		assertEquals(0.25f, proportionalWeight(ProportionalFalloff.Sharp, 0.5f, ANY_VERTEX), 1e-6f, "sharp is fade squared")
		assertEquals(0.5f, proportionalWeight(ProportionalFalloff.Linear, 0.5f, ANY_VERTEX), 1e-6f, "linear is fade")
		assertEquals(1f, proportionalWeight(ProportionalFalloff.Constant, 0.5f, ANY_VERTEX), "constant is full inside the radius")
	}

	/** Random scales the fade by a per-vertex value: never above the fade, the same for a vertex every time. */
	@Test
	fun randomStaysUnderTheFadeAndRepeatsPerVertex() {
		for (vertexIndex in 0 until RANDOM_SAMPLE_COUNT) {
			val weight = proportionalWeight(ProportionalFalloff.Random, 0.5f, vertexIndex)
			assertTrue(weight in 0f..0.5f, "vertex $vertexIndex weighs within [0, fade], got $weight")
			assertEquals(weight, proportionalWeight(ProportionalFalloff.Random, 0.5f, vertexIndex), "vertex $vertexIndex repeats its weight")
		}
	}

	/** Random's per-vertex values spread across [0, 1) instead of clustering, so the halo reads as noise. */
	@Test
	fun randomSamplesSpreadAcrossTheUnitRange() {
		val samples = (0 until RANDOM_SAMPLE_COUNT).map { vertexIndex -> randomFalloffSample(vertexIndex) }
		assertTrue(samples.all { sample -> sample >= 0f && sample < 1f }, "every sample is in [0, 1)")
		assertTrue(samples.toSet().size > RANDOM_SAMPLE_COUNT * 9 / 10, "neighboring vertices draw different values")
		assertTrue(samples.min() < 0.05f && samples.max() > 0.95f, "the samples reach both ends of the range")
		assertEquals(0.5f, samples.average().toFloat(), 0.05f, "the samples center on one half")
	}

	@Test
	fun everyFalloffIsMonotonicallyNonIncreasing() {
		val sampleCount = 20

		for (falloff in allFalloffs) {
			var previousWeight = proportionalWeight(falloff, 0f, ANY_VERTEX)

			for (sampleIndex in 1..sampleCount) {
				val weight = proportionalWeight(falloff, sampleIndex / sampleCount.toFloat(), ANY_VERTEX)
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
	fun influencesCarryTheOwningGroupsPivot() {
		// Two single-vertex groups at the strip's ends: vertex 1 belongs with end 0, vertex 3 with end 4 (each
		// measured to its NEARER end), and vertex 2, as far from both, with the group listed first.
		val groups =
			listOf(
				TransformPivotGroup(setOf(0), pivotX = 0f, pivotY = 0f),
				TransformPivotGroup(setOf(4), pivotX = 40f, pivotY = 0f),
			)
		val influences = proportionalInfluences(listOf(ProportionalMesh(stripPositions, groups)), radiusWorld = 25f, falloff = ProportionalFalloff.Linear).single()
		assertEquals(0f, influences.getValue(1).pivotX, "vertex 1 turns about the first group's pivot")
		assertEquals(0f, influences.getValue(2).pivotX, "the tie goes to the group listed first")
		assertEquals(40f, influences.getValue(3).pivotX, "vertex 3 turns about the second group's pivot")
		assertEquals(1f - 10f / 25f, influences.getValue(1).weight, 1e-6f, "the weight rides along unchanged")
	}

	@Test
	fun straightLineInfluenceReachesAcrossMeshes() {
		// The strip covers vertex 0; a second mesh covers a vertex at (40, 0) and a third has nothing selected.
		val strip = ProportionalMesh(stripPositions, TransformPivots.sharedGroup(setOf(0), 1f, 2f))
		val other = ProportionalMesh(floatArrayOf(40f, 0f, 70f, 0f), TransformPivots.sharedGroup(setOf(0), 3f, 4f))
		val bystander = ProportionalMesh(floatArrayOf(5f, 3f, 100f, 0f), emptyList())
		val (inStrip, inOther, inBystander) = proportionalInfluences(listOf(strip, other, bystander), radiusWorld = 25f, falloff = ProportionalFalloff.Linear)

		assertEquals(setOf(1, 2, 3, 4), inStrip.keys, "every uncovered strip vertex lies within the radius of a covered one")
		assertEquals(1f - 10f / 25f, inStrip.getValue(3).weight, 1e-6f, "vertex 3 measures to the other mesh's covered vertex, 10 away")
		assertEquals(3f to 4f, inStrip.getValue(3).pivotX to inStrip.getValue(3).pivotY, "and takes that mesh's pivot")
		assertEquals(1f, inStrip.getValue(4).weight, "the strip's far end sits on the other mesh's covered vertex, so it moves fully")
		assertEquals(1f to 2f, inStrip.getValue(1).pivotX to inStrip.getValue(1).pivotY, "vertex 1 keeps its own mesh's pivot")
		assertTrue(inOther.isEmpty(), "the other mesh's far vertex is 30 from the nearest covered vertex, past the radius")
		assertEquals(setOf(0), inBystander.keys, "a mesh with nothing selected is reached within the radius, and only there")
		assertEquals(1f to 2f, inBystander.getValue(0).pivotX to inBystander.getValue(0).pivotY, "it turns about the reaching group's pivot")
	}

	@Test
	fun straightLineInfluenceIsEmptyWithNothingCoveredAnywhere() {
		val halos = proportionalInfluences(listOf(ProportionalMesh(stripPositions, emptyList()), ProportionalMesh(stripPositions, emptyList())), 25f, ProportionalFalloff.Smooth)
		assertEquals(listOf(emptyMap<Int, ProportionalInfluence>(), emptyMap()), halos, "one empty halo per mesh")
	}

	@Test
	fun connectedOnlyMeasuresAlongEdgesAndSkipsUnreachableIslands() {
		// A bent sheet: 0=(0,0), 1=(0,4), 2=(3,0), 3=(3,4); triangles (0,1,2) and (1,2,3).  Vertex 3 is
		// 5 from vertex 0 straight-line but 7 along edges (0-1-3 or 0-2-3; there is no 0-3 edge).
		val positions = floatArrayOf(0f, 0f, 0f, 4f, 3f, 0f, 3f, 4f)
		val indices = intArrayOf(0, 1, 2, 1, 2, 3)
		val groups = TransformPivots.sharedGroup(setOf(0), 9f, 8f)
		val straight = proportionalInfluences(listOf(ProportionalMesh(positions, groups)), radiusWorld = 6f, falloff = ProportionalFalloff.Linear).single()
		val connected = proportionalInfluencesConnected(positions, groups, radiusWorld = 6f, falloff = ProportionalFalloff.Linear, topology = ElementTopology.ofTriangles(4, indices))
		assertTrue(3 in straight, "straight-line influence reaches vertex 3 (distance 5, radius 6)")
		assertFalse(3 in connected, "along edges vertex 3 is 7 away - outside the radius")
		assertEquals(1f - 4f / 6f, connected.getValue(1).weight, 1e-6f, "a direct edge matches the straight-line distance")
		assertEquals(9f to 8f, connected.getValue(1).pivotX to connected.getValue(1).pivotY, "the source group's pivot rides along")

		// A nearby-but-unconnected island takes no weight at ANY radius (the feature's point).
		val twoIslands = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 5f, 0f, 7f, 0f, 5f, 2f)
		val islandIndices = intArrayOf(0, 1, 2, 3, 4, 5)
		val islandTopology = ElementTopology.ofTriangles(6, islandIndices)
		val islandConnected =
			proportionalInfluencesConnected(twoIslands, TransformPivots.sharedGroup(setOf(0), 0f, 0f), radiusWorld = 100f, falloff = ProportionalFalloff.Constant, topology = islandTopology)
		assertEquals(setOf(1, 2), islandConnected.keys, "influence never leaps to the unconnected island")
		assertTrue(
			proportionalInfluencesConnected(twoIslands, emptyList(), radiusWorld = 100f, falloff = ProportionalFalloff.Constant, topology = islandTopology).isEmpty(),
			"a mesh with nothing selected has no geodesic source",
		)
	}

	@Test
	fun connectedOnlyTakesThePivotOfTheIslandItsPathStartedFrom() {
		// Two islands in one mesh, each covered at one corner and grouped about its own pivot.
		val twoIslands = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 5f, 0f, 7f, 0f, 5f, 2f)
		val groups =
			listOf(
				TransformPivotGroup(setOf(0), pivotX = -1f, pivotY = 0f),
				TransformPivotGroup(setOf(3), pivotX = 6f, pivotY = 1f),
			)
		val connected = proportionalInfluencesConnected(twoIslands, groups, radiusWorld = 100f, falloff = ProportionalFalloff.Constant, topology = ElementTopology.ofTriangles(6, intArrayOf(0, 1, 2, 3, 4, 5)))
		assertEquals(setOf(1, 2, 4, 5), connected.keys)
		assertEquals(-1f, connected.getValue(2).pivotX, "the first island's halo turns about its pivot")
		assertEquals(6f, connected.getValue(4).pivotX, "the second island's halo turns about its own")
	}

	private companion object {
		/** A vertex index for the curves that ignore it. */
		const val ANY_VERTEX = 7

		/** How many vertices the Random checks sample. */
		const val RANDOM_SAMPLE_COUNT = 1000
	}
}