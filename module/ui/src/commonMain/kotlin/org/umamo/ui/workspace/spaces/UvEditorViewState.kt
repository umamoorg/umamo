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
import org.umamo.render.LayerTextures
import org.umamo.render.PuppetTextures
import org.umamo.render.layerUvsFromAtlasUvs
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

	/**
	 * The space shows the active drawable's own source artwork instead of a packed page - the mapping
	 * over the art it was authored against.  Follow-active like [FollowSelection] and for the same
	 * reason: which layer to show is a restatement of which drawable is selected, so carrying a layer
	 * id here would be a second selection to keep in sync with the first.
	 */
	data object SourceLayer : UvTextureSelection()
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
 * The UV editor's resolved source-layer context: which layer the space shows and its pixel size.
 *
 * @property String layerKey The shown layer's key in the document's source-art store.
 * @property Int width The layer image's width in pixels.
 * @property Int height The layer image's height in pixels.
 */
internal data class UvEditorLayer(
	val layerKey: String,
	val width: Int,
	val height: Int,
)

/**
 * Resolves which source layer the UV editor shows: the one the active drawable was authored against,
 * following the same precedence the page chain uses (Edit-mode active mesh, then the object
 * selection's active drawable, then the first meshed drawable).
 *
 * Null when nothing resolves a layer - no store, no active drawable, or a drawable whose source art
 * this document does not retain.  The space falls back to its page view then, so choosing the layer
 * mode never blanks the editor.
 *
 * @param PuppetModel model The session's committed model.
 * @param MeshSelection meshSelection The mesh-element selection (its active drawable wins).
 * @param Selection objectSelection The object selection (its active drawable is the second choice).
 * @param LayerTextures? layers The document's source-art store, or null before one loads.
 * @return UvEditorLayer? The resolved layer context, or null with no layer to show.
 */
internal fun resolveUvEditorLayer(
	model: PuppetModel,
	meshSelection: MeshSelection,
	objectSelection: Selection,
	layers: LayerTextures?,
): UvEditorLayer? {
	if (layers == null || layers.isEmpty) {
		return null
	}
	val activeDrawable = activeUvDrawable(model, meshSelection, objectSelection) ?: return null
	val entry = layers.layerForDrawable(activeDrawable.id.raw) ?: return null
	if (entry.width <= 0 || entry.height <= 0) {
		return null
	}
	return UvEditorLayer(entry.key, entry.width, entry.height)
}

/**
 * The drawable the UV editor's view follows: the Edit-mode active mesh, else the object selection's
 * active drawable, else the first meshed drawable so the space is never blank.
 *
 * @param PuppetModel model The session's committed model.
 * @param MeshSelection meshSelection The mesh-element selection.
 * @param Selection objectSelection The object selection.
 * @return Drawable? The followed drawable, or null when the model has no meshed drawable at all.
 */
private fun activeUvDrawable(model: PuppetModel, meshSelection: MeshSelection, objectSelection: Selection): Drawable? {
	val activeDrawableId =
		meshSelection.activeDrawableId
			?: (objectSelection.active as? SelectionTarget.Drawable)?.id
			?: model.drawables.firstOrNull { drawable -> drawable.mesh != null }?.id
	return model.drawables.firstOrNull { drawable -> drawable.id == activeDrawableId }?.takeIf { it.mesh != null }
}

/**
 * The meshes drawn over a shown source layer: every drawable bound to it that carries an editable
 * mapping.  Usually one, but duplicated art shares a layer and all of its users draw together.
 *
 * @param PuppetModel model The session's committed model.
 * @param LayerTextures layers The document's source-art store.
 * @param String layerKey The shown layer.
 * @return List<Drawable> The drawables whose mappings draw over the layer, in model order.
 */
internal fun shownLayerDrawables(model: PuppetModel, layers: LayerTextures, layerKey: String): List<Drawable> {
	val boundIds = layers.layerFor(layerKey)?.boundDrawableIds?.toSet() ?: return emptyList()
	return model.drawables.filter { drawable ->
		val mesh = drawable.mesh
		drawable.id.raw in boundIds && mesh != null && mesh.uvs.isNotEmpty() && mesh.uvs.size == mesh.positions.size
	}
}

/**
 * Projects the shown drawables' mappings into the SOURCE LAYER's display space: each drawable's atlas
 * texture coordinates are recovered into its layer's own frame, then scaled to layer texels with the
 * same v-flip the page view uses (see UvDisplayMapping.kt), so every downstream overlay, camera, and
 * hit query works over layer geometry unchanged.
 *
 * A drawable whose recovery is degenerate is skipped rather than drawn at a wrong place.
 *
 * @param List<Drawable> shownDrawables The drawables bound to the shown layer.
 * @param LayerTextures layers The document's source-art store (holds the recovered bindings).
 * @param Int layerWidth The shown layer's width in pixels (the display mapping's scale).
 * @param Int layerHeight The shown layer's height in pixels.
 * @return List<GizmoMeshGeometry> One geometry per drawable whose mapping recovers.
 */
internal fun layerGizmoGeometries(
	shownDrawables: List<Drawable>,
	layers: LayerTextures,
	layerWidth: Int,
	layerHeight: Int,
): List<GizmoMeshGeometry> =
	shownDrawables.mapNotNull { drawable ->
		val mesh = drawable.mesh ?: return@mapNotNull null
		val binding = layers.bindingsByDrawableId[drawable.id.raw] ?: return@mapNotNull null
		val layerUvs = layerUvsFromAtlasUvs(mesh.uvs, binding, layerWidth, layerHeight) ?: return@mapNotNull null
		GizmoMeshGeometry(
			drawable.id,
			mesh.indices,
			MeshTopology.uniqueEdges(mesh.indices),
			uvToDisplay(layerUvs, layerWidth, layerHeight),
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