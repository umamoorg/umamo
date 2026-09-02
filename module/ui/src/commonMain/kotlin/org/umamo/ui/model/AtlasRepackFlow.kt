package org.umamo.ui.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.commitAtlasRepack
import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.format.atlas.AtlasPackSkip
import org.umamo.format.atlas.AtlasPackSkipReason
import org.umamo.format.atlas.packAtlas
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.render.atlasPlacementFromPack
import org.umamo.render.derivedPackPolicy
import org.umamo.render.generatedPuppetTextures
import org.umamo.render.meshReserveByTile
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.storage.UmamoLog

/** The page side a repack packs against when the document has no pages to take one from. */
private const val DEFAULT_REPACK_PAGE_SIDE = 4096

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
 * @property List items              The tiles' pixels plus their mesh reserves.
 * @property Set  undecodableTileIds Tiles whose art would not decode or disagrees with its tile.
 * @property Map  reserveByTile      The mesh reserves the items were built with, kept so the commit
 *                                   can tell whether a mesh edit during the pack staled them.
 */
internal class RepackPackInput(
	val items: List<AtlasPackItem>,
	val undecodableTileIds: Set<AtlasTileId>,
	val reserveByTile: Map<AtlasTileId, AtlasPackReserve>,
)

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
		}
	}
	return RepackPackInput(items, undecodable, reserveByTile)
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
 * Repacks the document's atlas: decodes every bound tile's art, runs the shared deterministic packer
 * against the document's own page size, and commits the new pages + placements + re-derived
 * coordinates as ONE undo step, pre-warming the session's page resolver with the pages the pack
 * already composed so nothing composes twice.  The viewport and every UV editor follow the MODEL -
 * the resolver publishes the pages and the engine swaps them - so this function ends at the commit.
 *
 * All or nothing: any bound tile the pack cannot carry aborts the whole repack into [report] (the
 * modal report surface - a lost tile must not be a four-second toast).  Unbound tiles are packed
 * out (placement null) with a log line; art nobody samples does not spend page space.
 *
 * Runs on the UI thread; the decode + pack hop to the default dispatcher.  The commit applies to the
 * model CURRENT at completion, so non-mesh edits made while packing survive.  Two things abort as
 * superseded rather than applying a stale pack: the atlas itself changing (an undo across a repack,
 * say), and a mesh or coordinate edit that changes a tile's reach - the pack was spaced by the
 * reserves it started with, so it no longer proves the current meshes disjoint.
 *
 * @param EditorSession     session            The session to commit into.
 * @param SourceArtRasters  artRasters         The source-art pixels to pack.
 * @param SessionAtlasPages? sessionAtlasPages The session's page resolver, pre-warmed on success.
 * @param Boolean           premultipliedAlpha The document's texture-convention flag, carried onto the new set.
 * @param Function          report             Receives the abort report when the repack refuses.
 */
suspend fun runAtlasRepack(
	session: EditorSession,
	artRasters: SourceArtRasters,
	sessionAtlasPages: SessionAtlasPages?,
	premultipliedAlpha: Boolean,
	report: (AtlasRepackReport) -> Unit,
) {
	val modelAtStart = session.model.value
	val atlas = modelAtStart.atlas
	if (atlas.tiles.isEmpty() || !atlas.storedUvsAddressPages) {
		// The command's availability gates both; a race between the check and the dispatch just no-ops.
		return
	}
	val packSide = atlas.pages.maxOfOrNull { page -> maxOf(page.width, page.height) } ?: DEFAULT_REPACK_PAGE_SIDE
	val options = derivedPackPolicy.copy(maxPageSize = packSide)

	// Only a BOUND failure refuses the repack; a placed-unbound tile that cannot come along packs out
	// instead, logged below.
	val (packInput, packResult) =
		withContext(Dispatchers.Default) {
			val input = buildRepackPackInput(modelAtStart) { tileId -> artRasters.decodeRaster(tileId) }
			input to packAtlas(input.items, options)
		}

	val refusals = repackRefusals(modelAtStart, packResult.skipped, packInput.undecodableTileIds)
	if (refusals.isNotEmpty()) {
		for (refusal in refusals) {
			UmamoLog.warn("repack: tile '${refusal.tileName}' refused (${refusal.reason}); nothing was applied")
		}
		report(AtlasRepackReport(refusals))
		return
	}

	val newPages = packResult.pages.map { page -> AtlasPage(page.width, page.height) }
	val packedByKey = packResult.placements.associateBy { placement -> placement.key }
	val placementsByTile = HashMap<AtlasTileId, AtlasPlacement?>()
	var packedOut = 0
	for (tile in atlas.tiles) {
		val packed = packedByKey[tile.id.raw]
		placementsByTile[tile.id] = packed?.let { placement -> atlasPlacementFromPack(placement) }
		if (packed == null && tile.placement != null) {
			// Placed but unbound, and the pack could not carry it (empty, oversized, undecodable):
			// it leaves the pages rather than blocking the repack over art nothing samples.
			UmamoLog.warn("repack: unbound tile '${tile.name}' could not be packed and leaves the pages")
			packedOut++
		}
	}

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
	val committed = session.commitAtlasRepack(newPages, placementsByTile)
	if (committed == null) {
		UmamoLog.info("repack: the pack reproduced the current placements exactly; nothing to change")
		session.emitNotice("notice.atlas.repackUnchanged", NoticePlacement.NearCursor)
		return
	}
	// Pre-warm BEFORE the resolver's collector resumes (it is queued behind this dispatch), so the
	// commit resolves its pages by cache hit instead of composing the same pages a second time.
	sessionAtlasPages?.prewarm(committed.atlas, generatedPuppetTextures(packResult.pages, committed, premultipliedAlpha))
	val occupancy =
		packResult.pages.indices.joinToString(separator = ", ") { pageIndex ->
			"${(packResult.pageOccupancy(pageIndex) * 100f).toInt()}%"
		}
	UmamoLog.info(
		"repack: ${packResult.placements.size} tile(s) onto ${newPages.size} page(s) at $packSide px" +
			" (occupancy $occupancy); $packedOut unbound tile(s) packed out",
	)
	session.emitNotice("notice.atlas.repacked", NoticePlacement.NearCursor)
}