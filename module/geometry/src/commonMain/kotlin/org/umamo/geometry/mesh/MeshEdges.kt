package org.umamo.geometry.mesh

import org.umamo.geometry.packVertexPair

/**
 * Visits every edge of the mesh once: a boundary edge (a triangle on one side only) in its triangle's
 * winding direction, an interior edge from its lower-indexed vertex.  The one place that decides which
 * edges are boundary, so a clearance check and a preview that draws the outline cannot disagree.
 *
 * @param Function action Receives each edge's start, end, and whether it is a boundary edge.
 */
public inline fun PlanarTriangleMesh.forEachEdge(action: (start: Int, end: Int, isBoundary: Boolean) -> Unit) {
	val directed = directedEdgeKeys()

	for (entry in triangles.indices) {
		val start = triangles[entry]
		val end = triangles[nextCornerEntry(entry)]
		val isBoundary = packVertexPair(end, start) !in directed

		if (isBoundary || start < end) {
			action(start, end, isBoundary)
		}
	}
}

/**
 * The keys of every directed edge of the mesh's triangles.
 *
 * @return HashSet<Long> The keys, [packVertexPair] of each triangle's corner and the corner after it.
 */
@PublishedApi
internal fun PlanarTriangleMesh.directedEdgeKeys(): HashSet<Long> {
	val directed = HashSet<Long>(triangles.size * 2)

	for (entry in triangles.indices) {
		directed.add(packVertexPair(triangles[entry], triangles[nextCornerEntry(entry)]))
	}

	return directed
}

/**
 * The next corner of the same triangle.
 *
 * @param Int entry An index into a triangle array.
 * @return Int The next corner's index.
 */
@PublishedApi
internal fun nextCornerEntry(entry: Int): Int = if (entry % 3 == 2) entry - 2 else entry + 1