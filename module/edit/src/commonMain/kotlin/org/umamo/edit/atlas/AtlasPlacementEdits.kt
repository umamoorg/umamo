package org.umamo.edit.atlas

import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile

/*
 * Atlas placement transforms over the immutable PuppetModel: a tile's placement on its page, the pins that
 * hold a placement against a repack, and the repack itself, which rewrites the pages and remaps every
 * drawable's texture coordinates onto them.  Pure and copy-on-write; the EditorSession wrappers live in
 * SessionAtlasEdits.
 */

/**
 * This model with one atlas tile packed at [placement], every drawable over that art re-mapped so it
 * keeps sampling the same pixels.
 *
 * Moving art around a page must not change what the art means.  Stored texture coordinates address the
 * PAGE, so a placement that moves without them moving with it silently re-points every mesh at whatever
 * now occupies the old spot; that is why this rewrites both together, and why a repack is an edit on
 * the tile rather than on each drawable.  The re-mapping is the composition of the old mapping into the
 * art's own frame with the inverse of the new one, so the vertex-to-art-pixel binding comes out
 * bit-identical wherever the affines are exact.
 *
 * The mappings are built from the placements directly: stored coordinates mean the same thing whichever
 * surface is being displayed, so a document whose coordinates address the packed pages re-maps here and
 * one whose coordinates already address the art does not.
 *
 * PIXELS ARE NOT MOVED.  A page's bytes belong to the document, not the model, so a caller that moves a
 * placement must recompose the page for the result to render; the session's page resolver does that
 * from the committed model, which is what lets the placement gizmo commit here and let the pixels
 * follow.
 *
 * A no-op returns the same instance.  So does an edit that cannot be expressed: an unknown tile, a
 * placement naming a page the document does not have, or a mapping that will not invert (a zero scale,
 * a zero-sized tile).  Refusing beats writing a placement whose coordinates cannot follow it.
 *
 * @param AtlasTileId     tileId    The tile to place.
 * @param AtlasPlacement? placement Where its art now sits, or null to mark it unpacked.
 * @return PuppetModel The model with the placement and the re-derived coordinates, or [this].
 */
fun PuppetModel.withAtlasPlacement(tileId: AtlasTileId, placement: AtlasPlacement?): PuppetModel =
	withAtlasPlacements(mapOf(tileId to placement))

/**
 * This model with several tiles placed anew in one pass - [withAtlasPlacement] over a whole gesture's
 * worth of tiles, every drawable over each moved tile re-mapped so it keeps sampling the same pixels.
 *
 * One pass rather than a fold of single-tile edits so the result is one instance for one history
 * push, and so the whole edit is refused together: an unknown tile, a placement naming a page the
 * document does not have, or a mapping that will not invert anywhere in the map returns [this]
 * untouched rather than moving the tiles that happened to precede the fault.  The page inventory
 * never changes here - that is the repack's op, [withAtlasRepack].
 *
 * @param Map placementByTile Each tile's new placement, keyed by tile, null to mark it unpacked.
 * @return PuppetModel The model with the placements and the re-derived coordinates, or [this] when
 *   nothing changes or the edit cannot be expressed.
 */
fun PuppetModel.withAtlasPlacements(placementByTile: Map<AtlasTileId, AtlasPlacement?>): PuppetModel {
	if (placementByTile.isEmpty()) {
		return this
	}
	val movedTiles = atlas.tiles.toMutableList()
	val changedTileIds = ArrayList<AtlasTileId>()
	for ((tileId, placement) in placementByTile) {
		val tileIndex = movedTiles.indexOfFirst { tile -> tile.id == tileId }
		if (tileIndex < 0) {
			return this
		}
		if (placement != null && placement.pageIndex !in atlas.pages.indices) {
			return this
		}
		val tile = movedTiles[tileIndex]
		if (tile.placement == placement) {
			continue
		}
		// An unpacked tile has no placement to keep, so packing one out drops its pin with it.
		movedTiles[tileIndex] = tile.copy(placement = placement, pinned = tile.pinned && placement != null)
		changedTileIds.add(tileId)
	}
	if (changedTileIds.isEmpty()) {
		return this
	}
	val newAtlas = atlas.copy(tiles = movedTiles)
	val remapByTile = HashMap<AtlasTileId, AtlasTileRemap>()
	for (tileId in changedTileIds) {
		when (val outcome = tileRemap(atlas, newAtlas, tileId)) {
			TileRemapOutcome.Unchanged -> Unit
			TileRemapOutcome.Inexpressible -> return this
			is TileRemapOutcome.Remap -> remapByTile[tileId] = outcome.remap
		}
	}
	return copy(atlas = newAtlas, drawables = drawables.remappedOver(remapByTile))
}

/**
 * This model with every tile in [tileIds] that has a placement pinned or unpinned - the flag a
 * repack reads to keep a hand-placed tile where it is.
 *
 * A tile without a placement is skipped rather than refused: there is nothing on a page to keep, and
 * a selection spanning packed and unpacked art should still pin what it can.  An unknown tile is
 * skipped the same way.  No coordinates move - the flag says only what a LATER repack may do.
 *
 * @param Collection<AtlasTileId> tileIds The tiles to pin or unpin.
 * @param Boolean                 pinned  True to pin, false to unpin.
 * @return PuppetModel The model with the flags set, or [this] when nothing changes.
 */
fun PuppetModel.withAtlasPins(tileIds: Collection<AtlasTileId>, pinned: Boolean): PuppetModel {
	if (tileIds.isEmpty()) {
		return this
	}
	val wanted = tileIds.toSet()
	var changed = false
	val tiles =
		atlas.tiles.map { tile ->
			if (tile.id in wanted && tile.placement != null && tile.pinned != pinned) {
				changed = true
				tile.copy(pinned = pinned)
			} else {
				tile
			}
		}
	if (!changed) {
		return this
	}
	return copy(atlas = atlas.copy(tiles = tiles))
}

/**
 * One tile's coordinate re-derivation across a placement change: out of the old mapping's frame, into
 * the new.
 *
 * @property FloatArray storedToArt The old stored-to-art mapping.
 * @property FloatArray artToStored The inverse of the new one.
 */
private class AtlasTileRemap(
	val storedToArt: FloatArray,
	val artToStored: FloatArray,
)

/** What a tile's placement change means for the texture coordinates over it. */
private sealed interface TileRemapOutcome {
	/** The mapping is the same under both atlases, so the coordinates are left alone. */
	data object Unchanged : TileRemapOutcome

	/** One side has no mapping: a page the atlas lacks, a degenerate placement, or a zero-sized tile. */
	data object Inexpressible : TileRemapOutcome

	/**
	 * The coordinates move through [remap].
	 *
	 * @property AtlasTileRemap remap The re-derivation to apply.
	 */
	class Remap(val remap: AtlasTileRemap) : TileRemapOutcome
}

/**
 * How [tileId]'s texture coordinates move between [oldAtlas] and [newAtlas] - the one resolution both
 * the per-tile edit and the whole-atlas repack read.
 *
 * Each side reads its mapping against ITS OWN page inventory: the page a placement names supplies the
 * mapping's normalization, so the two reads must not share pages.  That is also why the repack is a
 * distinct op from the per-tile edit - only it can change the inventory the new side reads.
 *
 * @param PuppetAtlas oldAtlas The atlas the coordinates currently address.
 * @param PuppetAtlas newAtlas The atlas they will address, with the tile already moved.
 * @param AtlasTileId tileId   The tile whose mapping changed.
 * @return TileRemapOutcome Unchanged, inexpressible, or the remap to apply.
 */
private fun tileRemap(oldAtlas: PuppetAtlas, newAtlas: PuppetAtlas, tileId: AtlasTileId): TileRemapOutcome {
	val oldAffine = oldAtlas.storedToArtAffineForTile(tileId)
	val newAffine = newAtlas.storedToArtAffineForTile(tileId)
	if (oldAffine != null && newAffine != null && oldAffine.contentEquals(newAffine)) {
		return TileRemapOutcome.Unchanged
	}
	val artToStored = newAffine?.let { affine -> invertUvAffine(affine) }
	if (oldAffine == null || artToStored == null) {
		return TileRemapOutcome.Inexpressible
	}
	return TileRemapOutcome.Remap(AtlasTileRemap(oldAffine, artToStored))
}

/**
 * These drawables with every one over a tile in [remapByTile] carried through that tile's remap.  Every
 * other drawable, and every mesh's positions, passes through by reference: moving art on a page never
 * moves the mesh, and an untouched coordinate array must not pick up float round-trip noise.
 *
 * @param Map remapByTile The re-derivation per moved tile.
 * @return List<Drawable> The drawables, the same list when nothing moved.
 */
private fun List<Drawable>.remappedOver(remapByTile: Map<AtlasTileId, AtlasTileRemap>): List<Drawable> {
	if (remapByTile.isEmpty()) {
		return this
	}
	return map { drawable ->
		val remap = drawable.atlasTileId?.let { tileId -> remapByTile[tileId] } ?: return@map drawable
		val mesh = drawable.mesh ?: return@map drawable
		val artUvs = applyUvAffine(mesh.uvs, remap.storedToArt)
		drawable.copy(mesh = mesh.withUvs(applyUvAffine(artUvs, remap.artToStored)))
	}
}

/**
 * This model repacked: the page inventory replaced by [pages], every tile's placement restated from
 * [placementsByTile], and every bound drawable's texture coordinates re-derived, in one pass.
 *
 * The whole-atlas twin of [withAtlasPlacement], and a distinct op because the per-tile edit cannot
 * change the page list: a tile's old mapping normalizes against the CURRENT pages and its new one
 * against the REPLACEMENT pages, so folding per-tile edits would read the new mapping off a stale
 * inventory.  One call is also one history push - a repack is one gesture, not one step per tile.
 *
 * Input is validated up front instead of refused tile-by-tile: the placement map must restate every
 * tile (an explicit null marks it unpacked), every placement must name one of [pages], and a tile
 * with drawables bound must carry an expressible re-derivation.  The orchestrating repack guarantees
 * all three - it aborts before mutating when a bound tile cannot come along - so a violation here is
 * a caller bug, not document state to absorb.  A tile with NO drawables bound may have an
 * inexpressible mapping (a degenerate imported placement); its placement still installs, and there
 * are no coordinates to move.
 *
 * A tile whose mapping comes out unchanged is left alone entirely, so an untouched drawable's
 * coordinate arrays pass through by reference rather than picking up float round-trip noise.
 *
 * PIXELS ARE NOT MOVED - [withAtlasPlacement]'s rule, unchanged: the caller must compose the new
 * pages for the result to render.
 *
 * @param List             pages            The new page inventory.
 * @param Map              placementsByTile Every tile's new placement, keyed by tile, null for unpacked.
 * @param AtlasComposition composition      The trim and extrusion policy the pack composed under, recorded
 *   so the pages derive the same way; defaults to the atlas's current policy.
 * @return PuppetModel The repacked model, or [this] when nothing changes.
 */
fun PuppetModel.withAtlasRepack(
	pages: List<AtlasPage>,
	placementsByTile: Map<AtlasTileId, AtlasPlacement?>,
	composition: AtlasComposition = atlas.composition,
): PuppetModel {
	require(placementsByTile.keys == atlas.tiles.map { tile -> tile.id }.toSet()) {
		"a repack must restate every tile's placement exactly once"
	}
	for ((tileId, placement) in placementsByTile) {
		if (placement != null) {
			require(placement.pageIndex in pages.indices) {
				"tile '${tileId.raw}' names page ${placement.pageIndex} of ${pages.size}"
			}
		}
	}
	val movedTiles = atlas.tiles.map { tile -> tile.copy(placement = placementsByTile.getValue(tile.id)) }
	val newAtlas = atlas.copy(pages = pages, tiles = movedTiles, composition = composition)
	if (newAtlas == atlas) {
		return this
	}

	val boundTileIds = drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
	val remapByTile = HashMap<AtlasTileId, AtlasTileRemap>()
	for (tile in atlas.tiles) {
		when (val outcome = tileRemap(atlas, newAtlas, tile.id)) {
			TileRemapOutcome.Unchanged -> Unit
			TileRemapOutcome.Inexpressible ->
				require(tile.id !in boundTileIds) {
					"tile '${tile.id.raw}' has drawables bound but no expressible re-derivation"
				}
			is TileRemapOutcome.Remap -> remapByTile[tile.id] = outcome.remap
		}
	}
	return copy(atlas = newAtlas, drawables = drawables.remappedOver(remapByTile))
}