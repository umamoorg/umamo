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
 * A row that reads as erased keeps the canvas frame its art last had: an art program saves an erased
 * layer with a collapsed rectangle, and the tile still holds the art from the frame before, so a
 * reload that finds the art back (an undo in the art program) measures it against that frame rather
 * than against the collapse - which would carry every coordinate by the bogus difference.
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
			val previousRow = previousByKey[row.key]
			when {
				row.key in untouchedKeys && previousRow != null && previousRow.present -> previousRow
				// The previous row already holds the last frame with art when it was itself erased.
				row.empty && previousRow != null ->
					row.copy(left = previousRow.left, top = previousRow.top, width = previousRow.width, height = previousRow.height)
				else -> row
			}
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
 * The best candidate for every binding to [sourceId] its inventory does not list as present - or lists
 * as erased to nothing - keyed by the lost layer's key.  The candidates are the file's present layers
 * with art that no tile is bound to - never a bound one, since on the model alone nothing tells a
 * re-created layer's fresh drawable from any other untouched drawable; the proposal that names a
 * bound layer comes only from a reload that scored the binding before it minted the layer.  Without
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
			.filter { row -> row.present && !row.empty && row.key !in tileByKey && row.width > 0 && row.height > 0 }
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
		if (row != null && row.present && !row.empty) {
			continue
		}
		val best = matcher.rank(missingRowFor(row, tile), missingRasterOf(tile.id), candidates).firstOrNull() ?: continue
		suggestions[key] = best
	}
	return suggestions
}

/**
 * The suggestions for [sourceId]'s unresolved bindings against its art as just read: the record's
 * inventory refreshed with [inventory] first - the lost rows kept, and every bound layer's previous row
 * kept too, since nothing here carries a change into a tile - so a file never reloaded still scores,
 * then [suggestionsFor] over that.  What the reload planner scores before it mints, and what an
 * operation that read the file publishes for the review chips.
 *
 * @param PuppetModel          model             The model as it stands.
 * @param ArtSourceId          sourceId          The file.
 * @param List<ArtSourceLayer> inventory         The inventory of the file as just read.
 * @param Function             missingRasterOf   The document's pixels for a tile, or null when unread.
 * @param Function             candidateRasterOf The pixels of a present layer by key, or null when unread.
 * @param LayerMatcher         matcher           The matcher to rank with.
 * @return Map<String, LayerMatch> Each unresolved binding's best match by its key; one with no candidate is absent.
 */
fun suggestionsAgainstRead(
	model: PuppetModel,
	sourceId: ArtSourceId,
	inventory: List<ArtSourceLayer>,
	missingRasterOf: (AtlasTileId) -> LayerRaster? = { null },
	candidateRasterOf: (String) -> LayerRaster? = { null },
	matcher: LayerMatcher = InventoryLayerMatcher,
): Map<String, LayerMatch> {
	val source = model.sources.firstOrNull { candidate -> candidate.id == sourceId } ?: return emptyMap()
	val boundKeys = model.atlas.tiles.filter { tile -> tile.source?.sourceId == sourceId }.mapNotNullTo(HashSet()) { tile -> tile.source?.layerKey }
	val refreshed = source.copy(layers = inventoryWithMissing(source.layers, inventory, boundKeys, untouchedKeys = boundKeys))
	val scoringModel = model.copy(sources = model.sources.map { candidate -> if (candidate.id == sourceId) refreshed else candidate })
	return suggestionsFor(scoringModel, sourceId, missingRasterOf, candidateRasterOf, matcher)
}

/**
 * The matches to apply out of [suggestions]: those at or above [threshold], best first, each candidate
 * taken once - two lost layers that both prefer one candidate are settled in favor of the more
 * confident, the other left for a person (its suggestion stands).
 *
 * @param Map<String, LayerMatch> suggestions The best candidate per lost key.
 * @param Float                   threshold   The confidence bar, 0..1, inclusive.
 * @return Map<String, String> The candidate key each accepted lost key moves to, most confident first.
 */
fun confidentMatches(suggestions: Map<String, LayerMatch>, threshold: Float): Map<String, String> {
	val takenCandidates = HashSet<String>()
	val accepted = LinkedHashMap<String, String>()
	for ((lostKey, match) in suggestions.entries.sortedByDescending { (_, match) -> match.score }) {
		if (match.score < threshold || !takenCandidates.add(match.key)) {
			continue
		}
		accepted[lostKey] = match.key
	}
	return accepted
}

/**
 * The tiles a rebinding onto [candidateKey] may retire: every tile of [sourceId] bound to that key
 * other than [except] whose drawables carry no rig work - the fresh drawable a reload minted for the
 * layer before the lost layer's rig work claimed it.  A tile with rig work over it stays bound; two
 * tiles then share the layer, which a person sorts out.  What the Sources space shows against a
 * proposal naming a bound layer, and what a match re-checks before it retires anything.
 *
 * @param PuppetModel      model        The model the rebinding applies to.
 * @param ArtSourceId      sourceId     The file.
 * @param String           candidateKey The layer being claimed.
 * @param Set<AtlasTileId> except       The tiles doing the claiming.
 * @return List<AtlasTileId> The tiles to retire, in atlas order.
 */
fun retirableTiles(model: PuppetModel, sourceId: ArtSourceId, candidateKey: String, except: Set<AtlasTileId>): List<AtlasTileId> {
	val row = model.sources.firstOrNull { source -> source.id == sourceId }?.layers?.firstOrNull { layer -> layer.key == candidateKey }
	return model.atlas.tiles
		.filter { tile -> tile.id !in except && tile.source?.sourceId == sourceId && tile.source?.layerKey == candidateKey }
		.filter { tile -> model.tileCarriesNoRigWork(tile.id, row) }
		.map { tile -> tile.id }
}