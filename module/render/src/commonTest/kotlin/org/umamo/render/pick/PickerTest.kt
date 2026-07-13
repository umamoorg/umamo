package org.umamo.render.pick

import org.umamo.runtime.model.DrawableId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the pure picking math: point-in-triangle, barycentric weights, front-most-by-paint-order
 * ranking, the atlas-alpha coverage gate, the front-to-back candidate list, and centrality scoring.
 * All pure — alpha is supplied by an injected lambda, so no GL or textures are needed.
 */
class PickerTest {
	// A unit square at the origin, two triangles; UVs equal the positions (already in [0,1]).
	private val squarePositions = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
	private val squareUvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
	private val squareIndices = intArrayOf(0, 1, 2, 0, 2, 3)

	// An always-opaque sampler for the geometry-only cases.
	private val opaque: (DrawableId, Float, Float) -> Float = { _, _, _ -> 1f }

	/**
	 * A point well inside a triangle is contained.
	 */
	@Test
	fun pointInsideTriangle() {
		assertTrue(pointInTriangle(0.25f, 0.25f, 0f, 0f, 1f, 0f, 0f, 1f))
	}

	/**
	 * A point outside the triangle is not contained.
	 */
	@Test
	fun pointOutsideTriangle() {
		assertFalse(pointInTriangle(0.9f, 0.9f, 0f, 0f, 1f, 0f, 0f, 1f))
	}

	/**
	 * A point exactly on an edge counts as contained, and winding order does not matter.
	 */
	@Test
	fun pointOnEdgeIsInsideRegardlessOfWinding() {
		assertTrue(pointInTriangle(0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 1f))
		assertTrue(pointInTriangle(0.25f, 0.25f, 0f, 1f, 1f, 0f, 0f, 0f))
	}

	/**
	 * A degenerate (collinear) triangle contains points on its span but rejects points off the line.
	 */
	@Test
	fun degenerateTriangle() {
		assertTrue(pointInTriangle(0.5f, 0f, 0f, 0f, 1f, 0f, 2f, 0f))
		assertFalse(pointInTriangle(0.5f, 0.5f, 0f, 0f, 1f, 0f, 2f, 0f))
	}

	/**
	 * Barycentric weights sum to 1, give a unit weight at a vertex, and fall back to thirds when
	 * degenerate.
	 */
	@Test
	fun barycentricWeightsBasics() {
		val centroid = barycentricWeights(1f / 3f, 1f / 3f, 0f, 0f, 1f, 0f, 0f, 1f)
		assertEquals(1f, centroid[0] + centroid[1] + centroid[2], 1e-4f)

		val atFirst = barycentricWeights(0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f)
		assertEquals(1f, atFirst[0], 1e-4f)
		assertEquals(0f, atFirst[1], 1e-4f)
		assertEquals(0f, atFirst[2], 1e-4f)

		val degenerate = barycentricWeights(0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f)
		assertEquals(1f / 3f, degenerate[0], 1e-4f)
	}

	/**
	 * A point inside a single opaque drawable's mesh picks that drawable.
	 */
	@Test
	fun picksContainingDrawable() {
		val id = DrawableId("square")
		val hit =
			pickDrawable(
				0.5f,
				0.5f,
				mapOf(id to squarePositions),
				mapOf(id to squareIndices),
				mapOf(id to squareUvs),
				mapOf(id to 0f),
				opaque,
			)
		assertEquals(id, hit)
	}

	/**
	 * A point outside every mesh picks nothing.
	 */
	@Test
	fun pointOutsideAllReturnsNull() {
		val id = DrawableId("square")
		val hit =
			pickDrawable(
				5f,
				5f,
				mapOf(id to squarePositions),
				mapOf(id to squareIndices),
				mapOf(id to squareUvs),
				mapOf(id to 0f),
				opaque,
			)
		assertNull(hit)
	}

	/**
	 * Among overlapping opaque drawables, the highest front-rank (the renderer's resolved paint order,
	 * front = higher) wins — not the raw draw-order scalar, which ignores the group hierarchy.
	 */
	@Test
	fun overlappingPicksHighestFrontRank() {
		val under = DrawableId("under")
		val over = DrawableId("over")
		val hit =
			pickDrawable(
				0.5f,
				0.5f,
				mapOf(under to squarePositions, over to squarePositions),
				mapOf(under to squareIndices, over to squareIndices),
				mapOf(under to squareUvs, over to squareUvs),
				mapOf(under to 3f, over to 7f), // over is drawn later (more front)
				opaque,
			)
		assertEquals(over, hit)
	}

	/**
	 * A click on a transparent texel is rejected: the front mesh is transparent there, so the opaque mesh
	 * behind it is picked.  Inverting the alpha picks the front one.
	 */
	@Test
	fun transparentOverhangRejectedSoBehindWins() {
		val front = DrawableId("front")
		val back = DrawableId("back")
		val positions = mapOf(front to squarePositions, back to squarePositions)
		val indices = mapOf(front to squareIndices, back to squareIndices)
		val uvs = mapOf(front to squareUvs, back to squareUvs)
		val rank = mapOf(front to 9f, back to 1f) // front is more front, but transparent here

		val frontTransparent: (DrawableId, Float, Float) -> Float = { id, _, _ -> if (id == front) 0f else 1f }
		assertEquals(back, pickDrawable(0.5f, 0.5f, positions, indices, uvs, rank, frontTransparent))

		val frontOpaque: (DrawableId, Float, Float) -> Float = { id, _, _ -> if (id == front) 1f else 1f }
		assertEquals(front, pickDrawable(0.5f, 0.5f, positions, indices, uvs, rank, frontOpaque))
	}

	/**
	 * A drawable not in the front-rank map (not drawn this frame) is skipped.
	 */
	@Test
	fun drawableAbsentFromFrontRankSkipped() {
		val id = DrawableId("ghost")
		val hit =
			pickDrawable(
				0.5f,
				0.5f,
				mapOf(id to squarePositions),
				mapOf(id to squareIndices),
				mapOf(id to squareUvs),
				emptyMap(), // not drawn → no rank
				opaque,
			)
		assertNull(hit)
	}

	/**
	 * pickAllDrawables returns every opaque candidate front-to-back; a fully transparent one is absent.
	 */
	@Test
	fun pickAllIsFrontToBackAndAlphaGated() {
		val back = DrawableId("back")
		val mid = DrawableId("mid")
		val front = DrawableId("front")
		val positions = mapOf(back to squarePositions, mid to squarePositions, front to squarePositions)
		val indices = mapOf(back to squareIndices, mid to squareIndices, front to squareIndices)
		val uvs = mapOf(back to squareUvs, mid to squareUvs, front to squareUvs)
		val rank = mapOf(back to 0f, mid to 1f, front to 2f)
		// mid is transparent at the click, so it drops out.
		val sampler: (DrawableId, Float, Float) -> Float = { id, _, _ -> if (id == mid) 0f else 1f }

		val candidates =
			pickAllDrawables(0.5f, 0.5f, positions, indices, uvs, rank, atlasSizeOf = { null }, sampleAlpha = sampler)

		assertEquals(listOf(front, back), candidates.map { it.id }) // front-to-back, mid gated out
		assertTrue(candidates.all { it.centrality == 1f }) // untextured (null atlas size) ⇒ full centrality
	}

	/**
	 * Centrality is higher for a hit deep in the opaque region than for one near a transparent boundary.
	 */
	@Test
	fun centralityDeepBeatsBoundary() {
		val id = DrawableId("art")
		val centerU = 0.5f
		val centerV = 0.5f
		val radiusUv = 40f / 256f // opaque disc of ~40 texels radius on a 256² atlas
		val discField: (DrawableId, Float, Float) -> Float = { _, u, v ->
			val du = u - centerU
			val dv = v - centerV
			if (sqrt(du * du + dv * dv) <= radiusUv) 1f else 0f
		}

		val deep = centralityAt(id, centerU, centerV, 256, 256, discField)
		val nearEdge = centralityAt(id, centerU + 35f / 256f, centerV, 256, 256, discField)

		assertTrue(deep > nearEdge, "a central hit ($deep) should score above a near-boundary hit ($nearEdge)")
	}
}
