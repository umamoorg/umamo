package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.DepthSurface
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.TransformAxisConstraint
import org.umamo.edit.transform.MeshTransforms
import org.umamo.edit.transform.ProportionalInfluence
import org.umamo.edit.transform.RotationAngleTracker
import org.umamo.edit.transform.RotationAxis
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformPivotGroup
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.identityAffine
import org.umamo.runtime.model.rotationAboutAffine
import org.umamo.runtime.model.scaleAboutAffine
import org.umamo.runtime.model.translationAffine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min

/**
 * One modal gesture's pointer frame: everything the operator math needs to convert the screen-space
 * gesture into world-space deltas, factors, and angles.  Frozen per pointer event and shared by every
 * mesh in the capture (the per-mesh loop varies only the geometry and pivot groups).
 *
 * @property Pair<Float, Float> anchor The world-space anchor factors / angles measure against.
 * @property Offset start The pointer position at gesture start, in screen pixels.
 * @property Offset current The current (wrap-continuous virtual) pointer, in screen pixels.
 * @property TransformAxisConstraint? axisConstraint The axis lock, or null when unconstrained; on a Rotate, the axis
 *   the turn goes about in depth (only a depth rotation ever holds one).
 * @property ViewportCamera camera The area camera (world<->screen affine).
 * @property IntSize size The area size in pixels.
 * @property Float pivotDepth The depth rotation's pivot depth along world Y, in world px (read only by a locked Rotate).
 * @property DepthSurface depthSurface How deep the points sit before a depth rotation (read only by a locked Rotate).
 */
internal class TransformGestureFrame(
	val anchor: Pair<Float, Float>,
	val start: Offset,
	val current: Offset,
	val axisConstraint: TransformAxisConstraint?,
	val camera: ViewportCamera,
	val size: IntSize,
	val pivotDepth: Float = 0f,
	val depthSurface: DepthSurface = DepthSurface.Flat,
)

/**
 * Resolves [frame] into the parameters [operator] applies: a Grab's translation, a Scale's factors,
 * or a Rotate's accumulated angle, the rest at identity.  A Rotate whose frame holds an axis lock turns in
 * depth about that axis instead, by [depthRotationRadians], at the frame's pivot depth and surface.  Call it once
 * per pointer frame, before the per-mesh loop - the Rotate branch advances [rotationTracker] (locked or not,
 * so releasing a lock picks the in-plane angle up where the pointer is), and one advance per frame is what
 * the accumulator expects.
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

		MeshOperatorKind.Rotate -> {
			val inPlaneRadians = gestureRotationRadians(frame, rotationTracker)
			val axis =
				when (frame.axisConstraint) {
					null -> RotationAxis.Y
					TransformAxisConstraint.AxisX -> RotationAxis.X
					TransformAxisConstraint.AxisZ -> RotationAxis.Z
				}
			val radians = if (axis == RotationAxis.Y) inPlaneRadians else depthRotationRadians(frame)

			// The in-plane turn carries the frame's depth and surface unread, so a strip that offers them shows where
			// the next depth rotation would start.
			TransformGestureParameters(0f, 0f, 1f, 1f, radians, axis, frame.pivotDepth, frame.depthSurface)
		}

		// Vertex Slide has its own edge projection (slideFactorAlongEdge); it never applies these.
		MeshOperatorKind.VertexSlide -> {
			TransformGestureParameters.IDENTITY
		}
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
 * sense - the value [MeshTransforms.rotateVertices] takes.  The in-plane angle, whatever the axis lock: a
 * locked Rotate turns by [depthRotationRadians] instead.
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
 * The angle a depth rotation applies about its locked axis: the pointer's travel from the gesture start along
 * the screen direction that turns it - horizontal for Z (rightward turns the front right), vertical for X (downward
 * tilts the front down) - at a quarter turn per half the area's shorter side, so the rate is the same whatever the
 * zoom.  The travel is the wrap-continuous pointer's, so the turn is unbounded.
 *
 * @param TransformGestureFrame frame The gesture's pointer frame, holding an axis lock.
 * @return Float The turn in radians; 0 without a lock.
 */
internal fun depthRotationRadians(frame: TransformGestureFrame): Float {
	val shorterSide = min(frame.size.width, frame.size.height).toFloat()

	if (shorterSide <= 0f) {
		return 0f
	}

	val travel =
		when (frame.axisConstraint) {
			TransformAxisConstraint.AxisZ -> frame.current.x - frame.start.x
			TransformAxisConstraint.AxisX -> frame.current.y - frame.start.y
			null -> 0f
		}

	return (travel * PI / shorterSide).toFloat()
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
 * world-posed array.  The parameters come from a pointer frame during the drag ([gestureParameters]) or
 * from the operation settings strip's rows after it, so an adjustment runs the very same math.
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
internal fun gestureAffine(operator: MeshOperatorKind, parameters: TransformGestureParameters, group: TransformPivotGroup): FloatArray =
	when (operator) {
		MeshOperatorKind.Grab -> translationAffine(parameters.deltaX, parameters.deltaY)
		MeshOperatorKind.Scale -> scaleAboutAffine(group.pivotX, group.pivotY, parameters.factorX, parameters.factorY)
		MeshOperatorKind.Rotate -> rotationAboutAffine(group.pivotX, group.pivotY, parameters.rotationRadians)
		MeshOperatorKind.VertexSlide -> identityAffine()
	}