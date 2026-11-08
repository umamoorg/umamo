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
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the depth rotation's gesture side: a locked Rotate frame resolves to a turn about the locked axis by the pointer's
 * travel (a quarter turn per half the area's shorter side), with the frame's pivot depth and surface; the in-plane
 * accumulator keeps advancing under a lock; and [applyOperator] runs the depth rotation per pivot group and halo.
 */
class DepthRotationGestureTest {
	private val camera = ViewportCamera(0f, 0f, 2f)
	private val size = IntSize(800, 400)

	/**
	 * A pointer frame from [start] to [current] under [lock], at depth 90 over a Cylinder surface.
	 *
	 * @param Offset start The gesture's start.
	 * @param Offset current The current pointer.
	 * @param TransformAxisConstraint? lock The axis lock.
	 * @return TransformGestureFrame The frame.
	 */
	private fun frame(start: Offset, current: Offset, lock: TransformAxisConstraint?): TransformGestureFrame =
		TransformGestureFrame(0f to 0f, start, current, lock, camera, size, pivotDepth = 90f, depthSurface = DepthSurface.Cylinder)

	/** A Z lock turns by the pointer's horizontal travel, a quarter turn per half the shorter side, at the frame's depth and surface. */
	@Test
	fun aZLockTurnsByHorizontalTravel() {
		val parameters = gestureParameters(MeshOperatorKind.Rotate, frame(Offset(400f, 200f), Offset(500f, 260f), TransformAxisConstraint.AxisZ), RotationAngleTracker())

		assertEquals(RotationAxis.Z, parameters.rotationAxis)
		assertEquals((PI / 4.0).toFloat(), parameters.rotationRadians, 1e-5f, "100 px of a 400 px shorter side is an eighth turn")
		assertEquals(90f, parameters.pivotDepth)
		assertEquals(DepthSurface.Cylinder, parameters.depthSurface)
	}

	/** An X lock tilts by the pointer's vertical travel, upward travel tilting the front up. */
	@Test
	fun anXLockTiltsByVerticalTravel() {
		val parameters = gestureParameters(MeshOperatorKind.Rotate, frame(Offset(400f, 200f), Offset(380f, 100f), TransformAxisConstraint.AxisX), RotationAngleTracker())

		assertEquals(RotationAxis.X, parameters.rotationAxis)
		assertEquals(-(PI / 4.0).toFloat(), parameters.rotationRadians, 1e-5f, "moving up tilts the front up")
	}

	/** A locked frame still advances the in-plane accumulator, so releasing the lock turns from where the pointer is. */
	@Test
	fun theInPlaneTurnCarriesTheDepthUnreadAndTheTrackerKeepsAdvancing() {
		val tracker = RotationAngleTracker()
		val start = Offset(500f, 200f)
		gestureParameters(MeshOperatorKind.Rotate, frame(start, start, TransformAxisConstraint.AxisZ), tracker)
		gestureParameters(MeshOperatorKind.Rotate, frame(start, Offset(400f, 100f), TransformAxisConstraint.AxisZ), tracker)

		assertNotEquals(0f, tracker.totalAngle, "a locked frame still advances the in-plane accumulator")

		val inPlane = gestureParameters(MeshOperatorKind.Rotate, frame(start, Offset(400f, 100f), null), tracker)
		assertEquals(RotationAxis.Y, inPlane.rotationAxis)
		assertEquals(-tracker.totalAngle, inPlane.rotationRadians, 1e-6f, "releasing the lock picks the in-plane angle up where the pointer is")
		assertEquals(90f, inPlane.pivotDepth, "the frame's depth rides along for the strip")
	}

	/** A Rotate in depth turns each pivot group and then the proportional halo, as the depth math does on its own. */
	@Test
	fun applyOperatorRotatesEachGroupAndTheHaloInDepth() {
		val points = floatArrayOf(10f, 0f, 30f, 5f, 60f, -20f)
		val group = TransformPivotGroup(setOf(0, 1), 20f, 0f)
		val influences = mapOf(2 to ProportionalInfluence(0.5f, 20f, 0f))
		val parameters = TransformGestureParameters(0f, 0f, 1f, 1f, 0.6f, RotationAxis.Z, 45f, DepthSurface.Cylinder)

		val applied = applyOperator(MeshOperatorKind.Rotate, points, listOf(group), parameters, influences)
		val grouped = MeshTransforms.rotateVerticesInDepth(points, setOf(0, 1), RotationAxis.Z, 0.6f, 45f, DepthSurface.Cylinder, 20f, 0f)
		val expected = MeshTransforms.rotateVerticesInDepthWeighted(grouped, influences, RotationAxis.Z, 0.6f, 45f, DepthSurface.Cylinder)

		assertEquals(expected.toList(), applied.toList())
	}
}