package org.umamo.ui.viewport.uv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.render.ContentBounds
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.UvSceneContent
import org.umamo.ui.viewport.gizmo.EditMeshOverlayProducer
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/*
 * A UV area's scene as the render service draws it: the shown surface, the extent a fit takes in beside
 * it, and, in Edit mode, the session meshes' overlay over the surface, published together as the area's
 * UvSceneContent so a surface and the overlay drawn over it reach the render thread as one value.  The
 * renderer draws the overlay from the meshes' display positions (UvDisplayMapping.kt), which this hands
 * over by identity, so a drive uploads only the meshes it moved.  Nothing here draws.
 */

/**
 * What a UV area shows, taken as one value so a derive never pairs one surface with another's meshes.
 *
 * @property UvSceneContent content The shown surface, carrying no overlay.
 * @property ContentBounds? islandExtent The shown meshes' display-space bounds, or null for none.
 * @property PuppetModel model The model the geometries were projected from: the live preview while a
 *   gesture runs, so every area follows the drag.
 * @property List<GizmoMeshGeometry> geometries The shown meshes in display space.
 */
internal class UvShownScene(
	val content: UvSceneContent,
	val islandExtent: ContentBounds?,
	val model: PuppetModel,
	val geometries: List<GizmoMeshGeometry>,
)

/**
 * Publishes a UV area's scene to [service] for as long as it runs: the shown surface and its extent, with
 * the Edit overlay of the shown session meshes laid on the surface while the session edits, derived from
 * the mode, the area's live circle stroke or else the committed mesh selection, the shown scene, and the
 * sizes.  It publishes whenever the content (the overlay by identity) or the extent changes, so a Grab's
 * confirm, which commits what the preview already showed, publishes nothing.
 *
 * The circle stroke is the area's own rather than the session's preview selection: the stroke a UV area
 * paints is drawn over that area alone.
 *
 * The derive runs on [deriveDispatcher], one input at a time with the latest winning, and the publish
 * lands back on the caller's dispatcher.
 *
 * @param PuppetViewportService service The render service to publish to.
 * @param String areaId The UV area the scene is for.
 * @param EditorSession session The session to derive from.
 * @param Flow<UvShownScene> shownScene What the area shows, re-emitted as it changes.
 * @param Flow<MeshSelection?> circleStroke The area's live circle stroke, or null while none is in flight.
 * @param Flow<MeshOverlaySizes> sizes The overlay sizes, re-emitted when the density changes.
 * @param CoroutineDispatcher deriveDispatcher Where the derive runs.
 */
internal suspend fun publishUvScene(
	service: PuppetViewportService,
	areaId: String,
	session: EditorSession,
	shownScene: Flow<UvShownScene>,
	circleStroke: Flow<MeshSelection?>,
	sizes: Flow<MeshOverlaySizes>,
	deriveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
	val producer = UvEditOverlayProducer()
	combine(session.mode, session.meshSelection, circleStroke, shownScene, sizes) { mode, committed, stroke, scene, overlaySizes ->
		UvSceneInputs(mode, stroke ?: committed, scene, overlaySizes)
	}
		.conflate()
		.map { inputs ->
			val overlay = producer.produce(inputs.mode, inputs.selection, inputs.scene, inputs.sizes)
			UvScenePublish(withOverlay(inputs.scene.content, overlay), inputs.scene.islandExtent)
		}
		.flowOn(deriveDispatcher)
		.conflate()
		.distinctUntilChanged { previous, next -> previous.content == next.content && previous.islandExtent == next.islandExtent }
		.collect { publish -> service.setUvSceneContent(areaId, publish.content, publish.islandExtent) }
}

/**
 * One derive's inputs, taken together so the derive never pairs one emission's selection with another's
 * scene by accident of timing.
 *
 * @property EditorMode mode The editor mode.
 * @property MeshSelection selection The selection to show: the area's circle stroke while one is live.
 * @property UvShownScene scene What the area shows.
 * @property MeshOverlaySizes sizes The overlay sizes.
 */
private class UvSceneInputs(
	val mode: EditorMode,
	val selection: MeshSelection,
	val scene: UvShownScene,
	val sizes: MeshOverlaySizes,
)

/**
 * One publish: the content with its overlay and the extent beside it.
 *
 * @property UvSceneContent content The surface with the overlay laid on it.
 * @property ContentBounds? islandExtent The shown meshes' display-space bounds, or null for none.
 */
private class UvScenePublish(
	val content: UvSceneContent,
	val islandExtent: ContentBounds?,
)

/**
 * The content with [overlay] laid on it, whatever surface it shows.
 *
 * @param UvSceneContent content The surface.
 * @param DirectMeshOverlay? overlay The overlay, or null for none.
 * @return UvSceneContent The content carrying the overlay.
 */
private fun withOverlay(content: UvSceneContent, overlay: DirectMeshOverlay?): UvSceneContent =
	when (content) {
		is UvSceneContent.AtlasPage -> content.copy(overlay = overlay)
		is UvSceneContent.SourceLayer -> content.copy(overlay = overlay)
	}

/**
 * Turns the session's state over one UV area's shown scene into the renderer's [DirectMeshOverlay]: the
 * Edit overlay of the session meshes the area shows (the shared [EditMeshOverlayProducer], narrowed to the
 * shown ones), each paired with its display positions and triangle indices exactly as the shown geometry
 * holds them.  The value is the previous instance while the overlay and every paired array are the same
 * instances, and the arrays pass through by identity, so the renderer re-uploads only what moved.
 *
 * Not thread-safe: one sequential derive owns it.
 */
internal class UvEditOverlayProducer {
	private val meshes = EditMeshOverlayProducer()
	private var lastDirect: DirectMeshOverlay? = null

	/**
	 * The overlay for one state: null outside Edit mode or when the scene shows none of the session's
	 * meshes.
	 *
	 * @param EditorMode mode The editor mode.
	 * @param MeshSelection selection The selection to show.
	 * @param UvShownScene scene What the area shows.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @return DirectMeshOverlay? The overlay, or null for none.
	 */
	fun produce(mode: EditorMode, selection: MeshSelection, scene: UvShownScene, sizes: MeshOverlaySizes): DirectMeshOverlay? {
		if (mode != EditorMode.Edit) {
			lastDirect = null
			return null
		}
		val geometryById = HashMap<DrawableId, GizmoMeshGeometry>(scene.geometries.size)
		for (geometry in scene.geometries) {
			geometryById[geometry.drawableId] = geometry
		}
		val overlay = meshes.produce(mode, selection, scene.model, sizes, geometryById.keys)
		if (overlay == null) {
			lastDirect = null
			return null
		}
		val previous = lastDirect
		if (previous != null && previous.overlay === overlay && pairsUnchanged(previous, geometryById)) {
			return previous
		}
		val positionsById = HashMap<DrawableId, FloatArray>(overlay.meshes.size)
		val triangleIndicesById = HashMap<DrawableId, IntArray>(overlay.meshes.size)
		for (mesh in overlay.meshes) {
			val geometry = geometryById[mesh.drawableId] ?: continue
			positionsById[mesh.drawableId] = geometry.positions
			triangleIndicesById[mesh.drawableId] = geometry.indices
		}
		val direct = DirectMeshOverlay(overlay, positionsById, triangleIndicesById)
		lastDirect = direct
		return direct
	}

	/**
	 * Whether every mesh of the previous value still pairs with the same position and index arrays.
	 *
	 * @param DirectMeshOverlay previous The previous value.
	 * @param Map<DrawableId, GizmoMeshGeometry> geometryById The shown geometry by drawable.
	 * @return Boolean True when every pair is the same instances.
	 */
	private fun pairsUnchanged(previous: DirectMeshOverlay, geometryById: Map<DrawableId, GizmoMeshGeometry>): Boolean =
		previous.overlay.meshes.all { mesh ->
			val geometry = geometryById[mesh.drawableId]
			geometry != null &&
				previous.positionsById[mesh.drawableId] === geometry.positions &&
				previous.triangleIndicesById[mesh.drawableId] === geometry.indices
		}
}