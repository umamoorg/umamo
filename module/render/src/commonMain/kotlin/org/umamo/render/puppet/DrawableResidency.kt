package org.umamo.render.puppet

import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.TextureFilter
import org.umamo.render.eval.WarpWorld
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

/**
 * The drawables resident on the GPU: what is uploaded, what an edit re-uploads or frees, and the
 * per-pose state stamped onto each resident.
 *
 * It applies decisions made elsewhere and makes none of its own: [planGlueLayout] says what welds to
 * what, [diffModel] says which reconcile tier an edit falls into, and [resolvePose] says what poses.
 * This class turns each into device calls and keeps the residents they produce.
 *
 * Glue state lives here rather than in an owner of its own because it follows residency's two
 * lifecycles: the layout, the store, the glue mesh list, and the partner map follow every upload and
 * reconcile (an edit that moves a weld re-plans the layout, and every upload takes its entry in it), and
 * the intensities and the stale flag move with each pose.
 *
 * The atlas page a drawable samples is looked up through a function handed in per call, never held:
 * the art textures are owned elsewhere and re-stamped onto the residents this class builds.
 *
 * Render thread only.  Constructing it touches no device, and neither does [applyPose] while nothing is
 * resident; every other call must run with the device's context current.
 *
 * @property RenderDevice device The backend the residents live on.
 */
internal class DrawableResidency(
	private val device: RenderDevice,
) {
	/** The resident drawables by id, in the order they were uploaded. */
	var residents: Map<DrawableId, GpuDrawable> = emptyMap()
		private set

	/**
	 * Which resident drawables carry triangles (id → indexCount > 0).  Residency changes only at
	 * [uploadAll] and [reconcile], and a pose is far hotter (every pose tick during a drag), so this is
	 * cached and rebuilt only where [residents] itself changes rather than mapped afresh every pose.
	 */
	var renderableById: Map<DrawableId, Boolean> = emptyMap()
		private set

	/** The resident glue meshes, which pass 1 deforms into [positionStore]. */
	var glueDeformList: List<GpuDrawable> = emptyList()
		private set

	/**
	 * Glue weld partners by drawable (both directions), rebuilt with residency: a glue mesh's welded
	 * vertices stay inside the hull of its own and its partners' unwelded bounds, so the bounds walk
	 * unions the partner extents in.
	 */
	var gluePartnersById: Map<DrawableId, List<DrawableId>> = emptyMap()
		private set

	/** The shared pass-1 deformed-position store; null while the glue layout places no glue mesh. */
	var positionStore: DeformedPositionStore? = null
		private set

	// The store's vertex capacity: it grows when a layout outgrows it and stays when a layout shrinks.
	private var positionStoreCapacity = 0

	/** The glue layout the residents were uploaded with: each glue mesh's weld attributes and store region. */
	var glueLayout: GlueLayout = GlueLayout.EMPTY
		private set

	/** Per-glue weld intensity by glue index, refilled in place by each pose's resolve. */
	val glueIntensities: FloatArray = FloatArray(MAX_GLUES) { 1f }

	/**
	 * Whether [positionStore] is behind the pose.  Pass 1 only needs to re-deform the shared store when
	 * the pose changed; on a static pose its contents are unchanged.  Set by a pose, cleared by the
	 * render that captures.  Gating here also confines the write→read barrier to pose-change frames.
	 */
	var glueStoreStale: Boolean = true

	/**
	 * Lays out the shared glue store and uploads each drawable's static data.  Must run with the device's
	 * context current.
	 *
	 * @param PuppetModel     model             The model to upload.
	 * @param List<Parameter> defaultParameters The parameters whose defaults blend-shape deltas are
	 *   baked against.
	 * @param Function        atlasTextureOf    The atlas page a drawable samples, or null when it has none.
	 */
	fun uploadAll(model: PuppetModel, defaultParameters: List<Parameter>, atlasTextureOf: (Drawable) -> GpuTexture?) {
		val warpDeformerIds = model.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()

		// Glue addressing is planned in commonMain; the device holds the store and the interleaved attrs.
		glueLayout = planGlueLayout(model)
		fitGlueStore(glueLayout.globalVertexCount)

		val uploaded = ArrayList<GpuDrawable>()
		for (drawable in model.drawables) {
			val gpuDrawable =
				uploadDrawable(
					drawable = drawable,
					glueLayout = glueLayout,
					warpDeformerIds = warpDeformerIds,
					defaultParameters = defaultParameters,
					atlasTextureOf = atlasTextureOf,
				) ?: continue
			uploaded.add(gpuDrawable)
		}
		residents = uploaded.associateBy { it.id }
		glueDeformList = uploaded.filter { it.isGlueMesh }
		renderableById = residents.mapValues { (_, resident) -> resident.indexCount > 0 }
		rebuildGluePartners(model)
	}

	/**
	 * Reconciles residency with the current model after an edit, via a backend-neutral [diffModel] whose
	 * four tiers are applied here as device calls: a reorder / reparent needs no buffer work; a base-mesh
	 * move re-uploads positions; a UV edit re-uploads UVs; a structural change frees and re-uploads whole.
	 *
	 * The glue layout follows the model: an edit that moves a weld (a re-paired, added, removed, or
	 * reordered glue, or a glue mesh that changed size or place) re-plans it, re-uploads every resident
	 * whose weld attributes or store region moved, and fits the store to it, freeing the store once no
	 * glue is left; every upload takes its entry in the current layout.  An intensity, channel, move, or
	 * key edit re-plans nothing.  So after any reconcile the residents' glue state is what [uploadAll]
	 * of [newModel] would produce.
	 *
	 * @param PuppetModel residentModel  The model the residents currently reflect.  Blend-shape deltas
	 *   uploaded here are baked against ITS parameter defaults, not [newModel]'s.
	 * @param PuppetModel newModel       The current model.
	 * @param Function    atlasTextureOf The atlas page a drawable samples, or null when it has none.
	 * @pre Call BEFORE the caller reassigns its current model, keeping the invariant "GPU buffer contents
	 *   === the current model's arrays".
	 */
	fun reconcile(residentModel: PuppetModel, newModel: PuppetModel, atlasTextureOf: (Drawable) -> GpuTexture?) {
		val warpDeformerIds = newModel.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()
		val diff = diffModel(residentModel, newModel, residents.mapValues { (_, resident) -> resident.vertexCount })
		val previousLayout = glueLayout
		val layout = if (glueLayoutFits(previousLayout, newModel)) previousLayout else planGlueLayout(newModel)
		val replanned = layout !== previousLayout
		if (replanned) {
			fitGlueStore(layout.globalVertexCount)
		}
		val reconciled = LinkedHashMap<DrawableId, GpuDrawable>()
		for (action in diff.actions) {
			when (action) {
				is DrawableAction.Upload ->
					uploadDrawable(
						action.drawable,
						glueLayout = layout,
						warpDeformerIds = warpDeformerIds,
						defaultParameters = residentModel.parameters,
						atlasTextureOf = atlasTextureOf,
					)?.let { reconciled[action.drawableId] = it }

				is DrawableAction.Reupload -> {
					residents[action.drawableId]?.let { deleteDrawable(it) }
					uploadDrawable(
						action.drawable,
						glueLayout = layout,
						warpDeformerIds = warpDeformerIds,
						defaultParameters = residentModel.parameters,
						atlasTextureOf = atlasTextureOf,
					)?.let { reconciled[action.drawableId] = it }
				}

				is DrawableAction.Keep -> {
					val existing = residents[action.drawableId] ?: continue
					if (replanned && glueEntryChanged(previousLayout, layout, action.drawableId)) {
						// A mesh's weld attributes and store region are fixed at upload, so a moved entry
						// re-uploads the mesh whole.
						deleteDrawable(existing)
						uploadDrawable(
							action.drawable,
							glueLayout = layout,
							warpDeformerIds = warpDeformerIds,
							defaultParameters = residentModel.parameters,
							atlasTextureOf = atlasTextureOf,
						)?.let { reconciled[action.drawableId] = it }
						continue
					}
					reconciled[action.drawableId] = existing
					// Re-stamp the static composite state from the edited drawable: a blend/alpha/culling/
					// mask/invert edit does no buffer work, so it lands here, and these were otherwise
					// frozen at upload (isExtendedBlend is a getter, so draw routing updates for free).
					existing.blendMode = action.drawable.blendMode
					existing.alphaBlendMode = action.drawable.alphaBlendMode
					existing.culling = action.drawable.culling
					existing.maskIds = action.drawable.maskedBy
					existing.invertMask = action.drawable.invertMask
					action.positions?.let {
						device.updateMeshPositions(existing.mesh, it)
						// Re-point the bounds walk at the new rest positions too, or the composite scissor
						// would keep sizing to the pre-edit geometry and clip the moved vertices.
						existing.boundsBase = it
					}
					action.uvs?.let { device.updateMeshUvs(existing.mesh, it) }
				}
			}
		}
		// Free residents the edit dropped. A Reupload already freed its own and is never in `removed`, so
		// nothing is freed twice.
		for (drawableId in diff.removed) {
			residents[drawableId]?.let { deleteDrawable(it) }
		}
		residents = reconciled
		glueDeformList = reconciled.values.filter { it.isGlueMesh }
		renderableById = reconciled.mapValues { (_, resident) -> resident.indexCount > 0 }
		rebuildGluePartners(newModel)
		glueLayout = layout
	}

	/**
	 * Stamps a resolved pose onto the residents and uploads each posed warp parent's control points.
	 * Issues no device call while nothing is resident.
	 *
	 * @param ResolvedPose resolved The pose, resolved against [renderableById].
	 */
	fun applyPose(resolved: ResolvedPose) {
		for (gpuDrawable in residents.values) {
			gpuDrawable.visible = false
		}
		for (posedDrawable in resolved.posed.values) {
			val gpuDrawable = residents[posedDrawable.drawableId] ?: continue
			gpuDrawable.corners = posedDrawable.corners
			val parentWorld = posedDrawable.parentWorld
			gpuDrawable.parentWorld = parentWorld
			// Warp control points are pose-dependent but frame-INVARIANT: upload them here, once per pose
			// change, NOT every frame in the draw loop. Re-specifying this texture 60×/sec would churn the
			// d3d12/Mesa driver and progressively corrupt the sampled control points, causing visible
			// warp flicker over time - worst on masked warp meshes, which draw twice per frame.
			if (parentWorld is WarpWorld && gpuDrawable.cpTexture != null) {
				device.updateFloatTexture(gpuDrawable.cpTexture, parentWorld.cols + 1, parentWorld.rows + 1, parentWorld.cp)
			}
			gpuDrawable.opacity = posedDrawable.opacity
			gpuDrawable.multiplyColor = posedDrawable.multiplyColor
			gpuDrawable.screenColor = posedDrawable.screenColor
			gpuDrawable.blend = posedDrawable.blend
			gpuDrawable.visible = true
		}
	}

	/**
	 * Frees every resident drawable's device objects and the glue store, and empties the residency.  Must
	 * run with the device's context current.
	 */
	fun dispose() {
		for (gpuDrawable in residents.values) {
			deleteDrawable(gpuDrawable)
		}
		positionStore?.let { store -> device.destroyDeformedPositionStore(store) }
		positionStore = null
		positionStoreCapacity = 0
		glueLayout = GlueLayout.EMPTY
		residents = emptyMap()
		glueDeformList = emptyList()
		renderableById = emptyMap()
	}

	/**
	 * Uploads one drawable's static GPU data and returns its resident [GpuDrawable], or null when it draws
	 * nothing (no mesh, no keyforms, empty geometry, or a triangle-less non-glue mesh).  Shared by
	 * [uploadAll] and the structural reconcile in [reconcile]; must run with the device's context current.
	 *
	 * @param Drawable              drawable          The model drawable.
	 * @param GlueLayout            glueLayout        The glue layout; the drawable's entry in it, if any, gives
	 *   its weld attributes and its base index in the shared store.
	 * @param Set<DeformerId>       warpDeformerIds   The model's warp deformers.
	 * @param List<Parameter>       defaultParameters The parameters whose defaults blend-shape deltas are
	 *   baked against.
	 * @param Function              atlasTextureOf    The atlas page a drawable samples, or null when it has none.
	 * @return GpuDrawable? The uploaded drawable, or null when it draws nothing.
	 */
	private fun uploadDrawable(
		drawable: Drawable,
		glueLayout: GlueLayout,
		warpDeformerIds: Set<DeformerId>,
		defaultParameters: List<Parameter>,
		atlasTextureOf: (Drawable) -> GpuTexture?,
	): GpuDrawable? {
		val mesh = drawable.mesh ?: return null
		// A null grid is an UNKEYED drawable, which uploads a single all-zero delta column and renders at
		// its rest mesh. Refusing to upload it would leave a freshly created (or freshly unbound) drawable
		// invisible - the one state where the rigger most needs to see it.
		val grid = drawable.geometryGrid
		if (mesh.positions.isEmpty()) {
			return null
		}
		val glueAttributes = glueLayout.attributesById[drawable.id]
		val isGlue = glueAttributes != null
		if (!isGlue && mesh.indices.isEmpty()) {
			return null // a non-glue mesh with no triangles draws nothing and is no weld partner
		}
		val cellCount = keyformCellCount(grid)
		val vertexCount = mesh.positions.size / 2
		// Built once and shared: the delta-texel bake and the composite-bounds walk both need it. An
		// unkeyed drawable has no cells, so every lookup misses and contributes a zero offset.
		val cells = if (grid != null) cellsByLinearIndex(grid) else emptyMap()
		// Blend-shape delta columns append after the grid cells; the zero-blend layout is empty and
		// the texture build reduces to the plain grid texels.
		val blendLayout = blendColumnLayout(drawable, cellCount)
		val defaults = defaultParameters.associate { it.id to it.default }
		val texels =
			if (blendLayout.blendColumnCount > 0) {
				buildDeltaTexelsWithBlend(grid, drawable, { defaults[it] ?: 0f }, vertexCount, blendLayout, cells)
			} else {
				buildDeltaTexels(grid, vertexCount, cellCount, cells)
			}
		val deltaTexture =
			device.createFloatTexture(cellCount + blendLayout.blendColumnCount, vertexCount, TextureFilter.Nearest, texels)
		val gpuMesh = device.createMesh(MeshSpec(mesh.positions, mesh.uvs, mesh.indices, glueAttributes))
		// A warp-parented drawable needs a control-point texture a pose re-specifies. Created as a
		// 1x1 placeholder here so updateFloatTexture always has a handle to overwrite.
		val cpTexture =
			if (drawable.parentDeformerId in warpDeformerIds) {
				device.createFloatTexture(1, 1, TextureFilter.Nearest, FloatArray(2))
			} else {
				null
			}
		return GpuDrawable(
			id = drawable.id,
			mesh = gpuMesh,
			deltaTexture = deltaTexture,
			vertexCount = vertexCount,
			indexCount = mesh.indices.size,
			cpTexture = cpTexture,
			atlasTexture = atlasTextureOf(drawable),
			color = fallbackColorFor(drawable.id.raw),
			blendMode = drawable.blendMode,
			alphaBlendMode = drawable.alphaBlendMode,
			culling = drawable.culling,
			maskIds = drawable.maskedBy,
			invertMask = drawable.invertMask,
			isGlueMesh = isGlue,
			glueBaseOffset = glueLayout.baseOffsetById[drawable.id] ?: 0,
			blendLayout = blendLayout,
			boundsBase = mesh.positions,
			boundsCells = cells,
		)
	}

	/**
	 * Frees one resident drawable's device objects: its mesh and delta / control-point textures.  The atlas
	 * and any source-artwork texture are shared across drawables and stay.  Must run with the device's
	 * context current.
	 *
	 * @param GpuDrawable gpuDrawable The resident drawable to free.
	 */
	private fun deleteDrawable(gpuDrawable: GpuDrawable) {
		device.destroyMesh(gpuDrawable.mesh)
		device.destroyTexture(gpuDrawable.deltaTexture)
		gpuDrawable.cpTexture?.let { device.destroyTexture(it) }
	}

	/**
	 * Fits the shared glue store to a layout's vertex count: frees it when no glue mesh is placed, replaces
	 * it with a larger one when the layout outgrows it (the new store is behind every pose, so it is
	 * marked stale), and otherwise keeps it, larger than needed.  Must run with the device's context
	 * current.
	 *
	 * @param Int vertexCount The layout's total glue vertex count.
	 */
	private fun fitGlueStore(vertexCount: Int) {
		if (vertexCount == 0) {
			positionStore?.let { store -> device.destroyDeformedPositionStore(store) }
			positionStore = null
			positionStoreCapacity = 0
			return
		}
		if (vertexCount > positionStoreCapacity) {
			positionStore?.let { store -> device.destroyDeformedPositionStore(store) }
			positionStore = device.createDeformedPositionStore(vertexCount)
			positionStoreCapacity = vertexCount
			glueStoreStale = true
		}
	}

	/**
	 * Rebuilds the two-way glue partner map the composite bounds walk unions across.
	 *
	 * @param PuppetModel fromModel The model whose glues pair the partners.
	 */
	private fun rebuildGluePartners(fromModel: PuppetModel) {
		if (fromModel.glues.isEmpty()) {
			gluePartnersById = emptyMap()
			return
		}
		val partners = HashMap<DrawableId, MutableList<DrawableId>>()
		for ((glueIndex, glue) in fromModel.glues.withIndex()) {
			// Glues past MAX_GLUES render unwelded (resolvePose / planGlueLayout skip them by the same
			// index), so their partners move nothing - don't union those extents into the bounds.
			if (glueIndex >= MAX_GLUES) {
				break
			}
			partners.getOrPut(glue.meshA) { ArrayList() }.add(glue.meshB)
			partners.getOrPut(glue.meshB) { ArrayList() }.add(glue.meshA)
		}
		gluePartnersById = partners
	}
}