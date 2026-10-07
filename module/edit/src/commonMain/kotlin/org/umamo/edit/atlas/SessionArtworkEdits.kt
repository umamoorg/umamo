package org.umamo.edit.atlas

import org.umamo.edit.DocumentChange
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef

/*
 * Source-artwork edits on an EditorSession, each one undo step: the Sources space's rebind, delete, and
 * ignore actions, and the commits the artwork flows land (add, reload, relink, match, replace) once they
 * have computed the new model.
 */

/**
 * Rebinds one tile to a layer of a listed artwork file, or unbinds it, as one undo step -
 * withTileSource under the one history push a relink should be.
 *
 * @param AtlasTileId     tileId The tile to rebind.
 * @param SourceLayerRef? source The new binding, or null to unbind.
 */
fun EditorSession.setTileSource(tileId: AtlasTileId, source: SourceLayerRef?) {
	setTileSources(listOf(tileId), source)
}

/**
 * Rebinds every tile in [tileIds] to one layer of a listed artwork file, or unbinds them all, as ONE
 * undo step - what a review row's accepted proposal lands through when the layer's art cannot be
 * pulled, so the tiles bound to a lost key move together.  A tile the model refuses (unknown, or a
 * binding to an unlisted file) is left as it is; a call that changes no tile pushes nothing.
 *
 * @param Collection<AtlasTileId> tileIds The tiles to rebind, in any order; empty pushes nothing.
 * @param SourceLayerRef?         source  The new binding, or null to unbind.
 */
fun EditorSession.setTileSources(tileIds: Collection<AtlasTileId>, source: SourceLayerRef?) {
	val first = tileIds.firstOrNull() ?: return
	mutate(DocumentChange.SetTileSource(first, bound = source != null)) { model ->
		tileIds.fold(model) { current, tileId -> current.withTileSource(tileId, source) }
	}
}

/**
 * Removes the tile [tileId] from the atlas as one undo step, when no drawable samples it; a tile some
 * drawable still samples, or one the model lacks, pushes nothing.  With [ignoreLayer] the layer the tile
 * was bound to is marked ignored in the same step (the import setting's choice), so the next reload
 * does not mint the art back; an unbound tile has no layer to mark, and a layer another tile still binds
 * is left unmarked, since the reload matches it by key.
 *
 * @param AtlasTileId tileId      The tile to remove.
 * @param Boolean     ignoreLayer Whether to keep the tile's layer out of the rig from now on.
 */
fun EditorSession.deleteTile(tileId: AtlasTileId, ignoreLayer: Boolean = false) {
	val current = model.value
	val binding = current.atlas.tileById[tileId]?.source
	val shared =
		binding != null &&
			current.atlas.tiles.any { other -> other.id != tileId && other.source?.sourceId == binding.sourceId && other.source?.layerKey == binding.layerKey }
	val marks = ignoreLayer && binding != null && !shared
	mutate(DocumentChange.DeleteTile(tileId, ignoredLayer = marks)) { before ->
		val deleted = before.withTileDeleted(tileId)
		// marks already carries binding != null, and the compiler smart-casts through it.
		if (marks && deleted !== before) {
			deleted.withLayerIgnored(binding.sourceId, binding.layerKey, ignored = true)
		} else {
			deleted
		}
	}
}

/**
 * Marks or clears the ignore on one inventory row as one undo step - withLayerIgnored under the one
 * history push the Sources row's toggle should be; a row the model refuses (unknown, or a key some tile
 * binds) pushes nothing.
 *
 * @param SourceLayerRef ref     The file and layer key.
 * @param Boolean        ignored True to keep the layer out of the rig, false to let a reload mint it again.
 */
fun EditorSession.setLayerIgnored(ref: SourceLayerRef, ignored: Boolean) {
	mutate(DocumentChange.SetLayerIgnored(ref.sourceId, ref.layerKey, ignored)) { before -> before.withLayerIgnored(ref.sourceId, ref.layerKey, ignored) }
}

/**
 * Commits a model that already carries an artwork file's additions and their pack as ONE undo step.
 *
 * The orchestrator builds [added] off the UI thread (the bridge, the pack around the existing art,
 * the re-derivation) from the model current when it started, checks nothing moved underneath it, and
 * lands it here; the page pixels are session state it swaps in beside this commit, which is why the
 * committed model comes back for the resolver's pre-warm.
 *
 * @param String      sourceName    The added file's display name, for the history label.
 * @param Int         drawableCount How many drawables it added.
 * @param PuppetModel added         The model with the additions and their pack applied.
 * @return PuppetModel The committed model.
 */
fun EditorSession.commitArtworkAdded(sourceName: String, drawableCount: Int, added: PuppetModel): PuppetModel {
	mutate(DocumentChange.AddArtwork(sourceName, drawableCount)) { added }
	return added
}

/**
 * Commits a model that already carries a reload of the document's artwork files and its pack as ONE
 * undo step - the same contract as [commitArtworkAdded]: built off the UI thread from the model
 * current when the reload started, checked against it, landed here, the page pixels swapped in
 * beside it and the committed model returned for the resolver's pre-warm.
 *
 * @param DocumentChange.ReloadArtwork change   The step's counts, for the history label.
 * @param PuppetModel                  reloaded The model with the reload and its pack applied.
 * @return PuppetModel The committed model.
 */
fun EditorSession.commitArtworkReloaded(change: DocumentChange.ReloadArtwork, reloaded: PuppetModel): PuppetModel {
	mutate(change) { reloaded }
	return reloaded
}

/**
 * Commits a model that already carries one tile's rebinding with the layer's art pulled in, and its
 * pack, as ONE undo step; see [commitArtworkAdded] for the contract.
 *
 * @param AtlasTileId tileId   The rebound tile's id before the replacement.
 * @param PuppetModel relinked The model with the relink and its pack applied.
 * @return PuppetModel The committed model.
 */
fun EditorSession.commitArtworkRelinked(tileId: AtlasTileId, relinked: PuppetModel): PuppetModel {
	mutate(DocumentChange.RelinkArtwork(tileId)) { relinked }
	return relinked
}

/**
 * Commits a model that already carries the matcher's accepted rebindings and their pack as ONE undo
 * step; see [commitArtworkAdded] for the contract.
 *
 * @param DocumentChange.MatchArtwork change  The step's counts, for the history label.
 * @param PuppetModel                 matched The model with the rebindings and their pack applied.
 * @return PuppetModel The committed model.
 */
fun EditorSession.commitArtworkMatched(change: DocumentChange.MatchArtwork, matched: PuppetModel): PuppetModel {
	mutate(change) { matched }
	return matched
}

/**
 * Commits a model that already carries one record's replacement, the reload it resolved by key, and
 * its pack as ONE undo step; see [commitArtworkAdded] for the contract.
 *
 * @param DocumentChange.ReplaceArtwork change   The step's counts, for the history label.
 * @param PuppetModel                   replaced The model with the replacement and its pack applied.
 * @return PuppetModel The committed model.
 */
fun EditorSession.commitArtworkReplaced(change: DocumentChange.ReplaceArtwork, replaced: PuppetModel): PuppetModel {
	mutate(change) { replaced }
	return replaced
}