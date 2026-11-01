package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.IntStack
import org.umamo.geometry.triangulation.DelaunayInserter
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import org.umamo.geometry.triangulation.compareAlongLine

/*
 * Adding one vertex to a constrained Delaunay triangulation in place, the mechanics refinement is built
 * from.  Every added vertex is a Float point: refinement serves meshes stored in Float, and a triangle
 * that is positive on stored values stays positive in the mesh.
 *
 * An interior vertex is found by walking the straight segment from inside the triangle it is meant to
 * fix, so a constraint between the two stops the walk; that is how refinement learns which segment
 * hides its candidate.  Before anything changes, the candidate's cavity - the triangles whose
 * circumcircles hold it, reached without crossing a constraint - names every vertex it would join and
 * every segment it would face, which is what the separation floor and the encroachment test read.
 * Inserting through the Delaunay inserter then rebuilds exactly that cavity.
 *
 * A segment cannot simply be split at its midpoint: the midpoint of two Float points is rarely a Float
 * point on the segment.  A Float point exactly on it goes in by an edge split.  Otherwise the vertex
 * goes in strictly beside the segment, inside the triangle on that side, while the segment still holds,
 * and then the constraint is rerouted through it: the old edge is released and the two new edges take
 * its attributes.  The thin triangle left between the old line and the new vertex joins the region on
 * the far side of the old line, which is the region it now lies in, and Delaunay flips clear it away.
 */

/**
 * Approves or refuses a segment split's candidate, after its cavity is known.
 */
internal fun interface SplitApproval {
	/**
	 * Whether to insert this candidate.
	 *
	 * @param Int     candidate                      The appended candidate vertex.
	 * @param Boolean cutsInside                     Whether it lies on the inside of a boundary edge, so the sliver it leaves is cut out of the domain.
	 * @param Double  nearestNeighborDistanceSquared The squared distance to the nearest vertex it would join.
	 * @return Boolean True to insert it.
	 */
	fun approves(candidate: Int, cutsInside: Boolean, nearestNeighborDistanceSquared: Double): Boolean
}

/**
 * Locates, measures, and inserts single vertices in a classified store.
 *
 * @property HalfEdgeTriangulation store The store, its regions adopted.
 */
internal class SteinerInsertion(private val store: HalfEdgeTriangulation) {
	private val inserter = DelaunayInserter(store)

	/** What the last [locate] found: a triangle, a half-edge, or a vertex, by its result. */
	var locatedAt: Int = 0
		private set

	/** The triangles of the last cavity, seeds first. */
	val cavityTriangles: IntStack = IntStack()

	/** The vertices the last cavity's candidate would join. */
	val cavityVertices: IntStack = IntStack()

	/** The constrained edges on the last cavity's boundary, as half-edges facing into it. */
	val cavityConstraints: IntStack = IntStack()

	private val triangleStamp = IntArray(store.regions.size)
	private val vertexStamp = IntArray(store.vertexCapacity + 1)
	private var stamp = 0

	/** Whether the boundary split check turned down a candidate during the last [splitConstraint]. */
	var lastSplitRefusedByCheck: Boolean = false
		private set

	/** Why the last [crossEdge] refused to cross. */
	private var lastStop: Int = LOCATED_UNRESOLVED

	/** The straight walk's start and target, for the side tests against its line. */
	private var rayFromX = 0.0
	private var rayFromY = 0.0
	private var rayToX = 0.0
	private var rayToY = 0.0

	/** The corner the walk's segment leaves a triangle through, when [exitOfRay] found no edge. */
	private var rayExitVertex = NO_VERTEX

	/**
	 * Walks the straight segment from a triangle's centroid to an appended candidate, through inside
	 * triangles only, and reports where the candidate lies.  The walk stops at the first constraint the
	 * segment would cross or touch, and gives up when the segment leaves the inside through a vertex.
	 *
	 * @param Int startTriangle An inside triangle.
	 * @param Int candidate     The appended, not yet inserted, candidate.
	 * @return Int One of the LOCATED_* results; [locatedAt] names the triangle (inside), the unconstrained half-edge (on edge), the constrained half-edge (blocked), or nothing.
	 */
	fun locate(startTriangle: Int, candidate: Int): Int {
		if (!startRay(startTriangle, candidate)) {
			return LOCATED_UNRESOLVED
		}

		var triangle = startTriangle
		var entered = HalfEdgeTriangulation.NO_HALF_EDGE
		val stepLimit = store.lineWalkStepLimit()

		for (step in 0 until stepLimit) {
			val placement = placeInTriangle(triangle, entered, candidate)

			if (placement != STILL_BEYOND) {
				return placement
			}

			val exitEdge = exitOfRay(triangle)

			if (exitEdge != HalfEdgeTriangulation.NO_HALF_EDGE) {
				val crossing = crossEdge(exitEdge)

				if (crossing == HalfEdgeTriangulation.NO_HALF_EDGE) {
					return lastStop
				}

				entered = crossing
				triangle = crossing / 3

				continue
			}

			if (rayExitVertex == NO_VERTEX) {
				return LOCATED_UNRESOLVED
			}

			val leaving = leaveVertex(rayExitVertex, candidate, stepLimit)

			if (leaving != STILL_BEYOND) {
				return leaving
			}

			entered = locatedAt
			triangle = entered / 3
		}

		return LOCATED_UNRESOLVED
	}

	/**
	 * Starts the straight walk from a triangle's centroid toward a candidate.
	 *
	 * @param Int startTriangle The triangle.
	 * @param Int candidate     The candidate.
	 * @return Boolean False when the centroid rounded onto or past an edge: the triangle is too thin to walk out of.
	 */
	private fun startRay(startTriangle: Int, candidate: Int): Boolean {
		val startBase = 3 * startTriangle
		val firstCorner = store.origin[startBase]
		val secondCorner = store.origin[startBase + 1]
		val thirdCorner = store.origin[startBase + 2]
		rayFromX = (store.pointX(firstCorner) + store.pointX(secondCorner) + store.pointX(thirdCorner)) / 3.0
		rayFromY = (store.pointY(firstCorner) + store.pointY(secondCorner) + store.pointY(thirdCorner)) / 3.0
		rayToX = store.pointX(candidate)
		rayToY = store.pointY(candidate)

		for (offset in 0 until 3) {
			val halfEdge = startBase + offset

			if (sideOf(store.origin[halfEdge], store.destination(halfEdge), rayFromX, rayFromY) <= 0.0) {
				return false
			}
		}

		return true
	}

	/**
	 * How the walk's segment leaves a triangle: through the edge whose origin is right of it and
	 * destination left, or through a corner on it whose neighbors sit that way round ([rayExitVertex]).
	 *
	 * @param Int triangle The triangle.
	 * @return Int The exit half-edge, or [HalfEdgeTriangulation.NO_HALF_EDGE].
	 */
	private fun exitOfRay(triangle: Int): Int {
		var exitEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		rayExitVertex = NO_VERTEX

		for (offset in 0 until 3) {
			val halfEdge = 3 * triangle + offset
			val originSide = sideOfRay(store.origin[halfEdge])
			val destinationSide = sideOfRay(store.destination(halfEdge))

			if (originSide < 0.0 && destinationSide > 0.0) {
				exitEdge = halfEdge
			} else if (originSide == 0.0 && destinationSide > 0.0 && sideOfRay(store.origin[store.previous(halfEdge)]) < 0.0) {
				rayExitVertex = store.origin[halfEdge]
			}
		}

		return exitEdge
	}

	/**
	 * Gathers a candidate's cavity: the triangles whose circumcircles strictly contain it, grown from
	 * the seeds across unconstrained edges, then the vertices and constrained edges on its boundary.
	 *
	 * @param Int candidate  The appended candidate.
	 * @param Int firstSeed  A triangle the candidate lies in or on, or -1.
	 * @param Int secondSeed Another such triangle, or -1.
	 */
	fun collectCavity(candidate: Int, firstSeed: Int, secondSeed: Int) {
		stamp++
		cavityTriangles.clear()
		cavityVertices.clear()
		cavityConstraints.clear()

		for (seed in intArrayOf(firstSeed, secondSeed)) {
			if (seed >= 0 && triangleStamp[seed] != stamp) {
				triangleStamp[seed] = stamp
				cavityTriangles.push(seed)
			}
		}

		var position = 0

		while (position < cavityTriangles.size) {
			val triangle = cavityTriangles[position]
			position++

			for (offset in 0 until 3) {
				val halfEdge = 3 * triangle + offset
				val across = store.twin[halfEdge]
				val acrossTriangle = across / 3

				if (triangleStamp[acrossTriangle] == stamp || store.constrained[halfEdge] || store.isGhostTriangle(acrossTriangle)) {
					continue
				}

				if (store.inCircle(store.origin[across], store.destination(across), store.origin[store.previous(across)], candidate) > 0.0) {
					triangleStamp[acrossTriangle] = stamp
					cavityTriangles.push(acrossTriangle)
				}
			}
		}

		for (cavityPosition in 0 until cavityTriangles.size) {
			val triangle = cavityTriangles[cavityPosition]

			for (offset in 0 until 3) {
				val halfEdge = 3 * triangle + offset

				if (triangleStamp[store.twin[halfEdge] / 3] == stamp) {
					continue
				}

				addCavityVertex(store.origin[halfEdge])
				addCavityVertex(store.destination(halfEdge))

				if (store.constrained[halfEdge]) {
					cavityConstraints.push(halfEdge)
				}
			}
		}
	}

	/**
	 * The squared distance from a candidate to the nearest vertex of the last cavity.
	 *
	 * @param Int candidate The candidate the cavity was gathered for.
	 * @return Double The squared distance, or infinity for an empty cavity.
	 */
	fun nearestCavityDistanceSquared(candidate: Int): Double {
		var nearest = Double.POSITIVE_INFINITY
		val candidateX = store.pointX(candidate)
		val candidateY = store.pointY(candidate)

		for (position in 0 until cavityVertices.size) {
			val vertex = cavityVertices[position]
			nearest = minOf(nearest, squaredDistance(candidateX, candidateY, store.pointX(vertex), store.pointY(vertex)))
		}

		return nearest
	}

	/**
	 * Gathers the cavity of a located candidate, from the triangle it lies in or the two beside the
	 * unconstrained half-edge it lies on.
	 *
	 * @param Int     candidate The appended candidate.
	 * @param Boolean onEdge    Whether [at] is the unconstrained half-edge it lies on rather than the triangle it lies in.
	 * @param Int     at        The half-edge or triangle.
	 */
	fun collectCavityAt(candidate: Int, onEdge: Boolean, at: Int) {
		if (onEdge) {
			collectCavity(candidate, at / 3, store.twin[at] / 3)
		} else {
			collectCavity(candidate, at, -1)
		}
	}

	/**
	 * Inserts a located candidate and restores the Delaunay property around it.
	 *
	 * @param Int     candidate The appended candidate.
	 * @param Boolean onEdge    Whether [at] is the unconstrained half-edge it lies on rather than the triangle it lies in.
	 * @param Int     at        The half-edge or triangle.
	 */
	fun insert(candidate: Int, onEdge: Boolean, at: Int) {
		inserter.insertLocated(candidate, onEdge, at)
	}

	/**
	 * Splits a constrained edge near a point on or beside it, keeping clear of every vertex the split
	 * would join.  A boundary edge tries its outward side first and splits only where the check allows;
	 * [lastSplitRefusedByCheck] then says whether the check turned a candidate down.
	 *
	 * @param Int                 halfEdge          The constrained half-edge.
	 * @param Double              targetX           The point's x.
	 * @param Double              targetY           The point's y.
	 * @param Double              separationSquared The squared separation floor.
	 * @param BoundarySplitCheck? check             Asked before a boundary edge splits; null allows every split.
	 * @return Int The new vertex, or [NO_VERTEX].
	 */
	fun splitConstraint(halfEdge: Int, targetX: Double, targetY: Double, separationSquared: Double, check: BoundarySplitCheck?): Int {
		val start = store.origin[halfEdge]
		val end = store.destination(halfEdge)
		val isBoundary = store.boundaryCount[halfEdge] % 2 == 1
		val outwardIsLeft = if (isBoundary) !store.regions[halfEdge / 3] else null
		val boundaryCheck = if (isBoundary) check else null
		lastSplitRefusedByCheck = false

		return splitSegment(start, end, targetX, targetY, outwardIsLeft) { candidate, cutsInside, nearestNeighborDistanceSquared ->
			approvesSplit(start, end, candidate, cutsInside, nearestNeighborDistanceSquared, separationSquared, boundaryCheck)
		}
	}

	/**
	 * Whether a constraint split's candidate may go in: far enough from every vertex it would join, and
	 * allowed by the check when it splits a boundary edge.
	 *
	 * @param Int                 start                          The edge's start.
	 * @param Int                 end                            Its end.
	 * @param Int                 candidate                      The appended candidate.
	 * @param Boolean             cutsInside                     Whether it lies on the boundary's inside.
	 * @param Double              nearestNeighborDistanceSquared The squared distance to its nearest would-be neighbor.
	 * @param Double              separationSquared              The squared separation floor.
	 * @param BoundarySplitCheck? check                          The check, for a boundary edge.
	 * @return Boolean True to insert it.
	 */
	private fun approvesSplit(start: Int, end: Int, candidate: Int, cutsInside: Boolean, nearestNeighborDistanceSquared: Double, separationSquared: Double, check: BoundarySplitCheck?): Boolean {
		if (nearestNeighborDistanceSquared < separationSquared) {
			return false
		}

		if (check == null) {
			return true
		}

		val accepted = check.accepts(store.pointX(start), store.pointY(start), store.pointX(end), store.pointY(end), store.pointX(candidate), store.pointY(candidate), cutsInside)

		if (!accepted) {
			lastSplitRefusedByCheck = true
		}

		return accepted
	}

	/**
	 * Splits a constrained edge at a Float point near a target on it: the target rounded to Float or one
	 * of its eight Float neighbors, whichever valid candidate the approval accepts first.
	 *
	 * @param Int           start        The edge's start.
	 * @param Int           end          The edge's end.
	 * @param Double        targetX      The ideal split point's x, on the segment.
	 * @param Double        targetY      The ideal split point's y.
	 * @param Boolean?      outwardIsLeft For a boundary edge, whether the outside lies left of start -> end (the side tried first); null when neither side is outside.
	 * @param SplitApproval approval     Decides each candidate.
	 * @return Int The new vertex, or [NO_VERTEX] when no candidate was valid and approved.
	 */
	fun splitSegment(start: Int, end: Int, targetX: Double, targetY: Double, outwardIsLeft: Boolean?, approval: SplitApproval): Int {
		val halfEdge = store.findHalfEdge(start, end)
		val roundedX = targetX.toFloat()
		val roundedY = targetY.toFloat()

		if (!roundedX.isFinite() || !roundedY.isFinite()) {
			return NO_VERTEX
		}

		val candidates = gatherSplitCandidates(halfEdge, roundedX, roundedY)
		val collinear = candidates[SPLIT_ON_SEGMENT]

		if (collinear != NO_CANDIDATE) {
			val inserted = trySplitCandidate(start, end, collinear, SPLIT_ON_SEGMENT, false, approval)

			if (inserted != NO_VERTEX) {
				return inserted
			}
		}

		val outwardFirst = outwardIsLeft ?: true

		for (isLeft in booleanArrayOf(outwardFirst, !outwardFirst)) {
			val placement = if (isLeft) SPLIT_LEFT else SPLIT_RIGHT
			val packed = candidates[placement]

			if (packed == NO_CANDIDATE) {
				continue
			}

			val cutsInside = outwardIsLeft != null && isLeft != outwardIsLeft
			val inserted = trySplitCandidate(start, end, packed, placement, cutsInside, approval)

			if (inserted != NO_VERTEX) {
				return inserted
			}
		}

		return NO_VERTEX
	}

	/**
	 * The first valid split candidate of each kind among a rounded point and its eight Float neighbors.
	 *
	 * @param Int   halfEdge The edge, start -> end.
	 * @param Float roundedX The rounded split point's x.
	 * @param Float roundedY The rounded split point's y.
	 * @return LongArray Per kind (SPLIT_ON_SEGMENT, SPLIT_LEFT, SPLIT_RIGHT), the packed candidate or [NO_CANDIDATE].
	 */
	private fun gatherSplitCandidates(halfEdge: Int, roundedX: Float, roundedY: Float): LongArray {
		val candidates = LongArray(SPLIT_KIND_COUNT) { NO_CANDIDATE }

		for (stepX in CANDIDATE_STEPS) {
			for (stepY in CANDIDATE_STEPS) {
				val candidateX = floatNeighbor(roundedX, stepX)
				val candidateY = floatNeighbor(roundedY, stepY)

				if (!candidateX.isFinite() || !candidateY.isFinite()) {
					continue
				}

				val placement = classifySplitCandidate(halfEdge, candidateX.toDouble(), candidateY.toDouble())

				if (placement != SPLIT_INVALID && candidates[placement] == NO_CANDIDATE) {
					candidates[placement] = packFloatPoint(candidateX, candidateY)
				}
			}
		}

		return candidates
	}

	/**
	 * Appends one split candidate, gathers its cavity, and inserts it when approved; otherwise gives
	 * its vertex slot back.
	 *
	 * @param Int           start      The edge's start.
	 * @param Int           end        The edge's end.
	 * @param Long          packed     The candidate as a packed Float point.
	 * @param Int           placement  SPLIT_ON_SEGMENT, SPLIT_LEFT, or SPLIT_RIGHT.
	 * @param Boolean       cutsInside Whether it lies on the inside of a boundary edge.
	 * @param SplitApproval approval   Decides it.
	 * @return Int The new vertex, or [NO_VERTEX].
	 */
	private fun trySplitCandidate(start: Int, end: Int, packed: Long, placement: Int, cutsInside: Boolean, approval: SplitApproval): Int {
		val halfEdge = store.findHalfEdge(start, end)
		val opposed = store.twin[halfEdge]
		val candidate = store.appendVertex(unpackFloatX(packed).toDouble(), unpackFloatY(packed).toDouble())
		val leftTriangle = realTriangleOrNone(halfEdge / 3)
		val rightTriangle = realTriangleOrNone(opposed / 3)

		when (placement) {
			SPLIT_ON_SEGMENT -> collectCavity(candidate, leftTriangle, rightTriangle)
			SPLIT_LEFT -> collectCavity(candidate, leftTriangle, seedAcross(rightTriangle, candidate))
			else -> collectCavity(candidate, rightTriangle, seedAcross(leftTriangle, candidate))
		}

		if (!approval.approves(candidate, cutsInside, nearestCavityDistanceSquared(candidate))) {
			store.removeLastVertex()

			return NO_VERTEX
		}

		if (placement == SPLIT_ON_SEGMENT) {
			inserter.insertLocated(candidate, true, halfEdge)

			return candidate
		}

		val wasConstrained = store.constrained[halfEdge]
		val ringCrossings = store.boundaryCount[halfEdge]
		inserter.insertLocated(candidate, false, if (placement == SPLIT_LEFT) halfEdge / 3 else opposed / 3)

		// The constraint held the candidate on its side; now it runs through the candidate instead.
		val oldEdge = store.findHalfEdge(start, end)
		check(oldEdge != HalfEdgeTriangulation.NO_HALF_EDGE) { "the constrained edge $start -> $end flipped during a split" }
		store.setEdgeAttributes(oldEdge, false, 0)
		store.setEdgeAttributes(store.findHalfEdge(start, candidate), wasConstrained, ringCrossings)
		store.setEdgeAttributes(store.findHalfEdge(candidate, end), wasConstrained, ringCrossings)
		val sliver = if (store.origin[store.previous(oldEdge)] == candidate) oldEdge else store.twin[oldEdge]
		check(store.origin[store.previous(sliver)] == candidate) { "no triangle joins $start -> $end to the split vertex" }
		store.setRegion(sliver / 3, store.regions[store.twin[sliver] / 3])
		inserter.legalizeFrom(sliver)

		return candidate
	}

	/**
	 * Where a split candidate sits against the edge it splits.
	 *
	 * @param Int    halfEdge   The edge, start -> end.
	 * @param Double candidateX The candidate's x.
	 * @param Double candidateY The candidate's y.
	 * @return Int SPLIT_ON_SEGMENT, SPLIT_LEFT, SPLIT_RIGHT, or SPLIT_INVALID.
	 */
	private fun classifySplitCandidate(halfEdge: Int, candidateX: Double, candidateY: Double): Int {
		val start = store.origin[halfEdge]
		val end = store.destination(halfEdge)
		val side = sideOf(start, end, candidateX, candidateY)

		if (side == 0.0) {
			return if (strictlyBetween(start, end, candidateX, candidateY)) SPLIT_ON_SEGMENT else SPLIT_INVALID
		}

		val onLeft = side > 0.0
		val sideEdge = if (onLeft) halfEdge else store.twin[halfEdge]
		val apex = store.origin[store.previous(sideEdge)]

		if (apex != store.ghostVertex) {
			// Strictly inside the triangle on that side: left of its other two edges as well.
			val sideEnd = store.destination(sideEdge)
			val sideStart = store.origin[sideEdge]

			if (sideOf(sideEnd, apex, candidateX, candidateY) <= 0.0 || sideOf(apex, sideStart, candidateX, candidateY) <= 0.0) {
				return SPLIT_INVALID
			}
		}

		return if (onLeft) SPLIT_LEFT else SPLIT_RIGHT
	}

	/**
	 * A triangle slot, or -1 for a ghost.
	 *
	 * @param Int triangle The slot.
	 * @return Int The slot when real, else -1.
	 */
	private fun realTriangleOrNone(triangle: Int): Int = if (store.isGhostTriangle(triangle)) -1 else triangle

	/**
	 * The triangle across a split edge as a cavity seed, when the candidate lies in its circumcircle.
	 *
	 * @param Int triangle  The real triangle across, or -1.
	 * @param Int candidate The candidate.
	 * @return Int The triangle, or -1.
	 */
	private fun seedAcross(triangle: Int, candidate: Int): Int {
		if (triangle < 0) {
			return -1
		}

		val base = 3 * triangle

		return if (store.inCircle(store.origin[base], store.origin[base + 1], store.origin[base + 2], candidate) > 0.0) triangle else -1
	}

	/**
	 * Tests the candidate against a triangle the walk is in.
	 *
	 * @param Int triangle  The triangle.
	 * @param Int entered   The half-edge the walk came in through (the candidate is strictly inside it), or none.
	 * @param Int candidate The candidate.
	 * @return Int A LOCATED_* result, or [STILL_BEYOND] when the candidate lies past the triangle.
	 */
	private fun placeInTriangle(triangle: Int, entered: Int, candidate: Int): Int {
		var zeroCount = 0
		var zeroEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		val base = 3 * triangle

		for (offset in 0 until 3) {
			val halfEdge = base + offset

			if (halfEdge == entered) {
				continue
			}

			val side = store.orient(store.origin[halfEdge], store.destination(halfEdge), candidate)

			if (side < 0.0) {
				return STILL_BEYOND
			}

			if (side == 0.0) {
				zeroCount++
				zeroEdge = halfEdge
			}
		}

		return when (zeroCount) {
			0 -> {
				locatedAt = triangle
				LOCATED_INSIDE
			}

			1 -> {
				placeOnEdge(zeroEdge)
			}

			else -> {
				LOCATED_COINCIDENT
			}
		}
	}

	/**
	 * Reports a candidate lying on an edge's open segment: blocked when the edge is a constraint.
	 *
	 * @param Int halfEdge The edge.
	 * @return Int LOCATED_BLOCKED or LOCATED_ON_EDGE, with [locatedAt] set to the edge.
	 */
	private fun placeOnEdge(halfEdge: Int): Int {
		locatedAt = halfEdge

		return if (store.constrained[halfEdge]) LOCATED_BLOCKED else LOCATED_ON_EDGE
	}

	/**
	 * Crosses an edge the walk leaves through, unless it is a constraint or leads out of the inside.
	 *
	 * @param Int exitEdge The half-edge being left through.
	 * @return Int The half-edge entered on the far side, or NO_HALF_EDGE with [lastStop] set to the result.
	 */
	private fun crossEdge(exitEdge: Int): Int {
		if (store.constrained[exitEdge]) {
			locatedAt = exitEdge
			lastStop = LOCATED_BLOCKED

			return HalfEdgeTriangulation.NO_HALF_EDGE
		}

		val across = store.twin[exitEdge]

		if (store.isGhostTriangle(across / 3) || !store.regions[across / 3]) {
			lastStop = LOCATED_UNRESOLVED

			return HalfEdgeTriangulation.NO_HALF_EDGE
		}

		return across
	}

	/**
	 * Continues the walk from a vertex the segment passes exactly through: along an edge lying on the
	 * segment, or into the triangle around the vertex that the segment enters.
	 *
	 * @param Int vertex    The vertex on the segment.
	 * @param Int candidate The candidate.
	 * @param Int stepLimit The most vertices to pass through.
	 * @return Int A LOCATED_* result, or [STILL_BEYOND] with [locatedAt] set to the half-edge entered next.
	 */
	private fun leaveVertex(vertex: Int, candidate: Int, stepLimit: Int): Int {
		var current = vertex

		for (step in 0 until stepLimit) {
			val exit = store.exitEdgeFrom(current, candidate)

			if (exit == HalfEdgeTriangulation.NO_EXIT) {
				return LOCATED_UNRESOLVED
			}

			if (exit < 0) {
				val neighbor = -(exit + 1)
				val edge = store.findHalfEdge(current, neighbor)

				if (!store.regions[edge / 3] && !store.regions[store.twin[edge] / 3]) {
					return LOCATED_UNRESOLVED
				}

				val order = orderAlong(current, neighbor, candidate)

				if (order == 0) {
					return LOCATED_COINCIDENT
				}

				if (order < 0) {
					return placeOnEdge(edge)
				}

				current = neighbor

				continue
			}

			return placeAcrossFarEdge(exit, candidate)
		}

		return LOCATED_UNRESOLVED
	}

	/**
	 * Places the candidate against the far edge of the triangle the segment enters from a vertex: the
	 * segment passes between the two spokes, so only that edge can hold the candidate.
	 *
	 * @param Int exit      The triangle's far edge, the one the segment crosses.
	 * @param Int candidate The candidate.
	 * @return Int A LOCATED_* result, or [STILL_BEYOND] with [locatedAt] set to the half-edge entered next.
	 */
	private fun placeAcrossFarEdge(exit: Int, candidate: Int): Int {
		if (!store.regions[exit / 3]) {
			return LOCATED_UNRESOLVED
		}

		val side = store.orient(store.origin[exit], store.destination(exit), candidate)

		if (side > 0.0) {
			locatedAt = exit / 3

			return LOCATED_INSIDE
		}

		if (side == 0.0) {
			return placeOnEdge(exit)
		}

		val crossing = crossEdge(exit)

		if (crossing == HalfEdgeTriangulation.NO_HALF_EDGE) {
			return lastStop
		}

		locatedAt = crossing

		return STILL_BEYOND
	}

	/**
	 * Where a candidate lies along an edge running from a vertex on the walk's segment, toward the
	 * candidate, exactly (see [compareAlongLine]).
	 *
	 * @param Int from      The vertex the edge leaves.
	 * @param Int to        The edge's far end, ahead on the segment.
	 * @param Int candidate The candidate, ahead on the segment.
	 * @return Int Negative before [to], zero at it, positive beyond it.
	 */
	private fun orderAlong(from: Int, to: Int, candidate: Int): Int =
		compareAlongLine(store.pointX(to) - store.pointX(from), store.pointY(to) - store.pointY(from), store.pointX(to), store.pointY(to), store.pointX(candidate), store.pointY(candidate))

	/**
	 * Whether a point collinear with an edge lies strictly between its ends, exactly (see
	 * [compareAlongLine]).
	 *
	 * @param Int    start  The edge's start.
	 * @param Int    end    The edge's end.
	 * @param Double pointX The point's x.
	 * @param Double pointY The point's y.
	 * @return Boolean True inside the open segment.
	 */
	private fun strictlyBetween(start: Int, end: Int, pointX: Double, pointY: Double): Boolean {
		val directionX = store.pointX(end) - store.pointX(start)
		val directionY = store.pointY(end) - store.pointY(start)

		return compareAlongLine(directionX, directionY, store.pointX(start), store.pointY(start), pointX, pointY) > 0 &&
			compareAlongLine(directionX, directionY, store.pointX(end), store.pointY(end), pointX, pointY) < 0
	}

	/**
	 * The side of the walk's segment a vertex lies on.
	 *
	 * @param Int vertex The vertex.
	 * @return Double Positive left of the segment's direction, negative right, zero on its line.
	 */
	private fun sideOfRay(vertex: Int): Double = store.orientOf(rayFromX, rayFromY, rayToX, rayToY, vertex)

	/**
	 * The side of an edge's line a point lies on.
	 *
	 * @param Int    start  The edge's start.
	 * @param Int    end    The edge's end.
	 * @param Double pointX The point's x.
	 * @param Double pointY The point's y.
	 * @return Double Positive left of start -> end.
	 */
	private fun sideOf(start: Int, end: Int, pointX: Double, pointY: Double): Double = store.orientAt(pointX, pointY, start, end)

	/**
	 * Adds a vertex to the cavity's vertex list once.
	 *
	 * @param Int vertex The vertex.
	 */
	private fun addCavityVertex(vertex: Int) {
		if (vertexStamp[vertex] != stamp) {
			vertexStamp[vertex] = stamp
			cavityVertices.push(vertex)
		}
	}

	companion object {
		/** The candidate lies strictly inside the triangle [locatedAt]. */
		const val LOCATED_INSIDE: Int = 0

		/** The candidate lies on the open segment of the unconstrained half-edge [locatedAt]. */
		const val LOCATED_ON_EDGE: Int = 1

		/** The constrained half-edge [locatedAt] lies across the walk or under the candidate. */
		const val LOCATED_BLOCKED: Int = 2

		/** The candidate coincides with a vertex. */
		const val LOCATED_COINCIDENT: Int = 3

		/** The walk left the inside through a vertex, or could not start. */
		const val LOCATED_UNRESOLVED: Int = 4

		/** [splitSegment]'s result when nothing was inserted. */
		const val NO_VERTEX: Int = -1

		/** The walk has not reached the candidate yet. */
		private const val STILL_BEYOND: Int = -1

		/** A split candidate exactly on the segment. */
		private const val SPLIT_ON_SEGMENT: Int = 0

		/** A split candidate strictly left of the segment, inside the triangle there. */
		private const val SPLIT_LEFT: Int = 1

		/** A split candidate strictly right of the segment, inside the triangle there. */
		private const val SPLIT_RIGHT: Int = 2

		/** The valid split candidate kinds, each an index below this. */
		private const val SPLIT_KIND_COUNT: Int = 3

		/** A split candidate that lies past the segment's ends or outside the triangle on its side. */
		private const val SPLIT_INVALID: Int = 3

		/** No split candidate of that kind was found: a packed NaN point, which no candidate can be. */
		private const val NO_CANDIDATE: Long = 0x7FC000007FC00000L

		/** The Float steps tried around a rounded split point, the point itself first. */
		private val CANDIDATE_STEPS = intArrayOf(0, -1, 1)
	}
}

/**
 * The Float one step away from a value, toward positive (step 1) or negative (step -1) infinity, or
 * the value itself (step 0).
 *
 * @param Float value The value.
 * @param Int   step  -1, 0, or 1.
 * @return Float The neighbor.
 */
internal fun floatNeighbor(value: Float, step: Int): Float {
	if (step == 0) {
		return value
	}

	if (value == 0.0f) {
		return if (step > 0) Float.MIN_VALUE else -Float.MIN_VALUE
	}

	// Away from zero the magnitude's bit pattern grows by one; toward zero it shrinks by one.
	val awayFromZero = (step > 0) == (value > 0.0f)
	val bits = value.toRawBits()

	return Float.fromBits(if (awayFromZero) bits + 1 else bits - 1)
}

/**
 * Packs a Float point into one Long.
 *
 * @param Float x The x.
 * @param Float y The y.
 * @return Long The packed point.
 */
private fun packFloatPoint(x: Float, y: Float): Long = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)

/**
 * A packed Float point's x.
 *
 * @param Long packed The packed point.
 * @return Float The x.
 */
private fun unpackFloatX(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())

/**
 * A packed Float point's y.
 *
 * @param Long packed The packed point.
 * @return Float The y.
 */
private fun unpackFloatY(packed: Long): Float = Float.fromBits(packed.toInt())