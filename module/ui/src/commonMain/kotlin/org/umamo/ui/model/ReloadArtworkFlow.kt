package org.umamo.ui.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.DocumentChange
import org.umamo.edit.NoticePlacement
import org.umamo.edit.commitArtworkRelinked
import org.umamo.edit.commitArtworkReloaded
import org.umamo.edit.setTileSource
import org.umamo.edit.withArtworkReloaded
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.reimport.ArtworkReloadPlanner
import org.umamo.reimport.ReloadPlan
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.storage.UmamoLog

/*
 * Reloading the document's artwork files, and relinking a tile with the layer's art pulled in: the
 * planner's delta applied to the live model, the changed tiles packed into the gaps around the art
 * that did not change, committed as one undo step and registered on the operation settings strip.
 * Add Artwork's shape throughout - the same pack-around primitive, the same supersede check, the
 * same amend-in-place adjustment - because a reload is an import of the layers that changed.
 *
 * A reloaded tile is a NEW tile (see AtlasTile.replaces): its pixels join the raster store beside the
 * old tile's, so an undo shows the old art again by pure snapshot, and nothing here ever overwrites
 * a raster the document already holds.
 */

/**
 * One listed file to re-read.
 *
 * @property ArtSourceId sourceId    The file's record in the model.
 * @property SourceArt   art         The file as just read.
 * @property String?     contentHash The whole-file content hash of the bytes it was read from, recorded
 *   on the refreshed source so the watcher knows this save was taken; null keeps the record's.
 */
class ReloadEntry(
	val sourceId: ArtSourceId,
	val art: SourceArt,
	val contentHash: String? = null,
)

/** How a reload ended - what the watcher needs to know to wait, retry, or let go. */
enum class ReloadArtworkResult {
	/** The reload landed as one undo step. */
	Applied,

	/** The files were read and nothing in them changed the document. */
	NothingChanged,

	/** The pack could not keep the document's own art in place; the person was shown the report. */
	Refused,

	/** An edit landed while the reload was being planned; nothing was applied. */
	Superseded,
}

/**
 * The files a reload re-reads, as read from disk, with the decoded wrapper of every layer raster
 * minted once so a re-run hands the raster store the same instances (the renderer's texture cache
 * keys on that identity).
 *
 * @property List                   entries The files, each with its art.
 * @property SourceArtImportOptions options The threshold and margin the first run uses.
 */
class ReloadArtworkRequest(
	val entries: List<ReloadEntry>,
	val options: SourceArtImportOptions,
) {
	private val decodedByRaster: Map<LayerRaster, DecodedImage> =
		entries.flatMap { entry -> entry.art.layers }.associate { layer -> layer.raster to DecodedImage(layer.raster.rgba, layer.raster.width, layer.raster.height) }

	/**
	 * The decoded wrapper of one of the files' layer rasters.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call.
	 */
	internal fun decodedFor(raster: LayerRaster): DecodedImage =
		decodedByRaster[raster] ?: DecodedImage(raster.rgba, raster.width, raster.height)
}

/**
 * One tile to rebind, with the layer's file as read when it could be.
 *
 * @property AtlasTileId            tileId  The tile.
 * @property SourceLayerRef         ref     The binding it takes.
 * @property SourceArt?             art     The file [ref] names, or null when it could not be read
 *   (missing, unreadable, or a platform uri) - the binding then changes alone.
 * @property SourceArtImportOptions options The threshold and margin a re-born quad uses.
 */
class RelinkArtworkRequest(
	val tileId: AtlasTileId,
	val ref: SourceLayerRef,
	val art: SourceArt?,
	val options: SourceArtImportOptions,
) {
	private val decodedByRaster: Map<LayerRaster, DecodedImage> =
		art?.layers.orEmpty().associate { layer -> layer.raster to DecodedImage(layer.raster.rgba, layer.raster.width, layer.raster.height) }

	/**
	 * The decoded wrapper of one of the file's layer rasters.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call.
	 */
	internal fun decodedFor(raster: LayerRaster): DecodedImage =
		decodedByRaster[raster] ?: DecodedImage(raster.rgba, raster.width, raster.height)
}

/** What one reload pass produced, or why it produced nothing. */
private sealed interface ReloadOutcome {
	/** No file changed in any way the document records. */
	data object NothingChanged : ReloadOutcome

	/**
	 * The pack could not keep some of the document's own art where it is; nothing was applied.
	 *
	 * @property List refusals The document's tiles the pack could not keep.
	 */
	class Refused(val refusals: List<AtlasRepackRefusal>) : ReloadOutcome

	/**
	 * The delta applied and packed.
	 *
	 * @property PuppetModel    model         The base with the reload applied and packed.
	 * @property PuppetTextures textures      The pages the pack composed, index-parallel to the model's.
	 * @property Map            decodedByTile The new tiles' pixels, for the raster store.
	 * @property List           notices       The planner's notes plus every new tile the pack left unplaced.
	 * @property List           outgrown      Drawables whose kept mesh no longer covers the new art.
	 * @property DocumentChange.ReloadArtwork change The step's counts.
	 */
	class Reloaded(
		val model: PuppetModel,
		val textures: PuppetTextures,
		val decodedByTile: Map<AtlasTileId, DecodedImage>,
		val notices: List<SourceArtImportNotice>,
		val outgrown: List<DrawableId>,
		val change: DocumentChange.ReloadArtwork,
	) : ReloadOutcome
}

/**
 * The document's pixels for a tile as the planner reads them: the raster store's decoded image,
 * rewrapped.
 *
 * @param SourceArtRasters artRasters The document's raster store.
 * @return Function The lookup, null for a tile the store cannot decode.
 */
private fun oldRasterLookup(artRasters: SourceArtRasters): (AtlasTileId) -> LayerRaster? =
	{ tileId -> artRasters.decodeRaster(tileId)?.let { decoded -> LayerRaster(decoded.width, decoded.height, decoded.rgba) } }

/**
 * Applies [request]'s files to [base] one after another and packs the tiles they replaced or added
 * into the gaps around the rest.
 *
 * @param PuppetModel            base               The model the reload applies to.
 * @param ReloadArtworkRequest   request            The files as read.
 * @param SourceArtImportOptions options            The threshold and margin to plan with.
 * @param SourceArtRasters       artRasters         The document's raster store (read, never written here).
 * @param Boolean                premultipliedAlpha The document's texture-convention flag.
 * @return ReloadOutcome The outcome.
 */
private fun reloadOutcome(
	base: PuppetModel,
	request: ReloadArtworkRequest,
	options: SourceArtImportOptions,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
): ReloadOutcome {
	var model = base
	val rasters = LinkedHashMap<AtlasTileId, LayerRaster>()
	val notices = ArrayList<SourceArtImportNotice>()
	val outgrown = ArrayList<DrawableId>()
	var replaced = 0
	var added = 0
	var missing = 0
	val oldRasterOf = oldRasterLookup(artRasters)
	for (entry in request.entries) {
		val plan = ArtworkReloadPlanner.plan(model, entry.sourceId, entry.art, options, oldRasterOf, entry.contentHash) ?: continue
		val next = model.withArtworkReloaded(plan.reload)
		if (next === model) {
			UmamoLog.error("reload artwork: the plan for '${plan.reload.source.name}' collides with the document's ids; that file was skipped")
			continue
		}
		model = next
		rasters.putAll(plan.rasterByTile)
		notices.addAll(plan.notices)
		outgrown.addAll(plan.reload.outgrown)
		replaced += plan.reload.replacedTiles.size
		added += plan.reload.additions?.drawables?.size ?: 0
		missing += plan.report.needsReview.size
	}
	if (model === base) {
		return ReloadOutcome.NothingChanged
	}
	val change = DocumentChange.ReloadArtwork(request.entries.size, replaced, added, missing)
	return packReloaded(model, rasters.mapValues { (_, raster) -> request.decodedFor(raster) }, notices, artRasters, premultipliedAlpha) { packedModel, textures, packedNotices, decodedByTile ->
		ReloadOutcome.Reloaded(packedModel, textures, decodedByTile, packedNotices, outgrown, change)
	}
}

/**
 * Packs the tiles a reload or relink minted (all of them unplaced) around the document's art and
 * builds the outcome from the result.
 *
 * @param PuppetModel      model              The model with the delta applied.
 * @param Map              decodedByTile      The new tiles' pixels.
 * @param List             notices            The notes so far.
 * @param SourceArtRasters artRasters         The document's raster store, for every other tile.
 * @param Boolean          premultipliedAlpha The document's texture-convention flag.
 * @param Function         reloaded           Builds the success outcome from the packed model.
 * @return ReloadOutcome The outcome, refused when the document's own art could not be kept.
 */
private inline fun packReloaded(
	model: PuppetModel,
	decodedByTile: Map<AtlasTileId, DecodedImage>,
	notices: List<SourceArtImportNotice>,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
	reloaded: (PuppetModel, PuppetTextures, List<SourceArtImportNotice>, Map<AtlasTileId, DecodedImage>) -> ReloadOutcome,
): ReloadOutcome {
	val decode: (AtlasTileId) -> DecodedImage? = { tileId -> decodedByTile[tileId] ?: artRasters.decodeRaster(tileId) }
	return when (val packed = packNewTilesAround(model, decodedByTile.keys, decode, premultipliedAlpha, notices)) {
		is PackAroundOutcome.Refused -> ReloadOutcome.Refused(packed.refusals)
		is PackAroundOutcome.Packed -> reloaded(packed.model, packed.textures, packed.notices, decodedByTile)
	}
}

/**
 * Reloads the document's artwork files as ONE undo step and registers it on the operation settings
 * strip (Alpha Threshold, Birth Mesh Margin - the rows every re-born quad and added layer take).
 *
 * Runs on the UI thread; the planning and the pack hop to the default dispatcher.  The delta is
 * planned against the model current at the start, so any edit landing meanwhile supersedes it.  The
 * new rasters join the store only once the commit is certain.
 *
 * @param AtlasRepackHost      host    The session, art, resolver, scope, and shell callbacks.
 * @param ReloadArtworkRequest request The files as read.
 * @param String?              areaId  The area the strip shows in, or null.
 * @return ReloadArtworkResult How the reload ended.
 */
suspend fun runReloadArtwork(host: AtlasRepackHost, request: ReloadArtworkRequest, areaId: String?): ReloadArtworkResult {
	val session = host.session
	val modelAtStart = session.model.value
	val outcome =
		withContext(Dispatchers.Default) {
			reloadOutcome(modelAtStart, request, request.options, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		ReloadOutcome.NothingChanged -> {
			UmamoLog.info("reload artwork: nothing changed in ${request.entries.size} file(s)")
			session.emitNotice("notice.reload.noChanges", NoticePlacement.StatusBar)
			return ReloadArtworkResult.NothingChanged
		}
		is ReloadOutcome.Refused -> {
			for (refusal in outcome.refusals) {
				UmamoLog.warn("reload artwork: the document's tile '${refusal.tileName}' could not be kept in place (${refusal.reason}); nothing was applied")
			}
			host.report(AtlasRepackReport(outcome.refusals))
			return ReloadArtworkResult.Refused
		}
		is ReloadOutcome.Reloaded -> Unit
	}
	if (session.model.value !== modelAtStart) {
		UmamoLog.warn("reload artwork: the document changed while its files were being reloaded; nothing was applied")
		session.emitNotice("notice.import.artworkSuperseded", NoticePlacement.StatusBar)
		return ReloadArtworkResult.Superseded
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	val committed = session.commitArtworkReloaded(outcome.change, outcome.model)
	host.sessionAtlasPages?.prewarm(committed.atlas, outcome.textures)
	reportReload(outcome, committed)
	val change = outcome.change
	session.emitNotice(
		when {
			outcome.outgrown.isNotEmpty() -> "notice.reload.outgrown"
			outcome.notices.isNotEmpty() || change.missingCount > 0 -> "notice.reload.notes"
			else -> "notice.reload.done"
		},
		NoticePlacement.StatusBar,
		listOf(change.replacedCount.toString(), change.addedCount.toString(), change.missingCount.toString()),
	)
	session.registerAdjustableOperation(committed, areaId, addArtworkParameters(request.options)) { record ->
		host.scope.launch { adjustReloadArtwork(host, record, request) }
	}
	return ReloadArtworkResult.Applied
}

/**
 * Re-plans the reload for an adjustment of the strip: the record's rows become the options, the SAME
 * read files are planned again over the record's base model, packed, and landed over the operation's
 * own step.  A record cleared while the pass ran makes [org.umamo.edit.EditorSession.amendLastCommit]
 * drop the result.
 *
 * @param AtlasRepackHost      host    The session, resolver, and shell callbacks the first run had.
 * @param AdjustableOperation  record  The record with the adjusted parameters.
 * @param ReloadArtworkRequest request The first run's files.
 */
internal suspend fun adjustReloadArtwork(host: AtlasRepackHost, record: AdjustableOperation, request: ReloadArtworkRequest) {
	val base = record.baseSnapshot.model
	val options = addArtworkOptionsOf(record.parameters, request.options)
	val outcome =
		withContext(Dispatchers.Default) {
			reloadOutcome(base, request, options, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		ReloadOutcome.NothingChanged -> {
			UmamoLog.warn("reload artwork: under the adjusted options nothing changes; the previous result stands")
			return
		}
		is ReloadOutcome.Refused -> {
			host.report(AtlasRepackReport(outcome.refusals))
			return
		}
		is ReloadOutcome.Reloaded -> Unit
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	host.sessionAtlasPages?.prewarm(outcome.model.atlas, outcome.textures)
	if (!host.session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("reload artwork: the adjustment was superseded before it landed; nothing was applied")
		return
	}
	reportReload(outcome, outcome.model)
}

/**
 * Logs one reload pass the way the first run and an adjustment both report it.
 *
 * @param ReloadOutcome.Reloaded outcome   What the pass produced.
 * @param PuppetModel            committed The model that landed.
 */
private fun reportReload(outcome: ReloadOutcome.Reloaded, committed: PuppetModel) {
	for (notice in outcome.notices) {
		UmamoLog.warn("reload artwork: ${describeImportNotice(notice)}")
	}
	for (drawableId in outcome.outgrown) {
		val name = committed.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.name ?: drawableId.raw
		UmamoLog.warn("reload artwork: the new art of '$name' reaches past its edited mesh; re-mesh it or extend the mesh")
	}
	val change = outcome.change
	UmamoLog.info(
		"reload artwork: ${change.fileCount} file(s) -> ${change.replacedCount} tile(s) updated, ${change.addedCount} drawable(s) added," +
			" ${change.missingCount} layer(s) missing, ${outcome.outgrown.size} outgrown; now ${committed.atlas.pages.size} page(s); ${outcome.notices.size} note(s)",
	)
}

/**
 * Rebinds a tile to another source layer, pulling the layer's art in when the file could be read:
 * the tile is replaced with the layer's art and its drawables carried over it as ONE undo step,
 * packed beside the rest and registered on the strip like a reload; without the file, or when the
 * layer has no art to give, only the binding changes and the notice says so.
 *
 * @param AtlasRepackHost      host    The session, art, resolver, scope, and shell callbacks.
 * @param RelinkArtworkRequest request The tile, its new binding, and the file when read.
 * @param String?              areaId  The area the strip shows in, or null.
 * @return Boolean Whether the art was pulled (a binding-only change returns false).
 */
suspend fun runRelinkArtwork(host: AtlasRepackHost, request: RelinkArtworkRequest, areaId: String?): Boolean {
	val session = host.session
	val modelAtStart = session.model.value
	val art = request.art
	val plan =
		if (art == null) {
			null
		} else {
			withContext(Dispatchers.Default) {
				ArtworkReloadPlanner.planRelink(modelAtStart, request.tileId, request.ref, art, request.options, oldRasterLookup(host.artRasters))
			}
		}
	if (plan == null) {
		val current = modelAtStart.atlas.tileById[request.tileId]?.source
		if (current == request.ref) {
			UmamoLog.info("relink artwork: '${request.tileId.raw}' already holds this binding and this art; nothing to pull")
			return false
		}
		session.setTileSource(request.tileId, request.ref)
		UmamoLog.warn("relink artwork: '${request.tileId.raw}' rebound to '${request.ref.layerKey}' without its art (the file could not be read, or the layer has none)")
		session.emitNotice("notice.relink.bindingOnly", NoticePlacement.StatusBar)
		return false
	}
	val outcome =
		withContext(Dispatchers.Default) {
			relinkOutcome(modelAtStart, plan, request, host.artRasters, host.premultipliedAlpha)
		}
	if (!landRelink(host, request, modelAtStart, outcome) { model -> session.commitArtworkRelinked(request.tileId, model) }) {
		return false
	}
	session.registerAdjustableOperation(session.model.value, areaId, addArtworkParameters(request.options)) { record ->
		host.scope.launch { adjustRelinkArtwork(host, record, request) }
	}
	return true
}

/**
 * Applies a relink plan to [base] and packs its one new tile.
 *
 * @param PuppetModel          base               The model the relink applies to.
 * @param ReloadPlan           plan               The planner's delta for the tile.
 * @param RelinkArtworkRequest request            The request, for the decoded wrappers.
 * @param SourceArtRasters     artRasters         The document's raster store.
 * @param Boolean              premultipliedAlpha The document's texture-convention flag.
 * @return ReloadOutcome The outcome.
 */
private fun relinkOutcome(
	base: PuppetModel,
	plan: ReloadPlan,
	request: RelinkArtworkRequest,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
): ReloadOutcome {
	val model = base.withArtworkReloaded(plan.reload)
	if (model === base) {
		UmamoLog.error("relink artwork: the plan for '${request.tileId.raw}' collides with the document's ids; nothing was applied")
		return ReloadOutcome.NothingChanged
	}
	val change = DocumentChange.ReloadArtwork(1, plan.reload.replacedTiles.size, 0, 0)
	return packReloaded(model, plan.rasterByTile.mapValues { (_, raster) -> request.decodedFor(raster) }, plan.notices, artRasters, premultipliedAlpha) { packedModel, textures, notices, decodedByTile ->
		ReloadOutcome.Reloaded(packedModel, textures, decodedByTile, notices, plan.reload.outgrown, change)
	}
}

/**
 * Lands a relink outcome: the supersede check, the rasters into the store, the commit, the pre-warm,
 * the log, and the notice.
 *
 * @param AtlasRepackHost      host         The session, art, resolver, and shell callbacks.
 * @param RelinkArtworkRequest request      The request, for the log.
 * @param PuppetModel          modelAtStart The model the outcome was planned against.
 * @param ReloadOutcome        outcome      What the pass produced.
 * @param Function             commit       Commits the packed model and returns the committed one.
 * @return Boolean Whether the outcome landed.
 */
private inline fun landRelink(
	host: AtlasRepackHost,
	request: RelinkArtworkRequest,
	modelAtStart: PuppetModel,
	outcome: ReloadOutcome,
	commit: (PuppetModel) -> PuppetModel,
): Boolean {
	val session = host.session
	when (outcome) {
		ReloadOutcome.NothingChanged -> return false
		is ReloadOutcome.Refused -> {
			host.report(AtlasRepackReport(outcome.refusals))
			return false
		}
		is ReloadOutcome.Reloaded -> Unit
	}
	if (session.model.value !== modelAtStart) {
		UmamoLog.warn("relink artwork: the document changed while '${request.tileId.raw}' was being relinked; nothing was applied")
		session.emitNotice("notice.import.artworkSuperseded", NoticePlacement.StatusBar)
		return false
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	val committed = commit(outcome.model)
	host.sessionAtlasPages?.prewarm(committed.atlas, outcome.textures)
	reportReload(outcome, committed)
	session.emitNotice(if (outcome.outgrown.isEmpty()) "notice.relink.pulled" else "notice.reload.outgrown", NoticePlacement.StatusBar)
	return true
}

/**
 * Re-plans a relink for an adjustment of the strip over the record's base, landing through
 * [org.umamo.edit.EditorSession.amendLastCommit].
 *
 * @param AtlasRepackHost      host    The session, resolver, and shell callbacks the first run had.
 * @param AdjustableOperation  record  The record with the adjusted parameters.
 * @param RelinkArtworkRequest request The first run's request.
 */
internal suspend fun adjustRelinkArtwork(host: AtlasRepackHost, record: AdjustableOperation, request: RelinkArtworkRequest) {
	val art = request.art ?: return
	val base = record.baseSnapshot.model
	val options = addArtworkOptionsOf(record.parameters, request.options)
	val outcome =
		withContext(Dispatchers.Default) {
			val plan = ArtworkReloadPlanner.planRelink(base, request.tileId, request.ref, art, options, oldRasterLookup(host.artRasters))
			if (plan == null) ReloadOutcome.NothingChanged else relinkOutcome(base, plan, request, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		ReloadOutcome.NothingChanged -> {
			UmamoLog.warn("relink artwork: under the adjusted options nothing changes; the previous result stands")
			return
		}
		is ReloadOutcome.Refused -> {
			host.report(AtlasRepackReport(outcome.refusals))
			return
		}
		is ReloadOutcome.Reloaded -> Unit
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	host.sessionAtlasPages?.prewarm(outcome.model.atlas, outcome.textures)
	if (!host.session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("relink artwork: the adjustment was superseded before it landed; nothing was applied")
		return
	}
	reportReload(outcome, outcome.model)
}