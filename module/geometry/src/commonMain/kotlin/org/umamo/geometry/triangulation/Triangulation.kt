package org.umamo.geometry.triangulation

/**
 * A triangulation's result.
 *
 * A plain class: it holds arrays.
 *
 * @property IntArray                 triangles                Three input-point indices per triangle, each triple positively oriented (orient2d > 0: counterclockwise with y up, clockwise on screen in a y-down frame).  Only canonical indices appear.  With boundary rings, only the triangles inside them.
 * @property IntArray                 canonicalIndex           For each input point, the lowest index with exactly the same coordinates - itself unless the point is a duplicate.
 * @property IntArray                 constrainedEdges         Every constrained edge as a (low, high) pair of canonical indices, ascending.  A constraint with vertices lying on it appears as its pieces.
 * @property List<RejectedConstraint> rejectedConstraints      The constraint edges that could not be honored, in insertion order (boundaries first).
 * @property Boolean                  classificationConsistent False when a rejected boundary edge left a ring open, so inside and outside are not well defined; [triangles] then follows a deterministic but arbitrary reading.  Always true without boundaries.
 */
public class Triangulation(
	public val triangles: IntArray,
	public val canonicalIndex: IntArray,
	public val constrainedEdges: IntArray,
	public val rejectedConstraints: List<RejectedConstraint>,
	public val classificationConsistent: Boolean,
) {
	/** The number of triangles. */
	public val triangleCount: Int
		get() = triangles.size / 3
}

/**
 * The constrained Delaunay triangulation of a point set, optionally clipped to boundary rings.
 *
 * Every distinct point becomes a vertex; no vertex is ever added.  Exact duplicates (zero and
 * negative zero alike) collapse onto their lowest index, reported through
 * [Triangulation.canonicalIndex], and constraints are read through that aliasing.  Cocircular points
 * are tie-broken deterministically: the result is a function of the input alone, and a hull vertex
 * collinear with its neighbors stays a vertex, so no triangle has zero area.
 *
 * Constraints are polylines of point indices.  BOUNDARY rings are closed (the last entry joins the
 * first) and decide what is inside, by the even-odd rule: a hole is a ring inside a ring, an island a
 * ring inside a hole, and rings may touch at vertices or share edges.  SEGMENT polylines are open and
 * constrain without changing inside or outside.  Boundaries are inserted before segments; an edge
 * crossing one already present is rejected and reported, never forced, so no vertex is invented.  A
 * constraint passing exactly through a vertex is split there.  Zero-length edges are dropped silently.
 * Every edge that is not a constraint is locally Delaunay.
 *
 * Without boundaries the result covers the convex hull; with them, only the inside.  Fewer than three
 * distinct points, or all of them collinear, yield no triangles, and every constraint edge is then
 * reported as [ConstraintRejectionReason.NoTriangulation].
 *
 * Every orientation and circle test is exact (Shewchuk's adaptive predicates), so near-degenerate
 * input cannot corrupt the result.
 *
 * @param DoubleArray    points     The points, x0, y0, x1, y1, ...; every component must be finite.
 * @param List<IntArray> boundaries Closed rings of point indices.
 * @param List<IntArray> segments   Open polylines of point indices.
 * @return Triangulation The triangles, the aliasing, the constraints as inserted, and what was rejected.
 */
public fun triangulate(points: DoubleArray, boundaries: List<IntArray> = emptyList(), segments: List<IntArray> = emptyList()): Triangulation {
	val plan = planInsertion(points)
	val pointCount = points.size / 2
	requireIndicesInRange(boundaries, pointCount, ConstraintKind.Boundary)
	requireIndicesInRange(segments, pointCount, ConstraintKind.Segment)
	val canonical = plan.canonicalIndex
	val rejected = ArrayList<RejectedConstraint>()
	val triangulation = buildDelaunay(points, plan)

	if (triangulation == null) {
		reportUntriangulated(boundaries, ConstraintKind.Boundary, canonical, rejected)
		reportUntriangulated(segments, ConstraintKind.Segment, canonical, rejected)

		return Triangulation(IntArray(0), canonical, IntArray(0), rejected, true)
	}

	val inserter = ConstraintInserter(triangulation)
	insertPolylines(boundaries, ConstraintKind.Boundary, canonical, inserter, rejected)
	insertPolylines(segments, ConstraintKind.Segment, canonical, inserter, rejected)

	if (boundaries.isEmpty()) {
		return Triangulation(triangulation.realTriangles(), canonical, triangulation.constrainedEdgePairs(), rejected, true)
	}

	val classification = classifyRegions(triangulation)

	return Triangulation(triangulation.realTriangles(classification.inside), canonical, triangulation.constrainedEdgePairs(), rejected, classification.consistent)
}

/**
 * Checks that every polyline entry names a point.
 *
 * @param List<IntArray> polylines  The polylines.
 * @param Int            pointCount The number of points.
 * @param ConstraintKind kind       The list they came from, for the message.
 */
private fun requireIndicesInRange(polylines: List<IntArray>, pointCount: Int, kind: ConstraintKind) {
	for ((polylineIndex, polyline) in polylines.withIndex()) {
		for ((entryIndex, point) in polyline.withIndex()) {
			require(point in 0 until pointCount) { "${kind.name.lowercase()} $polylineIndex entry $entryIndex is $point, outside 0 until $pointCount" }
		}
	}
}

/**
 * The number of edges a polyline has: a ring closes back to its first entry, a segment does not.
 *
 * @param IntArray       polyline The polyline.
 * @param ConstraintKind kind     Its kind.
 * @return Int The edge count.
 */
private fun edgeCountOf(polyline: IntArray, kind: ConstraintKind): Int = if (kind == ConstraintKind.Boundary) polyline.size else maxOf(polyline.size - 1, 0)

/**
 * Inserts every non-degenerate edge of every polyline, collecting the rejected ones.
 *
 * @param List<IntArray>                 polylines The polylines.
 * @param ConstraintKind                 kind      Their kind.
 * @param IntArray                       canonical The duplicate aliasing.
 * @param ConstraintInserter             inserter  The inserter over the triangulation.
 * @param MutableList<RejectedConstraint> rejected  Receives the rejections.
 */
private fun insertPolylines(polylines: List<IntArray>, kind: ConstraintKind, canonical: IntArray, inserter: ConstraintInserter, rejected: MutableList<RejectedConstraint>) {
	for ((polylineIndex, polyline) in polylines.withIndex()) {
		for (edgeIndex in 0 until edgeCountOf(polyline, kind)) {
			val start = polyline[edgeIndex]
			val end = polyline[(edgeIndex + 1) % polyline.size]

			if (canonical[start] == canonical[end]) {
				continue
			}

			if (!inserter.insert(canonical[start], canonical[end], kind == ConstraintKind.Boundary)) {
				rejected.add(RejectedConstraint(kind, polylineIndex, edgeIndex, start, end, ConstraintRejectionReason.CrossesConstraint, inserter.crossedStart, inserter.crossedEnd))
			}
		}
	}
}

/**
 * Reports every non-degenerate polyline edge as untriangulable.
 *
 * @param List<IntArray>                 polylines The polylines.
 * @param ConstraintKind                 kind      Their kind.
 * @param IntArray                       canonical The duplicate aliasing.
 * @param MutableList<RejectedConstraint> rejected  Receives the rejections.
 */
private fun reportUntriangulated(polylines: List<IntArray>, kind: ConstraintKind, canonical: IntArray, rejected: MutableList<RejectedConstraint>) {
	for ((polylineIndex, polyline) in polylines.withIndex()) {
		for (edgeIndex in 0 until edgeCountOf(polyline, kind)) {
			val start = polyline[edgeIndex]
			val end = polyline[(edgeIndex + 1) % polyline.size]

			if (canonical[start] != canonical[end]) {
				rejected.add(RejectedConstraint(kind, polylineIndex, edgeIndex, start, end, ConstraintRejectionReason.NoTriangulation, -1, -1))
			}
		}
	}
}