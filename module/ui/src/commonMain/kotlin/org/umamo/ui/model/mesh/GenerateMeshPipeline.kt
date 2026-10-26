package org.umamo.ui.model.mesh

import org.umamo.edit.atlas.withAtlasRepack
import org.umamo.edit.mesh.MeshRegeneration
import org.umamo.edit.mesh.MeshTopologyEdit
import org.umamo.edit.mesh.RegenerationInput
import org.umamo.edit.mesh.RegenerationRefusal
import org.umamo.edit.mesh.deformationGuideFor
import org.umamo.edit.mesh.meshRegeneration
import org.umamo.edit.mesh.regenerationInputFor
import org.umamo.edit.mesh.withMeshTopologyEdits
import org.umamo.format.art.AlphaChannel
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.format.atlas.AtlasPackSkipReason
import org.umamo.format.atlas.packAtlas
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.triangulation.refine.PiecewiseLinearGuide
import org.umamo.interop.art.mesh.ArtMeshNotice
import org.umamo.interop.art.mesh.MeshFit
import org.umamo.interop.art.mesh.fitArtMesh
import org.umamo.interop.art.mesh.isMeshingGuardFailure
import org.umamo.interop.art.mesh.quadMeshOver
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.SampledRegion
import org.umamo.render.TileMeshMask
import org.umamo.render.TileOpaqueMask
import org.umamo.render.drawableMeshTrianglesOf
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.generatedPuppetTextures
import org.umamo.render.meshReserveByTile
import org.umamo.render.meshReserveOf
import org.umamo.render.meshTrianglesOf
import org.umamo.render.placementFootprint
import org.umamo.render.sampledRegionHitsMask
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.repack.AtlasRepackRefusal
import org.umamo.ui.model.repack.MAX_IMPORT_PAGE_SIZE
import org.umamo.ui.model.repack.buildRepackPackInput
import org.umamo.ui.model.repack.logPack
import org.umamo.ui.model.repack.lowerPack
import org.umamo.ui.model.repack.repackPageSizeOf
import org.umamo.ui.model.repack.repackRefusals
import org.umamo.ui.progress.ProgressHandle
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.viewport.gizmo.mapInBalancedChunks

/*
 * Generate Mesh from Art and Generate Quad Mesh, the headless part: which drawables a run covers, what
 * art each one meshes, the meshing and the rig transfer, and the atlas room the new meshes need.  The two
 * differ only in what meshes the art (the run's MeshRecipe); everything around it is shared.  Pure over
 * its inputs and safe off the UI thread; GenerateMeshFlow.kt commits and registers what it produces.
 *
 * The plan a run keeps for the strip's reruns holds no pixels: each run or adjustment decodes the art
 * it needs through a [RunTileDecoder] of its own (every tile once) and lets it go when it is done, so
 * a strip that stays adjustable pins no rasters.
 *
 * A run covers the drawables asked for plus every drawable sharing a tile with one of them - twins that
 * share art to save atlas space stay alike.  A tile only one drawable samples is meshed whole; on a
 * shared tile each drawable meshes only the art its current mesh covers, so a layer split into pieces
 * keeps its pieces while twins over the same art come out identical.
 *
 * Each drawable's old mesh, with the rig built on it, guides its new one (see deformationGuideFor): the
 * old vertices where the rig bends are kept until the new mesh follows it to the deformation tolerance.
 * The rest pose is evaluated once per plan for the world field.  Twins - one tile, the same old mesh,
 * the same pins - share one guide holding both rigs, so they still come out identical.
 *
 * A generated mesh usually reaches further past its art than the old one, and on a page that could mean
 * sampling a neighbour's pixels.  A tile whose new reach stays inside the room the atlas already gave it
 * (its trim and its old reach), or that lands on no other tile's paint, stays where it is; a pinned tile
 * that needs more room keeps its old meshes; any other tile that needs more room is packed again around
 * the art that holds still, and the whole run is refused when it does not fit.
 */

/** Why a drawable a run covered was not regenerated. */
internal enum class GenerateMeshSkipReason {
	/** The drawable has no mesh. */
	NoMesh,

	/** The drawable samples no atlas tile. */
	NoTile,

	/** The tile has no usable art frame. */
	NoArtFrame,

	/** The old mesh cannot place new vertices. */
	DegenerateOldMesh,

	/** The model moved under the generated mesh. */
	PinMismatch,

	/** The tile's art is not available. */
	NoRaster,

	/** The tile's art is not the size the tile records. */
	RasterSizeMismatch,

	/** Nothing in the drawable's art reaches the alpha threshold. */
	NothingOpaque,

	/** The mesh needs more vertices than the largest budget allows, even with holes filled. */
	OverBudget,

	/** The mesher withheld a mesh that failed its own structural check. */
	Withheld,

	/** The mesher refused its input. */
	MesherRefused,

	/** A check inside the mesher or the transfer tripped on the drawable; the log has the details. */
	MesherFailed,

	/** The tile is pinned and the new mesh needs more room than it has. */
	PinnedWithoutRoom,

	/** Glue holds most of the drawable's surface, so the generated vertices between the glued ones would carry none. */
	GluedThroughout,
}

/**
 * What a run will mesh, decided once from the model it starts on and kept for the strip's reruns: the
 * drawables it covers, the inputs of those that can be regenerated, why the rest cannot, and each
 * shared drawable's own region.  No pixels - see the file comment.
 *
 * @property List<DrawableId>                              drawableIds        Every drawable the run covers, in document order.
 * @property List<RegenerationInput.Ready>                 inputs             The inputs of the drawables that can be meshed, in document order.
 * @property Map<DrawableId, GenerateMeshSkipReason>       skipsBeforeMeshing Why the others cannot.
 * @property Map<DrawableId, TileMeshMask>                 coverageByDrawable The region a drawable on a shared tile meshes.
 * @property Map<DrawableId, PiecewiseLinearGuide>         guidesByDrawable   The guide that keeps a drawable's rig, for those whose rig gives one.
 */
internal class GenerateMeshPlan(
	val drawableIds: List<DrawableId>,
	val inputs: List<RegenerationInput.Ready>,
	val skipsBeforeMeshing: Map<DrawableId, GenerateMeshSkipReason>,
	val coverageByDrawable: Map<DrawableId, TileMeshMask>,
	val guidesByDrawable: Map<DrawableId, PiecewiseLinearGuide> = emptyMap(),
) {
	/** The tiles the inputs mesh, in input order. */
	val tileIds: Set<AtlasTileId>
		get() = inputs.mapTo(LinkedHashSet()) { input -> input.tileId }
}

/**
 * Decodes each tile at most once for one run or adjustment: the plan's own tiles, the neighbours the
 * collision test reads, and everything a pack composes, each inflated a single time and held only as
 * long as the decoder is.  Used from one thread at a time - the meshing runs in parallel over art this
 * has already decoded, never through it.
 *
 * @property Function decode Decodes a tile's art; safe off the UI thread.
 */
internal class RunTileDecoder(private val decode: (AtlasTileId) -> DecodedImage?) : (AtlasTileId) -> DecodedImage? {
	private val decodedByTile = HashMap<AtlasTileId, DecodedImage?>()

	/**
	 * A tile's art, decoded on the first ask.
	 *
	 * @param AtlasTileId tileId The tile.
	 * @return DecodedImage? The art, or null when the tile has none.
	 */
	override fun invoke(tileId: AtlasTileId): DecodedImage? = decodedByTile.getOrPut(tileId) { decode(tileId) }
}

/** What one generation produced, or why it produced nothing. */
internal sealed interface GenerateMeshOutcome {
	/** Why each covered drawable was not regenerated. */
	val skips: Map<DrawableId, GenerateMeshSkipReason>

	/**
	 * Meshes were generated.
	 *
	 * @property PuppetModel                             model             The model with the new meshes and any atlas change.
	 * @property PuppetTextures?                         textures          The pages a pack composed, or null when no tile moved.
	 * @property List<DrawableId>                        generatedIds      The drawables that got a new mesh.
	 * @property Map<DrawableId, GenerateMeshSkipReason> skips             Why the others did not.
	 * @property Set<AtlasTileId>                        packedTileIds     The tiles packed to a new spot.
	 * @property Map<DrawableId, List<ArtMeshNotice>>    noticesByDrawable What the mesher adjusted for a generated drawable, for those it adjusted anything.
	 * @property Map<DrawableId, MeshFit>                 fitsByDrawable    How a generated drawable was made to fit when the rows could not fit it, for those that needed it.
	 */
	class Generated(
		val model: PuppetModel,
		val textures: PuppetTextures?,
		val generatedIds: List<DrawableId>,
		override val skips: Map<DrawableId, GenerateMeshSkipReason>,
		val packedTileIds: Set<AtlasTileId>,
		val noticesByDrawable: Map<DrawableId, List<ArtMeshNotice>>,
		val fitsByDrawable: Map<DrawableId, MeshFit> = emptyMap(),
	) : GenerateMeshOutcome {
		/** The generated drawables meshed under a raised budget or with holes filled, the rows not fitting them. */
		val refitIds: List<DrawableId>
			get() = fitsByDrawable.keys.toList()

		/** The generated drawables whose mesh is coarser than the settings asked for (a spacing or the working grid widened). */
		val coarsenedIds: List<DrawableId>
			get() = noticesByDrawable.filterValues { notices -> notices.any { notice -> notice is ArtMeshNotice.SpacingCoarsened || notice is ArtMeshNotice.WorkingGridCoarsened } }.keys.toList()
	}

	/**
	 * No drawable could be regenerated.
	 *
	 * @property Map<DrawableId, GenerateMeshSkipReason> skips Why each was not.
	 */
	class NothingGenerated(override val skips: Map<DrawableId, GenerateMeshSkipReason>) : GenerateMeshOutcome

	/**
	 * Tiles that needed more room did not fit the atlas, so nothing was applied.
	 *
	 * @property List<AtlasRepackRefusal>                refusals The tiles the pack could not carry.
	 * @property Map<DrawableId, GenerateMeshSkipReason> skips    Why the drawables already set aside were.
	 */
	class PackRefused(val refusals: List<AtlasRepackRefusal>, override val skips: Map<DrawableId, GenerateMeshSkipReason>) : GenerateMeshOutcome
}

/**
 * The drawables a run covers: the targets plus every drawable sharing a tile with one of them, in
 * document order.
 *
 * @param PuppetModel          model   The model.
 * @param Collection<DrawableId> targets The drawables asked for.
 * @return List<DrawableId> The covered drawables.
 */
internal fun expandedMeshTargets(model: PuppetModel, targets: Collection<DrawableId>): List<DrawableId> {
	val wanted = targets.toHashSet()
	val drawablesByTile = model.drawableIdsByAtlasTile()

	for (drawable in model.drawables) {
		if (drawable.id !in targets) {
			continue
		}

		val tileId = drawable.atlasTileId ?: continue
		wanted.addAll(drawablesByTile[tileId].orEmpty())
	}

	return model.drawables.map { drawable -> drawable.id }.filter { drawableId -> drawableId in wanted }
}

/**
 * Plans a run over [targets] on [base]: expands the targets to their tiles' sharers, asks each for its
 * regeneration input, reads each meshed tile's art once to check it is there and the size the tile
 * records, records the region of every drawable on a shared tile, and builds each drawable's guide.
 *
 * @param PuppetModel          base    The model the run starts on.
 * @param Collection<DrawableId> targets The drawables asked for.
 * @param Function             decode  Decodes a tile's art; safe off the UI thread.  The run's [RunTileDecoder], so the meshing does not decode again.
 * @param Boolean              guided  Whether the meshing follows each rig's guide; a quad follows none, so its plan skips the rest-pose evaluation.
 * @return GenerateMeshPlan The plan.
 */
internal fun planMeshGeneration(base: PuppetModel, targets: Collection<DrawableId>, decode: (AtlasTileId) -> DecodedImage?, guided: Boolean = true): GenerateMeshPlan {
	val drawableIds = expandedMeshTargets(base, targets)
	val meshedCountByTile = HashMap<AtlasTileId, Int>()

	for (drawable in base.drawables) {
		val tileId = drawable.atlasTileId ?: continue

		if (drawable.mesh != null) {
			meshedCountByTile[tileId] = (meshedCountByTile[tileId] ?: 0) + 1
		}
	}

	val inputs = ArrayList<RegenerationInput.Ready>()
	val skips = LinkedHashMap<DrawableId, GenerateMeshSkipReason>()
	val coverage = HashMap<DrawableId, TileMeshMask>()
	val rasterByTile = HashMap<AtlasTileId, DecodedImage?>()

	for (drawableId in drawableIds) {
		val input =
			when (val asked = base.regenerationInputFor(drawableId)) {
				is RegenerationInput.Refused -> {
					skips[drawableId] = skipReasonOf(asked.refusal)

					continue
				}

				is RegenerationInput.Ready -> {
					asked
				}
			}
		val raster = rasterByTile.getOrPut(input.tileId) { decode(input.tileId) }

		if (raster == null) {
			skips[drawableId] = GenerateMeshSkipReason.NoRaster

			continue
		}

		if (raster.width != input.tileWidth || raster.height != input.tileHeight) {
			skips[drawableId] = GenerateMeshSkipReason.RasterSizeMismatch

			continue
		}

		if ((meshedCountByTile[input.tileId] ?: 0) > 1) {
			TileMeshMask.of(drawableMeshTrianglesOf(base, drawableId))?.let { mask -> coverage[drawableId] = mask }
		}

		inputs.add(input)
	}

	return GenerateMeshPlan(drawableIds, inputs, skips, coverage, if (guided) guidesOf(base, inputs) else emptyMap())
}

/**
 * Each input's guide over the rest pose, twins sharing one merged guide.
 *
 * @param PuppetModel                   base   The model the run starts on.
 * @param List<RegenerationInput.Ready> inputs The inputs.
 * @return Map<DrawableId, PiecewiseLinearGuide> The guides, for the inputs whose rig gives one.
 */
private fun guidesOf(base: PuppetModel, inputs: List<RegenerationInput.Ready>): Map<DrawableId, PiecewiseLinearGuide> {
	if (inputs.isEmpty()) {
		return emptyMap()
	}

	val restWorld = CpuDeformationEvaluator().evaluate(base, emptyMap()).worldPositions
	val guides = LinkedHashMap<DrawableId, PiecewiseLinearGuide>()

	for (input in inputs) {
		base.deformationGuideFor(input, restWorld[input.drawableId])?.let { guide -> guides[input.drawableId] = guide }
	}

	val meshById = base.drawables.associate { drawable -> drawable.id to drawable.mesh }
	val twins = inputs.filter { input -> input.drawableId in guides }.groupBy { input -> TwinKey(input, meshById[input.drawableId]?.uvs, meshById[input.drawableId]?.indices) }

	for (group in twins.values) {
		if (group.size < 2) {
			continue
		}

		val merged = group.map { input -> guides.getValue(input.drawableId) }.reduce { combined, next -> combined.mergedWith(next) }

		for (input in group) {
			guides[input.drawableId] = merged
		}
	}

	return guides
}

/**
 * What makes two drawables twins for a guide: the same tile, the same old mesh (stored UVs and
 * triangles, so the same art-pixel points), and the same pinned old vertices - so the same pins and
 * the same guide candidates, which is what merging their guides requires.  Two meshes pinned at the
 * same point through different vertices (a duplicated seam vertex) are not twins.
 *
 * @property RegenerationInput.Ready input   The drawable's input.
 * @property FloatArray?             uvs     Its old mesh's stored UVs.
 * @property IntArray?               indices Its old mesh's triangles.
 */
private class TwinKey(private val input: RegenerationInput.Ready, private val uvs: FloatArray?, private val indices: IntArray?) {
	override fun equals(other: Any?): Boolean =
		other is TwinKey &&
			input.tileId == other.input.tileId &&
			input.pinnedOldVertices.contentEquals(other.input.pinnedOldVertices) &&
			uvs.contentEquals(other.uvs) &&
			indices.contentEquals(other.indices)

	override fun hashCode(): Int = 31 * (31 * input.tileId.hashCode() + uvs.contentHashCode()) + indices.contentHashCode()
}

/**
 * The skip a transfer refusal stands for.
 *
 * @param RegenerationRefusal refusal The refusal.
 * @return GenerateMeshSkipReason The skip reason.
 */
private fun skipReasonOf(refusal: RegenerationRefusal): GenerateMeshSkipReason =
	when (refusal) {
		RegenerationRefusal.NoMesh -> GenerateMeshSkipReason.NoMesh
		RegenerationRefusal.NoTile -> GenerateMeshSkipReason.NoTile
		RegenerationRefusal.NoArtFrame -> GenerateMeshSkipReason.NoArtFrame
		RegenerationRefusal.DegenerateOldMesh -> GenerateMeshSkipReason.DegenerateOldMesh
		RegenerationRefusal.PinMismatch -> GenerateMeshSkipReason.PinMismatch
		RegenerationRefusal.GluedThroughout -> GenerateMeshSkipReason.GluedThroughout
	}

/**
 * Generates the plan's meshes by [recipe] over [base]: decodes the plan's tiles, meshes every input
 * (tiles in parallel), carries each rig onto its new mesh, applies them, and finds the atlas room they
 * need.
 *
 * @param PuppetModel      base               The model the run starts on (the plan's).
 * @param GenerateMeshPlan plan               The plan.
 * @param MeshRecipe       recipe             What meshes the art, already clamped.
 * @param Function         decode             Decodes any tile's art - the plan's, a neighbour's for the collision test, and every tile a pack composes.  A [RunTileDecoder] decodes each once.
 * @param Boolean          premultipliedAlpha The document's texture-convention flag.
 * @param ProgressHandle?  progress           Counts each drawable meshed, then says when the atlas packs; or null.
 * @return GenerateMeshOutcome The outcome.
 */
internal suspend fun generateMeshOutcome(
	base: PuppetModel,
	plan: GenerateMeshPlan,
	recipe: MeshRecipe,
	decode: (AtlasTileId) -> DecodedImage?,
	premultipliedAlpha: Boolean,
	progress: ProgressHandle? = null,
): GenerateMeshOutcome {
	progress?.stage(ProgressLabels.MESH_GENERATING, total = plan.inputs.size)
	val skips = LinkedHashMap(plan.skipsBeforeMeshing)
	// Decoded here, before the parallel section, so the meshing reads a finished map and never the decoder.
	val rasterByTile = plan.tileIds.associateWith { tileId -> decode(tileId) }
	// One group per tile, so a tile's art is wrapped once and the work spreads by tile.
	val groups = plan.inputs.groupBy { input -> input.tileId }.values.toList()
	val results =
		mapInBalancedChunks(groups, weightOf = { group -> meshingWeightOf(group) }) { group ->
			group.map { input -> meshOne(base, plan, rasterByTile, input, recipe).also { progress?.advance() } }
		}.flatten()
	val edits = LinkedHashMap<DrawableId, MeshTopologyEdit>()
	val noticesByDrawable = LinkedHashMap<DrawableId, List<ArtMeshNotice>>()
	val fitsByDrawable = LinkedHashMap<DrawableId, MeshFit>()

	for (result in results) {
		val edit = result.edit

		if (edit != null) {
			edits[result.drawableId] = edit

			if (result.notices.isNotEmpty()) {
				noticesByDrawable[result.drawableId] = result.notices
			}

			result.fit?.let { fit -> fitsByDrawable[result.drawableId] = fit }
		} else {
			skips[result.drawableId] = result.skip ?: GenerateMeshSkipReason.MesherRefused
		}
	}

	if (edits.isEmpty()) {
		return GenerateMeshOutcome.NothingGenerated(skips)
	}

	val edited = base.withMeshTopologyEdits(edits)

	if (edited == null) {
		UmamoLog.error("generate mesh: a generated mesh did not apply to its drawable; nothing was applied")

		return GenerateMeshOutcome.NothingGenerated(skips)
	}

	progress?.stage(ProgressLabels.ATLAS_PACKING)

	return withAtlasRoom(base, edited, edits, noticesByDrawable, fitsByDrawable, skips, decode, premultipliedAlpha)
}

/**
 * The work one tile group stands for: its tile's size, once per drawable meshed over it.
 *
 * @param List<RegenerationInput.Ready> group The group, all over one tile.
 * @return Int The weight.
 */
private fun meshingWeightOf(group: List<RegenerationInput.Ready>): Int {
	val first = group.first()

	return (first.tileWidth.toLong() * first.tileHeight / 1024 + 1).coerceAtMost(Int.MAX_VALUE.toLong() / group.size).toInt() * group.size
}

/**
 * One drawable's meshing result: its edit, or why it has none.
 *
 * @property DrawableId              drawableId The drawable.
 * @property MeshTopologyEdit?       edit       The edit, or null.
 * @property GenerateMeshSkipReason? skip       Why there is no edit, or null.
 * @property List<ArtMeshNotice>     notices    What the mesher adjusted on the way to the edit; empty without one.
 */
private class MeshingResult(val drawableId: DrawableId, val edit: MeshTopologyEdit?, val skip: GenerateMeshSkipReason?, val notices: List<ArtMeshNotice>, val fit: MeshFit? = null)

/**
 * Meshes one drawable's art by the recipe and carries its rig onto the result.  A guard tripping inside
 * the mesher or the transfer skips this drawable alone; the others in the run still land.
 *
 * @param PuppetModel                       base         The model the run starts on.
 * @param GenerateMeshPlan                  plan         The plan.
 * @param Map<AtlasTileId, DecodedImage?>   rasterByTile The decoded art of the plan's tiles.
 * @param RegenerationInput.Ready           input        The drawable's input.
 * @param MeshRecipe                        recipe       What meshes the art.
 * @return MeshingResult The edit or the skip.
 */
private fun meshOne(
	base: PuppetModel,
	plan: GenerateMeshPlan,
	rasterByTile: Map<AtlasTileId, DecodedImage?>,
	input: RegenerationInput.Ready,
	recipe: MeshRecipe,
): MeshingResult {
	val decoded = rasterByTile[input.tileId] ?: return MeshingResult(input.drawableId, null, GenerateMeshSkipReason.NoRaster, emptyList())

	if (decoded.width != input.tileWidth || decoded.height != input.tileHeight) {
		return MeshingResult(input.drawableId, null, GenerateMeshSkipReason.RasterSizeMismatch, emptyList())
	}

	val alpha = tileAlphaChannel(decoded, plan.coverageByDrawable[input.drawableId])

	try {
		val mesh: PlanarTriangleMesh
		val notices: List<ArtMeshNotice>
		var fit: MeshFit? = null

		when (recipe) {
			is MeshGenerationOptions -> {
				val fitted = fitArtMesh(alpha, recipe.settings, input.pinPoints, input.pinSurroundings, guide = plan.guidesByDrawable[input.drawableId])
				mesh = fitted.result.mesh ?: return MeshingResult(input.drawableId, null, skipReasonOf(fitted.result.notices), emptyList())
				notices = fitted.result.notices
				fit = fitted.fit
			}

			is QuadMeshOptions -> {
				mesh = quadMeshOver(alpha, recipe.alphaThreshold, recipe.margin, input.pinPoints) ?: return MeshingResult(input.drawableId, null, GenerateMeshSkipReason.NothingOpaque, emptyList())
				notices = emptyList()
			}
		}

		return when (val regeneration = base.meshRegeneration(input, mesh)) {
			is MeshRegeneration.Built -> MeshingResult(input.drawableId, regeneration.edit, null, notices, fit)
			is MeshRegeneration.Refused -> MeshingResult(input.drawableId, null, skipReasonOf(regeneration.refusal), emptyList())
		}
	} catch (failure: RuntimeException) {
		if (!failure.isMeshingGuardFailure()) {
			throw failure
		}

		if (failure is IllegalArgumentException) {
			UmamoLog.warn("generate mesh: the mesher refused '${input.drawableId.raw}': ${failure.message}")

			return MeshingResult(input.drawableId, null, GenerateMeshSkipReason.MesherRefused, emptyList())
		}

		UmamoLog.error("generate mesh: meshing '${input.drawableId.raw}' failed; it was skipped", failure)

		return MeshingResult(input.drawableId, null, GenerateMeshSkipReason.MesherFailed, emptyList())
	}
}

/**
 * The skip a mesher that produced no mesh stands for, read from its notices.
 *
 * @param List<ArtMeshNotice> notices The mesher's notices.
 * @return GenerateMeshSkipReason The skip reason.
 */
private fun skipReasonOf(notices: List<ArtMeshNotice>): GenerateMeshSkipReason =
	when {
		notices.any { notice -> notice is ArtMeshNotice.NothingOpaque } -> GenerateMeshSkipReason.NothingOpaque
		notices.any { notice -> notice is ArtMeshNotice.OverBudget } -> GenerateMeshSkipReason.OverBudget
		notices.any { notice -> notice is ArtMeshNotice.StructureCheckFailed } -> GenerateMeshSkipReason.Withheld
		else -> GenerateMeshSkipReason.MesherRefused
	}

/**
 * The alpha the mesher reads for one drawable: the whole tile's, or, on a shared tile, the tile's
 * inside the drawable's region and zero outside it.  Both read the decoded image in place - it is
 * shared with the renderer and is never written or copied.
 *
 * @param DecodedImage  decoded  The tile's art.
 * @param TileMeshMask? coverage The drawable's region, or null for the whole tile.
 * @return AlphaChannel The channel.
 */
private fun tileAlphaChannel(decoded: DecodedImage, coverage: TileMeshMask?): AlphaChannel {
	val whole = LayerRaster(decoded.width, decoded.height, decoded.rgba).alphaChannel()

	return if (coverage == null) whole else CoveredAlphaChannel(whole, coverage)
}

/**
 * A tile's alpha inside a drawable's region, transparent outside it.
 *
 * @property AlphaChannel tile     The whole tile's alpha.
 * @property TileMeshMask coverage The region.
 */
private class CoveredAlphaChannel(private val tile: AlphaChannel, private val coverage: TileMeshMask) : AlphaChannel {
	override val width: Int
		get() = tile.width

	override val height: Int
		get() = tile.height

	override fun alphaAt(column: Int, row: Int): Int = if (coverage.isCovered(column, row)) tile.alphaAt(column, row) else 0
}

/**
 * Finds the atlas room the new meshes need and makes it: every tile that stays put is left alone, a
 * pinned tile that cannot keeps its old meshes, and the rest are packed again around the art that holds
 * still.
 *
 * @param PuppetModel                             base               The model the run started on.
 * @param PuppetModel                             edited             The base with every new mesh applied.
 * @param Map<DrawableId, MeshTopologyEdit>       edits              The new meshes.
 * @param Map<DrawableId, List<ArtMeshNotice>>    noticesByDrawable  What the mesher adjusted per new mesh.
 * @param Map<DrawableId, MeshFit>                 fitsByDrawable     How a new mesh was made to fit, per mesh that needed it.
 * @param MutableMap<DrawableId, GenerateMeshSkipReason> skips       The skips so far; pinned drawables are added.
 * @param Function                                decode             Decodes any tile's art, each once.
 * @param Boolean                                 premultipliedAlpha The document's texture-convention flag.
 * @return GenerateMeshOutcome The outcome.
 */
private fun withAtlasRoom(
	base: PuppetModel,
	edited: PuppetModel,
	edits: Map<DrawableId, MeshTopologyEdit>,
	noticesByDrawable: Map<DrawableId, List<ArtMeshNotice>>,
	fitsByDrawable: Map<DrawableId, MeshFit>,
	skips: MutableMap<DrawableId, GenerateMeshSkipReason>,
	decode: (AtlasTileId) -> DecodedImage?,
	premultipliedAlpha: Boolean,
): GenerateMeshOutcome {
	// Stored coordinates that address the art directly do not depend on any placement.
	if (!base.atlas.storedUvsAddressPages) {
		return GenerateMeshOutcome.Generated(edited, null, edits.keys.toList(), skips, emptySet(), noticesByDrawable, fitsByDrawable)
	}

	val tileIdByDrawable = base.drawables.associate { drawable -> drawable.id to drawable.atlasTileId }
	val affectedTileIds = edits.keys.mapNotNullTo(LinkedHashSet()) { drawableId -> tileIdByDrawable[drawableId] }
	val oldReserves = meshReserveByTile(base)
	val newReserves = meshReserveByTile(edited)
	val grownTileIds = LinkedHashSet<AtlasTileId>()
	val pinnedAllowance = HashMap<AtlasTileId, AtlasPackReserve?>()

	for (tileId in affectedTileIds) {
		val tile = base.atlas.tileById[tileId] ?: continue

		if (tile.placement == null) {
			continue
		}

		val allowed = allowedReach(trimOf(base, tile, decode), oldReserves[tileId])
		val reach = newReserves[tileId]

		if (reach == null || (allowed != null && allowed.encloses(reach)) || landsOnNoPaint(edited, tile, decode)) {
			continue
		}

		if (tile.pinned) {
			pinnedAllowance[tileId] = allowed
		} else {
			grownTileIds.add(tileId)
		}
	}

	var model = edited
	var keptEdits: Map<DrawableId, MeshTopologyEdit> = edits
	var keptNotices = noticesByDrawable
	var keptFits = fitsByDrawable

	if (pinnedAllowance.isNotEmpty()) {
		val dropped = HashSet<DrawableId>()

		for ((drawableId, _) in edits) {
			val tileId = tileIdByDrawable[drawableId] ?: continue

			if (tileId !in pinnedAllowance) {
				continue
			}

			val allowed = pinnedAllowance[tileId]
			val reach = meshReserveOf(edited, drawableId)

			if (reach != null && (allowed == null || !allowed.encloses(reach))) {
				dropped.add(drawableId)
				skips[drawableId] = GenerateMeshSkipReason.PinnedWithoutRoom
			}
		}

		keptEdits = edits.filterKeys { drawableId -> drawableId !in dropped }
		keptNotices = noticesByDrawable.filterKeys { drawableId -> drawableId !in dropped }
		keptFits = fitsByDrawable.filterKeys { drawableId -> drawableId !in dropped }

		if (keptEdits.isEmpty()) {
			return GenerateMeshOutcome.NothingGenerated(skips)
		}

		model = base.withMeshTopologyEdits(keptEdits) ?: return GenerateMeshOutcome.NothingGenerated(skips)
	}

	if (grownTileIds.isEmpty()) {
		return GenerateMeshOutcome.Generated(model, null, keptEdits.keys.toList(), skips, emptySet(), keptNotices, keptFits)
	}

	return when (val packed = packGrownTilesAround(model, grownTileIds, decode, premultipliedAlpha)) {
		is GrownPackOutcome.Refused -> GenerateMeshOutcome.PackRefused(packed.refusals, skips)
		is GrownPackOutcome.Packed -> GenerateMeshOutcome.Generated(packed.model, packed.textures, keptEdits.keys.toList(), skips, grownTileIds, keptNotices, keptFits)
	}
}

/**
 * A tile's trim: the bounds of its art at the atlas's own threshold, what the pack reserves around.
 *
 * @param PuppetModel model  The model.
 * @param AtlasTile   tile   The tile.
 * @param Function    decode Decodes a tile's art.
 * @return LayerBounds? The trim, or null when the art is unavailable or empty.
 */
private fun trimOf(model: PuppetModel, tile: AtlasTile, decode: (AtlasTileId) -> DecodedImage?): LayerBounds? {
	val raster = decode(tile.id) ?: return null

	return analyzeAlpha(raster.width, raster.height, raster.rgba, model.atlas.composition.alphaThreshold)?.opaqueBounds
}

/**
 * The room a placed tile already has: the box around its trim and its old mesh reach - what the pack
 * reserved for it.
 *
 * @param LayerBounds?      trim       The tile's trim.
 * @param AtlasPackReserve? oldReserve The tile's old mesh reach.
 * @return AtlasPackReserve? The room, or null when the tile had neither.
 */
private fun allowedReach(trim: LayerBounds?, oldReserve: AtlasPackReserve?): AtlasPackReserve? {
	val trimReserve = trim?.let { bounds -> AtlasPackReserve(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height) }

	return when {
		trimReserve == null -> {
			oldReserve
		}

		oldReserve == null -> {
			trimReserve
		}

		else -> {
			AtlasPackReserve(
				minOf(trimReserve.left, oldReserve.left),
				minOf(trimReserve.top, oldReserve.top),
				maxOf(trimReserve.right, oldReserve.right),
				maxOf(trimReserve.bottom, oldReserve.bottom),
			)
		}
	}
}

/**
 * Whether one reserve lies wholly inside another.
 *
 * @param AtlasPackReserve inner The reserve to test.
 * @return Boolean True when [inner] fits.
 */
private fun AtlasPackReserve.encloses(inner: AtlasPackReserve): Boolean = inner.left >= left && inner.top >= top && inner.right <= right && inner.bottom <= bottom

/**
 * Whether a placed tile's new meshes stay on its page and sample no other tile's paint - the exact test
 * the placement gizmo uses, so a tile that needs more room than the pack gave it may still stay put
 * when the room around it is empty.
 *
 * @param PuppetModel model  The model with the new meshes.
 * @param AtlasTile   tile   The tile, placed.
 * @param Function    decode Decodes any tile's art.
 * @return Boolean True when the tile can stay where it is.
 */
private fun landsOnNoPaint(model: PuppetModel, tile: AtlasTile, decode: (AtlasTileId) -> DecodedImage?): Boolean {
	val placement = tile.placement ?: return true
	val page = model.atlas.pages.getOrNull(placement.pageIndex) ?: return false
	val mask = TileMeshMask.of(meshTrianglesOf(model, tile.id)) ?: return true
	val region = SampledRegion(placement, mask)

	if (region.bounds.exceeds(page.width, page.height)) {
		return false
	}

	val composition = model.atlas.composition

	for (other in model.atlas.tiles) {
		val otherPlacement = other.placement ?: continue

		if (other.id == tile.id || otherPlacement.pageIndex != placement.pageIndex) {
			continue
		}

		// Only a tile whose art (and the composer's band around it) reaches the region can be hit.
		val reach = placementFootprint(otherPlacement, LayerBounds(0, 0, other.width, other.height), reserve = null).expanded(composition.extrude.toFloat())

		if (!region.bounds.overlaps(reach)) {
			continue
		}

		val raster = decode(other.id) ?: return false

		if (raster.width != other.width || raster.height != other.height) {
			return false
		}

		val trim = analyzeAlpha(raster.width, raster.height, raster.rgba, composition.alphaThreshold)?.opaqueBounds ?: continue

		if (sampledRegionHitsMask(region, otherPlacement, TileOpaqueMask.of(raster, trim, composition.alphaThreshold), composition.extrude)) {
			return false
		}
	}

	return true
}

/** What packing the grown tiles produced, or why nothing was applied. */
internal sealed interface GrownPackOutcome {
	/**
	 * A tile the pack could not carry; nothing was applied.
	 *
	 * @property List<AtlasRepackRefusal> refusals The tiles.
	 */
	class Refused(val refusals: List<AtlasRepackRefusal>) : GrownPackOutcome

	/**
	 * The grown tiles packed and the model re-derived over the pages.
	 *
	 * @property PuppetModel    model    The model with the pack applied.
	 * @property PuppetTextures textures The pages the pack composed.
	 */
	class Packed(val model: PuppetModel, val textures: PuppetTextures) : GrownPackOutcome
}

/**
 * Packs the tiles whose meshes grew past their room into new spots around every other placed tile, which
 * holds still (pinned or not, painted or fully transparent), at the document's page size and once more
 * at [MAX_IMPORT_PAGE_SIZE] when one does not fit; the result re-derives every coordinate through
 * [withAtlasRepack] under the document's composition.  Only the grown tiles move: a tile with no
 * placement that is not growing (art the import left unpacked, a canvas-sized background larger than any
 * page) stays out of the pack as it is.  A grown tile the pack cannot carry refuses the whole pack, and so
 * does a held tile the new pages would lose - a used tile left without a placement would leave its
 * drawables without a page.
 *
 * @param PuppetModel      model              The model with the new meshes.
 * @param Set<AtlasTileId> grownTileIds       The tiles that need a new spot.
 * @param Function         decode             Decodes any tile's art.
 * @param Boolean          premultipliedAlpha The document's texture-convention flag.
 * @return GrownPackOutcome The packed model and pages, or the refusal.
 */
internal fun packGrownTilesAround(
	model: PuppetModel,
	grownTileIds: Set<AtlasTileId>,
	decode: (AtlasTileId) -> DecodedImage?,
	premultipliedAlpha: Boolean,
): GrownPackOutcome {
	val composition = model.atlas.composition
	val defaults = AtlasPackOptions()
	val freeKeys = grownTileIds.mapTo(HashSet()) { tileId -> tileId.raw }
	var packOptions =
		AtlasPackOptions(
			maxPageSize = repackPageSizeOf(model),
			gutter = maxOf(defaults.gutter, composition.extrude),
			extrude = composition.extrude,
			alphaThreshold = composition.alphaThreshold,
		)
	val input = buildRepackPackInput(model, decode)
	val items = input.itemsFor(keepPinned = true, fixPlaced = true, freeKeys = freeKeys).filter { item -> item.key in freeKeys || item.fixed != null }
	var result = packAtlas(items, packOptions)

	if (packOptions.maxPageSize < MAX_IMPORT_PAGE_SIZE && result.skipped.any { skip -> skip.reason == AtlasPackSkipReason.LargerThanPage }) {
		packOptions = packOptions.copy(maxPageSize = MAX_IMPORT_PAGE_SIZE)
		result = packAtlas(items, packOptions)
	}

	// Only what went into the pack can refuse it: a tile left out keeps what it had.
	val packedKeys = items.mapTo(HashSet()) { item -> item.key }
	val undecodableInPack = input.undecodableTileIds.filterTo(HashSet()) { tileId -> tileId.raw in freeKeys || model.atlas.tileById[tileId]?.placement != null }
	val refusals = repackRefusals(model, result.skipped.filter { skip -> skip.key in packedKeys }, undecodableInPack)

	if (refusals.isNotEmpty()) {
		return GrownPackOutcome.Refused(refusals)
	}

	val lowered = lowerPack(model.atlas, result)
	val repacked = model.withAtlasRepack(lowered.pages, lowered.placementsByTile, composition)
	logPack(result, lowered, packOptions)

	return GrownPackOutcome.Packed(repacked, generatedPuppetTextures(result.pages, repacked, premultipliedAlpha))
}