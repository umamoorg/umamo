package org.umamo.ui.workspace.spaces.uv

import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasBindingFor
import org.umamo.runtime.model.layerUvsFromAtlasUvs
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.uv.uvToDisplay

/**
 * A UV area's shown geometry kept from one derive to the next, so an edit rebuilds only the meshes it
 * touched.  A UV drive previews a model in which only the moved meshes carry new texture coordinates, and
 * every shown mesh is re-derived on each one; this hands back the previous arrays for every mesh whose
 * inputs are the same instances, which spares the edge walk and the display mapping, and is what lets the
 * renderer upload only the moved meshes' positions, since it compares them by identity.
 *
 * What each piece is kept by: a mesh's edges by its index array; its recovered layer coordinates by its
 * stored coordinates, its layer binding, and the layer's size; its display positions by the coordinates
 * in the shown surface's frame and the surface's size; and its whole geometry by those three results.
 * Each derive drops the meshes it no longer shows.
 *
 * Not thread-safe: the area's composition owns it.  Every entry is a pure function of what it is kept by,
 * so a composition that is thrown away leaves nothing wrong behind.
 */
internal class UvGizmoGeometryCache {
	/**
	 * One mesh's coordinates recovered into its layer's frame.
	 *
	 * @property FloatArray storedUvs The stored coordinates they came from (compared by identity).
	 * @property DrawableLayerBinding binding The binding they went through.
	 * @property Int layerWidth The layer's width.
	 * @property Int layerHeight The layer's height.
	 * @property FloatArray? recovered The coordinates in the layer's frame, or null when the mapping cannot
	 *   be formed.
	 */
	private class RecoveredUvs(
		val storedUvs: FloatArray,
		val binding: DrawableLayerBinding,
		val layerWidth: Int,
		val layerHeight: Int,
		val recovered: FloatArray?,
	)

	/**
	 * One mesh's display positions.
	 *
	 * @property FloatArray surfaceUvs The coordinates they were mapped from (compared by identity).
	 * @property Int displayWidth The surface width they were mapped at.
	 * @property Int displayHeight The surface height.
	 * @property FloatArray positions The display positions.
	 */
	private class DisplayPositions(
		val surfaceUvs: FloatArray,
		val displayWidth: Int,
		val displayHeight: Int,
		val positions: FloatArray,
	)

	/**
	 * One mesh's unique edges.
	 *
	 * @property IntArray indices The triangle indices they were walked from (compared by identity).
	 * @property List<MeshElement.Edge> edges The edges.
	 */
	private class Edges(
		val indices: IntArray,
		val edges: List<MeshElement.Edge>,
	)

	private val recoveredById = HashMap<DrawableId, RecoveredUvs>()
	private val displayById = HashMap<DrawableId, DisplayPositions>()
	private val edgesById = HashMap<DrawableId, Edges>()
	private val geometryById = HashMap<DrawableId, GizmoMeshGeometry>()

	/**
	 * Each shown drawable's mapping in the shown surface's own frame (see [shownSurfaceUvs]): the stored
	 * coordinates verbatim over a page, the kept recovery over a layer.
	 *
	 * @param List<Drawable> shownDrawables The drawables drawn over the shown surface.
	 * @param PuppetModel model The puppet, for the atlas the layer mapping derives from.
	 * @param UvEditorLayer? layerView The shown layer, or null when a page is shown.
	 * @return Map<DrawableId, FloatArray> Each drawable's mapping in the shown surface's frame.
	 */
	fun surfaceUvs(shownDrawables: List<Drawable>, model: PuppetModel, layerView: UvEditorLayer?): Map<DrawableId, FloatArray> {
		val surfaceUvsById = LinkedHashMap<DrawableId, FloatArray>(shownDrawables.size)
		for (drawable in shownDrawables) {
			val mesh = drawable.mesh ?: continue
			if (layerView == null) {
				surfaceUvsById[drawable.id] = mesh.uvs
				continue
			}
			val binding = model.atlasBindingFor(drawable) ?: continue
			val recovered = recoveredUvsOf(drawable.id, mesh.uvs, binding, layerView) ?: continue
			surfaceUvsById[drawable.id] = recovered
		}
		if (layerView == null) {
			recoveredById.clear()
		} else {
			recoveredById.keys.retainAll(surfaceUvsById.keys)
		}
		return surfaceUvsById
	}

	/**
	 * The shown drawables' display-space gizmo geometry (see [uvGizmoGeometries]), each mesh's the previous
	 * instance when its indices and its mapping are the ones it was built from and the surface kept its size.
	 *
	 * @param List<Drawable> shownDrawables The drawables drawn over the shown surface.
	 * @param Map<DrawableId, FloatArray> uvsById Each drawable's mapping in the shown surface's frame.
	 * @param Int displayWidth The shown surface's width in texels.
	 * @param Int displayHeight The shown surface's height in texels.
	 * @return List<GizmoMeshGeometry> One geometry per drawable with a mapping, in shown order.
	 */
	fun geometries(
		shownDrawables: List<Drawable>,
		uvsById: Map<DrawableId, FloatArray>,
		displayWidth: Int,
		displayHeight: Int,
	): List<GizmoMeshGeometry> {
		val geometries = ArrayList<GizmoMeshGeometry>(shownDrawables.size)
		for (drawable in shownDrawables) {
			val mesh = drawable.mesh ?: continue
			val uvs = uvsById[drawable.id] ?: continue
			val edges = edgesOf(drawable.id, mesh.indices)
			val positions = displayPositionsOf(drawable.id, uvs, displayWidth, displayHeight)
			val cached = geometryById[drawable.id]
			if (cached != null && cached.indices === mesh.indices && cached.edges === edges && cached.positions === positions) {
				geometries.add(cached)
				continue
			}
			val geometry = GizmoMeshGeometry(drawable.id, mesh.indices, edges, positions)
			geometryById[drawable.id] = geometry
			geometries.add(geometry)
		}
		val shownIds = geometries.mapTo(HashSet(geometries.size)) { geometry -> geometry.drawableId }
		geometryById.keys.retainAll(shownIds)
		displayById.keys.retainAll(shownIds)
		edgesById.keys.retainAll(shownIds)
		return geometries
	}

	/**
	 * A mesh's coordinates in its layer's frame, kept while its stored coordinates, its binding, and the
	 * layer's size are the ones they were recovered from.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @param FloatArray storedUvs Its stored coordinates.
	 * @param DrawableLayerBinding binding Its binding.
	 * @param UvEditorLayer layerView The shown layer.
	 * @return FloatArray? The recovered coordinates, or null when the mapping cannot be formed.
	 */
	private fun recoveredUvsOf(drawableId: DrawableId, storedUvs: FloatArray, binding: DrawableLayerBinding, layerView: UvEditorLayer): FloatArray? {
		val cached = recoveredById[drawableId]
		if (cached != null &&
			cached.storedUvs === storedUvs &&
			cached.binding == binding &&
			cached.layerWidth == layerView.width &&
			cached.layerHeight == layerView.height
		) {
			return cached.recovered
		}
		val recovered = layerUvsFromAtlasUvs(storedUvs, binding, layerView.width, layerView.height)
		recoveredById[drawableId] = RecoveredUvs(storedUvs, binding, layerView.width, layerView.height, recovered)
		return recovered
	}

	/**
	 * A mesh's display positions, kept while its surface coordinates and the surface's size are the ones
	 * they were mapped from.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @param FloatArray surfaceUvs Its coordinates in the shown surface's frame.
	 * @param Int displayWidth The surface width in texels.
	 * @param Int displayHeight The surface height in texels.
	 * @return FloatArray The display positions.
	 */
	private fun displayPositionsOf(drawableId: DrawableId, surfaceUvs: FloatArray, displayWidth: Int, displayHeight: Int): FloatArray {
		val cached = displayById[drawableId]
		if (cached != null && cached.surfaceUvs === surfaceUvs && cached.displayWidth == displayWidth && cached.displayHeight == displayHeight) {
			return cached.positions
		}
		val positions = uvToDisplay(surfaceUvs, displayWidth, displayHeight)
		displayById[drawableId] = DisplayPositions(surfaceUvs, displayWidth, displayHeight, positions)
		return positions
	}

	/**
	 * A mesh's unique edges, kept while its index array is the one they were walked from.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @param IntArray indices Its triangle indices.
	 * @return List<MeshElement.Edge> The edges.
	 */
	private fun edgesOf(drawableId: DrawableId, indices: IntArray): List<MeshElement.Edge> {
		val cached = edgesById[drawableId]
		if (cached != null && cached.indices === indices) {
			return cached.edges
		}
		val edges = MeshTopology.uniqueEdges(indices)
		edgesById[drawableId] = Edges(indices, edges)
		return edges
	}
}