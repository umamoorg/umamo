package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.assertNotNull

/*
 * The rig the lattice tests share: two warps in a Body part, one in a Guide part, a rotation, and a quad of art.
 *   - body: a root 1x1 warp over world x 0..40 and z -40..0.
 *   - head: a 1x1 warp inside body.
 *   - guide: a root 1x1 warp in the Guide part, which a test can hide.
 *   - turn: a root rotation, which has no lattice.
 *   - art: a 20 x 20 quad at the root of the org tree, under no deformer.
 */

/** The parameter every keyed entity of the rig is keyed on, with two keys holding one form. */
internal val LATTICE_RIG_PARAMETER = ParameterId("ParamAngleX")

/** The root warp in the Body part. */
internal val LATTICE_RIG_BODY = DeformerId("body")

/** The warp inside the body warp. */
internal val LATTICE_RIG_HEAD = DeformerId("head")

/** The root warp in the Guide part. */
internal val LATTICE_RIG_GUIDE = DeformerId("guide")

/** The root rotation. */
internal val LATTICE_RIG_TURN = DeformerId("turn")

/** The Body part. */
internal val LATTICE_RIG_BODY_PART = PartId("bodyPart")

/** The Guide part. */
internal val LATTICE_RIG_GUIDE_PART = PartId("guidePart")

/** The quad of art. */
internal val LATTICE_RIG_ART = DrawableId("art")

/**
 * A 1x1 warp keyed with two keys holding one lattice, so it is shown at every value of the parameter.
 *
 * @param DeformerId id The warp.
 * @param DeformerId? parent Its parent deformer, or null at the root.
 * @param PartId partId Its part.
 * @param FloatArray points Its four control points, in its parent's space.
 * @return Deformer.Warp The warp.
 */
private fun latticeRigWarp(id: DeformerId, parent: DeformerId?, partId: PartId, points: FloatArray): Deformer.Warp =
	Deformer.Warp(
		id = id,
		name = id.raw,
		parent = parent,
		partId = partId,
		rows = 1,
		columns = 1,
		isQuadTransform = true,
		geometryGrid =
			KeyformGrid(
				listOf(KeyformAxis(LATTICE_RIG_PARAMETER, floatArrayOf(-1f, 1f))),
				listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(points)), KeyformCell(intArrayOf(1), WarpLatticeForm(points))),
			),
	)

/**
 * The rig's model.
 *
 * @return PuppetModel The model.
 */
internal fun latticeRigModel(): PuppetModel {
	val rotation =
		Deformer.Rotation(
			id = LATTICE_RIG_TURN,
			name = "turn",
			parent = null,
			partId = LATTICE_RIG_BODY_PART,
			baseAngle = 0f,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(LATTICE_RIG_PARAMETER, floatArrayOf(-1f, 1f))),
					listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0f, 0f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0f, 0f, 0f, 1f))),
				),
		)
	val art =
		Drawable(
			id = LATTICE_RIG_ART,
			name = "art",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
		)

	return PuppetModel(
		parameters = listOf(Parameter(LATTICE_RIG_PARAMETER, "X", -1f, 1f, 0f)),
		parts =
			listOf(
				Part(LATTICE_RIG_BODY_PART, "Body", emptyList()),
				Part(LATTICE_RIG_GUIDE_PART, "Guide", emptyList()),
			),
		deformers =
			listOf(
				rotation,
				latticeRigWarp(LATTICE_RIG_BODY, null, LATTICE_RIG_BODY_PART, floatArrayOf(0f, 0f, 40f, 0f, 0f, 40f, 40f, 40f)),
				latticeRigWarp(LATTICE_RIG_HEAD, LATTICE_RIG_BODY, LATTICE_RIG_BODY_PART, floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)),
				latticeRigWarp(LATTICE_RIG_GUIDE, null, LATTICE_RIG_GUIDE_PART, floatArrayOf(60f, 0f, 80f, 0f, 60f, 20f, 80f, 20f)),
			),
		drawables = listOf(art),
		rootChildren = listOf(OrgChild.Part(LATTICE_RIG_BODY_PART), OrgChild.Part(LATTICE_RIG_GUIDE_PART), OrgChild.Drawable(LATTICE_RIG_ART)),
		rootPartId = null,
	)
}

/**
 * A session over the rig with the given targets selected, the last active; none for an empty selection.
 *
 * @param List<SelectionTarget> selected The targets to select.
 * @return EditorSession The session, in Object mode.
 */
internal fun latticeRigSession(selected: List<SelectionTarget> = emptyList()): EditorSession {
	val session = EditorSession(latticeRigModel())
	session.setSelection(Selection(selected.toSet(), selected.lastOrNull()))

	return session
}

/** Where the pointer rests before a gesture latches in a rig viewport: the gesture measures from here. */
internal val LATTICE_RIG_GESTURE_START = Offset(200f, 150f)

/** Forty pixels right of [LATTICE_RIG_GESTURE_START]: ten world units at the rig's zoom. */
internal val LATTICE_RIG_TEN_UNITS_RIGHT = Offset(240f, 150f)

/**
 * A warp's rest lattice in world space.
 *
 * @param EditorSession session The session.
 * @param DeformerId deformerId The warp.
 * @return FloatArray The world points.
 */
internal fun latticeRigRestWorld(session: EditorSession, deformerId: DeformerId): FloatArray = assertNotNull(warpLatticeAt(session.model.value, emptyMap(), deformerId)).world

/**
 * Selects [points] of [owner]'s lattice in the session's element selection, [active] the active one; no points leaves
 * the selection as it is.
 *
 * @param ElementOwner owner The lattice.
 * @param Set<Int> points The points to select.
 * @param Int? active The active point, or null.
 */
internal fun EditorSession.selectLatticePoints(owner: ElementOwner, points: Set<Int>, active: Int?) {
	if (points.isEmpty()) {
		return
	}

	setMeshSelection(
		meshSelection.value.copy(
			elementsByOwner = mapOf(owner to points.map { pointIndex -> MeshElement.Vertex(pointIndex) }.toSet()),
			activeElement = active?.let { pointIndex -> ActiveMeshElement(owner, MeshElement.Vertex(pointIndex)) },
		),
	)
}