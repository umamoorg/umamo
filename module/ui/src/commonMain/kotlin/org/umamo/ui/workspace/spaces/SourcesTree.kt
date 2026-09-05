package org.umamo.ui.workspace.spaces

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

/** Which rows the Sources space shows. */
enum class SourcesFilter {
	/** Every row. */
	All,

	/** Layers no tile is bound to, and tiles bound to no layer. */
	Unbound,

	/** Artwork files that are no longer where the document read them. */
	Missing,
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

	/** A row with no status of its own. */
	None,
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
 * One row of the Sources tree.
 *
 * @property String          id       A stable, unique key for expand state and drop hit-testing.
 * @property String          label    The display text (a document name, never localized chrome, except the unbound group's).
 * @property SourcesDetail   detail   The secondary text.
 * @property SourcesNodeKind kind     What the row stands for.
 * @property SourcesStatus   status   The status chip.
 * @property List            children The child rows, in display order.
 */
data class SourcesNode(
	val id: String,
	val label: String,
	val detail: SourcesDetail,
	val kind: SourcesNodeKind,
	val status: SourcesStatus,
	val children: List<SourcesNode>,
)

/** One visible row after flattening: the node and its depth. */
data class SourcesRow(val node: SourcesNode, val depth: Int)

/** The id of the synthetic unbound-art group row. */
const val SOURCES_UNBOUND_GROUP_ID: String = "unbound"

/**
 * Builds the Sources tree from a puppet: one node per artwork file in document order, each holding
 * its inventory layers in the file's order with the tiles bound to each (and, after them, any tile
 * bound to a key the inventory no longer lists), the drawables over each tile, and last the unbound
 * group when any tile has no binding.
 *
 * @param PuppetModel puppet            The rig to walk.
 * @param Function    presenceOf        Whether each file is still on disk.
 * @param String      unboundGroupLabel The localized label of the unbound-art group.
 * @return List<SourcesNode> The top-level rows.
 */
fun buildSourcesTree(puppet: PuppetModel, presenceOf: (ArtSource) -> SourcePresence, unboundGroupLabel: String): List<SourcesNode> {
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

	fun layerNode(sourceId: ArtSourceId, key: String, label: String, detail: SourcesDetail): SourcesNode {
		val bound = tilesByBinding[sourceId to key].orEmpty()
		val stable = bound.any { tile -> tile.source?.stableKey == true }
		val status =
			when {
				bound.isEmpty() -> SourcesStatus.Unbound
				stable -> SourcesStatus.Bound
				else -> SourcesStatus.BoundByName
			}
		return SourcesNode(
			id = "layer:${sourceId.raw}/$key",
			label = label,
			detail = detail,
			kind = SourcesNodeKind.Layer(SourceLayerRef(sourceId, key, stableKey = stable || layerKeyLooksStable(key))),
			status = status,
			children = bound.map { tile -> tileNode(tile.id) },
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
				source.layers.map { layer ->
					val node = layerNode(source.id, layer.key, layer.name, SourcesDetail.Layer(layer.width, layer.height, layer.left, layer.top))
					val ordinal = (rowCountByKey[layer.key] ?: 0) + 1
					rowCountByKey[layer.key] = ordinal
					if (ordinal == 1) node else node.copy(id = "${node.id}~$ordinal", status = SourcesStatus.Unbound, children = emptyList())
				}
			// Tiles bound to this file under a key its inventory no longer lists (a layer renamed or removed
			// upstream, or a CMO3 whose walk found no such layer): shown so the binding is never invisible.
			val strayRows =
				tilesByBinding.keys
					.filter { (sourceId, key) -> sourceId == source.id && key !in inventoryKeys }
					.sortedBy { (_, key) -> key }
					.map { (_, key) -> layerNode(source.id, key, key.removePrefix("name:"), SourcesDetail.None) }
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
 * Prunes the tree to [filter] and [query]: a row survives when it satisfies the filter (or sits under
 * one that does) and its label matches the query, or when any descendant survives.  Ancestors of a
 * surviving row are kept for context, exactly as the outliner's search does.
 *
 * @param List<SourcesNode> nodes  The top-level rows.
 * @param String            query  The name search; blank matches everything.
 * @param SourcesFilter     filter Which rows to show.
 * @return List<SourcesNode> The surviving rows.
 */
fun filterSourcesTree(nodes: List<SourcesNode>, query: String, filter: SourcesFilter): List<SourcesNode> {
	val trimmed = query.trim()

	fun matchesFilter(node: SourcesNode): Boolean =
		when (filter) {
			SourcesFilter.All -> true
			SourcesFilter.Unbound -> node.status == SourcesStatus.Unbound || node.kind == SourcesNodeKind.UnboundGroup
			SourcesFilter.Missing -> node.kind is SourcesNodeKind.Source && node.status == SourcesStatus.Missing
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