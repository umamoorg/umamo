package org.umamo.ui.workspace.spaces

import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.ParameterNodeRef
import org.umamo.edit.RowDropBand
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

// Above / below these fractions a group-header drop reads as reorder (before / after) rather than nest.
private const val GROUP_NEST_LOW_FRACTION = 0.33f
private const val GROUP_NEST_HIGH_FRACTION = 0.66f

/**
 * The band a drop resolves to, or null when the pairing is illegal - one level of groups only. Parameters
 * never nest, so a slider / pad target only takes Before / After. A group header takes Into (a parameter
 * nesting inside it) plus Before / After (a root sibling), but only for a dragged parameter run - a
 * dragged group never nests, so against a group header it takes only Before / After. A dragged group may
 * reorder only among root nodes, so a non-root target refuses it. Used by both the indicator and the
 * dispatch, so the line the user sees matches the move that happens.
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

/** Before above the row's midline, After below it. */
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
 * resolved against the model's tree (a group-less model is treated as a flat root of leaves). Into
 * appends inside the group; Before anchors on the target's leading node; After anchors on the node past
 * the target's span (a pad spans its two leaves), or null (append) when the target is last in its
 * siblings.
 *
 * @param PuppetModel puppet The model whose tree is walked.
 * @param ParameterRow targetRow The row under the pointer.
 * @param RowDropBand band The resolved band.
 * @return Pair<ParameterGroupId?, ParameterNodeRef?> The destination parent (null = root) and the anchor.
 */
internal fun parameterDropAnchor(
	puppet: PuppetModel,
	targetRow: ParameterRow,
	band: RowDropBand,
): Pair<ParameterGroupId?, ParameterNodeRef?> {
	if (band == RowDropBand.Into && targetRow is ParameterRow.GroupHeader) {
		return targetRow.groupId to null
	}
	val tree = puppet.parameterTree.ifEmpty { puppet.parameters.map { parameter -> ParameterNode.Param(parameter.id) } }
	val primaryRef = primaryRefOf(targetRow)
	val location = locate(tree, primaryRef) ?: return null to null
	val (parentGroupId, siblings, index) = location
	return when (band) {
		RowDropBand.Before -> parentGroupId to primaryRef
		RowDropBand.After -> {
			val span = if (targetRow is ParameterRow.Pair2D) 2 else 1
			val afterIndex = index + span
			val anchor = siblings.getOrNull(afterIndex)?.let { node -> refOf(node) }
			parentGroupId to anchor
		}
		RowDropBand.Into -> parentGroupId to primaryRef // a non-group Into never reaches here.
	}
}

/** The node ref that anchors [targetRow]: a group by its id, a slider / pad by its leading leaf. */
private fun primaryRefOf(targetRow: ParameterRow): ParameterNodeRef =
	when (targetRow) {
		is ParameterRow.GroupHeader -> ParameterNodeRef.Group(targetRow.groupId)
		is ParameterRow.Single -> ParameterNodeRef.Leaf(targetRow.parameter.id)
		is ParameterRow.Pair2D -> ParameterNodeRef.Leaf(targetRow.horizontal.id)
	}

/** The ref naming a tree node. */
private fun refOf(node: ParameterNode): ParameterNodeRef =
	when (node) {
		is ParameterNode.Param -> ParameterNodeRef.Leaf(node.id)
		is ParameterNode.Group -> ParameterNodeRef.Group(node.id)
	}

/** True when [node] is the one [ref] names. */
private fun ParameterNode.matches(ref: ParameterNodeRef): Boolean =
	when {
		this is ParameterNode.Param && ref is ParameterNodeRef.Leaf -> id == ref.id
		this is ParameterNode.Group && ref is ParameterNodeRef.Group -> id == ref.id
		else -> false
	}

/** The sibling list and index of the node [ref] names, with its parent group id (null at root). */
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
