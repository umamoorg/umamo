package org.umamo.ui.viewport

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

/**
 * Creates (and starts) the platform render service for one open document. Desktop supplies the
 * offscreen GLFW/LWJGL engine; Android supplies its GLES sibling. Injected into
 * rememberPuppetViewportHost so the whole viewport composable stack stays common.
 */
typealias PuppetViewportServiceFactory = (PuppetModel, PuppetTextures, LiveParams) -> PuppetViewportService

/**
 * A rendered puppet frame together with the camera AND the model it was rendered from. The engine
 * renders asynchronously, so a frame lands a few ticks behind the live state; the overlay draws itself
 * as a pure function of this frame - projecting through [camera] and posing its geometry from [model] -
 * so the vector overlay stays glued to the raster along both the navigation axis (pan/zoom, the camera)
 * and the edit axis (mesh geometry, the model) instead of racing ahead of it. Bundling all three in one
 * immutable value keeps the publish atomic, so the bitmap, its camera, and its geometry can never tear
 * apart across the frame flow.
 *
 * @property ImageBitmap bitmap The area's rendered pixels (already downscaled to the area size).
 * @property ViewportCamera camera The camera the pixels were rendered with.
 * @property PuppetModel model The model whose geometry the pixels reflect (the overlay poses from it so
 *           the wireframe lags with the raster during an edit instead of leading it).
 */
data class RenderedFrame(val bitmap: ImageBitmap, val camera: ViewportCamera, val model: PuppetModel)

/**
 * The platform seam between the common viewport UI and a puppet render engine. The engine owns a GPU
 * context on its own thread, renders the posed puppet per registered area, and publishes each frame as
 * a Compose ImageBitmap; the common `Viewport2D` composable, the navigation/pick pointer loop, and the
 * Edit-mode gizmo overlay drive it exclusively through this interface. Desktop implements it with an
 * offscreen GLFW/LWJGL service; Android's GLES sibling implements the same surface when it lands.
 *
 * Threading contract: everything here is called from the UI thread; implementations publish to their
 * render thread internally (volatile swaps of immutable values). Picking is CPU-side and synchronous.
 *
 * 共通ビューポート UI とレンダエンジンの継ぎ目。デスクトップはオフスクリーン GL、Android は GLES 実装が
 * この同じ面を実装する。呼び出しは UI スレッド、公開は実装側の volatile スワップで行う。
 */
interface PuppetViewportService {
	/**
	 * The viewport the pointer last addressed (written by every viewport's navigation loop; never
	 * cleared, so it means "last touched", null only before any viewport was ever entered).  Keyboard
	 * view commands (fit / 1:1 / zoom) target it, and latching commands resolve it ONCE as a gesture's
	 * initiating area.
	 *
	 * Contract: DISPATCH-TIME ONLY.  Read it inside command handlers and request-bus collect bodies at
	 * event time - never during composition.  It is a plain non-reactive var, so a composition-time
	 * gate would go stale without recomposing; composition gates key off a latch's own area id instead
	 * (ActiveOperator.areaId, ActiveSelectTool.areaId, zoomRegionArmedArea).
	 */
	var activeAreaId: String?

	/** The fine zoom increment in percentage points (one wheel notch / key press), fed from settings. */
	var zoomStepPercent: Float

	/** The coarse (Shift-held) zoom increment in percentage points, fed from settings. */
	var zoomStepCoarsePercent: Float

	/** The grid backdrop colors (background / major / minor), fed from the editor theme; a change forces a redraw. */
	var gridColors: GridColors

	/** The per-document grid geometry (major spacing + subdivisions), fed from the session; a change forces a redraw. */
	var gridConfig: GridConfig

	/**
	 * Registers a viewport area and returns the flow its rendered frames arrive on. Reference-counted
	 * per [areaId]: register/unregister pair across the area's composable life.
	 *
	 * @param String areaId The hosting area's stable id.
	 * @return StateFlow The frame flow (null until the first render lands); each frame carries the
	 *         camera it was rendered at so the overlay can reproject it against the live camera.
	 */
	fun register(areaId: String): StateFlow<RenderedFrame?>

	/**
	 * Registers an ATLAS-PAGE area - the UV editor's flat page-under-wireframe - rather than the posed
	 * puppet.  Same reference-counted lifecycle and [RenderedFrame] flow as [register] (the frame's bitmap
	 * is the rendered page, its camera the one it was rendered at), driven by the same per-area camera
	 * surface ([cameraFlow] / [resize] / [pan] / [zoomAtCursor] / [zoomCentered] / [fit] / [fitWorldRect] /
	 * [actualSize] / [unregister]) - only the rendered content differs.  [pageIndex] indexes the document's
	 * atlas pages; null (an untextured active drawable) renders the grid only.  Keep it current with
	 * [setAtlasPageIndex] as the UV editor's active drawable (hence its page) changes.  The engine fits the
	 * page rectangle rather than the puppet content bounds.
	 *
	 * @param String areaId The hosting UV-editor area's stable id.
	 * @param Int pageIndex The atlas page to draw, or null for none (grid only).
	 * @return StateFlow The frame flow (null until the first render lands).
	 */
	fun registerAtlasPage(areaId: String, pageIndex: Int?): StateFlow<RenderedFrame?>

	/**
	 * Retargets the atlas page an already-registered [registerAtlasPage] area renders, as the UV editor's
	 * active drawable changes.  A no-op for an unregistered area or a puppet (2D) area.
	 *
	 * @param String areaId The atlas-page area to retarget.
	 * @param Int pageIndex The new atlas page, or null for none (grid only).
	 */
	fun setAtlasPageIndex(areaId: String, pageIndex: Int?)

	/**
	 * Releases one registration of [areaId]; the engine drops the area's resources at zero.
	 *
	 * @param String areaId The area to release.
	 */
	fun unregister(areaId: String)

	/**
	 * The flow of [areaId]'s pan/zoom camera (null until the first fit), for readouts and overlays.
	 *
	 * @param String areaId The area whose camera to observe.
	 * @return StateFlow The camera flow.
	 */
	fun cameraFlow(areaId: String): StateFlow<ViewportCamera?>

	/**
	 * Reports [areaId]'s current size in pixels; the engine re-renders at the new size.
	 *
	 * @param String areaId The resized area.
	 * @param Int width The new width in pixels.
	 * @param Int height The new height in pixels.
	 */
	fun resize(areaId: String, width: Int, height: Int)

	/**
	 * Pans [areaId]'s camera by a pointer delta in viewport pixels.
	 *
	 * @param String areaId The panned area.
	 * @param Float deltaXpx The horizontal delta in pixels.
	 * @param Float deltaYpx The vertical delta in pixels.
	 */
	fun pan(areaId: String, deltaXpx: Float, deltaYpx: Float)

	/**
	 * Zooms [areaId] one step toward/away from the cursor position (the wheel gesture).
	 *
	 * @param String areaId The zoomed area.
	 * @param Boolean zoomIn True to zoom in, false to zoom out.
	 * @param Boolean coarse True for the coarse (Shift) step.
	 * @param Float cursorXpx The cursor x in viewport pixels (the zoom anchor).
	 * @param Float cursorYpx The cursor y in viewport pixels (the zoom anchor).
	 */
	fun zoomAtCursor(areaId: String, zoomIn: Boolean, coarse: Boolean, cursorXpx: Float, cursorYpx: Float)

	/**
	 * Zooms [areaId] one step about its centre (the keyboard/menu gesture).
	 *
	 * @param String areaId The zoomed area.
	 * @param Boolean zoomIn True to zoom in, false to zoom out.
	 * @param Boolean coarse True for the coarse (Shift) step.
	 */
	fun zoomCentered(areaId: String, zoomIn: Boolean, coarse: Boolean)

	/**
	 * Frames a screen-pixel rectangle in [areaId], filling the viewport with the dragged box (the Zoom
	 * Region / Shift+B gesture).  Mode-agnostic - the camera does not distinguish Object from Edit mode.
	 *
	 * @param String areaId The zoomed area.
	 * @param Float leftPx One horizontal box edge in viewport pixels.
	 * @param Float topPx One vertical box edge in viewport pixels.
	 * @param Float rightPx The other horizontal box edge in viewport pixels.
	 * @param Float bottomPx The other vertical box edge in viewport pixels.
	 */
	fun zoomToRegion(areaId: String, leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float)

	/**
	 * Sets [areaId]'s camera to 1:1 (one content unit per pixel), centred.
	 *
	 * @param String areaId The area to reset.
	 */
	fun actualSize(areaId: String)

	/**
	 * Fits the puppet's content bounds inside [areaId], centred.
	 *
	 * @param String areaId The area to fit.
	 */
	fun fit(areaId: String)

	/**
	 * Fits a world-space rectangle inside [areaId], centred - the Frame Selected camera move (the
	 * caller computes the selection's world bounds; this only frames them).
	 *
	 * @param String areaId The area to frame in.
	 * @param Float minX The rectangle's minimum world x.
	 * @param Float minY The rectangle's minimum world y.
	 * @param Float maxX The rectangle's maximum world x.
	 * @param Float maxY The rectangle's maximum world y.
	 */
	fun fitWorldRect(areaId: String, minX: Float, minY: Float, maxX: Float, maxY: Float)

	/**
	 * Pushes the selected drawables; the engine re-renders their tint.
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	fun setSelection(ids: Set<DrawableId>)

	/**
	 * Pushes the drawables actually shown (the resolved Parts-panel visibility cascade).
	 *
	 * @param Set<DrawableId> ids The shown drawable ids.
	 */
	fun setShownDrawables(ids: Set<DrawableId>)

	/**
	 * Pushes the latest model after a committed edit or undo/redo (draw order, mesh preview, visibility).
	 *
	 * @param PuppetModel model The current model.
	 */
	fun setModel(model: PuppetModel)

	/**
	 * Sets the color selected drawables are tinted toward (0..1 sRGB components, from settings).
	 *
	 * @param Float red The red component.
	 * @param Float green The green component.
	 * @param Float blue The blue component.
	 */
	fun setSelectionHighlightColor(red: Float, green: Float, blue: Float)

	/**
	 * Hit-tests the front-most opaque drawable under the cursor, or null on empty canvas.
	 *
	 * @param String areaId The clicked area.
	 * @param Float cursorXpx The cursor x in viewport pixels.
	 * @param Float cursorYpx The cursor y in viewport pixels.
	 * @return DrawableId? The hit drawable, or null.
	 */
	fun pickAt(areaId: String, cursorXpx: Float, cursorYpx: Float): DrawableId?

	/**
	 * Hit-tests every opaque drawable under the cursor, front-to-back (the overlap picker's rows).
	 *
	 * @param String areaId The clicked area.
	 * @param Float cursorXpx The cursor x in viewport pixels.
	 * @param Float cursorYpx The cursor y in viewport pixels.
	 * @return List<PickCandidate> The candidates, front-to-back.
	 */
	fun pickAllAt(areaId: String, cursorXpx: Float, cursorYpx: Float): List<PickCandidate>

	/**
	 * Every visible drawable's world-space geometry centroid at the current pose - the mean of its posed
	 * world vertices, interleaved as [x, y].  Object-mode box / circle select caches this at gesture start
	 * (the pose is fixed for the whole drag) and tests each drawable's centroid against the region.  World
	 * space, so it is area-independent: the caller projects with the area's own camera.  Empty until the
	 * first frame's geometry lands.
	 *
	 * @return Map<DrawableId, FloatArray> Per-drawable [x, y] world centroid.
	 */
	fun drawableWorldCentroids(): Map<DrawableId, FloatArray>

	/**
	 * A drawable's cached art thumbnail (the overlap-picker row image), or null when untextured.
	 *
	 * @param DrawableId id The drawable.
	 * @return ImageBitmap? The thumbnail, or null.
	 */
	fun thumbnailFor(id: DrawableId): ImageBitmap?

	/**
	 * The thumbnail provider backing [thumbnailFor], for `LocalDrawableThumbnails`.
	 *
	 * @return DrawableThumbnailProvider The provider.
	 */
	fun thumbnails(): DrawableThumbnailProvider

	/**
	 * The owning part's display name for a drawable (the overlap-picker row label), or null when partless.
	 *
	 * @param DrawableId id The drawable.
	 * @return String? The part name, or null.
	 */
	fun partNameFor(id: DrawableId): String?

	/** Stops the engine and releases its GPU resources; the service is unusable afterwards. */
	fun dispose()
}
