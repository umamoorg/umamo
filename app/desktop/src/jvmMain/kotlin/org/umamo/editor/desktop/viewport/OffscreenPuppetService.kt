package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow
import org.umamo.edit.GridConfig
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.RenderedFrame

/**
 * Renders the puppet OFF-SCREEN and publishes it as a Compose ImageBitmap per viewport area.
 * Because the viewport is then lightweight Compose content, the UI layers over it correctly on every platform.
 *
 * This is the desktop implementation of [PuppetViewportService]. It is a thin facade that composes the
 * pieces and forwards each call to the collaborator that owns it:
 *
 *   - [ViewportAreaRegistry] - the registered areas + their cameras (register / resize / navigation), on the
 *     UI thread.
 *   - [OffscreenRenderEngine] - the render thread that owns the GL context, renderer, framebuffers, and
 *     async read-back, plus the render-input state (selection / shown / model / grid / highlight colors).
 *   - [ViewportPicker] - CPU hit-testing and art thumbnails, on the UI thread (no GL).
 *
 * The GL context is chosen per OS behind [OffscreenGlContext]: a hidden GLFW window on Windows / Linux, CGL
 * on MacOS.
 *
 * Threading: every method here is called on the UI thread; the engine publishes frames from its render
 * thread via the areas' StateFlows. Picking is CPU-side and synchronous.
 *
 * @property PuppetModel puppet The rig to render.
 * @property PuppetTextures textures The atlas page(s).
 * @property LiveParams liveParams The shared parameter hand-off (drives re-render on change).
 */
class OffscreenPuppetService(
	puppet: PuppetModel,
	textures: PuppetTextures,
	liveParams: LiveParams,
) : PuppetViewportService {
	private val registry = ViewportAreaRegistry()
	private val engine = OffscreenRenderEngine(puppet, textures, liveParams, registry)

	// The picker shares the engine's renderer for its pure-CPU pickGeometry()/drawnOrder() snapshots (the
	// same instance the render thread draws with - those reads are CPU-only and UI-thread-safe).
	private val picker = ViewportPicker(engine.puppetRenderer, textures, puppet)

	/** Starts the render thread (call once, after construction). */
	fun start() {
		engine.start()
	}

	// The viewport the pointer is over, so keyboard view commands (fit / 1:1 / zoom) target it. Dispatch-time
	// only, UI thread; a plain non-reactive var per the interface contract.
	@Volatile
	override var activeAreaId: String? = null

	override var zoomStepPercent: Float
		get() = registry.zoomStepPercent
		set(value) {
			registry.zoomStepPercent = value
		}

	override var zoomStepCoarsePercent: Float
		get() = registry.zoomStepCoarsePercent
		set(value) {
			registry.zoomStepCoarsePercent = value
		}

	override var gridColors: GridColors
		get() = engine.gridColors
		set(value) {
			engine.gridColors = value
		}

	override var gridConfig: GridConfig
		get() = engine.gridConfig
		set(value) {
			engine.gridConfig = value
		}

	override fun register(areaId: String): StateFlow<RenderedFrame?> = registry.register(areaId)

	override fun registerAtlasPage(areaId: String, pageIndex: Int?): StateFlow<RenderedFrame?> =
		registry.registerAtlasPage(areaId, pageIndex)

	override fun setAtlasPageIndex(areaId: String, pageIndex: Int?) = registry.setAtlasPageIndex(areaId, pageIndex)

	override fun unregister(areaId: String) = registry.unregister(areaId)

	override fun cameraFlow(areaId: String): StateFlow<ViewportCamera?> = registry.cameraFlow(areaId)

	override fun resize(areaId: String, width: Int, height: Int) = registry.resize(areaId, width, height)

	override fun pan(areaId: String, deltaXpx: Float, deltaYpx: Float) = registry.pan(areaId, deltaXpx, deltaYpx)

	override fun zoomAtCursor(areaId: String, zoomIn: Boolean, coarse: Boolean, cursorXpx: Float, cursorYpx: Float) =
		registry.zoomAtCursor(areaId, zoomIn, coarse, cursorXpx, cursorYpx)

	override fun zoomCentered(areaId: String, zoomIn: Boolean, coarse: Boolean) =
		registry.zoomCentered(areaId, zoomIn, coarse)

	override fun zoomToRegion(areaId: String, leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float) =
		registry.zoomToRegion(areaId, leftPx, topPx, rightPx, bottomPx)

	override fun actualSize(areaId: String) = registry.actualSize(areaId)

	override fun fit(areaId: String) = registry.fit(areaId)

	override fun fitWorldRect(areaId: String, minX: Float, minY: Float, maxX: Float, maxY: Float) =
		registry.fitWorldRect(areaId, minX, minY, maxX, maxY)

	override fun setSelection(ids: Set<DrawableId>) = engine.setSelection(ids)

	override fun setActiveSelection(id: DrawableId?) = engine.setActiveSelection(id)

	override fun setShownDrawables(ids: Set<DrawableId>) = engine.setShownDrawables(ids)

	/**
	 * Pushes the latest model to the render engine and, when it actually changed, rebuilds the picker's
	 * model-derived lookup maps so session-created drawables stay pickable / sampleable / labeled.
	 *
	 * @param PuppetModel model The current model.
	 */
	override fun setModel(model: PuppetModel) {
		if (engine.setModel(model)) {
			picker.updateModel(model)
		}
	}

	override fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) =
		engine.setSelectionHighlightColor(red, green, blue)

	override fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) =
		engine.setActiveSelectionHighlightColor(red, green, blue)

	override fun pickAt(areaId: String, cursorXpx: Float, cursorYpx: Float): DrawableId? {
		val view = registry.viewFor(areaId) ?: return null
		return picker.pickAt(view.camera, view.width, view.height, cursorXpx, cursorYpx)
	}

	override fun pickAllAt(areaId: String, cursorXpx: Float, cursorYpx: Float): List<PickCandidate> {
		val view = registry.viewFor(areaId) ?: return emptyList()
		return picker.pickAllAt(view.camera, view.width, view.height, cursorXpx, cursorYpx)
	}

	override fun drawableWorldCentroids(): Map<DrawableId, FloatArray> = picker.drawableWorldCentroids()

	override fun thumbnailFor(id: DrawableId): ImageBitmap? = picker.thumbnailFor(id)

	override fun thumbnails(): DrawableThumbnailProvider = picker.thumbnails()

	override fun partNameFor(id: DrawableId): String? = picker.partNameFor(id)

	override fun dispose() = engine.dispose()
}
