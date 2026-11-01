package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Immutable
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId

/**
 * One visible row after flattening: the node and its depth.
 *
 * Immutable, as its node is: the rows are built again on every fold, and a row built again for the same
 * node at the same depth equals the one before it, so its composable skips.
 *
 * @property SourcesNode node  The node the row shows.
 * @property Int         depth How deep the row sits, 0 for a file and the unbound group.
 */
@Immutable
internal data class SourcesRow(val node: SourcesNode, val depth: Int)

/**
 * Flattens the tree into the visible rows: a node's children follow it when [isOpen] says its row
 * is expanded.
 *
 * @param List<SourcesNode> nodes  The top-level rows.
 * @param Function          isOpen Whether the row with the given id is expanded.
 * @return List<SourcesRow> The rows top to bottom, each with its depth.
 */
internal fun flattenSources(nodes: List<SourcesNode>, isOpen: (String) -> Boolean): List<SourcesRow> {
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

/** The art a row previews on hover: a tile's source art, or a drawable's crop of the atlas. */
internal sealed interface SourcesPreviewSubject {
	data class Tile(val tileId: AtlasTileId) : SourcesPreviewSubject

	data class Drawable(val drawableId: DrawableId) : SourcesPreviewSubject
}

/**
 * The art a row previews on hover, or null for a row with none in the document, such as an unbound layer
 * whose pixels live only in its file.  A layer under review still binds its old tile, so its row previews
 * the art the review is about.
 *
 * A layer's tile is read off the row's own list, not off the rows listed under it: a search lists only
 * the tiles it matches, and the row stands for the binding whatever is listed.
 *
 * @param SourcesNode node The hovered row.
 * @return SourcesPreviewSubject? What the row previews, or null for nothing.
 */
internal fun sourcesPreviewSubject(node: SourcesNode): SourcesPreviewSubject? =
	when (val kind = node.kind) {
		is SourcesNodeKind.Tile -> SourcesPreviewSubject.Tile(kind.tileId)
		is SourcesNodeKind.Layer -> node.tileIds.firstOrNull()?.let { tileId -> SourcesPreviewSubject.Tile(tileId) }
		is SourcesNodeKind.Drawable -> SourcesPreviewSubject.Drawable(kind.drawableId)
		is SourcesNodeKind.Source, SourcesNodeKind.UnboundGroup -> null
	}

/**
 * The drawables a row's click selects, read off the row's own list, so a click asks the model nothing and
 * selects the same whatever a search leaves listed under the row.
 *
 * @param SourcesNode node The clicked row.
 * @return List The targets, possibly empty.
 */
internal fun sourcesSelectionTargets(node: SourcesNode): List<SelectionTarget> =
	when (val kind = node.kind) {
		is SourcesNodeKind.Drawable -> listOf(SelectionTarget.Drawable(kind.drawableId))
		is SourcesNodeKind.Tile, is SourcesNodeKind.Layer -> node.drawableIds.map { drawableId -> SelectionTarget.Drawable(drawableId) }
		is SourcesNodeKind.Source, SourcesNodeKind.UnboundGroup -> emptyList()
	}