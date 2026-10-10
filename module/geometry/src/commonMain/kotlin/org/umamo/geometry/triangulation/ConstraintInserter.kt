package org.umamo.geometry.triangulation

/*
 * Constraint insertion into a finished Delaunay triangulation, by edge flips (Sloan 1993).
 *
 * Each constraint edge is first TRACED without touching anything: from its start, through the
 * triangles it crosses, to its end, splitting it at every vertex lying exactly on it.  If the trace
 * crosses an edge an earlier constraint already holds, the edge is rejected and the triangulation is
 * left exactly as it was.  Otherwise each piece is inserted: the edges it crosses are flipped away one
 * at a time, preferring an edge whose flip removes its crossing outright, and falling back to a step
 * of plain Sloan when none qualifies (a FIFO queue where a non-convex edge, or a new diagonal that
 * still crosses, goes to the back).  Lawson flips then restore the constrained Delaunay property over
 * the new edges.
 *
 * Flips move half-edges between slots, so every work list here holds VERTEX PAIRS and re-finds the
 * edge on each use; a slot number is never kept across a flip.
 */

/**
 * Inserts constraint edges into a triangulation that is Delaunay and holds every vertex already.
 *
 * @property HalfEdgeTriangulation triangulation                The triangulation constrained in place.
 * @property Boolean               prefersFlipsLeavingNoCrossing Whether to try a crossing-removing flip before plain Sloan; tests turn it off to exercise the fallback on its own.
 */
internal class ConstraintInserter(
	private val triangulation: HalfEdgeTriangulation,
	private val prefersFlipsLeavingNoCrossing: Boolean = true,
) {
	/** The vertices one traced constraint passes through, start and end included. */
	private val chain = ArrayList<Int>()

	/** The edges a piece crosses, as packed (right, left) vertex pairs ordered from its start. */
	private val crossings = ArrayList<Long>()

	/** The diagonals the flips created, as packed vertex pairs, to restore afterwards. */
	private val createdEdges = ArrayList<Long>()

	/** The restore pass's work list, as packed vertex pairs. */
	private val restorePending = ArrayList<Long>()

	/** One end of the constrained edge the last rejected trace ran into. */
	var crossedStart: Int = -1
		private set

	/** The other end of that edge. */
	var crossedEnd: Int = -1
		private set

	/**
	 * Inserts one constraint edge, splitting it at any vertex lying on it.
	 *
	 * @param Int     start      The edge's start (a canonical vertex).
	 * @param Int     end        The edge's end (a canonical vertex, distinct from [start]).
	 * @param Boolean isBoundary Whether the edge belongs to a boundary ring.
	 * @return Boolean True when inserted; false when it crosses an existing constraint ([crossedStart] and [crossedEnd] then name it) - the triangulation is then untouched.
	 */
	fun insert(start: Int, end: Int, isBoundary: Boolean): Boolean {
		if (!traceChain(start, end)) {
			return false
		}

		for (position in 0 until chain.size - 1) {
			insertPiece(chain[position], chain[position + 1], isBoundary)
		}

		return true
	}

	/**
	 * Traces a constraint edge from start to end without changing anything, recording in [chain] the
	 * vertices it passes through.
	 *
	 * @param Int start The edge's start.
	 * @param Int end   The edge's end.
	 * @return Boolean False when the edge crosses an existing constraint.
	 */
	private fun traceChain(start: Int, end: Int): Boolean {
		chain.clear()
		chain.add(start)
		var current = start

		while (current != end) {
			val reached = walkToNextVertex(current, end, null)

			if (reached == REJECTED) {
				return false
			}

			chain.add(reached)
			current = reached
		}

		return true
	}

	/**
	 * Walks from a vertex toward a target through the triangles the straight segment between them
	 * crosses, until the segment meets a vertex: the target itself, or one lying exactly on the
	 * segment.  Every crossed edge has its origin to the right of from -> to and its destination to the
	 * left.  A segment between two vertices never leaves the convex hull, so the walk never enters a
	 * ghost triangle.
	 *
	 * @param Int              from    The start vertex.
	 * @param Int              to      The target vertex.
	 * @param ArrayList<Long>? crossed Receives each crossed edge as a packed (right, left) pair, or null to only trace.
	 * @return Int The vertex reached, or [REJECTED] when a crossed edge is already a constraint.
	 */
	private fun walkToNextVertex(from: Int, to: Int, crossed: ArrayList<Long>?): Int {
		var crossing = exitEdgeFrom(from, to)

		if (crossing < 0) {
			// The segment leaves along an existing edge: -(neighbor + 1) encodes the neighbor reached.
			return -(crossing + 1)
		}

		while (true) {
			if (triangulation.constrained[crossing]) {
				crossedStart = triangulation.origin[crossing]
				crossedEnd = triangulation.destination(crossing)

				return REJECTED
			}

			crossed?.add(packPair(triangulation.origin[crossing], triangulation.destination(crossing)))
			val across = triangulation.twin[crossing]
			check(!triangulation.isGhostTriangle(across / 3)) { "the segment $from -> $to left the convex hull" }
			val farVertex = triangulation.origin[triangulation.previous(across)]

			if (farVertex == to) {
				return to
			}

			val side = triangulation.orient(from, to, farVertex)

			if (side == 0.0) {
				return farVertex
			}

			// The segment leaves the far triangle between the far vertex and whichever crossed-edge end
			// lies on the other side of it.
			crossing = if (side < 0.0) triangulation.previous(across) else triangulation.next(across)
		}
	}

	/**
	 * Finds how a segment leaves a vertex: along an edge to a neighbor (the target, or a neighbor
	 * lying on the segment), or through the interior of one triangle around the vertex.
	 *
	 * @param Int from The vertex.
	 * @param Int to   The segment's far end.
	 * @return Int The first crossed half-edge (origin right of the segment), or -(neighbor + 1) when the segment runs along an edge to that neighbor.
	 */
	private fun exitEdgeFrom(from: Int, to: Int): Int {
		val ghost = triangulation.ghostVertex
		val first = triangulation.vertexHalfEdge[from]
		var spoke = first

		do {
			val neighbor = triangulation.destination(spoke)

			if (neighbor == to) {
				return -(to + 1)
			}

			if (neighbor != ghost) {
				val neighborSide = triangulation.orient(from, to, neighbor)

				if (neighborSide == 0.0 && pointsForward(from, to, neighbor)) {
					return -(neighbor + 1)
				}

				val apex = triangulation.origin[triangulation.previous(spoke)]

				if (apex != ghost && neighborSide < 0.0 && triangulation.orient(from, to, apex) > 0.0) {
					return triangulation.next(spoke)
				}
			}

			spoke = triangulation.twin[triangulation.previous(spoke)]
		} while (spoke != first)

		error("the segment $from -> $to leaves through no triangle around $from")
	}

	/**
	 * Inserts one piece of a traced constraint - a segment no vertex lies on - then marks it.
	 *
	 * @param Int     start      The piece's start.
	 * @param Int     end        The piece's end.
	 * @param Boolean isBoundary Whether the constraint belongs to a boundary ring.
	 */
	private fun insertPiece(start: Int, end: Int, isBoundary: Boolean) {
		var halfEdge = triangulation.findHalfEdge(start, end)
		createdEdges.clear()

		if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
			crossings.clear()
			val reached = walkToNextVertex(start, end, crossings)
			check(reached == end) { "the piece $start -> $end met vertex $reached it was split at" }
			flipOutCrossings(start, end)
			halfEdge = triangulation.findHalfEdge(start, end)
			check(halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE) { "flipping out the crossings did not produce $start -> $end" }
		}

		// Marked before the restore, so the restore can never flip the constraint away.
		triangulation.markConstraint(halfEdge, isBoundary)
		restoreDelaunay()
	}

	/**
	 * Flips away every edge crossing a piece until the piece itself is an edge.
	 *
	 * @param Int start The piece's start.
	 * @param Int end   The piece's end.
	 */
	private fun flipOutCrossings(start: Int, end: Int) {
		val stepLimit = 64 * crossings.size * crossings.size + 256
		var steps = 0

		while (crossings.isNotEmpty()) {
			steps++
			check(steps <= stepLimit) { "constraint $start -> $end did not converge" }

			if (!prefersFlipsLeavingNoCrossing || !flipOneLeavingNoCrossing(start, end)) {
				sloanStep(start, end)
			}
		}
	}

	/**
	 * Flips the first crossing edge whose quad is strictly convex and whose new diagonal does not cross
	 * the piece, removing one crossing for good.
	 *
	 * @param Int start The piece's start.
	 * @param Int end   The piece's end.
	 * @return Boolean True when an edge qualified and was flipped.
	 */
	private fun flipOneLeavingNoCrossing(start: Int, end: Int): Boolean {
		for (position in crossings.indices) {
			val halfEdge = crossingHalfEdge(position)
			val apex = triangulation.origin[triangulation.previous(halfEdge)]
			val farApex = triangulation.origin[triangulation.previous(triangulation.twin[halfEdge])]

			if (isStrictlyConvex(halfEdge, apex, farApex) && !crossesPiece(start, end, apex, farApex)) {
				triangulation.flip(halfEdge)
				crossings.removeAt(position)
				recordCreated(start, end, apex, farApex)

				return true
			}
		}

		return false
	}

	/**
	 * One step of plain Sloan, which treats [crossings] as a FIFO queue: take the front edge; if its
	 * quad is not strictly convex, send it to the back; otherwise flip it, and send the new diagonal to
	 * the back if it still crosses the piece.  The queue order is what guarantees termination - a
	 * diagonal that still crosses is never the very next edge tried, so a flip is never undone at once.
	 *
	 * @param Int start The piece's start.
	 * @param Int end   The piece's end.
	 */
	private fun sloanStep(start: Int, end: Int) {
		val halfEdge = crossingHalfEdge(0)
		val pair = crossings.removeAt(0)
		val apex = triangulation.origin[triangulation.previous(halfEdge)]
		val farApex = triangulation.origin[triangulation.previous(triangulation.twin[halfEdge])]

		if (!isStrictlyConvex(halfEdge, apex, farApex)) {
			crossings.add(pair)

			return
		}

		triangulation.flip(halfEdge)

		if (crossesPiece(start, end, apex, farApex)) {
			val apexOnRight = triangulation.orient(start, end, apex) < 0.0
			crossings.add(if (apexOnRight) packPair(apex, farApex) else packPair(farApex, apex))
		} else {
			recordCreated(start, end, apex, farApex)
		}
	}

	/**
	 * Re-finds a listed crossing edge's current half-edge.
	 *
	 * @param Int position The edge's position in [crossings].
	 * @return Int The half-edge from its right end to its left end.
	 */
	private fun crossingHalfEdge(position: Int): Int {
		val pair = crossings[position]
		val halfEdge = triangulation.findHalfEdge(pairFirst(pair), pairSecond(pair))
		check(halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE) { "crossing edge ${pairFirst(pair)} -> ${pairSecond(pair)} disappeared" }

		return halfEdge
	}

	/**
	 * Whether the quad around an edge is strictly convex, so flipping it yields two positive triangles:
	 * the edge's ends lie strictly on opposite sides of the line through the two apexes.
	 *
	 * @param Int halfEdge The edge.
	 * @param Int apex     The third corner of the edge's own triangle.
	 * @param Int farApex  The third corner of the triangle across.
	 * @return Boolean True when the flip is legal.
	 */
	private fun isStrictlyConvex(halfEdge: Int, apex: Int, farApex: Int): Boolean {
		val startSide = triangulation.orient(apex, farApex, triangulation.origin[halfEdge])
		val endSide = triangulation.orient(apex, farApex, triangulation.destination(halfEdge))

		return (startSide > 0.0 && endSide < 0.0) || (startSide < 0.0 && endSide > 0.0)
	}

	/**
	 * Whether a diagonal properly crosses the piece.  A diagonal ending at either end of the piece
	 * does not (it may be the piece itself).
	 *
	 * @param Int start   The piece's start.
	 * @param Int end     The piece's end.
	 * @param Int first   One end of the diagonal.
	 * @param Int second  The other end.
	 * @return Boolean True when the two lie strictly on opposite sides of the piece.
	 */
	private fun crossesPiece(start: Int, end: Int, first: Int, second: Int): Boolean {
		val firstSide = triangulation.orient(start, end, first)
		val secondSide = triangulation.orient(start, end, second)

		return (firstSide > 0.0 && secondSide < 0.0) || (firstSide < 0.0 && secondSide > 0.0)
	}

	/**
	 * Remembers a diagonal a flip created, unless it is the piece itself, for the restore pass.
	 *
	 * @param Int start  The piece's start.
	 * @param Int end    The piece's end.
	 * @param Int first  One end of the diagonal.
	 * @param Int second The other end.
	 */
	private fun recordCreated(start: Int, end: Int, first: Int, second: Int) {
		val isPiece = (first == start && second == end) || (first == end && second == start)

		if (!isPiece) {
			createdEdges.add(packPair(first, second))
		}
	}

	/**
	 * Lawson-flips the created diagonals, and whatever their flips expose, until every unconstrained
	 * edge is locally Delaunay again.
	 */
	private fun restoreDelaunay() {
		restorePending.clear()
		restorePending.addAll(createdEdges)

		while (restorePending.isNotEmpty()) {
			val pair = restorePending.removeAt(restorePending.size - 1)
			val halfEdge = triangulation.findHalfEdge(pairFirst(pair), pairSecond(pair))

			if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE || !violatesDelaunay(halfEdge)) {
				continue
			}

			val start = triangulation.origin[halfEdge]
			val end = triangulation.destination(halfEdge)
			val apex = triangulation.origin[triangulation.previous(halfEdge)]
			val farApex = triangulation.origin[triangulation.previous(triangulation.twin[halfEdge])]
			triangulation.flip(halfEdge)

			restorePending.add(packPair(end, apex))
			restorePending.add(packPair(apex, start))
			restorePending.add(packPair(start, farApex))
			restorePending.add(packPair(farApex, end))
		}
	}

	/**
	 * Whether an unconstrained edge between two real triangles fails the Delaunay test - and so must
	 * flip.  An incircle violation implies the quad is strictly convex, so the flip is always legal.
	 *
	 * @param Int halfEdge The edge.
	 * @return Boolean True when the far corner lies strictly inside the edge's own triangle's circumcircle.
	 */
	private fun violatesDelaunay(halfEdge: Int): Boolean {
		if (triangulation.constrained[halfEdge]) {
			return false
		}

		val opposed = triangulation.twin[halfEdge]

		if (triangulation.isGhostTriangle(halfEdge / 3) || triangulation.isGhostTriangle(opposed / 3)) {
			return false
		}

		val start = triangulation.origin[halfEdge]
		val end = triangulation.destination(halfEdge)
		val apex = triangulation.origin[triangulation.previous(halfEdge)]
		val farApex = triangulation.origin[triangulation.previous(opposed)]

		return triangulation.inCircle(start, end, apex, farApex) > 0.0
	}

	/**
	 * Whether a vertex collinear with a segment lies ahead of its start, toward its end.  Exact: the
	 * sign of a floating-point difference is always correct.
	 *
	 * @param Int from      The segment's start.
	 * @param Int to        The segment's end.
	 * @param Int collinear A vertex on the segment's line.
	 * @return Boolean True when the vertex lies on the end's side of the start.
	 */
	private fun pointsForward(from: Int, to: Int, collinear: Int): Boolean {
		val alongX = triangulation.pointX(to) - triangulation.pointX(from)

		if (alongX != 0.0) {
			return (triangulation.pointX(collinear) - triangulation.pointX(from) > 0.0) == (alongX > 0.0)
		}

		val alongY = triangulation.pointY(to) - triangulation.pointY(from)

		return (triangulation.pointY(collinear) - triangulation.pointY(from) > 0.0) == (alongY > 0.0)
	}

	private companion object {
		/** [walkToNextVertex]'s result when the walk ran into an existing constraint. */
		const val REJECTED = -1

		/**
		 * Packs two vertices into one Long.
		 *
		 * @param Int first  The first vertex.
		 * @param Int second The second vertex.
		 * @return Long The packed pair.
		 */
		fun packPair(first: Int, second: Int): Long = (first.toLong() shl 32) or (second.toLong() and 0xFFFFFFFFL)

		/**
		 * The first vertex of a packed pair.
		 *
		 * @param Long pair The packed pair.
		 * @return Int The first vertex.
		 */
		fun pairFirst(pair: Long): Int = (pair ushr 32).toInt()

		/**
		 * The second vertex of a packed pair.
		 *
		 * @param Long pair The packed pair.
		 * @return Int The second vertex.
		 */
		fun pairSecond(pair: Long): Int = pair.toInt()
	}
}