package org.umamo.edit.transform

import org.umamo.edit.MeshTopology

/**
 * One pivot group of a modal transform: a set of vertices turning about one pivot.  Median / Active /
 * Cursor pivot modes produce a single group per mesh (every covered vertex about the one shared
 * anchor); IndividualOrigins produces one group per connectivity island (edit mode) or per drawable
 * (object mode), each about its own centroid.
 *
 * @property Set<Int> vertexIndices The group's vertex indices (into the mesh's interleaved array).
 * @property Float pivotX The group pivot's x, in the positions' coordinate space.
 * @property Float pivotY The group pivot's y, in the positions' coordinate space.
 */
data class TransformPivotGroup(
	val vertexIndices: Set<Int>,
	val pivotX: Float,
	val pivotY: Float,
)

/**
 * Pure pivot-group builders for the modal transforms (the [TransformPivotMode] machinery).
 */
object TransformPivots {
	/**
	 * One group turning every covered vertex about a shared anchor - the shape Median Point, Active
	 * Element, and Cursor pivots all reduce to (they differ only in where the anchor is).
	 *
	 * @param Set<Int> coveredIndices The vertices the gesture moves.
	 * @param Float pivotX The shared anchor's x.
	 * @param Float pivotY The shared anchor's y.
	 * @return List<TransformPivotGroup> The single shared-pivot group.
	 */
	fun sharedGroup(coveredIndices: Set<Int>, pivotX: Float, pivotY: Float): List<TransformPivotGroup> =
		listOf(TransformPivotGroup(coveredIndices, pivotX, pivotY))

	/**
	 * Per-island groups for Individual Origins in edit mode: the covered vertices split into
	 * connectivity islands (components of the sub-graph the selection induces), each turning about its
	 * own centroid.
	 *
	 * @param FloatArray positions The mesh's interleaved positions (the pivots' coordinate space).
	 * @param Set<Int> coveredIndices The vertices the gesture moves.
	 * @param IntArray triangleIndices The mesh triangle vertex indices (for connectivity).
	 * @return List<TransformPivotGroup> One group per island.
	 */
	fun islandGroups(
		positions: FloatArray,
		coveredIndices: Set<Int>,
		triangleIndices: IntArray,
	): List<TransformPivotGroup> {
		val vertexCount = positions.size / 2
		val adjacency = MeshTopology.buildVertexAdjacency(vertexCount, triangleIndices)
		return MeshTopology.selectionIslands(adjacency, coveredIndices).map { island ->
			val pivot = MeshTransforms.medianPivot(positions, island)
			TransformPivotGroup(island, pivot.first, pivot.second)
		}
	}

	/**
	 * Splits a proportional influence map into per-group weight maps, index-parallel to [groups]: each
	 * influenced vertex follows the pivot group that OWNS its nearest covered vertex, so with Individual
	 * Origins the halo around an island turns about that island's pivot, not the shared gesture anchor.
	 * With a single shared group everything lands in it (whose pivot IS the gesture anchor), so the
	 * shared-pivot modes keep their behavior.  An influence whose nearest covered vertex is in no group
	 * (impossible today - the groups partition the covered set) falls into the first group rather than
	 * dropping motion.
	 *
	 * @param Map<Int, ProportionalInfluence> influences The influenced vertices (weight + nearest covered).
	 * @param List<TransformPivotGroup> groups The gesture's pivot groups.
	 * @return List<Map<Int, Float>> One weight map per group, parallel to [groups].
	 */
	fun partitionInfluencesByGroup(
		influences: Map<Int, ProportionalInfluence>,
		groups: List<TransformPivotGroup>,
	): List<Map<Int, Float>> {
		if (groups.isEmpty()) {
			return emptyList()
		}
		val partitions = List(groups.size) { LinkedHashMap<Int, Float>() }
		for ((vertexIndex, influence) in influences) {
			val ownerIndex = groups.indexOfFirst { group -> influence.nearestCoveredIndex in group.vertexIndices }
			partitions[if (ownerIndex >= 0) ownerIndex else 0][vertexIndex] = influence.weight
		}
		return partitions
	}
}