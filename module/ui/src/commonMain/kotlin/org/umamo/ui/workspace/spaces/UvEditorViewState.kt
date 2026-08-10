package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.UvPageKind
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.GizmoMeshGeometry
import org.umamo.ui.viewport.atlasPageIndexFor
import org.umamo.ui.viewport.uvToDisplay

/** The AreaScope.spaceState key the UV editor parks its view state under. */
internal const val UV_EDITOR_VIEW_STATE_KEY = "uv.view"

/**
 * What the UV editor area is showing: the auto-follow default, or one atlas page pinned regardless
 * of the selection.  Sealed so the source-layer view can join as a third mode without touching the
 * existing cases.
 */
internal sealed class UvTextureSelection {
	/** The auto-follow default: the shown page tracks the session's active drawable. */
	data object FollowSelection : UvTextureSelection()

	/** The space keeps showing atlas page [pageIndex] regardless of the selection. */
	data class PinnedPage(val pageIndex: Int) : UvTextureSelection()
}

/**
 * The UV editor's per-area view state, shared between its area-header controls and its body (they
 * render as sibling subtrees, so this lives on the hosting AreaScope via spaceState rather than in a
 * body-local remember).  Lifetime follows the leaf area: it survives switching the space away and
 * back, two UV editors each get their own instance, and it resets when the leaf closes.  In-memory
 * on purpose - not a settings key; the native UMA format is the intended future persistence home.
 *
 * The leaf outlives the open document, so a pin can survive a document swap; resolution treats a pin
 * the new document cannot satisfy as Follow Selection (resolveUvEditorPage) without clearing what is
 * stored here.
 */
internal class UvEditorViewState {
	/** The area's texture selection: follow the session, or a pinned atlas page. */
	var textureSelection by mutableStateOf<UvTextureSelection>(UvTextureSelection.FollowSelection)
}

/**
 * The UV editor's resolved page context: the shown page and its texel dimensions.
 *
 * @property Int? pageIndex The shown atlas page's index, or null for an untextured drawable or absent textures.
 * @property Int pageWidth The page width in texels (1 for the untextured fallback).
 * @property Int pageHeight The page height in texels (1 for the untextured fallback).
 */
internal data class UvEditorPage(
	val pageIndex: Int?,
	val pageWidth: Int,
	val pageHeight: Int,
)

/**
 * Resolves which atlas page the UV editor shows.  A pinned page the textures can satisfy wins
 * outright - page-first, with no meshed-drawable requirement, so an empty page is still reviewable.
 * Otherwise (following the selection, or a pin the current document cannot satisfy) the page follows
 * the session's active drawable (Edit-mode active mesh first, then the object selection's active
 * drawable), falling back to the first meshed drawable so the space is never blank.  Null when no
 * meshed drawable resolves in follow mode (the space shows its placeholder).
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
 * @param UvTextureSelection textureSelection The area's texture selection (a valid pin wins the chain).
 * @return UvEditorPage? The resolved page context, or null with no meshed drawable to show.
 */
internal fun resolveUvEditorPage(
	model: PuppetModel,
	meshSelection: MeshSelection,
	objectSelection: Selection,
	textures: PuppetTextures?,
	textureSelection: UvTextureSelection = UvTextureSelection.FollowSelection,
): UvEditorPage? {
	if (textureSelection is UvTextureSelection.PinnedPage) {
		val pinnedPage = textures?.atlases?.getOrNull(textureSelection.pageIndex)
		if (pinnedPage != null) {
			return UvEditorPage(
				pageIndex = textureSelection.pageIndex,
				pageWidth = pinnedPage.width,
				pageHeight = pinnedPage.height,
			)
		}
	}
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
		pageIndex = pageIndex,
		pageWidth = page?.width ?: 1,
		pageHeight = page?.height ?: 1,
	)
}

/**
 * The texture-selection transition for one page-switch request: cycling pins the page adjacent to
 * the EFFECTIVE page (what the area is actually showing, pin or follow) with wrap-around, and
 * FollowSelection clears the pin.  From the untextured fallback (a null effective page) NextPage
 * pins the first page and PreviousPage the last, so cycling always lands somewhere real.  With no
 * pages at all there is nothing to pin and cycling keeps the selection unchanged.
 *
 * @param UvPageKind kind The requested page operation.
 * @param UvTextureSelection currentSelection The area's stored texture selection.
 * @param Int? effectivePageIndex The shown page's index, or null for the untextured fallback.
 * @param Int pageCount The document's atlas page count.
 * @return UvTextureSelection The texture selection after the request.
 */
internal fun uvPageSelectionAfter(
	kind: UvPageKind,
	currentSelection: UvTextureSelection,
	effectivePageIndex: Int?,
	pageCount: Int,
): UvTextureSelection =
	when (kind) {
		UvPageKind.FollowSelection -> UvTextureSelection.FollowSelection
		UvPageKind.NextPage ->
			if (pageCount <= 0) {
				currentSelection
			} else {
				UvTextureSelection.PinnedPage(((effectivePageIndex ?: -1) + 1).mod(pageCount))
			}
		UvPageKind.PreviousPage ->
			if (pageCount <= 0) {
				currentSelection
			} else {
				UvTextureSelection.PinnedPage(((effectivePageIndex ?: 0) - 1 + pageCount).mod(pageCount))
			}
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