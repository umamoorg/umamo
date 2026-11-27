package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.ConstraintKind
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import org.umamo.geometry.triangulation.Triangulation
import org.umamo.geometry.triangulation.buildConstrained
import org.umamo.geometry.triangulation.classifyRegions

/**
 * Decides whether refinement may split one boundary edge at a point, so a caller that knows what
 * lies on either side (art the mesh must cover, a margin it must keep) can refuse.
 */
public fun interface BoundarySplitCheck {
	/**
	 * Whether the boundary edge may run through the split point instead of straight.
	 *
	 * @param Double  startX     The edge's start x.
	 * @param Double  startY     The edge's start y.
	 * @param Double  endX       The edge's end x.
	 * @param Double  endY       The edge's end y.
	 * @param Double  splitX     The split point's x (a Float value).
	 * @param Double  splitY     The split point's y.
	 * @param Boolean cutsInside Whether the split point lies on the inside of the edge, so the thin triangle between the old edge and the two new ones leaves the domain.  False for a point exactly on the edge or on its outside.
	 * @return Boolean True to allow the split.
	 */
	public fun accepts(startX: Double, startY: Double, endX: Double, endY: Double, splitX: Double, splitY: Double, cutsInside: Boolean): Boolean
}

/**
 * How [triangulateAndRefine] refines.
 *
 * @property Double              minimumAngleDegrees  The smallest angle every inside triangle should reach, 0 (no refinement) up to below 60 degrees.  Triangles whose small angle is fixed by the input (two constraints meeting at it) or that a floor below stops are left and counted.
 * @property Int                 maximumAddedPoints   The most vertices refinement may add; it stops when it reaches them.
 * @property Double              minimumSeparation    No added vertex comes closer than this to a vertex it joins.
 * @property Double              minimumSegmentLength No constraint is split into a piece shorter than this.
 * @property BoundarySplitCheck? boundarySplitCheck   Asked before any boundary edge is split; null allows every split.
 * @property PiecewiseLinearGuide? guide             A target the triangulation should follow: where it strays, the guide's vertices are inserted and its creases held as constraints (see [PiecewiseLinearGuide]), after angle refinement; null for none.  A crease splits the constraints across it, a boundary only where [boundarySplitCheck] allows; a segment (never a boundary) with no room for the split gives way, so the result may lack it.
 * @property Double              guideTolerance       The largest error from the guide left alone, in the plane's units.
 * @property Double              guidePointShare      The share of [maximumAddedPoints] kept for a guide the triangulation strays from before refinement: angle refinement, which runs first, stops short of it, and the guide takes whatever is left.  A guide nothing strays from keeps no share.
 */
public class RefinementOptions(
	public val minimumAngleDegrees: Double,
	public val maximumAddedPoints: Int,
	public val minimumSeparation: Double,
	public val minimumSegmentLength: Double,
	public val boundarySplitCheck: BoundarySplitCheck? = null,
	public val guide: PiecewiseLinearGuide? = null,
	public val guideTolerance: Double = 0.5,
	public val guidePointShare: Double = 0.75,
) {
	init {
		require(minimumAngleDegrees >= 0.0 && minimumAngleDegrees < 60.0) { "the minimum angle $minimumAngleDegrees is outside 0 until 60 degrees" }
		require(maximumAddedPoints >= 0) { "the added point limit $maximumAddedPoints is negative" }
		require(minimumSeparation > 0.0 && minimumSeparation.isFinite()) { "the minimum separation $minimumSeparation is not a positive distance" }
		require(minimumSegmentLength > 0.0 && minimumSegmentLength.isFinite()) { "the minimum segment length $minimumSegmentLength is not a positive distance" }
		require(guideTolerance > 0.0 && guideTolerance.isFinite()) { "the guide tolerance $guideTolerance is not a positive distance" }
		require(guidePointShare in 0.0..1.0) { "the guide's share $guidePointShare is outside 0 to 1" }
	}
}

/**
 * What one refinement did.
 *
 * @property Int     addedPointCount           Vertices added, interior and on segments.
 * @property Int     interiorPointCount        Vertices added inside the domain to fix a triangle.
 * @property Int     offCenterCount            Of those, the off-centers (the rest are circumcenters).
 * @property Int     segmentSplitCount         Constraint edges split, boundary edges included.
 * @property Int     boundarySplitCount        Of those, boundary edges.
 * @property Int     refusedBoundarySplitCount Boundary edges left unsplit because the boundary split check refused every point offered for them (up to three per edge).
 * @property Int     underTargetTriangleCount  Inside triangles left with an angle below the target.
 * @property Int     unfixableTriangleCount    Of those, the ones refinement gave up on: an angle between two constraints, a triangle across two segments' matching shells, a floor, or one the guide left (refinement does not run after it).  Every one of them when neither stop below fired.
 * @property Boolean stoppedByCap              Whether angle refinement stopped with work left at the most vertices it could add: [RefinementOptions.maximumAddedPoints], or with a guide that strays, what the guide's share leaves of it - the share is claimed before refinement runs and never handed back, so with a guide the whole cap may still have room - or, with no room to add any vertex, left triangles below the target.
 * @property Boolean stoppedByBackstop         Whether it, or the guide, stopped at its operation backstop, which only a defect reaches.
 * @property Int     guidePointCount           Of the added vertices, the guide's.
 * @property Double  guideRemainingError       The largest error from the guide any candidate kept (0 without a guide).
 * @property Boolean guideStoppedByCap         Whether the guide's share ran out with a candidate still past the tolerance.
 */
public data class RefinementStatistics(
	val addedPointCount: Int,
	val interiorPointCount: Int,
	val offCenterCount: Int,
	val segmentSplitCount: Int,
	val boundarySplitCount: Int,
	val refusedBoundarySplitCount: Int,
	val underTargetTriangleCount: Int,
	val unfixableTriangleCount: Int,
	val stoppedByCap: Boolean,
	val stoppedByBackstop: Boolean,
	val guidePointCount: Int,
	val guideRemainingError: Double,
	val guideStoppedByCap: Boolean,
)

/**
 * A refined triangulation.
 *
 * A plain class: it holds arrays.
 *
 * @property DoubleArray          points          The input points, then every added point (each a Float value), x0, y0, x1, y1, ...
 * @property Int                  inputPointCount How many of [points] were the input.
 * @property Triangulation        triangulation   The triangles over [points], as [org.umamo.geometry.triangulation.triangulate] reports them; an added point is its own canonical index, and a split constraint appears as its pieces.
 * @property RefinementStatistics statistics      What refinement did.
 */
public class RefinedTriangulation(
	public val points: DoubleArray,
	public val inputPointCount: Int,
	public val triangulation: Triangulation,
	public val statistics: RefinementStatistics,
)

/**
 * The constrained Delaunay triangulation of a point set, then refined in place until every inside
 * triangle's smallest angle reaches a target - by Delaunay refinement with off-centers (Üngör), which
 * adds fewer vertices than circumcenters for the same bound, and with diametral lenses (Shewchuk) for
 * the segments, so a vertex a little behind a segment does not force it to split.
 *
 * The triangulation is exactly [org.umamo.geometry.triangulation.triangulate]'s, given the same
 * input; refinement then adds vertices after the input ones - angle refinement's first, short of the
 * share of the cap a guide with something to hold claims, then the guide's.  Without boundaries the
 * convex hull's edges bound the domain: they are made boundary constraints first, so they split like
 * any other.  When a boundary edge is rejected or a ring is left open, inside and outside are not well
 * defined and nothing is added.
 *
 * Refinement always terminates: triangles whose small angle the input fixes are left and counted, and
 * the separation and segment-length floors, the added-point cap, and an operation backstop bound the
 * rest.  Every decision is exact or a deterministic floating-point inequality, so the result is a
 * function of the input alone, on every platform.
 *
 * @param DoubleArray       points     The points, x0, y0, x1, y1, ...; every component must be finite.
 * @param List<IntArray>    boundaries Closed rings of point indices, as for triangulate.
 * @param List<IntArray>    segments   Open polylines of point indices, as for triangulate.
 * @param RefinementOptions options    The target and the limits.
 * @return RefinedTriangulation The points, the triangulation over them, and what was done.
 */
public fun triangulateAndRefine(points: DoubleArray, boundaries: List<IntArray> = emptyList(), segments: List<IntArray> = emptyList(), options: RefinementOptions): RefinedTriangulation =
	refineTriangulation(points, boundaries, segments, options, SteinerPointKind.OffCenter, null)

/** Which point refinement inserts to fix a triangle. */
internal enum class SteinerPointKind {
	/** The off-center on the short edge's bisector, or the circumcenter when it is nearer. */
	OffCenter,

	/** Always the circumcenter: plain Ruppert refinement, kept as the comparison arm. */
	Circumcenter,
}

/**
 * [triangulateAndRefine] with its internal choices exposed for tests.
 *
 * @param DoubleArray                         points        The points.
 * @param List<IntArray>                      boundaries    The boundary rings.
 * @param List<IntArray>                      segments      The segment polylines.
 * @param RefinementOptions                   options       The target and the limits.
 * @param SteinerPointKind                    steinerPoints Which interior points to insert.
 * @param ((HalfEdgeTriangulation) -> Unit)? afterEachStep Called after every added vertex (tests validate here).
 * @return RefinedTriangulation The result.
 */
internal fun refineTriangulation(
	points: DoubleArray,
	boundaries: List<IntArray>,
	segments: List<IntArray>,
	options: RefinementOptions,
	steinerPoints: SteinerPointKind,
	afterEachStep: ((HalfEdgeTriangulation) -> Unit)?,
): RefinedTriangulation {
	val inputPointCount = points.size / 2
	require(inputPointCount.toLong() + options.maximumAddedPoints < MAXIMUM_PACKED_VERTICES) { "$inputPointCount points plus ${options.maximumAddedPoints} added exceed $MAXIMUM_PACKED_VERTICES vertices" }
	val built = buildConstrained(points, boundaries, segments, inputPointCount + options.maximumAddedPoints)
	val store = built.store

	if (store == null) {
		val empty = Triangulation(IntArray(0), built.canonicalIndex, IntArray(0), built.rejectedConstraints, true)

		return RefinedTriangulation(points.copyOf(), inputPointCount, empty, unrefinedStatistics(0, false))
	}

	if (boundaries.isEmpty()) {
		markHullAsBoundary(store)
	}

	val classification = classifyRegions(store)
	store.adoptRegions(classification.inside)
	val boundaryRejected = built.rejectedConstraints.any { rejection -> rejection.kind == ConstraintKind.Boundary }
	val refines = classification.consistent && !boundaryRejected && (options.minimumAngleDegrees > 0.0 || options.guide != null)
	val statistics =
		if (refines && options.maximumAddedPoints > 0) {
			QualityRefiner(store, inputPointCount, options, steinerPoints, afterEachStep).run()
		} else {
			val underTargetCount = countUnderTarget(store, options.minimumAngleDegrees)
			unrefinedStatistics(underTargetCount, refines && underTargetCount > 0)
		}
	val canonical = IntArray(store.pointCount) { vertex -> if (vertex < inputPointCount) built.canonicalIndex[vertex] else vertex }
	val triangulation = Triangulation(store.realTriangles(store.regions), canonical, store.constrainedEdgePairs(), built.rejectedConstraints, classification.consistent)

	return RefinedTriangulation(store.copyPoints(), inputPointCount, triangulation, statistics)
}

/**
 * Makes every convex hull edge a boundary constraint, so a domain given without boundary rings is
 * bounded by edges refinement can split.
 *
 * @param HalfEdgeTriangulation store The unclassified store.
 */
private fun markHullAsBoundary(store: HalfEdgeTriangulation) {
	for (triangle in 0 until store.triangleCount) {
		if (store.isGhostTriangle(triangle)) {
			store.markConstraint(store.realEdgeOfGhost(triangle), true)
		}
	}
}

/**
 * Counts the inside triangles below a target angle in a store that was not refined.
 *
 * @param HalfEdgeTriangulation store        The classified store.
 * @param Double                targetDegrees The target; 0 counts nothing.
 * @return Int The count.
 */
private fun countUnderTarget(store: HalfEdgeTriangulation, targetDegrees: Double): Int {
	if (targetDegrees <= 0.0) {
		return 0
	}

	var count = 0
	store.forEachInsideBelow(sineSquaredOfDegrees(targetDegrees), TriangleShape()) { _, _, _ -> count++ }

	return count
}

/**
 * The statistics of a triangulation refinement left alone.
 *
 * @param Int     underTargetCount The inside triangles below the target.
 * @param Boolean stoppedByCap     Whether a zero cap is what left them.
 * @return RefinementStatistics Nothing added.
 */
private fun unrefinedStatistics(underTargetCount: Int, stoppedByCap: Boolean): RefinementStatistics = RefinementStatistics(0, 0, 0, 0, 0, 0, underTargetCount, 0, stoppedByCap, false, 0, 0.0, false)