package org.umamo.ui.workspace.spaces

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.GizmoMeshGeometry
import org.umamo.ui.viewport.atlasPageIndexFor
import org.umamo.ui.viewport.uvToDisplay

/**
 * The UV editor's resolved page context: which drawable drives the shown page and the page's texel
 * dimensions.
 *
 * @property Drawable activeDrawable The drawable driving the shown page (always meshed).
 * @property Int? pageIndex The shown atlas page's index, or null for an untextured drawable or absent textures.
 * @property Int pageWidth The page width in texels (1 for the untextured fallback).
 * @property Int pageHeight The page height in texels (1 for the untextured fallback).
 */
internal data class UvEditorPage(
	val activeDrawable: Drawable,
	val pageIndex: Int?,
	val pageWidth: Int,
	val pageHeight: Int,
)

/**
 * Resolves which atlas page the UV editor shows: the page follows the session's active drawable
 * (Edit-mode active mesh first, then the object selection's active drawable), falling back to the
 * first meshed drawable so the space is never blank.  Null when no meshed drawable resolves at all
 * (the space shows its placeholder).
 *
 * Untextured fallback: a 1x1 "page" turns the display mapping into the flipped unit square, so the
 * wireframe still shows (over the grid) for a drawable with no atlas entry.  Must equal the
 * service's pageContentBounds dimensions so the Compose wireframe and the GL page frame at the same
 * camera align.
 *
 * @param PuppetModel model The session's committed model.
 * @param MeshSelection meshSelection The mesh-element selection (its active drawable wins).
 * @param Selection objectSelection The object selection (its active drawable is the second choice).
 * @param PuppetTextures? textures The decoded atlas pages, or null before textures load.
 * @return UvEditorPage? The resolved page context, or null with no meshed drawable to show.
 */
internal fun resolveUvEditorPage(
	model: PuppetModel,
	meshSelection: MeshSelection,
	objectSelection: Selection,
	textures: PuppetTextures?,
): UvEditorPage? {
	val activeDrawableId =
		meshSelection.activeDrawableId
			?: (objectSelection.active as? SelectionTarget.Drawable)?.id
			?: model.drawables.firstOrNull { drawable -> drawable.mesh != null }?.id
	val activeDrawable = model.drawables.firstOrNull { drawable -> drawable.id == activeDrawableId }
	if (activeDrawable?.mesh == null) {
		return null
	}
	val pageIndex = textures?.let { puppetTextures -> atlasPageIndexFor(activeDrawable, puppetTextures) }
	val page = if (textures != null && pageIndex != null) textures.atlases.getOrNull(pageIndex) else null
	return UvEditorPage(
		activeDrawable = activeDrawable,
		pageIndex = pageIndex,
		pageWidth = page?.width ?: 1,
		pageHeight = page?.height ?: 1,
	)
}

/**
 * The meshes drawn over the shown page: in Edit mode every session mesh sampling this page (the
 * active one is emphasized via the highlight sets), in Object mode EVERY visible meshed drawable
 * mapped to the page, in model (parts-tree) order - the islands are the click targets, so all of
 * them show, selection styling the difference.  Visibility follows the Parts-panel eyeball cascade
 * (visibleDrawableIds), matching what the viewport can render and therefore pick; a hidden island
 * neither draws nor picks.  Meshes without an editable UV array (empty or malformed) and meshes
 * mapped to another page are excluded everywhere.
 *
 * @param PuppetModel model The session's committed model.
 * @param EditorMode mode The session mode selecting the Edit / Object candidate rule.
 * @param MeshSelection meshSelection The mesh-element selection (Edit mode's candidate set).
 * @param PuppetTextures? textures The decoded atlas pages, or null before textures load.
 * @param Int? pageIndex The shown page's index (the page filter), or null for the untextured fallback.
 * @return List<Drawable> The drawables whose mappings draw over the page.
 */
internal fun shownUvDrawables(
	model: PuppetModel,
	mode: EditorMode,
	meshSelection: MeshSelection,
	textures: PuppetTextures?,
	pageIndex: Int?,
): List<Drawable> {
	val candidates =
		if (mode == EditorMode.Edit) {
			meshSelection.drawableIds.mapNotNull { drawableId -> model.drawables.firstOrNull { drawable -> drawable.id == drawableId } }
		} else {
			val shownIds = model.visibleDrawableIds()
			model.drawables.filter { drawable -> drawable.id in shownIds }
		}
	return candidates.filter { drawable ->
		val mesh = drawable.mesh
		mesh != null &&
			mesh.uvs.isNotEmpty() &&
			mesh.uvs.size == mesh.positions.size &&
			(textures == null || atlasPageIndexFor(drawable, textures) == pageIndex)
	}
}

/**
 * Projects the shown drawables' UV mappings into display-space gizmo geometry: texel units with the
 * v-axis flip (see UvDisplayMapping.kt), edges derived from the triangle indices.
 *
 * @param List<Drawable> shownDrawables The drawables whose mappings draw over the page.
 * @param Int pageWidth The shown page's width in texels (the display mapping's scale).
 * @param Int pageHeight The shown page's height in texels.
 * @return List<GizmoMeshGeometry> One geometry per meshed drawable.
 */
internal fun uvGizmoGeometries(
	shownDrawables: List<Drawable>,
	pageWidth: Int,
	pageHeight: Int,
): List<GizmoMeshGeometry> =
	shownDrawables.mapNotNull { drawable ->
		val mesh = drawable.mesh ?: return@mapNotNull null
		GizmoMeshGeometry(drawable.id, mesh.indices, MeshTopology.uniqueEdges(mesh.indices), uvToDisplay(mesh.uvs, pageWidth, pageHeight))
	}