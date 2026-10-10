package org.umamo.geometry.triangulation

import org.umamo.geometry.predicate.PredicateScratch
import org.umamo.geometry.predicate.incircle
import org.umamo.geometry.predicate.orient2d

/**
 * The triangulator's mutable half-edge store: three half-edges per triangle in flat arrays, Delaunator
 * style, closed over the convex hull by a symbolic GHOST vertex.
 *
 * Triangle `t` owns half-edges `3t`, `3t + 1`, and `3t + 2`, running from [origin] of each to the
 * origin of the next.  Real triangles are positively oriented (orient2d > 0), so the interior lies to
 * the left of every half-edge.  Each hull edge u->v of a real triangle has a ghost triangle (v, u,
 * ghost) across it, which makes the whole store a closed sphere: every half-edge has a [twin], every
 * vertex star is a closed cycle, and a point outside the hull is simply "inside" some ghost triangle.
 * The ghost is vertex index [pointCount] and is never handed to a predicate.
 *
 * Per-edge attributes ([constrained], [boundaryCount]) are stored on BOTH halves of an edge.  [flip]
 * and the two splits move them with the edges they describe, because those operations move half-edges
 * between slots; every caller that holds a slot across a mutation must re-find its edge.
 *
 * @property DoubleArray coordinates The caller's points, x0, y0, x1, y1, ...
 * @property Int         pointCount  The number of points, and the ghost vertex's index.
 */
internal class HalfEdgeTriangulation(private val coordinates: DoubleArray, val pointCount: Int) {
	/** The symbolic vertex every ghost triangle shares. */
	val ghostVertex: Int = pointCount

	/** A sphere over k vertices plus the ghost has exactly 2k - 2 triangles, so this never grows. */
	private val capacity: Int = 3 * maxOf(2 * pointCount, 4)

	/** The vertex each half-edge starts at. */
	val origin: IntArray = IntArray(capacity)

	/** The opposite half-edge of the same edge. */
	val twin: IntArray = IntArray(capacity)

	/** Whether the edge is a constraint, which no flip may remove. */
	val constrained: BooleanArray = BooleanArray(capacity)

	/** How many boundary rings run along the edge; its parity is what classification crosses. */
	val boundaryCount: IntArray = IntArray(capacity)

	/** One half-edge leaving each vertex, kept current through every mutation. */
	val vertexHalfEdge: IntArray = IntArray(pointCount + 1) { NO_HALF_EDGE }

	/** The predicates' buffers, owned by this run so the adaptive paths never allocate. */
	private val scratch = PredicateScratch()

	/** The number of half-edges in use (a multiple of three). */
	var halfEdgeCount: Int = 0
		private set

	/** The number of triangles, real and ghost. */
	val triangleCount: Int
		get() = halfEdgeCount / 3

	/**
	 * A point's x.
	 *
	 * @param Int vertex A real vertex.
	 * @return Double The x coordinate.
	 */
	fun pointX(vertex: Int): Double = coordinates[2 * vertex]

	/**
	 * A point's y.
	 *
	 * @param Int vertex A real vertex.
	 * @return Double The y coordinate.
	 */
	fun pointY(vertex: Int): Double = coordinates[2 * vertex + 1]

	/**
	 * The next half-edge around the same triangle.
	 *
	 * @param Int halfEdge A half-edge.
	 * @return Int The half-edge that starts where [halfEdge] ends.
	 */
	fun next(halfEdge: Int): Int = if (halfEdge % 3 == 2) halfEdge - 2 else halfEdge + 1

	/**
	 * The previous half-edge around the same triangle.
	 *
	 * @param Int halfEdge A half-edge.
	 * @return Int The half-edge that ends where [halfEdge] starts.
	 */
	fun previous(halfEdge: Int): Int = if (halfEdge % 3 == 0) halfEdge + 2 else halfEdge - 1

	/**
	 * The vertex a half-edge ends at.
	 *
	 * @param Int halfEdge A half-edge.
	 * @return Int Its destination vertex.
	 */
	fun destination(halfEdge: Int): Int = origin[next(halfEdge)]

	/**
	 * Whether a triangle has the ghost among its corners.
	 *
	 * @param Int triangle A triangle index.
	 * @return Boolean True for a ghost triangle.
	 */
	fun isGhostTriangle(triangle: Int): Boolean {
		val base = 3 * triangle
		return origin[base] == ghostVertex || origin[base + 1] == ghostVertex || origin[base + 2] == ghostVertex
	}

	/**
	 * The one half-edge of a ghost triangle whose ends are both real - the hull edge seen from outside.
	 *
	 * @param Int triangle A ghost triangle.
	 * @return Int Its real half-edge.
	 */
	fun realEdgeOfGhost(triangle: Int): Int {
		val base = 3 * triangle

		for (offset in 0 until 3) {
			val halfEdge = base + offset
			if (origin[halfEdge] != ghostVertex && destination(halfEdge) != ghostVertex) {
				return halfEdge
			}
		}

		error("triangle $triangle has no real edge")
	}

	/**
	 * The exact orientation of three real vertices (see [orient2d]).
	 *
	 * @param Int first  The first vertex.
	 * @param Int second The second vertex.
	 * @param Int third  The third vertex.
	 * @return Double Positive when they turn counterclockwise with y up.
	 */
	fun orient(first: Int, second: Int, third: Int): Double = orient2d(pointX(first), pointY(first), pointX(second), pointY(second), pointX(third), pointY(third), scratch)

	/**
	 * The exact incircle test of four real vertices (see [incircle]).
	 *
	 * @param Int first  The first circle vertex.
	 * @param Int second The second circle vertex.
	 * @param Int third  The third circle vertex.
	 * @param Int query  The vertex tested against the circle.
	 * @return Double Positive when [query] is strictly inside, for a positively oriented circle triple.
	 */
	fun inCircle(first: Int, second: Int, third: Int, query: Int): Double = incircle(pointX(first), pointY(first), pointX(second), pointY(second), pointX(third), pointY(third), pointX(query), pointY(query), scratch)

	/**
	 * Builds the first real triangle and the three ghost triangles closing it into a sphere.
	 *
	 * @param Int first  The first vertex.
	 * @param Int second The second vertex.
	 * @param Int third  The third vertex; the triple must be positively oriented.
	 */
	fun bootstrap(first: Int, second: Int, third: Int) {
		check(halfEdgeCount == 0) { "bootstrap runs once, on an empty store" }
		val real = createTriangle(first, second, third)
		val ghostAcrossFirst = createTriangle(second, first, ghostVertex)
		val ghostAcrossSecond = createTriangle(third, second, ghostVertex)
		val ghostAcrossThird = createTriangle(first, third, ghostVertex)

		link(real, ghostAcrossFirst)
		link(real + 1, ghostAcrossSecond)
		link(real + 2, ghostAcrossThird)
		link(ghostAcrossFirst + 2, ghostAcrossSecond + 1)
		link(ghostAcrossSecond + 2, ghostAcrossThird + 1)
		link(ghostAcrossThird + 2, ghostAcrossFirst + 1)

		vertexHalfEdge[first] = real
		vertexHalfEdge[second] = real + 1
		vertexHalfEdge[third] = real + 2
		vertexHalfEdge[ghostVertex] = ghostAcrossFirst + 2
	}

	/**
	 * Splits a triangle (a, b, c) into (a, b, p), (b, c, p), and (c, a, p) around a vertex inside it -
	 * or, for a ghost triangle, around a vertex beyond its hull edge, which extends the hull.
	 *
	 * @param Int      triangle The triangle containing [vertex].
	 * @param Int      vertex   The vertex being inserted.
	 * @param IntStack opposite Receives the three half-edges opposite [vertex], for legalization.
	 */
	fun splitTriangle(triangle: Int, vertex: Int, opposite: IntStack) {
		val edgeAb = 3 * triangle
		val edgeBc = edgeAb + 1
		val edgeCa = edgeAb + 2
		val vertexA = origin[edgeAb]
		val vertexB = origin[edgeBc]
		val vertexC = origin[edgeCa]
		val outerBc = twin[edgeBc]
		val outerCa = twin[edgeCa]

		val second = createTriangle(vertexB, vertexC, vertex)
		val third = createTriangle(vertexC, vertexA, vertex)
		moveAttributes(edgeBc, second)
		moveAttributes(edgeCa, third)
		// The original slots keep (a, b) and become (b, p) and (p, a).
		origin[edgeCa] = vertex

		link(second, outerBc)
		link(third, outerCa)
		link(edgeBc, second + 2)
		link(edgeCa, third + 1)
		link(second + 1, third + 2)

		vertexHalfEdge[vertexA] = edgeAb
		vertexHalfEdge[vertexB] = edgeBc
		vertexHalfEdge[vertexC] = second + 1
		vertexHalfEdge[vertex] = edgeCa

		opposite.push(edgeAb)
		opposite.push(second)
		opposite.push(third)
	}

	/**
	 * Splits the two triangles on an edge around a vertex lying on its open segment: (u, v, x) and
	 * (v, u, y) become (u, p, x), (p, v, x), (v, p, y), and (p, u, y).  The two halves of the split
	 * edge inherit its attributes.
	 *
	 * @param Int      halfEdge The half-edge u->v the vertex lies on.
	 * @param Int      vertex   The vertex being inserted.
	 * @param IntStack opposite Receives the four half-edges opposite [vertex], for legalization.
	 */
	fun splitEdge(halfEdge: Int, vertex: Int, opposite: IntStack) {
		val firstNext = next(halfEdge)
		val firstPrevious = previous(halfEdge)
		val opposed = twin[halfEdge]
		val secondNext = next(opposed)
		val secondPrevious = previous(opposed)
		val vertexU = origin[halfEdge]
		val vertexV = origin[firstNext]
		val vertexX = origin[firstPrevious]
		val vertexY = origin[secondPrevious]
		val outerVx = twin[firstNext]
		val outerUy = twin[secondNext]

		val besideV = createTriangle(vertex, vertexV, vertexX)
		val besideU = createTriangle(vertex, vertexU, vertexY)
		moveAttributes(firstNext, besideV + 1)
		moveAttributes(secondNext, besideU + 1)
		copyAttributes(halfEdge, besideV)
		copyAttributes(halfEdge, besideU)
		// The original slots become (u, p, x) and (v, p, y).
		origin[firstNext] = vertex
		origin[secondNext] = vertex

		link(besideV + 1, outerVx)
		link(besideU + 1, outerUy)
		link(halfEdge, besideU)
		link(besideV, opposed)
		link(firstNext, besideV + 2)
		link(secondNext, besideU + 2)

		vertexHalfEdge[vertexU] = halfEdge
		vertexHalfEdge[vertex] = firstNext
		vertexHalfEdge[vertexX] = firstPrevious
		vertexHalfEdge[vertexV] = opposed
		vertexHalfEdge[vertexY] = secondPrevious

		opposite.push(firstPrevious)
		opposite.push(besideV + 1)
		opposite.push(secondPrevious)
		opposite.push(besideU + 1)
	}

	/**
	 * Flips an edge: triangles (u, v, x) and (v, u, y) become (x, y, v) and (y, x, u).  The half-edge
	 * keeps its slot and now runs x->y; the four outer edges move between slots with their attributes,
	 * and the new diagonal is unconstrained.  The caller guarantees the quad is strictly convex.
	 *
	 * @param Int halfEdge The half-edge u->v to flip.
	 */
	fun flip(halfEdge: Int) {
		val firstNext = next(halfEdge)
		val firstPrevious = previous(halfEdge)
		val opposed = twin[halfEdge]
		val secondNext = next(opposed)
		val secondPrevious = previous(opposed)
		val vertexU = origin[halfEdge]
		val vertexV = origin[firstNext]
		val vertexX = origin[firstPrevious]
		val vertexY = origin[secondPrevious]
		val outerVx = twin[firstNext]
		val outerXu = twin[firstPrevious]
		val outerUy = twin[secondNext]
		val outerYv = twin[secondPrevious]
		val constrainedVx = constrained[firstNext]
		val constrainedXu = constrained[firstPrevious]
		val constrainedUy = constrained[secondNext]
		val constrainedYv = constrained[secondPrevious]
		val countVx = boundaryCount[firstNext]
		val countXu = boundaryCount[firstPrevious]
		val countUy = boundaryCount[secondNext]
		val countYv = boundaryCount[secondPrevious]

		origin[halfEdge] = vertexX
		origin[firstNext] = vertexY
		origin[firstPrevious] = vertexV
		origin[opposed] = vertexY
		origin[secondNext] = vertexX
		origin[secondPrevious] = vertexU

		setAttributes(firstNext, constrainedYv, countYv)
		setAttributes(firstPrevious, constrainedVx, countVx)
		setAttributes(secondNext, constrainedXu, countXu)
		setAttributes(secondPrevious, constrainedUy, countUy)
		setAttributes(halfEdge, false, 0)
		setAttributes(opposed, false, 0)

		link(firstNext, outerYv)
		link(firstPrevious, outerVx)
		link(secondNext, outerXu)
		link(secondPrevious, outerUy)

		vertexHalfEdge[vertexX] = halfEdge
		vertexHalfEdge[vertexY] = opposed
		vertexHalfEdge[vertexV] = firstPrevious
		vertexHalfEdge[vertexU] = secondPrevious
	}

	/**
	 * Finds the half-edge running from one vertex to another by walking the first vertex's star.
	 *
	 * @param Int from The start vertex (inserted).
	 * @param Int to   The end vertex.
	 * @return Int The half-edge from -> to, or [NO_HALF_EDGE] when the two are not joined.
	 */
	fun findHalfEdge(from: Int, to: Int): Int {
		val first = vertexHalfEdge[from]

		if (first == NO_HALF_EDGE) {
			return NO_HALF_EDGE
		}

		var spoke = first

		do {
			if (destination(spoke) == to) {
				return spoke
			}

			spoke = twin[previous(spoke)]
		} while (spoke != first)

		return NO_HALF_EDGE
	}

	/**
	 * Marks an edge as a constraint on both halves, and counts one more boundary ring along it when
	 * the constraint is a boundary.
	 *
	 * @param Int     halfEdge   Either half of the edge.
	 * @param Boolean isBoundary Whether the constraint belongs to a boundary ring.
	 */
	fun markConstraint(halfEdge: Int, isBoundary: Boolean) {
		val opposed = twin[halfEdge]
		constrained[halfEdge] = true
		constrained[opposed] = true

		if (isBoundary) {
			boundaryCount[halfEdge]++
			boundaryCount[opposed]++
		}
	}

	/**
	 * Every constrained edge once, as (low, high) vertex pairs in ascending order.
	 *
	 * @return IntArray Two vertex indices per constrained edge.
	 */
	fun constrainedEdgePairs(): IntArray {
		val keys = ArrayList<Long>()

		for (halfEdge in 0 until halfEdgeCount) {
			val start = origin[halfEdge]
			val end = destination(halfEdge)

			if (constrained[halfEdge] && start < end) {
				keys.add((start.toLong() shl 32) or end.toLong())
			}
		}

		keys.sort()
		val pairs = IntArray(2 * keys.size)

		for ((position, key) in keys.withIndex()) {
			pairs[2 * position] = (key ushr 32).toInt()
			pairs[2 * position + 1] = key.toInt()
		}

		return pairs
	}

	/**
	 * The real triangles as vertex triples, in slot order.
	 *
	 * @param BooleanArray? selected Per triangle slot, whether to include it; null includes every real triangle.
	 * @return IntArray Three vertex indices per included triangle, each triple positively oriented.
	 */
	fun realTriangles(selected: BooleanArray? = null): IntArray {
		var realCount = 0

		for (triangle in 0 until triangleCount) {
			if (isIncluded(triangle, selected)) {
				realCount++
			}
		}

		val triangles = IntArray(3 * realCount)
		var written = 0

		for (triangle in 0 until triangleCount) {
			if (isIncluded(triangle, selected)) {
				triangles[written] = origin[3 * triangle]
				triangles[written + 1] = origin[3 * triangle + 1]
				triangles[written + 2] = origin[3 * triangle + 2]
				written += 3
			}
		}

		return triangles
	}

	/**
	 * Checks every structural invariant, for tests and fuzzing: twins pair up and agree on their ends,
	 * attributes match across twins (and a boundary edge is always constrained), each triangle has at
	 * most one ghost corner, real triangles are positively oriented, and the vertex map points at
	 * half-edges leaving each vertex.
	 */
	fun validate() {
		check(halfEdgeCount % 3 == 0) { "half-edge count $halfEdgeCount is not a multiple of three" }

		for (halfEdge in 0 until halfEdgeCount) {
			val opposed = twin[halfEdge]
			check(opposed in 0 until halfEdgeCount && opposed != halfEdge) { "half-edge $halfEdge has twin $opposed" }
			check(twin[opposed] == halfEdge) { "twin of $opposed is ${twin[opposed]}, not $halfEdge" }
			check(origin[opposed] == destination(halfEdge)) { "half-edge $halfEdge and its twin $opposed disagree on their ends" }
			check(origin[halfEdge] != destination(halfEdge)) { "half-edge $halfEdge is a loop" }
			check(constrained[halfEdge] == constrained[opposed]) { "half-edge $halfEdge and its twin disagree on the constraint flag" }
			check(boundaryCount[halfEdge] == boundaryCount[opposed]) { "half-edge $halfEdge and its twin disagree on the boundary count" }
			check(boundaryCount[halfEdge] == 0 || constrained[halfEdge]) { "half-edge $halfEdge counts boundary rings but is not constrained" }
		}

		for (triangle in 0 until triangleCount) {
			val base = 3 * triangle
			var ghostCorners = 0

			for (offset in 0 until 3) {
				if (origin[base + offset] == ghostVertex) {
					ghostCorners++
				}
			}

			check(ghostCorners <= 1) { "triangle $triangle has $ghostCorners ghost corners" }

			if (ghostCorners == 0) {
				check(orient(origin[base], origin[base + 1], origin[base + 2]) > 0.0) { "real triangle $triangle is not positively oriented" }
			}
		}

		for (vertex in 0..pointCount) {
			val halfEdge = vertexHalfEdge[vertex]

			if (halfEdge != NO_HALF_EDGE) {
				check(origin[halfEdge] == vertex) { "vertex $vertex maps to half-edge $halfEdge, which starts at ${origin[halfEdge]}" }
			}
		}
	}

	/**
	 * Whether [realTriangles] includes a triangle.
	 *
	 * @param Int           triangle The triangle slot.
	 * @param BooleanArray? selected The selection, or null for every real triangle.
	 * @return Boolean True for a real triangle the selection keeps.
	 */
	private fun isIncluded(triangle: Int, selected: BooleanArray?): Boolean = !isGhostTriangle(triangle) && (selected == null || selected[triangle])

	/**
	 * Appends a triangle with unlinked, unconstrained half-edges.
	 *
	 * @param Int first  The first corner.
	 * @param Int second The second corner.
	 * @param Int third  The third corner.
	 * @return Int The triangle's first half-edge (first -> second).
	 */
	private fun createTriangle(first: Int, second: Int, third: Int): Int {
		check(halfEdgeCount + 3 <= capacity) { "triangle store is full at $halfEdgeCount half-edges" }

		val base = halfEdgeCount
		origin[base] = first
		origin[base + 1] = second
		origin[base + 2] = third

		for (offset in 0 until 3) {
			twin[base + offset] = NO_HALF_EDGE
			setAttributes(base + offset, false, 0)
		}

		halfEdgeCount += 3

		return base
	}

	/**
	 * Makes two half-edges each other's twin.
	 *
	 * @param Int first  One half-edge.
	 * @param Int second The other half-edge.
	 */
	private fun link(first: Int, second: Int) {
		twin[first] = second
		twin[second] = first
	}

	/**
	 * Sets one half-edge's attributes.
	 *
	 * @param Int     halfEdge       The half-edge.
	 * @param Boolean isConstrained  The constraint flag.
	 * @param Int     ringCrossings  The boundary count.
	 */
	private fun setAttributes(halfEdge: Int, isConstrained: Boolean, ringCrossings: Int) {
		constrained[halfEdge] = isConstrained
		boundaryCount[halfEdge] = ringCrossings
	}

	/**
	 * Copies one half-edge's attributes onto another.
	 *
	 * @param Int source      The half-edge copied from.
	 * @param Int destination The half-edge copied to.
	 */
	private fun copyAttributes(source: Int, destination: Int) {
		setAttributes(destination, constrained[source], boundaryCount[source])
	}

	/**
	 * Moves one half-edge's attributes onto another and clears the source.
	 *
	 * @param Int source      The half-edge moved from.
	 * @param Int destination The half-edge moved to.
	 */
	private fun moveAttributes(source: Int, destination: Int) {
		copyAttributes(source, destination)
		setAttributes(source, false, 0)
	}

	companion object {
		/** The empty slot of [twin] and [vertexHalfEdge]. */
		const val NO_HALF_EDGE: Int = -1
	}
}