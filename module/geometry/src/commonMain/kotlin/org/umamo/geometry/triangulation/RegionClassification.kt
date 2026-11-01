package org.umamo.geometry.triangulation

/**
 * Which triangles lie inside the boundary rings, by the even-odd rule.
 *
 * A plain class: [inside] is an array.
 *
 * @property BooleanArray inside     Per triangle slot, true for a real triangle inside the region.
 * @property Boolean      consistent False when some ring was left open (a boundary edge was rejected), so the labelling below is only one of the readings.
 */
internal class RegionClassification(val inside: BooleanArray, val consistent: Boolean)

/**
 * Labels every triangle inside or outside the boundary rings with the even-odd rule, spreading
 * breadth-first from the ghost triangles, which lie outside every ring.  Parity is decided per EDGE
 * crossing, never at a vertex, so rings that touch at a corner (the alpha tracer's saddle corners)
 * classify correctly and two rings sharing an edge cancel there.
 *
 * @param HalfEdgeTriangulation triangulation The constrained triangulation, boundary counts set.
 * @return RegionClassification The inside flags and whether they are consistent.
 */
internal fun classifyRegions(triangulation: HalfEdgeTriangulation): RegionClassification {
	val triangleCount = triangulation.triangleCount
	val parity = IntArray(triangleCount) { UNVISITED }
	// Each triangle is queued exactly once, so a plain array is the whole queue.
	val queue = IntArray(triangleCount)
	var head = 0
	var tail = 0

	for (triangle in 0 until triangleCount) {
		if (triangulation.isGhostTriangle(triangle)) {
			parity[triangle] = 0
			queue[tail] = triangle
			tail++
		}
	}

	var consistent = true

	while (head < tail) {
		val triangle = queue[head]
		head++

		for (offset in 0 until 3) {
			val halfEdge = 3 * triangle + offset
			val neighbor = triangulation.twin[halfEdge] / 3
			val neighborParity = (parity[triangle] + triangulation.boundaryCount[halfEdge]) and 1

			if (parity[neighbor] == UNVISITED) {
				parity[neighbor] = neighborParity
				queue[tail] = neighbor
				tail++
			} else if (parity[neighbor] != neighborParity) {
				// Visited neighbors are rechecked so a ring left open by a rejected edge is detected; the
				// first parity reached stays, keeping the labelling deterministic.
				consistent = false
			}
		}
	}

	val inside = BooleanArray(triangleCount) { triangle -> parity[triangle] == 1 && !triangulation.isGhostTriangle(triangle) }

	return RegionClassification(inside, consistent)
}

/** A triangle no crossing has reached yet. */
private const val UNVISITED: Int = -1