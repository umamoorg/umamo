package org.umamo.edit.transform

import org.umamo.edit.MeshOperatorKind
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.depthAt
import org.umamo.runtime.model.identityAffine
import org.umamo.runtime.model.latticeDepthOf
import org.umamo.runtime.model.rotationAboutAffine
import org.umamo.runtime.model.scaleAboutAffine
import org.umamo.runtime.model.translationAffine

/**
 * Slides one vertex along the edge toward [neighborIndex] by [factor] of the edge's length - the
 * application half of the Vertex Slide, shared by the drag (whose factor is the pointer projected onto the
 * edge) and the operation settings strip's Factor row.
 *
 * @param FloatArray originalPositions The captured interleaved (x, y) positions, in the gesture's space: world in the 2D
 *   viewport (y world Z, up), display texels in the UV editor.
 * @param Int vertexIndex The sliding vertex.
 * @param Int neighborIndex The edge's far endpoint.
 * @param Float factor How far along the edge the vertex lands, clamped to [0, 1].
 * @return FloatArray A new positions array with the vertex slid.
 */
fun slideVertexByFactor(
	originalPositions: FloatArray,
	vertexIndex: Int,
	neighborIndex: Int,
	factor: Float,
): FloatArray {
	val clampedFactor = factor.coerceIn(0f, 1f)

	return originalPositions.copyOf().also { positions ->
		positions[vertexIndex * 2] = originalPositions[vertexIndex * 2] + (originalPositions[neighborIndex * 2] - originalPositions[vertexIndex * 2]) * clampedFactor
		positions[vertexIndex * 2 + 1] =
			originalPositions[vertexIndex * 2 + 1] + (originalPositions[neighborIndex * 2 + 1] - originalPositions[vertexIndex * 2 + 1]) * clampedFactor
	}
}

/**
 * The depth half of [slideVertexByFactor] for a lattice: the sliding point's depth moved toward its neighbor's by the
 * same factor, so a point slid along an edge stays on it in 3D too.
 *
 * @param LatticeDepth depth The lattice's captured depth.
 * @param Int vertexIndex The sliding point.
 * @param Int neighborIndex The edge's far endpoint.
 * @param Float factor How far along the edge the point lands, clamped to [0, 1].
 * @return LatticeDepth The depth with the point slid; flat stays flat.
 */
fun slideDepthByFactor(
	depth: LatticeDepth,
	vertexIndex: Int,
	neighborIndex: Int,
	factor: Float,
): LatticeDepth {
	if (depth !is LatticeDepth.PerPoint) {
		return depth
	}

	val ownDepth = depth.depthAt(vertexIndex)
	val values = depth.values.copyOf()

	if (vertexIndex in values.indices) {
		values[vertexIndex] = ownDepth + (depth.depthAt(neighborIndex) - ownDepth) * factor.coerceIn(0f, 1f)
	}

	return latticeDepthOf(values)
}

/**
 * Applies the active modal operator to [originalPositions] by the resolved [parameters], producing a new array in the
 * same space.  The parameters come from a pointer frame during the drag or from the operation settings strip's rows
 * after it, so an adjustment runs the very same math.
 *
 * Each proportionally influenced vertex takes the same gesture scaled by its falloff weight, anchored on
 * the pivot of the group owning its nearest covered vertex, so with Individual Origins the halo turns with
 * its island.
 *
 * @param MeshOperatorKind operator The active operator.
 * @param FloatArray originalPositions The interleaved (x, y) positions captured at gesture start, in the gesture's space:
 *   world posed positions in the 2D viewport (y world Z, up), display texels in the UV editor.
 * @param List<TransformPivotGroup> groups The pivot groups covering the selected vertices.
 * @param TransformGestureParameters parameters The delta / factors / angle to apply, in the same space - shared by
 *   every mesh in the capture.
 * @param Map<Int, ProportionalInfluence> proportionalInfluences The influenced unselected vertices, each
 *   with the pivot it anchors on, in the same space (empty when proportional editing is off).
 * @return FloatArray The transformed positions, in the gesture's space.
 */
fun applyOperator(
	operator: MeshOperatorKind,
	originalPositions: FloatArray,
	groups: List<TransformPivotGroup>,
	parameters: TransformGestureParameters,
	proportionalInfluences: Map<Int, ProportionalInfluence>,
): FloatArray =
	when (operator) {
		MeshOperatorKind.Grab -> {
			val deltaX = parameters.deltaX
			val deltaY = parameters.deltaY
			val moved =
				groups.fold(originalPositions) { positions, group ->
					MeshTransforms.translateVertices(positions, group.vertexIndices, deltaX, deltaY)
				}
			MeshTransforms.translateVerticesWeighted(moved, proportionalInfluences, deltaX, deltaY)
		}

		MeshOperatorKind.Scale -> {
			// The factor was measured against the gesture anchor; each group then scales about its own
			// pivot, and each influenced vertex about its owning group's pivot.
			val factorX = parameters.factorX
			val factorY = parameters.factorY
			val moved =
				groups.fold(originalPositions) { positions, group ->
					MeshTransforms.scaleVerticesAxis(positions, group.vertexIndices, factorX, factorY, group.pivotX, group.pivotY)
				}
			MeshTransforms.scaleVerticesWeighted(moved, proportionalInfluences, factorX, factorY)
		}

		MeshOperatorKind.Rotate -> {
			// The angle was measured against the gesture anchor; each group then rotates about its own
			// pivot, and each influenced vertex about its owning group's pivot.  A rotate in depth does the same
			// about X or Z, each group's pivot pushed to the one depth; about Y the depth math is the in-plane turn.
			val rotation = parameters.rotationRadians
			val axis = parameters.rotationAxis
			val depth = parameters.pivotDepth
			val surface = parameters.depthSurface
			val moved =
				groups.fold(originalPositions) { positions, group ->
					MeshTransforms.rotateVerticesInDepth(positions, group.vertexIndices, axis, rotation, depth, surface, group.pivotX, group.pivotY)
				}
			MeshTransforms.rotateVerticesInDepthWeighted(moved, proportionalInfluences, axis, rotation, depth, surface)
		}

		// Vertex Slide never reaches here: the drive loop handles it with its own edge projection.
		MeshOperatorKind.VertexSlide -> {
			originalPositions
		}
	}

/**
 * [applyOperator] over points that carry depth, a lattice's: a Rotate about X or Z turns each point at its depth and
 * hands the turned depth back, and every other operator moves the points in the plane and keeps their depths.
 *
 * @param MeshOperatorKind operator The active operator.
 * @param PointsInDepth original The world positions (the second component is world Z, up) and depths captured at
 *   gesture start.
 * @param List<TransformPivotGroup> groups The pivot groups covering the selected vertices.
 * @param TransformGestureParameters parameters The delta / factors / angle to apply, in world space.
 * @param Map<Int, ProportionalInfluence> proportionalInfluences The influenced unselected vertices, each with the pivot
 *   it anchors on (empty when proportional editing is off).
 * @return PointsInDepth The transformed points.
 */
fun applyOperatorInDepth(
	operator: MeshOperatorKind,
	original: PointsInDepth,
	groups: List<TransformPivotGroup>,
	parameters: TransformGestureParameters,
	proportionalInfluences: Map<Int, ProportionalInfluence>,
): PointsInDepth {
	if (operator != MeshOperatorKind.Rotate) {
		return PointsInDepth(applyOperator(operator, original.positions, groups, parameters, proportionalInfluences), original.depths)
	}

	val axis = parameters.rotationAxis
	val rotation = parameters.rotationRadians
	val depth = parameters.pivotDepth
	val surface = parameters.depthSurface
	val moved =
		groups.fold(original) { points, group ->
			MeshTransforms.rotateVerticesInDepth(points, group.vertexIndices, axis, rotation, depth, surface, group.pivotX, group.pivotY)
		}

	return MeshTransforms.rotateVerticesInDepthWeighted(moved, proportionalInfluences, axis, rotation, depth, surface)
}

/**
 * The world transform [applyOperator] applies to a whole object in pivot group [group], as a 2x3 row-major affine
 * `[a, b, c, d, e, f]` (x' = a x + b y + c, y' = d x + e y + f, where x is world X and y world Z).  An object moved whole takes no proportional
 * halo, so the affine is the operator exactly - what a warp moved as an object maps every one of its forms through.
 *
 * @param MeshOperatorKind operator The active operator.
 * @param TransformGestureParameters parameters The delta / factors / angle to apply, in world space.
 * @param TransformPivotGroup group The pivot group the object turns and scales about, its pivot in world space.
 * @return FloatArray The affine; the identity for a Vertex Slide, which never moves a whole object.
 */
fun gestureAffine(operator: MeshOperatorKind, parameters: TransformGestureParameters, group: TransformPivotGroup): FloatArray =
	when (operator) {
		MeshOperatorKind.Grab -> translationAffine(parameters.deltaX, parameters.deltaY)
		MeshOperatorKind.Scale -> scaleAboutAffine(group.pivotX, group.pivotY, parameters.factorX, parameters.factorY)
		MeshOperatorKind.Rotate -> rotationAboutAffine(group.pivotX, group.pivotY, parameters.rotationRadians)
		MeshOperatorKind.VertexSlide -> identityAffine()
	}