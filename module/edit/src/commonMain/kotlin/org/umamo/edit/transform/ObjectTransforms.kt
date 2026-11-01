package org.umamo.edit.transform

import org.umamo.edit.Pose
import org.umamo.runtime.model.PuppetModel

/**
 * Reports whether the live pose sits at every parameter's default value - the gate for Object-mode
 * transforms, whose write-back through the deformer-chain inverse is only exact at the neutral pose.  A
 * parameter missing from the pose map counts as neutral, since the renderer substitutes its default.
 *
 * @param PuppetModel model The rig whose parameter defaults define neutral.
 * @param Pose pose The live parameter values to test.
 * @return Boolean True when every posed value equals its parameter's default.
 */
fun isPoseNeutral(model: PuppetModel, pose: Pose): Boolean {
	for (parameter in model.parameters) {
		val value = pose[parameter.id] ?: continue

		if (value != parameter.default) {
			return false
		}
	}

	return true
}

/**
 * The axis-aligned bounds of an interleaved position array: its center and its extents.  A drawable has no
 * scalar transform - its placement IS its geometry - so a "transform" for one is derived from these bounds,
 * and an edit to the transform is an edit to the geometry that produced them.
 *
 * Space-agnostic: this measures whatever array it is handed.  Which array that should be is the caller's
 * decision and a consequential one - see the Properties panel's DrawableWorldTransform.kt, which measures
 * WORLD positions because a keyformed drawable's base array is nothing like its on-screen shape.
 *
 * @property Float centerX The bounds' center x.
 * @property Float centerY The bounds' center y.
 * @property Float width The x extent (0 for a degenerate or empty mesh).
 * @property Float height The y extent (0 for a degenerate or empty mesh).
 */
class MeshBounds(val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/**
 * Computes the axis-aligned bounds of an interleaved (x, y) position array.  Shared by the Properties
 * readout and the transform edits below, so what is displayed and what is written are the same measurement
 * - a second definition would let the two drift and make a typed value land somewhere else.  Both must be
 * handed positions in the SAME space for that to hold.
 *
 * @param FloatArray positions Interleaved x, y vertex positions.
 * @return MeshBounds The center and extents; a zero box when there are no vertices.
 */
fun meshBounds(positions: FloatArray): MeshBounds {
	if (positions.size < 2) {
		return MeshBounds(0f, 0f, 0f, 0f)
	}

	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	var slotIndex = 0

	while (slotIndex + 1 < positions.size) {
		val x = positions[slotIndex]
		val y = positions[slotIndex + 1]

		if (x < minX) {
			minX = x
		}

		if (x > maxX) {
			maxX = x
		}

		if (y < minY) {
			minY = y
		}

		if (y > maxY) {
			maxY = y
		}

		slotIndex += 2
	}

	return MeshBounds((minX + maxX) / 2f, (minY + maxY) / 2f, maxX - minX, maxY - minY)
}

/**
 * Every point index of an interleaved (x, y) array - the whole set a transform of a whole mesh, lattice, or island
 * moves.
 *
 * @param FloatArray positions The interleaved positions.
 * @return Set<Int> The indices 0 until the point count.
 */
fun allPointIndices(positions: FloatArray): Set<Int> = (0 until positions.size / 2).toSet()

/**
 * Translates a whole mesh so its bounds center lands on ([newCenterX], [newCenterY]) - the write side of
 * the Properties Position row, which reads [meshBounds]'s center, so the displayed number becomes exactly
 * the value entered.
 *
 * @param FloatArray positions The interleaved positions.
 * @param Float newCenterX The x the bounds center should land on.
 * @param Float newCenterY The y the bounds center should land on.
 * @return FloatArray A new positions array, or the same instance when nothing moves.
 */
fun movedToBoundsCenter(positions: FloatArray, newCenterX: Float, newCenterY: Float): FloatArray {
	if (positions.size < 2) {
		return positions
	}

	val bounds = meshBounds(positions)
	val deltaX = newCenterX - bounds.centerX
	val deltaY = newCenterY - bounds.centerY

	if (deltaX == 0f && deltaY == 0f) {
		return positions
	}

	return MeshTransforms.translateVertices(positions, allPointIndices(positions), deltaX, deltaY)
}

/**
 * Scales a whole mesh about its bounds center so its extents become ([newWidth], [newHeight]) - the write
 * side of the Properties Size row.  The bounds center is the pivot because the Position row reads that same
 * center, so resizing never makes the position readout jump; the vertex centroid the viewport gizmo pivots
 * on sits off it on an asymmetric mesh.
 *
 * @param FloatArray positions The interleaved positions.
 * @param Float newWidth The target x extent (ignored when the current x extent is 0).
 * @param Float newHeight The target y extent (ignored when the current y extent is 0).
 * @return FloatArray A new positions array, or the same instance when nothing changes.
 */
fun resizedAboutBoundsCenter(positions: FloatArray, newWidth: Float, newHeight: Float): FloatArray {
	if (positions.size < 2) {
		return positions
	}

	val bounds = meshBounds(positions)
	val (factorX, factorY) = boundsScaleFactors(bounds, newWidth, newHeight)

	if (factorX == 1f && factorY == 1f) {
		return positions
	}

	return MeshTransforms.scaleVerticesAxis(
		positions,
		allPointIndices(positions),
		factorX,
		factorY,
		bounds.centerX,
		bounds.centerY,
	)
}

/**
 * The per-axis factors that scale [bounds] to ([newWidth], [newHeight]).  An axis whose current extent is zero has
 * no defined factor - nothing on it can be given an extent - so its factor is 1 rather than infinity.  The one rule
 * every bounds resize reads: the Size rows' write, the factors they register on the operation strip, and a warp
 * resized as a whole.
 *
 * @param MeshBounds bounds The current bounds.
 * @param Float newWidth The target x extent.
 * @param Float newHeight The target y extent.
 * @return Pair<Float, Float> The x and y factors.
 */
fun boundsScaleFactors(bounds: MeshBounds, newWidth: Float, newHeight: Float): Pair<Float, Float> =
	(if (bounds.width > 0f) newWidth / bounds.width else 1f) to (if (bounds.height > 0f) newHeight / bounds.height else 1f)