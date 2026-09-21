package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasBindingFor
import org.umamo.ui.viewport.GizmoMeshGeometry

/**
 * The shown islands as the overlays consume them: each drawable's mapping in the shown surface's own
 * frame, and its display-space gizmo geometry, in shown order.
 *
 * @property Map<DrawableId, FloatArray> uvsById Each island's mapping in the shown surface's frame.
 * @property List<GizmoMeshGeometry> geometries One geometry per island with a mapping.
 */
internal class UvShownIslands(
	val uvsById: Map<DrawableId, FloatArray>,
	val geometries: List<GizmoMeshGeometry>,
)

/**
 * Derives the shown islands incrementally across frames: an island whose inputs kept their identity
 * hands back the very same mapping array and geometry instance it had, and only the islands whose
 * inputs changed are rebuilt.
 *
 * The UV editor re-derives its islands from whatever model it shows, and during a modal G / S / R that
 * model is a fresh preview instance per pointer frame - one that differs from the committed model in
 * nothing but the moved drawables' uv arrays.  Rebuilding every visible island of an atlas page for
 * each of those frames costs a frame budget on its own (the unique-edge derivation alone allocates
 * three edge objects per triangle), for edges and positions that did not change.  So the derivation
 * follows the renderer's own diff rule and compares the arrays by identity: a stored uv array, an
 * index array, a binding, a layer, or a surface size that is the same as last frame yields the same
 * output, and a uv array that changed under an unchanged index array keeps its edges.
 *
 * The rules of what an island's mapping and geometry ARE stay in UvEditorViewState.kt
 * ([surfaceUvsOf], [gizmoGeometryOf]); this class decides only when to run them again.  One
 * instance per UV editor area, remembered across frames and commits, never across documents.
 */
internal class UvIslandCache {
	/**
	 * One island's inputs as of its last derivation, and what they produced.
	 *
	 * @property FloatArray storedUvs The stored uv array the mapping derived from (identity).
	 * @property IntArray indices The index array the geometry derived from (identity).
	 * @property DrawableLayerBinding? binding The layer binding over a source layer, null over a page.
	 * @property UvEditorLayer? layerView The shown layer, null over a page.
	 * @property Int displayWidth The surface width the geometry projected into.
	 * @property Int displayHeight The surface height the geometry projected into.
	 * @property FloatArray surfaceUvs The derived mapping in the shown surface's frame.
	 * @property GizmoMeshGeometry geometry The derived gizmo geometry.
	 */
	private class Entry(
		val storedUvs: FloatArray,
		val indices: IntArray,
		val binding: DrawableLayerBinding?,
		val layerView: UvEditorLayer?,
		val displayWidth: Int,
		val displayHeight: Int,
		val surfaceUvs: FloatArray,
		val geometry: GizmoMeshGeometry,
	)

	private var entries: Map<DrawableId, Entry> = emptyMap()

	/**
	 * The shown islands for this frame, reusing last frame's derivations wherever the inputs are the
	 * same objects.  An island absent from [shownDrawables] is forgotten; one that returns later is
	 * derived afresh.
	 *
	 * @param List<Drawable> shownDrawables The drawables drawn over the shown surface, in shown order.
	 * @param PuppetModel model The model the drawables came from (the preview while a gesture runs).
	 * @param UvEditorLayer? layerView The shown layer, or null when a page is shown.
	 * @param Int displayWidth The shown surface's width in texels.
	 * @param Int displayHeight The shown surface's height in texels.
	 * @return UvShownIslands The mappings and geometries, in shown order.
	 */
	fun update(
		shownDrawables: List<Drawable>,
		model: PuppetModel,
		layerView: UvEditorLayer?,
		displayWidth: Int,
		displayHeight: Int,
	): UvShownIslands {
		val next = LinkedHashMap<DrawableId, Entry>(shownDrawables.size)
		val uvsById = LinkedHashMap<DrawableId, FloatArray>(shownDrawables.size)
		val geometries = ArrayList<GizmoMeshGeometry>(shownDrawables.size)
		for (drawable in shownDrawables) {
			val mesh = drawable.mesh ?: continue
			val binding = if (layerView == null) null else model.atlasBindingFor(drawable)
			val previous = entries[drawable.id]
			val entry =
				if (previous != null && previous.matches(mesh.uvs, mesh.indices, binding, layerView, displayWidth, displayHeight)) {
					previous
				} else {
					val surfaceUvs = surfaceUvsOf(drawable, model, layerView) ?: continue
					// A uv-only change keeps the edges: they derive from the indices alone.
					val edges = previous?.takeIf { entry -> entry.indices === mesh.indices }?.geometry?.edges
					val geometry = gizmoGeometryOf(drawable, surfaceUvs, displayWidth, displayHeight, edges) ?: continue
					Entry(mesh.uvs, mesh.indices, binding, layerView, displayWidth, displayHeight, surfaceUvs, geometry)
				}
			next[drawable.id] = entry
			uvsById[drawable.id] = entry.surfaceUvs
			geometries.add(entry.geometry)
		}
		entries = next
		return UvShownIslands(uvsById, geometries)
	}

	/**
	 * Whether this entry's derivation still holds for the given inputs: the arrays by identity, the
	 * binding and layer by value, the surface size by value.
	 *
	 * @param FloatArray storedUvs The drawable's current stored uv array.
	 * @param IntArray indices The drawable's current index array.
	 * @param DrawableLayerBinding? binding The current layer binding, null over a page.
	 * @param UvEditorLayer? layerView The shown layer, null over a page.
	 * @param Int displayWidth The shown surface's width in texels.
	 * @param Int displayHeight The shown surface's height in texels.
	 * @return Boolean True when the cached derivation can be handed back as is.
	 */
	private fun Entry.matches(
		storedUvs: FloatArray,
		indices: IntArray,
		binding: DrawableLayerBinding?,
		layerView: UvEditorLayer?,
		displayWidth: Int,
		displayHeight: Int,
	): Boolean =
		this.storedUvs === storedUvs &&
			this.indices === indices &&
			this.binding == binding &&
			this.layerView == layerView &&
			this.displayWidth == displayWidth &&
			this.displayHeight == displayHeight
}