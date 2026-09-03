package org.umamo.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.commitAtlasRepack
import org.umamo.edit.withAtlasRepack
import org.umamo.format.atlas.AtlasPackFixed
import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.format.atlas.AtlasPackResult
import org.umamo.format.atlas.AtlasPackSkip
import org.umamo.format.atlas.AtlasPackSkipReason
import org.umamo.format.atlas.packAtlas
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.render.atlasCompositionOf
import org.umamo.render.atlasPlacementFromPack
import org.umamo.render.generatedPuppetTextures
import org.umamo.render.meshReserveByTile
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.placementAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.storage.UmamoLog

/** The maximum page size a repack packs against when the document has no pages to take one from. */
const val DEFAULT_REPACK_PAGE_SIZE: Int = 4096

/** Why one tile kept the whole repack from running. */
enum class AtlasRepackRefusalReason {
	/** The tile's trimmed art plus its gutter does not fit a page even alone. */
	LargerThanPage,

	/** Nothing in the tile's art meets the alpha threshold, so the pack has nothing to place. */
	NoOpaquePixels,

	/** The tile's art has opaque pixels but fewer than the pack's configured minimum. */
	BelowMinimumCoverage,

	/** The tile's art would not decode, or decodes to a size that disagrees with the tile. */
	Undecodable,

	/** The tile's current placement has no invertible mapping, so its coordinates cannot follow. */
	DegeneratePlacement,

	/** The tile is pinned at a spot that does not fit inside the maximum page size, so it cannot be kept. */
	PinnedOffPage,
}

/**
 * One tile the repack refused over.
 *
 * @property String                  tileName The tile's display name - document data, shown verbatim.
 * @property AtlasRepackRefusalReason reason  Why it could not come along.
 */
class AtlasRepackRefusal(
	val tileName: String,
	val reason: AtlasRepackRefusalReason,
)

/**
 * The repack abort report: every BOUND tile the pack could not carry.  A repack is all or nothing -
 * a tile with drawables that came out of the pack unplaced would leave those drawables without a
 * page, so one refusal aborts the whole operation and this names each one.
 *
 * @property List refusals The refused tiles, in document tile order.
 */
class AtlasRepackReport(
	val refusals: List<AtlasRepackRefusal>,
)

/**
 * What one repack feeds the packer.
 *
 * The items are every tile as a FREE item; the pinned tiles' placements sit beside them so one
 * decoded input serves a pack that keeps the pins and a pack that ignores them - the strip's Keep
 * Pinned Tiles row flips between the two without re-decoding.
 *
 * @property List items              The tiles' pixels plus their mesh reserves, all free.
 * @property Map  fixedByKey         The pinned placed tiles' placements as the packer's fixed form, by key.
 * @property Set  undecodableTileIds Tiles whose art would not decode or disagrees with its tile.
 * @property Map  reserveByTile      The mesh reserves the items were built with, kept so the commit
 *                                   can tell whether a mesh edit during the pack staled them.
 */
internal class RepackPackInput(
	val items: List<AtlasPackItem>,
	val fixedByKey: Map<String, AtlasPackFixed>,
	val undecodableTileIds: Set<AtlasTileId>,
	val reserveByTile: Map<AtlasTileId, AtlasPackReserve>,
) {
	/**
	 * The items to pack: the pinned tiles fixed where they are when [keepPinned], else every tile free.
	 * Items hold their pixels by reference, so the fixed copies cost nothing.
	 *
	 * @param Boolean keepPinned Whether the pinned tiles stay put.
	 * @return List The pack items.
	 */
	fun itemsFor(keepPinned: Boolean): List<AtlasPackItem> {
		if (!keepPinned || fixedByKey.isEmpty()) {
			return items
		}
		return items.map { item ->
			val fixed = fixedByKey[item.key] ?: return@map item
			AtlasPackItem(item.key, item.width, item.height, item.rgba, item.reserve, fixed)
		}
	}
}

/**
 * Builds the pack input for [model]: every tile that is used OR already packed, with its pixels and
 * its mesh reserve.
 *
 * A placed tile nobody samples keeps its spot on the pages (the file's own image entries describe it
 * there), while never-packed unbound art stays out.  One function shared by the live repack and the
 * corpus gate, so what the gate proves disjoint is exactly what the command packs.
 *
 * Thread-safe given a thread-safe [decodeRaster]; the live flow runs it on the default dispatcher.
 *
 * @param PuppetModel model        The model to pack.
 * @param Function    decodeRaster Yields a tile's decoded pixels, or null.
 * @return RepackPackInput The pack items and the tiles that would not decode.
 */
internal fun buildRepackPackInput(
	model: PuppetModel,
	decodeRaster: (AtlasTileId) -> DecodedImage?,
): RepackPackInput {
	val boundTileIds = model.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
	val reserveByTile = meshReserveByTile(model)
	val items = ArrayList<AtlasPackItem>()
	val fixedByKey = HashMap<String, AtlasPackFixed>()
	val undecodable = HashSet<AtlasTileId>()
	for (tile in model.atlas.tiles) {
		if (tile.id !in boundTileIds && tile.placement == null) {
			continue
		}
		val raster = decodeRaster(tile.id)
		if (raster == null || raster.width != tile.width || raster.height != tile.height) {
			undecodable.add(tile.id)
		} else {
			items.add(AtlasPackItem(tile.id.raw, raster.width, raster.height, raster.rgba, reserveByTile[tile.id]))
			val placement = tile.placement
			if (tile.pinned && placement != null) {
				fixedByKey[tile.id.raw] = AtlasPackFixed(placement.pageIndex, placementAffine(placement))
			}
		}
	}
	return RepackPackInput(items, fixedByKey, undecodable, reserveByTile)
}

/**
 * The refusals a pack outcome implies for [model]: bound tiles the packer skipped, bound tiles whose
 * art would not decode, and bound tiles whose CURRENT placement has no invertible mapping (the UV
 * re-derivation reads through it, so a degenerate one strands the coordinates).  Unbound tiles never
 * refuse - with no drawables over them there is nothing to strand.
 *
 * @param PuppetModel        model             The model the repack would apply to.
 * @param List               packSkips         What the packer left out, keyed by tile id.
 * @param Set                undecodableTileIds Bound tiles whose art would not decode.
 * @return List The refusals, empty when the repack may proceed.
 */
internal fun repackRefusals(
	model: PuppetModel,
	packSkips: List<AtlasPackSkip>,
	undecodableTileIds: Set<AtlasTileId>,
): List<AtlasRepackRefusal> {
	val boundTileIds = model.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
	val skipReasonByTileId = packSkips.associateBy({ skip -> AtlasTileId(skip.key) }, { skip -> skip.reason })
	val refusals = ArrayList<AtlasRepackRefusal>()
	for (tile in model.atlas.tiles) {
		if (tile.id !in boundTileIds) {
			continue
		}
		if (tile.id in undecodableTileIds) {
			refusals.add(AtlasRepackRefusal(tile.name, AtlasRepackRefusalReason.Undecodable))
			continue
		}
		val skipReason = skipReasonByTileId[tile.id]
		if (skipReason != null) {
			val reason =
				when (skipReason) {
					AtlasPackSkipReason.LargerThanPage -> AtlasRepackRefusalReason.LargerThanPage
					AtlasPackSkipReason.NoOpaquePixels -> AtlasRepackRefusalReason.NoOpaquePixels
					AtlasPackSkipReason.BelowMinimumCoverage -> AtlasRepackRefusalReason.BelowMinimumCoverage
					AtlasPackSkipReason.FixedOutsidePage -> AtlasRepackRefusalReason.PinnedOffPage
				}
			refusals.add(AtlasRepackRefusal(tile.name, reason))
			continue
		}
		if (tile.placement != null) {
			// The same mapping the re-derivation reads: the placement plus the page it names.  A page
			// the model does not have counts as degenerate too - the mapping cannot be formed at all.
			val storedToArt = model.atlas.storedToArtAffineForTile(tile.id)
			if (storedToArt == null || invertUvAffine(storedToArt) == null) {
				refusals.add(AtlasRepackRefusal(tile.name, AtlasRepackRefusalReason.DegeneratePlacement))
			}
		}
	}
	return refusals
}

/**
 * Everything a repack needs from the shell besides its options: the session to commit into, the art
 * to pack, the page resolver to pre-warm, the texture convention, the scope an adjustment re-runs on,
 * and the two shell callbacks - the refusal report and the sticky-options record.  Built once per
 * document by the shell and handed to [runAtlasRepack]; the adjustment closure keeps it.
 *
 * @property EditorSession      session            The session to commit into.
 * @property SourceArtRasters   artRasters         The source-art pixels to pack.
 * @property SessionAtlasPages? sessionAtlasPages  The session's page resolver, pre-warmed on success.
 * @property Boolean            premultipliedAlpha The document's texture-convention flag, carried onto the new set.
 * @property CoroutineScope     scope              Where an adjustment's re-pack launches (the shell's scope).
 * @property Function           report             Receives the abort report when a pack refuses.
 * @property Function           rememberOptions    Receives the options and the keep-pinned choice every
 *   successful pack ran with.
 */
class AtlasRepackHost(
	val session: EditorSession,
	val artRasters: SourceArtRasters,
	val sessionAtlasPages: SessionAtlasPages?,
	val premultipliedAlpha: Boolean,
	val scope: CoroutineScope,
	val report: (AtlasRepackReport) -> Unit,
	val rememberOptions: (AtlasPackOptions, Boolean) -> Unit,
)

/**
 * The maximum page size [model]'s repack packs against by default: its largest page's side, or
 * [DEFAULT_REPACK_PAGE_SIZE] when it has none.
 *
 * @param PuppetModel model The document.
 * @return Int The page size in pixels.
 */
fun repackPageSizeOf(model: PuppetModel): Int = model.atlas.pages.maxOfOrNull { page -> maxOf(page.width, page.height) } ?: DEFAULT_REPACK_PAGE_SIZE

/**
 * What one pack produced for the model: the new pages and every tile's lowered placement.
 *
 * @property List pages            The new page inventory.
 * @property Map  placementsByTile Every tile's new placement, null for one packed out.
 * @property Int  packedOutCount   Placed-but-unbound tiles the pack could not carry, now unpacked.
 */
private class LoweredPack(
	val pages: List<AtlasPage>,
	val placementsByTile: Map<AtlasTileId, AtlasPlacement?>,
	val packedOutCount: Int,
)

/**
 * Lowers a pack result onto [atlas]'s tiles: every packed tile gets its placement, every tile the
 * pack kept fixed restates the placement it already has, and every other tile is restated unpacked.
 * A placed-but-unbound tile the pack could not carry (empty, oversized, undecodable) leaves the
 * pages rather than blocking the repack over art nothing samples; each is logged and counted.
 *
 * @param PuppetAtlas     atlas      The atlas being repacked.
 * @param AtlasPackResult packResult What the packer produced.
 * @return LoweredPack The pages and placements to commit.
 */
private fun lowerPack(atlas: PuppetAtlas, packResult: AtlasPackResult): LoweredPack {
	val pages = packResult.pages.map { page -> AtlasPage(page.width, page.height) }
	val packedByKey = packResult.placements.associateBy { placement -> placement.key }
	val keptKeys = packResult.fixed.mapTo(HashSet()) { kept -> kept.key }
	val placementsByTile = HashMap<AtlasTileId, AtlasPlacement?>()
	var packedOut = 0
	for (tile in atlas.tiles) {
		if (tile.id.raw in keptKeys) {
			placementsByTile[tile.id] = tile.placement
			continue
		}
		val packed = packedByKey[tile.id.raw]
		placementsByTile[tile.id] = packed?.let { placement -> atlasPlacementFromPack(placement) }
		if (packed == null && tile.placement != null) {
			UmamoLog.warn("repack: unbound tile '${tile.name}' could not be packed and leaves the pages")
			packedOut++
		}
	}
	return LoweredPack(pages, placementsByTile, packedOut)
}

/**
 * Logs one pack's outcome the way both the first run and an adjustment report it.
 *
 * @param AtlasPackResult packResult The pack.
 * @param LoweredPack     lowered    Its lowering.
 * @param AtlasPackOptions options   The options it ran with.
 */
private fun logPack(packResult: AtlasPackResult, lowered: LoweredPack, options: AtlasPackOptions) {
	val occupancy =
		packResult.pages.indices.joinToString(separator = ", ") { pageIndex ->
			"${(packResult.pageOccupancy(pageIndex) * 100f).toInt()}%"
		}
	UmamoLog.info(
		"repack: ${packResult.placements.size} tile(s) onto ${lowered.pages.size} page(s) at ${options.maxPageSize} px" +
			" (occupancy $occupancy; gutter ${options.gutter}, extrude ${options.extrude}, rotation ${options.allowRotation});" +
			" ${packResult.fixed.size} pinned tile(s) kept, ${lowered.packedOutCount} unbound tile(s) packed out",
	)
}

/**
 * Repacks the document's atlas: decodes every bound tile's art, runs the shared deterministic packer
 * under [options], and commits the new pages + placements + re-derived coordinates as ONE undo step,
 * pre-warming the session's page resolver with the pages the pack already composed so nothing
 * composes twice.  The viewport and every UV editor follow the MODEL - the resolver publishes the
 * pages and the engine swaps them - so this function ends at the commit and the registration.
 *
 * The commit registers itself as the session's adjustable operation (the operation settings strip):
 * its rows are the pack options, and editing one re-packs the SAME decoded input from the model the
 * repack ran on and lands over the repack's own history step ([EditorSession.amendLastCommit]) - one
 * pack per adjustment, no decode, nothing replayed.  See [adjustAtlasRepack].
 *
 * All or nothing: any bound tile the pack cannot carry aborts the whole repack into the host's
 * report (the modal report surface - a lost tile must not be a four-second toast).  Unbound tiles
 * are packed out (placement null) with a log line; art nobody samples does not spend page space.
 *
 * Pinned tiles stay exactly where they are while [keepPinned] (the packer seeds their footprints
 * and paints them through their own placements), and pack like any other tile when it is off - the
 * pin itself survives either way, so the next repack honors it again.
 *
 * Runs on the UI thread; the decode + pack hop to the default dispatcher.  The commit applies to the
 * model CURRENT at completion, so non-mesh edits made while packing survive.  Two things abort as
 * superseded rather than applying a stale pack: the atlas itself changing (an undo across a repack,
 * say), and a mesh or coordinate edit that changes a tile's reach - the pack was spaced by the
 * reserves it started with, so it no longer proves the current meshes disjoint.
 *
 * @param AtlasRepackHost  host       The session, art, resolver, scope, and shell callbacks.
 * @param AtlasPackOptions options    The options to pack with (the strip's rows start from them).
 * @param String?          areaId     The area the command was dispatched over, or null - where the strip shows.
 * @param Boolean          keepPinned Whether pinned tiles stay where they are.
 */
suspend fun runAtlasRepack(
	host: AtlasRepackHost,
	options: AtlasPackOptions,
	areaId: String?,
	keepPinned: Boolean = true,
) {
	val session = host.session
	val modelAtStart = session.model.value
	val atlas = modelAtStart.atlas
	if (atlas.tiles.isEmpty() || !atlas.storedUvsAddressPages) {
		// The command's availability gates both; a race between the check and the dispatch just no-ops.
		return
	}

	// Only a BOUND failure refuses the repack; a placed-unbound tile that cannot come along packs out
	// instead, logged in the lowering.
	val (packInput, packResult) =
		withContext(Dispatchers.Default) {
			val input = buildRepackPackInput(modelAtStart) { tileId -> host.artRasters.decodeRaster(tileId) }
			input to packAtlas(input.itemsFor(keepPinned), options)
		}

	val refusals = repackRefusals(modelAtStart, packResult.skipped, packInput.undecodableTileIds)
	if (refusals.isNotEmpty()) {
		for (refusal in refusals) {
			UmamoLog.warn("repack: tile '${refusal.tileName}' refused (${refusal.reason}); nothing was applied")
		}
		host.report(AtlasRepackReport(refusals))
		return
	}
	val lowered = lowerPack(atlas, packResult)

	// Back on the UI thread, synchronously to the commit: a pack computed against one atlas value must
	// not apply over another (an undo across a repack landing mid-pack, say).
	val modelAtCommit = session.model.value
	if (modelAtCommit.atlas !== modelAtStart.atlas) {
		UmamoLog.warn("repack: the atlas changed while packing; nothing was applied")
		session.emitNotice("notice.atlas.repackSuperseded", NoticePlacement.NearCursor)
		return
	}
	// Same rule for the reserves: the pack is only proof of disjointness for the meshes it was spaced
	// by, so a mesh edit that moved a tile's reach while packing makes it stale.
	val reservesAtCommit = meshReserveByTile(modelAtCommit)
	if (reservesAtCommit != packInput.reserveByTile) {
		val changedTileIds = (reservesAtCommit.keys + packInput.reserveByTile.keys).filter { tileId -> reservesAtCommit[tileId] != packInput.reserveByTile[tileId] }
		UmamoLog.warn(
			"repack: a mesh edit changed ${changedTileIds.size} tile reach(es) while packing (${changedTileIds.take(3).joinToString { tileId -> tileId.raw }}); nothing was applied",
		)
		session.emitNotice("notice.atlas.repackSuperseded", NoticePlacement.NearCursor)
		return
	}
	val committed = session.commitAtlasRepack(lowered.pages, lowered.placementsByTile, atlasCompositionOf(options))
	if (committed == null) {
		UmamoLog.info("repack: the pack reproduced the current placements exactly; nothing to change")
		session.emitNotice("notice.atlas.repackUnchanged", NoticePlacement.NearCursor)
		return
	}
	// Pre-warm BEFORE the resolver's collector resumes (it is queued behind this dispatch), so the
	// commit resolves its pages by cache hit instead of composing the same pages a second time.
	host.sessionAtlasPages?.prewarm(committed.atlas, generatedPuppetTextures(packResult.pages, committed, host.premultipliedAlpha))
	host.rememberOptions(options, keepPinned)
	logPack(packResult, lowered, options)
	session.emitNotice("notice.atlas.repacked", NoticePlacement.NearCursor)

	// The decoded input is what an adjustment keeps: a re-pack costs the pack alone.  The record's
	// base is the model this pack ran on, so the reserves the input was spaced by are its reserves.
	session.registerAdjustableOperation(committed, areaId, repackParameters(options, keepPinned)) { record ->
		host.scope.launch { adjustAtlasRepack(host, record, packInput) }
	}
}

/**
 * Re-packs for an adjustment of the strip: the record's parameters become the options, the SAME
 * decoded [packInput] packs again off-thread, and the result lands over the repack's own step from
 * the record's base model.  A refusal reports like the first run's and leaves the previous result
 * standing; a record cleared while the pack ran (any other edit, an undo, a document swap) makes
 * [EditorSession.amendLastCommit] drop the result rather than commit over what the rigger did.
 *
 * @param AtlasRepackHost     host      The session, resolver, and shell callbacks the first run had.
 * @param AdjustableOperation record    The record with the adjusted parameters.
 * @param RepackPackInput     packInput The first run's decoded input, packed again as it is.
 */
internal suspend fun adjustAtlasRepack(
	host: AtlasRepackHost,
	record: AdjustableOperation,
	packInput: RepackPackInput,
) {
	val base = record.baseSnapshot.model
	val options = repackOptionsOf(record.parameters, fallback = AtlasPackOptions(maxPageSize = repackPageSizeOf(base)))
	val keepPinned = repackKeepPinnedOf(record.parameters)
	val packResult =
		try {
			withContext(Dispatchers.Default) { packAtlas(packInput.itemsFor(keepPinned), options) }
		} catch (exception: IllegalArgumentException) {
			// The rows keep every value inside the packer's contract, so this is a bug, not a rigger's
			// mistake - but a bug in an adjustment must not take the shell's scope down with it.
			UmamoLog.error("repack: the adjusted options were refused by the packer (${exception.message}); the previous result stands")
			return
		}
	val refusals = repackRefusals(base, packResult.skipped, packInput.undecodableTileIds)
	if (refusals.isNotEmpty()) {
		for (refusal in refusals) {
			UmamoLog.warn("repack: tile '${refusal.tileName}' refused under the adjusted options (${refusal.reason}); the previous result stands")
		}
		host.report(AtlasRepackReport(refusals))
		return
	}
	val lowered = lowerPack(base.atlas, packResult)
	val repacked = base.withAtlasRepack(lowered.pages, lowered.placementsByTile, atlasCompositionOf(options))
	host.sessionAtlasPages?.prewarm(repacked.atlas, generatedPuppetTextures(packResult.pages, repacked, host.premultipliedAlpha))
	if (!host.session.amendLastCommit(record, repacked)) {
		UmamoLog.info("repack: the adjustment was superseded before it landed; nothing was applied")
		return
	}
	host.rememberOptions(options, keepPinned)
	logPack(packResult, lowered, options)
}