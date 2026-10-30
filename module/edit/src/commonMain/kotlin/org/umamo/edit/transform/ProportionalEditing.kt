package org.umamo.edit.transform

import org.umamo.edit.ElementTopology
import org.umamo.edit.ProportionalFalloff
import kotlin.math.sqrt

/* Proportional editing (Blender's O): a modal transform also pulls the UNSELECTED vertices within an
 * influence radius, each weighted by a falloff curve over its distance to the nearest selected vertex,
 * so mesh edits feel like sculpting instead of vertex-by-vertex CAD.  This file holds the pure math
 * (a falloff's weight over a distance and the influence maps); the state types live in
 * ProportionalEditState.kt, the session carries the on/off state, and the gizmo overlay feeds the weights
 * into the weighted MeshTransforms variants. */

/**
 * The falloff weight at a normalized distance: 1 at the selection (distance 0), 0 at and beyond the
 * radius edge (distance >= 1), shaped in between by the curve.  The formulas match Blender's
 * proportional falloffs over fade = 1 - distance: Smooth is the smoothstep 3·fade² - 2·fade³, Sphere is
 * the quarter-circle sqrt(1 - distance²), Root is sqrt(fade), Inverse Square is fade·(2 - fade) (that
 * is, 1 - distance²), Sharp is fade², Linear is fade, Constant is 1 everywhere inside the radius, and
 * Random is fade scaled by a per-vertex value in [0, 1).
 *
 * Random's per-vertex value is a hash of the vertex index rather than a draw from a generator, so it is
 * the same every time the influence map is derived: the map is re-derived when the wheel resizes the
 * radius mid-gesture and when the operation strip adjusts the transform, and a fresh draw each time would
 * reshuffle the halo under the rigger.  Blender draws afresh; here only the fade rescales.
 *
 * @param ProportionalFalloff falloff The falloff curve.
 * @param Float normalizedDistance The vertex's distance to the nearest selected vertex, divided by the
 *   influence radius (values below 0 clamp to full weight).
 * @param Int vertexIndex The vertex the weight is for; only Random reads it.
 * @return Float The weight in [0, 1].
 */
fun proportionalWeight(falloff: ProportionalFalloff, normalizedDistance: Float, vertexIndex: Int): Float {
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
		ProportionalFalloff.InverseSquare -> fade * (2f - fade)
		ProportionalFalloff.Sharp -> fade * fade
		ProportionalFalloff.Linear -> fade
		ProportionalFalloff.Constant -> 1f
		ProportionalFalloff.Random -> fade * randomFalloffSample(vertexIndex)
	}
}

/**
 * The Random falloff's per-vertex value: a 32-bit integer hash of the vertex index (an xorshift-multiply
 * avalanche), so neighboring indices land far apart, mapped to [0, 1) from its top 24 bits.  Stateless, so
 * the same vertex always gets the same value and the influence builders can run on any thread.
 *
 * @param Int vertexIndex The vertex.
 * @return Float The vertex's value in [0, 1).
 */
internal fun randomFalloffSample(vertexIndex: Int): Float {
	var hash = vertexIndex
	hash = hash xor (hash ushr 16)
	hash *= 0x7feb352d
	hash = hash xor (hash ushr 15)
	hash *= 0x846ca68b.toInt()
	hash = hash xor (hash ushr 16)

	return (hash ushr 8) / RANDOM_FALLOFF_SAMPLE_SPAN
}

/** The span the Random falloff's 24 hash bits divide by: 2^24, so the value stays below 1. */
private const val RANDOM_FALLOFF_SAMPLE_SPAN = 16_777_216f

/**
 * One influenced vertex's proportional-editing sample: its falloff weight and the pivot it scales and turns
 * about - the pivot of the group that OWNS the covered vertex its distance was measured against.  With
 * Individual Origins that is the owning island's own centroid, so the halo around an island turns with
 * it even when the island lives in another mesh; every other pivot mode has one shared group per mesh,
 * whose pivot is the gesture anchor.
 *
 * @property Float weight The falloff weight in (0, 1].
 * @property Float pivotX The pivot's x, in the positions' coordinate space.
 * @property Float pivotY The pivot's y.
 */
data class ProportionalInfluence(
	val weight: Float,
	val pivotX: Float,
	val pivotY: Float,
)

/**
 * One mesh a proportional halo is measured over: its positions, in the space every mesh measured with it
 * shares, and the pivot groups its covered vertices turn about.  The groups are the halo's sources and the
 * vertices it leaves out; a mesh with nothing selected has none, so only another mesh's halo reaches it.
 *
 * @property FloatArray positions The interleaved vertex positions.
 * @property List<TransformPivotGroup> groups The pivot groups covering the mesh's selected vertices.
 */
class ProportionalMesh(
	val positions: FloatArray,
	val groups: List<TransformPivotGroup>,
)

/**
 * The straight-line proportional influence maps for meshes that share one space, index-parallel to
 * [meshes]: every vertex not covered in its own mesh whose distance to the nearest covered vertex of ANY
 * of the meshes is inside [radiusWorld] maps to its falloff weight and the pivot of the group owning that
 * covered vertex.  Measuring across the meshes is what lets the halo reach nearby geometry with nothing
 * selected (Blender's multi-object proportional editing); Connected Only stays inside one mesh
 * ([proportionalInfluencesConnected]).  Covered vertices (they move at full strength through their pivot
 * groups) and vertices outside the radius are absent.
 *
 * The nearest-covered scan is O(covered x candidates), the candidates being the vertices inside the covered
 * set's bounds grown by the radius - fine at rigging mesh sizes; an acceleration structure can slot in here
 * later without changing the contract.  A tie goes to the covered vertex listed first.
 *
 * @param List<ProportionalMesh> meshes The meshes, their positions in one shared space.
 * @param Float radiusWorld The influence radius, in the positions' units.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @return List<Map<Int, ProportionalInfluence>> Per mesh, vertex index to influence for every influenced
 *   vertex, parallel to [meshes].
 */
fun proportionalInfluences(
	meshes: List<ProportionalMesh>,
	radiusWorld: Float,
	falloff: ProportionalFalloff,
): List<Map<Int, ProportionalInfluence>> {
	val coveredCount = meshes.sumOf { mesh -> mesh.groups.sumOf { group -> group.vertexIndices.size } }

	if (coveredCount == 0 || radiusWorld <= 0f) {
		return meshes.map { emptyMap() }
	}

	// Every covered vertex of every mesh, flattened with the pivot it turns about, and the bounds a vertex
	// must fall inside to be within the radius of any of them.
	val coveredX = FloatArray(coveredCount)
	val coveredY = FloatArray(coveredCount)
	val coveredPivotX = FloatArray(coveredCount)
	val coveredPivotY = FloatArray(coveredCount)
	var reachMinX = Float.MAX_VALUE
	var reachMinY = Float.MAX_VALUE
	var reachMaxX = -Float.MAX_VALUE
	var reachMaxY = -Float.MAX_VALUE
	var coveredSlot = 0

	for (mesh in meshes) {
		for (group in mesh.groups) {
			for (vertexIndex in group.vertexIndices) {
				val x = mesh.positions[vertexIndex * 2]
				val y = mesh.positions[vertexIndex * 2 + 1]
				coveredX[coveredSlot] = x
				coveredY[coveredSlot] = y
				coveredPivotX[coveredSlot] = group.pivotX
				coveredPivotY[coveredSlot] = group.pivotY
				reachMinX = minOf(reachMinX, x - radiusWorld)
				reachMinY = minOf(reachMinY, y - radiusWorld)
				reachMaxX = maxOf(reachMaxX, x + radiusWorld)
				reachMaxY = maxOf(reachMaxY, y + radiusWorld)
				coveredSlot++
			}
		}
	}

	val radiusSquared = radiusWorld * radiusWorld

	return meshes.map { mesh ->
		val vertexCount = mesh.positions.size / 2
		val coveredHere = BooleanArray(vertexCount)

		for (group in mesh.groups) {
			for (vertexIndex in group.vertexIndices) {
				coveredHere[vertexIndex] = true
			}
		}

		val influences = LinkedHashMap<Int, ProportionalInfluence>()

		for (vertexIndex in 0 until vertexCount) {
			val vertexX = mesh.positions[vertexIndex * 2]
			val vertexY = mesh.positions[vertexIndex * 2 + 1]

			if (coveredHere[vertexIndex] || vertexX < reachMinX || vertexX > reachMaxX || vertexY < reachMinY || vertexY > reachMaxY) {
				continue
			}

			var nearestSquared = Float.MAX_VALUE
			var nearestSlot = -1

			for (candidateSlot in 0 until coveredCount) {
				val deltaX = coveredX[candidateSlot] - vertexX
				val deltaY = coveredY[candidateSlot] - vertexY
				val distanceSquared = deltaX * deltaX + deltaY * deltaY

				if (distanceSquared < nearestSquared) {
					nearestSquared = distanceSquared
					nearestSlot = candidateSlot
				}
			}

			if (nearestSlot >= 0 && nearestSquared < radiusSquared) {
				val weight = proportionalWeight(falloff, sqrt(nearestSquared) / radiusWorld, vertexIndex)

				if (weight > 0f) {
					influences[vertexIndex] = ProportionalInfluence(weight, coveredPivotX[nearestSlot], coveredPivotY[nearestSlot])
				}
			}
		}

		influences
	}
}

/**
 * One mesh's proportional weight map, measured against its own covered vertices alone - [proportionalInfluences]
 * reduced to the weights, for callers that need no pivot.
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
): Map<Int, Float> =
	proportionalInfluences(listOf(ProportionalMesh(positions, TransformPivots.sharedGroup(coveredIndices, 0f, 0f))), radiusWorld, falloff)
		.single()
		.mapValues { entry -> entry.value.weight }

/**
 * The CONNECTED-ONLY proportional influence map for one mesh: like [proportionalInfluences], but
 * distance is geodesic - the shortest path along mesh edges (euclidean edge lengths) from the mesh's own
 * covered vertices - so influence flows through the surface and never leaps a gap to nearby-but-unconnected
 * geometry, in this mesh or another (Blender's Connected Only).  A vertex unreachable within [radiusWorld]
 * takes no weight, and each influenced vertex takes the pivot of the group its shortest path started from.
 *
 * Dijkstra over the vertex adjacency, seeded at every covered vertex, with an early stop past the
 * radius.  The frontier scan is O(V) per settled vertex (no priority queue in common Kotlin) - fine
 * at rigging mesh sizes, and the radius bound keeps the settled set local in practice.
 *
 * @param FloatArray positions The interleaved vertex positions (edge lengths measured here).
 * @param List<TransformPivotGroup> groups The pivot groups covering the selected vertices (the geodesic
 *   sources); a covered vertex in two groups belongs to the first.
 * @param Float radiusWorld The influence radius, along edges, in the positions' units.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @param ElementTopology topology The mesh's topology (the edge graph), sized to [positions].
 * @return Map<Int, ProportionalInfluence> Vertex index to influence, for every reachable vertex.
 */
fun proportionalInfluencesConnected(
	positions: FloatArray,
	groups: List<TransformPivotGroup>,
	radiusWorld: Float,
	falloff: ProportionalFalloff,
	topology: ElementTopology,
): Map<Int, ProportionalInfluence> {
	if (radiusWorld <= 0f || groups.all { group -> group.vertexIndices.isEmpty() }) {
		return emptyMap()
	}

	val vertexCount = positions.size / 2
	val adjacency = topology.adjacency
	val distance = FloatArray(vertexCount) { Float.MAX_VALUE }
	// The group each vertex's shortest path started from, -1 while unreached.
	val sourceGroup = IntArray(vertexCount) { -1 }
	val covered = BooleanArray(vertexCount)
	val settled = BooleanArray(vertexCount)

	for ((groupIndex, group) in groups.withIndex()) {
		for (coveredIndex in group.vertexIndices) {
			if (coveredIndex in 0 until vertexCount && !covered[coveredIndex]) {
				covered[coveredIndex] = true
				distance[coveredIndex] = 0f
				sourceGroup[coveredIndex] = groupIndex
			}
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

		for (neighbor in adjacency.getOrElse(frontier) { IntArray(0) }) {
			if (settled[neighbor]) {
				continue
			}

			val deltaX = positions[neighbor * 2] - positions[frontier * 2]
			val deltaY = positions[neighbor * 2 + 1] - positions[frontier * 2 + 1]
			val candidate = frontierDistance + sqrt(deltaX * deltaX + deltaY * deltaY)

			if (candidate < distance[neighbor]) {
				distance[neighbor] = candidate
				sourceGroup[neighbor] = sourceGroup[frontier]
			}
		}
	}

	val influences = LinkedHashMap<Int, ProportionalInfluence>()

	for (vertexIndex in 0 until vertexCount) {
		if (covered[vertexIndex] || sourceGroup[vertexIndex] < 0 || distance[vertexIndex] >= radiusWorld) {
			continue
		}

		val weight = proportionalWeight(falloff, distance[vertexIndex] / radiusWorld, vertexIndex)

		if (weight > 0f) {
			val group = groups[sourceGroup[vertexIndex]]
			influences[vertexIndex] = ProportionalInfluence(weight, group.pivotX, group.pivotY)
		}
	}

	return influences
}