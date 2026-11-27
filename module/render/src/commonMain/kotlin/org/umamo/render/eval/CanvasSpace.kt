package org.umamo.render.eval

/*
 * The one convention between the eval's world space and canvas space: the eval negates Y into world space, so
 * canvas space is its pre-negation Y-down space, the space CMO3 is built on and the one a DrawableMesh's canvas
 * mesh is in.  Both directions are the same reflection; each is named so a call site says which way it goes.
 */

/**
 * [world] positions, interleaved x/y, as canvas positions: Y negated.
 *
 * @param FloatArray world The world positions.
 * @return FloatArray The canvas positions, a new array.
 */
fun worldToCanvas(world: FloatArray): FloatArray = reflectY(world)

/**
 * [canvas] positions, interleaved x/y, as world positions: Y negated.
 *
 * @param FloatArray canvas The canvas positions.
 * @return FloatArray The world positions, a new array.
 */
fun canvasToWorld(canvas: FloatArray): FloatArray = reflectY(canvas)

/**
 * [positions] with every Y component negated.
 *
 * @param FloatArray positions Interleaved x/y positions.
 * @return FloatArray The reflected positions, a new array.
 */
private fun reflectY(positions: FloatArray): FloatArray =
	FloatArray(positions.size) { componentIndex -> if (componentIndex % 2 == 1) -positions[componentIndex] else positions[componentIndex] }