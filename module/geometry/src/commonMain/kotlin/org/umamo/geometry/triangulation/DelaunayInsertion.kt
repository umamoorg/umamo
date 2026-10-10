package org.umamo.geometry.triangulation

/*
 * Incremental Delaunay triangulation (Lawson's insertion): locate each point, split the triangle or
 * edge it lands in, then flip edges until every edge is locally Delaunay again.  The ghost triangles
 * make insertion outside the hull the same operation as insertion inside it: a point beyond a hull
 * edge splits that edge's ghost triangle, and the flips that follow walk the hull outward.
 *
 * Ties never flip.  An edge whose opposite corner lies exactly on the circumcircle stays, and a hull
 * vertex collinear with its neighbors stays on the hull, so the result is deterministic and never
 * contains a zero-area triangle.
 */

/**
 * Builds the Delaunay triangulation of a plan's points.
 *
 * @param DoubleArray                         points             The points, x0, y0, x1, y1, ...
 * @param InsertionPlan                       plan               The aliasing and insertion order.
 * @param ((HalfEdgeTriangulation) -> Unit)? afterEachInsertion Called after the first triangle and after every insertion (tests validate here).
 * @return HalfEdgeTriangulation? The triangulation, or null when fewer than three points are distinct or every point is collinear.
 */
internal fun buildDelaunay(points: DoubleArray, plan: InsertionPlan, afterEachInsertion: ((HalfEdgeTriangulation) -> Unit)? = null): HalfEdgeTriangulation? {
	val order = plan.order

	if (order.size < 3) {
		return null
	}

	val triangulation = HalfEdgeTriangulation(points, points.size / 2)
	val firstVertex = order[0]
	val secondVertex = order[1]

	// The first triangle needs a third point off the line through the first two; collinear points
	// before it are inserted afterwards like any other.
	var thirdPosition = 2

	while (thirdPosition < order.size && triangulation.orient(firstVertex, secondVertex, order[thirdPosition]) == 0.0) {
		thirdPosition++
	}

	if (thirdPosition == order.size) {
		return null
	}

	val thirdVertex = order[thirdPosition]
	if (triangulation.orient(firstVertex, secondVertex, thirdVertex) > 0.0) {
		triangulation.bootstrap(firstVertex, secondVertex, thirdVertex)
	} else {
		triangulation.bootstrap(firstVertex, thirdVertex, secondVertex)
	}

	afterEachInsertion?.invoke(triangulation)
	val inserter = DelaunayInserter(triangulation)

	for (position in 2 until order.size) {
		if (position == thirdPosition) {
			continue
		}
		inserter.insert(order[position])
		afterEachInsertion?.invoke(triangulation)
	}

	return triangulation
}

/**
 * Inserts points one at a time into a triangulation that is already Delaunay.
 *
 * @property HalfEdgeTriangulation triangulation The triangulation grown in place.
 */
internal class DelaunayInserter(private val triangulation: HalfEdgeTriangulation) {
	private val pending = IntStack()

	/** The triangle the next walk starts from: the last one created, so Hilbert order keeps walks short. */
	private var walkStart: Int = 0

	/** What the last [locate] found: one of the LOCATED_* kinds. */
	private var locatedKind: Int = LOCATED_INSIDE

	/** The triangle (inside, outside) or half-edge (on an edge) the last [locate] found. */
	private var locatedAt: Int = 0

	/**
	 * Inserts one vertex and restores the Delaunay property around it.
	 *
	 * @param Int vertex A vertex not yet in the triangulation and not coincident with any that is.
	 */
	fun insert(vertex: Int) {
		locate(vertex)
		pending.clear()

		if (locatedKind == LOCATED_ON_EDGE) {
			triangulation.splitEdge(locatedAt, vertex, pending)
		} else {
			triangulation.splitTriangle(locatedAt, vertex, pending)
		}

		legalize()
		walkStart = triangulation.vertexHalfEdge[vertex] / 3
	}

	/**
	 * Finds where a vertex falls: inside a real triangle, on the open segment of an edge, or beyond a
	 * hull edge (inside that edge's ghost triangle).  A visibility walk from the last created
	 * triangle: cross any edge the vertex lies strictly to the right of, until none remains.  The walk
	 * terminates on a Delaunay triangulation; a step cap falls back to a scan all the same.
	 *
	 * @param Int vertex The vertex to locate.
	 */
	private fun locate(vertex: Int) {
		var triangle = walkStart

		if (triangulation.isGhostTriangle(triangle)) {
			triangle = triangulation.twin[triangulation.realEdgeOfGhost(triangle)] / 3
		}

		var entered = HalfEdgeTriangulation.NO_HALF_EDGE
		val stepLimit = 4 * triangulation.triangleCount + 16
		var steps = 0

		walk@ while (true) {
			steps++

			if (steps > stepLimit) {
				locateByScan(vertex)
				return
			}

			var zeroCount = 0
			var zeroEdge = HalfEdgeTriangulation.NO_HALF_EDGE
			val base = 3 * triangle

			for (offset in 0 until 3) {
				val halfEdge = base + offset

				if (halfEdge == entered) {
					// The vertex lies strictly left of the edge just crossed.
					continue
				}

				val side = triangulation.orient(triangulation.origin[halfEdge], triangulation.destination(halfEdge), vertex)

				if (side < 0.0) {
					val across = triangulation.twin[halfEdge]
					val acrossTriangle = across / 3
					if (triangulation.isGhostTriangle(acrossTriangle)) {
						locatedKind = LOCATED_OUTSIDE
						locatedAt = acrossTriangle
						return
					}
					triangle = acrossTriangle
					entered = across
					continue@walk
				}

				if (side == 0.0) {
					zeroCount++
					zeroEdge = halfEdge
				}
			}
			recordContainment(triangle, zeroCount, zeroEdge, vertex)
			return
		}
	}

	/**
	 * Locates a vertex by testing every triangle in slot order - the deterministic fallback when a
	 * walk exceeds its step cap.
	 *
	 * @param Int vertex The vertex to locate.
	 */
	private fun locateByScan(vertex: Int) {
		for (triangle in 0 until triangulation.triangleCount) {
			if (triangulation.isGhostTriangle(triangle)) {
				continue
			}

			var zeroCount = 0
			var zeroEdge = HalfEdgeTriangulation.NO_HALF_EDGE
			var outside = false

			for (offset in 0 until 3) {
				val halfEdge = 3 * triangle + offset
				val side = triangulation.orient(triangulation.origin[halfEdge], triangulation.destination(halfEdge), vertex)

				if (side < 0.0) {
					outside = true
					break
				}

				if (side == 0.0) {
					zeroCount++
					zeroEdge = halfEdge
				}
			}

			if (!outside) {
				recordContainment(triangle, zeroCount, zeroEdge, vertex)
				return
			}
		}

		for (triangle in 0 until triangulation.triangleCount) {
			if (!triangulation.isGhostTriangle(triangle)) {
				continue
			}

			val hullEdge = triangulation.realEdgeOfGhost(triangle)

			// The ghost's real edge runs against the hull, so "beyond the hull" is strictly to its left.
			if (triangulation.orient(triangulation.origin[hullEdge], triangulation.destination(hullEdge), vertex) > 0.0) {
				locatedKind = LOCATED_OUTSIDE
				locatedAt = triangle
				return
			}
		}

		error("vertex $vertex lies in no triangle")
	}

	/**
	 * Records a vertex found in a real triangle's closure.
	 *
	 * @param Int triangle  The triangle.
	 * @param Int zeroCount How many of its edges the vertex is collinear with.
	 * @param Int zeroEdge  The last such edge.
	 * @param Int vertex    The vertex, for the error message.
	 */
	private fun recordContainment(triangle: Int, zeroCount: Int, zeroEdge: Int, vertex: Int) {
		when (zeroCount) {
			0 -> {
				locatedKind = LOCATED_INSIDE
				locatedAt = triangle
			}
			1 -> {
				locatedKind = LOCATED_ON_EDGE
				locatedAt = zeroEdge
			}
			else -> error("vertex $vertex coincides with a corner of triangle $triangle; duplicates must be aliased first")
		}
	}

	/** Flips edges until every edge around the new vertex is locally Delaunay. */
	private fun legalize() {
		while (pending.isNotEmpty()) {
			val halfEdge = pending.pop()

			if (shouldFlip(halfEdge)) {
				val opposed = triangulation.twin[halfEdge]
				triangulation.flip(halfEdge)
				// The flip leaves the new vertex at the start of the same slot; its two new opposite
				// edges sit in the slot after it and the slot before its twin.
				pending.push(triangulation.next(halfEdge))
				pending.push(triangulation.previous(opposed))
			}
		}
	}

	/**
	 * Whether an edge opposite the newly inserted vertex must flip.  For a real triangle the test is
	 * incircle against the far corner (a hull edge never flips); for an edge between two ghost
	 * triangles it is whether the new vertex sees the far ghost's hull edge from outside, which is
	 * what extends the hull around a point inserted beyond it.
	 *
	 * @param Int halfEdge A half-edge whose triangle's third corner is the inserted vertex.
	 * @return Boolean True when the edge must flip.
	 */
	private fun shouldFlip(halfEdge: Int): Boolean {
		if (triangulation.constrained[halfEdge]) {
			return false
		}

		val ghost = triangulation.ghostVertex
		val start = triangulation.origin[halfEdge]
		val end = triangulation.destination(halfEdge)
		val inserted = triangulation.origin[triangulation.previous(halfEdge)]
		val far = triangulation.origin[triangulation.previous(triangulation.twin[halfEdge])]

		if (start != ghost && end != ghost) {
			if (far == ghost) {
				return false
			}
			return triangulation.inCircle(start, end, inserted, far) > 0.0
		}

		// Both triangles are ghosts.  Rotated to put the ghost last, the far one is (far, end, ghost)
		// or (start, far, ghost); its real edge is the hull edge seen from outside.
		return if (start == ghost) {
			triangulation.orient(far, end, inserted) > 0.0
		} else {
			triangulation.orient(start, far, inserted) > 0.0
		}
	}

	private companion object {
		/** The vertex is strictly inside a real triangle. */
		const val LOCATED_INSIDE = 0

		/** The vertex is on an edge's open segment. */
		const val LOCATED_ON_EDGE = 1

		/** The vertex is beyond the hull, inside a ghost triangle. */
		const val LOCATED_OUTSIDE = 2
	}
}