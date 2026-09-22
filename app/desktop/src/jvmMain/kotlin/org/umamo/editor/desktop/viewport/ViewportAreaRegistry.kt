package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.ContentBounds
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.AreaCameraKey
import org.umamo.ui.viewport.CameraSurface
import org.umamo.ui.viewport.RenderedFrame
import org.umamo.ui.viewport.UvSceneContent
import java.util.concurrent.ConcurrentHashMap

/**
 * Which content an area renders: the posed puppet (2D viewport), or the UV editor's flat surface.
 *
 * WHICH flat surface - a packed atlas page or a source layer's artwork - is not recorded here.  That
 * belongs to [AreaSlot.uvContent], which carries the kind together with its payload; splitting it
 * across two fields is what let a switch be observed half applied.
 */
internal enum class RenderScene {
	Puppet2D,
	UvScene,
}

/** The surface a scene's camera frames, which is what a remembered camera is keyed by beside its area. */
internal val RenderScene.cameraSurface: CameraSurface
	get() =
		when (this) {
			RenderScene.Puppet2D -> CameraSurface.Viewport
			RenderScene.UvScene -> CameraSurface.Uv
		}

/**
 * Which flat surface a UV-editor camera frames: one atlas page, or one source layer.  A view of one means
 * nothing over another - a page of thousands of texels and a layer of a few hundred share only their
 * origin - so a UV-editor area keeps a view per surface.
 */
internal sealed interface UvSurfaceId {
	/**
	 * An atlas page, by index.
	 *
	 * @property Int? pageIndex The page, or null for the untextured fallback's unit square.
	 */
	data class Page(val pageIndex: Int?) : UvSurfaceId

	/**
	 * A source layer, by its key in the document's source-art store.
	 *
	 * @property String layerKey The layer's key.
	 */
	data class Layer(val layerKey: String) : UvSurfaceId
}

/** The surface this content shows: its identity, never its pixels, so a re-decoded layer is the same surface. */
internal val UvSceneContent.surfaceId: UvSurfaceId
	get() =
		when (this) {
			is UvSceneContent.AtlasPage -> UvSurfaceId.Page(pageIndex)
			is UvSceneContent.SourceLayer -> UvSurfaceId.Layer(layerKey)
		}

/**
 * A camera together with the UV surface it frames, published as ONE value so the two cannot tear: a pan on the
 * UI thread racing a surface switch on the render thread can lose one update at worst, never file a view under
 * the wrong surface.
 *
 * @property ViewportCamera camera  The pan / zoom.
 * @property UvSurfaceId?   surface The UV surface it frames, or null for a puppet area's view of the world.
 */
internal data class FramedCamera(val camera: ViewportCamera, val surface: UvSurfaceId?)

/**
 * What a UV-editor view is remembered under for the session: one area showing one surface.
 *
 * @property String      areaId  The hosting leaf's area id.
 * @property UvSurfaceId surface The page or layer the view frames.
 */
internal data class AreaUvSurfaceKey(val areaId: String, val surface: UvSurfaceId)

/**
 * The rectangle a UV-editor fit frames: the shown surface, widened to take in every shown mesh that reaches
 * past it.  A placement moved off the page carries its islands with it, and a fit that only knew the page
 * would frame everything but the thing just moved.
 *
 * @param ContentBounds  surfaceBounds The shown surface's rectangle.
 * @param ContentBounds? islandExtent  The shown meshes' bounds, or null for none.
 * @return ContentBounds The union of the two.
 */
internal fun uvFitBounds(surfaceBounds: ContentBounds, islandExtent: ContentBounds?): ContentBounds {
	if (islandExtent == null) {
		return surfaceBounds
	}
	val minX = minOf(surfaceBounds.minX, islandExtent.minX)
	val minY = minOf(surfaceBounds.minY, islandExtent.minY)
	val maxX = maxOf(surfaceBounds.minX + surfaceBounds.width, islandExtent.minX + islandExtent.width)
	val maxY = maxOf(surfaceBounds.minY + surfaceBounds.height, islandExtent.minY + islandExtent.height)
	return ContentBounds(minX, minY, maxX - minX, maxY - minY)
}

/** The camera and pixel size of a registered area, resolved for a CPU pick. Null-camera areas do not appear. */
internal data class AreaView(val camera: ViewportCamera, val width: Int, val height: Int)

/**
 * Per-area render state, shared between the UI thread ([ViewportAreaRegistry]) and the render thread
 * ([OffscreenRenderEngine]). Field-ownership contract:
 *
 *   - The @Volatile fields are written by the UI thread and read by the render thread (a volatile publish of
 *     immutable values or plain scalars): scene, uvContent, uvIslandExtent, width, height, refitRequested.
 *     framing is written by both: the UI thread's pan / zoom and the render thread's establish, each
 *     replacing the whole value.
 *   - imageState / cameraState are thread-safe StateFlows; either thread may set them.
 *   - refCount is touched only on the UI thread (register/unregister run as composition effects).
 *   - The remaining plain fields (inFlight, rendered*, *RenderBumpDone) are render-thread-only bookkeeping.
 */
internal class AreaSlot {
	// Whether this area renders the posed puppet or the UV editor's flat surface.  Set by whichever
	// registration claimed the slot last (register vs registerUvScene): switching an area's editor type in
	// place registers the new space before the old one releases, so a live slot CAN change kind.  WHICH
	// flat surface is carried by uvContent below.  UI thread writes, render thread reads - a volatile publish.
	@Volatile
	var scene: RenderScene = RenderScene.Puppet2D

	// What a UV-editor area draws, as ONE value: which kind and its payload inseparably.
	//
	// Publishing the kind and the payload as separate fields would let the render thread observe a
	// half-applied switch - the old kind against the new payload - which reads as a legitimately stale
	// frame, renders grid-only, and then stamps itself fresh against the payload that landed meanwhile.
	// The area would sit on an empty grid until a pan or resize happened to dislodge it, and nothing
	// would look wrong anywhere. A single reference cannot tear, so the switch is all-or-nothing.
	//
	// Null for a puppet area, which has no UV surface.
	@Volatile
	var uvContent: UvSceneContent? = null

	// The display-space bounds of the meshes a UV-editor area shows, which its fit widens the surface to take
	// in.  Read only when a fit runs, so an edit that moves an island re-renders nothing.  Written BEFORE
	// uvContent, and read after it: a render thread that sees new content also sees the extent measured over it.
	@Volatile
	var uvIslandExtent: ContentBounds? = null

	@Volatile
	var width: Int = 0

	@Volatile
	var height: Int = 0
	val imageState = MutableStateFlow<RenderedFrame?>(null)

	// The per-area camera (pan/zoom) with the UV surface it frames. null until the render thread computes the
	// initial fit (it needs the area size + content bounds); thereafter each pan / zoom swaps in a new immutable
	// value (a volatile publish), and the render thread swaps it again when a UV area's shown surface is no
	// longer the one it frames. cameraState mirrors the camera for the overlay readout.
	@Volatile
	var framing: FramedCamera? = null

	/** The area's current pan / zoom, or null before the first fit. */
	val camera: ViewportCamera?
		get() = framing?.camera

	// Set by the Fit command (UI thread), cleared by the render thread, which then recomputes a fresh fit
	// (ignoring any remembered camera). A flag rather than camera=null so the old view shows until the refit
	// lands, and so refit beats the workspace-restore path.
	@Volatile
	var refitRequested: Boolean = false
	val cameraState = MutableStateFlow<ViewportCamera?>(null)

	// How many live composables currently hold this area id. A leaf can be torn down and rebuilt for the SAME
	// id during a tree collapse (closing a split sibling), so register(new) and unregister(old) briefly
	// overlap; the slot is dropped only when this returns to 0. Touched only on the Compose UI thread.
	var refCount: Int = 0

	// Render-thread-only bookkeeping (no synchronization needed).
	var inFlight: Boolean = false
	var renderedWidth: Int = -1
	var renderedHeight: Int = -1
	var renderedParamsVersion: Long = -1
	var renderedCamera: ViewportCamera? = null
	var puppetRenderBumpDone: Long = -1
	var atlasRenderBumpDone: Long = -1
	var renderedUvContent: UvSceneContent? = null

	// Render-thread-only resize-throttle bookkeeping: the last size the loop observed, when it last
	// changed, when the last resize-driven render was issued (all System.nanoTime), and the
	// supersample scale of the last issued render (a 1x interactive frame stays size-stale so the
	// settle pass re-renders it at full quality).
	var observedWidth: Int = 0
	var observedHeight: Int = 0
	var sizeChangedNanos: Long = 0L
	var resizeRenderNanos: Long = 0L
	var renderedScale: Int = -1
}

/**
 * Owns the registered viewport areas and their cameras. The register/resize/camera-navigation surface runs
 * on the UI thread; [OffscreenRenderEngine] iterates the same [areas] map on the render thread and calls
 * [establishCamera] to fit newly-sized areas. The shared state is safe across the two threads: the slot map
 * is a ConcurrentHashMap, per-area hand-off fields are @Volatile, and the camera flows are StateFlows.
 */
internal class ViewportAreaRegistry {
	/** The registered areas, keyed by stable area id. Iterated by the render engine; mutated by the UI thread. */
	val areas = ConcurrentHashMap<String, AreaSlot>()

	// Cameras remembered by area AND surface beyond a slot's composable life, so switching workspaces (which
	// disposes the inactive workspace's viewports -> drops their slots) and returning restores the pan/zoom
	// instead of refitting.  Keyed by the surface too because a view of the puppet's world means nothing over
	// a texture's pixels: an area switched between the 2D viewport and the UV editor keeps one view of each.
	// Area ids are minted once and never reused, so entries never collide; the map grows only by areas ever
	// shown (a few floats each) and is not evicted (session-bounded, negligible cost).  A UV editor's entry is
	// the view of the page or layer it shows now - the one a save writes.
	private val rememberedCameras = ConcurrentHashMap<AreaCameraKey, ViewportCamera>()

	// A UV-editor area's view of EACH page and layer it has shown, so switching to one it has left brings back
	// that view and one it has never shown is fitted: a page's pan and zoom over a layer a fraction of its size
	// lands the layer tiny and in a corner.  Session state only - never in cameras(), so it never reaches a
	// save - and dropped with the engine, so a new document starts over.  Bounded the way rememberedCameras is.
	private val uvSurfaceCameras = ConcurrentHashMap<AreaUvSurfaceKey, ViewportCamera>()

	// The areas that have shown a UV surface this session.  Until an area has, its remembered UV view can only
	// be the one the document was saved with, which belongs to whichever surface the area opens on.
	private val uvShownAreaIds = ConcurrentHashMap.newKeySet<String>()

	/**
	 * Every remembered camera, by area and surface - the areas shown now and the ones a workspace switch put away.
	 *
	 * @return Map A copy of the remembered cameras.
	 */
	fun cameras(): Map<AreaCameraKey, ViewportCamera> = HashMap(rememberedCameras)

	/**
	 * Remembers [cameras] as though each area had been shown with it, so an area registering for the first time
	 * opens on its saved view rather than a fit.  Meant for the moment the engine is built, before any area
	 * registers; an area that already has a camera keeps it.
	 *
	 * @param Map cameras The saved cameras, by area and surface.
	 */
	fun seedCameras(cameras: Map<AreaCameraKey, ViewportCamera>) {
		for ((cameraKey, camera) in cameras) {
			rememberedCameras.putIfAbsent(cameraKey, camera)
		}
	}

	// Zoom increments in percentage points, fed from settings (viewport.zoomStep*Percent): the fine step is
	// one wheel notch / keyboard press, the coarse step is the Shift-held variant. Defaults mirror
	// defaultSettings.json. UI thread only.
	@Volatile
	var zoomStepPercent: Float = 1f

	@Volatile
	var zoomStepCoarsePercent: Float = 5f

	/**
	 * Registers a viewport area and returns the flow of images to display for it. Re-registering an id that
	 * is still live (the same area's leaf rebuilt during a tree collapse - see [AreaSlot.refCount]) returns
	 * the existing slot and its last image, so the surviving viewport keeps rendering instead of being
	 * orphaned.
	 *
	 * @param String areaId The hosting area's stable id.
	 * @return StateFlow The area's image stream (null until the first render completes).
	 */
	fun register(areaId: String): StateFlow<RenderedFrame?> {
		val slot = areas.getOrPut(areaId) { AreaSlot() }
		claim(slot, RenderScene.Puppet2D, content = null, islandExtent = null)
		slot.refCount++
		return slot.imageState
	}

	/**
	 * Registers a UV-editor area (the flat image underlay) and returns its image flow.
	 *
	 * @param String         areaId       The hosting area's stable id.
	 * @param UvSceneContent content      What the area draws.
	 * @param ContentBounds? islandExtent The shown meshes' display-space bounds, or null for none.
	 * @return StateFlow The area's image stream (null until the first render completes).
	 */
	fun registerUvScene(areaId: String, content: UvSceneContent, islandExtent: ContentBounds?): StateFlow<RenderedFrame?> {
		val slot = areas.getOrPut(areaId) { AreaSlot() }
		claim(slot, RenderScene.UvScene, content, islandExtent)
		slot.refCount++
		return slot.imageState
	}

	/**
	 * Makes [slot] an area of [scene], whatever it was.
	 *
	 * A slot outlives the space that registered it whenever the next space registers first - and switching an
	 * area's editor type in place does exactly that, the new space composing before the old one is disposed.  The
	 * slot then holds the OLD kind, its surface, and its camera, so a registration has to claim all three or the
	 * engine keeps drawing the UV page under a 2D viewport's gizmos (and frames the puppet with a page's pan and
	 * zoom).  A registration of the same kind - a leaf rebuilt during a tree collapse - changes nothing here, so
	 * the surviving viewport keeps its view.
	 *
	 * Order matters to the render thread, which walks the slot map and can see this slot mid-claim.  Becoming a
	 * UV scene, the content goes first and the kind second; becoming a puppet, the kind goes first and the
	 * content is cleared second - either way the area never reads as a UV scene with nothing to draw.  The camera
	 * goes LAST: dropped before the kind, the render thread could re-establish the old surface's view in the gap
	 * and keep it.  Dropped after, the worst it can do is render one frame of the new surface through the old
	 * camera, which the fresh camera then makes stale.
	 *
	 * @param AreaSlot        slot         The slot to claim.
	 * @param RenderScene     scene        The kind of area registering.
	 * @param UvSceneContent? content      What a UV area draws; null for a puppet area.
	 * @param ContentBounds?  islandExtent The shown meshes' bounds a UV area's fit takes in; null for a puppet area.
	 */
	private fun claim(slot: AreaSlot, scene: RenderScene, content: UvSceneContent?, islandExtent: ContentBounds?) {
		val kindChanged = slot.scene != scene
		when (scene) {
			RenderScene.UvScene -> {
				slot.uvIslandExtent = islandExtent
				slot.uvContent = content
				slot.scene = scene
			}

			RenderScene.Puppet2D -> {
				slot.scene = scene
				slot.uvContent = null
				slot.uvIslandExtent = null
			}
		}
		if (kindChanged) {
			// The old surface's view stays remembered under its own key for the day the area switches back; this
			// one is re-established from the new surface's remembered view, or a fit.  The last frame goes too:
			// it shows the old surface, and the new space's overlays would be drawn over it until the next lands.
			slot.framing = null
			slot.cameraState.value = null
			slot.imageState.value = null
		}
	}

	/**
	 * Retargets what an already-registered UV-editor area draws, and the mesh extent its fit takes in. A no-op
	 * for an unregistered area or a puppet (2D) area - only a registration moves an area between the puppet and
	 * the UV family, and this moves the content within the UV one.
	 *
	 * A switch to another page or layer changes no camera here: the render thread sees that the surface shown is
	 * no longer the one the camera frames and swaps the view as it establishes the next frame, so the new
	 * surface's first frame already renders through its own view (see [establishCamera]).
	 *
	 * @param String         areaId       The UV-editor area to retarget.
	 * @param UvSceneContent content      The new content to draw.
	 * @param ContentBounds? islandExtent The shown meshes' display-space bounds, or null for none.
	 */
	fun setUvSceneContent(areaId: String, content: UvSceneContent, islandExtent: ContentBounds?) {
		val slot = areas[areaId] ?: return
		if (slot.scene == RenderScene.Puppet2D) {
			return
		}
		applyUvContent(slot, content, islandExtent)
	}

	/**
	 * Publishes one UV content choice onto a slot, kind and payload inseparably, with the mesh extent measured
	 * over it.
	 *
	 * The content is one volatile store of one immutable value, which is what makes the switch atomic - see
	 * [AreaSlot.uvContent] for what publishing them separately would cost.  The extent goes first, so a render
	 * thread that sees the new content also sees the extent that belongs to it.
	 *
	 * @param AreaSlot       slot         The slot to retarget.
	 * @param UvSceneContent content      What the area draws.
	 * @param ContentBounds? islandExtent The shown meshes' display-space bounds, or null for none.
	 */
	private fun applyUvContent(slot: AreaSlot, content: UvSceneContent, islandExtent: ContentBounds?) {
		slot.uvIslandExtent = islandExtent
		slot.uvContent = content
	}

	/**
	 * Releases one hold on an area; the slot is dropped only when the last holder leaves (ref-count to zero).
	 * This guards the tree-collapse case: closing one split sibling rebuilds the surviving area's leaf under a
	 * fresh composition node, so its [register] runs BEFORE the old leaf's unregister - a plain remove here
	 * would delete the slot the rebuilt viewport just bound to, freezing it at a stale (stretched) frame. Any
	 * read-back already in flight for a truly-dropped slot still completes and is discarded when collected.
	 *
	 * @param String areaId The area id to release.
	 */
	fun unregister(areaId: String) {
		val slot = areas[areaId] ?: return
		slot.refCount--
		if (slot.refCount <= 0) {
			areas.remove(areaId)
		}
	}

	/**
	 * Returns the flow of the area's current camera, for the overlay zoom readout. The viewport composable
	 * registers the area before reading this, so it attaches to the existing slot.
	 *
	 * @param String areaId The area id.
	 * @return StateFlow The area's camera stream (null until the first fit).
	 */
	fun cameraFlow(areaId: String): StateFlow<ViewportCamera?> =
		areas.getOrPut(areaId) { AreaSlot() }.cameraState

	/**
	 * Updates an area's requested pixel size; the render thread re-renders it at the new size. A no-op for an
	 * unregistered id - registration always precedes the size report, so resize must never resurrect a slot
	 * the ref-count has already dropped (that would leak a zero-ref slot the render loop renders forever).
	 *
	 * @param String areaId The area id.
	 * @param Int width The new width in pixels.
	 * @param Int height The new height in pixels.
	 */
	fun resize(areaId: String, width: Int, height: Int) {
		val slot = areas[areaId] ?: return
		slot.width = width
		slot.height = height
	}

	/**
	 * Pans the area's camera by a screen-pixel drag (grab style). No-op before the initial fit exists.
	 *
	 * @param String areaId The area id.
	 * @param Float deltaXpx Horizontal drag in pixels.
	 * @param Float deltaYpx Vertical drag in pixels.
	 */
	fun pan(areaId: String, deltaXpx: Float, deltaYpx: Float) {
		updateCamera(areaId) { camera -> camera.panByScreen(deltaXpx, deltaYpx) }
	}

	/**
	 * Zooms the area's camera about a cursor position (wheel zoom) by one configured step, pinning the world
	 * point under the cursor. The step (fine or coarse Shift step) comes from settings.
	 *
	 * @param String areaId The area id.
	 * @param Boolean zoomIn True to zoom in, false to zoom out.
	 * @param Boolean coarse Use the coarse (Shift) step.
	 * @param Float cursorXpx Cursor X in pixels.
	 * @param Float cursorYpx Cursor Y in pixels.
	 */
	fun zoomAtCursor(areaId: String, zoomIn: Boolean, coarse: Boolean, cursorXpx: Float, cursorYpx: Float) {
		val slot = areas[areaId] ?: return
		val width = slot.width
		val height = slot.height
		val step = stepFor(coarse)
		val deltaPercent = if (zoomIn) step else -step
		updateCamera(areaId) { camera ->
			camera.zoomAtCursorByPercent(
				deltaPercent,
				step,
				cursorXpx,
				cursorYpx,
				width,
				height,
			)
		}
	}

	/**
	 * Frames a screen-pixel rectangle in the area's camera (Zoom Region / Shift+B): the box fills the
	 * viewport, letterboxed on the looser axis. A no-op before the first fit or when the area has no size yet.
	 *
	 * @param String areaId The area id.
	 * @param Float leftPx One horizontal box edge in viewport pixels.
	 * @param Float topPx One vertical box edge in viewport pixels.
	 * @param Float rightPx The other horizontal box edge in viewport pixels.
	 * @param Float bottomPx The other vertical box edge in viewport pixels.
	 */
	fun zoomToRegion(areaId: String, leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float) {
		val slot = areas[areaId] ?: return
		val width = slot.width
		val height = slot.height
		if (width <= 0 || height <= 0) {
			return
		}
		updateCamera(areaId) { camera -> camera.framingScreenRect(leftPx, topPx, rightPx, bottomPx, width, height) }
	}

	/**
	 * Zooms the area's camera about its center (keyboard zoom in/out) by one configured step.
	 *
	 * @param String areaId The area id.
	 * @param Boolean zoomIn True to zoom in, false to zoom out.
	 * @param Boolean coarse Use the coarse (Shift) step.
	 */
	fun zoomCentered(areaId: String, zoomIn: Boolean, coarse: Boolean) {
		val step = stepFor(coarse)
		val deltaPercent = if (zoomIn) step else -step
		updateCamera(areaId) { camera -> camera.zoomedByPercent(deltaPercent, step) }
	}

	/**
	 * Sets the area's camera to true 1:1 about its center (actual size / 100%).
	 *
	 * @param String areaId The area id.
	 */
	fun actualSize(areaId: String) {
		updateCamera(areaId) { camera -> camera.withActualSize() }
	}

	/**
	 * Refits the area's camera to the content on the next render tick by flagging it - the render thread,
	 * which owns the content bounds and the current size, recomputes the fit in [establishCamera].
	 *
	 * @param String areaId The area id.
	 */
	fun fit(areaId: String) {
		val slot = areas[areaId] ?: return
		slot.refitRequested = true
	}

	/**
	 * Fits a world-space rectangle inside the area, centerd (the Frame Selected camera move). A no-op before
	 * the first fit or when the area has no size yet.
	 *
	 * @param String areaId The area id.
	 * @param Float minX The rectangle's minimum world x.
	 * @param Float minY The rectangle's minimum world y.
	 * @param Float maxX The rectangle's maximum world x.
	 * @param Float maxY The rectangle's maximum world y.
	 */
	fun fitWorldRect(areaId: String, minX: Float, minY: Float, maxX: Float, maxY: Float) {
		val slot = areas[areaId] ?: return
		val width = slot.width
		val height = slot.height
		if (width <= 0 || height <= 0 || maxX < minX || maxY < minY) {
			return
		}
		// A degenerate rect (a single vertex) still frames: ContentBounds spans are clamped to >= 1 world unit
		// by the fit, so the camera centers on the point at a sane zoom.
		updateCamera(areaId) {
			ViewportCamera.fit(ContentBounds(minX, minY, maxX - minX, maxY - minY), width, height)
		}
	}

	/**
	 * The camera and size of an area for a CPU pick, or null when the area is unregistered, unsized, or has
	 * no camera yet (before the first fit).
	 *
	 * @param String areaId The area id.
	 * @return AreaView The area's camera and pixel size, or null.
	 */
	fun viewFor(areaId: String): AreaView? {
		val slot = areas[areaId] ?: return null
		val camera = slot.camera ?: return null
		return AreaView(camera, slot.width, slot.height)
	}

	/**
	 * Render-thread: ensures the slot has a camera for what it shows, establishing or refitting it now that the
	 * size is known.  A pending refit forces a fresh fit.  Otherwise a freshly-(re)registered area restores its
	 * remembered camera (so workspace switches preserve pan/zoom), and a UV-editor area whose shown page or
	 * layer is no longer the one its camera frames swaps to the view it left that surface with - either falling
	 * back to a fit the first time that surface is shown.  The content bounds are resolved lazily (only when a
	 * fit is actually needed) via [contentBounds]; a UV-editor fit is widened to the shown meshes' extent.
	 *
	 * The kind and the content are read once, up front: the fit, the recall, and the record all have to be about
	 * the same surface, and the UI thread can retarget the slot at any moment.  A retarget that lands after the
	 * read leaves a camera framing a surface no longer shown, which the next call swaps in turn.
	 *
	 * @param AreaSlot slot The area being established.
	 * @param String areaId The area id.
	 * @param Int width The current area width.
	 * @param Int height The current area height.
	 * @param Function contentBounds Supplies the rectangle of the scene and content it is handed, evaluated only
	 *   when a fit is needed.
	 * @return ViewportCamera The area's current camera.
	 */
	fun establishCamera(
		slot: AreaSlot,
		areaId: String,
		width: Int,
		height: Int,
		contentBounds: (RenderScene, UvSceneContent?) -> ContentBounds,
	): ViewportCamera {
		val scene = slot.scene
		val content = slot.uvContent
		val shownSurface =
			when (scene) {
				RenderScene.Puppet2D -> null
				RenderScene.UvScene -> content?.surfaceId ?: UvSurfaceId.Page(null)
			}
		val framing = slot.framing
		val refit = slot.refitRequested
		if (framing != null && !refit && framing.surface == shownSurface) {
			return framing.camera
		}
		val recalled = if (refit) null else recallCamera(areaId, scene, shownSurface)
		val camera =
			recalled ?: run {
				val sceneBounds = contentBounds(scene, content)
				// Read after the content: the UI thread writes the extent first, so it belongs to this content.
				val fitBounds =
					when (scene) {
						RenderScene.Puppet2D -> sceneBounds
						RenderScene.UvScene -> uvFitBounds(sceneBounds, slot.uvIslandExtent)
					}
				ViewportCamera.fit(fitBounds, width, height)
			}
		slot.refitRequested = false
		publishCamera(slot, areaId, FramedCamera(camera, shownSurface))
		return camera
	}

	/**
	 * The view an area had of a surface, for an area establishing one without a refit, or null when it has none
	 * and the surface is fitted.
	 *
	 * A UV-editor area takes its own view of that page or layer first.  The area's whole-UV view - what a saved
	 * document seeds - stands in only while the area has shown no UV surface this session: until then it can only
	 * be the saved view, which belongs to whatever surface the area opens on.  After that it is the last surface's
	 * view, and a different surface fits rather than inheriting it.
	 *
	 * @param String       areaId  The area id.
	 * @param RenderScene  scene   The area's kind.
	 * @param UvSurfaceId? surface The UV surface shown, or null for a puppet area.
	 * @return ViewportCamera? The view to restore, or null to fit.
	 */
	private fun recallCamera(areaId: String, scene: RenderScene, surface: UvSurfaceId?): ViewportCamera? {
		if (surface == null) {
			return rememberedCameras[AreaCameraKey(areaId, scene.cameraSurface)]
		}
		uvSurfaceCameras[AreaUvSurfaceKey(areaId, surface)]?.let { remembered ->
			return remembered
		}
		if (areaId in uvShownAreaIds) {
			return null
		}
		return rememberedCameras[AreaCameraKey(areaId, CameraSurface.Uv)]
	}

	/**
	 * Makes [framing] the slot's camera and remembers it: under the area and its surface kind (what a save writes
	 * and a workspace switch restores) and, for a UV-editor view, under the page or layer it frames.  Keyed off the
	 * framing's own surface rather than the slot's current content, so a view is always filed with the surface it
	 * was taken of.
	 *
	 * @param AreaSlot     slot    The area's slot.
	 * @param String       areaId  The area id.
	 * @param FramedCamera framing The camera and the surface it frames.
	 */
	private fun publishCamera(slot: AreaSlot, areaId: String, framing: FramedCamera) {
		slot.framing = framing
		slot.cameraState.value = framing.camera
		val surface = framing.surface
		if (surface == null) {
			rememberedCameras[AreaCameraKey(areaId, CameraSurface.Viewport)] = framing.camera
		} else {
			rememberedCameras[AreaCameraKey(areaId, CameraSurface.Uv)] = framing.camera
			uvSurfaceCameras[AreaUvSurfaceKey(areaId, surface)] = framing.camera
			uvShownAreaIds.add(areaId)
		}
	}

	/** The zoom step in percentage points for this notch/press: the coarse (Shift) step or the fine step. */
	private fun stepFor(coarse: Boolean): Float = if (coarse) zoomStepCoarsePercent else zoomStepPercent

	/**
	 * Applies [transform] to the area's current camera and publishes the result, still framing the surface it
	 * framed. No-op before the initial fit (there is nothing to transform until the first frame establishes the
	 * view).
	 *
	 * @param String areaId The area id.
	 * @param Function transform Maps the current camera to its replacement.
	 */
	private fun updateCamera(areaId: String, transform: (ViewportCamera) -> ViewportCamera) {
		val slot = areas[areaId] ?: return
		val framing = slot.framing ?: return
		publishCamera(slot, areaId, framing.copy(camera = transform(framing.camera)))
	}
}