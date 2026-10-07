package org.umamo.edit.transform

import org.umamo.edit.MeshTopology
import org.umamo.edit.ProportionalFalloff
import kotlin.math.sqrt

/* Proportional editing (Blender's O): a modal transform also pulls the UNSELECTED vertices within an
 * influence radius, each weighted by a falloff curve over its distance to the nearest selected vertex,
 * so mesh edits feel like sculpting instead of vertex-by-vertex CAD.  This file holds the pure math
 * (a falloff's weight over a distance and the influence map); the state types live in
 * ProportionalEditState.kt, the session carries the on/off state, and the gizmo overlay feeds the weights
 * into the weighted MeshTransforms variants. */

/**
 * The falloff weight at a normalized distance: 1 at the selection (distance 0), 0 at and beyond the
 * radius edge (distance >= 1), shaped in between by the curve.  The formulas match Blender's
 * proportional falloffs over fade = 1 - distance: Smooth is the smoothstep 3·fade² - 2·fade³, Sphere is
 * the quarter-circle sqrt(1 - distance²), Root is sqrt(fade), Sharp is fade², Linear is fade, and
 * Constant is 1 everywhere inside the radius.
 *
 * @param ProportionalFalloff falloff The falloff curve.
 * @param Float normalizedDistance The vertex's distance to the nearest selected vertex, divided by the
 *   influence radius (values below 0 clamp to full weight).
 * @return Float The weight in [0, 1].
 */
fun proportionalWeight(falloff: ProportionalFalloff, normalizedDistance: Float): Float {
	if (normalizedDistance >= 1f) {
		return 0f
	}
	if (normalizedDistance <= 0f) {
		return 1f
	}
	val fade = 1f - normalizedDistance
	return when (falloff) {
		ProportionalFalloff.Smooth -> fade * fade * (3f - 2f * fade)
		ProportionalFalloff.Sphere -> sqrt(1f - normalizedDistance * normalizedDistance)
		ProportionalFalloff.Root -> sqrt(fade)
		ProportionalFalloff.Sharp -> fade * fade
		ProportionalFalloff.Linear -> fade
		ProportionalFalloff.Constant -> 1f
	}
}

/**
 * One influenced vertex's proportional-editing sample: its falloff weight and the covered vertex it
 * measured its distance against.  The nearest-covered identity is what lets Individual Origins anchor
 * the influenced halo on the OWNING island's pivot instead of the shared gesture anchor - the weight
 * alone cannot say which island a bystander vertex belongs with.
 *
 * @property Float weight The falloff weight in (0, 1].
 * @property Int nearestCoveredIndex The covered vertex this vertex measured its distance to.
 */
data class ProportionalInfluence(
	val weight: Float,
	val nearestCoveredIndex: Int,
)

/**
 * The proportional influence map for one mesh: every vertex NOT in [coveredIndices] whose distance to
 * the nearest covered vertex is inside [radiusWorld] maps to its falloff weight plus that nearest
 * covered vertex; covered vertices (they move at full strength through the pivot groups) and vertices
 * outside the radius are absent.  The distances are measured in whatever space [positions] lives in -
 * the overlay passes the frozen world-posed shape, matching the world-unit radius.
 *
 * The nearest-covered scan is O(covered x uncovered), fine at rigging mesh sizes (hundreds of
 * vertices); an acceleration structure can slot in here later without changing the contract.
 *
 * @param FloatArray positions The interleaved vertex positions the distances are measured in.
 * @param Set<Int> coveredIndices The vertices the selection covers (excluded from the map).
 * @param Float radiusWorld The influence radius, in the positions' units.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @return Map<Int, ProportionalInfluence> Vertex index to influence, for every influenced vertex.
 */
fun proportionalInfluences(
	positions: FloatArray,
	coveredIndices: Set<Int>,
	radiusWorld: Float,
	falloff: ProportionalFalloff,
): Map<Int, ProportionalInfluence> {
	if (coveredIndices.isEmpty() || radiusWorld <= 0f) {
		return emptyMap()
	}
	val radiusSquared = radiusWorld * radiusWorld
	val influences = LinkedHashMap<Int, ProportionalInfluence>()
	val vertexCount = positions.size / 2
	for (vertexIndex in 0 until vertexCount) {
		if (vertexIndex in coveredIndices) {
			continue
		}
		val vertexX = positions[vertexIndex * 2]
		val vertexY = positions[vertexIndex * 2 + 1]
		var nearestSquared = Float.MAX_VALUE
		var nearestCovered = -1
		for (coveredIndex in coveredIndices) {
			val deltaX = positions[coveredIndex * 2] - vertexX
			val deltaY = positions[coveredIndex * 2 + 1] - vertexY
			val distanceSquared = deltaX * deltaX + deltaY * deltaY
			if (distanceSquared < nearestSquared) {
				nearestSquared = distanceSquared
				nearestCovered = coveredIndex
			}
		}
		if (nearestSquared < radiusSquared && nearestCovered >= 0) {
			val weight = proportionalWeight(falloff, sqrt(nearestSquared) / radiusWorld)
			if (weight > 0f) {
				influences[vertexIndex] = ProportionalInfluence(weight, nearestCovered)
			}
		}
	}
	return influences
}

/**
 * The proportional weight map for one mesh - [proportionalInfluences] reduced to the weights, for
 * callers that need no nearest-covered identity.
 *
 * @param FloatArray positions The interleaved vertex positions the distances are measured in.
 * @param Set<Int> coveredIndices The vertices the selection covers (excluded from the map).
 * @param Float radiusWorld The influence radius, in the positions' units.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @return Map<Int, Float> Vertex index to weight, for every influenced unselected vertex.
 */
fun proportionalWeights(
	positions: FloatArray,
	coveredIndices: Set<Int>,
	radiusWorld: Float,
	falloff: ProportionalFalloff,
): Map<Int, Float> = proportionalInfluences(positions, coveredIndices, radiusWorld, falloff).mapValues { entry -> entry.value.weight }

/**
 * The CONNECTED-ONLY proportional influence map for one mesh: like [proportionalInfluences], but
 * distance is geodesic - the shortest path along mesh edges (euclidean edge lengths) from the covered
 * set - so influence flows through the surface and never leaps a gap to nearby-but-unconnected
 * geometry (Blender's Connected Only).  A vertex unreachable within [radiusWorld] takes no weight,
 * and each influenced vertex reports the covered vertex its shortest path started from (the island
 * ownership key, exactly like the straight-line variant).
 *
 * Dijkstra over the vertex adjacency, seeded at every covered vertex, with an early stop past the
 * radius.  The frontier scan is O(V) per settled vertex (no priority queue in common Kotlin) - fine
 * at rigging mesh sizes, and the radius bound keeps the settled set local in practice.
 *
 * @param FloatArray positions The interleaved vertex positions (edge lengths measured here).
 * @param Set<Int> coveredIndices The vertices the selection covers (the geodesic sources).
 * @param Float radiusWorld The influence radius, along edges, in the positions' units.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @param IntArray triangleIndices The mesh's triangle vertex indices (the edge graph).
 * @return Map<Int, ProportionalInfluence> Vertex index to influence, for every reachable vertex.
 */
fun proportionalInfluencesConnected(
	positions: FloatArray,
	coveredIndices: Set<Int>,
	radiusWorld: Float,
	falloff: ProportionalFalloff,
	triangleIndices: IntArray,
): Map<Int, ProportionalInfluence> {
	if (coveredIndices.isEmpty() || radiusWorld <= 0f) {
		return emptyMap()
	}
	val vertexCount = positions.size / 2
	val adjacency = MeshTopology.buildVertexAdjacency(vertexCount, triangleIndices)
	val distance = FloatArray(vertexCount) { Float.MAX_VALUE }
	val sourceCovered = IntArray(vertexCount) { -1 }
	val settled = BooleanArray(vertexCount)
	for (coveredIndex in coveredIndices) {
		if (coveredIndex in 0 until vertexCount) {
			distance[coveredIndex] = 0f
			sourceCovered[coveredIndex] = coveredIndex
		}
	}
	while (true) {
		// The unsettled frontier vertex nearest the covered set; past the radius nothing can gain weight.
		var frontier = -1
		var frontierDistance = Float.MAX_VALUE
		for (vertexIndex in 0 until vertexCount) {
			if (!settled[vertexIndex] && distance[vertexIndex] < frontierDistance) {
				frontierDistance = distance[vertexIndex]
				frontier = vertexIndex
			}
		}
		if (frontier < 0 || frontierDistance >= radiusWorld) {
			break
		}
		settled[frontier] = true
		for (neighbor in adjacency[frontier]) {
			if (settled[neighbor]) {
				continue
			}
			val deltaX = positions[neighbor * 2] - positions[frontier * 2]
			val deltaY = positions[neighbor * 2 + 1] - positions[frontier * 2 + 1]
			val candidate = frontierDistance + sqrt(deltaX * deltaX + deltaY * deltaY)
			if (candidate < distance[neighbor]) {
				distance[neighbor] = candidate
				sourceCovered[neighbor] = sourceCovered[frontier]
			}
		}
	}
	val influences = LinkedHashMap<Int, ProportionalInfluence>()
	for (vertexIndex in 0 until vertexCount) {
		if (vertexIndex in coveredIndices || sourceCovered[vertexIndex] < 0 || distance[vertexIndex] >= radiusWorld) {
			continue
		}
		val weight = proportionalWeight(falloff, distance[vertexIndex] / radiusWorld)
		if (weight > 0f) {
			influences[vertexIndex] = ProportionalInfluence(weight, sourceCovered[vertexIndex])
		}
	}
	return influences
}