package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.ContentBounds
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.RenderedFrame
import java.util.concurrent.ConcurrentHashMap

/** Which content an area renders: the posed puppet (2D viewport) or a flat atlas page (UV editor). */
internal enum class RenderScene {
	Puppet2D,
	AtlasPage,
}

/** The camera and pixel size of a registered area, resolved for a CPU pick. Null-camera areas do not appear. */
internal data class AreaView(val camera: ViewportCamera, val width: Int, val height: Int)

/**
 * Per-area render state, shared between the UI thread ([ViewportAreaRegistry]) and the render thread
 * ([OffscreenRenderEngine]). Field-ownership contract:
 *
 *   - The @Volatile fields are written by the UI thread and read by the render thread (a volatile publish of
 *     immutable values or plain scalars): scene, pageIndex, width, height, camera, refitRequested.
 *   - imageState / cameraState are thread-safe StateFlows; either thread may set them.
 *   - refCount is touched only on the UI thread (register/unregister run as composition effects).
 *   - The remaining plain fields (inFlight, rendered*, *RenderBumpDone) are render-thread-only bookkeeping.
 *
 * エリアごとの描画状態。UI スレッド（レジストリ）とレンダースレッド（エンジン）で共有される。
 */
internal class AreaSlot {
	// Whether this area renders the posed puppet or a UV-editor atlas page. Fixed at registration (register
	// vs registerAtlasPage); pageIndex is retargeted by setAtlasPageIndex as the UV editor's active drawable
	// changes. UI thread writes, render thread reads - a volatile publish.
	@Volatile
	var scene: RenderScene = RenderScene.Puppet2D

	@Volatile
	var pageIndex: Int? = null

	@Volatile
	var width: Int = 0

	@Volatile
	var height: Int = 0
	val imageState = MutableStateFlow<RenderedFrame?>(null)

	// The per-area camera (pan/zoom). null until the render thread computes the initial fit (it needs the
	// area size + content bounds); thereafter the UI thread swaps in a new immutable camera per edit (a
	// volatile publish). cameraState mirrors it for the overlay readout.
	@Volatile
	var camera: ViewportCamera? = null

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
	var renderedPageIndex: Int? = null
}

/**
 * Owns the registered viewport areas and their cameras. The register/resize/camera-navigation surface runs
 * on the UI thread; [OffscreenRenderEngine] iterates the same [areas] map on the render thread and calls
 * [establishCamera] to fit newly-sized areas. The shared state is safe across the two threads: the slot map
 * is a ConcurrentHashMap, per-area hand-off fields are @Volatile, and the camera flows are StateFlows.
 *
 * ビューポートのエリアとカメラを所有する。登録・リサイズ・ナビゲーションは UI スレッド、描画はエンジンが
 * レンダースレッドから同じスロットを読む。
 */
internal class ViewportAreaRegistry {
	/** The registered areas, keyed by stable area id. Iterated by the render engine; mutated by the UI thread. */
	val areas = ConcurrentHashMap<String, AreaSlot>()

	// Cameras remembered by area id beyond a slot's composable life, so switching workspaces (which disposes
	// the inactive workspace's viewports -> drops their slots) and returning restores the pan/zoom instead of
	// refitting. Area ids are minted once and never reused, so entries never collide; the map grows only by
	// areas ever shown (a few floats each) and is not evicted (session-bounded, negligible cost).
	private val rememberedCameras = ConcurrentHashMap<String, ViewportCamera>()

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
		slot.refCount++
		return slot.imageState
	}

	/**
	 * Registers an atlas-page area (the UV editor's flat page underlay) and returns its image flow.
	 *
	 * @param String areaId The hosting area's stable id.
	 * @param Int pageIndex The atlas page to draw, or null for none (grid only).
	 * @return StateFlow The area's image stream (null until the first render completes).
	 */
	fun registerAtlasPage(areaId: String, pageIndex: Int?): StateFlow<RenderedFrame?> {
		val slot = areas.getOrPut(areaId) { AreaSlot() }
		slot.scene = RenderScene.AtlasPage
		slot.pageIndex = pageIndex
		slot.refCount++
		return slot.imageState
	}

	/**
	 * Retargets the atlas page an already-registered atlas-page area renders. A no-op for an unregistered
	 * area or a puppet (2D) area.
	 *
	 * @param String areaId The atlas-page area to retarget.
	 * @param Int pageIndex The new atlas page, or null for none.
	 */
	fun setAtlasPageIndex(areaId: String, pageIndex: Int?) {
		val slot = areas[areaId] ?: return
		if (slot.scene != RenderScene.AtlasPage) {
			return
		}
		slot.pageIndex = pageIndex
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
	 * Zooms the area's camera about its centre (keyboard zoom in/out) by one configured step.
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
	 * Sets the area's camera to true 1:1 about its centre (actual size / 100%).
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
	 * Fits a world-space rectangle inside the area, centred (the Frame Selected camera move). A no-op before
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
		// by the fit, so the camera centres on the point at a sane zoom.
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
	 * Render-thread: ensures the slot has a camera, establishing or refitting it now that the size is known.
	 * A pending refit forces a fresh fit; otherwise a freshly-(re)registered area restores its remembered
	 * camera (so workspace switches preserve pan/zoom), falling back to a fit the first time it is ever shown.
	 * The content bounds are resolved lazily (only when a fit is actually needed) via [contentBounds].
	 *
	 * @param AreaSlot slot The area being established.
	 * @param String areaId The area id.
	 * @param Int width The current area width.
	 * @param Int height The current area height.
	 * @param Function contentBounds Supplies the rectangle to fit, evaluated only when a fit is needed.
	 * @return ViewportCamera The area's current camera.
	 */
	fun establishCamera(
		slot: AreaSlot,
		areaId: String,
		width: Int,
		height: Int,
		contentBounds: () -> ContentBounds,
	): ViewportCamera {
		var camera = slot.camera
		if (slot.refitRequested || camera == null) {
			camera =
				if (slot.refitRequested) {
					ViewportCamera.fit(contentBounds(), width, height)
				} else {
					rememberedCameras[areaId] ?: ViewportCamera.fit(contentBounds(), width, height)
				}
			slot.refitRequested = false
			slot.camera = camera
			slot.cameraState.value = camera
			rememberedCameras[areaId] = camera
		}
		return camera
	}

	/** The zoom step in percentage points for this notch/press: the coarse (Shift) step or the fine step. */
	private fun stepFor(coarse: Boolean): Float = if (coarse) zoomStepCoarsePercent else zoomStepPercent

	/**
	 * Applies [transform] to the area's current camera and publishes the result. No-op before the initial fit
	 * (there is nothing to transform until the first frame establishes the view).
	 *
	 * @param String areaId The area id.
	 * @param Function transform Maps the current camera to its replacement.
	 */
	private fun updateCamera(areaId: String, transform: (ViewportCamera) -> ViewportCamera) {
		val slot = areas[areaId] ?: return
		val camera = slot.camera ?: return
		val updated = transform(camera)
		slot.camera = updated
		slot.cameraState.value = updated
		rememberedCameras[areaId] = updated
	}
}
