package org.umamo.ui.workspace.spaces.sources

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.layerKeyLooksStable

/**
 * One artwork file's rows in the relink list: the file as a heading, the layers beneath it.
 *
 * @property ArtSource source The file.
 * @property List      layers The layers to list under it, in inventory order.
 */
internal class RelinkGroup(
	val source: ArtSource,
	val layers: List<ArtSourceLayer>,
)

/**
 * The relink list for [query]: every file with every layer when the query is blank; otherwise a layer
 * survives when its name matches, a whole file survives when the FILE name matches, and a file with
 * nothing left under it is dropped.  Grouped by file so the file name is read once as a heading and
 * a long one can never push the layer names out of a row.
 *
 * @param List<ArtSource> sources The document's artwork files.
 * @param String          query   The search text, matched case-insensitively after trimming.
 * @return List<RelinkGroup> The files and their surviving layers, in document order.
 */
internal fun relinkGroups(sources: List<ArtSource>, query: String): List<RelinkGroup> {
	val trimmed = query.trim()
	return sources.mapNotNull { source ->
		// A row the file lost is kept for the review, never offered as a target; a layer erased to nothing
		// has no art to give; an ignored layer is kept out of the rig until its row says otherwise.
		val present = source.layers.filter { layer -> layer.present && !layer.empty && !layer.ignored }
		val layers =
			if (trimmed.isEmpty() || source.name.contains(trimmed, ignoreCase = true)) {
				present
			} else {
				present.filter { layer -> layer.name.contains(trimmed, ignoreCase = true) }
			}
		if (layers.isEmpty()) null else RelinkGroup(source, layers)
	}
}

/**
 * The binding to the layer [layerKey] of [sourceId]: the one rule for how strong a layer's key is, which
 * a pick from the relink list, an accepted proposal, and the layer's own row all go by, so a row and the
 * binding it hands out never disagree.
 *
 * A layer some tile already binds says how strong its key is, because the reader that minted the key said
 * so on that tile: the key is stable when any tile bound to the layer carries it as stable, which is what
 * the row's status reads.  For a layer no tile binds the key's shape is all there is to go by.
 *
 * @param List<AtlasTile> tiles    The tiles whose bindings are asked; the document's, or the ones bound to the layer.
 * @param ArtSourceId     sourceId The file the layer belongs to.
 * @param String          layerKey The reader's key for the layer.
 * @return SourceLayerRef The binding, typed stable or by name.
 */
internal fun relinkTargetRef(tiles: List<AtlasTile>, sourceId: ArtSourceId, layerKey: String): SourceLayerRef {
	val bound =
		tiles
			.mapNotNull { tile -> tile.source }
			.filter { ref -> ref.sourceId == sourceId && ref.layerKey == layerKey }
	val stable = if (bound.isEmpty()) layerKeyLooksStable(layerKey) else bound.any { ref -> ref.stableKey }
	return SourceLayerRef(sourceId, layerKey, stableKey = stable)
}