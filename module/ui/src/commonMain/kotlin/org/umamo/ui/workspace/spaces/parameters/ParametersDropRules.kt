package org.umamo.ui.workspace.spaces.parameters

import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.parameter.ParameterNodeRef
import org.umamo.edit.structure.RowDropBand
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.materializedParameterTree

// Above / below these fractions a group-header drop reads as reorder (before / after) rather than nest.
private const val GROUP_NEST_LOW_FRACTION = 0.33f
private const val GROUP_NEST_HIGH_FRACTION = 0.66f

/**
 * The band a drop resolves to, or null when the pairing is illegal - one level of groups only, so a parameter
 * may nest into a group header, but a dragged group never nests and reorders only among root nodes.  Used by
 * both the indicator and the dispatch, so the line the user sees matches the move that happens.
 *
 * @param ParameterMoveSubject dragged The subject being dragged.
 * @param ParameterRow targetRow The row under the pointer.
 * @param Boolean targetAtRoot Whether the target sits at the tree root (depth 0).
 * @param Float fraction The pointer's 0..1 position down the target row.
 * @return RowDropBand? The resolved band, or null when the drop is illegal.
 */
internal fun parameterDropBandFor(
	dragged: ParameterMoveSubject,
	targetRow: ParameterRow,
	targetAtRoot: Boolean,
	fraction: Float,
): RowDropBand? {
	val draggingGroup = dragged is ParameterMoveSubject.Group

	return when (targetRow) {
		is ParameterRow.GroupHeader -> {
			if (draggingGroup) {
				beforeOrAfter(fraction)
			} else {
				when {
					fraction < GROUP_NEST_LOW_FRACTION -> RowDropBand.Before
					fraction > GROUP_NEST_HIGH_FRACTION -> RowDropBand.After
					else -> RowDropBand.Into
				}
			}
		}

		is ParameterRow.Single, is ParameterRow.Pair2D -> {
			// A dragged group can only live at the root, so a target inside a group refuses it.
			if (draggingGroup && !targetAtRoot) {
				null
			} else {
				beforeOrAfter(fraction)
			}
		}
	}
}

/**
 * Before above the row's midline, After below it.
 *
 * @param Float fraction The pointer's 0..1 position down the target row.
 * @return RowDropBand The reorder band.
 */
private fun beforeOrAfter(fraction: Float): RowDropBand =
	if (fraction >= 0.5f) {
		RowDropBand.After
	} else {
		RowDropBand.Before
	}

/**
 * The subject a drag of [row] relocates: a slider is one leaf, a linked pad is its two leaves in order
 * (kept contiguous for combined-export adjacency), a group header is the whole group.
 *
 * @param ParameterRow row The row being dragged.
 * @return ParameterMoveSubject What the drag moves.
 */
internal fun parameterMoveSubjectOf(row: ParameterRow): ParameterMoveSubject =
	when (row) {
		is ParameterRow.Single -> ParameterMoveSubject.Leaves(listOf(row.parameter.id))
		is ParameterRow.Pair2D -> ParameterMoveSubject.Leaves(listOf(row.horizontal.id, row.vertical.id))
		is ParameterRow.GroupHeader -> ParameterMoveSubject.Group(row.groupId)
	}

/**
 * The destination parent group and the insert-before anchor for dropping onto [targetRow] with [band],
 * resolved against the model's tree (a group-less model is treated as a flat root of leaves).
 *
 * @param PuppetModel puppet The model whose tree is walked.
 * @param ParameterMoveSubject subject What is being dragged, which never anchors its own drop.
 * @param ParameterRow targetRow The row under the pointer.
 * @param RowDropBand band The resolved band.
 * @return Pair<ParameterGroupId?, ParameterNodeRef?> The destination parent (null = root) and the anchor.
 */
internal fun parameterDropAnchor(
	puppet: PuppetModel,
	subject: ParameterMoveSubject,
	targetRow: ParameterRow,
	band: RowDropBand,
): Pair<ParameterGroupId?, ParameterNodeRef?> {
	if (band == RowDropBand.Into && targetRow is ParameterRow.GroupHeader) {
		return targetRow.groupId to null
	}

	val tree = puppet.materializedParameterTree()
	val primaryRef = primaryRefOf(targetRow)
	val location = locate(tree, primaryRef) ?: return null to null
	val (parentGroupId, siblings, index) = location

	return when (band) {
		RowDropBand.Before -> {
			parentGroupId to primaryRef
		}

		RowDropBand.After -> {
			val span = if (targetRow is ParameterRow.Pair2D) 2 else 1
			// The move detaches the subject before it inserts, so an anchor on the subject itself would not be
			// found and would append; the anchor is the first sibling past the target the move leaves standing.
			val anchor = siblings.drop(index + span).firstOrNull { node -> !subject.includes(node) }?.let { node -> refOf(node) }
			parentGroupId to anchor
		}

		RowDropBand.Into -> {
			parentGroupId to primaryRef
		} // a non-group Into never reaches here.
	}
}

/**
 * Where a drop lands, as the move the document takes.
 *
 * @property ParameterGroupId? newParentGroupId The group the row ends up in, or null for the root.
 * @property ParameterNodeRef? before The sibling the row is put ahead of, or null to put it last.
 * @property ParameterGroupId? expandsGroupId The group to open so the dropped row can be seen, which is
 *   the target of a drop that nests; null for a drop that reorders.
 */
internal data class ParameterDrop(
	val newParentGroupId: ParameterGroupId?,
	val before: ParameterNodeRef?,
	val expandsGroupId: ParameterGroupId?,
)

/**
 * Resolves a release over the row keyed [targetKey] into the move it asks for, or null when the release
 * asks for none: the row is not in the list any more, or the dragged row may not land there.
 *
 * @param PuppetModel          puppet    The open model, whose tree resolves the anchor.
 * @param List                 rows      The current render rows.
 * @param ParameterMoveSubject subject   What is being dragged.
 * @param String               targetKey The key of the row the pointer was released over.
 * @param Float                fraction  How far down that row the pointer was, 0 at its top edge.
 * @return ParameterDrop? The move, or null for a release that drops nothing.
 */
internal fun resolveParameterDrop(
	puppet: PuppetModel,
	rows: List<ParameterRow>,
	subject: ParameterMoveSubject,
	targetKey: String,
	fraction: Float,
): ParameterDrop? {
	val targetRow = rows.firstOrNull { candidate -> rowKey(candidate) == targetKey } ?: return null
	val band = parameterDropBandFor(subject, targetRow, targetRow.depth == 0, fraction) ?: return null
	val (newParentGroupId, before) = parameterDropAnchor(puppet, subject, targetRow, band)
	val expandsGroupId =
		if (band == RowDropBand.Into && targetRow is ParameterRow.GroupHeader) {
			targetRow.groupId
		} else {
			null
		}

	return ParameterDrop(newParentGroupId, before, expandsGroupId)
}

/**
 * The node ref that anchors [targetRow]: a group by its id, a slider / pad by its leading leaf.
 *
 * @param ParameterRow targetRow The row under the pointer.
 * @return ParameterNodeRef The anchoring ref.
 */
private fun primaryRefOf(targetRow: ParameterRow): ParameterNodeRef =
	when (targetRow) {
		is ParameterRow.GroupHeader -> ParameterNodeRef.Group(targetRow.groupId)
		is ParameterRow.Single -> ParameterNodeRef.Leaf(targetRow.parameter.id)
		is ParameterRow.Pair2D -> ParameterNodeRef.Leaf(targetRow.horizontal.id)
	}

/**
 * The ref naming a tree node.
 *
 * @param ParameterNode node The tree node.
 * @return ParameterNodeRef The ref naming it.
 */
private fun refOf(node: ParameterNode): ParameterNodeRef =
	when (node) {
		is ParameterNode.Param -> ParameterNodeRef.Leaf(node.id)
		is ParameterNode.Group -> ParameterNodeRef.Group(node.id)
	}

/**
 * True when [node] is part of what this subject moves.
 *
 * @param ParameterNode node The tree node.
 * @return Boolean True when the move detaches the node.
 */
private fun ParameterMoveSubject.includes(node: ParameterNode): Boolean =
	when (this) {
		is ParameterMoveSubject.Leaves -> node is ParameterNode.Param && node.id in ids
		is ParameterMoveSubject.Group -> node is ParameterNode.Group && node.id == id
	}

/**
 * True when this node is the one [ref] names.
 *
 * @param ParameterNodeRef ref The ref to test against.
 * @return Boolean True when the ref names this node.
 */
private fun ParameterNode.matches(ref: ParameterNodeRef): Boolean =
	when {
		this is ParameterNode.Param && ref is ParameterNodeRef.Leaf -> id == ref.id
		this is ParameterNode.Group && ref is ParameterNodeRef.Group -> id == ref.id
		else -> false
	}

/**
 * The sibling list and index of the node [ref] names, with its parent group id (null at root).
 *
 * @param List<ParameterNode> tree The parameter tree to search.
 * @param ParameterNodeRef ref The node to find.
 * @return Triple? The parent group id, the sibling list, and the index, or null when [ref] is not in the tree.
 */
private fun locate(tree: List<ParameterNode>, ref: ParameterNodeRef): Triple<ParameterGroupId?, List<ParameterNode>, Int>? {
	val rootIndex = tree.indexOfFirst { node -> node.matches(ref) }

	if (rootIndex >= 0) {
		return Triple(null, tree, rootIndex)
	}

	for (node in tree) {
		if (node is ParameterNode.Group) {
			val childIndex = node.children.indexOfFirst { child -> child.matches(ref) }

			if (childIndex >= 0) {
				return Triple(node.id, node.children, childIndex)
			}
		}
	}

	return null
}