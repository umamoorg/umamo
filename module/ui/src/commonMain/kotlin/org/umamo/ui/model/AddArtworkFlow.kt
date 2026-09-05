package org.umamo.ui.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.NoticePlacement
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterUnit
import org.umamo.edit.commitArtworkAdded
import org.umamo.edit.intValue
import org.umamo.edit.withArtworkAdded
import org.umamo.edit.withAtlasRepack
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackSkipReason
import org.umamo.format.atlas.packAtlas
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtAdditions
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.render.generatedPuppetTextures
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog

/*
 * Adding an artwork file to an OPEN document: the bridge's additions appended to the live model, the
 * new tiles packed into the gaps around the art already on the pages, committed as one undo step and
 * registered on the operation settings strip.  The repack's shape throughout - the same pack
 * primitive, the same supersede checks, the same amend-in-place adjustment - so a second artwork
 * file lands the way a repack does rather than through a second pipeline.
 */

/** The parameter keys the add-artwork rows carry (the strip maps their label keys to strings). */
internal object ImportParameterKeys {
	const val ALPHA_THRESHOLD = "import.alphaThreshold"
	const val MARGIN = "import.margin"
}

/** The widest birth-mesh margin the strip offers, in source pixels. */
internal const val IMPORT_MAX_MARGIN = 64

/**
 * One artwork file to add to the open document, as read from disk.
 *
 * Holds the parsed art for the strip's adjustments (a re-run re-derives the additions from the same
 * layers) and the decoded wrapper of every layer raster, minted once: a re-run hands the raster store
 * the same instances again, and the renderer's texture cache keys on that identity.
 *
 * @property SourceArt              art        The parsed source art.
 * @property ArtSourceDescriptor    descriptor What the model records about the file.
 * @property SourceArtImportOptions options    The threshold and margin the first run uses.
 */
class AddArtworkRequest(
	val art: SourceArt,
	val descriptor: ArtSourceDescriptor,
	val options: SourceArtImportOptions,
) {
	// LayerRaster is a plain class, so this map keys by identity - which is the point: the wrapper of
	// one raster is one object for the request's life.  Built eagerly so the off-thread passes only read.
	private val decodedByRaster: Map<LayerRaster, DecodedImage> =
		art.layers.associate { layer -> layer.raster to DecodedImage(layer.raster.rgba, layer.raster.width, layer.raster.height) }

	/**
	 * The decoded wrapper of one of this file's layer rasters.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call.
	 */
	internal fun decodedFor(raster: LayerRaster): DecodedImage =
		decodedByRaster[raster] ?: DecodedImage(raster.rgba, raster.width, raster.height)
}

/** What one add-artwork pass produced, or why it produced nothing. */
private sealed interface AddArtworkOutcome {
	/** The file has no layer with art to add. */
	data object NothingToAdd : AddArtworkOutcome

	/**
	 * The pack could not keep some of the document's OWN art where it is - a placed tile the packer
	 * would not hold fixed - so nothing was applied; the refusals name the tiles.
	 *
	 * @property List refusals The document's tiles the pack could not keep.
	 */
	class Refused(val refusals: List<AtlasRepackRefusal>) : AddArtworkOutcome

	/**
	 * The additions appended and packed.
	 *
	 * @property SourceArtAdditions added    The bridge's delta, pixels, and notes.
	 * @property PuppetModel        model    The base with the additions appended and packed.
	 * @property PuppetTextures     textures The pages the pack composed, index-parallel to the model's.
	 * @property List               notices  The bridge's notes plus every added tile the pack left unplaced.
	 */
	class Added(
		val added: SourceArtAdditions,
		val model: PuppetModel,
		val textures: PuppetTextures,
		val notices: List<SourceArtImportNotice>,
	) : AddArtworkOutcome
}

/**
 * The strip's rows for an add-artwork operation.
 *
 * @param SourceArtImportOptions options The options the rows show.
 * @return List The rows.
 */
internal fun addArtworkParameters(options: SourceArtImportOptions): List<OperatorParameter> =
	listOf(
		OperatorParameter.IntParameter(ImportParameterKeys.ALPHA_THRESHOLD, ImportParameterKeys.ALPHA_THRESHOLD, options.alphaThreshold, 1, 255),
		OperatorParameter.IntParameter(ImportParameterKeys.MARGIN, ImportParameterKeys.MARGIN, options.birthMeshMargin, 0, IMPORT_MAX_MARGIN, unit = ParameterUnit.Pixels),
	)

/**
 * The options [parameters] describe, over [fallback] for the template the rows do not carry.
 *
 * @param List                   parameters The strip's rows.
 * @param SourceArtImportOptions fallback   The options the first run used.
 * @return SourceArtImportOptions The adjusted options.
 */
internal fun addArtworkOptionsOf(parameters: List<OperatorParameter>, fallback: SourceArtImportOptions): SourceArtImportOptions =
	SourceArtImportOptions(
		parameterTemplate = fallback.parameterTemplate,
		alphaThreshold = parameters.intValue(ImportParameterKeys.ALPHA_THRESHOLD, fallback.alphaThreshold).coerceIn(1, 255),
		birthMeshMargin = parameters.intValue(ImportParameterKeys.MARGIN, fallback.birthMeshMargin).coerceIn(0, IMPORT_MAX_MARGIN),
	)

/**
 * Appends [request]'s art to [base] and packs the new tiles into the gaps: every tile already on a
 * page is handed to the packer fixed (pinned or not), the additions pack around them at the
 * document's own page size (once more at [MAX_IMPORT_PAGE_SIZE] if a layer does not fit), and the
 * result re-derives through [withAtlasRepack] under the document's current composition so the pages
 * the existing art sits on compose exactly as they did.  A new tile the pack cannot carry stays
 * unplaced and is a note; the source-layer display still draws it.
 *
 * The new rasters are added to [artRasters] here, before the pack reads them; the store is
 * document-lifetime, so an adjustment finds them already present.
 *
 * @param PuppetModel            base               The model the additions join.
 * @param AddArtworkRequest      request            The file.
 * @param SourceArtImportOptions options            The threshold and margin to import with.
 * @param SourceArtRasters       artRasters         The document's raster store.
 * @param Boolean                premultipliedAlpha The document's texture-convention flag.
 * @return AddArtworkOutcome? The outcome, or null when the file has no layer with art to add.
 */
private fun addArtworkOutcome(
	base: PuppetModel,
	request: AddArtworkRequest,
	options: SourceArtImportOptions,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
): AddArtworkOutcome {
	val added = SourceArtImport.additionsFor(request.art, request.descriptor, options, base)
	if (added.additions.drawables.isEmpty()) {
		return AddArtworkOutcome.NothingToAdd
	}
	val withArt = base.withArtworkAdded(added.additions)
	if (withArt === base) {
		UmamoLog.error("add artwork: the additions for '${request.descriptor.name}' collide with the document's ids; nothing was applied")
		return AddArtworkOutcome.NothingToAdd
	}
	val decodedByTile = added.rasterByTile.mapValues { (_, raster) -> request.decodedFor(raster) }
	artRasters.addDecoded(decodedByTile)

	// The document's composition governs the pack, so the fixed tiles compose as they already do; the
	// gutter grows to hold the extrusion when a repack widened it past the default.
	val composition = withArt.atlas.composition
	val defaults = AtlasPackOptions()
	var packOptions =
		AtlasPackOptions(
			maxPageSize = repackPageSizeOf(withArt),
			gutter = maxOf(defaults.gutter, composition.extrude),
			extrude = composition.extrude,
			alphaThreshold = composition.alphaThreshold,
		)
	val decode: (AtlasTileId) -> DecodedImage? = { tileId -> decodedByTile[tileId] ?: artRasters.decodeRaster(tileId) }
	var packed = packAtlasOf(withArt, decode, packOptions, keepPinned = true, fixPlaced = true)
	if (packOptions.maxPageSize < MAX_IMPORT_PAGE_SIZE && packed.result.skipped.any { skip -> skip.reason == AtlasPackSkipReason.LargerThanPage }) {
		packOptions = packOptions.copy(maxPageSize = MAX_IMPORT_PAGE_SIZE)
		packed = PackedAtlas(packed.input, packAtlas(packed.input.itemsFor(keepPinned = true, fixPlaced = true), packOptions))
	}
	// A refusal over the document's OWN art aborts the add: the lowering would pack that tile out,
	// and the document's art must not move for a file being added.  A refusal over an ADDED tile is a
	// note - it stays unplaced and the source-layer display still draws it.
	val addedTileIds = added.additions.tiles.mapTo(HashSet()) { tile -> tile.id }
	val addedTileNames = added.additions.tiles.mapTo(HashSet()) { tile -> tile.name }
	val refusals = repackRefusals(withArt, packed.result.skipped, packed.input.undecodableTileIds)
	val skippedKeys = packed.result.skipped.mapTo(HashSet()) { skip -> skip.key }
	val existingRefusals =
		refusals.filter { refusal ->
			withArt.atlas.tiles.any { tile -> tile.name == refusal.tileName && tile.id !in addedTileIds && (tile.id.raw in skippedKeys || tile.id in packed.input.undecodableTileIds) }
		}
	if (existingRefusals.isNotEmpty()) {
		return AddArtworkOutcome.Refused(existingRefusals)
	}
	val notices = ArrayList(added.notices)
	for (refusal in refusals) {
		if (refusal.tileName !in addedTileNames) {
			continue
		}
		notices.add(
			when (refusal.reason) {
				AtlasRepackRefusalReason.LargerThanPage -> SourceArtImportNotice.LayerLargerThanPage(refusal.tileName)
				else -> SourceArtImportNotice.LayerNotPacked(refusal.tileName, refusal.reason.name)
			},
		)
	}
	val lowered = lowerPack(withArt.atlas, packed.result)
	val model = withArt.withAtlasRepack(lowered.pages, lowered.placementsByTile, composition)
	logPack(packed.result, lowered, packOptions)
	return AddArtworkOutcome.Added(added, model, generatedPuppetTextures(packed.result.pages, model, premultipliedAlpha), notices)
}

/**
 * Adds an artwork file to the open document as ONE undo step, packed beside the art already there,
 * and registers the step on the operation settings strip (Alpha Threshold, Birth Mesh Margin).
 *
 * Runs on the UI thread; the bridge and the pack hop to the default dispatcher.  The additions are
 * minted against the model current at the start, so ANY edit landing while they were built
 * supersedes them - stricter than the repack, whose pack only depends on the atlas and the reaches.
 *
 * @param AtlasRepackHost   host    The session, art, resolver, scope, and shell callbacks.
 * @param AddArtworkRequest request The file to add.
 * @param String?           areaId  The area the command was dispatched over, or null - where the strip shows.
 * @return Boolean Whether the artwork was added.
 */
suspend fun runAddArtwork(host: AtlasRepackHost, request: AddArtworkRequest, areaId: String?): Boolean {
	val session = host.session
	val modelAtStart = session.model.value
	val outcome =
		withContext(Dispatchers.Default) {
			addArtworkOutcome(modelAtStart, request, request.options, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		AddArtworkOutcome.NothingToAdd -> {
			UmamoLog.warn("add artwork: '${request.descriptor.name}' has no layer with pixels to add; nothing was applied")
			session.emitNotice("notice.import.noArtLayers", NoticePlacement.StatusBar)
			return false
		}
		is AddArtworkOutcome.Refused -> {
			for (refusal in outcome.refusals) {
				UmamoLog.warn("add artwork: the document's tile '${refusal.tileName}' could not be kept in place (${refusal.reason}); nothing was applied")
			}
			host.report(AtlasRepackReport(outcome.refusals))
			return false
		}
		is AddArtworkOutcome.Added -> Unit
	}
	if (session.model.value !== modelAtStart) {
		UmamoLog.warn("add artwork: the document changed while '${request.descriptor.name}' was being added; nothing was applied")
		session.emitNotice("notice.import.artworkSuperseded", NoticePlacement.StatusBar)
		return false
	}
	val drawableCount = outcome.added.additions.drawables.size
	val committed = session.commitArtworkAdded(request.descriptor.name, drawableCount, outcome.model)
	host.sessionAtlasPages?.prewarm(committed.atlas, outcome.textures)
	reportAddArtwork(request, outcome, committed)
	session.emitNotice(if (outcome.notices.isEmpty()) "notice.import.artworkAdded" else "notice.import.artworkNotes", NoticePlacement.StatusBar)
	session.registerAdjustableOperation(committed, areaId, addArtworkParameters(request.options)) { record ->
		host.scope.launch { adjustAddArtwork(host, record, request) }
	}
	return true
}

/**
 * Re-derives the additions for an adjustment of the strip: the record's rows become the options, the
 * SAME parsed art is imported again over the record's base model, packed, and landed over the
 * operation's own step.  A record cleared while the pass ran makes [org.umamo.edit.EditorSession.amendLastCommit]
 * drop the result rather than commit over what the rigger did since.
 *
 * @param AtlasRepackHost     host    The session, resolver, and shell callbacks the first run had.
 * @param AdjustableOperation record  The record with the adjusted parameters.
 * @param AddArtworkRequest   request The first run's file.
 */
internal suspend fun adjustAddArtwork(host: AtlasRepackHost, record: AdjustableOperation, request: AddArtworkRequest) {
	val base = record.baseSnapshot.model
	val options = addArtworkOptionsOf(record.parameters, request.options)
	val outcome =
		withContext(Dispatchers.Default) {
			addArtworkOutcome(base, request, options, host.artRasters, host.premultipliedAlpha)
		}
	when (outcome) {
		AddArtworkOutcome.NothingToAdd -> {
			UmamoLog.warn("add artwork: under the adjusted options '${request.descriptor.name}' has nothing to add; the previous result stands")
			return
		}
		is AddArtworkOutcome.Refused -> {
			host.report(AtlasRepackReport(outcome.refusals))
			return
		}
		is AddArtworkOutcome.Added -> Unit
	}
	host.sessionAtlasPages?.prewarm(outcome.model.atlas, outcome.textures)
	if (!host.session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("add artwork: the adjustment was superseded before it landed; nothing was applied")
		return
	}
	reportAddArtwork(request, outcome, outcome.model)
}

/**
 * Logs one add-artwork pass the way the first run and an adjustment both report it.
 *
 * @param AddArtworkRequest request   The file.
 * @param AddArtworkOutcome outcome   What the pass produced.
 * @param PuppetModel       committed The model that landed.
 */
private fun reportAddArtwork(request: AddArtworkRequest, outcome: AddArtworkOutcome.Added, committed: PuppetModel) {
	for (notice in outcome.notices) {
		UmamoLog.warn("add artwork: ${describeImportNotice(notice)}")
	}
	val additions = outcome.added.additions
	UmamoLog.info(
		"add artwork: ${request.descriptor.name} -> ${additions.drawables.size} drawable(s), ${additions.parts.size} part(s)," +
			" now ${committed.atlas.pages.size} page(s); ${outcome.notices.size} note(s)",
	)
}

/**
 * One import notice as a log line: plain English naming the layer and what happened to it, the same
 * shape the export notices log in.  Shared by the open (an artwork-origin document) and the add.
 *
 * @param SourceArtImportNotice notice The notice.
 * @return String The log text.
 */
fun describeImportNotice(notice: SourceArtImportNotice): String =
	when (notice) {
		is SourceArtImportNotice.NonRasterLayer -> "layer '${notice.layerName}' is a ${notice.kind.name.lowercase()} layer with no pixels; skipped"
		is SourceArtImportNotice.EmptyLayer -> "layer '${notice.layerName}' has no opaque pixels; skipped"
		is SourceArtImportNotice.BlendUnsupported -> "layer '${notice.layerName}' blends with ${notice.blend.name}, which has no equivalent; imported as Normal"
		is SourceArtImportNotice.BlendApproximated -> "layer '${notice.layerName}' blends with ${notice.blend.name}; imported as the nearest mode, ${notice.mappedTo.name}"
		is SourceArtImportNotice.ClipBaseMissing -> "layer '${notice.layerName}' clips to the layer below but has none in its folder; imported unclipped"
		is SourceArtImportNotice.ChannelMaskDropped -> "layer '${notice.layerName}' writes only some color channels; imported writing all of them"
		is SourceArtImportNotice.FolderBlendUnsupported -> "folder '${notice.groupPath}' blends with ${notice.blend.name}, which has no equivalent; its part composites as Normal"
		is SourceArtImportNotice.FolderClipDropped -> "folder '${notice.groupPath}' clips to the layer below; its part imports unclipped"
		is SourceArtImportNotice.LayerLargerThanPage -> "layer '${notice.layerName}' is larger than the largest atlas page; left unpacked"
		is SourceArtImportNotice.LayerNotPacked -> "layer '${notice.layerName}' could not be packed (${notice.reason}); left unpacked"
	}