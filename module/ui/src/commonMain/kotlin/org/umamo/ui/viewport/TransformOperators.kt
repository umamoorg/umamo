package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ProportionalInfluence
import org.umamo.edit.RotationAngleTracker
import org.umamo.edit.TransformAxisConstraint
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformPivotGroup
import org.umamo.edit.TransformPivots
import org.umamo.render.ViewportCamera
import kotlin.math.atan2

/**
 * One modal gesture's pointer frame: everything the operator math needs to convert the screen-space
 * gesture into world-space deltas, factors, and angles.  Frozen per pointer event and shared by every
 * mesh in the capture (the per-mesh loop varies only the geometry and pivot groups).  This is also
 * where Blender-style numeric input (G X 5 Enter) later lands: a typed override slots in here once
 * and feeds the Edit, Object, and UV overlays alike.
 *
 * @property Pair<Float, Float> anchor The world-space anchor factors / angles measure against.
 * @property Offset start The pointer position at gesture start, in screen pixels.
 * @property Offset current The current (wrap-continuous virtual) pointer, in screen pixels.
 * @property TransformAxisConstraint? axisConstraint The axis lock, or null when unconstrained.
 * @property ViewportCamera camera The area camera (world<->screen affine).
 * @property IntSize size The area size in pixels.
 */
internal class TransformGestureFrame(
	val anchor: Pair<Float, Float>,
	val start: Offset,
	val current: Offset,
	val axisConstraint: TransformAxisConstraint?,
	val camera: ViewportCamera,
	val size: IntSize,
)

/**
 * Resolves [frame] into the parameters [operator] applies: a Grab's translation, a Scale's factors,
 * or a Rotate's accumulated angle, the rest at identity.  Call it once per pointer frame, before the
 * per-mesh loop - the Rotate branch advances [rotationTracker], and one advance per frame is what the
 * accumulator expects.
 *
 * @param MeshOperatorKind operator The active operator.
 * @param TransformGestureFrame frame The gesture's pointer frame.
 * @param RotationAngleTracker rotationTracker The gesture's angle accumulator (Rotate only).
 * @return TransformGestureParameters The resolved parameters.
 */
internal fun gestureParameters(
	operator: MeshOperatorKind,
	frame: TransformGestureFrame,
	rotationTracker: RotationAngleTracker,
): TransformGestureParameters =
	when (operator) {
		MeshOperatorKind.Grab -> {
			val (deltaX, deltaY) = gestureTranslation(frame)
			TransformGestureParameters(deltaX, deltaY, 1f, 1f, 0f)
		}

		MeshOperatorKind.Scale -> {
			val (factorX, factorY) = gestureScaleFactors(frame)
			TransformGestureParameters(0f, 0f, factorX, factorY, 0f)
		}

		MeshOperatorKind.Rotate -> TransformGestureParameters(0f, 0f, 1f, 1f, gestureRotationRadians(frame, rotationTracker))

		// Vertex Slide has its own edge projection (slideFactorAlongEdge); it never applies these.
		MeshOperatorKind.VertexSlide -> TransformGestureParameters.IDENTITY
	}

/**
 * The translation a Grab gesture applies, in the positions' units: the pointer's screen travel over
 * the zoom, with the axis lock zeroing the constrained-out component (AxisX keeps horizontal movement,
 * AxisZ keeps vertical - world y, the displayed Z axis per the Y+ forward, Z+ up convention).
 *
 * Shared by [gestureParameters] and the placement gizmo so the two cannot drift on sign or axis.
 *
 * @param TransformGestureFrame frame The gesture's pointer frame.
 * @return Pair<Float, Float> The (x, y) delta.
 */
internal fun gestureTranslation(frame: TransformGestureFrame): Pair<Float, Float> {
	val deltaX = if (frame.axisConstraint == TransformAxisConstraint.AxisZ) 0f else (frame.current.x - frame.start.x) / frame.camera.zoom
	val deltaY = if (frame.axisConstraint == TransformAxisConstraint.AxisX) 0f else -(frame.current.y - frame.start.y) / frame.camera.zoom
	return deltaX to deltaY
}

/**
 * The per-axis factors a Scale gesture applies: the pointer's distance from the anchor over its
 * distance at gesture start (Blender measures against the transform center even with Individual
 * Origins), with the axis lock pinning the constrained-out axis at 1.
 *
 * @param TransformGestureFrame frame The gesture's pointer frame.
 * @return Pair<Float, Float> The (x, y) factors.
 */
internal fun gestureScaleFactors(frame: TransformGestureFrame): Pair<Float, Float> {
	val anchorScreen = worldToScreen(frame.anchor.first, frame.anchor.second, frame.camera, frame.size)
	val startDistance = (frame.start - anchorScreen).getDistance()
	val currentDistance = (frame.current - anchorScreen).getDistance()
	val factor = if (startDistance > 1e-3f) currentDistance / startDistance else 1f
	val factorX = if (frame.axisConstraint == TransformAxisConstraint.AxisZ) 1f else factor
	val factorY = if (frame.axisConstraint == TransformAxisConstraint.AxisX) 1f else factor
	return factorX to factorY
}

/**
 * The angle a Rotate gesture applies about a pivot, in the positions' units: the pointer's angle about
 * the anchor accumulated through [rotationTracker], negated out of screen space into the positions'
 * sense - the value [MeshTransforms.rotateVertices] takes.  Rotate has no axis to lock in 2D.
 *
 * @param TransformGestureFrame frame The gesture's pointer frame.
 * @param RotationAngleTracker rotationTracker The gesture's angle accumulator.
 * @return Float The rotation in radians.
 */
internal fun gestureRotationRadians(frame: TransformGestureFrame, rotationTracker: RotationAngleTracker): Float {
	val anchorScreen = worldToScreen(frame.anchor.first, frame.anchor.second, frame.camera, frame.size)
	val pointerAngle = atan2(frame.current.y - anchorScreen.y, frame.current.x - anchorScreen.x)
	return -rotationTracker.advance(pointerAngle)
}

/**
 * The slide factor a pointer frame means along the edge from [vertexIndex] toward [neighborIndex]: the
 * pointer projects onto the edge's screen direction and the parameter clamps between the endpoints
 * (Blender's Shift+V, without the unclamped and even-slide variants).  A degenerate on-screen edge
 * (both endpoints under the same pixel) yields 0.
 *
 * @param FloatArray originalWorld The captured world positions.
 * @param Int vertexIndex The sliding vertex.
 * @param Int neighborIndex The edge's far endpoint.
 * @param TransformGestureFrame frame The gesture's pointer frame (the virtual pointer projects).
 * @return Float The factor in [0, 1]: 0 leaves the vertex in place, 1 lands it on the neighbor.
 */
internal fun slideFactorAlongEdge(
	originalWorld: FloatArray,
	vertexIndex: Int,
	neighborIndex: Int,
	frame: TransformGestureFrame,
): Float {
	val vertexScreen = worldToScreen(originalWorld[vertexIndex * 2], originalWorld[vertexIndex * 2 + 1], frame.camera, frame.size)
	val neighborScreen = worldToScreen(originalWorld[neighborIndex * 2], originalWorld[neighborIndex * 2 + 1], frame.camera, frame.size)
	val axisX = neighborScreen.x - vertexScreen.x
	val axisY = neighborScreen.y - vertexScreen.y
	val lengthSquared = axisX * axisX + axisY * axisY
	if (lengthSquared <= 1e-3f) {
		return 0f
	}
	return (((frame.current.x - vertexScreen.x) * axisX + (frame.current.y - vertexScreen.y) * axisY) / lengthSquared).coerceIn(0f, 1f)
}

/**
 * Slides one vertex along the edge toward [neighborIndex] by [factor] of the edge's length - the
 * application half of the Vertex Slide, shared by the drag (whose factor [slideFactorAlongEdge]
 * projects from the pointer) and the operation settings strip's Factor row.
 *
 * @param FloatArray originalWorld The captured world positions.
 * @param Int vertexIndex The sliding vertex.
 * @param Int neighborIndex The edge's far endpoint.
 * @param Float factor How far along the edge the vertex lands, clamped to [0, 1].
 * @return FloatArray A new positions array with the vertex slid.
 */
internal fun slideVertexByFactor(
	originalWorld: FloatArray,
	vertexIndex: Int,
	neighborIndex: Int,
	factor: Float,
): FloatArray {
	val t = factor.coerceIn(0f, 1f)
	return originalWorld.copyOf().also { positions ->
		positions[vertexIndex * 2] = originalWorld[vertexIndex * 2] + (originalWorld[neighborIndex * 2] - originalWorld[vertexIndex * 2]) * t
		positions[vertexIndex * 2 + 1] =
			originalWorld[vertexIndex * 2 + 1] + (originalWorld[neighborIndex * 2 + 1] - originalWorld[vertexIndex * 2 + 1]) * t
	}
}

/**
 * Applies the active modal operator to [originalWorld] by the resolved [parameters], producing a new
 * world-posed array: Grab translates by the delta; Scale and Rotate work about each pivot group's
 * pivot by the factors and the angle.  The parameters come from a pointer frame during the drag
 * ([gestureParameters], which owns the screen-to-world conversion and the rotation accumulator) or from
 * the operation settings strip's rows after it, so an adjustment runs the very same math.
 *
 * Proportional editing adds [proportionalInfluences]: after the selected vertices transform at full
 * strength through their pivot groups, each influenced unselected vertex takes the SAME gesture scaled
 * by its falloff weight, anchored on the pivot of the group OWNING its nearest covered vertex - so
 * with Individual Origins the halo turns with its island, while the shared-pivot modes keep anchoring
 * on the gesture anchor (the single group's pivot).  Factors and angles are still MEASURED against
 * the frame anchor (Blender measures against the transform center even with Individual Origins).
 *
 * @param MeshOperatorKind operator The active operator.
 * @param FloatArray originalWorld The world posed positions captured at gesture start.
 * @param List<TransformPivotGroup> groups The pivot groups covering the selected vertices.
 * @param TransformGestureParameters parameters The delta / factors / angle to apply - shared by every
 *   mesh in the capture.
 * @param Map<Int, ProportionalInfluence> proportionalInfluences The influenced unselected vertices
 *   (empty when proportional editing is off).
 * @return FloatArray The transformed world positions.
 */
internal fun applyOperator(
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
			// A translation has no pivot, so the halo needs no per-group partition - one weighted pass.
			MeshTransforms.translateVerticesWeighted(moved, proportionalInfluences.mapValues { entry -> entry.value.weight }, deltaX, deltaY)
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
			val partitions = TransformPivots.partitionInfluencesByGroup(proportionalInfluences, groups)
			groups.foldIndexed(moved) { groupIndex, positions, group ->
				MeshTransforms.scaleVerticesWeighted(positions, partitions[groupIndex], factorX, factorY, group.pivotX, group.pivotY)
			}
		}

		MeshOperatorKind.Rotate -> {
			// The angle was measured against the gesture anchor; each group then rotates about its own
			// pivot, and each influenced vertex about its owning group's pivot.
			val rotation = parameters.rotationRadians
			val moved =
				groups.fold(originalWorld) { positions, group ->
					MeshTransforms.rotateVertices(positions, group.vertexIndices, rotation, group.pivotX, group.pivotY)
				}
			val partitions = TransformPivots.partitionInfluencesByGroup(proportionalInfluences, groups)
			groups.foldIndexed(moved) { groupIndex, positions, group ->
				MeshTransforms.rotateVerticesWeighted(positions, partitions[groupIndex], rotation, group.pivotX, group.pivotY)
			}
		}

		// Vertex Slide never reaches here: the drive loop handles it with its own edge projection.
		MeshOperatorKind.VertexSlide -> originalWorld
	}