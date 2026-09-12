package org.umamo.reimport

import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel

/*
 * What the document proposes for a binding its file no longer resolves.  The inventory keeps a lost
 * layer's row, flagged not present, for as long as a tile binds it - its name, bounds, and hash are
 * what the matcher scores candidates against and what the Sources space names the row by - and the
 * row leaves the inventory on the first refresh after nothing binds it.  A suggestion is only ever a
 * proposal: accepting one is the same relink a person makes by hand.
 */

/**
 * The inventory a refresh records: [fresh] as read, plus every [previous] row whose key the fresh art
 * lacks and a tile still binds, marked not present.  A previous row already not present stays so, and
 * a lost row nothing binds is dropped - there is no binding left to review.
 *
 * A plan that pulls art for some tiles and not others (a relink, an accepted match) names the bound
 * keys it left alone in [untouchedKeys]: their previous rows are kept over the fresh ones, so a change
 * to those layers the plan did not carry into their tiles is still seen by the next reload instead of
 * being recorded as already taken.
 *
 * @param List<ArtSourceLayer> previous      The inventory as the document held it.
 * @param List<ArtSourceLayer> fresh         The inventory of the art as just read.
 * @param Set<String>          boundKeys     The keys the tiles bound to this file carry after the plan.
 * @param Set<String>          untouchedKeys The bound keys whose tiles the plan did not update.
 * @return List<ArtSourceLayer> The rows to record, the fresh ones first in their order, then the kept lost ones in theirs.
 */
fun inventoryWithMissing(
	previous: List<ArtSourceLayer>,
	fresh: List<ArtSourceLayer>,
	boundKeys: Set<String>,
	untouchedKeys: Set<String> = emptySet(),
): List<ArtSourceLayer> {
	val previousByKey = previous.associateBy { row -> row.key }
	val freshKeys = fresh.mapTo(HashSet()) { row -> row.key }
	val refreshed =
		fresh.map { row ->
			val kept = if (row.key in untouchedKeys) previousByKey[row.key] else null
			if (kept != null && kept.present) kept else row
		}
	val lost =
		previous
			.filter { row -> row.key !in freshKeys && row.key in boundKeys }
			.map { row -> if (row.present) row.copy(present = false) else row }
	return refreshed + lost
}

/**
 * The row the matcher scores a lost binding by: the inventory's not-present row when it kept one, else
 * a row made from the tile itself - its name and size, at an unknown place - so a binding the
 * inventory never listed (a CMO3 walk's stray) still gets candidates.
 *
 * @param ArtSourceLayer? row  The inventory's row for the key, or null.
 * @param AtlasTile       tile The tile bound to it.
 * @return ArtSourceLayer The row to score with.
 */
private fun missingRowFor(row: ArtSourceLayer?, tile: AtlasTile): ArtSourceLayer =
	row ?: ArtSourceLayer(tile.source?.layerKey ?: tile.id.raw, tile.name, "", 0, 0, 0, 0, visible = true, present = false)

/**
 * The best candidate for every binding to [sourceId] its inventory does not list as present, keyed by
 * the lost layer's key.  The candidates are the file's present layers no tile is bound to.  Without
 * rasters the ranking rests on the inventory alone; with them the matcher compares pixels too.
 *
 * @param PuppetModel  model             The document's model.
 * @param ArtSourceId  sourceId          The file whose lost layers are scored.
 * @param Function     missingRasterOf   The document's pixels for a tile, or null when unread.
 * @param Function     candidateRasterOf The pixels of a present layer by key, or null when unread.
 * @param LayerMatcher matcher           The matcher to rank with.
 * @return Map<String, LayerMatch> Each lost binding's best match by its key; one with no candidate is absent.
 */
fun suggestionsFor(
	model: PuppetModel,
	sourceId: ArtSourceId,
	missingRasterOf: (AtlasTileId) -> LayerRaster? = { null },
	candidateRasterOf: (String) -> LayerRaster? = { null },
	matcher: LayerMatcher = InventoryLayerMatcher,
): Map<String, LayerMatch> {
	val source = model.sources.firstOrNull { candidate -> candidate.id == sourceId } ?: return emptyMap()
	val boundTiles = model.atlas.tiles.filter { tile -> tile.source?.sourceId == sourceId }
	val tileByKey = HashMap<String, AtlasTileId>()
	for (tile in boundTiles) {
		val key = tile.source?.layerKey ?: continue
		tileByKey.putIfAbsent(key, tile.id)
	}
	val candidates =
		source.layers
			.filter { row -> row.present && row.key !in tileByKey && row.width > 0 && row.height > 0 }
			.map { row -> MatchCandidate(row) { candidateRasterOf(row.key) } }
	if (candidates.isEmpty()) {
		return emptyMap()
	}
	val rowByKey = source.layers.associateBy { row -> row.key }
	val suggestions = LinkedHashMap<String, LayerMatch>()
	for (tile in boundTiles) {
		val key = tile.source?.layerKey ?: continue
		if (key in suggestions || tileByKey[key] != tile.id) {
			continue
		}
		val row = rowByKey[key]
		if (row != null && row.present) {
			continue
		}
		val best = matcher.rank(missingRowFor(row, tile), missingRasterOf(tile.id), candidates).firstOrNull() ?: continue
		suggestions[key] = best
	}
	return suggestions
}