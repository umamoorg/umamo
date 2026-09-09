package org.umamo.ui.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.DocumentChange
import org.umamo.edit.NoticePlacement
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterUnit
import org.umamo.edit.commitArtworkMatched
import org.umamo.edit.commitArtworkReplaced
import org.umamo.edit.floatValue
import org.umamo.edit.withArtworkReloaded
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayerKind
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.reimport.ArtworkReloadPlanner
import org.umamo.reimport.InventoryLayerMatcher
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.ReconcileResult
import org.umamo.reimport.inventoryWithMissing
import org.umamo.reimport.suggestionsFor
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog

/*
 * Match Automatically and Replace Artwork: the two operations that resolve a binding the file no
 * longer carries a key for.  Match scores every such binding against the file's unbound layers and
 * rebinds the confident ones, with the threshold on the operation strip so the same read files
 * re-score at another bar; Replace repoints one record at another file, reloading what the new file
 * resolves by key and leaving the rest for the matcher.  Both pack, commit, and register exactly as a
 * reload does, and both publish the suggestions they could not apply, scored with the pixels they
 * read, for the Sources space's review chips.
 */

/** The strip row keys the match operation reads its values back by. */
internal object MatchParameterKeys {
	const val THRESHOLD = "match.threshold"
}

/**
 * Every unresolved binding's best candidate as the flows publish it, keyed by file and lost key.
 */
typealias SourceSuggestions = Map<Pair<ArtSourceId, String>, LayerMatch>

/**
 * The files Match Automatically scores, as read, with the decoded wrapper of every layer raster
 * minted once so a re-score hands the raster store the same instances.
 *
 * @property List                   entries   The present files, each with its art.
 * @property Float                  threshold The confidence at or above which a match is applied, 0..1.
 * @property SourceArtImportOptions options   The threshold and margin every re-born quad uses.
 */
class MatchArtworkRequest(
	val entries: List<ReloadEntry>,
	val threshold: Float,
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
 * One record to repoint, with the new file as read.
 *
 * @property ArtSourceId            sourceId    The record being repointed.
 * @property SourceArt              art         The new file as read.
 * @property ArtSourceDescriptor    descriptor  The new file's name, path, and format.
 * @property String?                contentHash The whole-file content hash of the bytes [art] came from.
 * @property SourceArtImportOptions options     The threshold and margin a re-born quad uses.
 */
class ReplaceArtworkRequest(
	val sourceId: ArtSourceId,
	val art: SourceArt,
	val descriptor: ArtSourceDescriptor,
	val contentHash: String?,
	val options: SourceArtImportOptions,
) {
	private val decodedByRaster: Map<LayerRaster, DecodedImage> =
		art.layers.associate { layer -> layer.raster to DecodedImage(layer.raster.rgba, layer.raster.width, layer.raster.height) }

	/**
	 * The decoded wrapper of one of the file's layer rasters.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call.
	 */
	internal fun decodedFor(raster: LayerRaster): DecodedImage =
		decodedByRaster[raster] ?: DecodedImage(raster.rgba, raster.width, raster.height)
}

/** What one match or replace pass produced, or why it produced nothing. */
private sealed interface MatchOutcome {
	/**
	 * Nothing was rebound; the suggestions still stand for the review chips.
	 *
	 * @property SourceSuggestions suggestions The best candidate per unresolved binding.
	 */
	class NothingApplied(val suggestions: SourceSuggestions) : MatchOutcome

	/**
	 * The pack could not keep some of the document's own art where it is; nothing was applied.
	 *
	 * @property List refusals The document's tiles the pack could not keep.
	 */
	class Refused(val refusals: List<AtlasRepackRefusal>) : MatchOutcome

	/**
	 * The delta applied and packed.
	 *
	 * @property PuppetModel       model         The base with the delta applied and packed.
	 * @property PuppetTextures?   textures      The pages the pack composed, index-parallel to the model's, or
	 *   null when nothing was packed and the pages stand as they are.
	 * @property Map               decodedByTile The new tiles' pixels, for the raster store.
	 * @property List              notices       The planner's notes plus every new tile the pack left unplaced.
	 * @property List              outgrown      Drawables whose kept mesh no longer covers the new art.
	 * @property DocumentChange    change        The step's counts.
	 * @property SourceSuggestions suggestions   The best candidate per binding still unresolved.
	 */
	class Applied(
		val model: PuppetModel,
		val textures: PuppetTextures?,
		val decodedByTile: Map<AtlasTileId, DecodedImage>,
		val notices: List<SourceArtImportNotice>,
		val outgrown: List<DrawableId>,
		val change: DocumentChange,
		val suggestions: SourceSuggestions,
	) : MatchOutcome
}

/**
 * The strip rows a match registers: the threshold as a percentage beside the import rows.
 *
 * @param Float                  threshold The confidence bar, 0..1.
 * @param SourceArtImportOptions options   The import options the rows carry.
 * @return List<OperatorParameter> The rows.
 */
internal fun matchArtworkParameters(threshold: Float, options: SourceArtImportOptions): List<OperatorParameter> =
	listOf(OperatorParameter.FloatParameter(MatchParameterKeys.THRESHOLD, MatchParameterKeys.THRESHOLD, threshold * 100f, 0f, 100f, step = 5f, unit = ParameterUnit.Percent)) +
		addArtworkParameters(options)

/**
 * The threshold [parameters] describe, as a fraction.
 *
 * @param List  parameters The strip's rows.
 * @param Float fallback   The threshold the first run used.
 * @return Float The adjusted threshold, 0..1.
 */
internal fun matchThresholdOf(parameters: List<OperatorParameter>, fallback: Float): Float =
	(parameters.floatValue(MatchParameterKeys.THRESHOLD, fallback * 100f) / 100f).coerceIn(0f, 1f)

/**
 * The document's pixels for a tile as the matcher and planner read them.
 *
 * @param SourceArtRasters artRasters The document's raster store.
 * @return Function The lookup, null for a tile the store cannot decode.
 */
private fun tileRasterLookup(artRasters: SourceArtRasters): (AtlasTileId) -> LayerRaster? =
	{ tileId -> artRasters.decodeRaster(tileId)?.let { decoded -> LayerRaster(decoded.width, decoded.height, decoded.rgba) } }

/**
 * Scores [sourceId]'s unresolved bindings against [art]: the model's inventory refreshed against the
 * read art (so a file never reloaded still scores), the tiles' pixels and the art's layers handed
 * to the matcher.
 *
 * @param PuppetModel model      The model as it stands.
 * @param ArtSourceId sourceId   The file.
 * @param SourceArt   art        The file as read.
 * @param Function    tileRaster The document's pixels for a tile.
 * @return Map<String, LayerMatch> Each unresolved binding's best candidate, by its lost key.
 */
private fun scoreSource(model: PuppetModel, sourceId: ArtSourceId, art: SourceArt, tileRaster: (AtlasTileId) -> LayerRaster?): Map<String, LayerMatch> {
	val source = model.sources.firstOrNull { candidate -> candidate.id == sourceId } ?: return emptyMap()
	val boundKeys = model.atlas.tiles.filter { tile -> tile.source?.sourceId == sourceId }.mapNotNullTo(HashSet()) { tile -> tile.source?.layerKey }
	val refreshed = source.copy(layers = inventoryWithMissing(source.layers, SourceArtImport.inventoryOf(art), boundKeys, untouchedKeys = boundKeys))
	val scoringModel = model.copy(sources = model.sources.map { candidate -> if (candidate.id == sourceId) refreshed else candidate })
	val rasterByKey = art.layers.filter { layer -> layer.kind == SourceLayerKind.Raster }.associate { layer -> layer.id.raw to layer.raster }
	return suggestionsFor(scoringModel, sourceId, tileRaster, { key -> rasterByKey[key] }, InventoryLayerMatcher)
}

/**
 * Scores the unresolved bindings of every file in [entries] against its art as read, with the tiles'
 * pixels from the store: what a reload publishes for the review chips once it has landed.
 *
 * @param AtlasRepackHost   host    The session and the raster store.
 * @param List<ReloadEntry> entries The files as read.
 * @return SourceSuggestions Each unresolved binding's best candidate, by file and lost key.
 */
suspend fun scoreSourceSuggestions(host: AtlasRepackHost, entries: List<ReloadEntry>): SourceSuggestions =
	withContext(Dispatchers.Default) {
		val model = host.session.model.value
		val tileRaster = tileRasterLookup(host.artRasters)
		val suggestions = LinkedHashMap<Pair<ArtSourceId, String>, LayerMatch>()
		for (entry in entries) {
			for ((lostKey, match) in scoreSource(model, entry.sourceId, entry.art, tileRaster)) {
				suggestions[entry.sourceId to lostKey] = match
			}
		}
		suggestions
	}

/**
 * The matches to apply out of [suggestions]: those at or above [threshold], best first, each candidate
 * taken once - two lost layers that both prefer one candidate are settled in favour of the more
 * confident, the other left for a person.
 *
 * @param PuppetModel model       The model, for the tiles bound to each lost key.
 * @param ArtSourceId sourceId    The file.
 * @param Map         suggestions The best candidate per lost key.
 * @param Float       threshold   The confidence bar, 0..1.
 * @return List The tile and candidate key pairs to rebind.
 */
private fun acceptedMatches(model: PuppetModel, sourceId: ArtSourceId, suggestions: Map<String, LayerMatch>, threshold: Float): List<Pair<AtlasTileId, String>> {
	val takenCandidates = HashSet<String>()
	val accepted = ArrayList<Pair<AtlasTileId, String>>()
	for ((lostKey, match) in suggestions.entries.sortedByDescending { (_, match) -> match.score }) {
		if (match.score < threshold || !takenCandidates.add(match.key)) {
			continue
		}
		for (tile in model.atlas.tiles) {
			if (tile.source?.sourceId == sourceId && tile.source?.layerKey == lostKey) {
				accepted.add(tile.id to match.key)
			}
		}
	}
	return accepted
}

/**
 * Scores every file in [request] against [base], rebinds the confident matches, and packs the tiles
 * they replaced around the rest.
 *
 * @param PuppetModel            base               The model the match applies to.
 * @param MatchArtworkRequest    request            The files as read.
 * @param Float                  threshold          The confidence bar, 0..1.
 * @param SourceArtImportOptions options            The threshold and margin to plan with.
 * @param SourceArtRasters       artRasters         The document's raster store (read, never written here).
 * @param Boolean                premultipliedAlpha The document's texture-convention flag.
 * @return MatchOutcome The outcome.
 */
private fun matchOutcome(
	base: PuppetModel,
	request: MatchArtworkRequest,
	threshold: Float,
	options: SourceArtImportOptions,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
): MatchOutcome {
	var model = base
	val rasters = LinkedHashMap<AtlasTileId, LayerRaster>()
	val notices = ArrayList<SourceArtImportNotice>()
	val outgrown = ArrayList<DrawableId>()
	val remaining = LinkedHashMap<Pair<ArtSourceId, String>, LayerMatch>()
	var matched = 0
	val tileRaster = tileRasterLookup(artRasters)
	for (entry in request.entries) {
		val suggestions = scoreSource(model, entry.sourceId, entry.art, tileRaster)
		val accepted = acceptedMatches(model, entry.sourceId, suggestions, threshold)
		val acceptedKeys = accepted.mapTo(HashSet()) { (_, key) -> key }
		for ((lostKey, match) in suggestions) {
			if (match.key !in acceptedKeys) {
				remaining[entry.sourceId to lostKey] = match
			}
		}
		if (accepted.isEmpty()) {
			continue
		}
		val plan = ArtworkReloadPlanner.planMatches(model, entry.sourceId, entry.art, accepted, options, tileRaster, entry.contentHash) ?: continue
		val next = model.withArtworkReloaded(plan.reload)
		if (next === model) {
			UmamoLog.error("match artwork: the plan for '${plan.reload.source.name}' collides with the document's ids; that file was skipped")
			continue
		}
		model = next
		rasters.putAll(plan.rasterByTile)
		notices.addAll(plan.notices)
		outgrown.addAll(plan.reload.outgrown)
		matched += plan.reload.replacedTiles.size
	}
	if (model === base) {
		return MatchOutcome.NothingApplied(remaining)
	}
	val change = DocumentChange.MatchArtwork(matched, remaining.size)
	return packMatched(model, rasters.mapValues { (_, raster) -> request.decodedFor(raster) }, notices, artRasters, premultipliedAlpha) { packedModel, textures, packedNotices, decodedByTile ->
		MatchOutcome.Applied(packedModel, textures, decodedByTile, packedNotices, outgrown, change, remaining)
	}
}

/**
 * Repoints [request]'s record at its new file over [base]: the bindings the file resolves by key
 * reload, the rest are flagged, and the unresolved ones are scored against the file's layers.
 *
 * @param PuppetModel            base               The model the replacement applies to.
 * @param ReplaceArtworkRequest  request            The record and the new file.
 * @param SourceArtImportOptions options            The threshold and margin to plan with.
 * @param SourceArtRasters       artRasters         The document's raster store.
 * @param Boolean                premultipliedAlpha The document's texture-convention flag.
 * @return MatchOutcome The outcome.
 */
private fun replaceOutcome(
	base: PuppetModel,
	request: ReplaceArtworkRequest,
	options: SourceArtImportOptions,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
): MatchOutcome {
	val tileRaster = tileRasterLookup(artRasters)
	val plan =
		ArtworkReloadPlanner.plan(base, request.sourceId, request.art, options, tileRaster, request.contentHash, replacement = request.descriptor)
			?: return MatchOutcome.NothingApplied(emptyMap())
	val model = base.withArtworkReloaded(plan.reload)
	if (model === base) {
		UmamoLog.error("replace artwork: the plan for '${request.descriptor.name}' collides with the document's ids; nothing was applied")
		return MatchOutcome.NothingApplied(emptyMap())
	}
	val suggestions =
		scoreSource(model, request.sourceId, request.art, tileRaster).entries.associate { (lostKey, match) -> (request.sourceId to lostKey) to match }
	val resolvedByKey = plan.report.results.count { result -> result is ReconcileResult.Matched }
	val change = DocumentChange.ReplaceArtwork(request.descriptor.name, resolvedByKey, plan.report.needsReview.size)
	return packMatched(model, plan.rasterByTile.mapValues { (_, raster) -> request.decodedFor(raster) }, plan.notices, artRasters, premultipliedAlpha) { packedModel, textures, notices, decodedByTile ->
		MatchOutcome.Applied(packedModel, textures, decodedByTile, notices, plan.reload.outgrown, change, suggestions)
	}
}

/**
 * Packs the tiles a match or replace minted around the document's art and builds the outcome.
 *
 * @param PuppetModel      model              The model with the delta applied.
 * @param Map              decodedByTile      The new tiles' pixels.
 * @param List             notices            The notes so far.
 * @param SourceArtRasters artRasters         The document's raster store, for every other tile.
 * @param Boolean          premultipliedAlpha The document's texture-convention flag.
 * @param Function         applied            Builds the success outcome from the packed model.
 * @return MatchOutcome The outcome, refused when the document's own art could not be kept.
 */
private inline fun packMatched(
	model: PuppetModel,
	decodedByTile: Map<AtlasTileId, DecodedImage>,
	notices: List<SourceArtImportNotice>,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
	applied: (PuppetModel, PuppetTextures?, List<SourceArtImportNotice>, Map<AtlasTileId, DecodedImage>) -> MatchOutcome,
): MatchOutcome {
	if (decodedByTile.isEmpty()) {
		// A replacement that resolved nothing by key mints no tile: the record and the flags land as they are.
		return applied(model, null, notices, decodedByTile)
	}
	val decode: (AtlasTileId) -> DecodedImage? = { tileId -> decodedByTile[tileId] ?: artRasters.decodeRaster(tileId) }
	return when (val packed = packNewTilesAround(model, decodedByTile.keys, decode, premultipliedAlpha, notices)) {
		is PackAroundOutcome.Refused -> MatchOutcome.Refused(packed.refusals)
		is PackAroundOutcome.Packed -> applied(packed.model, packed.textures, packed.notices, decodedByTile)
	}
}

/**
 * Matches the document's unresolved bindings to the layers the matcher is confident about as ONE
 * undo step, registers it on the strip with the threshold row beside the import rows, and publishes
 * the suggestions it did not apply.  Nothing confident enough is a notice, not a step, and the
 * suggestions are published all the same.
 *
 * Runs on the UI thread; the scoring, planning, and pack hop to the default dispatcher.  Planned
 * against the model current at the start, so any edit landing meanwhile supersedes it.
 *
 * @param AtlasRepackHost     host    The session, art, resolver, scope, and shell callbacks.
 * @param MatchArtworkRequest request The files as read and the threshold.
 * @param String?             areaId  The area the strip shows in, or null.
 * @param Function            publish Takes the suggestions for the review chips.
 * @return Boolean Whether a step landed.
 */
suspend fun runMatchArtwork(host: AtlasRepackHost, request: MatchArtworkRequest, areaId: String?, publish: (SourceSuggestions) -> Unit): Boolean {
	val session = host.session
	val modelAtStart = session.model.value
	val outcome =
		withContext(Dispatchers.Default) {
			matchOutcome(modelAtStart, request, request.threshold, request.options, host.artRasters, host.premultipliedAlpha)
		}
	if (!landMatch(host, "match artwork", modelAtStart, outcome, publish) { model -> session.commitArtworkMatched(outcome.appliedChange<DocumentChange.MatchArtwork>(), model) }) {
		if (outcome is MatchOutcome.NothingApplied) {
			UmamoLog.info("match artwork: no binding scored at or above ${percentOf(request.threshold)}% across ${request.entries.size} file(s); ${outcome.suggestions.size} suggestion(s) published")
			session.emitNotice("notice.match.nothing", NoticePlacement.StatusBar)
		}
		return false
	}
	val change = (outcome as MatchOutcome.Applied).change as DocumentChange.MatchArtwork
	session.emitNotice("notice.match.done", NoticePlacement.StatusBar, listOf(change.matchedCount.toString(), change.remainingCount.toString()))
	session.registerAdjustableOperation(session.model.value, areaId, matchArtworkParameters(request.threshold, request.options)) { record ->
		host.scope.launch { adjustMatchArtwork(host, record, request, publish) }
	}
	return true
}

/**
 * Re-scores the match for an adjustment of the strip: the record's threshold and import rows over the
 * record's base model with the SAME read files, landed over the operation's own step.
 *
 * @param AtlasRepackHost     host    The session, resolver, and shell callbacks the first run had.
 * @param AdjustableOperation record  The record with the adjusted parameters.
 * @param MatchArtworkRequest request The first run's files.
 * @param Function            publish Takes the suggestions for the review chips.
 */
internal suspend fun adjustMatchArtwork(host: AtlasRepackHost, record: AdjustableOperation, request: MatchArtworkRequest, publish: (SourceSuggestions) -> Unit) {
	val base = record.baseSnapshot.model
	val threshold = matchThresholdOf(record.parameters, request.threshold)
	val options = addArtworkOptionsOf(record.parameters, request.options)
	val outcome =
		withContext(Dispatchers.Default) {
			matchOutcome(base, request, threshold, options, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		is MatchOutcome.NothingApplied -> {
			UmamoLog.warn("match artwork: at ${percentOf(threshold)}% nothing matches; the previous result stands")
			return
		}
		is MatchOutcome.Refused -> {
			host.report(AtlasRepackReport(outcome.refusals))
			return
		}
		is MatchOutcome.Applied -> Unit
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	prewarm(host, outcome.model, outcome.textures)
	if (!host.session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("match artwork: the adjustment was superseded before it landed; nothing was applied")
		return
	}
	publish(outcome.suggestions)
	reportMatch("match artwork", outcome, outcome.model)
}

/**
 * Repoints one artwork record at another file as ONE undo step, registered on the strip with the
 * import rows, and publishes the suggestions for whatever the new file did not resolve by key.
 *
 * @param AtlasRepackHost       host    The session, art, resolver, scope, and shell callbacks.
 * @param ReplaceArtworkRequest request The record and the new file.
 * @param String?               areaId  The area the strip shows in, or null.
 * @param Function              publish Takes the suggestions for the review chips.
 * @return Boolean Whether the step landed.
 */
suspend fun runReplaceArtwork(host: AtlasRepackHost, request: ReplaceArtworkRequest, areaId: String?, publish: (SourceSuggestions) -> Unit): Boolean {
	val session = host.session
	val modelAtStart = session.model.value
	val outcome =
		withContext(Dispatchers.Default) {
			replaceOutcome(modelAtStart, request, request.options, host.artRasters, host.premultipliedAlpha)
		}
	if (!landMatch(host, "replace artwork", modelAtStart, outcome, publish) { model -> session.commitArtworkReplaced(outcome.appliedChange<DocumentChange.ReplaceArtwork>(), model) }) {
		return false
	}
	val change = (outcome as MatchOutcome.Applied).change as DocumentChange.ReplaceArtwork
	session.emitNotice("notice.replace.done", NoticePlacement.StatusBar, listOf(change.matchedCount.toString(), change.missingCount.toString()))
	session.registerAdjustableOperation(session.model.value, areaId, addArtworkParameters(request.options)) { record ->
		host.scope.launch { adjustReplaceArtwork(host, record, request, publish) }
	}
	return true
}

/**
 * Re-plans a replacement for an adjustment of the strip over the record's base.
 *
 * @param AtlasRepackHost       host    The session, resolver, and shell callbacks the first run had.
 * @param AdjustableOperation   record  The record with the adjusted parameters.
 * @param ReplaceArtworkRequest request The first run's request.
 * @param Function              publish Takes the suggestions for the review chips.
 */
internal suspend fun adjustReplaceArtwork(host: AtlasRepackHost, record: AdjustableOperation, request: ReplaceArtworkRequest, publish: (SourceSuggestions) -> Unit) {
	val base = record.baseSnapshot.model
	val options = addArtworkOptionsOf(record.parameters, request.options)
	val outcome =
		withContext(Dispatchers.Default) {
			replaceOutcome(base, request, options, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		is MatchOutcome.NothingApplied -> {
			UmamoLog.warn("replace artwork: under the adjusted options nothing changes; the previous result stands")
			return
		}
		is MatchOutcome.Refused -> {
			host.report(AtlasRepackReport(outcome.refusals))
			return
		}
		is MatchOutcome.Applied -> Unit
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	prewarm(host, outcome.model, outcome.textures)
	if (!host.session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("replace artwork: the adjustment was superseded before it landed; nothing was applied")
		return
	}
	publish(outcome.suggestions)
	reportMatch("replace artwork", outcome, outcome.model)
}

/**
 * The applied outcome's change as the type the commit expects.
 *
 * @return TChange The change.
 */
private inline fun <reified TChange : DocumentChange> MatchOutcome.appliedChange(): TChange = (this as MatchOutcome.Applied).change as TChange

/**
 * Lands a match or replace outcome: the supersede check, the rasters into the store, the commit, the
 * pre-warm, the log, and the suggestions published.  A pass that applied nothing publishes its
 * suggestions and returns false without a step.
 *
 * @param AtlasRepackHost host         The session, art, resolver, and shell callbacks.
 * @param String          operation    The log prefix.
 * @param PuppetModel     modelAtStart The model the outcome was planned against.
 * @param MatchOutcome    outcome      What the pass produced.
 * @param Function        publish      Takes the suggestions for the review chips.
 * @param Function        commit       Commits the packed model and returns the committed one.
 * @return Boolean Whether a step landed.
 */
private inline fun landMatch(
	host: AtlasRepackHost,
	operation: String,
	modelAtStart: PuppetModel,
	outcome: MatchOutcome,
	publish: (SourceSuggestions) -> Unit,
	commit: (PuppetModel) -> PuppetModel,
): Boolean {
	val session = host.session
	when (outcome) {
		is MatchOutcome.NothingApplied -> {
			publish(outcome.suggestions)
			return false
		}
		is MatchOutcome.Refused -> {
			for (refusal in outcome.refusals) {
				UmamoLog.warn("$operation: the document's tile '${refusal.tileName}' could not be kept in place (${refusal.reason}); nothing was applied")
			}
			host.report(AtlasRepackReport(outcome.refusals))
			return false
		}
		is MatchOutcome.Applied -> Unit
	}
	if (session.model.value !== modelAtStart) {
		UmamoLog.warn("$operation: the document changed while its files were being read; nothing was applied")
		session.emitNotice("notice.import.artworkSuperseded", NoticePlacement.StatusBar)
		return false
	}
	host.artRasters.addDecoded(outcome.decodedByTile)
	val committed = commit(outcome.model)
	prewarm(host, committed, outcome.textures)
	publish(outcome.suggestions)
	reportMatch(operation, outcome, committed)
	return true
}

/**
 * Pre-warms the session's page resolver with the pages a pass composed, when it composed any.
 *
 * @param AtlasRepackHost host     The session's resolver.
 * @param PuppetModel     model    The model whose atlas the pages belong to.
 * @param PuppetTextures? textures The pages, or null when the pass packed nothing.
 */
private fun prewarm(host: AtlasRepackHost, model: PuppetModel, textures: PuppetTextures?) {
	if (textures != null) {
		host.sessionAtlasPages?.prewarm(model.atlas, textures)
	}
}

/**
 * Logs one match or replace pass the way the first run and an adjustment both report it.
 *
 * @param String               operation The log prefix.
 * @param MatchOutcome.Applied outcome   What the pass produced.
 * @param PuppetModel          committed The model that landed.
 */
private fun reportMatch(operation: String, outcome: MatchOutcome.Applied, committed: PuppetModel) {
	for (notice in outcome.notices) {
		UmamoLog.warn("$operation: ${describeImportNotice(notice)}")
	}
	for (drawableId in outcome.outgrown) {
		val name = committed.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.name ?: drawableId.raw
		UmamoLog.warn("$operation: the new art of '$name' reaches past its edited mesh; re-mesh it or extend the mesh")
	}
	for ((binding, match) in outcome.suggestions) {
		UmamoLog.info("$operation: '${binding.second}' in '${binding.first.raw}' left for review; best candidate '${match.key}' at ${percentOf(match.score)}%")
	}
	when (val change = outcome.change) {
		is DocumentChange.MatchArtwork ->
			UmamoLog.info("$operation: ${change.matchedCount} tile(s) rebound, ${change.remainingCount} left for review; now ${committed.atlas.pages.size} page(s); ${outcome.notices.size} note(s)")
		is DocumentChange.ReplaceArtwork ->
			UmamoLog.info("$operation: '${change.sourceName}' -> ${change.matchedCount} tile(s) reloaded by key, ${change.missingCount} left for review; ${outcome.notices.size} note(s)")
		else -> UmamoLog.info("$operation: landed")
	}
}

/**
 * A confidence as the whole percentage a person reads.
 *
 * @param Float score The confidence, 0..1.
 * @return Int The percentage.
 */
fun percentOf(score: Float): Int = (score * 100f + 0.5f).toInt().coerceIn(0, 100)