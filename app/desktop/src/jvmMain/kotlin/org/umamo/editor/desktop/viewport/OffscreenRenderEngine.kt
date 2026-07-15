package org.umamo.editor.desktop.viewport

import org.lwjgl.opengl.GL11
import org.umamo.edit.GridConfig
import org.umamo.render.ContentBounds
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.gl.GlPuppetRenderer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.RenderedFrame

/** Idle poll when nothing changed and no read-back is in flight (about 60 Hz wake to pick up new params). */
private const val IDLE_MILLIS = 16L

/** Short poll while a read-back is in flight, so its result is collected with low latency. */
private const val BUSY_MILLIS = 1L

/**
 * The render engine: a dedicated daemon thread owns the GL context, the [GlPuppetRenderer], the supersample
 * framebuffers, and the async read-back pool, and runs the render loop. It holds the render-input state the
 * UI thread pushes (selection, shown set, model, grid, highlight colors), renders each registered area whose
 * pose / size / camera / backdrop changed, and publishes finished frames to the area's slot.
 *
 * The read-back is asynchronous (PBO + fence) so the thread never blocks on the GPU while a slider drags.
 * All viewport areas of one document show the same puppet at the same pose (the shared [liveParams]), so
 * areas differ only by SIZE; re-renders happen only when the pose or an area's size/camera/backdrop changes.
 *
 * パペット描画エンジン。専用デーモンスレッドが GL コンテキスト・レンダラー・FBO・読み戻しプールを所有し、
 * 描画ループを回す。読み戻しは PBO とフェンスで非同期化する。
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
	// GL handles + async read-back state, all owned by the render thread.
	private val renderer =
		GlPuppetRenderer(puppet, textures).apply {
			// The editor viewport shows the world-origin axes (red X / blue Z behind the puppet); the
			// renderer default is off so headless render-diff tests stay line-free.
			setWorldAxesVisible(true)
		}

	/** The shared renderer, exposed so the facade can build the CPU picker over its pickGeometry()/drawnOrder(). */
	val puppetRenderer: GlPuppetRenderer
		get() = renderer

	private val context = createOffscreenGlContext()
	private val framebuffer = SupersampleFramebuffer()
	private val readback = PixelReadbackPool()

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

	// The latest model, re-pushed on a structural edit (layer reorder / reparent, base-mesh move); seeded
	// with the open model.
	@Volatile
	private var modelBacking: PuppetModel = puppet

	@Volatile
	private var puppetRenderBump: Long = 0

	// Atlas changes bump the renderer separately so the puppet updating does not needlessly update the atlas.
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
		framebuffer.allocate()
		try {
			var lastParams: Map<ParameterId, Float>? = null
			var lastShown: Set<DrawableId>? = null
			var lastModel: PuppetModel? = null
			var paramsVersion = 0L
			while (running) {
				collectCompleted()
				val params = liveParams.values
				val shown = shownBacking
				val orderModel = modelBacking
				// Rebuild the pose - and thus the draw list, which setPose filters by the shown set and sorts by
				// the render order - when the pose, the visibility cascade, OR the render order changes. A
				// visibility toggle or a layer reorder leaves the params untouched, so without these checks the
				// draw list would never refresh. setShownDrawables / updateModel run first so setPose uses them.
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
				var pendingWork = readback.hasPending()
				for ((areaId, slot) in registry.areas) {
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
					// content bounds; the registry restores a remembered camera or fits fresh.
					val camera = registry.establishCamera(slot, areaId, width, height) { contentBoundsFor(slot) }
					// An atlas page is model-independent, so its freshness ignores the pose version and
					// puppetRenderBump: it re-renders only on size / camera / pageIndex, plus the grid colors and
					// geometry it draws its backdrop with - tracked by atlasRenderBump. The puppet keeps the full
					// freshness via puppetRenderBump.
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
				} else if (readback.hasPending()) {
					Thread.sleep(BUSY_MILLIS)
				}
			}
		} finally {
			// glFinish first so the driver completes all pending GPU work BEFORE the disposers delete GL
			// objects and the context is destroyed - otherwise a driver worker thread can be mid-copy on
			// memory we free, which crashed (SIGSEGV in libc memcpy) on a clean window close. A single barrier
			// here; the collaborators' dispose() must NOT call glFinish, and the context is destroyed last.
			GL11.glFinish()
			readback.dispose()
			framebuffer.dispose()
			context.destroy()
		}
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

		framebuffer.ensure(width, height)

		// Supersample: render the whole pipeline (puppet, clip masks, grid) into the RENDER_SUPERSAMPLE x draw
		// buffer, then box-downscale to display size on resolve. The camera zoom and the grid line width scale
		// by the same factor so framing and backdrop are unchanged after the downscale.
		renderer.setRenderScale(RENDER_SUPERSAMPLE.toFloat())

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
		renderer.setCamera(camera.copy(zoom = camera.zoom * RENDER_SUPERSAMPLE))

		when (slot.scene) {
			RenderScene.Puppet2D -> renderer.render(renderWidth, renderHeight)
			// A UV area draws the flat atlas page instead; the pose / selection / shown state pushed above are
			// harmless no-ops for it (renderAtlasPage reads none of them - just the grid + the page quad).
			RenderScene.AtlasPage -> renderer.renderAtlasPage(slot.pageIndex, renderWidth, renderHeight)
		}

		framebuffer.downscaleResolve(renderWidth, renderHeight, width, height)

		if (!dumped) {
			System.getenv("UMAMO_DUMP_PNG")?.let { dumpPath ->
				// dumpPng does a synchronous client read-back; safe here because no PBO is bound yet.
				renderer.dumpPng(dumpPath, width, height)
				dumped = true
				UmamoLog.info("[GL] puppet dumped to $dumpPath (${width}x$height)")
			}
		}

		// Bind the frame to the camera it was rendered at (the plain, non-supersampled camera) and to
		// orderModel, the geometry this render reflects, so the overlay projects/poses against them - keeping
		// the mesh glued to the raster along both the navigation and edit axes.
		readback.readInto(framebuffer.resolveFbo, areaId, width, height, camera, orderModel)
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
	 * Collects every read-back whose fence signaled and publishes it to its area's slot, clearing the slot's
	 * in-flight flag. A read-back whose slot was unregistered while in flight is discarded (the slot is gone).
	 */
	private fun collectCompleted() {
		for (done in readback.collectCompleted()) {
			val slot = registry.areas[done.areaId] ?: continue
			slot.inFlight = false
			val bitmap = done.bitmap
			if (bitmap != null) {
				slot.imageState.value = RenderedFrame(bitmap, done.camera, done.model)
			}
		}
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
}
