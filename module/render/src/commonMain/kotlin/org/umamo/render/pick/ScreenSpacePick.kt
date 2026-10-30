package org.umamo.render.pick

import org.umamo.render.ViewportCamera
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Screen-space hit-testing and region enclosure over an interleaved (x, y) positions array, projected
 * through a ViewportCamera - the geometry seam the Edit-mode gizmo overlays use for element picking,
 * box select, and the circle brush, and that a UV editor can reuse verbatim by projecting through its
 * own camera.  Everything here is pure and Compose-free: points are plain floats, sizes are plain ints,
 * and results are ORDINALS - vertex indices, edge ordinals into the caller's edge array, and triangle
 * ordinals - so this module never depends on any UI element type.
 *
 * Edges come in as an interleaved endpoint array (two vertex indices per edge, the caller's edge order);
 * the point-in-triangle test is shared with the world-space picker (pointInTriangle in Picker.kt),
 * which is coordinate-space agnostic.
 *
 * 画面空間のヒットテストと範囲選択。カメラで投影する純粋な幾何で、UI 型に依存しない（結果は序数）。
 * UV エディタも同じ関数を自分のカメラで再利用できる。
 */

/**
 * Maps a world x to its screen pixel x through [camera].
 *
 * @param Float worldX The world-space x.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @return Float The screen x in pixels.
 */
fun worldToScreenX(worldX: Float, camera: ViewportCamera, viewportWidth: Int): Float =
	(worldX - camera.centerX) * camera.zoom + viewportWidth / 2f

/**
 * Maps a world z to its screen pixel y through [camera].  The vertical axis flips: screen y grows
 * downward, world z grows upward.
 *
 * @param Float worldZ The world-space z (up).
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Float The screen y in pixels.
 */
fun worldToScreenY(worldZ: Float, camera: ViewportCamera, viewportHeight: Int): Float =
	(camera.centerY - worldZ) * camera.zoom + viewportHeight / 2f

/**
 * Maps a screen pixel x back to world space - the exact inverse of [worldToScreenX].
 *
 * @param Float screenX The screen x in pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @return Float The world-space x.
 */
fun screenToWorldX(screenX: Float, camera: ViewportCamera, viewportWidth: Int): Float =
	camera.centerX + (screenX - viewportWidth / 2f) / camera.zoom

/**
 * Maps a screen pixel y back to world space - the exact inverse of [worldToScreenY].
 *
 * @param Float screenY The screen y in pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Float The world-space z (up).
 */
fun screenToWorldZ(screenY: Float, camera: ViewportCamera, viewportHeight: Int): Float =
	camera.centerY - (screenY - viewportHeight / 2f) / camera.zoom

/**
 * The distance from a point to the segment start-end: the point is projected onto the segment's line
 * with the projection parameter clamped into [0, 1], so endpoints measure correctly.  Space-agnostic
 * (the gizmos use it in screen pixels).
 *
 * @param Float pointX The point x.
 * @param Float pointY The point y.
 * @param Float startX The segment start x.
 * @param Float startY The segment start y.
 * @param Float endX The segment end x.
 * @param Float endY The segment end y.
 * @return Float The distance.
 */
fun distanceToSegment(pointX: Float, pointY: Float, startX: Float, startY: Float, endX: Float, endY: Float): Float {
	val segmentX = endX - startX
	val segmentY = endY - startY
	val lengthSquared = segmentX * segmentX + segmentY * segmentY

	// A degenerate (zero-length) segment measures as the distance to its single point.
	if (lengthSquared <= 1e-6f) {
		val deltaX = pointX - startX
		val deltaY = pointY - startY

		return sqrt(deltaX * deltaX + deltaY * deltaY)
	}

	val projection = ((pointX - startX) * segmentX + (pointY - startY) * segmentY) / lengthSquared
	val clamped = projection.coerceIn(0f, 1f)
	val nearestX = startX + segmentX * clamped
	val nearestY = startY + segmentY * clamped
	val deltaX = pointX - nearestX
	val deltaY = pointY - nearestY

	return sqrt(deltaX * deltaX + deltaY * deltaY)
}

/**
 * The nearest of a set of screen-space candidates to the pointer.
 *
 * @property Int index The candidate's index: a vertex index, or an edge's ordinal.
 * @property Float distancePx Its distance from the pointer, in screen pixels.
 */
class ScreenHit(val index: Int, val distancePx: Float)

/**
 * The vertex index under the pointer within [hitRadiusPx], or null.  Picks the nearest within the radius
 * ([nearestVertex]).
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param Float pointerX The pointer x in screen pixels.
 * @param Float pointerY The pointer y in screen pixels.
 * @param Float hitRadiusPx The pick radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Int? The hit vertex index, or null.
 */
fun hitTestVertex(
	positions: FloatArray,
	pointerX: Float,
	pointerY: Float,
	hitRadiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Int? = nearestVertex(positions, pointerX, pointerY, hitRadiusPx, camera, viewportWidth, viewportHeight)?.index

/**
 * The vertex nearest the pointer within [hitRadiusPx], with its distance, or null: the one hit-test a vertex and a
 * lattice control point are picked by.
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param Float pointerX The pointer x in screen pixels.
 * @param Float pointerY The pointer y in screen pixels.
 * @param Float hitRadiusPx The pick radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return ScreenHit? The nearest vertex and its distance, or null.
 */
fun nearestVertex(
	positions: FloatArray,
	pointerX: Float,
	pointerY: Float,
	hitRadiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): ScreenHit? {
	var best = -1
	var bestDistance = hitRadiusPx
	val vertexCount = positions.size / 2

	for (vertexIndex in 0 until vertexCount) {
		val screenX = worldToScreenX(positions[vertexIndex * 2], camera, viewportWidth)
		val screenY = worldToScreenY(positions[vertexIndex * 2 + 1], camera, viewportHeight)
		val deltaX = screenX - pointerX
		val deltaY = screenY - pointerY
		val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

		if (distance <= bestDistance) {
			bestDistance = distance
			best = vertexIndex
		}
	}

	return if (best >= 0) ScreenHit(best, bestDistance) else null
}

/**
 * The edge under the pointer within [hitRadiusPx], or null.  Picks the nearest by point-to-segment
 * distance in screen space, so a click between two close edges resolves to the closer one ([nearestEdge]).
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray edgeEndpoints The edges as interleaved endpoint vertex indices (two per edge).
 * @param Float pointerX The pointer x in screen pixels.
 * @param Float pointerY The pointer y in screen pixels.
 * @param Float hitRadiusPx The pick radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Int? The hit edge's ordinal (its pair index within [edgeEndpoints]), or null.
 */
fun hitTestEdge(
	positions: FloatArray,
	edgeEndpoints: IntArray,
	pointerX: Float,
	pointerY: Float,
	hitRadiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Int? = nearestEdge(positions, edgeEndpoints, pointerX, pointerY, hitRadiusPx, camera, viewportWidth, viewportHeight)?.index

/**
 * The edge nearest the pointer within [hitRadiusPx] by point-to-segment distance, with its distance, or null: the one
 * hit-test a mesh edge and a lattice grid line are picked by.
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray edgeEndpoints The edges as interleaved endpoint vertex indices (two per edge).
 * @param Float pointerX The pointer x in screen pixels.
 * @param Float pointerY The pointer y in screen pixels.
 * @param Float hitRadiusPx The pick radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return ScreenHit? The nearest edge's ordinal and its distance, or null.
 */
fun nearestEdge(
	positions: FloatArray,
	edgeEndpoints: IntArray,
	pointerX: Float,
	pointerY: Float,
	hitRadiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): ScreenHit? {
	var best = -1
	var bestDistance = hitRadiusPx
	val vertexCount = positions.size / 2
	val edgeCount = edgeEndpoints.size / 2

	for (edgeOrdinal in 0 until edgeCount) {
		val endpointA = edgeEndpoints[edgeOrdinal * 2]
		val endpointB = edgeEndpoints[edgeOrdinal * 2 + 1]

		if (endpointA >= vertexCount || endpointB >= vertexCount) {
			continue
		}

		val startX = worldToScreenX(positions[endpointA * 2], camera, viewportWidth)
		val startY = worldToScreenY(positions[endpointA * 2 + 1], camera, viewportHeight)
		val endX = worldToScreenX(positions[endpointB * 2], camera, viewportWidth)
		val endY = worldToScreenY(positions[endpointB * 2 + 1], camera, viewportHeight)
		val distance = distanceToSegment(pointerX, pointerY, startX, startY, endX, endY)

		if (distance <= bestDistance) {
			bestDistance = distance
			best = edgeOrdinal
		}
	}

	return if (best >= 0) ScreenHit(best, bestDistance) else null
}

/**
 * The face under the pointer, or null.  A point-in-face test on the projected positions - a face is a ring of
 * corners, tested as the fan of triangles from its first corner, so a triangle is itself and a lattice cell its
 * two halves; when a deformed mesh overlaps itself and several faces contain the pointer, the one with the
 * nearest centroid (the mean of its corners) wins (a deterministic tie-break that favors the face whose click
 * affordance is closest).
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray faceCorners [cornersPerFace] vertex indices per face, around the face.
 * @param Int cornersPerFace The corners per face: 3 for a triangle mesh, 4 for a lattice.
 * @param Float pointerX The pointer x in screen pixels.
 * @param Float pointerY The pointer y in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Int? The hit face's ordinal, or null.
 */
fun hitTestFace(
	positions: FloatArray,
	faceCorners: IntArray,
	cornersPerFace: Int,
	pointerX: Float,
	pointerY: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Int? {
	var best = -1
	var bestCentroidDistance = Float.MAX_VALUE
	val screen = FloatArray(cornersPerFace * 2)

	for (faceOrdinal in 0 until faceCorners.size / cornersPerFace) {
		if (!projectFace(positions, faceCorners, cornersPerFace, faceOrdinal, camera, viewportWidth, viewportHeight, screen)) {
			continue
		}

		if (!pointInFan(screen, cornersPerFace, pointerX, pointerY)) {
			continue
		}

		val deltaX = pointerX - centroidOf(screen, cornersPerFace, 0)
		val deltaY = pointerY - centroidOf(screen, cornersPerFace, 1)
		val centroidDistance = sqrt(deltaX * deltaX + deltaY * deltaY)

		if (centroidDistance < bestCentroidDistance) {
			bestCentroidDistance = centroidDistance
			best = faceOrdinal
		}
	}

	return if (best >= 0) best else null
}

/**
 * Projects one face's corners to screen pixels, interleaved, into [screen].
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray faceCorners The faces' corners.
 * @param Int cornersPerFace The corners per face.
 * @param Int faceOrdinal The face.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @param FloatArray screen Receives the corners, x then y each; [cornersPerFace] pairs long.
 * @return Boolean False when a corner names no vertex, with [screen] left partly written.
 */
private fun projectFace(
	positions: FloatArray,
	faceCorners: IntArray,
	cornersPerFace: Int,
	faceOrdinal: Int,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
	screen: FloatArray,
): Boolean {
	val vertexCount = positions.size / 2

	for (cornerIndex in 0 until cornersPerFace) {
		val corner = faceCorners[faceOrdinal * cornersPerFace + cornerIndex]

		if (corner >= vertexCount) {
			return false
		}

		screen[cornerIndex * 2] = worldToScreenX(positions[corner * 2], camera, viewportWidth)
		screen[cornerIndex * 2 + 1] = worldToScreenY(positions[corner * 2 + 1], camera, viewportHeight)
	}

	return true
}

/**
 * Whether a point lies in a projected face, tested as the fan of triangles from its first corner.
 *
 * @param FloatArray screen The face's corners, x then y each.
 * @param Int cornersPerFace The corners per face.
 * @param Float pointX The point's x.
 * @param Float pointY The point's y.
 * @return Boolean True when a fan triangle contains the point.
 */
private fun pointInFan(screen: FloatArray, cornersPerFace: Int, pointX: Float, pointY: Float): Boolean {
	for (fanIndex in 1 until cornersPerFace - 1) {
		val nextX = screen[fanIndex * 2 + 2]
		val nextY = screen[fanIndex * 2 + 3]

		if (pointInTriangle(pointX, pointY, screen[0], screen[1], screen[fanIndex * 2], screen[fanIndex * 2 + 1], nextX, nextY)) {
			return true
		}
	}

	return false
}

/**
 * The mean of a projected face's corners on one axis.
 *
 * @param FloatArray screen The corners, x then y each.
 * @param Int cornersPerFace The corners per face.
 * @param Int axis 0 for x, 1 for y.
 * @return Float The centroid's coordinate on that axis.
 */
private fun centroidOf(screen: FloatArray, cornersPerFace: Int, axis: Int): Float {
	var sum = 0f

	for (cornerIndex in 0 until cornersPerFace) {
		sum += screen[cornerIndex * 2 + axis]
	}

	return sum / cornersPerFace
}

/**
 * The set of vertices whose screen projection lies inside the rectangle spanned by the two corners
 * (a rubber-band box select).
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param Float cornerAx One box corner's x in screen pixels.
 * @param Float cornerAy One box corner's y in screen pixels.
 * @param Float cornerBx The opposite corner's x.
 * @param Float cornerBy The opposite corner's y.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Set<Int> The enclosed vertex indices.
 */
fun verticesInBox(
	positions: FloatArray,
	cornerAx: Float,
	cornerAy: Float,
	cornerBx: Float,
	cornerBy: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Set<Int> {
	val minX = min(cornerAx, cornerBx)
	val maxX = max(cornerAx, cornerBx)
	val minY = min(cornerAy, cornerBy)
	val maxY = max(cornerAy, cornerBy)
	val result = HashSet<Int>()
	val vertexCount = positions.size / 2

	for (vertexIndex in 0 until vertexCount) {
		val screenX = worldToScreenX(positions[vertexIndex * 2], camera, viewportWidth)
		val screenY = worldToScreenY(positions[vertexIndex * 2 + 1], camera, viewportHeight)

		if (screenX in minX..maxX && screenY in minY..maxY) {
			result.add(vertexIndex)
		}
	}

	return result
}

/**
 * The set of edges fully enclosed by the box (both endpoints inside) - Blender's edge box-select rule.
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray edgeEndpoints The edges as interleaved endpoint vertex indices (two per edge).
 * @param Float cornerAx One box corner's x in screen pixels.
 * @param Float cornerAy One box corner's y in screen pixels.
 * @param Float cornerBx The opposite corner's x.
 * @param Float cornerBy The opposite corner's y.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Set<Int> The enclosed edges' ordinals.
 */
fun edgesInBox(
	positions: FloatArray,
	edgeEndpoints: IntArray,
	cornerAx: Float,
	cornerAy: Float,
	cornerBx: Float,
	cornerBy: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Set<Int> {
	val insideVertices = verticesInBox(positions, cornerAx, cornerAy, cornerBx, cornerBy, camera, viewportWidth, viewportHeight)
	val result = HashSet<Int>()
	val edgeCount = edgeEndpoints.size / 2

	for (edgeOrdinal in 0 until edgeCount) {
		if (edgeEndpoints[edgeOrdinal * 2] in insideVertices && edgeEndpoints[edgeOrdinal * 2 + 1] in insideVertices) {
			result.add(edgeOrdinal)
		}
	}

	return result
}

/**
 * The set of faces whose screen centroid (the mean of their corners) lies inside the box - matching the centroid
 * dot drawn as the face-mode click affordance, so the box selects exactly the visible dots it covers.
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray faceCorners [cornersPerFace] vertex indices per face, around the face.
 * @param Int cornersPerFace The corners per face.
 * @param Float cornerAx One box corner's x in screen pixels.
 * @param Float cornerAy One box corner's y in screen pixels.
 * @param Float cornerBx The opposite corner's x.
 * @param Float cornerBy The opposite corner's y.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Set<Int> The enclosed faces' ordinals.
 */
fun facesInBox(
	positions: FloatArray,
	faceCorners: IntArray,
	cornersPerFace: Int,
	cornerAx: Float,
	cornerAy: Float,
	cornerBx: Float,
	cornerBy: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Set<Int> {
	val minX = min(cornerAx, cornerBx)
	val maxX = max(cornerAx, cornerBx)
	val minY = min(cornerAy, cornerBy)
	val maxY = max(cornerAy, cornerBy)
	val result = HashSet<Int>()
	val screen = FloatArray(cornersPerFace * 2)

	for (faceOrdinal in 0 until faceCorners.size / cornersPerFace) {
		if (!projectFace(positions, faceCorners, cornersPerFace, faceOrdinal, camera, viewportWidth, viewportHeight, screen)) {
			continue
		}

		if (centroidOf(screen, cornersPerFace, 0) in minX..maxX && centroidOf(screen, cornersPerFace, 1) in minY..maxY) {
			result.add(faceOrdinal)
		}
	}

	return result
}

/**
 * The set of vertices whose screen projection lies within [radiusPx] of the brush center - the
 * Circle-select brush.
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param Float centerX The brush center x in screen pixels.
 * @param Float centerY The brush center y in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Set<Int> The enclosed vertex indices.
 */
fun verticesInCircle(
	positions: FloatArray,
	centerX: Float,
	centerY: Float,
	radiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Set<Int> {
	val result = HashSet<Int>()
	val vertexCount = positions.size / 2

	for (vertexIndex in 0 until vertexCount) {
		val deltaX = worldToScreenX(positions[vertexIndex * 2], camera, viewportWidth) - centerX
		val deltaY = worldToScreenY(positions[vertexIndex * 2 + 1], camera, viewportHeight) - centerY

		if (sqrt(deltaX * deltaX + deltaY * deltaY) <= radiusPx) {
			result.add(vertexIndex)
		}
	}

	return result
}

/**
 * The set of edges the brush touches: an edge is included when the brush center is within [radiusPx] of
 * the edge segment in screen space (Blender's circle brush paints any edge the disc overlaps).
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray edgeEndpoints The edges as interleaved endpoint vertex indices (two per edge).
 * @param Float centerX The brush center x in screen pixels.
 * @param Float centerY The brush center y in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Set<Int> The touched edges' ordinals.
 */
fun edgesInCircle(
	positions: FloatArray,
	edgeEndpoints: IntArray,
	centerX: Float,
	centerY: Float,
	radiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Set<Int> {
	val vertexCount = positions.size / 2
	val result = HashSet<Int>()
	val edgeCount = edgeEndpoints.size / 2

	for (edgeOrdinal in 0 until edgeCount) {
		val endpointA = edgeEndpoints[edgeOrdinal * 2]
		val endpointB = edgeEndpoints[edgeOrdinal * 2 + 1]

		if (endpointA >= vertexCount || endpointB >= vertexCount) {
			continue
		}

		val startX = worldToScreenX(positions[endpointA * 2], camera, viewportWidth)
		val startY = worldToScreenY(positions[endpointA * 2 + 1], camera, viewportHeight)
		val endX = worldToScreenX(positions[endpointB * 2], camera, viewportWidth)
		val endY = worldToScreenY(positions[endpointB * 2 + 1], camera, viewportHeight)

		if (distanceToSegment(centerX, centerY, startX, startY, endX, endY) <= radiusPx) {
			result.add(edgeOrdinal)
		}
	}

	return result
}

/**
 * The set of faces whose screen centroid (the mean of their corners) lies within [radiusPx] of the brush center -
 * matching the centroid dot the face-mode overlay draws, so the brush paints the dots it covers (consistent with
 * box select).
 *
 * @param FloatArray positions The interleaved (x, y) world positions.
 * @param IntArray faceCorners [cornersPerFace] vertex indices per face, around the face.
 * @param Int cornersPerFace The corners per face.
 * @param Float centerX The brush center x in screen pixels.
 * @param Float centerY The brush center y in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Set<Int> The touched faces' ordinals.
 */
fun facesInCircle(
	positions: FloatArray,
	faceCorners: IntArray,
	cornersPerFace: Int,
	centerX: Float,
	centerY: Float,
	radiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): Set<Int> {
	val result = HashSet<Int>()
	val screen = FloatArray(cornersPerFace * 2)

	for (faceOrdinal in 0 until faceCorners.size / cornersPerFace) {
		if (!projectFace(positions, faceCorners, cornersPerFace, faceOrdinal, camera, viewportWidth, viewportHeight, screen)) {
			continue
		}

		val deltaX = centroidOf(screen, cornersPerFace, 0) - centerX
		val deltaY = centroidOf(screen, cornersPerFace, 1) - centerY

		if (sqrt(deltaX * deltaX + deltaY * deltaY) <= radiusPx) {
			result.add(faceOrdinal)
		}
	}

	return result
}