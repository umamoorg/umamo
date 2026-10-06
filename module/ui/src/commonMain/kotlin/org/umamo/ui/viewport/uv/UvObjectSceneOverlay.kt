package org.umamo.ui.viewport.uv

import org.umamo.edit.MeshElement
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.format.art.LayerBounds
import org.umamo.render.DecodedImage
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.IslandEdgeRole
import org.umamo.render.puppet.IslandFillRole
import org.umamo.render.puppet.IslandStyle
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.PlacementCropQuad
import org.umamo.render.puppet.PlacementPreview
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.UvSceneContent

/*
 * A UV area's Object-mode scene as the renderer draws it: every shown island in the Blender object-overlay
 * style (an islands overlay over the islands' display positions, back to front), and over a page the
 * placement drag's preview under them.  Derived off the UI thread with the rest of the area's scene
 * (UvSceneOverlay.kt); nothing here draws.
 */

/** The flags of an island: an island is selected whole, so none of its elements is ever flagged. */
private val NO_FLAGS = ByteArray(0)

/** The sample affine of a crop whose tile the model no longer has: no layer texture can stand in for it. */
private val IDENTITY_SAMPLE = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

/**
 * Each drawable's atlas tile, for the per-island warning and pin roles.
 *
 * @param PuppetModel model The model.
 * @return Map<DrawableId, AtlasTileId> The tile of every drawable bound to one.
 */
internal fun tileIdsByDrawable(model: PuppetModel): Map<DrawableId, AtlasTileId> =
	model.drawables.mapNotNull { drawable -> drawable.atlasTileId?.let { tileId -> drawable.id to tileId } }.toMap()

/**
 * The pinned tiles, whose islands outline in the pinned color.
 *
 * @param PuppetModel model The model.
 * @return Set<AtlasTileId> The pinned tiles.
 */
internal fun pinnedTileIdsOf(model: PuppetModel): Set<AtlasTileId> = model.atlas.tiles.filter { tile -> tile.pinned }.mapTo(HashSet()) { tile -> tile.id }

/**
 * One Object-mode derive's output.
 *
 * @property DirectMeshOverlay? islands The islands overlay, or null when no island is shown.
 * @property PlacementPreview? placement The placement preview, or null for none.
 */
internal class UvObjectScene(
	val islands: DirectMeshOverlay?,
	val placement: PlacementPreview?,
)

/**
 * The island's colors: a selected or active island fills selected (the active fill color belongs to a face
 * dot, never a fill, so the active island fills as selected and shows in its outline); its outline is the
 * warning color while its tile is in a placement collision, else the pinned color for a
 * pinned tile, else active, selected, or idle.  A warning is transient and a pin is state, so the warning
 * wins.
 *
 * @param DrawableId drawableId The island.
 * @param Set<DrawableId> selectedIds The selected drawables.
 * @param DrawableId? activeId The active drawable.
 * @param AtlasTileId? tileId The island's tile, or null.
 * @param Set<AtlasTileId> collidingTileIds Every tile in a live placement collision.
 * @param Set<AtlasTileId> pinnedTileIds The pinned tiles.
 * @return IslandStyle The style.
 */
internal fun islandStyleOf(
	drawableId: DrawableId,
	selectedIds: Set<DrawableId>,
	activeId: DrawableId?,
	tileId: AtlasTileId?,
	collidingTileIds: Set<AtlasTileId>,
	pinnedTileIds: Set<AtlasTileId>,
): IslandStyle {
	val active = drawableId == activeId
	val selected = active || drawableId in selectedIds
	val edge =
		when {
			tileId != null && tileId in collidingTileIds -> IslandEdgeRole.Warning
			tileId != null && tileId in pinnedTileIds -> IslandEdgeRole.Pinned
			active -> IslandEdgeRole.Active
			selected -> IslandEdgeRole.Selected
			else -> IslandEdgeRole.Idle
		}
	return IslandStyle(if (selected) IslandFillRole.Selected else IslandFillRole.Idle, edge)
}

/**
 * Turns the object selection, a UV area's shown scene, and its placement gesture into the renderer's
 * islands overlay and placement preview, keeping from one derive to the next everything that did not change,
 * so the renderer uploads only what moved: an island's edge list lives as long as its edges (by identity),
 * its overlay entry as long as its edges and its style, the positions pass through by identity (a mover's
 * preview positions during a drag, the shown geometry's otherwise), and each whole value is the previous
 * instance while all of its parts are.  A selection change therefore re-styles islands and uploads nothing.
 *
 * Not thread-safe: one sequential derive owns it.
 */
internal class UvObjectOverlayProducer {
	/**
	 * One island's edge list as the overlay ships it.
	 *
	 * @property List<MeshElement.Edge> edges The edges it was built from (compared by identity).
	 * @property IntArray endpoints Two endpoints per edge.
	 */
	private class Endpoints(
		val edges: List<MeshElement.Edge>,
		val endpoints: IntArray,
	)

	private val endpointsById = HashMap<DrawableId, Endpoints>()
	private val meshById = HashMap<DrawableId, MeshOverlayMesh>()
	private var lastIslands: DirectMeshOverlay? = null
	private var lastModel: PuppetModel? = null
	private var lastTileByDrawable: Map<DrawableId, AtlasTileId> = emptyMap()
	private var lastPinnedTileIds: Set<AtlasTileId> = emptySet()
	private var lastPreview: PlacementPreview? = null
	private var lastPreviewDrag: PlacementDragView? = null
	private var lastPreviewGhost: PlacementGhost? = null
	private var lastPreviewScene: UvShownScene? = null

	/**
	 * The scene for one state.  The placement preview is drawn over a page only, and its ghost only while
	 * the ghost's atlas is still the committed one.
	 *
	 * @param Selection selection The object selection.
	 * @param UvShownScene scene What the area shows.
	 * @param UvPlacementScene placement The placement gesture's share of the scene.
	 * @param PuppetAtlas committedAtlas The session's committed atlas.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @return UvObjectScene The islands and the preview.
	 */
	fun produce(selection: Selection, scene: UvShownScene, placement: UvPlacementScene, committedAtlas: PuppetAtlas, sizes: MeshOverlaySizes): UvObjectScene {
		val drag = placement.drag
		val islands = islandsOf(selection, scene, drag, sizes)
		val preview =
			if (scene.content is UvSceneContent.AtlasPage) {
				previewOf(scene, drag, activePlacementGhost(placement.ghost, committedAtlas))
			} else {
				null
			}
		return UvObjectScene(islands, preview)
	}

	/**
	 * The islands overlay: every shown island back to front by the rest front rank (ties keep the shown
	 * order), each in its style, a moving island at its preview positions.
	 *
	 * @param Selection selection The object selection.
	 * @param UvShownScene scene What the area shows.
	 * @param PlacementDragView? drag The drag in flight, or null.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @return DirectMeshOverlay? The overlay, or null when no island is shown.
	 */
	private fun islandsOf(selection: Selection, scene: UvShownScene, drag: PlacementDragView?, sizes: MeshOverlaySizes): DirectMeshOverlay? {
		if (scene.geometries.isEmpty()) {
			endpointsById.clear()
			meshById.clear()
			lastIslands = null
			return null
		}
		if (scene.model !== lastModel) {
			lastModel = scene.model
			lastTileByDrawable = tileIdsByDrawable(scene.model)
			lastPinnedTileIds = pinnedTileIdsOf(scene.model)
		}
		val selectedIds = selection.targets.mapNotNullTo(HashSet()) { target -> (target as? SelectionTarget.Drawable)?.id }
		val activeId = (selection.active as? SelectionTarget.Drawable)?.id
		val collidingTileIds = drag?.result?.overlappingTileIds ?: emptySet()
		val ordered = scene.geometries.sortedBy { geometry -> scene.frontRankById[geometry.drawableId] ?: 0f }
		val meshes = ArrayList<MeshOverlayMesh>(ordered.size)
		val positionsById = HashMap<DrawableId, FloatArray>(ordered.size)
		val indicesById = HashMap<DrawableId, IntArray>(ordered.size)
		for (geometry in ordered) {
			val drawableId = geometry.drawableId
			val style = islandStyleOf(drawableId, selectedIds, activeId, lastTileByDrawable[drawableId], collidingTileIds, lastPinnedTileIds)
			meshes.add(meshOf(drawableId, endpointsOf(drawableId, geometry.edges), geometry.positions.size / 2, style))
			positionsById[drawableId] = drag?.previewPositionsById?.get(drawableId) ?: geometry.positions
			indicesById[drawableId] = geometry.indices
		}
		val shownIds = positionsById.keys
		endpointsById.keys.retainAll(shownIds)
		meshById.keys.retainAll(shownIds)
		val previous = lastIslands
		if (previous != null && previous.overlay.sizes == sizes && sameIslands(previous, meshes, positionsById, indicesById)) {
			return previous
		}
		val islands = DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Islands, MeshOverlaySelectMode.Vertex, meshes, sizes), positionsById, indicesById)
		lastIslands = islands
		return islands
	}

	/**
	 * The placement preview: each mover's old trim under the scrim and its crop at the drive's placement,
	 * then the ghost's crops at their committed placements; the previous instance while the drag, the ghost,
	 * and the scene are the ones it was built from.
	 *
	 * @param UvShownScene scene What the area shows.
	 * @param PlacementDragView? drag The drag in flight, or null.
	 * @param PlacementGhost? ghost The ghost still standing, or null.
	 * @return PlacementPreview? The preview, or null with neither a drag nor a ghost.
	 */
	private fun previewOf(scene: UvShownScene, drag: PlacementDragView?, ghost: PlacementGhost?): PlacementPreview? {
		if (drag == null && ghost == null) {
			lastPreview = null
			return null
		}
		val previous = lastPreview
		if (previous != null && drag === lastPreviewDrag && ghost === lastPreviewGhost && scene.scrimColor == lastPreviewScene?.scrimColor && scene.model === lastPreviewScene?.model) {
			return previous
		}
		val scrimQuads = ArrayList<FloatArray>()
		val crops = ArrayList<PlacementCropQuad>()
		if (drag != null) {
			val pageHeight = drag.gesture.pageHeight
			for (mover in drag.gesture.movers) {
				scrimQuads.add(trimQuadToDisplay(mover.placement, mover.trim, pageHeight))
			}
			for (mover in drag.gesture.movers) {
				val placed = drag.result.placementByTile[mover.tileId] ?: continue
				crops.add(cropQuadOf(scene.model, mover.tileId, mover.crop, mover.trim, placed, pageHeight))
			}
		}
		val ghostCrops = ghost?.crops?.map { ghostCrop -> cropQuadOf(scene.model, ghostCrop.tileId, ghostCrop.crop, ghostCrop.trim, ghostCrop.placement, ghost.pageHeight) } ?: emptyList()
		val preview = PlacementPreview(scene.scrimColor, scrimQuads, crops, ghost?.atlas, ghostCrops)
		lastPreview = preview
		lastPreviewDrag = drag
		lastPreviewGhost = ghost
		lastPreviewScene = scene
		return preview
	}

	/**
	 * One crop's quad at [placement], sampling the tile's layer texture through the trim's place in it.
	 *
	 * @param PuppetModel model The model, for the tile's size.
	 * @param AtlasTileId tileId The tile.
	 * @param DecodedImage? crop The trim's pixels, or null.
	 * @param LayerBounds trim The trim, raster-local.
	 * @param AtlasPlacement placement Where the quad shows the tile.
	 * @param Int pageHeight The page height, the display flip's line.
	 * @return PlacementCropQuad The quad.
	 */
	private fun cropQuadOf(
		model: PuppetModel,
		tileId: AtlasTileId,
		crop: DecodedImage?,
		trim: LayerBounds,
		placement: AtlasPlacement,
		pageHeight: Int,
	): PlacementCropQuad {
		val tile = model.atlas.tileById[tileId]
		val sampleAffine = if (tile != null) trimSampleAffine(trim, tile.width, tile.height) else IDENTITY_SAMPLE
		return PlacementCropQuad(tileId.raw, crop, trimQuadToDisplay(placement, trim, pageHeight), sampleAffine)
	}

	/**
	 * An island's edge list as endpoints, kept while its edge list is the one it was built from.
	 *
	 * @param DrawableId drawableId The island.
	 * @param List<MeshElement.Edge> edges Its unique edges.
	 * @return IntArray Two endpoints per edge.
	 */
	private fun endpointsOf(drawableId: DrawableId, edges: List<MeshElement.Edge>): IntArray {
		val cached = endpointsById[drawableId]
		if (cached != null && cached.edges === edges) {
			return cached.endpoints
		}
		val endpoints = IntArray(edges.size * 2)
		for ((edgeIndex, edge) in edges.withIndex()) {
			endpoints[edgeIndex * 2] = edge.endpointLow
			endpoints[edgeIndex * 2 + 1] = edge.endpointHigh
		}
		endpointsById[drawableId] = Endpoints(edges, endpoints)
		return endpoints
	}

	/**
	 * An island's overlay entry, kept while its edge list, vertex count, and style are the ones it was built
	 * with.
	 *
	 * @param DrawableId drawableId The island.
	 * @param IntArray endpoints Its edge endpoints.
	 * @param Int vertexCount Its vertex count.
	 * @param IslandStyle style Its style.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun meshOf(drawableId: DrawableId, endpoints: IntArray, vertexCount: Int, style: IslandStyle): MeshOverlayMesh {
		val cached = meshById[drawableId]
		if (cached != null && cached.edgeEndpoints === endpoints && cached.vertexCount == vertexCount && cached.islandStyle == style) {
			return cached
		}
		val mesh = MeshOverlayMesh(drawableId, vertexCount, endpoints, NO_FLAGS, NO_FLAGS, NO_FLAGS, null, null, null, style)
		meshById[drawableId] = mesh
		return mesh
	}

	/**
	 * Whether the previous islands value shows exactly these entries over exactly these arrays, in order.
	 *
	 * @param DirectMeshOverlay previous The previous value.
	 * @param List<MeshOverlayMesh> meshes This derive's entries, in order.
	 * @param Map<DrawableId, FloatArray> positionsById This derive's positions.
	 * @param Map<DrawableId, IntArray> indicesById This derive's indices.
	 * @return Boolean True when every part is the same instance.
	 */
	private fun sameIslands(
		previous: DirectMeshOverlay,
		meshes: List<MeshOverlayMesh>,
		positionsById: Map<DrawableId, FloatArray>,
		indicesById: Map<DrawableId, IntArray>,
	): Boolean {
		val previousMeshes = previous.overlay.meshes
		if (previousMeshes.size != meshes.size) {
			return false
		}
		for (meshIndex in meshes.indices) {
			val mesh = meshes[meshIndex]
			if (previousMeshes[meshIndex] !== mesh ||
				previous.positionsById[mesh.drawableId] !== positionsById[mesh.drawableId] ||
				previous.triangleIndicesById[mesh.drawableId] !== indicesById[mesh.drawableId]
			) {
				return false
			}
		}
		return true
	}
}