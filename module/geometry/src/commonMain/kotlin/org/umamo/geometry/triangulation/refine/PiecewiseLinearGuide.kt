package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.locate.TriangleLocator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A piecewise-linear target a refined triangulation should follow: a guide mesh over the same plane,
 * carrying one or more 2D fields at its vertices, linear across each of its triangles.  Refinement
 * inserts guide vertices where the triangulation's own linear interpolation of a field strays from the
 * guide by more than a tolerance, so wherever the guide bends the triangulation gains the vertex that
 * holds the bend - at the guide vertex's exact Float point.
 *
 * Each field has a scale: how many of its units one unit of the plane stands for (see [areaScaleOf]),
 * so an error in field units divided by it reads in the plane's units and one tolerance serves fields of
 * any kind.  Inside the guide a field is the guide's own interpolation; outside it, the nearest
 * triangle's linear map carries on.
 *
 * A plain class: it holds arrays.
 *
 * @property FloatArray        points      The guide's vertices in the plane, x0, y0, ...
 * @property IntArray          triangles   Three vertex indices per guide triangle.
 * @property List<FloatArray>  fields      Each field's 2D value per guide vertex, x0, y0, ...
 * @property DoubleArray       fieldScales Each field's units per unit of the plane (positive).
 * @property IntArray          candidates  The guide vertices refinement may insert, ascending.
 */
public class PiecewiseLinearGuide(
	public val points: FloatArray,
	public val triangles: IntArray,
	public val fields: List<FloatArray>,
	public val fieldScales: DoubleArray,
	public val candidates: IntArray,
) {
	init {
		require(points.size % 2 == 0) { "points must hold x, y pairs, but has ${points.size} components" }
		require(triangles.size % 3 == 0) { "triangles must hold index triples, but has ${triangles.size} entries" }
		require(fields.size == fieldScales.size) { "${fields.size} fields but ${fieldScales.size} scales" }

		for ((index, field) in fields.withIndex()) {
			require(field.size == points.size) { "field $index has ${field.size} components for ${points.size}" }
			require(fieldScales[index] > 0.0 && fieldScales[index].isFinite()) { "field $index's scale ${fieldScales[index]} is not a positive number" }
		}

		for (candidate in candidates) {
			require(candidate in 0 until points.size / 2) { "candidate $candidate is not a guide vertex" }
		}
	}

	/** Locates points on the guide, built on first use. */
	private val locator: TriangleLocator by lazy { TriangleLocator(points, triangles) }

	/** The number of fields. */
	public val fieldCount: Int
		get() = fields.size

	/**
	 * This guide with another's fields added, for two drawables that must come out alike: the other
	 * guide must lie on exactly the same mesh.
	 *
	 * @param PiecewiseLinearGuide other The other guide, over the same points, triangles, and candidates.
	 * @return PiecewiseLinearGuide The merged guide.
	 */
	public fun mergedWith(other: PiecewiseLinearGuide): PiecewiseLinearGuide {
		require(points.contentEquals(other.points) && triangles.contentEquals(other.triangles) && candidates.contentEquals(other.candidates)) { "merged guides must share their mesh" }

		return PiecewiseLinearGuide(points, triangles, fields + other.fields, fieldScales + other.fieldScales, candidates)
	}

	/**
	 * Every field's value at a guide vertex, exactly.
	 *
	 * @param Int         vertex The guide vertex.
	 * @param DoubleArray into   Receives two values per field.
	 */
	internal fun valuesAtVertex(vertex: Int, into: DoubleArray) {
		for ((index, field) in fields.withIndex()) {
			into[2 * index] = field[2 * vertex].toDouble()
			into[2 * index + 1] = field[2 * vertex + 1].toDouble()
		}
	}

	/**
	 * Every field's value at a point: the containing guide triangle's interpolation, or past the guide,
	 * the nearest triangle's linear map carried on.  Allocates nothing of its own: a refinement reads
	 * it for every vertex a trace touches.
	 *
	 * @param Double      x      The point's x.
	 * @param Double      y      The point's y.
	 * @param DoubleArray into   Receives two values per field.
	 * @param Int         offset Where in [into] the first value goes.
	 * @return Boolean False when the guide has no triangle with area, so nothing was written.
	 */
	internal fun valuesAt(x: Double, y: Double, into: DoubleArray, offset: Int = 0): Boolean {
		val location = locator.locate(x, y) ?: return false
		val first = triangles[3 * location.triangleIndex]
		val second = triangles[3 * location.triangleIndex + 1]
		val third = triangles[3 * location.triangleIndex + 2]

		if (location.isInside) {
			blendFieldsInto(first, second, third, location.weightA, location.weightB, location.weightC, into, offset)
		} else {
			// The nearest triangle's own barycentric coordinates, unclamped: its linear map extended.
			withBarycentricWeights(
				points[2 * first].toDouble(),
				points[2 * first + 1].toDouble(),
				points[2 * second].toDouble(),
				points[2 * second + 1].toDouble(),
				points[2 * third].toDouble(),
				points[2 * third + 1].toDouble(),
				x,
				y,
			) { firstWeight, secondWeight, thirdWeight ->
				blendFieldsInto(first, second, third, firstWeight, secondWeight, thirdWeight, into, offset)
			}
		}

		return true
	}

	/**
	 * Every field's value blended from one guide triangle's corners.
	 *
	 * @param Int         first        The first corner.
	 * @param Int         second       The second corner.
	 * @param Int         third        The third corner.
	 * @param Double      firstWeight  The first corner's weight.
	 * @param Double      secondWeight The second corner's weight.
	 * @param Double      thirdWeight  The third corner's weight.
	 * @param DoubleArray into         Receives two values per field.
	 * @param Int         offset       Where in [into] the first value goes.
	 */
	private fun blendFieldsInto(first: Int, second: Int, third: Int, firstWeight: Double, secondWeight: Double, thirdWeight: Double, into: DoubleArray, offset: Int) {
		for ((index, field) in fields.withIndex()) {
			into[offset + 2 * index] = blendCorners(firstWeight, secondWeight, thirdWeight, field[2 * first].toDouble(), field[2 * second].toDouble(), field[2 * third].toDouble())
			into[offset + 2 * index + 1] = blendCorners(firstWeight, secondWeight, thirdWeight, field[2 * first + 1].toDouble(), field[2 * second + 1].toDouble(), field[2 * third + 1].toDouble())
		}
	}
}

/**
 * How many units of a field one unit of the plane stands for, over a mesh: the square root of the
 * field's total triangle area over the plane's.  A field that is the plane scaled by s reads s; a
 * rotated or translated copy reads 1.  Areas are taken whole, so a mesh that folds counts its folds.
 *
 * @param FloatArray points    The mesh's vertices in the plane, x0, y0, ...
 * @param IntArray   triangles Three vertex indices per triangle.
 * @param FloatArray field     The field's 2D value per vertex.
 * @return Double The scale; zero when either area is zero.
 */
public fun areaScaleOf(points: FloatArray, triangles: IntArray, field: FloatArray): Double {
	var planeArea = 0.0
	var fieldArea = 0.0

	for (triangle in 0 until triangles.size / 3) {
		val first = triangles[3 * triangle]
		val second = triangles[3 * triangle + 1]
		val third = triangles[3 * triangle + 2]
		planeArea += abs(doubledAreaOf(points, first, second, third))
		fieldArea += abs(doubledAreaOf(field, first, second, third))
	}

	if (planeArea == 0.0 || fieldArea == 0.0) {
		return 0.0
	}

	return sqrt(fieldArea / planeArea)
}

/**
 * Twice a triangle's signed area over a coordinate array.
 *
 * @param FloatArray coordinates The coordinates, x0, y0, ...
 * @param Int        first       The first corner.
 * @param Int        second      The second corner.
 * @param Int        third       The third corner.
 * @return Double The doubled signed area.
 */
private fun doubledAreaOf(coordinates: FloatArray, first: Int, second: Int, third: Int): Double {
	val firstX = coordinates[2 * first].toDouble()
	val firstY = coordinates[2 * first + 1].toDouble()

	return (coordinates[2 * second] - firstX) * (coordinates[2 * third + 1] - firstY) - (coordinates[2 * second + 1] - firstY) * (coordinates[2 * third] - firstX)
}