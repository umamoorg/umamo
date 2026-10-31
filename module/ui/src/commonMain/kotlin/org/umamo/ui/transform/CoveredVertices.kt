package org.umamo.ui.transform

import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner

/*
 * The vertices an Edit-mode selection covers and where their median sits in world space: what Shift+S snaps
 * from, what Frame Selected frames, and what the sidebar's Vertex / Median panel shows and moves - a mesh's
 * vertices or a lattice's points alike.
 */

/**
 * One owner's covered vertices: the vertices its selected elements span, with the world geometry they were
 * captured in.
 *
 * @property OwnerWorldGeometry geometry The owner's captured geometry: a mesh's or a lattice's.
 * @property ElementTopology topology The owner's topology (connectivity for a capture built over it).
 * @property Set<Int> indices The covered vertices, every one inside [geometry].
 */
internal class CoveredVertices(
	val geometry: OwnerWorldGeometry,
	val topology: ElementTopology,
	val indices: Set<Int>,
)

/**
 * The mean world position of a set of covered vertices.
 *
 * @property Float worldX The median's world x.
 * @property Float worldZ The median's world z (up).
 * @property Int vertexCount How many vertices it is the mean of.
 */
internal class WorldMedian(val worldX: Float, val worldZ: Float, val vertexCount: Int)

/**
 * The covered vertices of every owner in [geometries], dropping one that has none.
 *
 * An index at or past the captured vertex count is dropped too: the selection and the model can reach a reader
 * through separate collectors, so for a moment after a duplicate or a rip the selection may name vertices the
 * captured geometry does not have.  A set needing no such drop is kept as the topology returned it, so a sum over
 * it runs in the topology's order, the one a modal transform's capture sums the same vertices in.
 *
 * @param MeshSelection selection The mesh selection.
 * @param List<OwnerWorldGeometry> geometries The owners' captured geometry.
 * @param Map<ElementOwner, ElementTopology> topologyByOwner Each owner's topology, as captured with its geometry.
 * @return List<CoveredVertices> The owners with covered vertices, in [geometries] order.
 */
internal fun coveredVerticesOf(
	selection: MeshSelection,
	geometries: List<OwnerWorldGeometry>,
	topologyByOwner: Map<ElementOwner, ElementTopology>,
): List<CoveredVertices> =
	geometries.mapNotNull { geometry ->
		val elements = selection.elementsOf(geometry.owner)
		val topology = topologyByOwner[geometry.owner]

		if (elements.isEmpty() || topology == null) {
			return@mapNotNull null
		}

		val inside = MeshTopology.coveredVertexIndicesWithin(elements, topology, geometry.world.size / 2)

		if (inside.isEmpty()) null else CoveredVertices(geometry, topology, inside)
	}

/**
 * The median as a gesture's preview has the covered vertices.  A mesh's previewed base is carried onto the captured
 * displayed shape and through the captured deformer mapping - valid while the deformer chain holds still, which Edit
 * mode's pinned pose guarantees, and cheap, because only the covered vertices are mapped; a lattice's previewed
 * points are already in world space.  An owner the preview does not move, or one whose vertex count it changed,
 * counts at its captured world position.
 *
 * @param List<CoveredVertices> covered The covered vertices, as captured from the committed model.
 * @param Map<DrawableId, MeshRestPositions> restByDrawable The preview's new rest shape per moved mesh.
 * @param Map<DeformerId, FloatArray> latticeWorldByDeformer The preview's world points per moved lattice.
 * @return WorldMedian? The median, or null when nothing is covered.
 */
internal fun coveredMedian(
	covered: List<CoveredVertices>,
	restByDrawable: Map<DrawableId, MeshRestPositions> = emptyMap(),
	latticeWorldByDeformer: Map<DeformerId, FloatArray> = emptyMap(),
): WorldMedian? {
	var sumX = 0f
	var sumZ = 0f
	var count = 0

	for (owner in covered) {
		val packed =
			when (val geometry = owner.geometry) {
				is DrawableWorldGeometry -> {
					restByDrawable[geometry.drawableId]?.localPositions
						?.takeIf { base -> base.size == geometry.local.size }
						?.let { previewBase -> previewedWorld(geometry, previewBase, owner.indices) }
				}

				is LatticeWorldGeometry -> {
					latticeWorldByDeformer[geometry.deformerId]
						?.takeIf { previewWorld -> previewWorld.size == geometry.world.size }
						?.let { previewWorld -> packedWorld(previewWorld, owner.indices) }
				}
			}

		val points = packed ?: packedWorld(owner.geometry.world, owner.indices)
		var slotIndex = 0

		while (slotIndex + 1 < points.size) {
			sumX += points[slotIndex]
			sumZ += points[slotIndex + 1]
			slotIndex += 2
		}

		count += owner.indices.size
	}

	if (count == 0) {
		return null
	}

	return WorldMedian(sumX / count, sumZ / count, count)
}

/**
 * The world positions of [indices] with the base moved to [previewBase], packed in [indices] order.  A grid-less
 * mesh displays its base as it is; a keyformed one displays the base plus the neutral blend, which a positions-only
 * edit leaves unchanged, so the displayed shape moves by exactly what the base moved.
 *
 * @param DrawableWorldGeometry geometry The mesh's captured geometry.
 * @param FloatArray previewBase The previewed base, the same size as the captured one.
 * @param Set<Int> indices The vertices to map.
 * @return FloatArray Their world positions, two floats per index in iteration order.
 */
private fun previewedWorld(geometry: DrawableWorldGeometry, previewBase: FloatArray, indices: Set<Int>): FloatArray {
	val displayed = FloatArray(indices.size * 2)
	val gridless = geometry.displayed === geometry.local
	var slotIndex = 0

	for (vertexIndex in indices) {
		val xIndex = vertexIndex * 2

		if (gridless) {
			displayed[slotIndex] = previewBase[xIndex]
			displayed[slotIndex + 1] = previewBase[xIndex + 1]
		} else {
			displayed[slotIndex] = geometry.displayed[xIndex] + (previewBase[xIndex] - geometry.local[xIndex])
			displayed[slotIndex + 1] = geometry.displayed[xIndex + 1] + (previewBase[xIndex + 1] - geometry.local[xIndex + 1])
		}

		slotIndex += 2
	}

	return geometry.mapping.localToWorld(displayed)
}

/**
 * The world positions of [indices] in [world], packed in [indices] order.
 *
 * @param FloatArray world The world positions, interleaved x, z.
 * @param Set<Int> indices The vertices to read.
 * @return FloatArray Their positions, two floats per index in iteration order.
 */
private fun packedWorld(world: FloatArray, indices: Set<Int>): FloatArray {
	val packed = FloatArray(indices.size * 2)
	var slotIndex = 0

	for (vertexIndex in indices) {
		packed[slotIndex] = world[vertexIndex * 2]
		packed[slotIndex + 1] = world[vertexIndex * 2 + 1]
		slotIndex += 2
	}

	return packed
}