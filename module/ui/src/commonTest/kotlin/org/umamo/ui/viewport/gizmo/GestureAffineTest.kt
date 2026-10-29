package org.umamo.ui.viewport.gizmo

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformPivotGroup
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [gestureAffine] to [applyOperator]: the affine a whole object moves by is exactly what the operator does to
 * every point of it, for a Grab, a Scale, and a Rotate about an off-origin pivot.
 */
class GestureAffineTest {
	@Test
	fun theAffineIsTheOperatorOnEveryPoint() {
		val points = floatArrayOf(0f, 0f, 37f, -4f, -12.5f, 22f, 101f, 63f, 5f, -80f)
		val group = TransformPivotGroup((0 until points.size / 2).toSet(), 14f, -9f)
		val parameters = TransformGestureParameters(6.5f, -11f, 1.75f, 0.4f, 0.83f)

		for (operator in listOf(MeshOperatorKind.Grab, MeshOperatorKind.Scale, MeshOperatorKind.Rotate)) {
			val expected = applyOperator(operator, points, listOf(group), parameters, emptyMap())
			val affine = gestureAffine(operator, parameters, group)

			for (pointIndex in 0 until points.size / 2) {
				val x = points[pointIndex * 2]
				val z = points[pointIndex * 2 + 1]
				val movedX = affine[0] * x + affine[1] * z + affine[2]
				val movedZ = affine[3] * x + affine[4] * z + affine[5]
				assertTrue(abs(expected[pointIndex * 2] - movedX) <= 1e-3f && abs(expected[pointIndex * 2 + 1] - movedZ) <= 1e-3f, "$operator moves point $pointIndex as the operator does")
			}
		}
	}
}