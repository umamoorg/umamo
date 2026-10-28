package org.umamo.render.puppet

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm

/** The parameter the outer warp moves along x over, from -1 to 1. */
internal val LATTICE_RIG_ANGLE_X = ParameterId("ParamAngleX")

/** The parameter the ranged warp is keyed over, from 0 to 1, with keys only up to 0.5. */
internal val LATTICE_RIG_ANGLE_Y = ParameterId("ParamAngleY")

/** A root 2x2 warp whose lattice slides 20 units along x across angle X. */
internal val LATTICE_RIG_OUTER = DeformerId("outer")

/** A 1x1 warp inside the outer one, held at the middle half of its parent. */
internal val LATTICE_RIG_INNER = DeformerId("inner")

/** A root 1x1 warp keyed on angle Y from 0 to 0.5 only, so a pose past 0.5 hides it. */
internal val LATTICE_RIG_RANGED = DeformerId("ranged")

/** A root rotation, which has no lattice. */
internal val LATTICE_RIG_ROTATION = DeformerId("turn")

/**
 * The outer warp's 3x3 control points, 30 units apart from the origin, slid [shift] along x, in the cascade's
 * space (y down).
 *
 * @param Float shift The slide.
 * @return FloatArray The control points.
 */
internal fun latticeRigOuterPoints(shift: Float): FloatArray =
	FloatArray(18) { componentIndex ->
		val pointIndex = componentIndex / 2

		if (componentIndex % 2 == 0) {
			(pointIndex % 3) * 30f + shift
		} else {
			(pointIndex / 3) * 30f
		}
	}

/**
 * A rig with three warps and a rotation: [LATTICE_RIG_OUTER] moving with angle X, [LATTICE_RIG_INNER] inside it,
 * [LATTICE_RIG_RANGED] hidden past half of angle Y, and [LATTICE_RIG_ROTATION], in that model order, with the
 * rotation first.
 *
 * @return PuppetModel The rig.
 */
internal fun latticeRigModel(): PuppetModel {
	val angleXKeys = KeyformAxis(LATTICE_RIG_ANGLE_X, floatArrayOf(-1f, 1f))
	val outer =
		Deformer.Warp(
			id = LATTICE_RIG_OUTER,
			name = "outer",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(angleXKeys),
					listOf(
						KeyformCell(intArrayOf(0), WarpLatticeForm(latticeRigOuterPoints(-10f))),
						KeyformCell(intArrayOf(1), WarpLatticeForm(latticeRigOuterPoints(10f))),
					),
				),
		)
	val innerPoints = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)
	val inner =
		Deformer.Warp(
			id = LATTICE_RIG_INNER,
			name = "inner",
			parent = LATTICE_RIG_OUTER,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(angleXKeys),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(innerPoints)), KeyformCell(intArrayOf(1), WarpLatticeForm(innerPoints))),
				),
		)
	val rangedPoints = floatArrayOf(-50f, -50f, -40f, -50f, -50f, -40f, -40f, -40f)
	val ranged =
		Deformer.Warp(
			id = LATTICE_RIG_RANGED,
			name = "ranged",
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(LATTICE_RIG_ANGLE_Y, floatArrayOf(0f, 0.5f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(rangedPoints)), KeyformCell(intArrayOf(1), WarpLatticeForm(rangedPoints))),
				),
		)
	val rotation =
		Deformer.Rotation(
			id = LATTICE_RIG_ROTATION,
			name = "turn",
			parent = null,
			partId = null,
			baseAngle = 0f,
			geometryGrid =
				KeyformGrid(
					listOf(angleXKeys),
					listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0f, 0f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0f, 0f, 0f, 1f))),
				),
		)

	return PuppetModel(
		parameters = listOf(Parameter(LATTICE_RIG_ANGLE_X, "X", -1f, 1f, 0f), Parameter(LATTICE_RIG_ANGLE_Y, "Y", 0f, 1f, 0f)),
		parts = emptyList(),
		deformers = listOf(rotation, outer, inner, ranged),
		drawables = emptyList(),
		rootChildren = emptyList(),
		rootPartId = null,
	)
}