package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.worldToScreen
import kotlin.test.assertEquals

/*
 * The rig the viewport gizmo tests share.  Three drawables, none under a deformer:
 *   - quad: a 20 x 20 square of two triangles (0-1-2, 0-2-3), local (0,0) (20,0) (20,20) (0,20).  Vertex 0
 *     neighbors 1, 2, and 3, so a Vertex Slide from it has three edges to choose from.
 *   - other: a triangle beside the quad, local (40,0) (60,0) (40,20).
 *   - hidden: a triangle under a deformer the model does not hold, so it has no world mapping and the Edit
 *     overlay cannot project it - the empty-geometry state.
 * A parentless drawable's world z is its local y negated, so the quad spans world x 0..20 and z -20..0.
 * The camera centers it in a 400 x 300 area at four pixels per world unit: world (x, z) lands on screen
 * (160 + 4x, 110 - 4z), so the quad's corners sit at (160,110) (240,110) (240,190) (160,190).
 */

/** The square the rig's tests edit. */
internal val RIG_QUAD = DrawableId("quad")

/** The triangle beside the square. */
internal val RIG_OTHER = DrawableId("other")

/** The triangle no overlay can project. */
internal val RIG_HIDDEN = DrawableId("hidden")

/** The rig's area width in pixels. */
internal const val RIG_AREA_WIDTH = 400

/** The rig's area height in pixels. */
internal const val RIG_AREA_HEIGHT = 300

/** The rig's area size. */
internal val RIG_AREA_SIZE = IntSize(RIG_AREA_WIDTH, RIG_AREA_HEIGHT)

/** The camera that centers the quad in the area at four pixels per world unit. */
internal val RIG_CAMERA = ViewportCamera(centerX = 10f, centerY = -10f, zoom = 4f)

/**
 * One rig drawable with no deformer channels.
 *
 * @param DrawableId id The drawable.
 * @param FloatArray positions The local positions.
 * @param IntArray indices The triangle indices.
 * @param DeformerId? parentDeformerId The parent deformer, or null for the root.
 * @return Drawable The drawable.
 */
private fun rigDrawable(id: DrawableId, positions: FloatArray, indices: IntArray, parentDeformerId: DeformerId? = null): Drawable =
	Drawable(
		id = id,
		name = id.raw,
		parentDeformerId = parentDeformerId,
		blendMode = BlendMode.Normal,
		maskedBy = emptyList(),
		mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), indices),
		geometryGrid = null,
	)

/**
 * The rig's model.
 *
 * @return PuppetModel The quad, the triangle beside it, and the unprojectable triangle.
 */
internal fun gizmoRigModel(): PuppetModel =
	PuppetModel(
		parameters = emptyList(),
		parts = emptyList(),
		deformers = emptyList(),
		drawables =
			listOf(
				rigDrawable(RIG_QUAD, floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), intArrayOf(0, 1, 2, 0, 2, 3)),
				rigDrawable(RIG_OTHER, floatArrayOf(40f, 0f, 60f, 0f, 40f, 20f), intArrayOf(0, 1, 2)),
				rigDrawable(RIG_HIDDEN, floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), intArrayOf(0, 1, 2), DeformerId("missing")),
			),
		rootChildren = listOf(OrgChild.Drawable(RIG_QUAD), OrgChild.Drawable(RIG_OTHER), OrgChild.Drawable(RIG_HIDDEN)),
		rootPartId = null,
	)

/**
 * An Edit-mode session over the rig with the given drawables in the edit and the given elements of the
 * first one selected; the last element given is the active one.
 *
 * @param List<DrawableId> editing The drawables the Edit session spans, the first active.
 * @param List<MeshElement> elements The elements to select on the first drawable, in order.
 * @return EditorSession The session, asserted to be in Edit mode.
 */
internal fun gizmoEditSession(editing: List<DrawableId> = listOf(RIG_QUAD), elements: List<MeshElement> = emptyList()): EditorSession {
	val session = EditorSession(gizmoRigModel())
	val targets = editing.map { drawableId -> SelectionTarget.Drawable(drawableId) }
	session.setSelection(Selection(targets.toSet(), targets.first()))
	session.setMode(EditorMode.Edit)
	assertEquals(EditorMode.Edit, session.mode.value, "the rig must really be in Edit mode")
	var selection = session.meshSelection.value
	for (element in elements) {
		selection = MeshSelectionOps.add(selection, editing.first(), element)
	}
	if (elements.isNotEmpty()) {
		session.setMeshSelection(selection)
	}
	return session
}

/**
 * An Object-mode session over the rig with the given drawables selected, the first active; none for an
 * empty selection.
 *
 * @param List<DrawableId> selected The drawables to select.
 * @return EditorSession The session.
 */
internal fun gizmoObjectSession(selected: List<DrawableId> = listOf(RIG_QUAD)): EditorSession {
	val session = EditorSession(gizmoRigModel())
	val targets = selected.map { drawableId -> SelectionTarget.Drawable(drawableId) }
	session.setSelection(Selection(targets.toSet(), targets.firstOrNull()))
	return session
}

/**
 * Where a world point lands on the rig's screen.
 *
 * @param Float worldX The world x.
 * @param Float worldZ The world z.
 * @return Offset The area-local pixel.
 */
internal fun rigScreenOf(worldX: Float, worldZ: Float): Offset = worldToScreen(worldX, worldZ, RIG_CAMERA, RIG_AREA_SIZE)

/**
 * The local positions of one drawable in the session's committed model.
 *
 * @param EditorSession session The session.
 * @param DrawableId drawableId The drawable.
 * @return List<Float> Its positions, as a list for a readable comparison.
 */
internal fun rigPositionsOf(session: EditorSession, drawableId: DrawableId): List<Float> =
	session.model.value.drawables.first { drawable -> drawable.id == drawableId }.mesh!!.positions.toList()