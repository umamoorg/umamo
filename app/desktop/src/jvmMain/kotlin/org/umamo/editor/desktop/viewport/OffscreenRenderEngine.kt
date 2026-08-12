package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.lwjgl.opengl.GL11
import org.umamo.edit.GridConfig
import org.umamo.format.png.PngCodec
import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.SupersampledSurface
import org.umamo.render.ViewportCamera
import org.umamo.render.device.ReadbackTicket
import org.umamo.render.gl.GlRenderDevice
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.storage.UmamoLog
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.RenderedFrame
import org.umamo.ui.viewport.UvSceneContent
import java.io.File
import java.util.ArrayDeque

/**
 * Framebuffer pixels per display pixel while supersampling is on: the whole pipeline renders 2x and
 * box-downscales on resolve.  Supersampling off collapses the scale to 1.
 */
internal const val RENDER_SUPERSAMPLE = 2

/** Idle poll when nothing changed and no read-back is in flight (about 60 Hz wake to pick up new params). */
private const val IDLE_MILLIS = 16L

/** Short poll while a read-back is in flight, so its result is collected with low latency. */
private const val BUSY_MILLIS = 1L

/**
 * Minimum interval between resize-driven re-renders while an area's size is actively changing (a
 * gutter drag or a window-edge resize): about 10 Hz of live feedback, with the Compose side
 * stretching the previous frame between them.  Pose / camera / state changes are never throttled.
 */
private const val RESIZE_THROTTLE_NANOS = 50_000_000L

/** How long a size must hold still before it counts as settled (the full-quality render then runs). */
private const val RESIZE_SETTLE_NANOS = 25_000_000L

/**
 * The render engine: a dedicated daemon thread owns the GL context, the [PuppetRenderer], the supersample
 * framebuffers, and the async read-back pool, and runs the render loop. It holds the render-input state the
 * UI thread pushes (selection, shown set, model, source artwork, grid, highlight colors), renders each
 * registered area whose pose / size / camera / backdrop changed, and publishes finished frames to the
 * area's slot.
 *
 * The read-back is asynchronous (PBO + fence) so the thread never blocks on the GPU while a slider drags.
 * Every 2D area of one document shows the same puppet at the same pose (the shared [liveParams]), so those
 * areas differ only by size and camera; re-renders happen only when the pose or an area's
 * size / camera / scene content / backdrop changes.
 *
 * @property PuppetModel puppet The rig to render.
 * @property PuppetTextures textures The atlas page(s).
 * @property LiveParams liveParams The shared parameter hand-off (drives re-render on change).
 * @property ViewportAreaRegistry registry The area slots this engine renders and fits.
 */
internal class OffscreenRenderEngine(
	private val puppet: PuppetModel,
	private val textures: PuppetTextures,
	private val liveParams: LiveParams,
	private val registry: ViewportAreaRegistry,
) {
	// The GL backend the renderer draws through; render-thread-owned, like every GL object here.
	private val device = GlRenderDevice()

	// GL handles + async read-back state, all owned by the render thread.
	private val renderer =
		PuppetRenderer(puppet, textures, device).apply {
			// The editor viewport shows the world-origin axes (red X / blue Z behind the puppet); the
			// renderer default is off so headless render-diff tests stay line-free.
			setWorldAxesVisible(true)
		}

	/** The shared renderer, exposed so the facade can build the CPU picker over its pickGeometry()/drawnOrder(). */
	val puppetRenderer: PuppetRenderer
		get() = renderer

	private val context = createOffscreenGlContext()

	// The supersampled draw + display-size resolve target pair, device-owned and backend-neutral.
	private val surface = SupersampledSurface(device, RENDER_SUPERSAMPLE)

	/** An asynchronous read-back in flight: the device ticket plus what the pixels will mean on arrival. */
	private class PendingFrame(
		val ticket: ReadbackTicket,
		val areaId: String,
		val camera: ViewportCamera,
		val model: PuppetModel,
	)

	// In-flight read-backs in submission order; polled front-first each loop tick. Render-thread only.
	private val pendingFrames = ArrayDeque<PendingFrame>()

	@Volatile
	private var running = true

	// Daemon so it can never block JVM exit; clean teardown still happens via dispose() -> join.
	private val renderThread = Thread({ renderLoop() }, "umamo-offscreen-gl").apply { isDaemon = true }

	// --- Render inputs: written by the UI thread (volatile publishes of immutable values / scalars), read by
	// the render thread each frame. A change bumps a render-version counter the loop folds into per-area
	// freshness, so a state-only change (no resize / pose / camera change) still forces exactly one redraw.

	// The grid backdrop colors, fed from the editor theme; default to the neutral grey grid until the host
	// pushes the themed colors.
	@Volatile
	private var gridColorsBacking: GridColors = GridColors.Classic

	// The per-document grid geometry (major spacing + subdivisions), fed from the session.
	@Volatile
	private var gridConfigBacking: GridConfig = GridConfig()

	// The currently selected drawables, read by the render thread to tint them.
	@Volatile
	private var selectionBacking: Set<DrawableId> = emptySet()

	// The active (last-selected) drawable, tinted apart from the rest of a multi-selection; null when none.
	@Volatile
	private var activeSelectionBacking: DrawableId? = null

	// The drawables actually drawn (the resolved Parts-panel visibility cascade). Seeded from the open
	// model's static cascade.
	@Volatile
	private var shownBacking: Set<DrawableId> = puppet.visibleDrawableIds()

	// Which artwork the puppet's drawables map onto, published whole.  EMPTY is the atlas, which is where
	// every document starts until a plan is prepared for it.  The pixels are NOT here: they arrive
	// through the queue below, in answer to what the renderer asks for.
	@Volatile
	private var layerPlanBacking: LayerDrawPlan = LayerDrawPlan.EMPTY

	// Decoded artwork waiting to be uploaded, drained on the render thread.  A queue rather than a
	// volatile slot because deliveries are chunked - two batches landing between frames must both be
	// taken up, where a slot would silently drop the first.
	private val pendingRasterBatches = java.util.concurrent.ConcurrentLinkedQueue<LayerRasterBatch>()

	// What the renderer wants decoded next, republished from the render loop for the producer to answer.
	private val sourceLayerRequestsBacking = MutableStateFlow<Pair<Set<String>, Long>>(emptySet<String>() to 0L)

	// The latest model, re-pushed on a structural edit (layer reorder / reparent, base-mesh move); seeded
	// with the open model.
	@Volatile
	private var modelBacking: PuppetModel = puppet

	@Volatile
	private var puppetRenderBump: Long = 0

	// The UV editor's flat scenes (atlas page, source layer) bump separately from the puppet, so a puppet
	// update does not needlessly re-render them.
	@Volatile
	private var atlasRenderBump: Long = 0

	// The color selected drawables are tinted toward; RGB, each 0..1, defaults to the classic blue accent.
	@Volatile
	private var highlightRed: Float = 0.20f

	@Volatile
	private var highlightGreen: Float = 0.55f

	@Volatile
	private var highlightBlue: Float = 1.0f

	// The color the active drawable is tinted toward; RGB, each 0..1, defaults to the edit-mode active green.
	@Volatile
	private var activeHighlightRed: Float = 0.49f

	@Volatile
	private var activeHighlightGreen: Float = 0.89f

	@Volatile
	private var activeHighlightBlue: Float = 0.0f

	// The performance settings (viewport.rendering.*): whether settled frames supersample at all, and
	// whether frames rendered while a size is actively changing keep the supersample (false = drop to
	// 1x for a quarter of the fill cost during gutter drags and window resizes).
	@Volatile
	private var supersampleBacking: Boolean = true

	@Volatile
	private var supersampleWhileResizingBacking: Boolean = true

	private var dumped = false

	/** Starts the render thread (call once). */
	fun start() {
		renderThread.start()
	}

	/** Stops the render thread and releases the GL context. Blocks briefly to join. */
	fun dispose() {
		running = false
		renderThread.join(2000)
	}

	/**
	 * The grid backdrop colors (background / major / minor). A change bumps both render passes so a
	 * color-only change repaints without waiting for an unrelated render.
	 */
	var gridColors: GridColors
		get() = gridColorsBacking
		set(value) {
			if (value != gridColorsBacking) {
				gridColorsBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	/**
	 * The per-document grid geometry (major spacing + subdivisions). Like the grid colors, a change bumps
	 * both render passes so a grid-only change repaints without waiting for an unrelated render.
	 */
	var gridConfig: GridConfig
		get() = gridConfigBacking
		set(value) {
			if (value != gridConfigBacking) {
				gridConfigBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	/**
	 * Sets the highlighted drawables (object-mode selection). A change bumps the puppet render version so the
	 * loop re-renders every area once with the new tint; an identical set is a no-op.
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	fun setSelection(ids: Set<DrawableId>) {
		if (ids != selectionBacking) {
			selectionBacking = ids
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets the active (last-selected) drawable, tinted apart from the rest of a multi-selection. A change
	 * bumps the puppet render version; an identical value is a no-op.
	 *
	 * @param DrawableId id The active drawable id, or null when none is active.
	 */
	fun setActiveSelection(id: DrawableId?) {
		if (id != activeSelectionBacking) {
			activeSelectionBacking = id
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets which drawables are drawn (the resolved Parts-panel visibility cascade). A change bumps the puppet
	 * render version so every area re-renders once; the geometry is unchanged, so only the draw filter moves.
	 *
	 * @param Set<DrawableId> ids The drawable ids to draw.
	 */
	fun setShownDrawables(ids: Set<DrawableId>) {
		if (ids != shownBacking) {
			shownBacking = ids
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets which artwork the puppet's drawables map onto; an empty plan displays from the atlas.
	 *
	 * A volatile publish of one immutable value, like every other render input.  The render loop hands
	 * it to the renderer, which is where the GPU work happens - this must not touch the device.
	 *
	 * @param LayerDrawPlan plan Each drawable's mapping into the document's artwork.
	 */
	fun setSourceLayerPlan(plan: LayerDrawPlan) {
		if (plan !== layerPlanBacking) {
			layerPlanBacking = plan
			doPuppetRenderBump()
		}
	}

	/** What the renderer wants decoded next, for the producer to answer with [deliverSourceLayerRasters]. */
	val sourceLayerRequests: StateFlow<Pair<Set<String>, Long>> get() = sourceLayerRequestsBacking

	/**
	 * Queues decoded artwork for upload on the render thread.
	 *
	 * @param LayerRasterBatch batch The decoded artwork.
	 */
	fun deliverSourceLayerRasters(batch: LayerRasterBatch) {
		pendingRasterBatches.add(batch)
		doPuppetRenderBump()
	}

	/**
	 * Pushes the latest model so the render thread can reconcile it after an edit (a layer reorder
	 * re-derives the render order; a base-mesh move re-uploads the changed drawables' VBOs). A new (different)
	 * instance bumps the puppet render version so every area re-renders once.
	 *
	 * @param PuppetModel model The current model.
	 * @return Boolean True when the model actually changed (so the caller rebuilds model-derived state).
	 */
	fun setModel(model: PuppetModel): Boolean {
		if (model !== modelBacking) {
			modelBacking = model
			doPuppetRenderBump()
			return true
		}
		return false
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight). A change bumps the
	 * puppet render version; an identical color is a no-op.
	 *
	 * @param Float red The tint red, 0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue The tint blue, 0..1.
	 */
	fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		if (red != highlightRed || green != highlightGreen || blue != highlightBlue) {
			highlightRed = red
			highlightGreen = green
			highlightBlue = blue
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets the color the active drawable is tinted toward (the active-selection highlight). A change bumps the
	 * puppet render version; an identical color is a no-op.
	 *
	 * @param Float red The tint red, 0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue The tint blue, 0..1.
	 */
	fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		if (red != activeHighlightRed || green != activeHighlightGreen || blue != activeHighlightBlue) {
			activeHighlightRed = red
			activeHighlightGreen = green
			activeHighlightBlue = blue
			doPuppetRenderBump()
		}
	}

	/**
	 * Whether settled frames render supersampled at all (viewport.rendering.supersample).  Off renders
	 * everything at 1x - the whole-session performance escape hatch for weak GPUs.  A change bumps
	 * both render passes so every area repaints at the new quality.
	 */
	var supersampleEnabled: Boolean
		get() = supersampleBacking
		set(value) {
			if (value != supersampleBacking) {
				supersampleBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	/**
	 * Whether frames rendered while an area's size is actively changing keep the supersample
	 * (viewport.rendering.supersampleWhileResizing).  False (the default) drops those frames to 1x;
	 * the settle render restores full quality within the settle window.  No bump on change - the next
	 * resize simply picks up the new policy.
	 */
	var supersampleWhileResizing: Boolean
		get() = supersampleWhileResizingBacking
		set(value) {
			supersampleWhileResizingBacking = value
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
	 * The render thread body: create the context, then loop - collect finished read-backs, issue new renders
	 * for changed areas, and idle when there is nothing to do.
	 */
	private fun renderLoop() {
		if (!context.createAndMakeCurrent()) {
			UmamoLog.warn("[GL] offscreen context unavailable (${context.backendName}); viewport will stay blank")
			return
		}
		UmamoLog.info("[GL] offscreen via ${context.backendName}: ${context.describeContext()}")
		renderer.initGl()
		try {
			var lastParams: Map<ParameterId, Float>? = null
			var lastOverrides: Map<KeyableTarget, ChannelValue>? = null
			var lastShown: Set<DrawableId>? = null
			var lastModel: PuppetModel? = null
			var lastLayerPlan: LayerDrawPlan? = null
			var paramsVersion = 0L
			while (running) {
				collectCompleted()
				val params = liveParams.values
				val shown = shownBacking
				val orderModel = modelBacking
				// The artwork hand-off, on the render thread where the uploads belong.  The mapping is
				// compared by identity: it is published whole, so a new reference IS the change.
				val layerPlan = layerPlanBacking
				if (layerPlan !== lastLayerPlan) {
					renderer.setSourceLayerPlan(layerPlan)
					lastLayerPlan = layerPlan
				}
				// Then any decoded pixels that arrived since the last frame.  Drained rather than sampled:
				// the producer chunks its deliveries so visible art lands first, and skipping a batch would
				// strand whatever it carried on the atlas until the working set happened to move again.
				while (true) {
					val batch = pendingRasterBatches.poll() ?: break
					renderer.deliverSourceLayerRasters(batch)
				}
				// Republish what the renderer now wants.  Both of the calls above can move it - a new plan
				// changes what is admissible, and an upload can free room for more - so this sits after them.
				val wanted = renderer.wantedSourceLayerKeys()
				if (wanted != sourceLayerRequestsBacking.value) {
					sourceLayerRequestsBacking.value = wanted
				}
				// Rebuild the pose - and thus the draw list, which setPose filters by the shown set and sorts by
				// the render order - when the pose, the visibility cascade, OR the render order changes. A
				// visibility toggle or a layer reorder leaves the params untouched, so without these checks the
				// draw list would never refresh. setShownDrawables / updateModel run first so setPose uses them.
				// The override map is compared by identity like the params map: both are swapped wholesale on
				// the UI thread, so a reference change is exactly "something moved".
				val overrides = liveParams.channelOverrides
				if (params !== lastParams || overrides !== lastOverrides || shown !== lastShown || orderModel !== lastModel) {
					renderer.setShownDrawables(shown)
					if (orderModel !== lastModel) {
						// Re-point the renderer at the edited model so the next setPose re-derives the draw order
						// and (for a deformer reparent) the deform chain.
						renderer.updateModel(orderModel)
						lastModel = orderModel
					}
					renderer.setPose(params, overrides)
					lastParams = params
					lastOverrides = overrides
					lastShown = shown
					paramsVersion++
				}
				var pendingWork = pendingFrames.isNotEmpty()
				val nowNanos = System.nanoTime()
				val settleScale = if (supersampleBacking) RENDER_SUPERSAMPLE else 1
				// With supersampling off both scales collapse to 1, so supersampleWhileResizing is
				// inert by construction (the preferences UI disables its checkbox to say so).
				val interactiveScale = if (supersampleWhileResizingBacking) settleScale else 1
				for ((areaId, slot) in registry.areas) {
					val width = slot.width
					val height = slot.height
					if (width <= 0 || height <= 0) {
						continue
					}
					// Track size-change recency for the resize throttle.  The FIRST observation (a fresh
					// slot, observed 0x0) does not stamp, so a newly opened area counts as settled and its
					// first frame renders at full quality immediately.
					if (width != slot.observedWidth || height != slot.observedHeight) {
						val firstObservation = slot.observedWidth == 0 && slot.observedHeight == 0
						slot.observedWidth = width
						slot.observedHeight = height
						if (!firstObservation) {
							slot.sizeChangedNanos = nowNanos
						}
					}
					if (slot.inFlight) {
						pendingWork = true
						continue // one read-back per area in flight; coalesces a flurry of slider moves
					}
					// Establish or refit the camera now that the size is known - the render thread owns the
					// content bounds; the registry restores a remembered camera or fits fresh.
					val camera = registry.establishCamera(slot, areaId, width, height) { contentBoundsFor(slot) }
					// Freshness splits into the size axis (throttled during an active resize) and the rest.
					// A frame rendered below the settle scale stays size-stale on purpose, so the settle
					// pass re-renders it at full quality once the size holds still.
					// A UV scene is model-independent, so its freshness ignores the pose version and
					// puppetRenderBump: it re-renders only on size / camera / the surface it shows (the page index
					// or the layer raster), plus the grid colors and geometry it draws its backdrop with - tracked
					// by atlasRenderBump. The puppet keeps the full freshness via puppetRenderBump.
					val sizeFresh = slot.renderedWidth == width && slot.renderedHeight == height && slot.renderedScale == settleScale
					val restFresh =
						when (slot.scene) {
							RenderScene.Puppet2D ->
								slot.renderedParamsVersion == paramsVersion &&
									slot.renderedCamera === camera &&
									slot.puppetRenderBumpDone == puppetRenderBump

							// Kind and payload are read as ONE value, so a switch can never be observed half
							// applied.  Equality rather than identity: AtlasPage compares its index, and
							// SourceLayer's image compares by reference, which is the freshness test either
							// surface wants.
							RenderScene.UvScene ->
								slot.renderedUvContent == slot.uvContent &&
									slot.renderedCamera === camera &&
									slot.atlasRenderBumpDone == atlasRenderBump
						}
					if (sizeFresh && restFresh) {
						continue
					}
					if (restFresh && shouldDeferResizeRender(slot, nowNanos)) {
						// Deliberately NOT pendingWork: with no read-back in flight the loop then sleeps
						// IDLE_MILLIS (16 ms) and revisits, which cannot oversleep the RESIZE_SETTLE_NANOS or the
						// RESIZE_THROTTLE_NANOS window - flagging pendingWork with an empty pendingFrames queue
						// would skip both sleeps and busy-spin instead.
						continue
					}
					// A size still in motion renders at the interactive scale; pose / camera / state changes
					// during that motion share the burst's quality rather than forcing a full-scale render.
					val sizeInMotion = nowNanos - slot.sizeChangedNanos < RESIZE_SETTLE_NANOS
					val renderScale = if (sizeInMotion) interactiveScale else settleScale
					if (width != slot.renderedWidth || height != slot.renderedHeight) {
						slot.resizeRenderNanos = nowNanos
					}
					issueRender(areaId, slot, width, height, paramsVersion, camera, orderModel, renderScale)
					pendingWork = true
				}
				if (!pendingWork) {
					Thread.sleep(IDLE_MILLIS)
				} else if (pendingFrames.isNotEmpty()) {
					Thread.sleep(BUSY_MILLIS)
				}
			}
		} finally {
			// glFinish first so the driver completes all pending GPU work BEFORE the disposers delete GL
			// objects and the context is destroyed - otherwise a driver worker thread can be mid-copy on
			// memory we free, which crashed (SIGSEGV in libc memcpy) on a clean window close. A single barrier
			// here; the collaborators' dispose() must NOT call glFinish, and the context is destroyed last.
			GL11.glFinish()
			// Abandon in-flight read-backs (the fences/staging are freed through the device); the surface
			// targets go the same way. The context is destroyed last.
			while (pendingFrames.isNotEmpty()) {
				device.cancelReadback(pendingFrames.removeFirst().ticket)
			}
			// The renderer's own device objects.  Source artwork and underlay images are created and
			// destroyed across its life rather than uploaded once, so they need releasing explicitly
			// rather than being left to die with the context.
			renderer.disposeGl()
			pendingRasterBatches.clear()
			surface.dispose()
			context.destroy()
		}
	}

	/**
	 * The resize-throttle gate: whether a render whose ONLY staleness is its size should wait.  A size
	 * that has held still for the settle window renders immediately (the full-quality settle pass);
	 * one still in motion renders at most once per throttle interval.
	 *
	 * @param AreaSlot slot The area being considered.
	 * @param Long nowNanos The loop pass's monotonic timestamp.
	 * @return Boolean True to skip this tick and let the idle sleep revisit.
	 */
	private fun shouldDeferResizeRender(slot: AreaSlot, nowNanos: Long): Boolean {
		val settled = nowNanos - slot.sizeChangedNanos >= RESIZE_SETTLE_NANOS
		val throttleElapsed = nowNanos - slot.resizeRenderNanos >= RESIZE_THROTTLE_NANOS
		return !settled && !throttleElapsed
	}

	/**
	 * Renders [slot] at [width] x [height] into the supersampled draw target, box-downscales it into the
	 * resolve framebuffer, then kicks off an asynchronous read-back gated by a fence. Marks the slot
	 * in-flight; the result is posted later by [collectCompleted].
	 *
	 * @param String areaId The area's id.
	 * @param AreaSlot slot The area being rendered.
	 * @param Int width The render width in pixels.
	 * @param Int height The render height in pixels.
	 * @param Long paramsVersion The pose version this render reflects.
	 * @param ViewportCamera camera The view to project through.
	 * @param PuppetModel orderModel The model whose geometry this render reflects (stamped onto the frame).
	 * @param Int renderScale Framebuffer pixels per display pixel for THIS render: the settle scale for
	 *   a still frame, the interactive scale while the size is in motion.
	 */
	private fun issueRender(
		areaId: String,
		slot: AreaSlot,
		width: Int,
		height: Int,
		paramsVersion: Long,
		camera: ViewportCamera,
		orderModel: PuppetModel,
		renderScale: Int,
	) {
		val renderWidth = width * renderScale
		val renderHeight = height * renderScale

		val drawTarget = surface.ensure(width, height, renderScale)

		// Supersample: render the whole pipeline (puppet, clip masks, grid) into the renderScale x draw
		// buffer, then box-downscale to display size on resolve. The camera zoom and the grid line width scale
		// by the same factor so framing and backdrop are unchanged after the downscale.  The resolve target
		// is display-size whatever the scale, so read-back consumers never see the quality switch.
		renderer.setRenderScale(renderScale.toFloat())

		// Capture the backdrop versions applied to this render so the freshness stamp below matches what was
		// actually drawn; a change after this point bumps them again and re-renders next iteration.
		val puppetRenderBumpDone = puppetRenderBump
		val atlasRenderBumpDone = atlasRenderBump

		val gridConfigApplied = gridConfigBacking
		renderer.setGrid(gridColorsBacking, gridConfigApplied.scale, gridConfigApplied.subdivisions)
		renderer.setSelection(selectionBacking)
		renderer.setActiveSelection(activeSelectionBacking)

		// The shown set is applied in the render-loop pose block (before setPose filters the draw list by it).
		renderer.setSelectionHighlightColor(highlightRed, highlightGreen, highlightBlue)
		renderer.setActiveSelectionHighlightColor(activeHighlightRed, activeHighlightGreen, activeHighlightBlue)
		renderer.setCamera(camera.copy(zoom = camera.zoom * renderScale))

		// Read once, drawn and stamped from the same value: re-reading slot.uvContent between the draw and
		// the stamp below would let a switch land in between and mark the frame fresh for content it does
		// not show.
		val uvContent = slot.uvContent
		when (slot.scene) {
			RenderScene.Puppet2D -> renderer.render(drawTarget, renderWidth, renderHeight)
			// A UV area draws its flat surface instead; the pose / selection / shown state pushed above are
			// harmless no-ops for it (neither UV draw reads any of them - just the grid and the surface quad).
			RenderScene.UvScene ->
				when (uvContent) {
					// An atlas page the engine already uploaded, addressed by index.
					is UvSceneContent.AtlasPage -> renderer.renderAtlasPage(drawTarget, uvContent.pageIndex, renderWidth, renderHeight)
					// Artwork the engine has never uploaded, so the renderer takes the pixels rather than an
					// index and caches the texture it makes from them.
					is UvSceneContent.SourceLayer -> renderer.renderUnderlayImage(drawTarget, uvContent.image, renderWidth, renderHeight)
					null -> renderer.renderAtlasPage(drawTarget, null, renderWidth, renderHeight)
				}
		}

		surface.resolve()

		if (!dumped) {
			System.getenv("UMAMO_DUMP_PNG")?.let { dumpPath ->
				// A synchronous client read-back; safe here because no PBO is bound yet. Encoding and the
				// file write live here rather than in :render - reading pixels is the renderer's business,
				// turning them into a PNG on disk is not, and keeping the split means :render needs no
				// image library at all.
				File(dumpPath).writeBytes(PngCodec.write(device.readPixels(surface.resolveTarget, width, height)))
				dumped = true
				UmamoLog.info("[GL] puppet dumped to $dumpPath (${width}x$height)")
			}
		}

		// Bind the frame to the camera it was rendered at (the plain, non-supersampled camera) and to
		// orderModel, the geometry this render reflects, so the overlay projects/poses against them - keeping
		// the mesh glued to the raster along both the navigation and edit axes.  The resolve target is
		// capacity-sized (grow-only), so the read-back covers only the used region.
		pendingFrames.addLast(PendingFrame(device.beginReadback(surface.resolveTarget, width, height), areaId, camera, orderModel))
		slot.inFlight = true
		slot.renderedWidth = width
		slot.renderedHeight = height
		slot.renderedScale = renderScale
		slot.renderedParamsVersion = paramsVersion
		slot.renderedCamera = camera
		slot.puppetRenderBumpDone = puppetRenderBumpDone
		slot.atlasRenderBumpDone = atlasRenderBumpDone
		slot.renderedUvContent = uvContent
	}

	/**
	 * Collects every read-back whose fence signaled and publishes it to its area's slot, clearing the slot's
	 * in-flight flag. A read-back whose slot was unregistered while in flight is discarded (the slot is gone).
	 */
	private fun collectCompleted() {
		// Front-first, stopping at the first still-in-flight ticket: reads complete in submission order on
		// the GPU timeline, so a later one cannot be done before an earlier one.
		while (pendingFrames.isNotEmpty()) {
			val pending = pendingFrames.first()
			val pixels = device.pollReadback(pending.ticket) ?: break
			pendingFrames.removeFirst()
			val slot = registry.areas[pending.areaId] ?: continue
			slot.inFlight = false
			// The device's read-back is TOP-first RGBA already; the preview background is composited into
			// RGB, so it is opaque - the shared seam's Opaque path ignores the alpha bytes (no per-frame
			// alpha pass) and gives the eventual Android viewport the same conversion for free.
			val bitmap = rgbaToImageBitmap(pixels.rgba, pixels.width, pixels.height, RgbaAlphaType.Opaque)
			slot.imageState.value = RenderedFrame(bitmap, pending.camera, pending.model)
		}
	}

	/**
	 * The content rectangle an area's camera fits: the puppet's rest-pose bounds for a 2D area, or the
	 * shown surface's rectangle for a UV-editor area (the atlas page, or the source layer's raster).
	 * Render thread only (reads the renderer's bounds).
	 *
	 * @param AreaSlot slot The area being framed.
	 * @return ContentBounds The rectangle to fit.
	 */
	private fun contentBoundsFor(slot: AreaSlot): ContentBounds =
		when (slot.scene) {
			RenderScene.Puppet2D -> renderer.contentBounds()
			RenderScene.UvScene ->
				when (val uvContent = slot.uvContent) {
					is UvSceneContent.AtlasPage -> pageContentBounds(uvContent.pageIndex)
					is UvSceneContent.SourceLayer -> imageContentBounds(uvContent.image)
					null -> pageContentBounds(null)
				}
		}

	/**
	 * The source-layer rectangle (0, 0, width, height) for the UV-editor fit, or a unit square when there
	 * is no layer, matching [pageContentBounds]' fallback so both UV scenes frame the same way.
	 *
	 * @param DecodedImage image The layer raster, or null for none.
	 * @return ContentBounds The layer rectangle, in texel/display units.
	 */
	private fun imageContentBounds(image: DecodedImage?): ContentBounds =
		if (image != null) {
			ContentBounds(0f, 0f, image.width.toFloat(), image.height.toFloat())
		} else {
			ContentBounds(0f, 0f, 1f, 1f)
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
}