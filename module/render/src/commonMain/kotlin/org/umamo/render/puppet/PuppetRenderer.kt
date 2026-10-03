package org.umamo.render.puppet

import org.umamo.format.raster.RasterImage
import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.FrameBackdrop
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.GridUniforms
import org.umamo.render.device.LoadAction
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.WorldToNdc
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.eval.PartRenderState
import org.umamo.render.eval.PoseDeformInputs
import org.umamo.render.eval.RenderPlanNode
import org.umamo.render.eval.applyCpuDeform
import org.umamo.render.eval.preparePose
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.differsOnlyInMeshPositions
import org.umamo.runtime.model.visibleDrawableIds
import kotlin.concurrent.Volatile

/**
 * Framebuffer pixels per output pixel for an image capture.  Two, and only two: the resolve is a
 * filtered blit, which at exactly 2:1 samples the midpoint of each 2x2 block and so IS the box average,
 * while at 4:1 it would sample 4 of 16 texels and alias.
 */
const val SNAPSHOT_SUPERSAMPLE = 2

/**
 * The largest tile an image capture renders in one piece, in output pixels.  At the 2x supersample a
 * tile's targets are at most 4096 square (64 MiB each), so a capture's GPU memory stays bounded whatever
 * the image size - a 1:1 capture of a tall Live2D canvas is thousands of pixels on a side.
 */
const val SNAPSHOT_TILE_EDGE = 2048

/**
 * The smallest posed opacity that leaves any coverage in an 8-bit image: below half of one alpha step the
 * drawable rounds to nothing, so a capture's framing does not measure it.
 */
private const val MINIMUM_DRAWN_OPACITY = 0.5f / 255f

/**
 * GPU-deforming puppet renderer, over a [RenderDevice].
 *
 * The keyform morph + deformer cascade run in the vertex shader; the CPU only prepares the cheap per-pose
 * data.  Glue (seam-welding vertex pairs across two meshes) is a two-pass GPU step: pass 1
 * transform-feedback-deforms every glue-involved mesh into one shared position store, pass 2 renders the
 * visible glue meshes welding own + partner positions read from that store.  Non-glue meshes render in a
 * single deform pass.  Draws in Cubism render order with per-drawable opacity, blend, and masks.
 *
 * This class holds NO GL - every GPU operation goes through [device], and every decision (glue layout,
 * pose resolution, model diff) is a backend-neutral call into `org.umamo.render.puppet`.  A second
 * backend is therefore a second [RenderDevice], not a second renderer.  It runs on the render thread; the
 * host makes [device]'s context current there.
 *
 * It is the one entry point over the pieces that do the work, each owning a single concern:
 *  - [DrawPipelines]: the fixed-purpose pipelines, and the art-mesh draw pipelines per blend and cull.
 *  - [SideTargetPool]: the mask coverage, destination snapshot, and composite layer targets, at the
 *    one capacity they share.
 *  - [DrawableResidency]: the drawables resident on the GPU, their reconcile after an edit, the
 *    per-pose state stamped onto them, and the glue store they weld through.
 *  - [ArtResidency]: the atlas pages and the source artwork those drawables sample.
 *  - [RenderPlanEncoder]: a pose's render plan recorded as passes - the draws, the mask coverage, and
 *    the layer composites.
 *  - [BackdropEncoder]: the grid, the world axes, and the flat image underlay.
 *
 * What stays here is what ties those to one model and one pose: the current model, the pose's resolved
 * plan, the view the host set, and what is published to the UI thread for picking.
 *
 * @property PuppetModel  model  The model at construction; [updateModel] replaces it.
 * @param PuppetTextures textures The atlas page set at construction; [setAtlasPages] replaces it.
 * @property RenderDevice device The backend every GPU operation goes through.
 */
class PuppetRenderer(
	private val model: PuppetModel,
	textures: PuppetTextures,
	private val device: RenderDevice,
) {
	private val pipelines = DrawPipelines(device)

	// Rest-pose content extent, computed lazily and reused for contentBounds()/the fit fallback; re-armed
	// by updateModel/setShownDrawables so the framing follows geometry and visibility edits.
	private var minX = 0f
	private var minY = 0f
	private var spanX = 1f
	private var spanY = 1f
	private var bboxReady = false

	// The view to project through. null until setCamera; render() then fits contentBounds by default.
	private var currentCamera: ViewportCamera? = null

	// The last pose's prepared deform inputs, cached so picking can re-run the CPU deform on demand (at
	// click time, off the render thread) without re-doing it every frame. Immutable once built, so the
	// volatile reference is a safe publish to the UI thread that calls pickGeometry. null before first pose.
	@Volatile
	private var lastPoseInputs: PoseDeformInputs? = null

	// Drawables currently selected, tinted by the highlight uniform when drawn.  The desktop host sets it
	// on the render thread before each render, and a frame reads it once, as it begins.  @Volatile keeps a
	// set from any other thread a safe publish (a wholesale immutable-set swap).
	@Volatile
	private var selectedIds: Set<DrawableId> = emptySet()

	// The color selected drawables are tinted toward (the selection highlight), fed from the editor
	// settings.  Set and read like selectedIds.  Defaults to the classic blue accent until the host pushes
	// the configured color.  RGB, each 0..1; the immutable FloatArray swap makes the volatile reference a
	// safe publish.
	@Volatile
	private var highlightColor: FloatArray = floatArrayOf(0.20f, 0.55f, 1.0f)

	// The active (last-selected) drawable, tinted toward activeHighlightColor instead of highlightColor so
	// the primary target of a multi-selection reads apart from the rest.  Set and read like selectedIds;
	// null when nothing is active.  Always a member of selectedIds when non-null.
	@Volatile
	private var activeId: DrawableId? = null

	// The color the active drawable is tinted toward, fed from the editor settings alongside highlightColor.
	// Defaults to the edit-mode active green (#7DE400) until the host pushes the configured color.
	@Volatile
	private var activeHighlightColor: FloatArray = floatArrayOf(0.49f, 0.89f, 0.0f)

	// The last pose's resolved draw list (back-to-front; last = front), published for picking. This folds
	// in the parts/group hierarchy, so it is the authoritative front/back order. Render-thread-written,
	// UI-thread-read; the immutable list swap is a safe publish.
	@Volatile
	private var lastDrawnOrder: List<DrawableId> = emptyList()

	// Framebuffer pixels per on-screen pixel. 1 = native; the offscreen service sets >1 when it supersamples,
	// so the grid line width scales to match and reads back at a constant on-screen size.
	private var gridPixelScale: Float = 1f

	// The grid backdrop colors, set from the editor theme via setGrid; defaults to a neutral grey grid.
	private var gridColors: GridColors = GridColors.Classic

	// The per-document grid geometry (major line spacing in world units, and subdivisions per major cell).
	private var gridScale: Float = 100f
	private var gridSubdivisions: Int = 10

	// The mask coverage, destination snapshot, and composite layer targets, at their one shared capacity.
	private val sideTargets = SideTargetPool(device)

	// The drawables resident on the GPU, their per-pose stamps, and the glue store they weld through.
	private val residency = DrawableResidency(device)

	// The atlas pages and source artwork those drawables sample.
	private val art = ArtResidency(device, residency, textures)

	// The grid, the world axes, and the flat image underlay, with the underlay's texture cache.
	private val backdrop = BackdropEncoder(device, pipelines)

	// The pose's render plan, recorded as passes: the draws, the mask coverage, and the layer composites.
	private val planEncoder = RenderPlanEncoder(device, pipelines, sideTargets, residency)

	// The mesh overlay: the Edit-mode wireframe, dots, and fills drawn over the art.  The value and the
	// palette are swapped whole by the UI thread; a frame reads each once.  Its device objects are brought
	// to the value between frames, on the render thread, and its store is captured when stale.
	@Volatile
	private var meshOverlay: MeshOverlay? = null

	@Volatile
	private var meshOverlayPalette: MeshOverlayPalette = MeshOverlayPalette.Classic
	private val overlayResidency = MeshOverlayResidency(device)
	private val overlayEncoder = MeshOverlayEncoder(pipelines, sideTargets, overlayResidency)

	// The live model used for the per-pose deform eval, the render order, and the reconcile diff. A var so
	// an edit can re-push it via updateModel. @Volatile because the render thread writes it while the UI
	// thread reads it (pickGeometry); a PuppetModel is immutable, so the reference swap is a safe publish.
	@Volatile
	private var currentModel: PuppetModel = model
	private var baseOrder: List<DrawableId> = model.drawables.map { it.id }
	private var currentRenderRoot: RenderGroup = model.renderRoot

	// Effective Parts-panel visibility (own eyeball ∧ every ancestor part's), resolved once per change. Gates
	// only the drawn list; hidden meshes that are mask sources or glue partners still deform. Render-thread only.
	private var shownDrawableIds: Set<DrawableId> = model.visibleDrawableIds()

	// The pose's resolved render plan (back-to-front with composite boundaries) and the per-isolated-part
	// pose-blended composite channels, both set by setPose and walked by render. Render-thread only.
	private var currentPlan: List<RenderPlanNode> = emptyList()
	private var currentCompositeStates: Map<PartId, PartRenderState> = emptyMap()

	// Per-pose composite acceleration state, derived from the plan by setPose (render-thread only): which
	// isolated parts render() draws inline, and the bounds it scissors the rest to.
	private var compositeAcceleration: CompositeAcceleration = CompositeAcceleration.NONE

	// The two composite accelerations, each independently switchable so a correctness test can render
	// the same pose with either or both disabled and assert every combination is pixel-preserving on
	// real corpus models.  [compositeFlattenEnabled] draws identity Normal/Over groups inline;
	// [compositeBoundsScissorEnabled] confines a composite's layer work (and the empty-layer skip) to
	// the subtree's bounds.  Both on by default.
	internal var compositeFlattenEnabled = true
	internal var compositeBoundsScissorEnabled = true

	// Whether render() draws the world-origin axis lines. Off by default so headless render-diff tests stay
	// line-free; the editor's viewport host opts in.
	private var worldAxesVisible = false

	/**
	 * Creates the pipelines, uploads the atlas page(s), lays out the shared glue store, and uploads each
	 * drawable's static data.  Must run with the device's context current.
	 */
	fun initGl() {
		pipelines.create()
		art.uploadAtlasPages()
		residency.uploadAll(model, currentModel.parameters, art::atlasTextureAtUpload)
	}

	/**
	 * Sets which artwork every drawable would display from.  An empty plan returns the whole puppet to
	 * its atlas pages.
	 *
	 * The plan carries no pixels, so this is cheap.  The pixels follow through
	 * [deliverSourceLayerRasters], and the puppet keeps displaying from its atlas until they ALL have -
	 * the producer streams them in chunks rather than holding the document's artwork decoded at once.
	 *
	 * Render thread only - it frees GPU resources.
	 *
	 * @param LayerDrawPlan plan The mapping to display by, or [LayerDrawPlan.EMPTY] for the atlas.
	 */
	fun setSourceLayerPlan(plan: LayerDrawPlan) {
		art.setSourceLayerPlan(plan)
	}

	/**
	 * Takes delivery of decoded artwork, uploading it for the current plan.
	 *
	 * Render thread only: it creates and destroys GPU resources, so it must run with the context
	 * current.  The engine holds the pending value and calls this from inside the render loop, the way
	 * every other renderer input arrives.
	 *
	 * The batch is consumed, never retained - its images are uploaded and the reference dropped, so the
	 * decoded bytes do not outlive the call.
	 *
	 * @param LayerRasterBatch batch The decoded artwork to take up.
	 */
	fun deliverSourceLayerRasters(batch: LayerRasterBatch) {
		art.deliverSourceLayerRasters(batch)
	}

	/**
	 * Whether the puppet is displaying from source artwork, for tests and diagnostics.
	 *
	 * @return Triple Whether the mode is engaged, the resident layer count, and how many the plan maps.
	 */
	internal fun sourceLayerDisplayState(): Triple<Boolean, Int, Int> = art.sourceLayerDisplayState()

	/**
	 * Swaps the atlas page set: destroys the uploaded pages, uploads [next]'s, and re-stamps every
	 * resident's page binding.  One operation, because freeing a page is only safe together with the
	 * re-stamp that stops drawables sampling it.  Must run with the device's context current.
	 *
	 * @param PuppetTextures next The new page set.
	 */
	fun setAtlasPages(next: PuppetTextures) {
		art.setAtlasPages(next, currentModel)
	}

	/**
	 * Frees every device object this renderer owns.  Must run with the context current, and nothing may
	 * render afterwards.
	 *
	 * The artwork and underlay caches are created and destroyed across the renderer's life rather than
	 * uploaded once, so letting them die with the context is no longer enough: an engine that outlives
	 * one renderer would leak everything the previous one had admitted.  The pipelines are not freed
	 * here because the device seam exposes no way to - they remain context-lifetime objects.
	 */
	fun disposeGl() {
		overlayResidency.dispose()
		residency.dispose()
		art.dispose()
		backdrop.dispose()
	}

	/**
	 * Poses the puppet: evaluates the pose on the CPU, resolves what it draws and in what order, stamps
	 * it onto the resident drawables, and publishes it for picking.  The next [render] draws it.
	 *
	 * Render thread only: it uploads each posed warp parent's control points, so it must run with the
	 * device's context current once anything is resident.  While nothing is resident it touches no
	 * device, so a pose can be evaluated for [posedContentBounds] with no context at all.
	 *
	 * @param Map<ParameterId, Float>          parameters       The parameter values; one left out takes its
	 *   default.
	 * @param Map<KeyableTarget, ChannelValue> channelOverrides Channel values that replace what the pose
	 *   would sample, by target.
	 */
	fun setPose(parameters: Map<ParameterId, Float>, channelOverrides: Map<KeyableTarget, ChannelValue> = emptyMap()) {
		// currentModel (not the construction-time model) so a deformer reparent / reorder re-evaluates here.
		val inputs = preparePose(currentModel, parameters, channelOverrides)
		lastPoseInputs = inputs // publish for on-demand picking (CPU deform re-run at click time)
		residency.glueStoreStale = true // the pose moved: pass 1 must re-deform the shared store next render
		overlayResidency.storeStale = true
		// Resolve first, in backend-neutral terms; then apply onto the resident drawables and upload.
		val resolved =
			resolvePose(
				inputs = inputs,
				renderableById = residency.renderableById,
				shownIds = shownDrawableIds,
				baseOrder = baseOrder,
				renderRoot = currentRenderRoot,
				glueIntensities = residency.glueIntensities,
			)
		residency.applyPose(resolved)
		// resolvePose filled the residency's own glueIntensities in place - no copy needed.
		currentPlan = resolved.renderPlan
		currentCompositeStates = inputs.partCompositeStates
		compositeAcceleration =
			planCompositeAcceleration(
				plan = currentPlan,
				compositeStates = currentCompositeStates,
				residents = residency.residents,
				gluePartnersById = residency.gluePartnersById,
				flattenEnabled = compositeFlattenEnabled,
				boundsScissorEnabled = compositeBoundsScissorEnabled,
			)
		lastDrawnOrder = resolved.drawOrder // publish the resolved back-to-front order for picking
	}

	/**
	 * Sets the view the next [render] projects through.  Before any is set, a render fits the rest-pose
	 * content into its viewport.
	 *
	 * @param ViewportCamera camera The view.
	 */
	fun setCamera(camera: ViewportCamera) {
		currentCamera = camera
	}

	/**
	 * Sets how many framebuffer pixels map to one on-screen pixel, so the grid line width stays a constant
	 * on-screen size when the offscreen service renders supersampled and downscales.  1 = native, 2 = 2×.
	 *
	 * @param Float scale Framebuffer pixels per on-screen pixel.
	 */
	fun setRenderScale(scale: Float) {
		gridPixelScale = scale
	}

	/**
	 * Sets the grid backdrop's colors and geometry, so the viewport can follow the editor theme and the
	 * per-document grid config.  The next [render] picks them up.
	 *
	 * @param GridColors colors       The background / major / minor grid colors.
	 * @param Float      scale        The major grid line spacing in world units.
	 * @param Int        subdivisions The minor lines per major cell.
	 */
	fun setGrid(colors: GridColors, scale: Float, subdivisions: Int) {
		gridColors = colors
		gridScale = scale
		gridSubdivisions = subdivisions
	}

	/**
	 * Sets the mesh overlay the viewport draws over the art, or none.  A whole-value swap the next frame
	 * takes up: it brings its device objects to the value (uploading only what changed by identity) and
	 * captures the overlay meshes' positions.  Safe from any thread; a capture never draws it.
	 *
	 * @param MeshOverlay? overlay The overlay, or null for none.
	 */
	fun setMeshOverlay(overlay: MeshOverlay?) {
		meshOverlay = overlay
	}

	/**
	 * Sets the colors the mesh overlay draws with, from the editor settings; the classic palette until then.
	 *
	 * @param MeshOverlayPalette palette The palette.
	 */
	fun setMeshOverlayPalette(palette: MeshOverlayPalette) {
		meshOverlayPalette = palette
	}

	/**
	 * Sets which drawables are highlighted (object-mode selection).  The next [render] tints them.
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	fun setSelection(ids: Set<DrawableId>) {
		selectedIds = ids
	}

	/**
	 * Sets which drawable is active (the last-selected object of a multi-selection), tinted toward
	 * [activeHighlightColor] rather than [highlightColor]. Null clears the distinction.
	 *
	 * @param DrawableId? id The active drawable id, or null when none is active.
	 */
	fun setActiveSelection(id: DrawableId?) {
		activeId = id
	}

	/**
	 * Updates the set of drawables actually drawn (the resolved visibility cascade), so a visibility edit
	 * takes effect on the next [render].
	 *
	 * @param Set ids The drawable ids to draw.
	 */
	fun setShownDrawables(ids: Set<DrawableId>) {
		if (ids != shownDrawableIds) {
			// The bbox skips hidden drawables, so a visibility change invalidates the cached framing.
			bboxReady = false
		}
		shownDrawableIds = ids
	}

	/**
	 * Shows or hides the world-origin axis lines (the red X / blue Z cross at the model's world origin).
	 *
	 * @param Boolean visible True to draw the axes each frame.
	 */
	fun setWorldAxesVisible(visible: Boolean) {
		worldAxesVisible = visible
	}

	/**
	 * Reconciles the renderer with the current model after an edit, via a backend-neutral [diffModel] whose
	 * four tiers [DrawableResidency.reconcile] applies as device calls: a reorder / reparent needs no
	 * buffer work; a base-mesh move re-uploads positions; a UV edit re-uploads UVs; a structural change
	 * frees and re-uploads whole.
	 *
	 * Structural limits: a session-created drawable never joins the load-time glue layout (glues reference
	 * source ids, so a fresh id welds nothing), and a REMESHED glue mesh degrades to an unwelded draw (its
	 * store region and weld attrs index the old vertex order and are not remapped here).
	 *
	 * The diff compares against [currentModel] and therefore runs BEFORE the reassignment, keeping the
	 * invariant "GPU buffer contents === currentModel's arrays".
	 *
	 * A push that moved mesh positions alone - every preview push of a Grab - keeps the last pose: its
	 * inputs, the render plan, the composite states, and the draw order hold no positions, and the
	 * residents' pose stamps survive because the reconcile reuses the resident instances.  What reads
	 * positions is refreshed here instead - the glue store is marked stale so pass 1 re-captures it, and
	 * the composite acceleration is re-planned over the moved bounds - so the next render is right without
	 * a [setPose].  Reported as [ModelUpdateKind.PositionsOnly] only once a pose exists; before one there
	 * is nothing to keep.
	 *
	 * @param PuppetModel newModel The current model.
	 * @return ModelUpdateKind Whether the caller may keep the pose ([ModelUpdateKind.PositionsOnly]) or
	 *   must rebuild it ([ModelUpdateKind.Structural]).
	 */
	fun updateModel(newModel: PuppetModel): ModelUpdateKind {
		val positionsOnly = lastPoseInputs != null && newModel.differsOnlyInMeshPositions(currentModel)
		residency.reconcile(currentModel, newModel, art::atlasTextureAtUpload)
		// The residents may have been rebuilt and their positions have moved, so the overlay re-pairs (it
		// uploads nothing when nothing of its own changed) and re-captures on the next frame.
		overlayResidency.residencyChanged = true
		overlayResidency.storeStale = true
		currentModel = newModel
		currentRenderRoot = newModel.renderRoot
		baseOrder = newModel.drawables.map { it.id }
		// A rebuilt resident comes back on its atlas page, so re-point the whole set at the artwork the
		// document displays from - otherwise an edit silently drops that drawable back to the atlas.
		art.applySourceLayerDisplay()
		bboxReady = false
		if (!positionsOnly) {
			return ModelUpdateKind.Structural
		}
		// The two pose-derived things that read positions: pass 1 deforms the mesh buffers into the glue
		// store, and the composite scissors walk each resident's rest bounds.
		residency.glueStoreStale = true
		compositeAcceleration =
			planCompositeAcceleration(
				plan = currentPlan,
				compositeStates = currentCompositeStates,
				residents = residency.residents,
				gluePartnersById = residency.gluePartnersById,
				flattenEnabled = compositeFlattenEnabled,
				boundsScissorEnabled = compositeBoundsScissorEnabled,
			)
		return ModelUpdateKind.PositionsOnly
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight).
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		highlightColor = floatArrayOf(red, green, blue)
	}

	/**
	 * Sets the color the active drawable is tinted toward (the active-selection highlight).
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		activeHighlightColor = floatArrayOf(red, green, blue)
	}

	/**
	 * Evaluates the current pose's deformed world geometry on the CPU for hit-testing, or null before the
	 * first pose.  Pure CPU with no device calls, so it is safe from the UI thread; it reuses the immutable
	 * per-pose inputs cached by the last [setPose].
	 *
	 * @return DeformedGeometry The current deformed geometry, or null before the first pose.
	 */
	fun pickGeometry(): DeformedGeometry? {
		val inputs = lastPoseInputs ?: return null
		return applyCpuDeform(currentModel, inputs)
	}

	/**
	 * The world-space extent of what the current pose actually draws, from the same CPU deform as
	 * [pickGeometry] - so, like it, safe from the UI thread.  Where [contentBounds] measures the rest pose
	 * a view fits to, this measures the pose on screen, which is what a capture of it frames.
	 *
	 * A shown drawable whose posed opacity leaves no coverage in an 8-bit image is not measured: a guide or
	 * effect keyed to zero opacity draws nothing, and framing it would pad the capture with empty pixels.
	 * The view fit keeps measuring it, since a rigger fitting the view wants every drawable the rig can show.
	 *
	 * @param Set<DrawableId> shownIds The drawables actually drawn (the resolved visibility cascade).
	 * @return ContentBounds? The extent, or null before the first pose or when nothing drawn has a vertex.
	 */
	fun posedContentBounds(shownIds: Set<DrawableId>): ContentBounds? {
		val geometry = pickGeometry() ?: return null
		val drawnIds = shownIds.filterTo(HashSet()) { drawableId -> (geometry.opacity[drawableId] ?: 0f) >= MINIMUM_DRAWN_OPACITY }
		return contentBoundsOf(geometry, drawnIds)
	}

	/**
	 * The last frame's resolved draw order (back-to-front; last = front), or empty before the first pose -
	 * the hierarchy-correct front/back ranking picking uses to choose among overlapping meshes.
	 *
	 * @return List<DrawableId> The drawn drawables, back-to-front.
	 */
	fun drawnOrder(): List<DrawableId> = lastDrawnOrder

	/**
	 * The world-space extent of the shown drawables at rest - what a view fits to.  A model that shows
	 * nothing frames its canvas instead.  Pure CPU with no device calls.
	 *
	 * @return ContentBounds The extent.
	 */
	fun contentBounds(): ContentBounds {
		ensureContentBounds()
		return ContentBounds(minX, minY, spanX, spanY)
	}

	/**
	 * Draws the current pose into [target].
	 *
	 * The target is explicit rather than the bound framebuffer: a pass that discovered its own target could
	 * not exist on a backend with no bound-framebuffer concept, and even on GL, discovering it hid a real
	 * coupling with whoever bound it first.
	 *
	 * @param RenderTarget  target         The surface to draw into.
	 * @param Int           viewportWidth  The target width in pixels.
	 * @param Int           viewportHeight The target height in pixels.
	 * @param FrameBackdrop backdrop       What the puppet is drawn over: the grid (the viewport), or a flat
	 *   fill (an image capture).
	 */
	fun render(target: RenderTarget, viewportWidth: Int, viewportHeight: Int, backdrop: FrameBackdrop = FrameBackdrop.Grid) {
		// Read once, so the frame applies and draws the same value whatever the UI thread swaps in meanwhile.
		val overlay = meshOverlay
		overlayResidency.apply(overlay, residency.residents, currentModel)
		renderFrame(target, viewportWidth, viewportHeight, backdrop, effectiveCamera(viewportWidth, viewportHeight), gridPixelScale, selectedIds, activeId, overlay)
	}

	/**
	 * Draws the current pose into [target] through the view it is handed.  The viewport hands it the
	 * host's view; an image capture hands it one of its own, and so leaves the host's untouched.
	 *
	 * @param RenderTarget    target         The surface to draw into.
	 * @param Int             viewportWidth  The target width in pixels.
	 * @param Int             viewportHeight The target height in pixels.
	 * @param FrameBackdrop   backdrop       What the puppet is drawn over.
	 * @param ViewportCamera  camera         The view to project through.
	 * @param Float           pixelScale     Framebuffer pixels per on-screen pixel.
	 * @param Set<DrawableId> selected       The drawables tinted as selected.
	 * @param DrawableId?     active         The drawable tinted as active, or null.
	 * @param MeshOverlay?    overlay        The mesh overlay drawn over the art, or null for none; its
	 *   device objects must already reflect it (the viewport applies before each frame, a capture passes null).
	 */
	private fun renderFrame(
		target: RenderTarget,
		viewportWidth: Int,
		viewportHeight: Int,
		backdrop: FrameBackdrop,
		camera: ViewportCamera,
		pixelScale: Float,
		selected: Set<DrawableId>,
		active: DrawableId?,
		overlay: MeshOverlay?,
	) {
		sideTargets.ensure(viewportWidth, viewportHeight)
		val frame = device.beginFrame()

		planEncoder.encodeGlueCapture(frame)
		if (overlay != null) {
			overlayEncoder.encodeCapture(frame)
		}

		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val affine = WorldToNdc(transform[0], transform[1], transform[2], transform[3])

		// Main pass. The grid is an opaque full-screen fill, so it both clears and paints - DontCare load.
		// A flat backdrop is the pass's own clear, and the puppet blends over it exactly as over the grid.
		var pass =
			when (backdrop) {
				FrameBackdrop.Grid -> {
					val gridPass = frame.beginRenderPass(passSpec(target, LoadAction.DontCare, viewportWidth, viewportHeight))
					drawBackdrop(gridPass, affine, viewportWidth, viewportHeight, pixelScale)
					gridPass
				}

				is FrameBackdrop.Clear ->
					frame.beginRenderPass(
						passSpec(
							target,
							LoadAction.Clear,
							viewportWidth,
							viewportHeight,
							clearRed = backdrop.red,
							clearGreen = backdrop.green,
							clearBlue = backdrop.blue,
							clearAlpha = backdrop.alpha,
						),
					)
			}
		val inputs =
			FrameInputs(
				affine = affine,
				viewportWidth = viewportWidth,
				viewportHeight = viewportHeight,
				pixelScale = pixelScale,
				selectedIds = selected,
				activeId = active,
				highlightColor = highlightColor,
				activeHighlightColor = activeHighlightColor,
				boundsScissorEnabled = compositeBoundsScissorEnabled,
				compositeStates = currentCompositeStates,
				acceleration = compositeAcceleration,
				overlay = overlay,
				overlayPalette = meshOverlayPalette,
			)
		pass = planEncoder.encodePlan(frame, inputs, currentPlan, target, pass)
		if (overlay != null) {
			overlayEncoder.encodeDraws(pass, inputs)
		}
		pass.end()
		frame.endFrame()
	}

	/**
	 * Renders the current pose into a CPU image: the puppet as the viewport draws it, over [backdrop], with
	 * no selection tint and no editor chrome.
	 *
	 * The image renders at [SNAPSHOT_SUPERSAMPLE] into a surface of its own and is resolved and read back
	 * synchronously, in tiles of at most [tileEdge] output pixels (and never past what the device can
	 * allocate), stitched into one image.  A tile is the same camera re-centered on its own rectangle, so
	 * the pieces meet exactly: tile edges fall on whole output pixels, and the resolve never averages
	 * across one.
	 *
	 * Nothing the host set is disturbed: the capture draws through a camera, render scale, and empty
	 * selection of its own, and the side targets are released again when the capture grew them, so a
	 * large capture does not leave its high-water allocation behind for the viewport to carry.
	 *
	 * Over a transparent backdrop, a fixed-function Additive drawable adds color but no alpha, and Multiply
	 * scales what is beneath it.  Where nothing lies beneath them they leave no coverage, so they drop out
	 * of the image, as they do wherever the puppet is composited over transparency.
	 *
	 * Must run on the render thread with the device's context current, between frames.  A large capture is
	 * many tiles, so [shouldContinue] is asked before each one: a host shutting down stops the capture at
	 * the next tile rather than waiting for the whole image.
	 *
	 * @param ViewportCamera camera         The view: the world point at the image's center and the output
	 *   pixels per world unit.
	 * @param Int            width          The image width in pixels.
	 * @param Int            height         The image height in pixels.
	 * @param FrameBackdrop  backdrop       What the puppet is drawn over.
	 * @param Int            tileEdge       The largest tile edge in output pixels (tests shrink it to force
	 *   tiling).
	 * @param Function       shouldContinue Asked before each tile; false abandons the capture.
	 * @return RasterImage? The pixels, PREMULTIPLIED, top row first, or null when the capture was abandoned.
	 */
	fun renderSnapshot(
		camera: ViewportCamera,
		width: Int,
		height: Int,
		backdrop: FrameBackdrop,
		tileEdge: Int = SNAPSHOT_TILE_EDGE,
		shouldContinue: () -> Boolean = { true },
	): RasterImage? {
		val previousCapacityWidth = sideTargets.capacityWidth
		val previousCapacityHeight = sideTargets.capacityHeight
		try {
			return captureSnapshot(device, camera, width, height, tileEdge, shouldContinue) { tileTarget, tileCamera, tileWidth, tileHeight ->
				renderFrame(
					tileTarget,
					tileWidth,
					tileHeight,
					backdrop,
					tileCamera,
					pixelScale = SNAPSHOT_SUPERSAMPLE.toFloat(),
					selected = emptySet(),
					active = null,
					overlay = null,
				)
			}
		} finally {
			if (sideTargets.capacityWidth > previousCapacityWidth || sideTargets.capacityHeight > previousCapacityHeight) {
				releaseSideTargets()
			}
		}
	}

	/**
	 * Frees the mask, destination-snapshot, and composite-layer targets and resets their shared capacity,
	 * so the next [render] allocates them afresh at its own size.  The capacity is otherwise grow-only;
	 * this is how a one-off large render gives the memory back.
	 */
	fun releaseSideTargets() {
		sideTargets.release()
	}

	/**
	 * The shared side-target capacity (mask, destination snapshot, composite pool) as (width, height), for
	 * tests that pin a capture's release of it.
	 *
	 * @return Pair<Int, Int> The capacity in pixels.
	 */
	internal fun sideTargetCapacity(): Pair<Int, Int> = sideTargets.capacityWidth to sideTargets.capacityHeight

	/**
	 * Renders one atlas page as a flat, upright underlay for a UV-editor area (instead of the posed
	 * puppet): the themed grid backdrop, then the whole page as a single textured quad.  A null or
	 * out-of-range [pageIndex] paints the grid only.
	 *
	 * The page samples the SAME uploaded atlas texture a drawable binds while the puppet displays from the
	 * atlas, through the same premultiplied fragment shader, so the underlay matches the puppet's texel
	 * rendering exactly.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param Int          pageIndex      The atlas page to draw, or null for none.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun renderAtlasPage(target: RenderTarget, pageIndex: Int?, viewportWidth: Int, viewportHeight: Int) {
		val page = pageIndex?.let { art.pageImage(it) }
		renderUnderlay(target, page, pageIndex?.let { art.pageTexture(it) }, viewportWidth, viewportHeight)
	}

	/**
	 * Draws an arbitrary image as the flat underlay - the UV editor's source-layer view, the pre-atlas
	 * counterpart of [renderAtlasPage].
	 *
	 * Unlike the atlas pages, which upload as a whole set (at init, or wholesale on a [setAtlasPages]
	 * swap) rather than one image at a time, a layer image arrives whenever the editor is pointed at
	 * one, so its texture is created on first sight and cached.  A null image paints the grid only.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param DecodedImage image          The image to draw, or null for none.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun renderUnderlayImage(target: RenderTarget, image: DecodedImage?, viewportWidth: Int, viewportHeight: Int) {
		renderUnderlay(target, image, image?.let { backdrop.underlayTextureFor(it) }, viewportWidth, viewportHeight)
	}

	/**
	 * The flat underlay draw both UV scenes share: the themed grid backdrop, then the image as a single
	 * textured quad at the world origin.  A null image or handle paints the grid alone.
	 *
	 * The quad samples through the same premultiplied fragment shader the puppet uses, so an underlay
	 * matches the puppet's texel rendering exactly.
	 *
	 * @param RenderTarget  target         The surface to draw into.
	 * @param DecodedImage? image          The image whose extent the quad and grid tile take, or null.
	 * @param GpuTexture?   handle         The uploaded texture for [image], or null.
	 * @param Int           viewportWidth  The target width in pixels.
	 * @param Int           viewportHeight The target height in pixels.
	 */
	private fun renderUnderlay(
		target: RenderTarget,
		image: DecodedImage?,
		handle: GpuTexture?,
		viewportWidth: Int,
		viewportHeight: Int,
	) {
		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val affine = WorldToNdc(transform[0], transform[1], transform[2], transform[3])
		// The UV grid's major lines fall on the unit image tile (UV integers), so the major spacing is the
		// image's pixel extent; minor lines subdivide the tile. With no image, fall back to the square grid.
		val majorSpacingX = image?.width?.toFloat() ?: gridScale
		val majorSpacingY = image?.height?.toFloat() ?: gridScale

		// The UV grid's unit tile starts at the image origin (UV 0,0 = image-pixel 0,0), so anchor at (0, 0).
		val grid = GridUniforms(affine, viewportWidth, viewportHeight, 0f, 0f, majorSpacingX, majorSpacingY, gridSubdivisions, gridPixelScale, gridColors)
		backdrop.encodeUnderlay(target, image, handle, grid)
	}

	/**
	 * Draws the grid backdrop and, when enabled, the world-origin axis lines behind the puppet.
	 *
	 * @param RenderPassEncoder pass           The open pass on the frame's target.
	 * @param WorldToNdc        affine         The camera affine.
	 * @param Int               viewportWidth  The viewport width in pixels.
	 * @param Int               viewportHeight The viewport height in pixels.
	 * @param Float             pixelScale     Framebuffer pixels per on-screen pixel.
	 */
	private fun drawBackdrop(pass: RenderPassEncoder, affine: WorldToNdc, viewportWidth: Int, viewportHeight: Int, pixelScale: Float) {
		backdrop.encodeGrid(
			pass,
			GridUniforms(
				affine,
				viewportWidth,
				viewportHeight,
				currentModel.worldOriginX,
				currentModel.worldOriginZ,
				gridScale,
				gridScale,
				gridSubdivisions,
				pixelScale,
				gridColors,
			),
		)
		if (worldAxesVisible) {
			backdrop.encodeWorldAxes(pass, affine, currentModel.worldOriginX, currentModel.worldOriginZ)
		}
	}

	/**
	 * The camera to project through this frame: the one set by [setCamera], or a fit of the rest-pose
	 * content into the viewport before any is set.
	 *
	 * @param Int viewportWidth  The viewport width in pixels.
	 * @param Int viewportHeight The viewport height in pixels.
	 * @return ViewportCamera The camera.
	 */
	private fun effectiveCamera(viewportWidth: Int, viewportHeight: Int): ViewportCamera =
		currentCamera ?: ViewportCamera.fit(contentBounds(), viewportWidth, viewportHeight)

	/**
	 * Computes the rest-pose content bounds lazily, from a CPU eval at default parameters (shown drawables
	 * only).  Re-armed by [updateModel] / [setShownDrawables] so a base-mesh edit re-frames view.fit.  A
	 * model that shows nothing - a new, empty document, or a rig with every drawable hidden - frames its
	 * canvas instead, so the view opens on the work surface rather than on nothing.
	 */
	private fun ensureContentBounds() {
		if (bboxReady) {
			return
		}
		val bounds =
			contentBoundsOf(applyCpuDeform(currentModel, preparePose(currentModel, emptyMap())), shownDrawableIds)
				?: emptyContentBoundsOf(currentModel)
		minX = bounds.minX
		minY = bounds.minY
		spanX = bounds.width
		spanY = bounds.height
		bboxReady = true
	}
}