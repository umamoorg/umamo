package org.umamo.edit.atlas

import org.umamo.edit.structure.removingDrawables
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtworkAdditions
import org.umamo.runtime.model.ArtworkReload
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.OrgInsertion
import org.umamo.runtime.model.OrgSlot
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Source-artwork transforms over the immutable PuppetModel: binding a tile to a source layer, adding an
 * artwork file's layers, reloading a changed file, deleting a tile, and ignoring a layer.  Pure and
 * copy-on-write; the EditorSession wrappers live in SessionArtworkEdits.
 */

/**
 * This model with one tile's source binding replaced - rebound to another layer of a listed artwork
 * file, or unbound when [source] is null.
 *
 * Refused (returns [this]) for an unknown tile or a ref naming a source the document does not list:
 * a binding that points at nothing would be worse than no binding.  Nothing else moves - the
 * binding says which layer a re-import reads for this art, never where the art sits.
 *
 * @param AtlasTileId     tileId The tile to rebind.
 * @param SourceLayerRef? source The new binding, or null to unbind.
 * @return PuppetModel The rebound model, or [this] when refused or unchanged.
 */
fun PuppetModel.withTileSource(tileId: AtlasTileId, source: SourceLayerRef?): PuppetModel {
	val tileIndex = atlas.tiles.indexOfFirst { tile -> tile.id == tileId }
	if (tileIndex < 0) {
		return this
	}
	if (source != null && sources.none { candidate -> candidate.id == source.sourceId }) {
		return this
	}
	val tile = atlas.tiles[tileIndex]
	if (tile.source == source) {
		return this
	}
	val tiles = atlas.tiles.toMutableList()
	tiles[tileIndex] = tile.copy(source = source)
	return copy(atlas = atlas.copy(tiles = tiles))
}

/**
 * This model with an artwork file's additions appended: the source record, its tiles (unplaced), its
 * drawables and parts, and the file's top-level order after the existing root children, with the
 * render root re-derived.  The pack that places the new tiles is a separate step over the result,
 * exactly as it is for a fresh import.
 *
 * Refused (returns [this]) when any added id collides with one the model already has - the bridge
 * mints past the existing ids, so a collision is a caller bug rather than document state to absorb.
 *
 * @param ArtworkAdditions additions The delta to append.
 * @return PuppetModel The model with the artwork added, or [this] when refused.
 */
fun PuppetModel.withArtworkAdded(additions: ArtworkAdditions): PuppetModel {
	if (sources.any { source -> source.id == additions.source.id }) {
		return this
	}
	val existingTileIds = atlas.tiles.mapTo(HashSet()) { tile -> tile.id }
	val existingDrawableIds = drawables.mapTo(HashSet()) { drawable -> drawable.id }
	val existingPartIds = parts.mapTo(HashSet()) { part -> part.id }
	if (additions.tiles.any { tile -> tile.id in existingTileIds } ||
		additions.drawables.any { drawable -> drawable.id in existingDrawableIds } ||
		additions.parts.any { part -> part.id in existingPartIds } ||
		additions.insertions.any { insertion -> insertion.container != null && insertion.container !in existingPartIds }
	) {
		return this
	}
	val placed = placeInsertions(parts, rootChildren, additions.insertions)
	return copy(
		parts = placed.parts + additions.parts,
		drawables = drawables + additions.drawables,
		rootChildren = placed.rootChildren + additions.rootChildren,
		atlas = atlas.copy(tiles = atlas.tiles + additions.tiles),
		sources = sources + additions.source,
	).withDerivedRenderRoot()
}

/**
 * This model without the tile [tileId]: the tile and its placement gone from the atlas, its drawables
 * untouched because there are none - a tile some drawable samples is refused, since removing it would
 * strand the drawable's coordinates.  The pages are derived state, so the page the tile sat on simply
 * composes without it.  Refused (returns [this]) for a tile the model lacks.
 *
 * @param AtlasTileId tileId The tile to remove.
 * @return PuppetModel The model without the tile, or [this] when refused.
 */
fun PuppetModel.withTileDeleted(tileId: AtlasTileId): PuppetModel {
	if (atlas.tileById[tileId] == null || drawables.any { drawable -> drawable.atlasTileId == tileId }) {
		return this
	}
	return copy(atlas = atlas.copy(tiles = atlas.tiles.filter { tile -> tile.id != tileId }))
}

/**
 * This model with one inventory row's ignore mark set: a layer of a listed artwork file that a reload
 * leaves out of the rig while [ignored] is true.  The mark means something only for a layer no tile
 * binds - a bound layer is matched by key, never minted - so a key some tile binds is refused, as is an
 * unlisted file or key; a mark already as asked returns [this].
 *
 * @param ArtSourceId sourceId The file.
 * @param String      key      The layer's key within it.
 * @param Boolean     ignored  True to keep the layer out of the rig, false to let a reload mint it again.
 * @return PuppetModel The marked model, or [this] when refused or unchanged.
 */
fun PuppetModel.withLayerIgnored(sourceId: ArtSourceId, key: String, ignored: Boolean): PuppetModel {
	val sourceIndex = sources.indexOfFirst { source -> source.id == sourceId }
	if (sourceIndex < 0) {
		return this
	}
	if (atlas.tiles.any { tile -> tile.source?.sourceId == sourceId && tile.source?.layerKey == key }) {
		return this
	}
	val source = sources[sourceIndex]
	val rowIndex = source.layers.indexOfFirst { row -> row.key == key }
	if (rowIndex < 0 || source.layers[rowIndex].ignored == ignored) {
		return this
	}
	val rows = source.layers.toMutableList()
	rows[rowIndex] = rows[rowIndex].copy(ignored = ignored)
	val updated = sources.toMutableList()
	updated[sourceIndex] = source.copy(layers = rows)
	return copy(sources = updated)
}

/**
 * The org tree after a delta's insertions: the parts and the root children.
 *
 * @property List<Part>     parts        The parts, the ones that took children rebuilt.
 * @property List<OrgChild> rootChildren The root's children.
 */
private class PlacedOrgTree(
	val parts: List<Part>,
	val rootChildren: List<OrgChild>,
)

/**
 * [insertions] applied in order to [parts] and [rootChildren]: each child goes directly after or before
 * its anchor among the container's children as they stand at that moment (an earlier insertion
 * included), or at the end when the slot says so or the anchor is not there any more.
 *
 * @param List<Part>         parts        The parts as they stand.
 * @param List<OrgChild>     rootChildren The root's children as they stand.
 * @param List<OrgInsertion> insertions   The children to place, in order.
 * @return PlacedOrgTree The placed tree; the same lists when there is nothing to place.
 */
private fun placeInsertions(parts: List<Part>, rootChildren: List<OrgChild>, insertions: List<OrgInsertion>): PlacedOrgTree {
	if (insertions.isEmpty()) {
		return PlacedOrgTree(parts, rootChildren)
	}
	var root = rootChildren
	val childrenByPart = LinkedHashMap<PartId, List<OrgChild>>()
	for (part in parts) {
		childrenByPart[part.id] = part.children
	}
	for (insertion in insertions) {
		val container = insertion.container
		if (container == null) {
			root = root.withInserted(insertion.child, insertion.slot)
		} else {
			childrenByPart[container] = childrenByPart.getValue(container).withInserted(insertion.child, insertion.slot)
		}
	}
	val placedParts =
		parts.map { part ->
			val children = childrenByPart.getValue(part.id)
			if (children === part.children) part else part.copy(children = children)
		}
	return PlacedOrgTree(placedParts, root)
}

/**
 * This list with [child] at [slot]: after or before the anchor when the list holds it, else at the end.
 *
 * @param OrgChild child The child to place.
 * @param OrgSlot  slot  Where it goes.
 * @return List<OrgChild> The grown list.
 */
private fun List<OrgChild>.withInserted(child: OrgChild, slot: OrgSlot): List<OrgChild> {
	val index =
		when (slot) {
			is OrgSlot.After -> indexOf(slot.anchor).let { anchorIndex -> if (anchorIndex < 0) size else anchorIndex + 1 }
			is OrgSlot.Before -> indexOf(slot.anchor).let { anchorIndex -> if (anchorIndex < 0) size else anchorIndex }
			OrgSlot.End -> size
		}
	val grown = ArrayList<OrgChild>(size + 1)
	grown.addAll(this)
	grown.add(index, child)
	return grown
}

/**
 * This model with one artwork file re-read into it: the file's record replaced by [reload]'s (the
 * inventory as just read), every superseded tile removed and its replacement appended unplaced, the
 * drawables over a superseded tile moved onto its replacement with the meshes the plan decided, the
 * drawables the file's eye toggle reached given the visibility the plan decided, and the layers the
 * file gained appended under the same file (tiles, drawables, new parts, and each new
 * child placed among the existing children where the file puts it, like [withArtworkAdded]).  The
 * render root is re-derived, so a layer added at the top of the file draws in front.  The pack that places the new tiles is a
 * separate step over the result, exactly as for an added file: an unplaced tile's coordinates address
 * its own art, so the repack's re-derivation converts them.
 *
 * Nothing is deleted: a layer the file lost keeps its tile, its pixels, and its binding - the
 * refreshed inventory carries its row flagged not present, which is what the Sources space shows as
 * needing review.
 *
 * Refused (returns [this]) when the model does not list the file, a superseded or retired tile is
 * unknown, a retired tile is also superseded, or any new id collides with one the model has - the
 * planner mints past the model, so a collision is a caller bug rather than document state to absorb.
 *
 * @param ArtworkReload reload The delta to apply.
 * @return PuppetModel The reloaded model, or [this] when refused.
 */
fun PuppetModel.withArtworkReloaded(reload: ArtworkReload): PuppetModel {
	if (sources.none { source -> source.id == reload.source.id }) {
		return this
	}
	val existingTileIds = atlas.tiles.mapTo(HashSet()) { tile -> tile.id }
	val replacedIds = reload.replacedTiles.mapTo(HashSet()) { replaced -> replaced.oldId }
	if (replacedIds.size != reload.replacedTiles.size || replacedIds.any { oldId -> oldId !in existingTileIds }) {
		return this
	}
	val retiredIds = reload.retiredTiles.toSet()
	if (retiredIds.any { tileId -> tileId !in existingTileIds || tileId in replacedIds }) {
		return this
	}
	val additions = reload.additions
	val newTiles = reload.replacedTiles.map { replaced -> replaced.tile } + additions?.tiles.orEmpty()
	val existingDrawableIds = drawables.mapTo(HashSet()) { drawable -> drawable.id }
	val existingPartIds = parts.mapTo(HashSet()) { part -> part.id }
	if (newTiles.any { tile -> tile.id in existingTileIds } ||
		newTiles.mapTo(HashSet()) { tile -> tile.id }.size != newTiles.size ||
		additions?.drawables.orEmpty().any { drawable -> drawable.id in existingDrawableIds } ||
		additions?.parts.orEmpty().any { part -> part.id in existingPartIds } ||
		additions?.insertions.orEmpty().any { insertion -> insertion.container != null && insertion.container !in existingPartIds }
	) {
		return this
	}
	val newTileByOldId = reload.replacedTiles.associate { replaced -> replaced.oldId to replaced.tile.id }
	val movedDrawables =
		drawables.map { drawable ->
			val newTileId = drawable.atlasTileId?.let { tileId -> newTileByOldId[tileId] }
			val mesh = reload.drawableMeshes[drawable.id]
			val visible = reload.drawableVisibility[drawable.id]
			if (newTileId == null && mesh == null && visible == null) {
				drawable
			} else {
				drawable.copy(atlasTileId = newTileId ?: drawable.atlasTileId, mesh = mesh ?: drawable.mesh, isVisible = visible ?: drawable.isVisible)
			}
		}
	val keptTiles = atlas.tiles.filter { tile -> tile.id !in replacedIds && tile.id !in retiredIds }
	val placed = placeInsertions(parts, rootChildren, additions?.insertions.orEmpty())
	val reloaded =
		copy(
			parts = placed.parts + additions?.parts.orEmpty(),
			drawables = movedDrawables + additions?.drawables.orEmpty(),
			rootChildren = placed.rootChildren + additions?.rootChildren.orEmpty(),
			atlas = atlas.copy(tiles = keptTiles + newTiles),
			sources = sources.map { source -> if (source.id == reload.source.id) reload.source else source },
		)
	// A retired tile's drawables leave with it, every reference scrubbed the way a delete scrubs them.
	val retiredDrawables = reloaded.drawables.filter { drawable -> drawable.atlasTileId in retiredIds }.mapTo(HashSet()) { drawable -> drawable.id }
	return reloaded.removingDrawables(retiredDrawables).withDerivedRenderRoot()
}