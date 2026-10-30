package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm

/**
 * The rig the Pose-mode lattice tests share: two warps keyed on P at its ends (each lattice sliding 10 units right
 * from -1 to 1), a warp keyed only above the default (drawn at P = 0.75, hidden at rest), a rotation, and a quad of
 * art.  Q is a second parameter none of them is keyed on.
 */
internal object PoseLatticeTestRig {
	val parameterP = ParameterId("P")
	val parameterQ = ParameterId("Q")
	val art = DrawableId("art")
	val cage = DeformerId("cage")
	val second = DeformerId("second")
	val offRest = DeformerId("offRest")
	val turn = DeformerId("turn")

	/**
	 * A 2 x 2 lattice over a 40-unit square at [originX], as nine points row by row.
	 *
	 * @param Float originX The square's left edge.
	 * @return FloatArray The points.
	 */
	fun square(originX: Float): FloatArray =
		FloatArray(18) { componentIndex ->
			val pointIndex = componentIndex / 2

			if (componentIndex % 2 == 0) originX + (pointIndex % 3) * 20f else (pointIndex / 3) * 20f
		}

	/**
	 * A lattice of nine points, each component [x] or [y] plus its component index - a shape no blend of the rig's
	 * keys produces.
	 *
	 * @param Float x The base every x component adds its index to.
	 * @param Float y The base every y component adds its index to.
	 * @return WarpLatticeForm The lattice.
	 */
	fun shaped(
		x: Float,
		y: Float,
	): WarpLatticeForm = WarpLatticeForm(FloatArray(18) { componentIndex -> (if (componentIndex % 2 == 0) x else y) + componentIndex })

	/**
	 * A 2 x 2 warp keyed on P at [keys], each key holding the square at [originX] shifted by 5 units per unit of P.
	 *
	 * @param DeformerId id The warp.
	 * @param Float originX The square's left edge at P = 0.
	 * @param FloatArray keys The keys.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(
		id: DeformerId,
		originX: Float,
		keys: FloatArray,
	): Deformer.Warp = SessionTestModels.keyedWarp(id, 2, 2, parameterP, keys) { keyIndex -> square(originX + keys[keyIndex] * 5f) }

	/**
	 * The rig.
	 *
	 * @param Boolean withWarps False for a rig whose only deformer is the rotation.
	 * @return PuppetModel The model.
	 */
	fun model(withWarps: Boolean = true): PuppetModel {
		val rotation =
			Deformer.Rotation(
				id = turn,
				name = "turn",
				parent = null,
				partId = null,
				baseAngle = 0f,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(parameterP, floatArrayOf(-1f, 1f))),
						listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0f, 0f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0f, 0f, 0f, 1f))),
					),
			)
		val warps = listOf(warp(cage, 0f, floatArrayOf(-1f, 1f)), warp(second, 100f, floatArrayOf(-1f, 1f)), warp(offRest, 300f, floatArrayOf(0.5f, 1f)))

		return PuppetModel(
			parameters = listOf(Parameter(parameterP, "P", -1f, 1f, 0f), Parameter(parameterQ, "Q", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = if (withWarps) warps + rotation else listOf(rotation),
			drawables =
				listOf(
					Drawable(
						id = art,
						name = "art",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
						geometryGrid = null,
					),
				),
			rootChildren = listOf(OrgChild.Drawable(art)),
			rootPartId = null,
		)
	}

	/**
	 * A session over the rig posed at [pose] with [selected] selected, the last active, and Pose mode entered.
	 *
	 * @param List<SelectionTarget> selected The targets to select.
	 * @param Pose pose The pose to scrub to before entering.
	 * @return EditorSession The session.
	 */
	fun posing(
		selected: List<SelectionTarget>,
		pose: Pose = emptyMap(),
	): EditorSession {
		val session = EditorSession(model())

		if (pose.isNotEmpty()) {
			session.commitPose(ParameterChange.SetValue(pose.keys.toList()), session.pose.value + pose)
		}

		session.setSelection(Selection(selected.toSet(), selected.lastOrNull()))
		session.setMode(EditorMode.Pose)

		return session
	}
}