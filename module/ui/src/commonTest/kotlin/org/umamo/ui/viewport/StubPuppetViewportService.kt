package org.umamo.ui.viewport

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.umamo.format.raster.RasterImage
import org.umamo.render.ContentBounds
import org.umamo.render.FrameBackdrop
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.DrawableThumbnailProvider

/**
 * A render service with no renderer behind it, for tests of the viewport overlays: it records every model
 * an overlay pushes through [setModel] (a gesture's previews and the resync that ends it), every mesh
 * overlay, palette, and UV scene content published to it, and answers the picks from a table the test
 * fills.  Everything else does nothing, since no overlay under test reads it.
 */
internal class StubPuppetViewportService : PuppetViewportService {
	/** Every model pushed through [setModel], oldest first. */
	val pushedModels = ArrayList<PuppetModel>()

	/** Every mesh overlay published through [setMeshOverlay], oldest first, nulls included. */
	val pushedOverlays = ArrayList<MeshOverlay?>()

	/** Every palette published through [setMeshOverlayPalette], oldest first. */
	val pushedPalettes = ArrayList<MeshOverlayPalette>()

	/** Every UV scene content published through [setUvSceneContent], in order. */
	val pushedUvContents = ArrayList<UvContentPush>()

	/** Every per-area render option push through [setAreaOverlays], in order, as the area and its options. */
	val pushedAreaOverlays = ArrayList<Pair<String, AreaOverlays>>()

	/** Every capture asked through [renderImage], in order, as its backdrop and the overlays it was to draw. */
	val renderImageRequests = ArrayList<Pair<FrameBackdrop, AreaOverlays>>()

	/** What [pickAllAt] answers per area, front-most first; an area with no entry answers nothing. */
	val stackByArea = HashMap<String, List<PickCandidate>>()

	/** Every pick-stack request, as the area and the pointer it asked about. */
	val stackRequests = ArrayList<Pair<String, Pair<Float, Float>>>()

	/** What [drawableWorldCentroids] answers: each drawable's world centroid, set by the case. */
	val centroids = LinkedHashMap<DrawableId, FloatArray>()

	/** How many times an overlay asked for the centroids (one snapshot per select gesture). */
	var centroidSnapshots = 0
		private set

	/** Every Zoom Region request, as the area and its left, top, right, and bottom edges in pixels. */
	val zoomRegionRequests = ArrayList<Pair<String, List<Float>>>()

	override var zoomStepPercent: Float = 0f

	override var zoomStepCoarsePercent: Float = 0f

	override var supersampleEnabled: Boolean = false

	override var supersampleWhileResizing: Boolean = false

	override var gridColors: GridColors = GridColors.Classic

	/**
	 * Registers nothing; no frame ever lands.
	 *
	 * @param String areaId The area.
	 * @return StateFlow A flow that stays null.
	 */
	override fun register(areaId: String): StateFlow<RenderedFrame?> = MutableStateFlow(null)

	/**
	 * Registers nothing; no frame ever lands.
	 *
	 * @param String areaId The area.
	 * @param UvSceneContent content The scene.
	 * @param ContentBounds? islandExtent The island extent.
	 * @return StateFlow A flow that stays null.
	 */
	override fun registerUvScene(areaId: String, content: UvSceneContent, islandExtent: ContentBounds?): StateFlow<RenderedFrame?> = MutableStateFlow(null)

	/**
	 * Records the content a UV area publishes.
	 *
	 * @param String areaId The area.
	 * @param UvSceneContent content The scene.
	 * @param ContentBounds? islandExtent The island extent.
	 */
	override fun setUvSceneContent(areaId: String, content: UvSceneContent, islandExtent: ContentBounds?) {
		pushedUvContents.add(UvContentPush(areaId, content, islandExtent))
	}

	/**
	 * Records the render options an area publishes.
	 *
	 * @param String areaId The area.
	 * @param AreaOverlays overlays The options.
	 */
	override fun setAreaOverlays(areaId: String, overlays: AreaOverlays) {
		pushedAreaOverlays.add(areaId to overlays)
	}

	/**
	 * The render options an area last published.
	 *
	 * @param String areaId The area.
	 * @return AreaOverlays? The area's last push, or null.
	 */
	override fun areaOverlays(areaId: String): AreaOverlays? = pushedAreaOverlays.lastOrNull { (id, _) -> id == areaId }?.second

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 */
	override fun unregister(areaId: String) {}

	/**
	 * No camera, ever.
	 *
	 * @param String areaId The area.
	 * @return StateFlow A flow that stays null.
	 */
	override fun cameraFlow(areaId: String): StateFlow<ViewportCamera?> = MutableStateFlow(null)

	/**
	 * No cameras.
	 *
	 * @return Map An empty map.
	 */
	override fun cameras(): Map<AreaCameraKey, ViewportCamera> = emptyMap()

	/**
	 * Does nothing.
	 *
	 * @param Map cameras The cameras.
	 */
	override fun seedCameras(cameras: Map<AreaCameraKey, ViewportCamera>) {}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 * @param Int width The width.
	 * @param Int height The height.
	 */
	override fun resize(areaId: String, width: Int, height: Int) {}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 * @param Float deltaXpx The x delta.
	 * @param Float deltaYpx The y delta.
	 */
	override fun pan(areaId: String, deltaXpx: Float, deltaYpx: Float) {}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 * @param Boolean zoomIn The direction.
	 * @param Boolean coarse The step size.
	 * @param Float cursorXpx The cursor x.
	 * @param Float cursorYpx The cursor y.
	 */
	override fun zoomAtCursor(areaId: String, zoomIn: Boolean, coarse: Boolean, cursorXpx: Float, cursorYpx: Float) {}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 * @param Boolean zoomIn The direction.
	 * @param Boolean coarse The step size.
	 */
	override fun zoomCentered(areaId: String, zoomIn: Boolean, coarse: Boolean) {}

	/**
	 * Records the request.
	 *
	 * @param String areaId The area.
	 * @param Float leftPx The left edge.
	 * @param Float topPx The top edge.
	 * @param Float rightPx The right edge.
	 * @param Float bottomPx The bottom edge.
	 */
	override fun zoomToRegion(areaId: String, leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float) {
		zoomRegionRequests.add(areaId to listOf(leftPx, topPx, rightPx, bottomPx))
	}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 */
	override fun actualSize(areaId: String) {}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 */
	override fun fit(areaId: String) {}

	/**
	 * Does nothing.
	 *
	 * @param String areaId The area.
	 * @param Float minX The left edge.
	 * @param Float minZ The bottom edge.
	 * @param Float maxX The right edge.
	 * @param Float maxZ The top edge.
	 */
	override fun fitWorldRect(areaId: String, minX: Float, minZ: Float, maxX: Float, maxZ: Float) {}

	/**
	 * Does nothing.
	 *
	 * @param Set ids The selected drawables.
	 */
	override fun setSelection(ids: Set<DrawableId>) {}

	/**
	 * Does nothing.
	 *
	 * @param DrawableId? id The active drawable.
	 */
	override fun setActiveSelection(id: DrawableId?) {}

	/**
	 * Does nothing.
	 *
	 * @param LayerDrawPlan plan The plan.
	 */
	override fun setSourceLayerPlan(plan: LayerDrawPlan) {}

	/**
	 * Does nothing.
	 *
	 * @param LayerRasterBatch batch The rasters.
	 */
	override fun deliverSourceLayerRasters(batch: LayerRasterBatch) {}

	/**
	 * Does nothing.
	 *
	 * @param Set ids The shown drawables.
	 */
	override fun setShownDrawables(ids: Set<DrawableId>) {}

	/**
	 * Records the pushed model.
	 *
	 * @param PuppetModel model The model an overlay pushed.
	 */
	override fun setModel(model: PuppetModel) {
		pushedModels.add(model)
	}

	/**
	 * Does nothing.
	 *
	 * @param AtlasPageBinding binding The pages.
	 */
	override fun setAtlasPages(binding: AtlasPageBinding) {}

	/**
	 * Does nothing.
	 *
	 * @param Float red The red channel.
	 * @param Float green The green channel.
	 * @param Float blue The blue channel.
	 */
	override fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {}

	/**
	 * Does nothing.
	 *
	 * @param Float red The red channel.
	 * @param Float green The green channel.
	 * @param Float blue The blue channel.
	 */
	override fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) {}

	/**
	 * Records the overlay.
	 *
	 * @param MeshOverlay? overlay The overlay, or null.
	 */
	override fun setMeshOverlay(overlay: MeshOverlay?) {
		pushedOverlays.add(overlay)
	}

	/**
	 * Records the palette.
	 *
	 * @param MeshOverlayPalette palette The palette.
	 */
	override fun setMeshOverlayPalette(palette: MeshOverlayPalette) {
		pushedPalettes.add(palette)
	}

	/**
	 * The front-most entry of the area's stack.
	 *
	 * @param String areaId The area.
	 * @param Float cursorXpx The pointer x.
	 * @param Float cursorYpx The pointer y.
	 * @return DrawableId? The front-most drawable, or null for an empty stack.
	 */
	override fun pickAt(areaId: String, cursorXpx: Float, cursorYpx: Float): DrawableId? = stackByArea[areaId]?.firstOrNull()?.id

	/**
	 * The area's stack, recording the request.
	 *
	 * @param String areaId The area.
	 * @param Float cursorXpx The pointer x.
	 * @param Float cursorYpx The pointer y.
	 * @return List The area's stack, or nothing.
	 */
	override fun pickAllAt(areaId: String, cursorXpx: Float, cursorYpx: Float): List<PickCandidate> {
		stackRequests.add(areaId to (cursorXpx to cursorYpx))
		return stackByArea[areaId].orEmpty()
	}

	/**
	 * A copy of [centroids] as it is now, counting the snapshot: a copy, so a case that changes the map after
	 * a press can show the gesture kept the snapshot it took.
	 *
	 * @return Map Each drawable's world centroid.
	 */
	override fun drawableWorldCentroids(): Map<DrawableId, FloatArray> {
		centroidSnapshots++
		return centroids.toMap()
	}

	/**
	 * Records what was asked and renders nothing.
	 *
	 * @param ImageFrame frame The frame.
	 * @param FrameBackdrop backdrop The backdrop.
	 * @param AreaOverlays overlays The grid and axes a grid backdrop draws.
	 * @return RasterImage? Always null.
	 */
	override suspend fun renderImage(frame: ImageFrame, backdrop: FrameBackdrop, overlays: AreaOverlays): RasterImage? {
		renderImageRequests.add(backdrop to overlays)
		return null
	}

	/**
	 * No view.
	 *
	 * @param String areaId The area.
	 * @return ImageFrame? Always null.
	 */
	override fun areaView(areaId: String): ImageFrame? = null

	/**
	 * No bounds.
	 *
	 * @return ContentBounds? Always null.
	 */
	override fun visibleContentBounds(): ContentBounds? = null

	/**
	 * No thumbnail.
	 *
	 * @param DrawableId id The drawable.
	 * @return ImageBitmap? Always null.
	 */
	override fun thumbnailFor(id: DrawableId): ImageBitmap? = null

	/**
	 * A provider with no thumbnails.
	 *
	 * @return DrawableThumbnailProvider The provider.
	 */
	override fun thumbnails(): DrawableThumbnailProvider = NoThumbnails

	/**
	 * No part name.
	 *
	 * @param DrawableId id The drawable.
	 * @return String? Always null.
	 */
	override fun partNameFor(id: DrawableId): String? = null

	/**
	 * No drawable name.
	 *
	 * @param DrawableId id The drawable.
	 * @return String? Always null.
	 */
	override fun drawableNameFor(id: DrawableId): String? = null

	/** Does nothing. */
	override fun dispose() {}

	/** A thumbnail provider with nothing to show. */
	private object NoThumbnails : DrawableThumbnailProvider {
		/**
		 * No thumbnail.
		 *
		 * @param DrawableId id The drawable.
		 * @return ImageBitmap? Always null.
		 */
		override fun thumbnailFor(id: DrawableId): ImageBitmap? = null

		/**
		 * No thumbnail.
		 *
		 * @param PartId id The part.
		 * @return ImageBitmap? Always null.
		 */
		override fun partThumbnailFor(id: PartId): ImageBitmap? = null
	}
}

/**
 * One UV scene content publish, as the stub recorded it.
 *
 * @property String areaId The area.
 * @property UvSceneContent content The content, with its overlay.
 * @property ContentBounds? islandExtent The island extent.
 */
internal class UvContentPush(
	val areaId: String,
	val content: UvSceneContent,
	val islandExtent: ContentBounds?,
)