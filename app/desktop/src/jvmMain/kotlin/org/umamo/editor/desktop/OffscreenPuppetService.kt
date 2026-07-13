package org.umamo.editor.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import org.lwjgl.system.MemoryUtil
import org.umamo.edit.GridConfig
import org.umamo.render.ContentBounds
import org.umamo.render.GpuRenderer
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.gl.GlPuppetRenderer
import org.umamo.render.pick.PickCandidate
import org.umamo.render.pick.drawableCentroids
import org.umamo.render.pick.pickAllDrawables
import org.umamo.render.pick.pickDrawable
import org.umamo.render.pick.screenToWorldX
import org.umamo.render.pick.screenToWorldY
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasKeyByDrawable
import org.umamo.runtime.model.partNameByDrawable
import org.umamo.runtime.model.pickableIndicesByDrawable
import org.umamo.runtime.model.pickableUvsByDrawable
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.model.DrawableThumbnailer
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.RenderedFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.Image as SkiaImage

/** Idle poll when nothing changed and no read-back is in flight (≈60 Hz wake to pick up new params). */
private const val IDLE_MILLIS = 16L

/** Short poll while a read-back is in flight, so its result is collected with low latency. */
private const val BUSY_MILLIS = 1L

/**
 * Supersample factor for the offscreen render: the puppet, its clip masks, and the grid are drawn at this
 * multiple of the display resolution and box-downscaled on resolve. This anti-aliases geometry edges AND the
 * in-shader clip masks together (MSAA cannot smooth a per-fragment mask lookup), with no atlas mipmaps (so no
 * cross-region atlas bleed). 2× costs 4× the fill of the draw buffer; the read-back stays at display size.
 */
private const val RENDER_SUPERSAMPLE = 2

/**
 * Renders the puppet OFF-SCREEN and publishes it as a Compose [ImageBitmap] per viewport area, instead
 * of embedding a heavyweight AWT GL canvas. One dedicated thread owns a GLFW hidden-window GL context
 * and a [GlPuppetRenderer]; it renders the current pose into an FBO sized to each area and reads the
 * pixels back. Because the viewport is then lightweight Compose content, menus / overlays / (future)
 * gizmos layer over it correctly on every platform - which the heavyweight `SwingPanel` path could not.
 *
 * The read-back is ASYNCHRONOUS via pixel-buffer objects + fence syncs. A plain `glReadPixels` into a
 * client array stalls the GL thread until the GPU finishes and the copy completes; instead we
 * `glReadPixels` into a PBO (returns immediately, scheduling the copy on the GPU), drop a fence, and
 * only map the PBO on a later tick once the fence is signaled - so the thread never blocks on the GPU
 * and can keep the pipeline full while a slider is being dragged.
 *
 * All viewport areas of one document show the same puppet at the same pose (the shared [liveParams]),
 * so areas differ only by SIZE; re-renders happen only when the pose or an area's size changes.
 *
 * パペットをオフスクリーンで描画し、エリアごとに Compose ImageBitmap として公開する。読み戻しは PBO と
 * フェンスで非同期化し、GL スレッドが GPU 完了を待って停止しないようにする。
 *
 * @property PuppetModel puppet The rig to render.
 * @property PuppetTextures textures The atlas page(s).
 * @property LiveParams liveParams The shared parameter hand-off (drives re-render on change).
 */
class OffscreenPuppetService(
	private val puppet: PuppetModel,
	private val textures: PuppetTextures,
	private val liveParams: LiveParams,
) : PuppetViewportService {
	/** Which content an area renders: the posed puppet (2D viewport) or a flat atlas page (UV editor). */
	private enum class RenderScene { Puppet2D, AtlasPage }

	/** Per-area render state: the requested size (UI thread writes) and the published image. */
	private class AreaSlot {
		// Whether this area renders the posed puppet or a UV-editor atlas page.  Fixed at registration
		// (register vs registerAtlasPage); pageIndex is retargeted by setAtlasPageIndex as the UV editor's
		// active drawable changes.  UI thread writes, render thread reads - a volatile publish.
		@Volatile
		var scene: RenderScene = RenderScene.Puppet2D

		@Volatile
		var pageIndex: Int? = null

		@Volatile
		var width: Int = 0

		@Volatile
		var height: Int = 0
		val imageState = MutableStateFlow<RenderedFrame?>(null)

		// The per-area camera (pan/zoom). null until the render thread computes the initial fit (it needs
		// the area size + content bounds); thereafter the UI thread swaps in a new immutable camera per
		// edit (a volatile publish, like LiveParams). cameraState mirrors it for the overlay readout.
		// EN: per-area pan/zoom. JA: エリアごとのパン／ズーム。
		@Volatile
		var camera: ViewportCamera? = null

		// Set by the Fit command (UI thread), cleared by the render thread, which then recomputes a fresh
		// fit (ignoring any remembered camera). A flag rather than camera=null so the old view shows until
		// the refit lands, and so refit beats the workspace-restore path below.
		@Volatile
		var refitRequested: Boolean = false
		val cameraState = MutableStateFlow<ViewportCamera?>(null)

		// How many live composables currently hold this area id. A leaf can be torn down and rebuilt for
		// the SAME id during a tree collapse (closing a split sibling), so register(new) and
		// unregister(old) briefly overlap; the slot is dropped only when this returns to 0. Touched only
		// on the Compose UI thread (register/unregister run as composition effects), so no sync needed.
		// EN: live-holder count, UI-thread only. JA: 生存している保持側の数（UI スレッドのみ）。
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

	/** A recyclable pixel-buffer object and its current byte capacity. */
	private class Pbo(val id: Int, var capacity: Int)

	/** An in-flight asynchronous read-back: which area/size, into which PBO, gated by which fence, of which model. */
	private class Readback(
		val areaId: String,
		val width: Int,
		val height: Int,
		val pbo: Pbo,
		val fence: Long,
		val camera: ViewportCamera,
		val model: PuppetModel,
	)

	private val areas = ConcurrentHashMap<String, AreaSlot>()

	// Cameras remembered by area id beyond a slot's composable life, so switching workspaces (which
	// disposes the inactive workspace's viewports → drops their slots) and returning restores the
	// pan/zoom instead of refitting. Area ids are minted once and never reused, so entries never collide;
	// the map grows only by areas ever shown (a few floats each) and is not evicted (there is no
	// area-closed signal here - a session-bounded, negligible cost).
	private val rememberedCameras = ConcurrentHashMap<String, ViewportCamera>()

	@Volatile
	private var running = true

	// The viewport the pointer is over, so keyboard view commands (fit / 1:1 / zoom) target it. UI thread.
	@Volatile
	override var activeAreaId: String? = null

	// Zoom increments in percentage points, fed from settings (viewport.zoomStep*Percent): the fine step is
	// one wheel notch / keyboard press, the coarse step is the Shift-held variant. The app writes these on
	// settings load + change; the zoom methods read them. Defaults mirror defaultSettings.json.
	@Volatile
	override var zoomStepPercent: Float = 1f

	@Volatile
	override var zoomStepCoarsePercent: Float = 5f

	// The grid backdrop colors, fed from the editor theme on the UI thread and read by the render thread
	// before each render so a theme switch re-tints the viewport. Setting a new value bumps puppetRenderBump,
	// which the render loop folds into its per-area freshness check so a color-only change (no resize / pose /
	// camera change) still forces one redraw - otherwise a live theme switch would not repaint until the next
	// unrelated render. Defaults to the neutral grey grid until the host pushes the themed colors. UI thread
	// is the only writer (a LaunchedEffect), so the non-atomic ++ is safe.
	@Volatile
	private var gridColorsBacking: GridColors = GridColors.Classic

	override var gridColors: GridColors
		get() = gridColorsBacking
		set(value) {
			if (value != gridColorsBacking) {
				gridColorsBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	// The per-document grid geometry (major spacing + subdivisions), fed from the session on the UI thread
	// and read by the render thread. Like the grid colors, a change bumps both render passes so a grid-only
	// change (settings edit, per-file value) repaints without waiting for an unrelated render.
	@Volatile
	private var gridConfigBacking: GridConfig = GridConfig()

	override var gridConfig: GridConfig
		get() = gridConfigBacking
		set(value) {
			if (value != gridConfigBacking) {
				gridConfigBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	// The currently selected drawables, pushed from the UI on selection change and read by the render
	// thread to tint them. Setting a new (different) set bumps puppetRenderBump, which the render loop folds
	// into its per-area freshness check so a selection-only change still forces one redraw.
	// The wholesale immutable-set swap makes the volatile reference a safe publish.
	@Volatile
	private var selectionBacking: Set<DrawableId> = emptySet()

	// The drawables actually drawn (the resolved Parts-panel visibility cascade), pushed from the UI when
	// the model's visibility changes (a toggle, or its undo) and read by the render thread each frame.
	// Setting a new (different) set bumps puppetRenderBump so the area re-renders once. The wholesale immutable-set
	// swap makes the volatile reference a safe publish. Seeded from the open model's static cascade.
	@Volatile
	private var shownBacking: Set<DrawableId> = puppet.visibleDrawableIds()

	// The latest model, pushed from the UI when a structural edit changes the render order (a layer
	// reorder) and read by the render thread to re-push the order. The wholesale immutable swap makes the
	// volatile reference a safe publish; seeded with the open model.
	@Volatile
	private var modelBacking: PuppetModel = puppet

	@Volatile
	private var puppetRenderBump: Long = 0

	// Atlas changes bump the renderer separately so that the puppet updating does not unnecessarily update the atlas render.
	@Volatile
	private var atlasRenderBump: Long = 0

	// The color selected drawables are tinted toward, fed from the editor settings on the UI thread and read by the render thread before each render. Setting a new (different) color bumps puppetRenderBump,
	// which the render loop folds into its per-area freshness check so a color-only change still forces one redraw of the areas showing a selection.  RGB, each 0..1; defaults to the classic blue accent (the renderer's own default) until the host pushes the configured
	// color. UI thread is the only writer, so the non-atomic ++ is safe.
	@Volatile
	private var highlightRed: Float = 0.20f

	@Volatile
	private var highlightGreen: Float = 0.55f

	@Volatile
	private var highlightBlue: Float = 1.0f

	// Per-drawable triangle indices for hit-testing, restricted to the drawables actually shown (the
	// Parts-panel eyeball cascade) and to those carrying a triangle mesh. Picking iterates the pose's
	// deformed positions and looks indices up here, so unshown / mesh-less drawables are never hit.
	// Rebuilt by [setModel] so session-created drawables (a duplicate's fresh ".001" id) and visibility
	// edits stay pickable - a map frozen at construction would leave a duplicate unpickable, with every
	// click on it falling through to the art beneath.
	private var pickableIndices: Map<DrawableId, IntArray> = puppet.pickableIndicesByDrawable()

	// Per-drawable UVs (full-atlas [0,1]) for the same pickable set, so picking can interpolate the hit
	// point's UV and sample the atlas alpha (rejecting clicks on a mesh's transparent triangle overhang).
	// Rebuilt by [setModel] alongside [pickableIndices].
	private var pickableUvs: Map<DrawableId, FloatArray> = puppet.pickableUvsByDrawable()

	// Art-mesh previews for the overlap picker (the same provider the Outliner hover uses). Pure CPU over
	// the immutable atlas bytes, so it is built once and shared - a single cache across both consumers.
	private val thumbnailer = DrawableThumbnailer(puppet, textures)

	// Drawable id → owning part name, for the overlap-picker row labels. Rebuilt by [setModel] so a
	// session-created drawable is labeled by its owning part like any other.
	private var partNameByDrawableId: Map<DrawableId, String> = puppet.partNameByDrawable()

	// Drawable id → atlas lookup key: the source-format id the atlas map is keyed by, resolved through
	// textureSourceId so a duplicate samples its SOURCE's texels for the pick alpha gate (the atlas map
	// knows nothing of session-created ids). Rebuilt by [setModel].
	private var atlasKeyByDrawableId: Map<DrawableId, String> = puppet.atlasKeyByDrawable()

	// Daemon so it can never block JVM exit; clean teardown still happens via dispose() → join.
	private val renderThread = Thread({ renderLoop() }, "umamo-offscreen-gl").apply { isDaemon = true }

	// GL handles + async read-back state, all owned by the render thread.
	private val renderer =
		GlPuppetRenderer(puppet, textures).apply {
			// The editor viewport shows the world-origin axes (red X / blue Z behind the puppet); the
			// renderer default is off so headless render-diff tests stay line-free.
			setWorldAxesVisible(true)
		}
	private var window: Long = MemoryUtil.NULL

	// Display-size resolve target + read-back source: the supersampled draw target is downscaled here, then
	// glReadPixels reads it (the draw renderbuffer cannot be read back directly).
	private var framebuffer: Int = 0
	private var colorTexture: Int = 0

	// Supersampled draw target (RENDER_SUPERSAMPLE× the display size): the renderer draws the puppet, masks,
	// and grid here, then we box-downscale into `framebuffer` - the supersample resolve.
	private var drawFramebuffer: Int = 0
	private var drawColorRenderbuffer: Int = 0

	// Last DISPLAY size allocated (the draw buffer is RENDER_SUPERSAMPLE× this).
	private var framebufferWidth: Int = -1
	private var framebufferHeight: Int = -1
	private val freePbos = ArrayDeque<Pbo>()
	private val inFlight = ArrayDeque<Readback>()
	private var dumped = false

	/** Starts the render thread (call once). */
	fun start() {
		renderThread.start()
	}

	/**
	 * Registers a viewport area and returns the flow of images to display for it. Re-registering an id
	 * that is still live (the same area's leaf rebuilt during a tree collapse - see [AreaSlot.refCount])
	 * returns the existing slot and its last image, so the surviving viewport keeps rendering instead of
	 * being orphaned.
	 *
	 * @param String areaId The hosting area's stable id.
	 * @return StateFlow the area's image stream (null until the first render completes).
	 */
	override fun register(areaId: String): StateFlow<RenderedFrame?> {
		val slot = areas.getOrPut(areaId) { AreaSlot() }
		slot.refCount++
		return slot.imageState
	}

	override fun registerAtlasPage(areaId: String, pageIndex: Int?): StateFlow<RenderedFrame?> {
		val slot = areas.getOrPut(areaId) { AreaSlot() }
		slot.scene = RenderScene.AtlasPage
		slot.pageIndex = pageIndex
		slot.refCount++
		return slot.imageState
	}

	override fun setAtlasPageIndex(areaId: String, pageIndex: Int?) {
		val slot = areas[areaId] ?: return
		if (slot.scene != RenderScene.AtlasPage) {
			return
		}
		slot.pageIndex = pageIndex
	}

	/**
	 * Returns the flow of the area's current camera, for the overlay zoom readout. The viewport composable
	 * registers the area before reading this, so it attaches to the existing slot.
	 *
	 * @param String areaId The area id.
	 * @return StateFlow the area's camera stream (null until the first fit).
	 */
	override fun cameraFlow(areaId: String): StateFlow<ViewportCamera?> =
		areas.getOrPut(areaId) { AreaSlot() }.cameraState

	/**
	 * Updates an area's requested pixel size; the render thread re-renders it at the new size. A no-op
	 * for an unregistered id - registration (a composition `remember`) always precedes the size report (a
	 * post-composition effect), so resize must never resurrect a slot the ref-count has already dropped
	 * (that would leak a zero-ref slot the render loop renders forever).
	 *
	 * @param String areaId The area id.
	 * @param Int width The new width in pixels.
	 * @param Int height The new height in pixels.
	 */
	override fun resize(areaId: String, width: Int, height: Int) {
		val slot = areas[areaId] ?: return
		slot.width = width
		slot.height = height
	}

	/**
	 * Pans the area's camera by a screen-pixel drag (grab style). No-op before the initial fit exists.
	 *
	 * @param String areaId   The area id.
	 * @param Float  deltaXpx Horizontal drag in pixels.
	 * @param Float  deltaYpx Vertical drag in pixels.
	 */
	override fun pan(areaId: String, deltaXpx: Float, deltaYpx: Float) {
		updateCamera(areaId) { camera -> camera.panByScreen(deltaXpx, deltaYpx) }
	}

	/**
	 * Zooms the area's camera about a cursor position (wheel zoom) by one configured step, pinning the
	 * world point under the cursor. The step (fine or [coarse] Shift step) comes from settings.
	 *
	 * @param String  areaId    The area id.
	 * @param Boolean zoomIn    True to zoom in, false to zoom out.
	 * @param Boolean coarse    Use the coarse (Shift) step.
	 * @param Float   cursorXpx Cursor X in pixels.
	 * @param Float   cursorYpx Cursor Y in pixels.
	 */
	override fun zoomAtCursor(areaId: String, zoomIn: Boolean, coarse: Boolean, cursorXpx: Float, cursorYpx: Float) {
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
	 * viewport, letterboxed on the looser axis.  A no-op before the first fit (the camera is null, handled by
	 * updateCamera) or when the area has no size yet.
	 *
	 * @param String areaId The area id.
	 * @param Float leftPx One horizontal box edge in viewport pixels.
	 * @param Float topPx One vertical box edge in viewport pixels.
	 * @param Float rightPx The other horizontal box edge in viewport pixels.
	 * @param Float bottomPx The other vertical box edge in viewport pixels.
	 */
	override fun zoomToRegion(areaId: String, leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float) {
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
	 * @param String  areaId The area id.
	 * @param Boolean zoomIn True to zoom in, false to zoom out.
	 * @param Boolean coarse Use the coarse (Shift) step.
	 */
	override fun zoomCentered(areaId: String, zoomIn: Boolean, coarse: Boolean) {
		val step = stepFor(coarse)
		val deltaPercent = if (zoomIn) step else -step
		updateCamera(areaId) { camera -> camera.zoomedByPercent(deltaPercent, step) }
	}

	/** The zoom step in percentage points for this notch/press: the coarse (Shift) step or the fine step. */
	private fun stepFor(coarse: Boolean): Float = if (coarse) zoomStepCoarsePercent else zoomStepPercent

	/**
	 * Sets the area's camera to true 1:1 about its centre (actual size / 100%).
	 *
	 * @param String areaId The area id.
	 */
	override fun actualSize(areaId: String) {
		updateCamera(areaId) { camera -> camera.withActualSize() }
	}

	/**
	 * Refits the area's camera to the content on the next render tick by clearing it - the render thread,
	 * which owns the content bounds and the current size, recomputes the fit.
	 *
	 * @param String areaId The area id.
	 */
	override fun fit(areaId: String) {
		val slot = areas[areaId] ?: return
		slot.refitRequested = true
	}

	override fun fitWorldRect(areaId: String, minX: Float, minY: Float, maxX: Float, maxY: Float) {
		val slot = areas[areaId] ?: return
		val width = slot.width
		val height = slot.height
		if (width <= 0 || height <= 0 || maxX < minX || maxY < minY) {
			return
		}
		// A degenerate rect (a single vertex) still frames: ContentBounds spans are clamped to >= 1 world
		// unit by the fit, so the camera centres on the point at a sane zoom.
		updateCamera(areaId) {
			ViewportCamera.fit(ContentBounds(minX, minY, maxX - minX, maxY - minY), width, height)
		}
	}

	/**
	 * Sets the highlighted drawables (object-mode selection). A change bumps puppetRenderBump so the
	 * render loop re-renders every area once with the new tint; an identical set is a no-op.
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	override fun setSelection(ids: Set<DrawableId>) {
		if (ids != selectionBacking) {
			selectionBacking = ids
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets which drawables are drawn (the resolved Parts-panel visibility cascade). The host pushes this
	 * when the model's visibility changes — a part/drawable eyeball toggle or its undo — so the viewport
	 * hides or re-shows that art. A change bumps puppetRenderBump so every area re-renders once; an identical
	 * set is a no-op. The geometry is unchanged, so only the draw filter moves (no buffer re-upload).
	 *
	 * @param Set<DrawableId> ids The drawable ids to draw.
	 */
	override fun setShownDrawables(ids: Set<DrawableId>) {
		if (ids != shownBacking) {
			shownBacking = ids
			doPuppetRenderBump()
		}
	}

	/**
	 * Pushes the latest model so the render thread can reconcile it after an edit: a layer reorder / reparent
	 * re-derives the render order, and a base-mesh move re-uploads the changed drawables' position VBOs (both
	 * happen in the render loop's [GlPuppetRenderer.updateModel], which owns the GL context). A new (different)
	 * instance bumps puppetRenderBump so every area re-renders once; an identical instance is a no-op.
	 *
	 * @param PuppetModel model The current model.
	 */
	override fun setModel(model: PuppetModel) {
		if (model !== modelBacking) {
			modelBacking = model
			// Rebuild the model-derived lookup maps (pick indices / UVs, atlas keys, part labels) so
			// session-created drawables - a duplicate's fresh ".001" id - stay pickable, sampleable, and
			// labeled, and a visibility edit updates the pickable set. Cheap map assembly over shared
			// arrays, and all UI-thread: setModel is pushed from the UI bridge, the same thread that picks.
			pickableIndices = model.pickableIndicesByDrawable()
			pickableUvs = model.pickableUvsByDrawable()
			partNameByDrawableId = model.partNameByDrawable()
			atlasKeyByDrawableId = model.atlasKeyByDrawable()
			thumbnailer.updateModel(model)
			doPuppetRenderBump()
		}
	}

	/**
	 * Bump the render version for puppets to increase the frame by one.
	 */
	fun doPuppetRenderBump() {
		puppetRenderBump++
	}

	/**
	 * Bump the render version for atlases to increase the frame by one.
	 */
	fun doAtlasRenderBump() {
		atlasRenderBump++
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight). A change bumps the
	 * puppetRenderBump so the render loop re-renders every area once with the new tint; an identical
	 * color is a no-op.
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	override fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		if (red != highlightRed || green != highlightGreen || blue != highlightBlue) {
			highlightRed = red
			highlightGreen = green
			highlightBlue = blue
			doPuppetRenderBump()
		}
	}

	/**
	 * The atlas-texel alpha (0..1) for a drawable at a full-atlas (u, v), or 1f when the drawable is
	 * untextured (a flat draw color, treated as fully opaque). Reads the retained CPU atlas pixels; the
	 * alpha byte is the coverage whether or not the atlas is premultiplied, so no un-premultiply is needed.
	 *
	 * @param DrawableId id The drawable.
	 * @param Float u The full-atlas U.
	 * @param Float v The full-atlas V.
	 * @return Float The texel alpha, 0..1.
	 */
	private fun sampleTexelAlpha(id: DrawableId, u: Float, v: Float): Float {
		val atlasIndex = textures.atlasIndexByDrawableId[atlasKeyByDrawableId[id] ?: id.raw] ?: return 1f
		val image = textures.atlases.getOrNull(atlasIndex) ?: return 1f
		val px = (u * image.width).toInt().coerceIn(0, image.width - 1)
		val py = (v * image.height).toInt().coerceIn(0, image.height - 1)
		val alpha = image.rgba[(py * image.width + px) * 4 + 3].toInt() and 0xFF
		return alpha / 255f
	}

	/** The renderer's resolved back-to-front draw list as a front-rank map (higher index = more front). */
	private fun frontRankMap(): Map<DrawableId, Float> =
		renderer.drawnOrder().withIndex().associate { (index, id) -> id to index.toFloat() }

	/** The drawable's atlas page size (width, height) in texels, or null when it is untextured. */
	private fun atlasSizeOf(id: DrawableId): Pair<Int, Int>? =
		textures.atlasIndexByDrawableId[atlasKeyByDrawableId[id] ?: id.raw]?.let { atlasIndex ->
			textures.atlases.getOrNull(atlasIndex)?.let { image -> image.width to image.height }
		}

	/**
	 * A small preview of a drawable's art for the overlap-picker popup, delegated to the shared
	 * [DrawableThumbnailer] (the same provider the Outliner hover uses). Returns null for an untextured or
	 * mesh-less drawable, which the popup renders as a label-only row.
	 *
	 * ドロウアブルのアートのサムネイル。共有のサムネイル生成器に委譲する。
	 *
	 * @param DrawableId id The drawable to preview.
	 * @return ImageBitmap? The cropped preview, or null when the drawable is untextured or mesh-less.
	 */
	override fun thumbnailFor(id: DrawableId): ImageBitmap? = thumbnailer.thumbnailFor(id)

	/** The shared art-mesh thumbnail provider, exposed so the host can wire it to the Outliner hover preview. */
	override fun thumbnails(): DrawableThumbnailProvider = thumbnailer

	/**
	 * The name of the part a drawable belongs to, for the overlap-picker row labels, or null when the
	 * drawable sits at the root with no owning part.
	 *
	 * ドロウアブルが属するパート名（重なり選択の行ラベル用）、ルート直下なら null。
	 *
	 * @param DrawableId id The drawable to look up.
	 * @return String The owning part's name, or null.
	 */
	override fun partNameFor(id: DrawableId): String? = partNameByDrawableId[id]

	/**
	 * Hit-tests a viewport pixel against the current deformed pose and returns the FRONT-MOST opaque
	 * drawable under it, or null when the click misses or no pose/camera is ready yet. Runs on the UI
	 * thread: it reuses the camera's screen-to-world inverse and the renderer's pure CPU pick geometry,
	 * so it never touches GL. Restricted to shown, meshed drawables; front/back uses the renderer's
	 * resolved draw order, and the transparent triangle overhang is rejected by the atlas-alpha gate.
	 *
	 * ビューポートのピクセルを現在ポーズに当てて最前面の不透明ドロウアブルを返す（UI スレッド、GL 不使用）。
	 *
	 * @param String areaId    The area being clicked.
	 * @param Float  cursorXpx Cursor X in pixels (top-left origin).
	 * @param Float  cursorYpx Cursor Y in pixels (top-left origin).
	 * @return DrawableId The hit drawable, or null on a miss.
	 */
	override fun pickAt(areaId: String, cursorXpx: Float, cursorYpx: Float): DrawableId? {
		val slot = areas[areaId] ?: return null
		val camera = slot.camera ?: return null
		val width = slot.width
		val height = slot.height
		if (width <= 0 || height <= 0) {
			return null
		}
		val geometry = renderer.pickGeometry() ?: return null
		// Screen (y-down) to world (y-up), the inverse of the camera's worldToNdc — see ScreenSpacePick.
		val worldX = screenToWorldX(cursorXpx, camera, width)
		val worldY = screenToWorldY(cursorYpx, camera, height)
		return pickDrawable(
			worldX,
			worldY,
			geometry.worldPositions,
			pickableIndices,
			pickableUvs,
			frontRankMap(),
			::sampleTexelAlpha,
		)
	}

	/**
	 * All opaque drawables under a viewport pixel, FRONT-TO-BACK with each one's centrality — for the
	 * Alt-click overlap-picker popup. Same screen-to-world, hit, and alpha gate as [pickAt].
	 *
	 * カーソル下の不透明ドロウアブルすべてを前面順に返す（重なり選択ポップアップ用）。
	 *
	 * @param String areaId    The area being clicked.
	 * @param Float  cursorXpx Cursor X in pixels (top-left origin).
	 * @param Float  cursorYpx Cursor Y in pixels (top-left origin).
	 * @return List the opaque candidates, front-to-back.
	 */
	override fun pickAllAt(areaId: String, cursorXpx: Float, cursorYpx: Float): List<PickCandidate> {
		val slot = areas[areaId] ?: return emptyList()
		val camera = slot.camera ?: return emptyList()
		val width = slot.width
		val height = slot.height
		if (width <= 0 || height <= 0) {
			return emptyList()
		}
		val geometry = renderer.pickGeometry() ?: return emptyList()
		val worldX = screenToWorldX(cursorXpx, camera, width)
		val worldY = screenToWorldY(cursorYpx, camera, height)
		return pickAllDrawables(
			worldX,
			worldY,
			geometry.worldPositions,
			pickableIndices,
			pickableUvs,
			frontRankMap(),
			::atlasSizeOf,
			::sampleTexelAlpha,
		)
	}

	/**
	 * Every visible drawable's world-space centroid at the current pose, for Object-mode region select.  Reads
	 * the same posed pick geometry as [pickAt] (UI thread, no GL) and reduces each drawable to its centroid;
	 * empty before the first frame's geometry lands.  Area-independent - the caller projects with its camera.
	 *
	 * @return Map the per-drawable [x, y] world centroid.
	 */
	override fun drawableWorldCentroids(): Map<DrawableId, FloatArray> {
		val geometry = renderer.pickGeometry() ?: return emptyMap()
		return drawableCentroids(geometry.worldPositions)
	}

	/**
	 * Applies [transform] to the area's current camera and publishes the result. No-op before the initial
	 * fit (there is nothing to transform until the first frame establishes the view).
	 *
	 * @param String   areaId    The area id.
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

	/**
	 * Releases one hold on an area; the slot is dropped only when the last holder leaves (ref-count to
	 * zero). This guards the tree-collapse case: closing one split sibling rebuilds the surviving area's
	 * leaf under a fresh composition node, so its [register] runs BEFORE the old leaf's unregister - a
	 * plain remove here would delete the slot the rebuilt viewport just bound to, freezing it at a stale
	 * (stretched) frame. Any read-back already in flight for a truly-dropped slot still completes and is
	 * discarded when collected (the slot is gone).
	 *
	 * @param String areaId The area id to release.
	 */
	override fun unregister(areaId: String) {
		val slot = areas[areaId] ?: return
		slot.refCount--
		if (slot.refCount <= 0) {
			areas.remove(areaId)
		}
	}

	/** Stops the render thread and releases the GL context. Blocks briefly to join. */
	override fun dispose() {
		running = false
		renderThread.join(2000)
	}

	/**
	 * The content rectangle an area's camera fits: the puppet's rest-pose bounds for a 2D area, or the
	 * atlas page rectangle for a UV-editor area.  Render thread only (reads the renderer's bounds).
	 *
	 * @param AreaSlot slot The area being framed.
	 * @return ContentBounds The rectangle to fit.
	 */
	private fun contentBoundsFor(slot: AreaSlot): ContentBounds =
		when (slot.scene) {
			RenderScene.Puppet2D -> renderer.contentBounds()
			RenderScene.AtlasPage -> pageContentBounds(slot.pageIndex)
		}

	/**
	 * The atlas page rectangle (0, 0, pageWidth, pageHeight) for the UV-editor fit, or a unit square when
	 * the page is missing (an untextured active drawable) so the fit stays sane and the grid still frames.
	 *
	 * @param Int pageIndex The atlas page, or null for none.
	 * @return ContentBounds The page rectangle, in texel/display units.
	 */
	private fun pageContentBounds(pageIndex: Int?): ContentBounds {
		val page = pageIndex?.let { textures.atlases.getOrNull(it) }
		return if (page != null) {
			ContentBounds(0f, 0f, page.width.toFloat(), page.height.toFloat())
		} else {
			ContentBounds(0f, 0f, 1f, 1f)
		}
	}

	/**
	 * The render thread body: create the context, then loop - collect finished read-backs, issue new
	 * renders for changed areas, and idle when there is nothing to do.
	 */
	private fun renderLoop() {
		if (!createContext()) {
			UmamoLog.warn("[GL] offscreen context unavailable; viewport will stay blank")
			return
		}
		try {
			var lastParams: Map<ParameterId, Float>? = null
			var lastShown: Set<DrawableId>? = null
			var lastModel: PuppetModel? = null
			var paramsVersion = 0L
			while (running) {
				collectCompletedReadbacks()
				val params = liveParams.values
				val shown = shownBacking
				val orderModel = modelBacking
				// Rebuild the pose - and thus the draw list, which setPose filters by the shown set and sorts by
				// the render order - when the pose, the visibility cascade, OR the render order changes. A
				// visibility toggle or a layer reorder leaves the params untouched, so without these checks the
				// draw list would never refresh. setShownDrawables / setRenderOrder run first so setPose uses them.
				if (params !== lastParams || shown !== lastShown || orderModel !== lastModel) {
					renderer.setShownDrawables(shown)
					if (orderModel !== lastModel) {
						// Re-point the renderer at the edited model so the next setPose re-derives the draw order
						// and (for a deformer reparent) the deform chain.
						renderer.updateModel(orderModel)
						lastModel = orderModel
					}
					renderer.setPose(params)
					lastParams = params
					lastShown = shown
					paramsVersion++
				}
				var pendingWork = inFlight.isNotEmpty()
				for ((areaId, slot) in areas) {
					val width = slot.width
					val height = slot.height
					if (width <= 0 || height <= 0) {
						continue
					}
					if (slot.inFlight) {
						pendingWork = true
						continue // one read-back per area in flight; coalesces a flurry of slider moves
					}
					// Establish or refit the camera now that the size is known - the render thread owns the
					// content bounds. A pending refit forces a fresh fit; otherwise a freshly-(re)registered
					// area restores its remembered camera (so workspace switches preserve pan/zoom), falling
					// back to a fit the first time it is ever shown. The UI thread mutates it thereafter.
					var camera = slot.camera
					if (slot.refitRequested || camera == null) {
						camera =
							if (slot.refitRequested) {
								ViewportCamera.fit(contentBoundsFor(slot), width, height)
							} else {
								rememberedCameras[areaId] ?: ViewportCamera.fit(contentBoundsFor(slot), width, height)
							}
						slot.refitRequested = false
						slot.camera = camera
						slot.cameraState.value = camera
						rememberedCameras[areaId] = camera
					}
					// An atlas page is model-independent, so its freshness ignores the pose version and puppetRenderBump
					// (a UV edit's setModel stream re-renders the sibling puppet, not the flat page): it re-renders
					// only on size / camera / pageIndex, plus the grid colors and geometry it draws its backdrop with -
					// tracked by atlasRenderBump (the grid setters bump it), so a theme switch or grid-config change
					// still repaints the page.  The puppet keeps the full freshness via puppetRenderBump.
					val fresh =
						when (slot.scene) {
							RenderScene.Puppet2D ->
								slot.renderedWidth == width &&
									slot.renderedHeight == height &&
									slot.renderedParamsVersion == paramsVersion &&
									slot.renderedCamera === camera &&
									slot.puppetRenderBumpDone == puppetRenderBump

							RenderScene.AtlasPage ->
								slot.renderedWidth == width &&
									slot.renderedHeight == height &&
									slot.renderedPageIndex == slot.pageIndex &&
									slot.renderedCamera === camera &&
									slot.atlasRenderBumpDone == atlasRenderBump
						}
					if (fresh) {
						continue
					}
					issueRender(areaId, slot, width, height, paramsVersion, camera, orderModel)
					pendingWork = true
				}
				if (!pendingWork) {
					Thread.sleep(IDLE_MILLIS)
				} else if (inFlight.isNotEmpty()) {
					Thread.sleep(BUSY_MILLIS)
				}
			}
		} finally {
			destroyContext()
		}
	}

	/**
	 * Renders [slot] at [width]×[height] into the FBO, then kicks off an asynchronous read-back into a
	 * PBO gated by a fence. Marks the slot in-flight; the result is posted later by
	 * [collectCompletedReadbacks].
	 *
	 * @param String areaId The area's id.
	 * @param AreaSlot slot The area being rendered.
	 * @param Int width The render width in pixels.
	 * @param Int height The render height in pixels.
	 * @param Long paramsVersion The pose version this render reflects.
	 * @param ViewportCamera camera The view to project through.
	 * @param PuppetModel orderModel The model whose geometry this render reflects (stamped onto the frame).
	 */
	private fun issueRender(
		areaId: String,
		slot: AreaSlot,
		width: Int,
		height: Int,
		paramsVersion: Long,
		camera: ViewportCamera,
		orderModel: PuppetModel,
	) {
		val renderWidth = width * RENDER_SUPERSAMPLE
		val renderHeight = height * RENDER_SUPERSAMPLE

		ensureFramebuffer(width, height)

		// Supersample: render the whole pipeline (puppet, clip masks, grid) into the RENDER_SUPERSAMPLE×
		// draw buffer, then box-downscale to display size on resolve. The camera zoom and the grid line width
		// scale by the same factor so the framing and backdrop are unchanged after the downscale. render()
		// targets the currently-bound framebuffer (the draw FBO ensureFramebuffer bound); downscaleResolve
		// leaves the display-size `framebuffer` bound for the read-back (the draw buffer can't be read back).
		renderer.setRenderScale(RENDER_SUPERSAMPLE.toFloat())

		// Capture the backdrop version applied to this render so the freshness check below stamps the version
		// actually drawn; a color change after this point bumps it again and re-renders next iteration.
		val puppetRenderBumpDone = puppetRenderBump
		val atlasRenderBumpDone = atlasRenderBump

		val gridConfigApplied = gridConfigBacking
		renderer.setGrid(gridColorsBacking, gridConfigApplied.scale, gridConfigApplied.subdivisions)
		renderer.setSelection(selectionBacking)

		// The shown set is applied in the render-loop pose block (before setPose, which filters the draw list by it), so it is not re-pushed here.
		renderer.setSelectionHighlightColor(highlightRed, highlightGreen, highlightBlue)
		renderer.setCamera(camera.copy(zoom = camera.zoom * RENDER_SUPERSAMPLE))

		when (slot.scene) {
			RenderScene.Puppet2D -> renderer.render(renderWidth, renderHeight)
			// A UV area draws the flat atlas page instead; the pose / selection / shown state pushed above are
			// harmless no-ops for it (renderAtlasPage reads none of them - just the grid + the page quad).
			RenderScene.AtlasPage -> renderer.renderAtlasPage(slot.pageIndex, renderWidth, renderHeight)
		}

		downscaleResolve(renderWidth, renderHeight, width, height)

		if (!dumped) {
			System.getenv("UMAMO_DUMP_PNG")?.let { dumpPath ->
				// dumpPng does a synchronous client read-back; safe here because no PBO is bound yet.
				renderer.dumpPng(dumpPath, width, height)
				dumped = true
				UmamoLog.info("[GL] puppet dumped to $dumpPath (${width}x$height)")
			}
		}

		val pbo = acquirePbo(width * height * 4)

		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo.id)
		// With a PBO bound, the last argument is a BUFFER OFFSET, not a client pointer - glReadPixels
		// returns immediately and the copy happens asynchronously on the GPU timeline.
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)

		val fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
		GL11.glFlush() // make sure the commands + fence are submitted so the fence can eventually signal

		// Bind the frame to the camera it was rendered at (the plain, non-supersampled camera - the
		// final width*height bitmap frames exactly as this) and to orderModel, the geometry this render
		// reflects (uploaded above by the render-loop pose block, so it is exactly what these pixels
		// show). The overlay projects through the camera and poses from the model, keeping the mesh
		// glued to the raster along both the navigation and edit axes.
		inFlight.addLast(Readback(areaId, width, height, pbo, fence, camera, orderModel))
		slot.inFlight = true
		slot.renderedWidth = width
		slot.renderedHeight = height
		slot.renderedParamsVersion = paramsVersion
		slot.renderedCamera = camera
		slot.puppetRenderBumpDone = puppetRenderBumpDone
		slot.atlasRenderBumpDone = atlasRenderBumpDone
		slot.renderedPageIndex = slot.pageIndex
	}

	/**
	 * Collects every read-back whose fence has signaled (in submission order), maps its PBO without
	 * blocking, builds the ImageBitmap, posts it to the area, and recycles the PBO. Stops at the first
	 * unsignaled fence (later ones cannot be done before it).
	 */
	private fun collectCompletedReadbacks() {
		while (inFlight.isNotEmpty()) {
			val readback = inFlight.first()
			val status = GL32.glClientWaitSync(readback.fence, 0, 0L)
			if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
				break
			}
			inFlight.removeFirst()
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readback.pbo.id)
			val mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY)
			val bitmap = mapped?.let { buffer -> pixelsToImageBitmap(readback.width, readback.height, buffer) }
			GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
			GL32.glDeleteSync(readback.fence)
			freePbos.addLast(readback.pbo)
			val slot = areas[readback.areaId]
			if (slot != null) {
				slot.inFlight = false
				if (bitmap != null) {
					slot.imageState.value = RenderedFrame(bitmap, readback.camera, readback.model)
				}
			}
		}
	}

	/**
	 * Acquires a PBO with at least [capacity] bytes from the free pool (allocating/growing as needed)
	 * and leaves it bound. Uses GL_STREAM_READ since it is written by GL and read once by the client.
	 *
	 * @param Int capacity The minimum byte capacity needed.
	 * @return Pbo The acquired PBO.
	 */
	private fun acquirePbo(capacity: Int): Pbo {
		val pbo = freePbos.pollFirst() ?: Pbo(GL15.glGenBuffers(), 0)
		if (pbo.capacity < capacity) {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo.id)
			GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, capacity.toLong(), GL15.GL_STREAM_READ)
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
			pbo.capacity = capacity
		}
		return pbo
	}

	/**
	 * Creates the GLFW hidden-window GL 3.3 core context on this thread, initializes the renderer, and
	 * allocates the reusable FBO color texture. Returns false (degrading to a blank viewport) if GL is
	 * unavailable.
	 *
	 * @return Boolean true on success.
	 */
	private fun createContext(): Boolean {
		if (!GLFW.glfwInit()) {
			return false
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
		window = GLFW.glfwCreateWindow(1, 1, "umamo-offscreen", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			return false
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		UmamoLog.info("[GL] offscreen ${GpuRenderer().describeContext()}")
		renderer.initGl()
		// Supersampled draw target + display-size resolve target: the puppet renders into the larger draw
		// buffer and is box-downscaled into the resolve texture for read-back (see RENDER_SUPERSAMPLE).
		drawFramebuffer = GL30.glGenFramebuffers()
		drawColorRenderbuffer = GL30.glGenRenderbuffers()
		framebuffer = GL30.glGenFramebuffers()
		colorTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		return true
	}

	/**
	 * Releases the FBO, the PBO pool + in-flight syncs, and the GLFW window/context. Runs on the render
	 * thread, where the context is current.
	 *
	 * glFinish() first so the driver completes all pending GPU work BEFORE we delete the objects and
	 * destroy the context - otherwise a driver worker thread can be mid-copy on memory we free, which
	 * crashed (SIGSEGV in libc memcpy) on a clean window close. glfwTerminate() is deliberately NOT
	 * called: it is a main-thread-only global teardown that frees GLFW's own X connection, and calling
	 * it from this worker thread during app shutdown is the other half of that race. GLFW stays
	 * initialized for the process lifetime (idempotent across documents); the OS reclaims it on exit.
	 */
	private fun destroyContext() {
		GL11.glFinish()
		for (readback in inFlight) {
			GL32.glDeleteSync(readback.fence)
			GL15.glDeleteBuffers(readback.pbo.id)
		}
		inFlight.clear()
		for (pbo in freePbos) {
			GL15.glDeleteBuffers(pbo.id)
		}
		freePbos.clear()
		if (framebuffer != 0) {
			GL30.glDeleteFramebuffers(framebuffer)
			GL11.glDeleteTextures(colorTexture)
			GL30.glDeleteFramebuffers(drawFramebuffer)
			GL30.glDeleteRenderbuffers(drawColorRenderbuffer)
		}
		if (window != MemoryUtil.NULL) {
			GLFW.glfwMakeContextCurrent(MemoryUtil.NULL)
			GLFW.glfwDestroyWindow(window)
		}
	}

	/**
	 * Ensures the FBO exists and its attachments match [width]×[height], reallocating on a size change,
	 * and binds it as the draw target.
	 *
	 * @param Int width The target width.
	 * @param Int height The target height.
	 */
	private fun ensureFramebuffer(width: Int, height: Int) {
		if (width != framebufferWidth || height != framebufferHeight) {
			// Supersampled color for the draw target - the renderer draws here at RENDER_SUPERSAMPLE×.
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFramebuffer)
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, drawColorRenderbuffer)
			GL30.glRenderbufferStorage(
				GL30.GL_RENDERBUFFER,
				GL11.GL_RGBA8,
				width * RENDER_SUPERSAMPLE,
				height * RENDER_SUPERSAMPLE,
			)
			GL30.glFramebufferRenderbuffer(
				GL30.GL_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0,
				GL30.GL_RENDERBUFFER,
				drawColorRenderbuffer,
			)
			// Display-size color for the resolve target - the draw buffer is downscaled here, then read back.
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
			GL11.glTexImage2D(
				GL11.GL_TEXTURE_2D,
				0,
				GL11.GL_RGBA8,
				width,
				height,
				0,
				GL11.GL_RGBA,
				GL11.GL_UNSIGNED_BYTE,
				null as ByteBuffer?,
			)
			GL30.glFramebufferTexture2D(
				GL30.GL_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D,
				colorTexture,
				0,
			)
			framebufferWidth = width
			framebufferHeight = height
		}
		// Draw into the supersampled FBO; issueRender downscales it into `framebuffer` before the read-back.
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFramebuffer)
	}

	/**
	 * Downscales the supersampled draw target into the display-size [framebuffer] (the read-back source) with
	 * a linear box blit - the supersample resolve that anti-aliases geometry edges and clip masks alike.
	 * Leaves [framebuffer] bound for the read-back that follows.
	 *
	 * @param Int renderWidth  The supersampled draw width.
	 * @param Int renderHeight The supersampled draw height.
	 * @param Int width        The display width.
	 * @param Int height       The display height.
	 */
	private fun downscaleResolve(renderWidth: Int, renderHeight: Int, width: Int, height: Int) {
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer)
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
		GL30.glBlitFramebuffer(
			0,
			0,
			renderWidth,
			renderHeight,
			0,
			0,
			width,
			height,
			GL11.GL_COLOR_BUFFER_BIT,
			GL11.GL_LINEAR,
		)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
	}

	/**
	 * Converts a bottom-up RGBA read-back into a top-down, opaque Compose [ImageBitmap]. GL returns rows
	 * bottom-to-top, so rows are flipped (matching dumpPng).
	 *
	 * The flip is a bulk copy per row - `ByteBuffer.get(byte[], …)` is one memcpy out of the mapped PBO
	 * (the direct-buffer equivalent of System.arraycopy, which can't take a ByteBuffer source), far
	 * cheaper than the per-byte absolute reads it replaces. Alpha is then forced opaque in a separate
	 * sequential pass: the preview background is already composited into RGB, so a constant alpha keeps
	 * the image opaque without depending on the framebuffer's alpha channel.
	 *
	 * @param Int width The image width.
	 * @param Int height The image height.
	 * @param ByteBuffer pixels The bottom-up RGBA read-back (a mapped PBO).
	 * @return ImageBitmap The display bitmap.
	 */
	private fun pixelsToImageBitmap(width: Int, height: Int, pixels: ByteBuffer): ImageBitmap {
		val rowBytes = width * 4
		val topDown = ByteArray(rowBytes * height)
		for (row in 0 until height) {
			pixels.position((height - 1 - row) * rowBytes)
			pixels.get(topDown, row * rowBytes, rowBytes)
		}
		var alphaIndex = 3
		while (alphaIndex < topDown.size) {
			topDown[alphaIndex] = 255.toByte()
			alphaIndex += 4
		}
		val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
		return SkiaImage.makeRaster(info, topDown, rowBytes).toComposeImageBitmap()
	}
}
