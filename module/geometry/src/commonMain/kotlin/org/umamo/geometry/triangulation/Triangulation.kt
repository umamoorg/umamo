package org.umamo.geometry.triangulation

/**
 * A triangulation's result.
 *
 * A plain class: both fields are arrays.
 *
 * @property IntArray triangles      Three input-point indices per triangle, each triple positively oriented (orient2d > 0: counterclockwise with y up, clockwise on screen in a y-down frame).  Only canonical indices appear.
 * @property IntArray canonicalIndex For each input point, the lowest index with exactly the same coordinates - itself unless the point is a duplicate.
 */
public class Triangulation(
	public val triangles: IntArray,
	public val canonicalIndex: IntArray,
) {
	/** The number of triangles. */
	public val triangleCount: Int
		get() = triangles.size / 3
}

/**
 * The Delaunay triangulation of a point set's convex hull.
 *
 * Every distinct point becomes a vertex; no vertex is added.  Exact duplicates (zero and negative
 * zero alike) collapse onto their lowest index, reported through [Triangulation.canonicalIndex].
 * Cocircular points are tie-broken deterministically: the result is a function of the input alone,
 * and a hull vertex collinear with its neighbors stays a vertex, so no triangle has zero area.  Fewer
 * than three distinct points, or all of them collinear, yield no triangles.
 *
 * Every orientation and circle test is exact (Shewchuk's adaptive predicates), so near-degenerate
 * input cannot corrupt the result.
 *
 * @param DoubleArray points The points, x0, y0, x1, y1, ...; every component must be finite.
 * @return Triangulation The triangles and the duplicate aliasing.
 */
public fun triangulate(points: DoubleArray): Triangulation {
	val plan = planInsertion(points)
	val triangulation = buildDelaunay(points, plan)

	return Triangulation(triangulation?.realTriangles() ?: IntArray(0), plan.canonicalIndex)
}