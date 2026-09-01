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
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasBindingFor
import org.umamo.runtime.model.layerUvsFromAtlasUvs
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.GizmoMeshGeometry
import org.umamo.ui.viewport.atlasPageIndexFor
import org.umamo.ui.viewport.uvToDisplay

/** The AreaScope.spaceState key the UV editor parks its view state under. */
internal const val UV_EDITOR_VIEW_STATE_KEY = "uv.view"

/**
 * What the UV editor area is showing: the auto-follow default, one atlas page pinned regardless of the
 * selection, or the active drawable's own source artwork.  Sealed so a further surface joins as another
 * case rather than by widening the existing ones.
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
	/** The area's texture selection: follow the session, a pinned atlas page, or the source-layer view. */
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
 * Null when nothing resolves a layer - an empty atlas, no active drawable, or a drawable with no
 * tile of its own.  The space falls back to its page view then, so choosing the layer mode never
 * blanks the editor.
 *
 * @param PuppetModel model The session's committed model.
 * @param MeshSelection meshSelection The mesh-element selection (its active drawable wins).
 * @param Selection objectSelection The object selection (its active drawable is the second choice).
 * @return UvEditorLayer? The resolved layer context, or null with no layer to show.
 */
internal fun resolveUvEditorLayer(
	model: PuppetModel,
	meshSelection: MeshSelection,
	objectSelection: Selection,
): UvEditorLayer? {
	if (model.atlas.tiles.isEmpty()) {
		return null
	}
	val activeDrawable = activeUvDrawable(model, meshSelection, objectSelection) ?: return null
	// The tile the drawable itself names: a session-created duplicate copies the field along with the
	// rest of the drawable, so it finds its art natively rather than through its source.
	val entry = activeDrawable.atlasTileId?.let { tileId -> model.atlas.tileById[tileId] } ?: return null
	if (entry.width <= 0 || entry.height <= 0) {
		return null
	}
	return UvEditorLayer(entry.id.raw, entry.width, entry.height)
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
 * The meshes drawn over a shown source layer, under the same Edit / Object rule the page view uses:
 * the session's edited meshes while editing, every visible mesh while selecting.
 *
 * Duplicated art shares a layer, so several drawables can draw over one image at once - which is why
 * Object mode shows them all (they are the click targets) and Edit mode does not (only the meshes the
 * session is editing can actually be moved).
 *
 * @param PuppetModel model The session's committed model.
 * @param EditorMode mode The session mode selecting the Edit / Object candidate rule.
 * @param MeshSelection meshSelection The mesh-element selection (Edit mode's candidate set).
 * @param String layerKey The shown layer.
 * @return List<Drawable> The drawables whose mappings draw over the layer, in model order.
 */
internal fun shownLayerDrawables(
	model: PuppetModel,
	mode: EditorMode,
	meshSelection: MeshSelection,
	layerKey: String,
): List<Drawable> =
	shownSurfaceDrawables(model, mode, meshSelection) { drawable -> drawable.atlasTileId?.raw == layerKey }

/**
 * Each shown drawable's mapping IN THE SHOWN SURFACE'S OWN FRAME - the coordinates that address the
 * image the editor is drawing over.
 *
 * Over a page those are the stored coordinates verbatim; over a source layer they are the stored ones
 * recovered through the drawable's placement.  One map serves both of the things that need them: the
 * display projection the overlays draw, and the alpha gate the island pick samples the shown image
 * with.  Deriving them once is what keeps those two from disagreeing about where a mesh is.
 *
 * A drawable whose recovery is degenerate is absent rather than mapped to a wrong place.
 *
 * @param List<Drawable> shownDrawables The drawables drawn over the shown surface.
 * @param PuppetModel    model The puppet, for the atlas the layer mapping derives from.
 * @param UvEditorLayer? layerView The shown layer, or null when a page is shown.
 * @return Map<DrawableId, FloatArray> Each drawable's mapping in the shown surface's frame.
 */
internal fun shownSurfaceUvs(
	shownDrawables: List<Drawable>,
	model: PuppetModel,
	layerView: UvEditorLayer?,
): Map<DrawableId, FloatArray> =
	shownDrawables
		.mapNotNull { drawable ->
			val mesh = drawable.mesh ?: return@mapNotNull null
			if (layerView == null) {
				return@mapNotNull drawable.id to mesh.uvs
			}
			val binding = model.atlasBindingFor(drawable) ?: return@mapNotNull null
			val layerUvs = layerUvsFromAtlasUvs(mesh.uvs, binding, layerView.width, layerView.height) ?: return@mapNotNull null
			drawable.id to layerUvs
		}
		.toMap()

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
): List<Drawable> =
	shownSurfaceDrawables(model, mode, meshSelection) { drawable ->
		textures == null || atlasPageIndexFor(drawable, textures) == pageIndex
	}

/**
 * The meshes drawn over whichever surface the UV editor is showing.
 *
 * The Edit / Object split is the whole rule and it belongs to the EDITOR, not to the surface: Edit
 * mode shows the session's edited meshes, because those are the ones an operator can act on - showing
 * more would offer the user vertices that refuse to move - and Object mode shows every visible mesh,
 * because those are the click targets.  Only [drawsOverSurface] differs between a page and a source
 * layer, which is what keeps the two views from drifting apart on the rule they share.
 *
 * Meshes without an editable UV array (empty or malformed) are excluded everywhere.  Visibility
 * follows the Parts-panel eyeball cascade in Object mode, matching what the viewport can render and
 * therefore pick; a hidden island neither draws nor picks.
 *
 * @param PuppetModel model The session's committed model.
 * @param EditorMode mode The session mode selecting the Edit / Object candidate rule.
 * @param MeshSelection meshSelection The mesh-element selection (Edit mode's candidate set).
 * @param Function drawsOverSurface Whether a drawable's mapping addresses the shown surface.
 * @return List<Drawable> The drawables whose mappings draw over it, in model order.
 */
internal fun shownSurfaceDrawables(
	model: PuppetModel,
	mode: EditorMode,
	meshSelection: MeshSelection,
	drawsOverSurface: (Drawable) -> Boolean,
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
		mesh != null && mesh.uvs.isNotEmpty() && mesh.uvs.size == mesh.positions.size && drawsOverSurface(drawable)
	}
}

/**
 * Projects the shown drawables' mappings into display-space gizmo geometry: texel units with the
 * v-axis flip (see UvDisplayMapping.kt), edges derived from the triangle indices.
 *
 * Takes the mappings rather than reading them off the drawables, because which frame they are in is
 * the caller's business (see [shownSurfaceUvs]) - which is what lets one projection serve the page
 * view and the layer view alike, and every overlay downstream stay unaware of the difference.
 *
 * @param List<Drawable> shownDrawables The drawables drawn over the shown surface.
 * @param Map<DrawableId, FloatArray> uvsById Each drawable's mapping in the shown surface's frame.
 * @param Int displayWidth The shown surface's width in texels (the display mapping's scale).
 * @param Int displayHeight The shown surface's height in texels.
 * @return List<GizmoMeshGeometry> One geometry per drawable with a mapping.
 */
internal fun uvGizmoGeometries(
	shownDrawables: List<Drawable>,
	uvsById: Map<DrawableId, FloatArray>,
	displayWidth: Int,
	displayHeight: Int,
): List<GizmoMeshGeometry> =
	shownDrawables.mapNotNull { drawable ->
		val mesh = drawable.mesh ?: return@mapNotNull null
		val uvs = uvsById[drawable.id] ?: return@mapNotNull null
		GizmoMeshGeometry(drawable.id, mesh.indices, MeshTopology.uniqueEdges(mesh.indices), uvToDisplay(uvs, displayWidth, displayHeight))
	}