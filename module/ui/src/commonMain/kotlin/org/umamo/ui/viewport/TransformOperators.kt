package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ProportionalInfluence
import org.umamo.edit.RotationAngleTracker
import org.umamo.edit.TransformAxisConstraint
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
 * Transfers a displayed-shape movement onto the base mesh: `newBase = base + (after - before)`. The rest
 * shape a rigger sees is base + the neutral keyform blend; because the blend cancels out of the
 * subtraction, the moved rest shape re-renders exactly at `after` while only DrawableMesh.positions is
 * written - no keyform cell is touched, and blend-shape deltas (relative to base) follow the edit. For a
 * grid-less drawable `before` equals base, so this degenerates to `newBase = after`.
 *
 * @param FloatArray base The rest positions captured at gesture start.
 * @param FloatArray after The transformed displayed shape.
 * @param FloatArray before The displayed shape captured at gesture start.
 * @return FloatArray The new base positions (a fresh array).
 */
internal fun movementToBase(base: FloatArray, after: FloatArray, before: FloatArray): FloatArray =
	FloatArray(base.size) { coordIndex ->
		if (coordIndex < after.size && coordIndex < before.size) {
			base[coordIndex] + after[coordIndex] - before[coordIndex]
		} else {
			base[coordIndex]
		}
	}

/**
 * Slides one vertex along the edge toward [neighborIndex]: the pointer projects onto the edge's screen
 * direction and the parameter clamps between the endpoints (Blender's Shift+V, without the unclamped
 * and even-slide variants).
 *
 * @param FloatArray originalWorld The captured world positions.
 * @param Int vertexIndex The sliding vertex.
 * @param Int neighborIndex The edge's far endpoint.
 * @param TransformGestureFrame frame The gesture's pointer frame (the virtual pointer projects).
 * @return FloatArray A new positions array with the vertex slid.
 */
internal fun slideVertexAlongEdge(
	originalWorld: FloatArray,
	vertexIndex: Int,
	neighborIndex: Int,
	frame: TransformGestureFrame,
): FloatArray {
	val vertexScreen = worldToScreen(originalWorld[vertexIndex * 2], originalWorld[vertexIndex * 2 + 1], frame.camera, frame.size)
	val neighborScreen = worldToScreen(originalWorld[neighborIndex * 2], originalWorld[neighborIndex * 2 + 1], frame.camera, frame.size)
	val axisX = neighborScreen.x - vertexScreen.x
	val axisY = neighborScreen.y - vertexScreen.y
	val lengthSquared = axisX * axisX + axisY * axisY
	val t =
		if (lengthSquared > 1e-3f) {
			(((frame.current.x - vertexScreen.x) * axisX + (frame.current.y - vertexScreen.y) * axisY) / lengthSquared).coerceIn(0f, 1f)
		} else {
			0f
		}
	return originalWorld.copyOf().also { positions ->
		positions[vertexIndex * 2] = originalWorld[vertexIndex * 2] + (originalWorld[neighborIndex * 2] - originalWorld[vertexIndex * 2]) * t
		positions[vertexIndex * 2 + 1] =
			originalWorld[vertexIndex * 2 + 1] + (originalWorld[neighborIndex * 2 + 1] - originalWorld[vertexIndex * 2 + 1]) * t
	}
}

/**
 * Applies the active modal operator to [originalWorld], producing a new world-posed array. The pointer
 * gesture (from [TransformGestureFrame.start] to [TransformGestureFrame.current], screen pixels) is
 * converted into the world-space delta / factor / angle: Grab translates by the screen delta divided by
 * zoom; Scale and Rotate work about the frame anchor (world space, mapped to its screen point). Screen Y
 * points down while world Y points up, so the grab's Y delta and the rotation's angle delta negate
 * crossing into world space.
 *
 * Rotate's angle ACCUMULATES through [rotationTracker] (per-move increments wrapped into (-pi, pi])
 * instead of subtracting two raw atan2 samples: a far pivot starts the gesture at the branch cut where
 * the raw difference jumps ~2*pi and the arc direction latches; the accumulator reverses through zero
 * and multi-turns freely.
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
 * @param TransformGestureFrame frame The gesture's pointer frame (anchor, start, current, axis lock,
 *   camera, size) - shared by every mesh in the capture.
 * @param Map<Int, ProportionalInfluence> proportionalInfluences The influenced unselected vertices
 *   (empty when proportional editing is off).
 * @param RotationAngleTracker rotationTracker The gesture's angle accumulator (Rotate only; one per
 *   capture - feeding the same pointer position twice, as the per-mesh loop does, adds zero).
 * @return FloatArray The transformed world positions.
 */
internal fun applyOperator(
	operator: MeshOperatorKind,
	originalWorld: FloatArray,
	groups: List<TransformPivotGroup>,
	frame: TransformGestureFrame,
	proportionalInfluences: Map<Int, ProportionalInfluence>,
	rotationTracker: RotationAngleTracker,
): FloatArray =
	when (operator) {
		MeshOperatorKind.Grab -> {
			// An axis lock zeroes the constrained-out component: AxisX keeps horizontal movement, AxisZ
			// keeps vertical (world y - the displayed Z axis per the Y+ forward, Z+ up convention).
			val deltaX = if (frame.axisConstraint == TransformAxisConstraint.AxisZ) 0f else (frame.current.x - frame.start.x) / frame.camera.zoom
			val deltaY = if (frame.axisConstraint == TransformAxisConstraint.AxisX) 0f else -(frame.current.y - frame.start.y) / frame.camera.zoom
			val moved =
				groups.fold(originalWorld) { positions, group ->
					MeshTransforms.translateVertices(positions, group.vertexIndices, deltaX, deltaY)
				}
			// A translation has no pivot, so the halo needs no per-group partition - one weighted pass.
			MeshTransforms.translateVerticesWeighted(moved, proportionalInfluences.mapValues { entry -> entry.value.weight }, deltaX, deltaY)
		}

		MeshOperatorKind.Scale -> {
			// The factor comes from the gesture anchor (Blender measures against the transform center even
			// with Individual Origins); each group then scales about its own pivot, and each influenced
			// vertex about its owning group's pivot.
			val anchorScreen = worldToScreen(frame.anchor.first, frame.anchor.second, frame.camera, frame.size)
			val startDistance = (frame.start - anchorScreen).getDistance()
			val currentDistance = (frame.current - anchorScreen).getDistance()
			val factor = if (startDistance > 1e-3f) currentDistance / startDistance else 1f
			val factorX = if (frame.axisConstraint == TransformAxisConstraint.AxisZ) 1f else factor
			val factorY = if (frame.axisConstraint == TransformAxisConstraint.AxisX) 1f else factor
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
			// The angle comes from the gesture anchor; each group then rotates about its own pivot, and
			// each influenced vertex about its owning group's pivot.  Rotate has no axis to lock in 2D,
			// so the constraint is ignored.
			val anchorScreen = worldToScreen(frame.anchor.first, frame.anchor.second, frame.camera, frame.size)
			val pointerAngle = atan2(frame.current.y - anchorScreen.y, frame.current.x - anchorScreen.x)
			val gestureAngle = rotationTracker.advance(pointerAngle)
			val moved =
				groups.fold(originalWorld) { positions, group ->
					MeshTransforms.rotateVertices(positions, group.vertexIndices, -gestureAngle, group.pivotX, group.pivotY)
				}
			val partitions = TransformPivots.partitionInfluencesByGroup(proportionalInfluences, groups)
			groups.foldIndexed(moved) { groupIndex, positions, group ->
				MeshTransforms.rotateVerticesWeighted(positions, partitions[groupIndex], -gestureAngle, group.pivotX, group.pivotY)
			}
		}

		// Vertex Slide never reaches here: the drive loop handles it with its own edge projection.
		MeshOperatorKind.VertexSlide -> originalWorld
	}
