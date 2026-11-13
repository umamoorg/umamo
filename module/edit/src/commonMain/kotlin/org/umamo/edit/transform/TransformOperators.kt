package org.umamo.edit.transform

import org.umamo.edit.MeshOperatorKind
import org.umamo.runtime.model.identityAffine
import org.umamo.runtime.model.rotationAboutAffine
import org.umamo.runtime.model.scaleAboutAffine
import org.umamo.runtime.model.translationAffine

/**
 * Slides one vertex along the edge toward [neighborIndex] by [factor] of the edge's length - the
 * application half of the Vertex Slide, shared by the drag (whose factor is the pointer projected onto the
 * edge) and the operation settings strip's Factor row.
 *
 * @param FloatArray originalWorld The captured world positions.
 * @param Int vertexIndex The sliding vertex.
 * @param Int neighborIndex The edge's far endpoint.
 * @param Float factor How far along the edge the vertex lands, clamped to [0, 1].
 * @return FloatArray A new positions array with the vertex slid.
 */
fun slideVertexByFactor(
	originalWorld: FloatArray,
	vertexIndex: Int,
	neighborIndex: Int,
	factor: Float,
): FloatArray {
	val clampedFactor = factor.coerceIn(0f, 1f)

	return originalWorld.copyOf().also { positions ->
		positions[vertexIndex * 2] = originalWorld[vertexIndex * 2] + (originalWorld[neighborIndex * 2] - originalWorld[vertexIndex * 2]) * clampedFactor
		positions[vertexIndex * 2 + 1] =
			originalWorld[vertexIndex * 2 + 1] + (originalWorld[neighborIndex * 2 + 1] - originalWorld[vertexIndex * 2 + 1]) * clampedFactor
	}
}

/**
 * Applies the active modal operator to [originalWorld] by the resolved [parameters], producing a new
 * world-posed array.  The parameters come from a pointer frame during the drag or from the operation
 * settings strip's rows after it, so an adjustment runs the very same math.
 *
 * Each proportionally influenced vertex takes the same gesture scaled by its falloff weight, anchored on
 * the pivot of the group owning its nearest covered vertex, so with Individual Origins the halo turns with
 * its island.
 *
 * @param MeshOperatorKind operator The active operator.
 * @param FloatArray originalWorld The world posed positions captured at gesture start.
 * @param List<TransformPivotGroup> groups The pivot groups covering the selected vertices.
 * @param TransformGestureParameters parameters The delta / factors / angle to apply - shared by every
 *   mesh in the capture.
 * @param Map<Int, ProportionalInfluence> proportionalInfluences The influenced unselected vertices, each
 *   with the pivot it anchors on (empty when proportional editing is off).
 * @return FloatArray The transformed world positions.
 */
fun applyOperator(
	operator: MeshOperatorKind,
	originalWorld: FloatArray,
	groups: List<TransformPivotGroup>,
	parameters: TransformGestureParameters,
	proportionalInfluences: Map<Int, ProportionalInfluence>,
): FloatArray =
	when (operator) {
		MeshOperatorKind.Grab -> {
			val deltaX = parameters.deltaX
			val deltaY = parameters.deltaY
			val moved =
				groups.fold(originalWorld) { positions, group ->
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
				groups.fold(originalWorld) { positions, group ->
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
				groups.fold(originalWorld) { positions, group ->
					MeshTransforms.rotateVerticesInDepth(positions, group.vertexIndices, axis, rotation, depth, surface, group.pivotX, group.pivotY)
				}
			MeshTransforms.rotateVerticesInDepthWeighted(moved, proportionalInfluences, axis, rotation, depth, surface)
		}

		// Vertex Slide never reaches here: the drive loop handles it with its own edge projection.
		MeshOperatorKind.VertexSlide -> {
			originalWorld
		}
	}

/**
 * The world transform [applyOperator] applies to a whole object in pivot group [group], as a 2x3 row-major affine
 * `[a, b, c, d, e, f]` (x' = a x + b y + c, y' = d x + e y + f).  An object moved whole takes no proportional
 * halo, so the affine is the operator exactly - what a warp moved as an object maps every one of its forms through.
 *
 * @param MeshOperatorKind operator The active operator.
 * @param TransformGestureParameters parameters The delta / factors / angle to apply.
 * @param TransformPivotGroup group The pivot group the object turns and scales about.
 * @return FloatArray The affine; the identity for a Vertex Slide, which never moves a whole object.
 */
fun gestureAffine(operator: MeshOperatorKind, parameters: TransformGestureParameters, group: TransformPivotGroup): FloatArray =
	when (operator) {
		MeshOperatorKind.Grab -> translationAffine(parameters.deltaX, parameters.deltaY)
		MeshOperatorKind.Scale -> scaleAboutAffine(group.pivotX, group.pivotY, parameters.factorX, parameters.factorY)
		MeshOperatorKind.Rotate -> rotationAboutAffine(group.pivotX, group.pivotY, parameters.rotationRadians)
		MeshOperatorKind.VertexSlide -> identityAffine()
	}