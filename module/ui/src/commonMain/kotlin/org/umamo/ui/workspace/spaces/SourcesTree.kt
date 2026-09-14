package org.umamo.ui.workspace.spaces

import org.umamo.reimport.LayerMatch
import org.umamo.reimport.tileCarriesNoRigWork
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.drawableIdsByAtlasTile

/*
 * The Sources table as a tree: artwork file -> its layers (the inventory as of the last read) -> the
 * tiles bound to each layer -> the drawables over each tile, plus a trailing group for art bound to
 * no layer.  A pure function over the model so it unit-tests without Compose; the space renders it.
 */

/** Whether an artwork file is still where the document last read it. */
enum class SourcePresence {
	Present,
	Missing,
	Unknown,
}

/**
 * The kinds of row the Sources space can show or hide, each toggled on its own: the table shows the
 * rows of every enabled kind, with their descendants and the ancestors that give them context.  With
 * every kind enabled nothing is hidden at all.
 */
enum class SourcesFilter {
	/** Layers some tile is bound to, by a stable key or by name. */
	Bound,

	/** Layers no tile is bound to (the ignored ones among them), and tiles bound to no layer. */
	Unbound,

	/** Artwork files that are no longer where the document read them, with everything under them. */
	Missing,

	/** Bindings a reload could not resolve: tiles bound to a layer their file no longer lists, has erased to nothing, or a replacement file lacks. */
	NeedsReview,
}

/** The status a row shows at its right edge. */
enum class SourcesStatus {
	Present,
	Missing,
	Unknown,

	/** A layer some tile is bound to through a format-minted key. */
	Bound,

	/** A layer some tile is bound to through a name-and-order key, which only holds while the layer keeps its name and place. */
	BoundByName,

	/** A layer no tile is bound to, or a tile bound to no layer. */
	Unbound,

	/** A tile that is in the document but on no page. */
	Unplaced,

	/** A binding to a layer its file no longer lists: the tile keeps its art until a person decides. */
	NeedsReview,

	/** A binding to a layer the file still has but erased to nothing: the tile keeps its art until a person decides. */
	Emptied,

	/** A binding to a layer the replacement file has no key for (Replace Artwork repointed the record): the same wait, with its own reason. */
	SourceReplaced,

	/** A present layer no tile binds that the rigger keeps out of the rig: a reload never mints it. */
	Ignored,

	/** A row with no status of its own. */
	None,
	;

	/** Whether the row stands for a binding waiting on a person: a layer lost, erased, or lost to a replacement. */
	val isReview: Boolean
		get() = this == NeedsReview || this == Emptied || this == SourceReplaced
}

/** What a row stands for, and the identity a click or a drop acts on. */
sealed interface SourcesNodeKind {
	/** An artwork file the document lists. */
	data class Source(val sourceId: ArtSourceId) : SourcesNodeKind

	/** One layer of a file, as the binding a tile would carry to it. */
	data class Layer(val ref: SourceLayerRef) : SourcesNodeKind

	/** One piece of art in the document's atlas. */
	data class Tile(val tileId: AtlasTileId) : SourcesNodeKind

	/** A drawable sampling a tile. */
	data class Drawable(val drawableId: DrawableId) : SourcesNodeKind

	/** The synthetic group holding every tile bound to no layer. */
	data object UnboundGroup : SourcesNodeKind
}

/** The secondary text a row shows after its label, as data so the space localizes it. */
sealed interface SourcesDetail {
	/** A file's format and inventory size, and whether a path is recorded. */
	data class Source(val format: String, val layerCount: Int, val hasPath: Boolean) : SourcesDetail

	/** A layer's size and canvas position at the last read. */
	data class Layer(val width: Int, val height: Int, val left: Int, val top: Int) : SourcesDetail

	/** The 1-based page a placed tile sits on. */
	data class TilePage(val pageNumber: Int) : SourcesDetail

	/** No secondary text. */
	data object None : SourcesDetail
}

/**
 * What the document proposes for a binding its file no longer resolves: the layer it would move to,
 * how sure the matcher is, and the tiles that go with the move.
 *
 * @property String            candidateKey  The proposed layer's key.
 * @property String            candidateName The proposed layer's name, for the chip.
 * @property Float             score         The confidence, 0..1.
 * @property List<AtlasTileId> retires       The tiles bound to the proposed layer that accepting retires
 *   with their drawables - a fresh, untouched drawable a reload minted for the layer - so the chip can
 *   say so; empty when the layer is unbound.
 */
data class LayerSuggestion(
	val candidateKey: String,
	val candidateName: String,
	val score: Float,
	val retires: List<AtlasTileId> = emptyList(),
)

/**
 * One row of the Sources tree.
 *
 * @property String           id         A stable, unique key for expand state and drop hit-testing.
 * @property String           label      The display text (a document name, never localized chrome, except the unbound group's).
 * @property SourcesDetail    detail     The secondary text.
 * @property SourcesNodeKind  kind       What the row stands for.
 * @property SourcesStatus    status     The status chip.
 * @property List             children   The child rows, in display order.
 * @property LayerSuggestion? suggestion The proposed relink on a row that needs review, or null.
 */
data class SourcesNode(
	val id: String,
	val label: String,
	val detail: SourcesDetail,
	val kind: SourcesNodeKind,
	val status: SourcesStatus,
	val children: List<SourcesNode>,
	val suggestion: LayerSuggestion? = null,
)

/** One visible row after flattening: the node and its depth. */
data class SourcesRow(val node: SourcesNode, val depth: Int)

/** The id of the synthetic unbound-art group row. */
const val SOURCES_UNBOUND_GROUP_ID: String = "unbound"

/**
 * Builds the Sources tree from a puppet: one node per artwork file in document order, each holding
 * its inventory layers in the file's order with the tiles bound to each - a layer the file lost but a
 * tile still binds reads as needing review, named and sized as the inventory last saw it, with the
 * proposed relink when there is one and its own status when a replacement lost it; an unbound layer
 * the rigger ignored reads as ignored - then any tile bound to a key the inventory never listed, the
 * drawables over each tile, and last the unbound group when any tile has no binding.
 *
 * @param PuppetModel puppet            The rig to walk.
 * @param Function    presenceOf        Whether each file is still on disk.
 * @param String      unboundGroupLabel The localized label of the unbound-art group.
 * @param Function    suggestionsFor    The proposals for a lost binding by file and key, best first
 *   (the pixel-scored one an operation published, then the one the inventory alone ranks); the row
 *   takes the first that still holds - one naming a layer the file no longer has, or a layer bound to a
 *   tile with rig work over it, is passed over, so a stale published proposal never hides a live one
 *   behind it; a layer bound only to fresh, untouched drawables stands, and the proposal names the
 *   tiles accepting it retires.
 * @return List<SourcesNode> The top-level rows.
 */
fun buildSourcesTree(
	puppet: PuppetModel,
	presenceOf: (ArtSource) -> SourcePresence,
	unboundGroupLabel: String,
	suggestionsFor: (ArtSourceId, String) -> List<LayerMatch> = { _, _ -> emptyList() },
): List<SourcesNode> {
	val drawableIdsByTile = puppet.drawableIdsByAtlasTile()
	val drawableNameById = puppet.drawables.associate { drawable -> drawable.id to drawable.name }
	val tilesByBinding = puppet.atlas.tiles.filter { tile -> tile.source != null }.groupBy { tile -> tile.source!!.sourceId to tile.source!!.layerKey }

	fun tileNode(tileId: AtlasTileId): SourcesNode {
		val tile = puppet.atlas.tileById.getValue(tileId)
		val placement = tile.placement
		return SourcesNode(
			id = "tile:${tile.id.raw}",
			label = tile.name,
			detail = if (placement != null) SourcesDetail.TilePage(placement.pageIndex + 1) else SourcesDetail.None,
			kind = SourcesNodeKind.Tile(tile.id),
			status = if (placement == null) SourcesStatus.Unplaced else SourcesStatus.None,
			children =
				drawableIdsByTile[tile.id].orEmpty().map { drawableId ->
					SourcesNode(
						id = "drawable:${drawableId.raw}",
						label = drawableNameById[drawableId] ?: drawableId.raw,
						detail = SourcesDetail.None,
						kind = SourcesNodeKind.Drawable(drawableId),
						status = SourcesStatus.None,
						children = emptyList(),
					)
				},
		)
	}

	fun layerNode(
		source: ArtSource,
		key: String,
		label: String,
		detail: SourcesDetail,
		listed: Boolean = true,
		emptied: Boolean = false,
		replaced: Boolean = false,
		ignored: Boolean = false,
	): SourcesNode {
		val sourceId = source.id
		val bound = tilesByBinding[sourceId to key].orEmpty()
		val stable = bound.any { tile -> tile.source?.stableKey == true }
		val status =
			when {
				!listed && replaced -> SourcesStatus.SourceReplaced
				!listed -> SourcesStatus.NeedsReview
				// The ignore mark means nothing for a bound layer, so a bound row reads bound whatever it says.
				bound.isEmpty() && ignored -> SourcesStatus.Ignored
				bound.isEmpty() -> SourcesStatus.Unbound
				emptied -> SourcesStatus.Emptied
				stable -> SourcesStatus.Bound
				else -> SourcesStatus.BoundByName
			}
		val suggestion =
			if (status.isReview) {
				suggestionsFor(sourceId, key).firstNotNullOfOrNull { match ->
					val candidate = source.layers.firstOrNull { layer -> layer.key == match.key && layer.present && !layer.empty }
					val boundToCandidate = tilesByBinding[sourceId to match.key].orEmpty()
					when {
						candidate == null -> null
						boundToCandidate.any { tile -> !puppet.tileCarriesNoRigWork(tile.id, candidate) } -> null
						else -> LayerSuggestion(match.key, candidate.name, match.score, boundToCandidate.map { tile -> tile.id })
					}
				}
			} else {
				null
			}
		return SourcesNode(
			id = "layer:${sourceId.raw}/$key",
			label = label,
			detail = detail,
			kind = SourcesNodeKind.Layer(SourceLayerRef(sourceId, key, stableKey = stable || layerKeyLooksStable(key))),
			status = status,
			children = bound.map { tile -> tileNode(tile.id) },
			suggestion = suggestion,
		)
	}

	val sourceNodes =
		puppet.sources.map { source ->
			val inventoryKeys = source.layers.mapTo(HashSet()) { layer -> layer.key }
			// A reader mints one key per layer, so a repeated key can only come from a foreign or broken
			// file.  The guard keeps every row id unique (the list keys its rows on them) and lets the first
			// row own the binding, so no tile is listed twice.
			val rowCountByKey = HashMap<String, Int>()
			val inventoryRows =
				source.layers.mapNotNull { layer ->
					// A row the file lost is kept only while a tile binds it - its whole purpose is the
					// review of those tiles.  Once the last one is unbound or relinked away the row would
					// review nothing, so it leaves the table ahead of the refresh that prunes it.
					if (!layer.present && !tilesByBinding.containsKey(source.id to layer.key)) {
						return@mapNotNull null
					}
					val node =
						layerNode(
							source,
							layer.key,
							layer.name,
							SourcesDetail.Layer(layer.width, layer.height, layer.left, layer.top),
							listed = layer.present,
							emptied = layer.empty,
							replaced = layer.replaced,
							ignored = layer.ignored,
						)
					val ordinal = (rowCountByKey[layer.key] ?: 0) + 1
					rowCountByKey[layer.key] = ordinal
					if (ordinal == 1) node else node.copy(id = "${node.id}~$ordinal", status = SourcesStatus.Unbound, children = emptyList(), suggestion = null)
				}
			// Tiles bound to this file under a key its inventory never listed (a CMO3 whose walk found no such
			// layer, or a document from before the inventory kept lost rows): shown so the binding is never invisible.
			val strayRows =
				tilesByBinding.keys
					.filter { (sourceId, key) -> sourceId == source.id && key !in inventoryKeys }
					.sortedBy { (_, key) -> key }
					.map { (_, key) -> layerNode(source, key, key.removePrefix("name:"), SourcesDetail.None, listed = false) }
			SourcesNode(
				id = "source:${source.id.raw}",
				label = source.name,
				detail = SourcesDetail.Source(source.format, source.layers.size, hasPath = source.path != null),
				kind = SourcesNodeKind.Source(source.id),
				status =
					when (presenceOf(source)) {
						SourcePresence.Present -> SourcesStatus.Present
						SourcePresence.Missing -> SourcesStatus.Missing
						SourcePresence.Unknown -> SourcesStatus.Unknown
					},
				children = inventoryRows + strayRows,
			)
		}
	val unboundTiles = puppet.atlas.tiles.filter { tile -> tile.source == null }
	if (unboundTiles.isEmpty()) {
		return sourceNodes
	}
	return sourceNodes +
		SourcesNode(
			id = SOURCES_UNBOUND_GROUP_ID,
			label = unboundGroupLabel,
			detail = SourcesDetail.None,
			kind = SourcesNodeKind.UnboundGroup,
			status = SourcesStatus.None,
			children = unboundTiles.map { tile -> tileNode(tile.id) },
		)
}

/**
 * Whether a layer key reads as format-minted: the PSD name-and-order fallback (`name:` or a `#` order
 * suffix) is the one weak shape the readers produce; everything else (a lyid, a CLIP or Krita uuid)
 * survives a rename.  Used only to type a relink to a layer no tile was bound to before - a bound
 * layer's own ref says what it is.
 *
 * @param String key The reader's layer key.
 * @return Boolean True when the key looks stable.
 */
fun layerKeyLooksStable(key: String): Boolean = !key.startsWith("name:") && !key.contains('#')

/**
 * Prunes the tree to [filters] and [query]: a row survives when it is of an enabled kind (or sits
 * under one that is) and its label matches the query, or when any descendant survives.  Ancestors
 * of a surviving row are kept for context, exactly as the outliner's search does.  With every kind
 * enabled only the query prunes, so a file with no layers still lists; with none enabled nothing does.
 *
 * @param List<SourcesNode>  nodes   The top-level rows.
 * @param String             query   The name search; blank matches everything.
 * @param Set<SourcesFilter> filters The kinds of row to show.
 * @return List<SourcesNode> The surviving rows.
 */
fun filterSourcesTree(nodes: List<SourcesNode>, query: String, filters: Set<SourcesFilter>): List<SourcesNode> {
	val trimmed = query.trim()
	val unfiltered = filters.size == SourcesFilter.entries.size

	fun matchesFilter(node: SourcesNode): Boolean =
		unfiltered ||
			filters.any { filter ->
				when (filter) {
					SourcesFilter.Bound -> node.status == SourcesStatus.Bound || node.status == SourcesStatus.BoundByName
					SourcesFilter.Unbound -> node.status == SourcesStatus.Unbound || node.status == SourcesStatus.Ignored || node.kind == SourcesNodeKind.UnboundGroup
					SourcesFilter.Missing -> node.kind is SourcesNodeKind.Source && node.status == SourcesStatus.Missing
					SourcesFilter.NeedsReview -> node.status.isReview
				}
			}

	fun prune(node: SourcesNode, satisfiedAbove: Boolean): SourcesNode? {
		val satisfied = satisfiedAbove || matchesFilter(node)
		val children = node.children.mapNotNull { child -> prune(child, satisfied) }
		val labelMatches = trimmed.isEmpty() || node.label.contains(trimmed, ignoreCase = true)
		return when {
			children.isNotEmpty() -> node.copy(children = children)
			satisfied && labelMatches -> node
			else -> null
		}
	}
	return nodes.mapNotNull { node -> prune(node, satisfiedAbove = false) }
}

/**
 * Flattens the tree into the visible rows: a node's children follow it when [isOpen] says its row
 * is expanded.
 *
 * @param List<SourcesNode> nodes  The top-level rows.
 * @param Function          isOpen Whether the row with the given id is expanded.
 * @return List<SourcesRow> The rows top to bottom, each with its depth.
 */
fun flattenSources(nodes: List<SourcesNode>, isOpen: (String) -> Boolean): List<SourcesRow> {
	val rows = ArrayList<SourcesRow>()

	fun visit(node: SourcesNode, depth: Int) {
		rows.add(SourcesRow(node, depth))
		if (node.children.isNotEmpty() && isOpen(node.id)) {
			for (child in node.children) {
				visit(child, depth + 1)
			}
		}
	}
	for (node in nodes) {
		visit(node, 0)
	}
	return rows
}