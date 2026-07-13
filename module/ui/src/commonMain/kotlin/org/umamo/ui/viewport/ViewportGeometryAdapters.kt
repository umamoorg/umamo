package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.MeshElement
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.screenToWorldX
import org.umamo.render.pick.screenToWorldY
import org.umamo.render.pick.worldToScreenX
import org.umamo.render.pick.worldToScreenY

/*
 * Compose-typed adapters over the pure screen-space geometry in :render's ScreenSpacePick.kt: the
 * overlays speak Offset / IntSize / MeshElement, the geometry speaks plain floats and ordinals, and
 * this file is the only place the two meet.  Every function here is a thin projection or an
 * ordinal-to-element mapping - the actual math lives (and is tested) in :render, where the UV editor
 * reuses it through its own camera.
 *
 * Compose 型と :render の純粋な画面空間幾何をつなぐ薄いアダプタ層。数学は :render 側にある。
 */

/** Pointer distance (px) within which a click selects a vertex or an edge. */
internal const val HIT_RADIUS_PX = 10f

/**
 * Projects an evaluator world-space position into screen pixels for the given camera and area size.
 * Y flips between the spaces (screen y grows downward, world y grows upward).
 *
 * @param Float worldX The world-space x.
 * @param Float worldY The world-space y.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Offset The screen pixel position.
 */
internal fun worldToScreen(worldX: Float, worldY: Float, camera: ViewportCamera, size: IntSize): Offset =
	Offset(
		worldToScreenX(worldX, camera, size.width),
		worldToScreenY(worldY, camera, size.height),
	)

/**
 * Maps a screen point back to an evaluator world-space position - the exact inverse of [worldToScreen].
 *
 * @param Float screenX The screen x in pixels.
 * @param Float screenY The screen y in pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Pair<Float, Float> The (x, y) world-space position.
 */
internal fun screenToWorld(screenX: Float, screenY: Float, camera: ViewportCamera, size: IntSize): Pair<Float, Float> =
	screenToWorldX(screenX, camera, size.width) to screenToWorldY(screenY, camera, size.height)

/**
 * Flattens an edge list into the interleaved endpoint array ScreenSpacePick consumes (two vertex
 * indices per edge, in the list's order, so a returned ordinal indexes straight back into [edges]).
 *
 * @param List<MeshElement.Edge> edges The mesh's unique edges.
 * @return IntArray The interleaved (low, high) endpoint pairs.
 */
private fun edgeEndpointsOf(edges: List<MeshElement.Edge>): IntArray {
	val endpoints = IntArray(edges.size * 2)
	for (edgeOrdinal in edges.indices) {
		endpoints[edgeOrdinal * 2] = edges[edgeOrdinal].endpointLow
		endpoints[edgeOrdinal * 2 + 1] = edges[edgeOrdinal].endpointHigh
	}
	return endpoints
}

/**
 * The vertex index under [pointer] within [HIT_RADIUS_PX], or null.  Picks the nearest within the radius.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param Offset pointer The cursor in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Int? The hit vertex index, or null.
 */
internal fun hitTestVertex(positions: FloatArray, pointer: Offset, camera: ViewportCamera, size: IntSize): Int? =
	org.umamo.render.pick.hitTestVertex(positions, pointer.x, pointer.y, HIT_RADIUS_PX, camera, size.width, size.height)

/**
 * The edge under [pointer] within [HIT_RADIUS_PX], or null.  Picks the nearest by point-to-segment
 * distance in screen space.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param List<MeshElement.Edge> edges The mesh's unique edges.
 * @param Offset pointer The cursor in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return MeshElement.Edge? The hit edge, or null.
 */
internal fun hitTestEdge(
	positions: FloatArray,
	edges: List<MeshElement.Edge>,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
): MeshElement.Edge? =
	org.umamo.render.pick
		.hitTestEdge(positions, edgeEndpointsOf(edges), pointer.x, pointer.y, HIT_RADIUS_PX, camera, size.width, size.height)
		?.let { edgeOrdinal -> edges[edgeOrdinal] }

/**
 * The face under [pointer], or null.  When a deformed mesh overlaps itself, the triangle with the
 * nearest centroid wins.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
 * @param Offset pointer The cursor in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return MeshElement.Face? The hit face, or null.
 */
internal fun hitTestFace(
	positions: FloatArray,
	triangleIndices: IntArray,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
): MeshElement.Face? =
	org.umamo.render.pick
		.hitTestFace(positions, triangleIndices, pointer.x, pointer.y, camera, size.width, size.height)
		?.let { triangleOrdinal -> MeshElement.Face(triangleOrdinal) }

/**
 * The set of vertices whose screen projection lies inside the rectangle spanned by [cornerA] and
 * [cornerB] (a rubber-band box select).
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param Offset cornerA One box corner in screen pixels.
 * @param Offset cornerB The opposite box corner.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<Int> The enclosed vertex indices.
 */
internal fun verticesInBox(positions: FloatArray, cornerA: Offset, cornerB: Offset, camera: ViewportCamera, size: IntSize): Set<Int> =
	org.umamo.render.pick.verticesInBox(positions, cornerA.x, cornerA.y, cornerB.x, cornerB.y, camera, size.width, size.height)

/**
 * The set of edges fully enclosed by the box (both endpoints inside) - Blender's edge box-select rule.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param List<MeshElement.Edge> edges The mesh's unique edges.
 * @param Offset cornerA One box corner in screen pixels.
 * @param Offset cornerB The opposite box corner.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<MeshElement> The enclosed edges.
 */
internal fun edgesInBox(
	positions: FloatArray,
	edges: List<MeshElement.Edge>,
	cornerA: Offset,
	cornerB: Offset,
	camera: ViewportCamera,
	size: IntSize,
): Set<MeshElement> =
	org.umamo.render.pick
		.edgesInBox(positions, edgeEndpointsOf(edges), cornerA.x, cornerA.y, cornerB.x, cornerB.y, camera, size.width, size.height)
		.map { edgeOrdinal -> edges[edgeOrdinal] }
		.toSet()

/**
 * The set of faces whose screen centroid lies inside the box - matching the centroid dot drawn as the
 * face-mode click affordance.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
 * @param Offset cornerA One box corner in screen pixels.
 * @param Offset cornerB The opposite box corner.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<MeshElement> The enclosed faces.
 */
internal fun facesInBox(
	positions: FloatArray,
	triangleIndices: IntArray,
	cornerA: Offset,
	cornerB: Offset,
	camera: ViewportCamera,
	size: IntSize,
): Set<MeshElement> =
	org.umamo.render.pick
		.facesInBox(positions, triangleIndices, cornerA.x, cornerA.y, cornerB.x, cornerB.y, camera, size.width, size.height)
		.map { triangleOrdinal -> MeshElement.Face(triangleOrdinal) }
		.toSet<MeshElement>()

/**
 * The set of vertices whose screen projection lies within [radiusPx] of [center] - the Circle-select
 * brush.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param Offset center The brush centre in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<Int> The enclosed vertex indices.
 */
internal fun verticesInCircle(positions: FloatArray, center: Offset, radiusPx: Float, camera: ViewportCamera, size: IntSize): Set<Int> =
	org.umamo.render.pick.verticesInCircle(positions, center.x, center.y, radiusPx, camera, size.width, size.height)

/**
 * The set of edges the brush touches (the disc overlaps the segment in screen space).
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param List<MeshElement.Edge> edges The mesh's unique edges.
 * @param Offset center The brush centre in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<MeshElement> The touched edges.
 */
internal fun edgesInCircle(
	positions: FloatArray,
	edges: List<MeshElement.Edge>,
	center: Offset,
	radiusPx: Float,
	camera: ViewportCamera,
	size: IntSize,
): Set<MeshElement> =
	org.umamo.render.pick
		.edgesInCircle(positions, edgeEndpointsOf(edges), center.x, center.y, radiusPx, camera, size.width, size.height)
		.map { edgeOrdinal -> edges[edgeOrdinal] }
		.toSet()

/**
 * The set of faces whose screen centroid lies within [radiusPx] of [center] - matching the centroid dot
 * the face-mode overlay draws.
 *
 * @param FloatArray positions The interleaved world posed positions.
 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
 * @param Offset center The brush centre in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<MeshElement> The touched faces.
 */
internal fun facesInCircle(
	positions: FloatArray,
	triangleIndices: IntArray,
	center: Offset,
	radiusPx: Float,
	camera: ViewportCamera,
	size: IntSize,
): Set<MeshElement> =
	org.umamo.render.pick
		.facesInCircle(positions, triangleIndices, center.x, center.y, radiusPx, camera, size.width, size.height)
		.map { triangleOrdinal -> MeshElement.Face(triangleOrdinal) }
		.toSet<MeshElement>()
