package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.runtime.Immutable
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget

/**
 * One outliner node paired with its tree depth: the unit the body's list renders, and the row a drop, a
 * reveal, and a range selection count over.
 *
 * Immutable, as its node is: the rows are built again on every fold, and a row built again for the same
 * node at the same depth equals the one before it, so its composable skips.
 *
 * @property OutlinerNode node The node.
 * @property Int depth The node's depth below the root, 0 for the root itself.
 */
@Immutable
internal data class OutlinerRow(val node: OutlinerNode, val depth: Int)

/**
 * Flattens the tree into the visible rows for the body's list, descending into a node's children only
 * when [isOpen] reports it expanded.  Pure given the predicate, so the reveal effect can recompute the
 * same index the body renders.
 *
 * @param OutlinerNode root The tree root.
 * @param Function isOpen Reports whether a node id is expanded.
 * @return List the visible rows, each with its depth.
 */
internal fun flattenOutliner(root: OutlinerNode, isOpen: (String) -> Boolean): List<OutlinerRow> {
	val rows = mutableListOf<OutlinerRow>()

	/**
	 * Adds a node's row, then its children's when the node is open.
	 *
	 * @param OutlinerNode node The node to add.
	 * @param Int depth The node's nesting depth.
	 */
	fun visit(node: OutlinerNode, depth: Int) {
		rows += OutlinerRow(node, depth)

		if (isOpen(node.id)) {
			node.children.forEach { child -> visit(child, depth + 1) }
		}
	}

	visit(root, 0)

	return rows
}

/**
 * Finds the id path from [root] to the node carrying [target], inclusive, or null if absent.  Used by
 * reveal-on-select to expand a found node's ancestors before scrolling to it.
 *
 * @param OutlinerNode root The tree (sub)root to search.
 * @param SelectionTarget target The selection target to locate.
 * @return List the node ids from root to the match, or null.
 */
internal fun pathTo(root: OutlinerNode, target: SelectionTarget): List<String>? {
	if (root.target == target) {
		return listOf(root.id)
	}

	for (child in root.children) {
		val subPath = pathTo(child, target)

		if (subPath != null) {
			return listOf(root.id) + subPath
		}
	}

	return null
}

/**
 * Computes the selection after a row click given the held modifiers: plain replaces, Ctrl / Cmd
 * ([toggle]) toggles the one target, and Shift ([extend]) range-selects from the active row to the
 * clicked one over the currently visible [rows], adding that contiguous run to the existing selection
 * (a mass-select).  With no active anchor the range is just the clicked row.
 *
 * @param List rows The currently visible rows, in display order (the range runs over these).
 * @param Selection current The selection before the click.
 * @param Int clickedIndex The clicked row's index in [rows].
 * @param SelectionTarget target The clicked row's target (becomes active).
 * @param Boolean toggle Whether Ctrl / Cmd was held.
 * @param Boolean extend Whether Shift was held (range select).
 * @return Selection The resulting selection.
 */
internal fun selectionAfterClick(
	rows: List<OutlinerRow>,
	current: Selection,
	clickedIndex: Int,
	target: SelectionTarget,
	toggle: Boolean,
	extend: Boolean,
): Selection =
	when {
		extend -> outlinerRangeSelection(rows.map { row -> row.node.target }, current, clickedIndex, target)
		toggle -> SelectionOps.toggle(current, target)
		else -> SelectionOps.replace(target)
	}