package org.umamo.geometry.polyline

import org.umamo.geometry.predicate.orient2d

/*
 * Which closed rings enclose which, decided exactly: point-in-ring by the even-odd rule with a ray
 * toward +x, where each straddling edge's side is read from orient2d instead of an interpolated
 * crossing, so no rounding can move a point across a ring.  The mesher uses it to keep only the
 * outline rings no other ring encloses when it fills an art layer's holes.
 */

/**
 * Whether a point lies inside a closed ring by the even-odd rule, decided exactly.
 *
 * Each edge counts when it straddles the point's horizontal line (one end strictly above it, the
 * other at or below) and passes to the point's right, so a ray through a vertex counts it once.  A
 * point exactly on the ring may land on either side.
 *
 * @param DoubleArray ring The ring's vertices, x0, y0, x1, y1, ..., closed implicitly.
 * @param Double      x    The point's x.
 * @param Double      y    The point's y.
 * @return Boolean True when the point is inside.
 */
public fun ringContainsPoint(ring: DoubleArray, x: Double, y: Double): Boolean {
	require(ring.size % 2 == 0) { "ring must hold x, y pairs, but has ${ring.size} components" }
	val vertexCount = ring.size / 2
	var inside = false

	for (vertex in 0 until vertexCount) {
		val next = if (vertex + 1 == vertexCount) 0 else vertex + 1
		val fromX = ring[2 * vertex]
		val fromY = ring[2 * vertex + 1]
		val toX = ring[2 * next]
		val toY = ring[2 * next + 1]

		if ((fromY > y) == (toY > y)) {
			continue
		}

		// Going toward larger y, the crossing lies right of the point exactly when the point lies to
		// the edge's left (orient2d positive); going the other way, to its right.
		val orientation = orient2d(fromX, fromY, toX, toY, x, y)
		val crossesToTheRight = if (toY > fromY) orientation > 0.0 else orientation < 0.0

		if (crossesToTheRight) {
			inside = !inside
		}
	}

	return inside
}

/**
 * The rings no other ring encloses, from a set of pairwise disjoint closed rings (no two share a
 * point) - the outer boundaries, without their holes or anything inside those holes.  Disjoint rings
 * nest wholly or not at all, so testing one vertex of a ring decides it.
 *
 * @param List<DoubleArray> rings The rings, each x0, y0, x1, y1, ..., with at least three vertices.
 * @return IntArray The indices of the outermost rings, ascending.
 */
public fun outermostRings(rings: List<DoubleArray>): IntArray {
	for ((index, ring) in rings.withIndex()) {
		require(ring.size % 2 == 0 && ring.size >= 6) { "ring $index must hold at least three x, y pairs, but has ${ring.size} components" }
	}

	// Each ring's bounding box, minimum x, minimum y, maximum x, maximum y: a ring can only enclose
	// a point inside its box.
	val boxes = DoubleArray(4 * rings.size)

	for ((index, ring) in rings.withIndex()) {
		var minimumX = Double.POSITIVE_INFINITY
		var minimumY = Double.POSITIVE_INFINITY
		var maximumX = Double.NEGATIVE_INFINITY
		var maximumY = Double.NEGATIVE_INFINITY

		for (vertex in 0 until ring.size / 2) {
			minimumX = minOf(minimumX, ring[2 * vertex])
			minimumY = minOf(minimumY, ring[2 * vertex + 1])
			maximumX = maxOf(maximumX, ring[2 * vertex])
			maximumY = maxOf(maximumY, ring[2 * vertex + 1])
		}

		boxes[4 * index] = minimumX
		boxes[4 * index + 1] = minimumY
		boxes[4 * index + 2] = maximumX
		boxes[4 * index + 3] = maximumY
	}

	val outermost = ArrayList<Int>()

	for ((index, ring) in rings.withIndex()) {
		val probeX = ring[0]
		val probeY = ring[1]
		var enclosed = false

		for (other in rings.indices) {
			if (other == index) {
				continue
			}

			val withinBox = probeX >= boxes[4 * other] && probeY >= boxes[4 * other + 1] && probeX <= boxes[4 * other + 2] && probeY <= boxes[4 * other + 3]

			if (withinBox && ringContainsPoint(rings[other], probeX, probeY)) {
				enclosed = true

				break
			}
		}

		if (!enclosed) {
			outermost.add(index)
		}
	}

	return outermost.toIntArray()
}