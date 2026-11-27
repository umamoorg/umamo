package org.umamo.ui.document

import org.umamo.geometry.locate.TriangleLocator

/**
 * The position an art point takes inside a mesh: its containing triangle's corner positions blended by
 * its barycentric weights.
 *
 * @param TriangleLocator locator   The mesh's art-pixel locator.
 * @param IntArray        indices   The mesh's triangles.
 * @param FloatArray      positions The mesh's positions to blend (deformed world, or canvas).
 * @param Double          x         The art x.
 * @param Double          y         The art y.
 * @return Pair<Double, Double>? The blended point, or null outside the mesh.
 */
internal fun blendedAt(locator: TriangleLocator, indices: IntArray, positions: FloatArray, x: Double, y: Double): Pair<Double, Double>? {
	val location = locator.locate(x, y) ?: return null

	if (!location.isInside) {
		return null
	}

	val corners = IntArray(3) { corner -> indices[3 * location.triangleIndex + corner] }
	val weights = doubleArrayOf(location.weightA, location.weightB, location.weightC)
	var blendedX = 0.0
	var blendedY = 0.0

	for (corner in 0 until 3) {
		blendedX += weights[corner] * positions[2 * corners[corner]]
		blendedY += weights[corner] * positions[2 * corners[corner] + 1]
	}

	return blendedX to blendedY
}