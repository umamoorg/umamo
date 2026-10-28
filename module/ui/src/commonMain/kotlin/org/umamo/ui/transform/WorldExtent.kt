package org.umamo.ui.transform

import org.umamo.edit.transform.MeshBounds

/**
 * The axis-aligned world box around a set of captured vertices, x right and z up: what Frame Selected fits
 * the camera to and what a part's Position and Size measure.
 *
 * @property Float minX The box's left edge.
 * @property Float minZ The box's bottom edge.
 * @property Float maxX The box's right edge.
 * @property Float maxZ The box's top edge.
 */
internal class WorldExtent(val minX: Float, val minZ: Float, val maxX: Float, val maxZ: Float) {
	/**
	 * The box as center and size, the shape the Transform rows show (MeshBounds' y is world z).
	 *
	 * @return MeshBounds The box's center and extents.
	 */
	fun toMeshBounds(): MeshBounds = MeshBounds((minX + maxX) / 2f, (minZ + maxZ) / 2f, maxX - minX, maxZ - minZ)
}

/**
 * Grows a box one captured vertex at a time.
 */
private class WorldExtentBuilder {
	private var minX = Float.POSITIVE_INFINITY
	private var minZ = Float.POSITIVE_INFINITY
	private var maxX = Float.NEGATIVE_INFINITY
	private var maxZ = Float.NEGATIVE_INFINITY

	/**
	 * Takes in one vertex of an interleaved world array.
	 *
	 * @param FloatArray world The world positions, interleaved x, z.
	 * @param Int vertexIndex The vertex to take in.
	 */
	fun include(world: FloatArray, vertexIndex: Int) {
		minX = minOf(minX, world[vertexIndex * 2])
		maxX = maxOf(maxX, world[vertexIndex * 2])
		minZ = minOf(minZ, world[vertexIndex * 2 + 1])
		maxZ = maxOf(maxZ, world[vertexIndex * 2 + 1])
	}

	/**
	 * The box grown so far.
	 *
	 * @return WorldExtent? The box, or null when no vertex was taken in.
	 */
	fun build(): WorldExtent? = if (minX > maxX) null else WorldExtent(minX, minZ, maxX, maxZ)
}

/**
 * The world box around every vertex of [geometries], each whole.
 *
 * @param List<DrawableWorldGeometry> geometries The captured meshes.
 * @return WorldExtent? The box, or null when the meshes hold no vertex.
 */
internal fun worldExtentOf(geometries: List<DrawableWorldGeometry>): WorldExtent? {
	val builder = WorldExtentBuilder()

	for (geometry in geometries) {
		for (vertexIndex in 0 until geometry.world.size / 2) {
			builder.include(geometry.world, vertexIndex)
		}
	}

	return builder.build()
}

/**
 * The world box around the covered vertices alone, at the positions they were captured at.
 *
 * @param List<CoveredVertices> covered The covered vertices.
 * @return WorldExtent? The box, or null when nothing is covered.
 */
internal fun coveredWorldExtent(covered: List<CoveredVertices>): WorldExtent? {
	val builder = WorldExtentBuilder()

	for (mesh in covered) {
		for (vertexIndex in mesh.indices) {
			builder.include(mesh.geometry.world, vertexIndex)
		}
	}

	return builder.build()
}