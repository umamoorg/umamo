package org.umamo.edit.transform

import org.umamo.edit.ElementTopology
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
	 * @param ElementTopology topology The mesh's topology (for connectivity).
	 * @return List<TransformPivotGroup> One group per island.
	 */
	fun islandGroups(
		positions: FloatArray,
		coveredIndices: Set<Int>,
		topology: ElementTopology,
	): List<TransformPivotGroup> =
		MeshTopology.selectionIslands(topology.adjacency, coveredIndices).map { island ->
			val pivot = MeshTransforms.medianPivot(positions, island)
			TransformPivotGroup(island, pivot.first, pivot.second)
		}
}