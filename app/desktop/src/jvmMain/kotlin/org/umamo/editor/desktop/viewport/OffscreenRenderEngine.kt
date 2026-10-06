package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.Deferred
import org.lwjgl.opengl.GL11
import org.umamo.format.raster.RasterImage
import org.umamo.render.ContentBounds
import org.umamo.render.FrameBackdrop
import org.umamo.render.LayerDrawPlan
import org.umamo.render.PuppetTextures
import org.umamo.render.SupersampledSurface
import org.umamo.render.ViewportCamera
import org.umamo.render.gl.GlRenderDevice
import org.umamo.render.puppet.ModelUpdateKind
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.AtlasPageBinding
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.UvSceneContent

/*
 * The desktop render engine.  This file is the GL-owning wiring: the thread, the context, the renderer
 * and its surface, the hand-off state the thread keeps, and the loop that runs one tick's four steps in
 * order.  Its parts:
 *   - EngineRenderInputs.kt: the render inputs the UI thread publishes, with their change detection
 *     and the two render versions the loop folds into per-area freshness.
 *   - AtlasPairingDecision.kt: the pure rule pairing a published model with the pages composed for it.
 *   - PoseHandoff.kt: the pure rule for whether a tick's hand-off must also rebuild the pose.
 *   - AreaFreshness.kt: the resize throttle, the size observation, and the pure fresh / deferred /
 *     render decision per area.
 *   - SceneContentBounds.kt: the rectangle an area's fit frames, per scene kind.
 *   - PlacementGhostRule.kt: the pure rule for when a UV area's placement preview drops its ghost.
 *   - FrameReadbackQueue.kt: the asynchronous read-backs in flight and their publication to the slots.
 *   - SnapshotQueue.kt: the image captures the UI thread asks for, served between frames.
 *   - FirstFrameDump.kt: the UMAMO_DUMP_PNG developer dump.
 * The areas, their cameras, and the registry the loop walks are ViewportAreaRegistry.kt; the facade
 * that composes all of this with the picker is OffscreenPuppetService.kt.
 */

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
 * The render engine: a dedicated daemon thread owns the GL context, the [PuppetRenderer], the supersample
 * framebuffers, and the async read-back pool, and runs the render loop.  Each tick it collects the
 * read-backs that finished, hands the renderer what the UI thread published through [inputs] since the
 * last tick (pages and model as one pair, the artwork mapping and its pixels, the pose), serves the image
 * captures, and renders each registered area whose pose / size / camera / scene content / backdrop
 * changed, publishing finished frames to the area's slot.
 *
 * The read-back is asynchronous (PBO + fence) so the thread never blocks on the GPU while a slider drags.
 * Every 2D area of one document shows the same puppet at the same pose (the shared [liveParams]), so those
 * areas differ only by size and camera; re-renders happen only when the pose or an area's
 * size / camera / scene content / backdrop changes.
 *
 * Everything from [renderLoop] down runs on the render thread: the hand-off state is plain fields of
 * this class, and the collaborators holding GL objects or render-thread bookkeeping are never touched
 * from the UI thread.  What the UI thread calls is [start], [dispose], [requestSnapshot], and the
 * pure-CPU reads of [puppetRenderer].
 *
 * @param PuppetModel puppet The rig to render.
 * @param PuppetTextures textures The atlas page(s) the renderer uploads at initGl: the same instance [inputs]
 *   was built over, so the applied binding it seeds tags the pages actually resident.
 * @property LiveParams liveParams The shared parameter hand-off (drives re-render on change).
 * @property ViewportAreaRegistry registry The area slots this engine renders and fits.
 * @property EngineRenderInputs inputs The render inputs the UI thread pushes, read each frame.
 */
internal class OffscreenRenderEngine(
	puppet: PuppetModel,
	textures: PuppetTextures,
	private val liveParams: LiveParams,
	private val registry: ViewportAreaRegistry,
	private val inputs: EngineRenderInputs,
) {
	// The GL backend the renderer draws through; render-thread-owned, like every GL object here.
	private val device = GlRenderDevice()

	// The renderer and its GL handles, owned by the render thread.
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

	// The read-backs in flight, issued per area render and collected front-first each tick. Render-thread only.
	private val readbacks = FrameReadbackQueue(device)

	// The image captures the UI thread has asked for, served between frames and answered null at shutdown.
	private val snapshots = SnapshotQueue()

	// The UMAMO_DUMP_PNG developer dump, render-thread-owned like the frames it reads.
	private val firstFrameDump = FirstFrameDump()

	@Volatile
	private var running = true

	// The one gate the capture loop polls between captures and a capture polls between its tiles, so a
	// shutdown stops either at its next step.
	private val stillRunning: () -> Boolean = { running }

	// Which UV scenes the renderer keeps per tick: an area still registered and still showing a UV surface.
	private val isLiveUvArea: (String) -> Boolean = { areaId -> registry.areas[areaId]?.scene == RenderScene.UvScene }

	// Daemon so it can never block JVM exit; clean teardown still happens via dispose() -> join.
	private val renderThread = Thread({ renderLoop() }, "umamo-offscreen-gl").apply { isDaemon = true }

	// --- The hand-off state: what the renderer currently holds, compared against the published inputs
	// each tick by applyHandoffs.  Render-thread-only after start(), so plain fields.

	// The pair the render thread has actually applied; read by the UV fit path (contentBoundsFor) and the
	// placement ghost rule (placementToDraw), which also run on the render thread.  Seeded from the inputs'
	// construction-time pair, whose pages are the ones initGl uploads, so the loop's first tick applies
	// nothing unless a binding was pushed before the thread started.
	private var appliedAtlasBinding: AtlasPageBinding = inputs.initialAtlasBinding

	// The pose inputs the renderer last posed with, each compared by identity: all three are swapped
	// wholesale on the UI thread, so a reference change is exactly "something moved".
	private var lastParams: Map<ParameterId, Float>? = null
	private var lastOverrides: Map<KeyableTarget, ChannelValue>? = null
	private var lastShown: Set<DrawableId>? = null

	// The model the renderer was last pointed at, and the artwork mapping it was last handed.
	private var lastModel: PuppetModel? = null
	private var lastLayerPlan: LayerDrawPlan? = null

	// The pose version: bumped whenever the renderer re-poses, takes up a new model, or takes up new pages,
	// so every puppet area re-renders once.
	private var paramsVersion = 0L

	/** Starts the render thread (call once). */
	fun start() {
		renderThread.start()
	}

	/** Stops the render thread and releases the GL context. Blocks briefly to join. */
	fun dispose() {
		running = false
		renderThread.join(2000)
		snapshots.close()
	}

	/**
	 * Queues an image capture of the current pose for the render thread, which takes it up between frames
	 * with the latest model, pages, artwork, shown set, and pose applied.
	 *
	 * Always completes: with the premultiplied pixels, or with null when the render thread cannot serve it
	 * (a shutdown racing the request still sweeps it up - see [SnapshotQueue.request]).
	 *
	 * @param ViewportCamera camera   The capture's camera.
	 * @param Int            width    The image width in pixels.
	 * @param Int            height   The image height in pixels.
	 * @param FrameBackdrop  backdrop What the puppet is drawn over.
	 * @return Deferred<RasterImage?> The premultiplied pixels, top row first, or null.
	 */
	fun requestSnapshot(camera: ViewportCamera, width: Int, height: Int, backdrop: FrameBackdrop): Deferred<RasterImage?> =
		snapshots.request(camera, width, height, backdrop)

	/**
	 * The render thread body: create the context, then loop - collect finished read-backs, hand the
	 * renderer what was published, serve the captures, render the changed areas, and idle when there is
	 * nothing to do.
	 */
	private fun renderLoop() {
		if (!context.createAndMakeCurrent()) {
			UmamoLog.warn("[GL] offscreen context unavailable (${context.backendName}): ${context.failureReason() ?: "no reason given"}; viewport will stay blank")
			// The context releases what a failed attempt made; this is the engine's own guarantee that the thread
			// ends holding nothing, whatever backend it ran.
			context.destroy()
			snapshots.close()
			return
		}
		UmamoLog.info("[GL] offscreen via ${context.backendName}: ${context.describeContext()}")
		try {
			renderer.initGl()
			while (running) {
				readbacks.collect(registry.areas)
				applyHandoffs()
				// Captures run after the hand-offs, so each one shows exactly the state the areas are about
				// to render.
				serveSnapshots()
				val pendingWork = scheduleAreas()
				if (!pendingWork) {
					Thread.sleep(IDLE_MILLIS)
				} else if (readbacks.hasPending) {
					Thread.sleep(BUSY_MILLIS)
				}
			}
		} finally {
			teardown()
		}
	}

	/**
	 * Hands the renderer what the UI thread published since the last tick: the atlas pages and the model
	 * as one consistent pair, the artwork mapping and the decoded pixels that arrived for it, and the pose
	 * when any of its inputs moved.  Each hand-off that changes what the puppet areas show bumps
	 * [paramsVersion].  The model this tick renders is the published one, or the previous one while the
	 * pages for the published one's atlas are still in flight.
	 */
	private fun applyHandoffs() {
		val params = liveParams.values
		val shown = inputs.shownDrawables
		// Pages and model apply as a consistent PAIR - the decision itself is pure and tested
		// (resolveAtlasPairing); this block only carries it out.
		val pairing = resolveAtlasPairing(inputs.model, inputs.atlasBinding, appliedAtlasBinding, lastModel)
		val orderModel = pairing.orderModel
		pairing.applyBinding?.let { binding ->
			// Pages first, then the model that samples them.  The freshness bumps happen HERE, at
			// apply, not at publish: paramsVersion re-renders the puppet areas, the atlas bump the
			// UV page areas (whose AtlasPage content compares by index and cannot see same-index-
			// new-pixels).  A concurrent UI-thread bump can collapse into this one; both causes are
			// covered by the single re-render that follows either way.
			renderer.setAtlasPages(binding.textures)
			appliedAtlasBinding = binding
			paramsVersion++
			inputs.doAtlasRenderBump()
		}
		// The artwork hand-off, on the render thread where the uploads belong.  The mapping is
		// compared by identity: it is published whole, so a new reference IS the change.
		val layerPlan = inputs.sourceLayerPlan
		if (layerPlan !== lastLayerPlan) {
			renderer.setSourceLayerPlan(layerPlan)
			lastLayerPlan = layerPlan
		}
		// Then any decoded pixels that arrived since the last frame.  Drained rather than sampled:
		// the producer chunks its deliveries so visible art lands first, and skipping a batch would
		// strand whatever it carried on the atlas until the working set happened to move again.
		while (true) {
			val batch = inputs.pollRasterBatch() ?: break
			renderer.deliverSourceLayerRasters(batch)
		}
		// Rebuild the pose - and thus the draw list, which setPose filters by the shown set and sorts by
		// the render order - when the pose, the visibility cascade, OR the render order changes. A
		// visibility toggle or a layer reorder leaves the params untouched, so without these checks the
		// draw list would never refresh. setShownDrawables / updateModel run first so setPose uses them.
		// The override map is compared by identity like the params map: both are swapped wholesale on
		// the UI thread, so a reference change is exactly "something moved".  A push that moved mesh
		// positions alone - every preview push of a Grab - keeps the pose: the renderer kept its pose
		// inputs and refreshed what reads positions, so a rebake would only redo them.
		val overrides = liveParams.channelOverrides
		val modelChanged = orderModel !== lastModel
		val poseInputsChanged = params !== lastParams || overrides !== lastOverrides || shown !== lastShown
		if (poseInputsChanged || modelChanged) {
			renderer.setShownDrawables(shown)
			var modelUpdate: ModelUpdateKind? = null
			if (modelChanged) {
				// Re-point the renderer at the edited model so the next setPose re-derives the draw order
				// and (for a deformer reparent) the deform chain.
				modelUpdate = renderer.updateModel(orderModel)
				lastModel = orderModel
			}
			if (poseFollowsHandoff(poseInputsChanged, modelUpdate)) {
				renderer.setPose(params, overrides)
			}
			lastParams = params
			lastOverrides = overrides
			lastShown = shown
			paramsVersion++
		}
	}

	/**
	 * Serves every queued image capture with the state just handed to the renderer.  A capture over the
	 * grid applies the grid the same way an area render does; the renderer's camera, scale, and selection
	 * are left as the capture found them, and every area render sets its own anyway.
	 */
	private fun serveSnapshots() {
		snapshots.serve(stillRunning) { camera, width, height, backdrop ->
			if (backdrop == FrameBackdrop.Grid) {
				val gridConfigApplied = inputs.gridConfig
				renderer.setGrid(inputs.gridColors, gridConfigApplied.scale, gridConfigApplied.subdivisions)
			}
			renderer.renderSnapshot(camera, width, height, backdrop, shouldContinue = stillRunning)
		}
	}

	/**
	 * Walks the registered areas and renders each one whose last frame no longer covers what it shows,
	 * at the scale its size's motion allows.  An area with a read-back in flight is skipped (one per
	 * area, which coalesces a flurry of slider moves), as is one whose only staleness is a size still
	 * being dragged inside the throttle window.
	 *
	 * @return Boolean True when a read-back is in flight or was just issued, so the loop keeps its short
	 *   poll; false lets it idle.
	 */
	private fun scheduleAreas(): Boolean {
		var pendingWork = readbacks.hasPending
		val nowNanos = System.nanoTime()
		val settleScale = if (inputs.supersampleEnabled) RENDER_SUPERSAMPLE else 1
		// With supersampling off both scales collapse to 1, so supersampleWhileResizing is
		// inert by construction (the preferences UI disables its checkbox to say so).
		val interactiveScale = if (inputs.supersampleWhileResizing) settleScale else 1
		val tick = RenderTick(paramsVersion, settleScale, interactiveScale, nowNanos)
		// A UV area that closed, or now shows the puppet, gives its overlay store and buffers back.
		renderer.retainUvScenes(isLiveUvArea)
		for ((areaId, slot) in registry.areas) {
			val width = slot.width
			val height = slot.height
			if (width <= 0 || height <= 0) {
				continue
			}
			// Before the in-flight gate: a size that changes during a read-back still restarts the
			// settle window.
			observeAreaSize(slot, width, height, nowNanos)
			if (slot.inFlight) {
				pendingWork = true
				continue // one read-back per area in flight; coalesces a flurry of slider moves
			}
			// Establish or refit the camera now that the size is known - the render thread owns the
			// content bounds; the registry restores a remembered camera or fits fresh, and swaps a UV
			// area's view when the page or layer it shows has changed.
			val camera = registry.establishCamera(slot, areaId, width, height) { scene, content -> contentBoundsFor(scene, content) }
			// The decision is pure and tested (decideAreaRender); this block only carries it out.
			// The render versions are read here, per area, so a bump landing mid-pass reaches the areas
			// judged after it.
			when (val decision = decideAreaRender(slot, width, height, camera, tick, inputs.puppetRenderBump, inputs.atlasRenderBump)) {
				AreaRenderDecision.Fresh -> continue

				AreaRenderDecision.Deferred -> {
					// Deliberately NOT pendingWork: with no read-back in flight the loop then sleeps
					// IDLE_MILLIS (16 ms) and revisits, which cannot oversleep the RESIZE_SETTLE_NANOS or the
					// RESIZE_THROTTLE_NANOS window - flagging pendingWork with no read-back in flight
					// would skip both sleeps and busy-spin instead.
					continue
				}

				is AreaRenderDecision.Render -> {
					if (width != slot.renderedWidth || height != slot.renderedHeight) {
						slot.resizeRenderNanos = nowNanos
					}
					issueRender(areaId, slot, width, height, camera, decision.scale)
					pendingWork = true
				}
			}
		}
		return pendingWork
	}

	/**
	 * Renders [slot] at [width] x [height] into the supersampled draw target, box-downscales it into the
	 * resolve framebuffer, then kicks off an asynchronous read-back gated by a fence. Marks the slot
	 * in-flight; the result is posted later by [FrameReadbackQueue.collect].
	 *
	 * @param String areaId The area's id.
	 * @param AreaSlot slot The area being rendered.
	 * @param Int width The render width in pixels.
	 * @param Int height The render height in pixels.
	 * @param ViewportCamera camera The view to project through.
	 * @param Int renderScale Framebuffer pixels per display pixel for THIS render: the settle scale for
	 *   a still frame, the interactive scale while the size is in motion.
	 */
	private fun issueRender(
		areaId: String,
		slot: AreaSlot,
		width: Int,
		height: Int,
		camera: ViewportCamera,
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
		val puppetRenderBumpDone = inputs.puppetRenderBump
		val atlasRenderBumpDone = inputs.atlasRenderBump

		val gridConfigApplied = inputs.gridConfig
		renderer.setGrid(inputs.gridColors, gridConfigApplied.scale, gridConfigApplied.subdivisions)
		renderer.setSelection(inputs.selection)
		renderer.setActiveSelection(inputs.activeSelection)
		// Read AFTER the versions above, like the selection: a publish stores its value before it bumps, so one
		// landing after that read leaves this render stamped with the older version and the area renders again.
		// Applying these in the hand-off instead would let an area stamp itself fresh over a frame that drew the
		// outgoing overlay.  The puppet's overlay is renderer-wide state a UV render never reads (a UV area's
		// overlay rides its content, below); the palette colors both.
		renderer.setMeshOverlay(inputs.meshOverlay)
		renderer.setMeshOverlayPalette(inputs.meshOverlayPalette)

		// The shown set is applied in the hand-off's pose block (before setPose filters the draw list by it).
		renderer.setSelectionHighlightColor(inputs.highlightRed, inputs.highlightGreen, inputs.highlightBlue)
		renderer.setActiveSelectionHighlightColor(inputs.activeHighlightRed, inputs.activeHighlightGreen, inputs.activeHighlightBlue)
		renderer.setCamera(camera.copy(zoom = camera.zoom * renderScale))

		// Read once, drawn and stamped from the same value: re-reading slot.uvContent between the draw and
		// the stamp below would let a switch land in between and mark the frame fresh for content it does
		// not show.
		val uvContent = slot.uvContent
		when (slot.scene) {
			RenderScene.Puppet2D -> renderer.render(drawTarget, renderWidth, renderHeight)
			// A UV area draws its flat surface and the overlay its content carries instead; the pose / selection /
			// shown state pushed above are harmless no-ops for it (no UV draw reads any of them).  The overlay's
			// positions upload into the area's own store, keyed by the area id.
			RenderScene.UvScene ->
				when (uvContent) {
					// An atlas page the engine already uploaded, addressed by index.
					// Its placement preview goes through the ghost rule against the pages applied right now.
					is UvSceneContent.AtlasPage ->
						renderer.renderAtlasPage(
							drawTarget,
							uvContent.pageIndex,
							renderWidth,
							renderHeight,
							areaId,
							uvContent.overlay,
							placementToDraw(uvContent.placement, appliedAtlasBinding.atlas),
						)
					// Artwork the engine has never uploaded, so the renderer takes the pixels rather than an
					// index and caches the texture it makes from them.
					is UvSceneContent.SourceLayer ->
						renderer.renderUnderlayImage(drawTarget, uvContent.image, renderWidth, renderHeight, areaId, uvContent.overlay)
					null -> renderer.renderAtlasPage(drawTarget, null, renderWidth, renderHeight, areaId, null)
				}
		}

		surface.resolve()

		// A synchronous client read-back; safe here because no PBO is bound yet.
		firstFrameDump.dumpOnce(device, surface.resolveTarget, width, height)

		// Bind the frame to the camera it was rendered at (the plain, non-supersampled camera), so the gizmo
		// chrome projects against it and stays glued to the raster during pan and zoom.  The resolve target
		// is capacity-sized (grow-only), so the read-back covers only the used region.
		readbacks.issue(device.beginReadback(surface.resolveTarget, width, height), areaId, camera)
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
	 * Releases everything the render thread owns, in the one order that is safe.  Captures are answered
	 * before any GL teardown, which can itself throw: a caller awaiting one must hear back however this
	 * thread ends.  Then one glFinish, the disposers, and the context last.
	 */
	private fun teardown() {
		snapshots.close()
		// glFinish before any other GL call here, so the driver completes all pending GPU work BEFORE the
		// disposers delete GL objects and the context is destroyed - otherwise a driver worker thread can be
		// mid-copy on memory we free, which would crash (SIGSEGV in libc memcpy) on a clean window close. A
		// single barrier here; the collaborators' dispose() must NOT call glFinish, and the context is
		// destroyed last.
		GL11.glFinish()
		// Abandon in-flight read-backs (the fences/staging are freed through the device); the surface
		// targets go the same way. The context is destroyed last.
		readbacks.cancelAll()
		// The renderer's own device objects.  Source artwork and underlay images are created and
		// destroyed across its life rather than uploaded once, so they need releasing explicitly
		// rather than being left to die with the context.
		renderer.disposeGl()
		inputs.clearRasterBatches()
		surface.dispose()
		context.destroy()
	}

	/**
	 * The content rectangle an area's camera fits, resolved over the renderer's rest-pose bounds and the
	 * pages actually applied - see [sceneContentBounds].  Takes the kind and content the registry read
	 * rather than re-reading the slot, so the rectangle is of the surface the camera is filed under.
	 * Render thread only (reads the renderer's bounds and the applied binding).
	 *
	 * @param RenderScene     scene     The area's kind.
	 * @param UvSceneContent? uvContent What a UV-editor area shows; null for a puppet area.
	 * @return ContentBounds The rectangle to fit.
	 */
	private fun contentBoundsFor(scene: RenderScene, uvContent: UvSceneContent?): ContentBounds =
		sceneContentBounds(scene, uvContent, { renderer.contentBounds() }, appliedAtlasBinding.textures)
}