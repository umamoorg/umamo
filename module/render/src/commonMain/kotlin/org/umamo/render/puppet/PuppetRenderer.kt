package org.umamo.render.puppet

import org.umamo.render.ContentBounds
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.WorldAxisColors
import org.umamo.render.device.AxisLineUniforms
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FragmentUniforms
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.GridUniforms
import org.umamo.render.device.LoadAction
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.WorldToNdc
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.PoseDeformInputs
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.render.eval.WeightedCell
import org.umamo.render.eval.applyCpuDeform
import org.umamo.render.eval.preparePose
import org.umamo.render.glsl.MAX_CORNERS
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.render.glsl.SELECTION_TINT_STRENGTH
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.visibleDrawableIds
import kotlin.concurrent.Volatile

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
 * GPU 変形パペットレンダラ。GL を直接触らず RenderDevice 経由。バックエンドはデバイス実装で差し替える。
 */
class PuppetRenderer(
	private val model: PuppetModel,
	private val textures: PuppetTextures,
	private val device: RenderDevice,
) {
	// Draw pipelines by blend, plus the fixed-purpose ones, all created at initGl and reused every frame.
	private val puppetPipelines = HashMap<BlendMode, RenderPipeline>()
	private val gluePipelines = HashMap<BlendMode, RenderPipeline>()
	private var atlasPagePipeline: RenderPipeline? = null
	private var gridPipeline: RenderPipeline? = null
	private var axisPipeline: RenderPipeline? = null
	private var capturePipeline: DeformCapturePipeline? = null

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

	// Drawables currently selected, tinted by the highlight uniform when drawn. Set from the UI thread
	// (a wholesale immutable-set swap, so a volatile reference is a safe publish); read on the render thread.
	@Volatile
	private var selectedIds: Set<DrawableId> = emptySet()

	// The color selected drawables are tinted toward (the selection highlight), fed from the editor settings
	// on the UI thread and read on the render thread. Defaults to the classic blue accent until the host
	// pushes the configured color. RGB, each 0..1; the immutable FloatArray swap makes the volatile
	// reference a safe publish.
	@Volatile
	private var highlightColor: FloatArray = floatArrayOf(0.20f, 0.55f, 1.0f)

	// The active (last-selected) drawable, tinted toward activeHighlightColor instead of highlightColor so
	// the primary target of a multi-selection reads apart from the rest. Set from the UI thread; null when
	// nothing is active. Always a member of selectedIds when non-null.
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

	// The viewport-sized coverage target the mask pass renders into and the masked draws sample. Recreated
	// on a viewport resize; the same size as the main target, which is what makes the shader's screen-space
	// mask lookup line up.
	private var maskTarget: RenderTarget? = null
	private var maskWidth = 0
	private var maskHeight = 0

	// The shared pass-1 deformed-position store; null when the model has no glue.
	private var store: DeformedPositionStore? = null
	private var glueIntensities = FloatArray(MAX_GLUES) { 1f }

	// Pass 1 only needs to re-deform the shared store when the pose changed; on a static pose its contents
	// are unchanged. Set by setPose, consumed by render. Gating here also confines the write→read barrier
	// to pose-change frames.
	private var glueBufferDirty = true

	// Reused per-draw uniform scratch, refilled each draw rather than allocated. Render-thread only.
	private val deformScratch = DeformUniforms()
	private val fragmentScratch = FragmentUniforms()

	/**
	 * A drawable resident on the GPU.  Static residency (mesh, delta texture, optional warp control-point
	 * texture, atlas) plus the per-pose state setPose stamps onto it.  [isGlueMesh] meshes are deformed in
	 * pass 1 (even index-less anchors, whose positions are weld partners) and rendered via the glue pipeline
	 * in pass 2.
	 */
	private class GpuDrawable(
		val id: DrawableId,
		val mesh: GpuMesh,
		val deltaTexture: GpuTexture,
		val vertexCount: Int,
		val indexCount: Int,
		val cpTexture: GpuTexture?,
		val atlasTexture: GpuTexture?,
		val color: FloatArray,
		val blendMode: BlendMode,
		val maskIds: List<DrawableId>,
		val invertMask: Boolean,
		val isGlueMesh: Boolean,
		val glueBaseOffset: Int,
	) {
		var corners: List<WeightedCell>? = null
		var parentWorld: DeformerWorld? = null
		var opacity: Float = 1f
		var visible: Boolean = false
	}

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
	private var gpuDrawables: List<GpuDrawable> = emptyList()
	private var glueDeformList: List<GpuDrawable> = emptyList()
	private var gpuById: Map<DrawableId, GpuDrawable> = emptyMap()

	// The uploaded atlas pages, index-parallel to PuppetTextures.atlases. Retained so a structural reconcile
	// in updateModel can bind a newly-uploaded drawable to its page.
	private var atlasHandles: List<GpuTexture> = emptyList()

	// Whether render() draws the world-origin axis lines. Off by default so headless render-diff tests stay
	// line-free; the editor's viewport host opts in.
	private var worldAxesVisible = false

	/**
	 * Creates the pipelines, uploads the atlas page(s), lays out the shared glue store, and uploads each
	 * drawable's static data.  Must run with the device's context current.
	 */
	fun initGl() {
		capturePipeline = device.createDeformCapturePipeline()
		atlasPagePipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.AtlasPageDraw, PipelineBlend.Normal))
		gridPipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.GridBackdrop, PipelineBlend.Opaque))
		axisPipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.WorldAxisLine, PipelineBlend.Opaque))
		atlasHandles =
			textures.atlases.map { device.createTexture(it.width, it.height, TextureFormat.Rgba8, TextureFilter.Linear, it.rgba) }
		val warpDeformerIds = model.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()

		// Glue addressing is planned in commonMain; the device holds the store and the interleaved attrs.
		val glueLayout = planGlueLayout(model)
		if (glueLayout.globalVertexCount > 0) {
			store = device.createDeformedPositionStore(glueLayout.globalVertexCount)
		}

		val uploaded = ArrayList<GpuDrawable>()
		for (drawable in model.drawables) {
			val gpuDrawable =
				uploadDrawable(
					drawable = drawable,
					glueAttributes = glueLayout.attributesById[drawable.id],
					glueBaseOffset = glueLayout.baseOffsetById[drawable.id] ?: 0,
					warpDeformerIds = warpDeformerIds,
				) ?: continue
			uploaded.add(gpuDrawable)
		}
		gpuById = uploaded.associateBy { it.id }
		glueDeformList = uploaded.filter { it.isGlueMesh }
	}

	/**
	 * Uploads one drawable's static GPU data and returns its resident [GpuDrawable], or null when it draws
	 * nothing (no mesh, no keyforms, empty geometry, or a triangle-less non-glue mesh).  Shared by [initGl]
	 * and the structural reconcile in [updateModel]; must run with the device's context current.
	 *
	 * @param Drawable                drawable        The model drawable.
	 * @param GlueVertexAttributes?  glueAttributes  Its planned weld attributes, or null when not glued.
	 * @param Int                     glueBaseOffset  Its base index in the shared glue store (0 when not glued).
	 * @param Set<DeformerId>         warpDeformerIds The model's warp deformers.
	 * @return GpuDrawable? The uploaded drawable, or null when it draws nothing.
	 */
	private fun uploadDrawable(
		drawable: Drawable,
		glueAttributes: GlueVertexAttributes?,
		glueBaseOffset: Int,
		warpDeformerIds: Set<DeformerId>,
	): GpuDrawable? {
		val mesh = drawable.mesh ?: return null
		val grid = drawable.keyforms ?: return null
		if (mesh.positions.isEmpty()) {
			return null
		}
		val isGlue = glueAttributes != null
		if (!isGlue && mesh.indices.isEmpty()) {
			return null // a non-glue mesh with no triangles draws nothing and is no weld partner
		}
		val cellCount = keyformCellCount(grid)
		val vertexCount = mesh.positions.size / 2
		val deltaTexture =
			device.createFloatTexture(cellCount, vertexCount, TextureFilter.Nearest, buildDeltaTexels(grid, vertexCount, cellCount))
		val gpuMesh = device.createMesh(MeshSpec(mesh.positions, mesh.uvs, mesh.indices, glueAttributes))
		// A warp-parented drawable needs a control-point texture setPose re-specifies each pose. Created as a
		// 1x1 placeholder here so updateFloatTexture always has a handle to overwrite.
		val cpTexture =
			if (drawable.parentDeformerId in warpDeformerIds) {
				device.createFloatTexture(1, 1, TextureFilter.Nearest, FloatArray(2))
			} else {
				null
			}
		// The atlas mapping is keyed by the SOURCE format's drawable ids, so a session-created copy resolves
		// its page through the drawable it was duplicated from (Drawable.textureSourceId).
		val atlasIndex = textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]
		return GpuDrawable(
			id = drawable.id,
			mesh = gpuMesh,
			deltaTexture = deltaTexture,
			vertexCount = vertexCount,
			indexCount = mesh.indices.size,
			cpTexture = cpTexture,
			atlasTexture = atlasIndex?.let { atlasHandles[it] },
			color = fallbackColorFor(drawable.id.raw),
			blendMode = drawable.blendMode,
			maskIds = drawable.maskedBy,
			invertMask = drawable.invertMask,
			isGlueMesh = isGlue,
			glueBaseOffset = glueBaseOffset,
		)
	}

	/**
	 * Frees one resident drawable's device objects: its mesh and delta / control-point textures.  The atlas
	 * is shared across drawables and stays.  Must run with the device's context current.
	 *
	 * @param GpuDrawable gpuDrawable The resident drawable to free.
	 */
	private fun deleteDrawable(gpuDrawable: GpuDrawable) {
		device.destroyMesh(gpuDrawable.mesh)
		device.destroyTexture(gpuDrawable.deltaTexture)
		gpuDrawable.cpTexture?.let { device.destroyTexture(it) }
	}

	fun setPose(parameters: Map<ParameterId, Float>) {
		// currentModel (not the construction-time model) so a deformer reparent / reorder re-evaluates here.
		val inputs = preparePose(currentModel, parameters)
		lastPoseInputs = inputs // publish for on-demand picking (CPU deform re-run at click time)
		glueBufferDirty = true // the pose moved: pass 1 must re-deform the shared store next render
		// Resolve first, in backend-neutral terms; then apply onto the resident drawables and upload.
		val resolved =
			resolvePose(
				inputs = inputs,
				renderableById = gpuById.mapValues { (_, resident) -> resident.indexCount > 0 },
				shownIds = shownDrawableIds,
				baseOrder = baseOrder,
				renderRoot = currentRenderRoot,
			)
		for (gpuDrawable in gpuById.values) {
			gpuDrawable.visible = false
		}
		for (posedDrawable in resolved.posed.values) {
			val gpuDrawable = gpuById[posedDrawable.drawableId] ?: continue
			gpuDrawable.corners = posedDrawable.corners
			val parentWorld = posedDrawable.parentWorld
			gpuDrawable.parentWorld = parentWorld
			// Warp control points are pose-dependent but frame-INVARIANT: upload them here, once per pose
			// change, NOT every frame in the draw loop. Re-specifying this texture 60×/sec churned the
			// d3d12/Mesa driver and progressively corrupted the sampled control points (the "facial features
			// warp/flicker over time" bug, worst on masked warp meshes that draw twice).
			if (parentWorld is WarpWorld && gpuDrawable.cpTexture != null) {
				device.updateFloatTexture(gpuDrawable.cpTexture, parentWorld.cols + 1, parentWorld.rows + 1, parentWorld.cp)
			}
			gpuDrawable.opacity = posedDrawable.opacity
			gpuDrawable.visible = true
		}
		resolved.glueIntensities.copyInto(glueIntensities)
		gpuDrawables = resolved.drawOrder.mapNotNull { gpuById[it] }
		lastDrawnOrder = resolved.drawOrder // publish the resolved back-to-front order for picking
	}

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
	 * グリッド背景の色と間隔を設定する（テーマ / ドキュメント連動）。次の render で反映。
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
	 * Sets which drawables are highlighted (object-mode selection).  The next [render] tints them.
	 *
	 * ハイライトするドロウアブル（選択）を設定する。
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
	 * アクティブ（最後に選択した）ドロウアブルを設定する。
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
	 * 描画される drawable の集合を更新する。
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
	 * ワールド原点の軸線の表示を切り替える（エディタ用）。
	 *
	 * @param Boolean visible True to draw the axes each frame.
	 */
	fun setWorldAxesVisible(visible: Boolean) {
		worldAxesVisible = visible
	}

	/**
	 * Reconciles the renderer with the current model after an edit, via a backend-neutral [diffModel] whose
	 * four tiers are applied here as device calls: a reorder / reparent needs no buffer work; a base-mesh
	 * move re-uploads positions; a UV edit re-uploads UVs; a structural change frees and re-uploads whole.
	 *
	 * Structural limits: a session-created drawable never joins the load-time glue layout (glues reference
	 * source ids, so a fresh id welds nothing), and a REMESHED glue mesh degrades to an unwelded draw (its
	 * store region and weld attrs index the old vertex order and are not remapped here).
	 *
	 * The diff compares against [currentModel] and therefore runs BEFORE the reassignment, keeping the
	 * invariant "GPU buffer contents === currentModel's arrays".
	 *
	 * 編集後にレンダラを現在のモデルへ整合させる。差分は commonMain、適用のみデバイス呼び出し。
	 *
	 * @param PuppetModel newModel The current model.
	 */
	fun updateModel(newModel: PuppetModel) {
		val warpDeformerIds = newModel.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()
		val diff = diffModel(currentModel, newModel, gpuById.mapValues { (_, resident) -> resident.vertexCount })
		val reconciled = LinkedHashMap<DrawableId, GpuDrawable>()
		for (action in diff.actions) {
			when (action) {
				is DrawableAction.Upload ->
					uploadDrawable(action.drawable, glueAttributes = null, glueBaseOffset = 0, warpDeformerIds = warpDeformerIds)
						?.let { reconciled[action.drawableId] = it }

				is DrawableAction.Reupload -> {
					gpuById[action.drawableId]?.let { deleteDrawable(it) }
					uploadDrawable(action.drawable, glueAttributes = null, glueBaseOffset = 0, warpDeformerIds = warpDeformerIds)
						?.let { reconciled[action.drawableId] = it }
				}

				is DrawableAction.Keep -> {
					val existing = gpuById[action.drawableId] ?: continue
					reconciled[action.drawableId] = existing
					action.positions?.let { device.updateMeshPositions(existing.mesh, it) }
					action.uvs?.let { device.updateMeshUvs(existing.mesh, it) }
				}
			}
		}
		// Free residents the edit dropped. A Reupload already freed its own and is never in `removed`, so
		// nothing is freed twice.
		for (drawableId in diff.removed) {
			gpuById[drawableId]?.let { deleteDrawable(it) }
		}
		gpuById = reconciled
		glueDeformList = reconciled.values.filter { it.isGlueMesh }
		currentModel = newModel
		currentRenderRoot = newModel.renderRoot
		baseOrder = newModel.drawables.map { it.id }
		bboxReady = false
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight).
	 *
	 * 選択ハイライトの色を設定する。
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
	 * アクティブ選択ハイライトの色を設定する。
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
	 * ピッキング用に現在ポーズの変形ジオメトリを CPU 評価する（UI スレッドから安全）。
	 *
	 * @return DeformedGeometry The current deformed geometry, or null before the first pose.
	 */
	fun pickGeometry(): DeformedGeometry? {
		val inputs = lastPoseInputs ?: return null
		return applyCpuDeform(currentModel, inputs)
	}

	/**
	 * The last frame's resolved draw order (back-to-front; last = front), or empty before the first pose -
	 * the hierarchy-correct front/back ranking picking uses to choose among overlapping meshes.
	 *
	 * 最後のフレームの解決済み描画順（背面→前面）。
	 *
	 * @return List<DrawableId> The drawn drawables, back-to-front.
	 */
	fun drawnOrder(): List<DrawableId> = lastDrawnOrder

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
	 * @param RenderTarget target         The surface to draw into.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun render(target: RenderTarget, viewportWidth: Int, viewportHeight: Int) {
		ensureMaskTarget(viewportWidth, viewportHeight)
		val frame = device.beginFrame()

		// Pass 1: capture every glue mesh's deformed positions into the shared store. Only when the pose
		// changed - a static pose leaves the store (and pass 2's reads of it) unchanged.
		val activeStore = store
		if (activeStore != null && glueBufferDirty) {
			val capture = frame.beginDeformCapturePass(capturePipeline!!, activeStore)
			for (gpuDrawable in glueDeformList) {
				if (gpuDrawable.corners == null) {
					continue
				}
				fillDeform(deformScratch, gpuDrawable)
				capture.captureDeformedPositions(
					gpuDrawable.mesh,
					deformScratch,
					DrawTextures(deltaTexture = gpuDrawable.deltaTexture, warpControlPoints = gpuDrawable.cpTexture),
					gpuDrawable.glueBaseOffset,
					gpuDrawable.vertexCount,
				)
			}
			capture.end()
			frame.barrier(activeStore)
			glueBufferDirty = false
		}

		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val affine = WorldToNdc(transform[0], transform[1], transform[2], transform[3])

		// Main pass. The grid is an opaque full-screen fill, so it both clears and paints - DontCare load.
		var pass = frame.beginRenderPass(passSpec(target, LoadAction.DontCare, viewportWidth, viewportHeight))
		drawBackdrop(pass, affine, viewportWidth, viewportHeight)

		for (gpuDrawable in gpuDrawables) {
			var maskCoverage: GpuTexture? = null
			if (gpuDrawable.maskIds.isNotEmpty()) {
				// Mask by pass fragmentation: end the main pass, render coverage into the mask target, resume
				// the main pass preserving what is drawn so far. Correct on every backend and non-nesting.
				pass.end()
				renderMaskCoverage(frame, gpuDrawable.maskIds, affine)
				pass = frame.beginRenderPass(passSpec(target, LoadAction.Load, viewportWidth, viewportHeight))
				maskCoverage = maskTarget?.sampledTexture
			}
			val masked = maskCoverage != null
			val isActive = activeId != null && gpuDrawable.id == activeId
			val highlight = if (isActive || gpuDrawable.id in selectedIds) SELECTION_TINT_STRENGTH else 0f
			drawDrawable(pass, gpuDrawable, affine, viewportWidth, viewportHeight, gpuDrawable.opacity, highlight, isActive, masked, maskCoverage)
		}
		pass.end()
		frame.endFrame()
	}

	/**
	 * Renders one atlas page as a flat, upright underlay for a UV-editor area (instead of the posed
	 * puppet): the themed grid backdrop, then the whole page as a single textured quad.  A null or
	 * out-of-range [pageIndex] paints the grid only.
	 *
	 * The page samples the SAME atlas texture the puppet does, through the same premultiplied fragment
	 * shader, so the underlay matches the puppet's texel rendering exactly.
	 *
	 * @param RenderTarget target         The surface to draw into.
	 * @param Int          pageIndex      The atlas page to draw, or null for none.
	 * @param Int          viewportWidth  The target width in pixels.
	 * @param Int          viewportHeight The target height in pixels.
	 */
	fun renderAtlasPage(target: RenderTarget, pageIndex: Int?, viewportWidth: Int, viewportHeight: Int) {
		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val affine = WorldToNdc(transform[0], transform[1], transform[2], transform[3])
		val page = pageIndex?.let { textures.atlases.getOrNull(it) }
		// The UV grid's major lines fall on the unit atlas tile (UV integers), so the major spacing is the
		// page's pixel extent; minor lines subdivide the tile. With no page, fall back to the square grid.
		val majorSpacingX = page?.width?.toFloat() ?: gridScale
		val majorSpacingY = page?.height?.toFloat() ?: gridScale

		val frame = device.beginFrame()
		val pass = frame.beginRenderPass(passSpec(target, LoadAction.DontCare, viewportWidth, viewportHeight))
		pass.setPipeline(gridPipeline!!)
		// The UV grid's unit tile starts at the page origin (UV 0,0 = page-pixel 0,0), so anchor at (0, 0).
		pass.drawGrid(
			GridUniforms(affine, viewportWidth, viewportHeight, 0f, 0f, majorSpacingX, majorSpacingY, gridSubdivisions, gridPixelScale, gridColors),
		)
		val handle = pageIndex?.let { atlasHandles.getOrNull(it) }
		if (page != null && handle != null) {
			pass.setPipeline(atlasPagePipeline!!)
			pass.setCamera(affine, viewportWidth, viewportHeight)
			fragmentScratch.reset()
			fragmentScratch.useTexture = true
			pass.drawAtlasPage(handle, page.width.toFloat(), page.height.toFloat(), fragmentScratch)
		}
		pass.end()
		frame.endFrame()
	}

	/** Draws the grid backdrop and, when enabled, the world-origin axis lines behind the puppet. */
	private fun drawBackdrop(pass: RenderPassEncoder, affine: WorldToNdc, viewportWidth: Int, viewportHeight: Int) {
		pass.setPipeline(gridPipeline!!)
		pass.drawGrid(
			GridUniforms(
				affine,
				viewportWidth,
				viewportHeight,
				currentModel.worldOriginX,
				currentModel.worldOriginY,
				gridScale,
				gridScale,
				gridSubdivisions,
				gridPixelScale,
				gridColors,
			),
		)
		if (worldAxesVisible) {
			// The axes sit between the backdrop and the drawables, reading as part of the canvas.
			val originNdcX = affine.scaleX * currentModel.worldOriginX + affine.offsetX
			val originNdcY = affine.scaleY * currentModel.worldOriginY + affine.offsetY
			val axisColors = WorldAxisColors.Classic
			pass.setPipeline(axisPipeline!!)
			pass.drawAxisLine(AxisLineUniforms(originNdcY, vertical = false, axisColors.xRed, axisColors.xGreen, axisColors.xBlue))
			pass.drawAxisLine(AxisLineUniforms(originNdcX, vertical = true, axisColors.zRed, axisColors.zGreen, axisColors.zBlue))
		}
	}

	/** Renders the mask sources' coverage into the shared mask target, as its own cleared pass. */
	private fun renderMaskCoverage(frame: org.umamo.render.device.FrameEncoder, maskIds: List<DrawableId>, affine: WorldToNdc) {
		val target = maskTarget ?: return
		val coverage = frame.beginRenderPass(passSpec(target, LoadAction.Clear, maskWidth, maskHeight, clearAlpha = 0f))
		for (maskId in maskIds) {
			val mask = gpuById[maskId] ?: continue
			if (!mask.visible || mask.indexCount == 0) {
				continue
			}
			// Coverage is the mask's shape at full intensity, unmasked, always Normal blend.
			drawDrawable(coverage, mask, affine, maskWidth, maskHeight, opacity = 1f, highlight = 0f, isActive = false, masked = false, maskCoverage = null)
		}
		coverage.end()
	}

	/** Binds a drawable's pipeline + per-pose state and issues its draw into [pass]. */
	private fun drawDrawable(
		pass: RenderPassEncoder,
		gpuDrawable: GpuDrawable,
		affine: WorldToNdc,
		viewportWidth: Int,
		viewportHeight: Int,
		opacity: Float,
		highlight: Float,
		isActive: Boolean,
		masked: Boolean,
		maskCoverage: GpuTexture?,
	) {
		pass.setPipeline(pipelineFor(gpuDrawable.isGlueMesh, gpuDrawable.blendMode))
		pass.setCamera(affine, viewportWidth, viewportHeight)
		fillFragment(fragmentScratch, gpuDrawable, opacity, highlight, isActive, masked)
		val textures =
			DrawTextures(
				atlas = gpuDrawable.atlasTexture,
				maskCoverage = maskCoverage,
				deltaTexture = gpuDrawable.deltaTexture,
				warpControlPoints = gpuDrawable.cpTexture,
			)
		if (gpuDrawable.isGlueMesh) {
			pass.drawGlueMesh(gpuDrawable.mesh, store!!, gpuDrawable.glueBaseOffset, glueIntensities, fragmentScratch, textures)
		} else {
			fillDeform(deformScratch, gpuDrawable)
			pass.drawPuppetMesh(gpuDrawable.mesh, deformScratch, fragmentScratch, textures)
		}
	}

	/** The cached draw pipeline for a glue vs non-glue mesh at a blend mode. */
	private fun pipelineFor(isGlueMesh: Boolean, blendMode: BlendMode): RenderPipeline {
		val cache = if (isGlueMesh) gluePipelines else puppetPipelines
		return cache.getOrPut(blendMode) {
			val purpose = if (isGlueMesh) PipelinePurpose.PuppetGlueDraw else PipelinePurpose.PuppetDeformDraw
			device.createRenderPipeline(RenderPipelineSpec(purpose, blendOf(blendMode)))
		}
	}

	/** Fills a drawable's per-pose deform uniforms (active corners + baked parent transform). */
	private fun fillDeform(deform: DeformUniforms, gpuDrawable: GpuDrawable) {
		val corners = gpuDrawable.corners ?: return
		val cornerCount = minOf(MAX_CORNERS, corners.size)
		deform.cornerCount = cornerCount
		for (cornerIndex in 0 until cornerCount) {
			deform.cornerCell[cornerIndex] = corners[cornerIndex].linearIndex
			deform.cornerWeight[cornerIndex] = corners[cornerIndex].weight
		}
		when (val parentWorld = gpuDrawable.parentWorld) {
			is RotationWorld -> {
				deform.parentType = 1
				val xform = parentWorld.xform
				deform.rotation[0] = xform.c12
				deform.rotation[1] = xform.c13
				deform.rotation[2] = xform.c14
				deform.rotation[3] = xform.c15
				deform.rotation[4] = xform.ox
				deform.rotation[5] = xform.oy
			}

			is WarpWorld -> {
				deform.parentType = 2
				deform.warpColumns = parentWorld.cols
				deform.warpRows = parentWorld.rows
				deform.warpBilinear = parentWorld.bilinear
			}

			else -> deform.parentType = 0
		}
	}

	/** Fills a drawable's fragment uniforms (texture-or-color, opacity, mask flags, highlight). */
	private fun fillFragment(
		fragment: FragmentUniforms,
		gpuDrawable: GpuDrawable,
		opacity: Float,
		highlight: Float,
		isActive: Boolean,
		masked: Boolean,
	) {
		fragment.reset()
		if (gpuDrawable.atlasTexture != null) {
			fragment.useTexture = true
		} else {
			fragment.colorRed = gpuDrawable.color[0]
			fragment.colorGreen = gpuDrawable.color[1]
			fragment.colorBlue = gpuDrawable.color[2]
			fragment.colorAlpha = gpuDrawable.color[3]
		}
		fragment.opacity = opacity
		fragment.useMask = masked
		fragment.invertMask = masked && gpuDrawable.invertMask
		fragment.highlight = highlight
		val tint = if (isActive) activeHighlightColor else highlightColor
		fragment.highlightRed = tint[0]
		fragment.highlightGreen = tint[1]
		fragment.highlightBlue = tint[2]
	}

	/**
	 * The camera to project through this frame: the one set by [setCamera], or a fit of the rest-pose
	 * content into the viewport before any is set.
	 */
	private fun effectiveCamera(viewportWidth: Int, viewportHeight: Int): ViewportCamera =
		currentCamera ?: ViewportCamera.fit(contentBounds(), viewportWidth, viewportHeight)

	/**
	 * Computes the rest-pose content bounds lazily, from a CPU eval at default parameters (shown drawables
	 * only).  Re-armed by [updateModel] / [setShownDrawables] so a base-mesh edit re-frames view.fit.
	 */
	private fun ensureContentBounds() {
		if (bboxReady) {
			return
		}
		val bounds = contentBoundsOf(applyCpuDeform(currentModel, preparePose(currentModel, emptyMap())), shownDrawableIds)
		minX = bounds.minX
		minY = bounds.minY
		spanX = bounds.width
		spanY = bounds.height
		bboxReady = true
	}

	/** (Re)allocates the viewport-sized coverage target the mask pass renders into. */
	private fun ensureMaskTarget(viewportWidth: Int, viewportHeight: Int) {
		if (maskTarget != null && maskWidth == viewportWidth && maskHeight == viewportHeight) {
			return
		}
		maskTarget?.let { device.destroyRenderTarget(it) }
		maskTarget = device.createRenderTarget(RenderTargetSpec(viewportWidth, viewportHeight, TextureFormat.Rgba8, sampled = true))
		maskWidth = viewportWidth
		maskHeight = viewportHeight
	}

	/** A render-pass spec for [target] at [load], with an optional clear. */
	private fun passSpec(
		target: RenderTarget,
		load: LoadAction,
		viewportWidth: Int,
		viewportHeight: Int,
		clearAlpha: Float = 0f,
	) = org.umamo.render.device.RenderPassSpec(
		colorTarget = target,
		loadAction = load,
		viewportWidth = viewportWidth,
		viewportHeight = viewportHeight,
		clearAlpha = clearAlpha,
	)

	private fun blendOf(mode: BlendMode): PipelineBlend =
		when (mode) {
			BlendMode.Normal -> PipelineBlend.Normal
			BlendMode.Additive -> PipelineBlend.Additive
			BlendMode.Multiply -> PipelineBlend.Multiply
		}
}

/** Resets every field to its "nothing set" default, for reuse across draws. */
private fun FragmentUniforms.reset() {
	useTexture = false
	colorRed = 0f
	colorGreen = 0f
	colorBlue = 0f
	colorAlpha = 0f
	opacity = 1f
	useMask = false
	invertMask = false
	highlight = 0f
	highlightRed = 0f
	highlightGreen = 0f
	highlightBlue = 0f
}
