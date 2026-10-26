package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import org.umamo.format.art.opaqueBounds
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.triangulation.triangulate
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A quad over a layer's art: the rectangle around every pixel at or above [alphaThreshold], grown by
 * [margin] on every side, in the raster's own pixels with y down - the import's quad, made from an alpha
 * channel so a drawable that samples only part of its tile gets a quad over its own region.
 *
 * Its corners run left-top, right-top, right-bottom, left-bottom over the triangles 0, 1, 2 and 0, 2, 3,
 * the import's quad exactly, so a reload treats an untouched one as the import's own.  The rectangle is
 * not clamped to the raster: a quad may reach past it, as the import's does.
 *
 * Glued points ([pins]) must stay where they are, so the rectangle also grows to hold each of them, the
 * margin past it, and they are triangulated with the corners - each pin comes back as a vertex at its
 * Float-rounded point, listed in [PlanarTriangleMesh.pinVertices] in pin order, the way the art mesher
 * returns its pins.  Two pins at one point share a vertex; a pin on a corner is that corner.
 *
 * @param AlphaChannel alpha          The art.
 * @param Int          alphaThreshold The least alpha byte (1..255) counted as art.
 * @param Int          margin         How far the quad reaches past the art and the pins, in pixels; at least 0.
 * @param DoubleArray  pins           The glued points to keep, x0, y0, ..., in the raster's pixels.
 * @return PlanarTriangleMesh? The quad, or null when no pixel reaches the threshold.
 */
public fun quadMeshOver(alpha: AlphaChannel, alphaThreshold: Int, margin: Int, pins: DoubleArray = DoubleArray(0)): PlanarTriangleMesh? {
	require(margin >= 0) { "margin must not be negative: $margin" }
	require(pins.size % 2 == 0) { "pins must hold x, y pairs, but has ${pins.size} components" }
	require(pins.all { component -> component.isFinite() }) { "every pin must be finite" }
	val bounds = alpha.opaqueBounds(alphaThreshold) ?: return null
	// Pins are kept bit for bit at their Float values, the precision a mesh stores, as the art mesher keeps them.
	val roundedPins = DoubleArray(pins.size) { component -> pins[component].toFloat().toDouble() }
	var left = bounds.left.toDouble()
	var top = bounds.top.toDouble()
	var right = (bounds.left + bounds.width).toDouble()
	var bottom = (bounds.top + bounds.height).toDouble()

	for (pin in 0 until roundedPins.size / 2) {
		left = minOf(left, floor(roundedPins[2 * pin]))
		top = minOf(top, floor(roundedPins[2 * pin + 1]))
		right = maxOf(right, ceil(roundedPins[2 * pin]))
		bottom = maxOf(bottom, ceil(roundedPins[2 * pin + 1]))
	}

	val corners = doubleArrayOf(left - margin, top - margin, right + margin, top - margin, right + margin, bottom + margin, left - margin, bottom + margin)

	return triangulatedQuad(corners, roundedPins)
}

/** The import quad's two triangles over its corners in order. */
private val QUAD_TRIANGLES: IntArray = intArrayOf(0, 1, 2, 0, 2, 3)

/**
 * The quad's corners and its pins triangulated together.  The corners come first, so they stay vertices
 * 0 to 3; a pin at a corner, or two pins at one point, collapse onto one vertex.
 *
 * @param DoubleArray corners The four corners, x0, y0, ...
 * @param DoubleArray pins    The Float-rounded pins.
 * @return PlanarTriangleMesh The mesh.
 */
private fun triangulatedQuad(corners: DoubleArray, pins: DoubleArray): PlanarTriangleMesh {
	val points = corners + pins
	val pointCount = points.size / 2
	val result = triangulate(points)
	val used = BooleanArray(pointCount)

	for (vertex in result.triangles) {
		used[vertex] = true
	}

	val compacted = IntArray(pointCount) { -1 }
	var vertexCount = 0

	for (point in 0 until pointCount) {
		if (used[point]) {
			compacted[point] = vertexCount
			vertexCount++
		}
	}

	val positions = FloatArray(2 * vertexCount)

	for (point in 0 until pointCount) {
		if (used[point]) {
			positions[2 * compacted[point]] = points[2 * point].toFloat()
			positions[2 * compacted[point] + 1] = points[2 * point + 1].toFloat()
		}
	}

	val pinVertices =
		IntArray(pins.size / 2) { pin ->
			val canonical = result.canonicalIndex[4 + pin]
			check(used[canonical]) { "pin $pin is not a vertex of the quad" }
			compacted[canonical]
		}
	// The corners alone - no pins, or every pin on a corner - keep the import's diagonal: a rectangle's corners
	// lie on one circle, so the Delaunay tie-break could pick the other one.
	val triangles = if (vertexCount == 4) QUAD_TRIANGLES.copyOf() else IntArray(result.triangles.size) { entry -> compacted[result.triangles[entry]] }

	return PlanarTriangleMesh(positions, triangles, pinVertices)
}