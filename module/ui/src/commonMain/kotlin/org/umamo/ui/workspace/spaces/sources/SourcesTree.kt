package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Immutable
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.tileCarriesNoRigWork
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.ui.model.artwork.SourceFilePresence

/*
 * The Sources table as a tree: artwork file -> its layers (the inventory as of the last read) -> the
 * tiles bound to each layer -> the drawables over each tile, plus a trailing group for art bound to
 * no layer.  A pure function over the model so it unit-tests without a composition; the space renders it.
 */

/** Whether an artwork file is still where the document last read it. */
internal enum class SourcePresence {
	Present,
	Missing,
	Unknown,
}

/**
 * The kinds of row the Sources space can show or hide, each toggled on its own: the table shows the
 * rows of every enabled kind, with their descendants and the ancestors that give them context.  With
 * every kind enabled nothing is hidden at all.
 */
internal enum class SourcesFilter {
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
internal enum class SourcesStatus {
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

	/**
	 * A tile bound to a file the document does not list - a file whose sources entry this version could not
	 * read, or one a foreign writer dropped (docs/format/UMA.md §3.6).  The binding is carried as it is and
	 * waits on a person, like a lost layer.
	 */
	SourceNotListed,

	/** A row with no status of its own. */
	None,
	;

	/** Whether the row stands for a binding waiting on a person: a layer lost, erased, or lost to a replacement, or a file the document does not list. */
	val isReview: Boolean
		get() = this == NeedsReview || this == Emptied || this == SourceReplaced || this == SourceNotListed

	/**
	 * Whether a layer row carries the ignore toggle: an unbound present layer, or one already ignored.  A
	 * bound row is matched by key and needs no mark; a review row has a binding to settle first.
	 */
	val isIgnorable: Boolean
		get() = this == Unbound || this == Ignored
}

/** What a row stands for, and the identity a click or a drop acts on. */
internal sealed interface SourcesNodeKind {
	/** An artwork file the document lists. */
	data class Source(val sourceId: ArtSourceId) : SourcesNodeKind

	/** One layer of a file, as the binding a tile would carry to it. */
	data class Layer(val ref: SourceLayerRef) : SourcesNodeKind

	/** One piece of art in the document's atlas. */
	data class Tile(val tileId: AtlasTileId) : SourcesNodeKind

	/** A drawable sampling a tile. */
	data class Drawable(val drawableId: DrawableId) : SourcesNodeKind

	/** The synthetic group holding every tile bound to no layer or to a file the document does not list. */
	data object UnboundGroup : SourcesNodeKind
}

/** The secondary text a row shows after its label, as data so the space localizes it. */
internal sealed interface SourcesDetail {
	/** A file's format and inventory size, and whether a path is recorded. */
	data class Source(val format: String, val layerCount: Int, val hasPath: Boolean) : SourcesDetail

	/**
	 * A layer's size and position at the last read, in the FILE's own canvas (the row's document position
	 * with the source's offset taken back out: x subtracted, z - which is up - added), so the numbers match
	 * what the art program shows.  The one absolute position the rigger reads that does not start from the
	 * world axes, on purpose: the default, since it is the artist's own frame.
	 */
	data class Layer(val width: Int, val height: Int, val left: Int, val top: Int) : SourcesDetail

	/**
	 * A layer's size and the position of its top-left corner measured from the world axes, x right and z up -
	 * [Layer]'s alternative under the `import.layerPositionsFromWorldAxes` setting, for a rigger who places art
	 * by the rig's coordinates rather than the art program's.
	 */
	data class LayerOnAxes(val width: Int, val height: Int, val x: Float, val z: Float) : SourcesDetail

	/** The 1-based page a placed tile sits on. */
	data class TilePage(val pageNumber: Int) : SourcesDetail

	/** The binding a tile carries to a file the document does not list, shown as the tile carries it. */
	data class UnlistedBinding(val sourceId: ArtSourceId, val layerKey: String) : SourcesDetail

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
internal data class LayerSuggestion(
	val candidateKey: String,
	val candidateName: String,
	val score: Float,
	val retires: List<AtlasTileId> = emptyList(),
)

/**
 * One row of the Sources tree.
 *
 * Immutable: a node is built once with its tree and never changed after, its children included.  The
 * annotation is that promise made to Compose, which then compares a node by value, so a row handed a node
 * equal to the one it has skips.  It is the one Compose name this file knows.
 *
 * @property String           id         A stable, unique key for expand state and drop hit-testing.
 * @property String           label      The display text (a document name, never localized chrome, except the unbound group's).
 * @property SourcesDetail    detail     The secondary text.
 * @property SourcesNodeKind  kind       What the row stands for.
 * @property SourcesStatus    status     The status chip.
 * @property List             children   The child rows, in display order.
 * @property LayerSuggestion? suggestion The proposed relink on a row that needs review, or null.
 * @property List<DrawableId> drawableIds The drawables the row stands over, in document order: on a tile
 *   row every drawable sampling the tile, on a layer row every drawable over every tile bound to the layer;
 *   empty on every other row.  What a click selects, whatever a search leaves listed under the row.
 * @property SourceLayerRef?  binding    On a tile row, the layer the tile is bound to, as the tile carries
 *   it; null for a tile bound to none, and on every other row.
 * @property List<AtlasTileId> tileIds   On a layer row, every tile bound to the layer, by file and key;
 *   empty on every other row.  What a review moves and a hover previews, whatever a search leaves listed.
 */
@Immutable
internal data class SourcesNode(
	val id: String,
	val label: String,
	val detail: SourcesDetail,
	val kind: SourcesNodeKind,
	val status: SourcesStatus,
	val children: List<SourcesNode>,
	val suggestion: LayerSuggestion? = null,
	val drawableIds: List<DrawableId> = emptyList(),
	val binding: SourceLayerRef? = null,
	val tileIds: List<AtlasTileId> = emptyList(),
)

/**
 * Asks where each artwork file stands: present, missing, or unknown.  A file with no recorded path, a
 * platform with no probe, and a path the probe cannot answer for all read unknown, never missing - the
 * table must not accuse a file it could not check.
 *
 * @param List<ArtSource>     sources The document's artwork files.
 * @param SourceFilePresence? probe   The platform's probe, or null when it has none.
 * @return Map<ArtSourceId, SourcePresence> Every file's answer, by file.
 */
internal suspend fun probeSourcePresence(sources: List<ArtSource>, probe: SourceFilePresence?): Map<ArtSourceId, SourcePresence> {
	val answers = LinkedHashMap<ArtSourceId, SourcePresence>()
	for (source in sources) {
		val path = source.path
		val present = if (path == null || probe == null) null else probe(path)
		answers[source.id] =
			when (present) {
				null -> SourcePresence.Unknown
				true -> SourcePresence.Present
				false -> SourcePresence.Missing
			}
	}
	return answers
}

/**
 * The id of the row a layer's binding is listed under: the file and the reader's key, which is what a
 * tile bound to the layer carries.  The tree names the row by it and a drop opens the row by it, so the
 * row opened is the row the art lands under.
 *
 * @param ArtSourceId sourceId The file the layer belongs to.
 * @param String      layerKey The reader's key for the layer.
 * @return String The row's node id.
 */
internal fun sourcesLayerRowId(sourceId: ArtSourceId, layerKey: String): String = "layer:${sourceId.raw}/$layerKey"

/** The id of the synthetic unbound-art group row. */
internal const val SOURCES_UNBOUND_GROUP_ID: String = "unbound"

/**
 * Builds the Sources tree from a puppet: one node per artwork file in document order, each holding
 * its inventory layers in the file's order with the tiles bound to each - a layer the file lost but a
 * tile still binds reads as needing review, named and sized as the inventory last saw it, with the
 * proposed relink when there is one and its own status when a replacement lost it; an unbound layer
 * the rigger ignored reads as ignored - then any tile bound to a key the inventory never listed, the
 * drawables over each tile, and last the unbound group when any tile has no binding or is bound to a file
 * the document does not list (those read as needing review, with the binding they carry).
 *
 * @param PuppetModel puppet            The rig to walk.
 * @param Function    presenceOf        Whether each file is still on disk.
 * @param String      unboundGroupLabel The localized label of the unbound-art group.
 * @param Boolean     layerPositionsFromWorldAxes Whether layer rows read their position from the world axes
 *   ([SourcesDetail.LayerOnAxes]) instead of the file's own top-left corner ([SourcesDetail.Layer]).
 * @param Function    suggestionsFor    The proposals for a lost binding by file and key, best first
 *   (the pixel-scored one an operation published, then the one the inventory alone ranks); the row
 *   takes the first that still holds - one naming a layer the file no longer has, or a layer bound to a
 *   tile with rig work over it, is passed over, so a stale published proposal never hides a live one
 *   behind it; a layer bound only to fresh, untouched drawables stands, and the proposal names the
 *   tiles accepting it retires.
 * @return List<SourcesNode> The top-level rows.
 */
internal fun buildSourcesTree(
	puppet: PuppetModel,
	presenceOf: (ArtSource) -> SourcePresence,
	unboundGroupLabel: String,
	layerPositionsFromWorldAxes: Boolean = false,
	suggestionsFor: (ArtSourceId, String) -> List<LayerMatch> = { _, _ -> emptyList() },
): List<SourcesNode> {
	val drawableIdsByTile = puppet.drawableIdsByAtlasTile()
	val drawableNameById = puppet.drawables.associate { drawable -> drawable.id to drawable.name }
	val tilesByBinding = puppet.atlas.tiles.filter { tile -> tile.source != null }.groupBy { tile -> tile.source!!.sourceId to tile.source!!.layerKey }
	val listedSourceIds = puppet.sources.mapTo(HashSet()) { source -> source.id }

	fun tileNode(tileId: AtlasTileId): SourcesNode {
		val tile = puppet.atlas.tileById.getValue(tileId)
		val placement = tile.placement
		val drawableIds = drawableIdsByTile[tile.id].orEmpty()
		val unlisted = tile.source?.takeIf { ref -> ref.sourceId !in listedSourceIds }
		return SourcesNode(
			id = "tile:${tile.id.raw}",
			label = tile.name,
			detail =
				when {
					unlisted != null -> SourcesDetail.UnlistedBinding(unlisted.sourceId, unlisted.layerKey)
					placement != null -> SourcesDetail.TilePage(placement.pageIndex + 1)
					else -> SourcesDetail.None
				},
			kind = SourcesNodeKind.Tile(tile.id),
			status =
				when {
					unlisted != null -> SourcesStatus.SourceNotListed
					placement == null -> SourcesStatus.Unplaced
					else -> SourcesStatus.None
				},
			children =
				drawableIds.map { drawableId ->
					SourcesNode(
						id = "drawable:${drawableId.raw}",
						label = drawableNameById[drawableId] ?: drawableId.raw,
						detail = SourcesDetail.None,
						kind = SourcesNodeKind.Drawable(drawableId),
						status = SourcesStatus.None,
						children = emptyList(),
					)
				},
			drawableIds = drawableIds,
			binding = tile.source,
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
			id = sourcesLayerRowId(sourceId, key),
			label = label,
			detail = detail,
			kind = SourcesNodeKind.Layer(relinkTargetRef(bound, sourceId, key)),
			status = status,
			children = bound.map { tile -> tileNode(tile.id) },
			suggestion = suggestion,
			drawableIds = bound.flatMap { tile -> drawableIdsByTile[tile.id].orEmpty() },
			tileIds = bound.map { tile -> tile.id },
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
					val detail =
						if (layerPositionsFromWorldAxes) {
							// The inventory sits on the document canvas (canvas x, y down), and world z is the
							// negated canvas y, so the corner converts through the origin like any position.
							SourcesDetail.LayerOnAxes(layer.width, layer.height, puppet.originRelativeX(layer.left.toFloat()), puppet.originRelativeZ(-layer.top.toFloat()))
						} else {
							SourcesDetail.Layer(layer.width, layer.height, layer.left - source.offsetX, layer.top + source.offsetZ)
						}
					val node =
						layerNode(
							source,
							layer.key,
							layer.name,
							detail,
							listed = layer.present,
							emptied = layer.empty,
							replaced = layer.replaced,
							ignored = layer.ignored,
						)
					val ordinal = (rowCountByKey[layer.key] ?: 0) + 1
					rowCountByKey[layer.key] = ordinal
					if (ordinal == 1) {
						node
					} else {
						node.copy(id = "${node.id}~$ordinal", status = SourcesStatus.Unbound, children = emptyList(), suggestion = null, drawableIds = emptyList(), tileIds = emptyList())
					}
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
	// The trailing group holds every tile no file row lists: the ones bound to no layer, and the ones bound to a
	// file the document does not list, which would otherwise appear nowhere (UMA §3.6).
	val unboundTiles = puppet.atlas.tiles.filter { tile -> tile.source == null || tile.source!!.sourceId !in listedSourceIds }
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
internal fun filterSourcesTree(nodes: List<SourcesNode>, query: String, filters: Set<SourcesFilter>): List<SourcesNode> {
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