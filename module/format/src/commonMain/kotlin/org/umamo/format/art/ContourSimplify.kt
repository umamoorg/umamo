package org.umamo.format.art

import org.umamo.geometry.polyline.douglasPeuckerKeypoints

/**
 * Simplifies a closed lattice ring with Douglas-Peucker, returning a subset of its vertices.
 *
 * The ring is anchored at index 0, the tracer's deterministic topmost-then-leftmost start vertex, so
 * the output is deterministic.  Every kept vertex is an original lattice vertex on the exact mask
 * boundary, and the result deviates from that boundary by at most epsilon.  A result under 3 points
 * (a 1xN sliver's rectangle at epsilon 1 collapses to its two anchors) returns the unsimplified ring
 * instead.
 *
 * Known limitation, accepted for v1: on adversarial shapes Douglas-Peucker can produce a
 * self-intersecting simplified ring.  Callers needing a guaranteed-simple polygon should pass a smaller
 * epsilon (or 0) until a topology-preserving variant exists.
 *
 * @param IntArray points  Flattened x0, y0, x1, y1, ... closed ring; last point connects to the first.
 * @param Float    epsilon Maximum allowed deviation from the input ring, in pixels; non-negative.
 * @return IntArray The simplified ring, or the input array itself when nothing can be dropped.
 */
internal fun simplifyClosedRing(points: IntArray, epsilon: Float): IntArray {
	val pointCount = points.size / 2

	// A triangle is already minimal: dropping any vertex would degenerate the ring.
	if (pointCount < 4) {
		return points
	}

	val kept = douglasPeuckerKeypoints(points, epsilon.toDouble(), closed = true, ringAnchor = 0)

	if (kept.size < 3 || kept.size == pointCount) {
		return points
	}

	val simplified = IntArray(kept.size * 2)

	for ((position, pointIndex) in kept.withIndex()) {
		simplified[position * 2] = points[pointIndex * 2]
		simplified[position * 2 + 1] = points[pointIndex * 2 + 1]
	}

	return simplified
}