package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.packUndirectedPair
import org.umamo.geometry.packVertexPair
import org.umamo.geometry.pairFirst
import org.umamo.geometry.pairSecond
import org.umamo.geometry.triangulation.ConstraintInserter
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import org.umamo.geometry.triangulation.compareAlongLine
import kotlin.math.sqrt

/*
 * Greedy insertion of a guide into a classified triangulation - Garland and Heckbert's greedy insertion
 * turned around: rather than approximating a height field from scratch, the triangulation already
 * covers the plane and gains, one step at a time, what holds the guide where its linear interpolation
 * strays furthest, until nothing strays past the tolerance or the share of the cap runs out.
 *
 * Two piecewise-linear surfaces over one plane differ linearly inside each cell of their overlay, so
 * they part furthest at an overlay vertex: a guide vertex, or a point where a guide edge crosses a
 * triangulation edge (the triangulation's own vertices carry the guide's values and part by nothing).
 * Both are checked exactly, inside the domain: every candidate vertex where it lies, and every guide
 * edge at each of its crossings, found by walking the edge's straight line through the triangulation.
 *
 * A vertex that strays is inserted at its exact Float point.  An edge that strays is held as a chain of
 * constraints through vertices along it: its ends go in first; from an end in the domain toward one
 * outside it, the chain follows the edge to where it leaves the domain; any constraint across the edge
 * splits where the edge crosses it and joins the chain; then each piece becomes a constraint.  The
 * triangulation then carries the guide's crease, no crossing is left, and no later flip can take it
 * away.  An edge with both ends outside the domain grows its chain both ways from its worst crossing;
 * one whose chain is whole and still strays gains a vertex at its worst crossing.  A boundary edge
 * splits only where the caller's check allows; a constraint with no room for the split gives way when
 * it bounds nothing (an inner segment) and holds no other guide edge's crease, and otherwise stops the
 * chain - two creases that cross where neither can split (a folded guide) keep the first held, the
 * other goes without.  An operation backstop bounds the run besides.
 *
 * An error is the largest, over the guide's fields, of the distance between the guide's value at a
 * point and the triangulation's interpolation there of the guide's values at its own vertices, divided
 * by the field's scale.  A vertex the guide inserts carries the guide's exact values; any other vertex
 * carries the guide's interpolation at its point, so a vertex's values never change, and a candidate's
 * error changes only when a triangle it was measured in goes.  The worst candidate goes next, ties to
 * the lowest.  A candidate that can be held no way at all is left out.
 */

/** A vertex candidate's triangle when it has none: outside the domain, already in place, or left out. */
private const val NO_TRIANGLE: Long = -1L

/** An edge candidate's error when it is left out or never meets the domain. */
private const val OUT_OF_PLAY: Double = -1.0

/** A guide vertex whose presence has not been looked up. */
private const val UNKNOWN: Int = -2

/** What [GuideInsertion.edgeThrough] returns for a point on two edges' lines: a corner. */
private const val ON_CORNER: Int = -2

/**
 * One greedy insertion over a classified store.
 *
 * @property HalfEdgeTriangulation             store              The store, regions adopted.
 * @property SteinerInsertion                  steiner            The store's insertion mechanics.
 * @property PiecewiseLinearGuide              guide              The guide.
 * @property Double                            tolerance          The largest error left alone, in the plane's units.
 * @property Double                            separationSquared  The squared separation floor.
 * @property BoundarySplitCheck?               boundarySplitCheck Decides every boundary edge split, or null to allow them all.
 * @property ((HalfEdgeTriangulation) -> Unit)? afterEachStep      Called after every inserted vertex or constraint.
 */
internal class GuideInsertion(
	private val store: HalfEdgeTriangulation,
	private val steiner: SteinerInsertion,
	private val guide: PiecewiseLinearGuide,
	private val tolerance: Double,
	private val separationSquared: Double,
	private val boundarySplitCheck: BoundarySplitCheck?,
	private val afterEachStep: ((HalfEdgeTriangulation) -> Unit)?,
) {
	private val valueCount = 2 * guide.fieldCount
	private val vertexValues = DoubleArray(store.vertexCapacity * valueCount)
	private val vertexValuesKnown = BooleanArray(store.vertexCapacity)
	private val pointValues = DoubleArray(valueCount)
	private val endValues = DoubleArray(valueCount)
	private val inserter = ConstraintInserter(store)

	/** The guide's candidate vertices, each with the triangle it was measured in and its error there. */
	private val vertexCandidateCount = guide.candidates.size
	private val vertexTriangle = LongArray(vertexCandidateCount) { NO_TRIANGLE }
	private val vertexError = DoubleArray(vertexCandidateCount)

	/**
	 * Every guide edge as a packed (low, high) pair, each with its error, the triangles its trace entered,
	 * and where its worst crossing lies: the point, on the crossed edge, and that edge's ends.
	 */
	private val guideEdges: LongArray = uniqueEdgesOf(guide.triangles)
	private val edgeError = DoubleArray(guideEdges.size) { OUT_OF_PLAY }
	private val edgeTriangles = arrayOfNulls<LongArray>(guideEdges.size)
	private val edgeWorstX = DoubleArray(guideEdges.size)
	private val edgeWorstY = DoubleArray(guideEdges.size)
	private val edgeWorstCrossed = LongArray(guideEdges.size)

	/**
	 * Per guide edge being held, the store vertices along it from its low end toward its high end, and
	 * whether each side is final: at the end's own vertex, or where the edge leaves the domain.
	 */
	private val edgeChain = arrayOfNulls<IntArray>(guideEdges.size)
	private val lowSideFinal = BooleanArray(guideEdges.size)
	private val highSideFinal = BooleanArray(guideEdges.size)

	/** Per guide vertex, the store vertex at its point, [SteinerInsertion.NO_VERTEX], or [UNKNOWN]. */
	private val storeVertexOfGuide = IntArray(guide.points.size / 2) { UNKNOWN }

	/** Per guide vertex, whether inserting it was refused, so it is not tried again. */
	private val insertionRefused = BooleanArray(guide.points.size / 2)

	/** The chain pieces made constraints, as packed (low, high) pairs, each split piece replaced by its halves. */
	private val heldPieces = HashSet<Long>()
	private val backstop = OperationBackstop(store)

	/** The most vertices the current [run] may insert. */
	private var cap = 0

	/** What the last obstacle walk met: a constrained half-edge, or a vertex on its line. */
	private var metHalfEdge = HalfEdgeTriangulation.NO_HALF_EDGE
	private var metVertex = SteinerInsertion.NO_VERTEX

	/** The corner the line leaves a triangle through, when [exitOf] found no edge. */
	private var exitCorner = SteinerInsertion.NO_VERTEX

	/** The triangle the next locate starts from. */
	private var walkStart = 0

	/** The line the current trace follows. */
	private var lineStartX = 0.0
	private var lineStartY = 0.0
	private var lineEndX = 0.0
	private var lineEndY = 0.0

	/** The largest error the current trace has met, or [OUT_OF_PLAY] before it enters the domain, and where. */
	private var traceError = OUT_OF_PLAY
	private var traceWorstX = 0.0
	private var traceWorstY = 0.0
	private var traceWorstCrossed = 0L

	/** The triangles the current trace entered. */
	private var traced = LongArray(16)
	private var tracedCount = 0

	/** Where [crossLine] found two lines cross: along the segment (0 at its start, 1 at its end) and across the half-edge (0 at its origin, 1 at its destination). */
	private var crossingAlong = 0.0
	private var crossingAcross = 0.0

	/** The vertices inserted: guide vertices and crossing points. */
	var insertedCount: Int = 0
		private set

	/** The vertices inserted at a guide edge's crossing. */
	var crossingPointCount: Int = 0
		private set

	/** The constraints no boundary runs along that gave way to a crease. */
	var releasedConstraintCount: Int = 0
		private set

	/** The guide edges made constraints. */
	var constrainedEdgeCount: Int = 0
		private set

	/** The largest error any candidate kept, once [run] returns. */
	var largestRemainingError: Double = 0.0
		private set

	/** Whether the cap stopped insertion with a candidate still past the tolerance. */
	var stoppedByCap: Boolean = false
		private set

	/** Whether the operation backstop stopped insertion, which only a defect reaches. */
	val stoppedByBackstop: Boolean
		get() = backstop.tripped

	/**
	 * Holds the worst candidate in place while it strays past the tolerance and the cap allows.
	 *
	 * @param Int cap The most vertices to insert.
	 */
	fun run(cap: Int) {
		this.cap = cap
		scoreEveryCandidate()

		while (true) {
			val worst = worstCandidate()
			val error = if (worst < 0) 0.0 else errorOfCandidate(worst)
			largestRemainingError = error

			if (worst < 0 || error <= tolerance) {
				return
			}

			if (!backstop.spend()) {
				return
			}

			if (holdCandidate(worst)) {
				afterEachStep?.invoke(store)
				refresh()

				continue
			}

			if (stoppedByCap) {
				return
			}

			leaveOut(worst)
		}
	}

	/**
	 * Whether the triangulation strays from the guide past the tolerance anywhere, inserting nothing;
	 * [run] may follow on the same insertion, after the store changed.
	 *
	 * @return Boolean True when holding the guide would change something.
	 */
	fun strays(): Boolean {
		scoreEveryCandidate()
		val worst = worstCandidate()

		return worst >= 0 && errorOfCandidate(worst) > tolerance
	}

	/**
	 * Measures every vertex candidate where it lies and traces every edge, forgetting what an earlier
	 * scoring found: the store may have changed since.
	 */
	private fun scoreEveryCandidate() {
		vertexTriangle.fill(NO_TRIANGLE)
		storeVertexOfGuide.fill(UNKNOWN)

		for (candidate in 0 until vertexCandidateCount) {
			scoreVertex(candidate)
		}

		for (edge in guideEdges.indices) {
			traceEdge(edge)
		}
	}

	/**
	 * The in-play candidate with the largest error, ties to the lowest: the vertex candidates first, then
	 * the edges.
	 *
	 * @return Int The candidate, or -1 when none is in play.
	 */
	private fun worstCandidate(): Int {
		var worst = -1
		var worstError = OUT_OF_PLAY

		for (candidate in 0 until vertexCandidateCount + guideEdges.size) {
			val error = errorOfCandidate(candidate)

			if (error > worstError) {
				worst = candidate
				worstError = error
			}
		}

		return worst
	}

	/**
	 * A candidate's current error.
	 *
	 * @param Int candidate The candidate: a vertex below the vertex candidate count, an edge from it on.
	 * @return Double The error, or [OUT_OF_PLAY].
	 */
	private fun errorOfCandidate(candidate: Int): Double {
		if (candidate < vertexCandidateCount) {
			return if (vertexTriangle[candidate] == NO_TRIANGLE) OUT_OF_PLAY else vertexError[candidate]
		}

		return edgeError[candidate - vertexCandidateCount]
	}

	/**
	 * Holds one straying candidate: a vertex is inserted; an edge takes one step toward its chain of
	 * constraints (see [holdEdge]).
	 *
	 * @param Int candidate The candidate.
	 * @return Boolean True when something changed; false when the candidate cannot be held (or the cap stops it).
	 */
	private fun holdCandidate(candidate: Int): Boolean {
		if (candidate < vertexCandidateCount) {
			return insertGuideVertex(guide.candidates[candidate])
		}

		return holdEdge(candidate - vertexCandidateCount)
	}

	/**
	 * One step toward holding a guide edge as a chain of constraints through store vertices along it.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 * @return Boolean True when the store changed.
	 */
	private fun holdEdge(edge: Int): Boolean {
		val low = pairFirst(guideEdges[edge])
		val high = pairSecond(guideEdges[edge])

		if (insertAbsentEnds(low, high)) {
			return true
		}

		if (stoppedByCap) {
			return false
		}

		if (edgeChain[edge] == null) {
			val lowVertex = presentVertexOf(low)
			val highVertex = presentVertexOf(high)

			// The edge runs through the domain with both ends outside it: the chain grows both ways from
			// its worst crossing.
			if (lowVertex == SteinerInsertion.NO_VERTEX && highVertex == SteinerInsertion.NO_VERTEX) {
				return seedChain(edge)
			}

			edgeChain[edge] = listOf(lowVertex, highVertex).filter { vertex -> vertex != SteinerInsertion.NO_VERTEX }.toIntArray()
			lowSideFinal[edge] = lowVertex != SteinerInsertion.NO_VERTEX
			highSideFinal[edge] = highVertex != SteinerInsertion.NO_VERTEX
		}

		if (!lowSideFinal[edge] && extendChain(edge, low, towardLow = true)) {
			return true
		}

		if (!highSideFinal[edge] && extendChain(edge, high, towardLow = false)) {
			return true
		}

		if (stoppedByCap) {
			return false
		}

		return constrainChain(edge)
	}

	/**
	 * Inserts a guide edge's ends the domain lacks, the first that goes in, marking each that cannot so
	 * it is not tried again.
	 *
	 * @param Int low  The edge's low end.
	 * @param Int high The edge's high end.
	 * @return Boolean True when an end went in; false when none did, or the cap stopped one ([stoppedByCap]).
	 */
	private fun insertAbsentEnds(low: Int, high: Int): Boolean {
		for (end in intArrayOf(low, high)) {
			if (presentVertexOf(end) != SteinerInsertion.NO_VERTEX || insertionRefused[end]) {
				continue
			}

			if (insertGuideVertex(end)) {
				return true
			}

			if (stoppedByCap) {
				return false
			}

			insertionRefused[end] = true
		}

		return false
	}

	/**
	 * Starts the chain of an edge whose ends both lie outside the domain at a vertex inserted at its
	 * worst crossing.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 * @return Boolean True when the seed went in.
	 */
	private fun seedChain(edge: Int): Boolean {
		val seed = insertAtWorstCrossing(edge)

		if (seed == SteinerInsertion.NO_VERTEX) {
			return false
		}

		edgeChain[edge] = intArrayOf(seed)

		return true
	}

	/**
	 * Makes the first piece of a final chain that is not yet a constraint one, splitting a constraint in
	 * its way where the piece crosses it (the split joins the chain) or, with no room for the split,
	 * releasing a constraint that bounds nothing.  When every piece holds and the edge still strays, a
	 * vertex goes in at its worst crossing.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 * @return Boolean True when the store changed.
	 */
	private fun constrainChain(edge: Int): Boolean {
		var position = 0

		while (position < edgeChain[edge]!!.size - 1) {
			val chain = edgeChain[edge]!!
			val start = chain[position]
			val finish = chain[position + 1]
			val halfEdge = store.findHalfEdge(start, finish)

			if (halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.constrained[halfEdge]) {
				position++

				continue
			}

			if (inserter.insert(start, finish, false)) {
				constrainedEdgeCount++
				holdInsertedChain()

				return true
			}

			val blocking = store.findHalfEdge(inserter.crossedStart, inserter.crossedEnd)
			val split = splitWhereCrossed(blocking, store.pointX(start), store.pointY(start), store.pointX(finish), store.pointY(finish))

			if (split == SteinerInsertion.NO_VERTEX) {
				return !stoppedByCap && releaseIfNeutral(blocking)
			}

			edgeChain[edge] = chain.copyOfRange(0, position + 1) + split + chain.copyOfRange(position + 1, chain.size)

			return true
		}

		return insertAtWorstCrossing(edge) != SteinerInsertion.NO_VERTEX
	}

	/**
	 * Releases a constraint no boundary ring runs along and no crease holds, so a crease can cross it.
	 * A crease never gives way to another: two that cross where neither can split would otherwise
	 * release each other without end.
	 *
	 * @param Int halfEdge The constrained half-edge.
	 * @return Boolean True when released; false for a boundary or a held crease.
	 */
	private fun releaseIfNeutral(halfEdge: Int): Boolean {
		if (store.boundaryCount[halfEdge] != 0 || store.edgeKey(halfEdge) in heldPieces) {
			return false
		}

		inserter.release(halfEdge)
		releasedConstraintCount++

		return true
	}

	/** Notes every piece the last constraint insertion marked, so none of them gives way to another crease. */
	private fun holdInsertedChain() {
		val chain = inserter.insertedChain

		for (position in 0 until chain.size - 1) {
			heldPieces.add(packUndirectedPair(chain[position], chain[position + 1]))
		}
	}

	/**
	 * Extends a chain from its end toward a guide end the domain lacks: along the edge's line through
	 * any vertices on it, until the line crosses a constraint, which splits there - the side is final
	 * when that constraint bounds the domain, or when the line reaches the end's point without one.
	 *
	 * @param Int     edge      The edge's position in [guideEdges].
	 * @param Int     target    The guide end the domain lacks.
	 * @param Boolean towardLow Whether the target is the edge's low end (the chain's front).
	 * @return Boolean True when the store changed.
	 */
	private fun extendChain(edge: Int, target: Int, towardLow: Boolean): Boolean {
		val targetX = guide.points[2 * target].toDouble()
		val targetY = guide.points[2 * target + 1].toDouble()

		while (true) {
			val chain = edgeChain[edge]!!
			val from = if (towardLow) chain.first() else chain.last()

			if (walkToward(from, targetX, targetY)) {
				// The end's point lies in the domain, but its vertex could not go in: the chain ends short.
				finishSide(edge, towardLow)

				return false
			}

			if (metVertex != SteinerInsertion.NO_VERTEX && metVertex !in chain) {
				addToChain(edge, metVertex, towardLow)

				continue
			}

			if (metHalfEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
				finishSide(edge, towardLow)

				return false
			}

			val boundsDomain = store.boundaryCount[metHalfEdge] % 2 == 1
			val split = splitWhereCrossed(metHalfEdge, lineStartX, lineStartY, lineEndX, lineEndY)

			if (stoppedByCap) {
				return false
			}

			// With no room for the split, a constraint that bounds nothing gives way.
			if (split == SteinerInsertion.NO_VERTEX) {
				if (releaseIfNeutral(metHalfEdge)) {
					return true
				}

				finishSide(edge, towardLow)

				return false
			}

			addToChain(edge, split, towardLow)

			if (boundsDomain) {
				finishSide(edge, towardLow)
			}

			return true
		}
	}

	/**
	 * Adds a vertex at one end of a chain.
	 *
	 * @param Int     edge      The edge's position in [guideEdges].
	 * @param Int     vertex    The store vertex.
	 * @param Boolean towardLow Whether it goes at the low end (the front).
	 */
	private fun addToChain(edge: Int, vertex: Int, towardLow: Boolean) {
		val chain = edgeChain[edge]!!
		edgeChain[edge] = if (towardLow) intArrayOf(vertex) + chain else chain + vertex
	}

	/**
	 * Marks one side of a chain final.
	 *
	 * @param Int     edge      The edge's position in [guideEdges].
	 * @param Boolean towardLow Whether it is the low side.
	 */
	private fun finishSide(edge: Int, towardLow: Boolean) {
		if (towardLow) {
			lowSideFinal[edge] = true
		} else {
			highSideFinal[edge] = true
		}
	}

	/**
	 * Splits a constraint where a segment's line crosses it, clamped to it (a parallel segment splits it
	 * at its middle), counting the split against the cap.
	 *
	 * @param Int    halfEdge The constrained half-edge.
	 * @param Double startX   The segment's start x.
	 * @param Double startY   The segment's start y.
	 * @param Double endX     The segment's end x.
	 * @param Double endY     The segment's end y.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun splitWhereCrossed(halfEdge: Int, startX: Double, startY: Double, endX: Double, endY: Double): Int {
		if (capReached()) {
			return SteinerInsertion.NO_VERTEX
		}

		val across = if (crossLine(startX, startY, endX, endY, halfEdge)) crossingAcross.coerceIn(0.0, 1.0) else 0.5
		val vertex = splitConstraint(halfEdge, edgePointX(halfEdge, across), edgePointY(halfEdge, across))

		if (vertex != SteinerInsertion.NO_VERTEX) {
			countInserted(vertex, atCrossing = true)
		}

		return vertex
	}

	/**
	 * Whether the run has inserted all the cap allows, marking [stoppedByCap] when it has.
	 *
	 * @return Boolean True when no further vertex may go in.
	 */
	private fun capReached(): Boolean {
		if (insertedCount < cap) {
			return false
		}

		stoppedByCap = true

		return true
	}

	/**
	 * Counts an inserted vertex and starts the next locate beside it.
	 *
	 * @param Int     vertex     The vertex.
	 * @param Boolean atCrossing Whether it went in at a guide edge's crossing.
	 */
	private fun countInserted(vertex: Int, atCrossing: Boolean) {
		insertedCount++

		if (atCrossing) {
			crossingPointCount++
		}

		walkStart = store.vertexHalfEdge[vertex] / 3
	}

	/**
	 * Inserts a vertex where a guide edge strays furthest, on the edge it crosses there: a constrained
	 * edge is split (a boundary edge only where the caller's check allows), any other gains the point
	 * like any vertex.  Its values are the guide's at its Float point.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun insertAtWorstCrossing(edge: Int): Int {
		if (capReached()) {
			return SteinerInsertion.NO_VERTEX
		}

		val origin = pairFirst(edgeWorstCrossed[edge])
		val destination = pairSecond(edgeWorstCrossed[edge])
		val halfEdge = store.findHalfEdge(origin, destination)

		if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
			return SteinerInsertion.NO_VERTEX
		}

		val vertex =
			if (store.constrained[halfEdge]) {
				splitConstraint(halfEdge, edgeWorstX[edge], edgeWorstY[edge])
			} else {
				insertPoint(edgeWorstX[edge].toFloat().toDouble(), edgeWorstY[edge].toFloat().toDouble())
			}

		if (vertex != SteinerInsertion.NO_VERTEX) {
			countInserted(vertex, atCrossing = true)
		}

		return vertex
	}

	/**
	 * Inserts one guide vertex at its exact point, unless it lies outside the domain or too close to a
	 * vertex it would join; on a constraint, it splits the constraint there.
	 *
	 * @param Int guideVertex The guide vertex.
	 * @return Boolean True when inserted.
	 */
	private fun insertGuideVertex(guideVertex: Int): Boolean {
		if (capReached()) {
			return false
		}

		val x = guide.points[2 * guideVertex].toDouble()
		val y = guide.points[2 * guideVertex + 1].toDouble()
		val vertex = insertPoint(x, y)

		if (vertex == SteinerInsertion.NO_VERTEX) {
			storeVertexOfGuide[guideVertex] = SteinerInsertion.NO_VERTEX

			return false
		}

		countInserted(vertex, atCrossing = false)

		// A split that could not take the point itself took a Float neighbor of it, which carries the
		// guide's interpolation like any other vertex; the guide vertex stays absent.
		if (store.pointX(vertex) == x && store.pointY(vertex) == y) {
			guide.valuesAtVertex(guideVertex, pointValues)
			pointValues.copyInto(vertexValues, vertex * valueCount)
			vertexValuesKnown[vertex] = true
			storeVertexOfGuide[guideVertex] = vertex
		} else {
			storeVertexOfGuide[guideVertex] = SteinerInsertion.NO_VERTEX
		}

		return true
	}

	/**
	 * Inserts a vertex at a point in the domain's closure, unless it is too close to a vertex it would
	 * join; a point on a constraint splits it there (see [splitConstraint]).
	 *
	 * @param Double x The point's x, a Float value.
	 * @param Double y The point's y, a Float value.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun insertPoint(x: Double, y: Double): Int {
		val triangle = locate(x, y)

		if (triangle < 0) {
			return SteinerInsertion.NO_VERTEX
		}

		val onEdge = edgeThrough(triangle, x, y)

		if (onEdge == ON_CORNER) {
			// The point is a vertex already.
			return SteinerInsertion.NO_VERTEX
		}

		if (onEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.constrained[onEdge]) {
			val across = store.twin[onEdge] / 3
			val inDomain = store.regions[triangle] || (!store.isGhostTriangle(across) && store.regions[across])

			return if (inDomain) splitConstraint(onEdge, x, y) else SteinerInsertion.NO_VERTEX
		}

		if (!store.regions[triangle]) {
			return SteinerInsertion.NO_VERTEX
		}

		val isOnEdge = onEdge != HalfEdgeTriangulation.NO_HALF_EDGE
		val at = if (isOnEdge) onEdge else triangle
		val vertex = store.appendVertex(x, y)
		steiner.collectCavityAt(vertex, isOnEdge, at)

		if (steiner.nearestCavityDistanceSquared(vertex) < separationSquared) {
			store.removeLastVertex()

			return SteinerInsertion.NO_VERTEX
		}

		steiner.insert(vertex, isOnEdge, at)

		return vertex
	}

	/**
	 * Splits a constraint near a point on or beside it, keeping clear of every vertex the split would
	 * join; a boundary edge splits only where the caller's check allows.
	 *
	 * @param Int    halfEdge The constrained half-edge.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun splitConstraint(halfEdge: Int, x: Double, y: Double): Int {
		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val vertex = steiner.splitConstraint(halfEdge, x, y, separationSquared, boundarySplitCheck)

		// A held crease stays held through its halves.
		if (vertex != SteinerInsertion.NO_VERTEX && heldPieces.remove(packUndirectedPair(origin, destination))) {
			heldPieces.add(packUndirectedPair(origin, vertex))
			heldPieces.add(packUndirectedPair(vertex, destination))
		}

		return vertex
	}

	/** After a change: measures again every vertex candidate whose triangle went, and traces again every edge that entered one that went. */
	private fun refresh() {
		for (candidate in 0 until vertexCandidateCount) {
			val key = vertexTriangle[candidate]

			if (key != NO_TRIANGLE && store.triangleOfKey(key) < 0) {
				vertexTriangle[candidate] = NO_TRIANGLE
				scoreVertex(candidate)
			}
		}

		for (edge in guideEdges.indices) {
			val entered = edgeTriangles[edge] ?: continue

			if (entered.any { key -> store.triangleOfKey(key) < 0 }) {
				traceEdge(edge)
			}
		}
	}

	/**
	 * Locates a vertex candidate and records its triangle and error; a candidate already a vertex, or
	 * outside the domain, is out of play.
	 *
	 * @param Int candidate The vertex candidate.
	 */
	private fun scoreVertex(candidate: Int) {
		val guideVertex = guide.candidates[candidate]
		val x = guide.points[2 * guideVertex].toDouble()
		val y = guide.points[2 * guideVertex + 1].toDouble()
		val triangle = locate(x, y)

		if (triangle < 0 || !store.regions[triangle]) {
			return
		}

		val corner = cornerAt(triangle, x, y)

		if (corner != SteinerInsertion.NO_VERTEX) {
			storeVertexOfGuide[guideVertex] = corner

			return
		}

		val key = store.keyOfTriangle(triangle)
		vertexTriangle[candidate] = key
		guide.valuesAtVertex(guideVertex, pointValues)
		vertexError[candidate] = errorInTriangle(key, x, y)
	}

	/**
	 * The error at a point inside a triangle, against the guide's values there, already in [pointValues].
	 *
	 * @param Long   key The triangle.
	 * @param Double x   The point's x.
	 * @param Double y   The point's y.
	 * @return Double The error.
	 */
	private fun errorInTriangle(key: Long, x: Double, y: Double): Double {
		val first = triangleFirst(key)
		val second = triangleSecond(key)
		val third = triangleThird(key)
		val firstValues = valuesOfVertex(first) * valueCount
		val secondValues = valuesOfVertex(second) * valueCount
		val thirdValues = valuesOfVertex(third) * valueCount

		return withBarycentricWeights(store.pointX(first), store.pointY(first), store.pointX(second), store.pointY(second), store.pointX(third), store.pointY(third), x, y) { firstWeight, secondWeight, thirdWeight ->
			var largest = 0.0

			for (field in 0 until guide.fieldCount) {
				val component = 2 * field
				val interpolatedX = blendCorners(firstWeight, secondWeight, thirdWeight, vertexValues[firstValues + component], vertexValues[secondValues + component], vertexValues[thirdValues + component])
				val interpolatedY = blendCorners(firstWeight, secondWeight, thirdWeight, vertexValues[firstValues + component + 1], vertexValues[secondValues + component + 1], vertexValues[thirdValues + component + 1])
				val deltaX = pointValues[component] - interpolatedX
				val deltaY = pointValues[component + 1] - interpolatedY
				largest = maxOf(largest, sqrt(deltaX * deltaX + deltaY * deltaY) / guide.fieldScales[field])
			}

			largest
		}
	}

	/**
	 * Traces one guide edge through the triangulation, from its low end toward its high end, and records
	 * its largest error at a crossing in the domain and the triangles it entered.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 */
	private fun traceEdge(edge: Int) {
		traceError = OUT_OF_PLAY
		tracedCount = 0
		walkAlong(pairFirst(guideEdges[edge]), pairSecond(guideEdges[edge]))

		edgeError[edge] = traceError
		edgeTriangles[edge] = if (tracedCount == 0) null else traced.copyOf(tracedCount)
		edgeWorstX[edge] = traceWorstX
		edgeWorstY[edge] = traceWorstY
		edgeWorstCrossed[edge] = traceWorstCrossed
	}

	/**
	 * Walks a guide edge's straight line from one end toward the other through the domain's triangles,
	 * folding the error at every triangulation edge it crosses into [traceError].
	 *
	 * @param Int from The guide vertex the walk starts at.
	 * @param Int to   The guide vertex it heads for.
	 * @return Boolean True when the walk reached the far end; false when it left the domain first.
	 */
	private fun walkAlong(from: Int, to: Int): Boolean {
		setLine(guide.points[2 * from].toDouble(), guide.points[2 * from + 1].toDouble(), guide.points[2 * to].toDouble(), guide.points[2 * to + 1].toDouble())
		guide.valuesAtVertex(from, pointValues)
		guide.valuesAtVertex(to, endValues)
		var triangle = locate(lineStartX, lineStartY)

		if (triangle < 0) {
			return enterHull()
		}

		var vertex = SteinerInsertion.NO_VERTEX
		var entered = HalfEdgeTriangulation.NO_HALF_EDGE
		val startEdge = edgeThrough(triangle, lineStartX, lineStartY)

		if (startEdge == ON_CORNER) {
			vertex = cornerAt(triangle, lineStartX, lineStartY)
		} else if (startEdge != HalfEdgeTriangulation.NO_HALF_EDGE) {
			val originSide = sideOf(store.origin[startEdge])
			val destinationSide = sideOf(store.destination(startEdge))

			if (originSide == 0.0 && destinationSide == 0.0) {
				// The line runs along the edge the start lies on: it carries on from the end ahead.
				val ahead = if (isAhead(lineStartX, lineStartY, store.origin[startEdge])) store.origin[startEdge] else store.destination(startEdge)
				record(startEdge / 3)

				if (endLiesBefore(ahead)) {
					return true
				}

				vertex = ahead
			} else if (originSide < 0.0 && destinationSide > 0.0) {
				// The line leaves this triangle where it starts, so it starts in the one across.
				entered = store.twin[startEdge]
				triangle = entered / 3
			} else {
				entered = startEdge
			}
		}

		return walkLine(vertex, triangle, entered, findsObstacle = false)
	}

	/**
	 * Walks the current line from a start past the convex hull: from where it first enters the hull,
	 * through a hull edge or a hull vertex on it, when it does before its end.
	 *
	 * @return Boolean True when the walk reached the line's end.
	 */
	private fun enterHull(): Boolean {
		var entry = HalfEdgeTriangulation.NO_HALF_EDGE
		var entryAlong = Double.MAX_VALUE
		var corner = SteinerInsertion.NO_VERTEX
		var cornerAlong = Double.MAX_VALUE
		val directionX = lineEndX - lineStartX
		val directionY = lineEndY - lineStartY

		for (triangle in 0 until store.triangleCount) {
			if (!store.isGhostTriangle(triangle)) {
				continue
			}

			// The hull edge as its real triangle holds it: the line enters through it with its origin left.
			val hullEdge = store.twin[store.realEdgeOfGhost(triangle)]
			val originSide = sideOf(store.origin[hullEdge])

			if (originSide > 0.0 && sideOf(store.destination(hullEdge)) < 0.0) {
				val along = alongLineAt(hullEdge)

				if (along in 0.0..1.0 && along < entryAlong) {
					entry = hullEdge
					entryAlong = along
				}
			} else if (originSide == 0.0) {
				val vertex = store.origin[hullEdge]
				val along = ((store.pointX(vertex) - lineStartX) * directionX + (store.pointY(vertex) - lineStartY) * directionY) / (directionX * directionX + directionY * directionY)

				if (along in 0.0..1.0 && along < cornerAlong) {
					corner = vertex
					cornerAlong = along
				}
			}
		}

		if (corner != SteinerInsertion.NO_VERTEX && cornerAlong <= entryAlong) {
			return walkLine(corner, -1, HalfEdgeTriangulation.NO_HALF_EDGE, findsObstacle = false)
		}

		if (entry == HalfEdgeTriangulation.NO_HALF_EDGE) {
			return false
		}

		if (store.regions[entry / 3]) {
			foldCrossing(entry)
		}

		return walkLine(SteinerInsertion.NO_VERTEX, entry / 3, entry, findsObstacle = false)
	}

	/**
	 * Where along the current line, from 0 at its start to 1 at its end, it crosses a half-edge's line.
	 *
	 * @param Int halfEdge The half-edge.
	 * @return Double The parameter, or infinity when the two are parallel.
	 */
	private fun alongLineAt(halfEdge: Int): Double = if (crossLine(lineStartX, lineStartY, lineEndX, lineEndY, halfEdge)) crossingAlong else Double.POSITIVE_INFINITY

	/**
	 * Walks from a store vertex toward a point through the domain's triangles until something stands in
	 * the way: a constrained edge the line crosses ([metHalfEdge]), or a vertex on the line
	 * ([metVertex]); otherwise to the point.
	 *
	 * @param Int    from    The store vertex.
	 * @param Double targetX The point's x.
	 * @param Double targetY The point's y.
	 * @return Boolean True when the point was reached; false when something stood in the way, or the line leaves the domain at the vertex itself (nothing met).
	 */
	private fun walkToward(from: Int, targetX: Double, targetY: Double): Boolean {
		setLine(store.pointX(from), store.pointY(from), targetX, targetY)
		metHalfEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		metVertex = SteinerInsertion.NO_VERTEX

		return walkLine(from, -1, HalfEdgeTriangulation.NO_HALF_EDGE, findsObstacle = true)
	}

	/**
	 * Walks the current line from where it stands - a store vertex on it, or a triangle it lies in, entered
	 * through a half-edge or from inside - through the domain's triangles.  A trace folds the error at
	 * every edge it crosses; an obstacle walk stops at the first constrained edge or vertex on the line it
	 * meets.
	 *
	 * @param Int     startVertex   The vertex it stands on, or [SteinerInsertion.NO_VERTEX].
	 * @param Int     startTriangle The triangle it stands in, when not on a vertex.
	 * @param Int     startEntered  The half-edge it entered that triangle through, or none.
	 * @param Boolean findsObstacle Whether this is an obstacle walk rather than a trace.
	 * @return Boolean True when the walk reached the line's end; false when it left the domain or met something first.
	 */
	private fun walkLine(startVertex: Int, startTriangle: Int, startEntered: Int, findsObstacle: Boolean): Boolean {
		var vertex = startVertex
		var triangle = startTriangle
		var entered = startEntered

		for (step in 0 until store.lineWalkStepLimit()) {
			if (vertex != SteinerInsertion.NO_VERTEX) {
				if (findsObstacle && vertex != startVertex) {
					metVertex = vertex

					return false
				}

				if (store.pointX(vertex) == lineEndX && store.pointY(vertex) == lineEndY) {
					return true
				}

				// The vertex lies on the line before its end, so the line from it toward the end is the same
				// line, the same way round.
				val exit = store.exitEdgeToward(vertex, lineEndX, lineEndY)

				if (exit == HalfEdgeTriangulation.NO_EXIT) {
					return false
				}

				if (exit < 0) {
					// Along a spoke: the error there is the guide's own at both ends.
					val neighbor = -(exit + 1)
					recordSpoke(vertex, neighbor)

					if (endLiesBefore(neighbor)) {
						return true
					}

					vertex = neighbor

					continue
				}

				triangle = exit / 3
				entered = HalfEdgeTriangulation.NO_HALF_EDGE
				vertex = SteinerInsertion.NO_VERTEX
			}

			if (store.isGhostTriangle(triangle)) {
				return false
			}

			val inside = store.regions[triangle]

			if (findsObstacle && !inside) {
				return false
			}

			if (!findsObstacle) {
				record(triangle)

				if (inside) {
					traceError = maxOf(traceError, 0.0)
				}
			}

			if (containsPoint(triangle, lineEndX, lineEndY)) {
				return true
			}

			val exit = exitOf(triangle, entered)

			if (exit != HalfEdgeTriangulation.NO_HALF_EDGE) {
				if (findsObstacle && store.constrained[exit]) {
					metHalfEdge = exit

					return false
				}

				val across = store.twin[exit] / 3

				// Only where the domain is: an edge outside it carries nothing the mesh draws.
				if (!findsObstacle && (inside || (!store.isGhostTriangle(across) && store.regions[across]))) {
					foldCrossing(exit)
				}

				entered = store.twin[exit]
				triangle = entered / 3
			} else if (exitCorner != SteinerInsertion.NO_VERTEX) {
				vertex = exitCorner
			} else {
				return false
			}
		}

		return false
	}

	/**
	 * How the current line leaves a triangle: through the edge whose origin lies right of it and
	 * destination left, or - when it leaves through no edge - through a corner on it ([exitCorner]).
	 *
	 * @param Int triangle The triangle slot.
	 * @param Int entered  The half-edge the line came in through, or none.
	 * @return Int The exit half-edge, or [HalfEdgeTriangulation.NO_HALF_EDGE].
	 */
	private fun exitOf(triangle: Int, entered: Int): Int {
		var exit = HalfEdgeTriangulation.NO_HALF_EDGE
		exitCorner = SteinerInsertion.NO_VERTEX

		for (offset in 0 until 3) {
			val halfEdge = 3 * triangle + offset

			if (halfEdge == entered) {
				continue
			}

			val originSide = sideOf(store.origin[halfEdge])

			if (originSide < 0.0 && sideOf(store.destination(halfEdge)) > 0.0) {
				exit = halfEdge
			} else if (originSide == 0.0) {
				exitCorner = store.origin[halfEdge]
			}
		}

		return exit
	}

	/**
	 * Notes the triangle beside a spoke the current line runs along - the real one, for a spoke on the
	 * hull - so a change that takes it away traces the edge again.
	 *
	 * @param Int vertex   The vertex the spoke leaves.
	 * @param Int neighbor The vertex it runs to.
	 */
	private fun recordSpoke(vertex: Int, neighbor: Int) {
		val spoke = store.findHalfEdge(vertex, neighbor)
		record(if (store.isGhostTriangle(spoke / 3)) store.twin[spoke] / 3 else spoke / 3)
	}

	/**
	 * Folds the error where the current line crosses a triangulation edge into [traceError], noting the
	 * point when it is the worst yet: the guide's value there runs linearly between the line's ends, the
	 * triangulation's between the edge's.
	 *
	 * @param Int halfEdge The crossed half-edge.
	 */
	private fun foldCrossing(halfEdge: Int) {
		if (!crossLine(lineStartX, lineStartY, lineEndX, lineEndY, halfEdge)) {
			return
		}

		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val along = crossingAlong.coerceIn(0.0, 1.0)
		val across = crossingAcross.coerceIn(0.0, 1.0)
		val originValues = valuesOfVertex(origin) * valueCount
		val destinationValues = valuesOfVertex(destination) * valueCount

		for (field in 0 until guide.fieldCount) {
			val component = 2 * field
			val guideX = pointValues[component] + along * (endValues[component] - pointValues[component])
			val guideY = pointValues[component + 1] + along * (endValues[component + 1] - pointValues[component + 1])
			val interpolatedX = vertexValues[originValues + component] + across * (vertexValues[destinationValues + component] - vertexValues[originValues + component])
			val interpolatedY = vertexValues[originValues + component + 1] + across * (vertexValues[destinationValues + component + 1] - vertexValues[originValues + component + 1])
			val deltaX = guideX - interpolatedX
			val deltaY = guideY - interpolatedY
			val error = sqrt(deltaX * deltaX + deltaY * deltaY) / guide.fieldScales[field]

			if (error > traceError) {
				traceError = error
				traceWorstX = edgePointX(halfEdge, across)
				traceWorstY = edgePointY(halfEdge, across)
				traceWorstCrossed = packVertexPair(origin, destination)
			}
		}
	}

	/**
	 * Where a segment's line and a half-edge's line cross, unclamped, into [crossingAlong] and
	 * [crossingAcross].
	 *
	 * @param Double startX   The segment's start x.
	 * @param Double startY   The segment's start y.
	 * @param Double endX     The segment's end x.
	 * @param Double endY     The segment's end y.
	 * @param Int    halfEdge The half-edge.
	 * @return Boolean False when the two are parallel, leaving both untouched.
	 */
	private fun crossLine(startX: Double, startY: Double, endX: Double, endY: Double, halfEdge: Int): Boolean {
		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val directionX = endX - startX
		val directionY = endY - startY
		val edgeX = store.pointX(destination) - store.pointX(origin)
		val edgeY = store.pointY(destination) - store.pointY(origin)
		val toOriginX = store.pointX(origin) - startX
		val toOriginY = store.pointY(origin) - startY
		val denominator = directionX * edgeY - directionY * edgeX

		if (denominator == 0.0) {
			return false
		}

		crossingAlong = (toOriginX * edgeY - toOriginY * edgeX) / denominator
		crossingAcross = (toOriginX * directionY - toOriginY * directionX) / denominator

		return true
	}

	/**
	 * The x of the point a share of the way along a half-edge.
	 *
	 * @param Int    halfEdge The half-edge.
	 * @param Double across   The share, 0 at its origin, 1 at its destination.
	 * @return Double The x.
	 */
	private fun edgePointX(halfEdge: Int, across: Double): Double {
		val originX = store.pointX(store.origin[halfEdge])

		return originX + across * (store.pointX(store.destination(halfEdge)) - originX)
	}

	/**
	 * The y of the point a share of the way along a half-edge.
	 *
	 * @param Int    halfEdge The half-edge.
	 * @param Double across   The share, 0 at its origin, 1 at its destination.
	 * @return Double The y.
	 */
	private fun edgePointY(halfEdge: Int, across: Double): Double {
		val originY = store.pointY(store.origin[halfEdge])

		return originY + across * (store.pointY(store.destination(halfEdge)) - originY)
	}

	/**
	 * Notes a triangle the current trace entered, so a change that takes it away traces the edge again.
	 *
	 * @param Int triangle The triangle slot.
	 */
	private fun record(triangle: Int) {
		if (tracedCount == traced.size) {
			traced = traced.copyOf(2 * traced.size)
		}

		traced[tracedCount++] = store.keyOfTriangle(triangle)
	}

	/**
	 * Which side of the current line a vertex lies on, exactly.
	 *
	 * @param Int vertex The vertex.
	 * @return Double Positive left of the line, negative right, zero on it.
	 */
	private fun sideOf(vertex: Int): Double = store.orientOf(lineStartX, lineStartY, lineEndX, lineEndY, vertex)

	/**
	 * Whether a vertex on the current line lies strictly ahead of a point on it, toward the line's end,
	 * exactly (see [compareAlongLine]).
	 *
	 * @param Double fromX  The point's x.
	 * @param Double fromY  The point's y.
	 * @param Int    vertex The vertex.
	 * @return Boolean True when it lies past the point.
	 */
	private fun isAhead(fromX: Double, fromY: Double, vertex: Int): Boolean =
		compareAlongLine(lineEndX - lineStartX, lineEndY - lineStartY, fromX, fromY, store.pointX(vertex), store.pointY(vertex)) > 0

	/**
	 * Whether the current line's end lies no further along than a vertex on the line, exactly (see
	 * [compareAlongLine]).
	 *
	 * @param Int vertex The vertex.
	 * @return Boolean True when the end has been reached by the vertex.
	 */
	private fun endLiesBefore(vertex: Int): Boolean =
		compareAlongLine(lineEndX - lineStartX, lineEndY - lineStartY, lineEndX, lineEndY, store.pointX(vertex), store.pointY(vertex)) >= 0

	/**
	 * Whether a point lies in a triangle's closure, exactly.
	 *
	 * @param Int    triangle The triangle slot.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Boolean True inside or on its boundary.
	 */
	private fun containsPoint(triangle: Int, x: Double, y: Double): Boolean {
		val base = 3 * triangle

		return (0 until 3).all { offset -> store.orientAt(x, y, store.origin[base + offset], store.destination(base + offset)) >= 0.0 }
	}

	/**
	 * The store vertex standing at a guide vertex's point, looked up on first ask.
	 *
	 * @param Int guideVertex The guide vertex.
	 * @return Int The store vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun presentVertexOf(guideVertex: Int): Int {
		if (storeVertexOfGuide[guideVertex] != UNKNOWN) {
			return storeVertexOfGuide[guideVertex]
		}

		val x = guide.points[2 * guideVertex].toDouble()
		val y = guide.points[2 * guideVertex + 1].toDouble()
		val triangle = locate(x, y)
		val found = if (triangle >= 0) cornerAt(triangle, x, y) else SteinerInsertion.NO_VERTEX
		storeVertexOfGuide[guideVertex] = found

		return found
	}

	/**
	 * Makes sure a store vertex carries the guide's values, interpolating them at its point on first use.
	 *
	 * @param Int vertex The store vertex.
	 * @return Int The vertex, for indexing its values.
	 */
	private fun valuesOfVertex(vertex: Int): Int {
		if (!vertexValuesKnown[vertex]) {
			val offset = vertex * valueCount

			if (!guide.valuesAt(store.pointX(vertex), store.pointY(vertex), vertexValues, offset)) {
				vertexValues.fill(0.0, offset, offset + valueCount)
			}

			vertexValuesKnown[vertex] = true
		}

		return vertex
	}

	/**
	 * Drops a candidate from consideration.
	 *
	 * @param Int candidate The candidate.
	 */
	private fun leaveOut(candidate: Int) {
		if (candidate < vertexCandidateCount) {
			vertexTriangle[candidate] = NO_TRIANGLE
		} else {
			edgeError[candidate - vertexCandidateCount] = OUT_OF_PLAY
			edgeTriangles[candidate - vertexCandidateCount] = null
		}
	}

	/**
	 * The corner of a triangle standing exactly at a point.
	 *
	 * @param Int    triangle The triangle slot.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Int The corner, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun cornerAt(triangle: Int, x: Double, y: Double): Int {
		for (offset in 0 until 3) {
			val corner = store.origin[3 * triangle + offset]

			if (store.pointX(corner) == x && store.pointY(corner) == y) {
				return corner
			}
		}

		return SteinerInsertion.NO_VERTEX
	}

	/**
	 * The edge of a triangle whose line a point in its closure lies on, exactly.
	 *
	 * @param Int    triangle The triangle slot.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Int The half-edge, [HalfEdgeTriangulation.NO_HALF_EDGE] off every edge, or [ON_CORNER] on two (a corner).
	 */
	private fun edgeThrough(triangle: Int, x: Double, y: Double): Int {
		var onEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		var onEdgeCount = 0

		for (offset in 0 until 3) {
			val halfEdge = 3 * triangle + offset

			if (store.orientAt(x, y, store.origin[halfEdge], store.destination(halfEdge)) == 0.0) {
				onEdge = halfEdge
				onEdgeCount++
			}
		}

		return if (onEdgeCount >= 2) ON_CORNER else onEdge
	}

	/**
	 * Sets the line the next walk follows.
	 *
	 * @param Double startX The line's start x.
	 * @param Double startY The line's start y.
	 * @param Double endX   The line's end x.
	 * @param Double endY   The line's end y.
	 */
	private fun setLine(startX: Double, startY: Double, endX: Double, endY: Double) {
		lineStartX = startX
		lineStartY = startY
		lineEndX = endX
		lineEndY = endY
	}

	/**
	 * The real triangle whose closure holds a point, by a visibility walk across every edge from the last
	 * triangle reached, falling back to a scan when the walk runs long.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Int The triangle slot, or -1 when the point lies past the convex hull.
	 */
	private fun locate(x: Double, y: Double): Int {
		var triangle = if (walkStart < store.triangleCount && !store.isGhostTriangle(walkStart)) walkStart else firstRealTriangle()

		walk@ for (step in 0 until store.locateStepLimit()) {
			val base = 3 * triangle

			for (offset in 0 until 3) {
				val halfEdge = base + offset

				if (store.orientAt(x, y, store.origin[halfEdge], store.destination(halfEdge)) < 0.0) {
					val across = store.twin[halfEdge] / 3

					if (store.isGhostTriangle(across)) {
						return -1
					}

					triangle = across

					continue@walk
				}
			}

			walkStart = triangle

			return triangle
		}

		return locateByScan(x, y)
	}

	/**
	 * The first real triangle whose closure holds a point, scanning every slot.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Int The triangle slot, or -1.
	 */
	private fun locateByScan(x: Double, y: Double): Int {
		for (triangle in 0 until store.triangleCount) {
			if (!store.isGhostTriangle(triangle) && containsPoint(triangle, x, y)) {
				return triangle
			}
		}

		return -1
	}

	/**
	 * The lowest real triangle slot.
	 *
	 * @return Int The slot.
	 */
	private fun firstRealTriangle(): Int = (0 until store.triangleCount).first { triangle -> !store.isGhostTriangle(triangle) }
}

/**
 * Every edge of a triangle list once, as packed (low, high) pairs, ascending.
 *
 * @param IntArray triangles Three vertex indices per triangle.
 * @return LongArray The edges.
 */
private fun uniqueEdgesOf(triangles: IntArray): LongArray {
	val edges = HashSet<Long>()

	for (entry in triangles.indices) {
		val start = triangles[entry]
		val end = triangles[if (entry % 3 == 2) entry - 2 else entry + 1]

		if (start != end) {
			edges.add(packUndirectedPair(start, end))
		}
	}

	return edges.toLongArray().also { array -> array.sort() }
}