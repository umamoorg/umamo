package org.umamo.render.pick

/*
 * Region enclosure for object-mode box / circle select.  Object selection is whole-object, so a region
 * selects a drawable by its geometry CENTROID (the mean of its posed world vertices) and a warp by its
 * lattice's center, Blender's object-mode rule: an object is selected when its anchor falls inside the box
 * or circle.  Centroid-inside is stable
 * and predictable - a full-canvas layer whose centroid sits at the canvas center is not grabbed by a region
 * that only grazes its edge, unlike an any-vertex-touching rule.
 *
 * These functions are pure and work in the same interleaved (x, y) world space as
 * org.umamo.render.eval.DeformedGeometry.worldPositions, which is exactly what an overlay caches at
 * gesture start and tests against each frame - so the selection math is unit-testable with no render or
 * Compose context.  Only drawables appear in worldPositions (deformers and parts have no viewport geometry),
 * and hidden / mesh-less drawables are already absent, so the enclosure naturally covers only real drawables.
 */

/**
 * The world-space centroid of each object: the mean of its interleaved (x, y) world points - a drawable's posed
 * vertices, a warp's lattice points ([latticeCenters]).  An object with an empty array is skipped (it has no
 * centroid to test).
 *
 * @param Map<Key, FloatArray> worldPositions Interleaved world points per object.
 * @return Map<Key, FloatArray> A two-element [x, y] centroid per object, in the same order.
 */
fun <Key> centroidsOf(worldPositions: Map<Key, FloatArray>): Map<Key, FloatArray> {
	val centroids = LinkedHashMap<Key, FloatArray>(worldPositions.size)

	for ((key, positions) in worldPositions) {
		var sumX = 0f
		var sumY = 0f
		var vertexCount = 0
		var slotIndex = 0

		while (slotIndex + 1 < positions.size) {
			sumX += positions[slotIndex]
			sumY += positions[slotIndex + 1]
			vertexCount++
			slotIndex += 2
		}

		if (vertexCount > 0) {
			centroids[key] = floatArrayOf(sumX / vertexCount, sumY / vertexCount)
		}
	}

	return centroids
}

/**
 * The objects whose anchor lies inside the axis-aligned world rectangle (a box select), in the anchors'
 * order.  Boundaries are inclusive.  The caller normalises the corners, so [minX] <= [maxX] and [minY] <= [maxY]
 * is assumed.
 *
 * @param Map<Key, FloatArray> anchors The per-object [x, y] anchors (see [centroidsOf]).
 * @param Float minX The rectangle's low x edge.
 * @param Float minY The rectangle's low y edge.
 * @param Float maxX The rectangle's high x edge.
 * @param Float maxY The rectangle's high y edge.
 * @return Set<Key> The enclosed objects.
 */
fun <Key> anchorsInBox(
	anchors: Map<Key, FloatArray>,
	minX: Float,
	minY: Float,
	maxX: Float,
	maxY: Float,
): Set<Key> {
	val enclosed = LinkedHashSet<Key>()

	for ((key, anchor) in anchors) {
		if (anchor[0] in minX..maxX && anchor[1] in minY..maxY) {
			enclosed.add(key)
		}
	}

	return enclosed
}

/**
 * The objects whose anchor lies within [radius] of ([centerX], [centerY]) in world space (a circle select
 * brush stamp), in the anchors' order.  A point exactly on the circle counts as inside.
 *
 * @param Map<Key, FloatArray> anchors The per-object [x, y] anchors (see [centroidsOf]).
 * @param Float centerX The brush center x in world space.
 * @param Float centerY The brush center y in world space.
 * @param Float radius The brush radius in world units.
 * @return Set<Key> The enclosed objects.
 */
fun <Key> anchorsInCircle(
	anchors: Map<Key, FloatArray>,
	centerX: Float,
	centerY: Float,
	radius: Float,
): Set<Key> {
	val radiusSquared = radius * radius
	val enclosed = LinkedHashSet<Key>()

	for ((key, anchor) in anchors) {
		val offsetX = anchor[0] - centerX
		val offsetY = anchor[1] - centerY

		if (offsetX * offsetX + offsetY * offsetY <= radiusSquared) {
			enclosed.add(key)
		}
	}

	return enclosed
}