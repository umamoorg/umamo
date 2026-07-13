package org.umamo.ui.workspace

/**
 * A structural edit to an area tree, addressed by the target leaf's id. These are the named commands
 * the area header (and a future keymap/palette) issue; keeping them as data objects routed through
 * one pure [reduce] choke point means the mutation logic is centralized and unit-testable, and the
 * action registry can drive them by name without the header touching the tree directly.
 *
 * Ratio drags are deliberately NOT here: a splitter rewrites its own subtree (dragSplitBoundary) and
 * threads the result up via the layout callback, so it never needs an id lookup.
 *
 * エリアツリーへの構造的編集（対象は葉の id）。ヘッダ等が発行する名前付きコマンド。純粋な reduce に集約。
 *
 * @property String areaId The leaf the command targets.
 */
sealed interface AreaCommand {
	val areaId: String

	/**
	 * Split the target leaf in two along [orientation] at [ratio] (the original side's fraction of the
	 * axis, 0..1, defaulting to an even 50/50 split); the new sibling inherits the leaf's space.
	 */
	data class SplitArea(
		override val areaId: String,
		val orientation: SplitOrientation,
		val ratio: Float = 0.5f,
	) : AreaCommand

	/** Close the target leaf, collapsing its parent split into the surviving sibling. */
	data class CloseArea(override val areaId: String) : AreaCommand

	/** Switch the target leaf to a different editor [kind], keeping its id. */
	data class SwitchSpace(override val areaId: String, val kind: SpaceKind) : AreaCommand

	/**
	 * Join two direct-sibling leaves: [survivorId] consumes its sibling [consumedId], collapsing their
	 * shared parent split into the survivor.  A no-op unless the two are direct sibling leaves.  The
	 * command targets the surviving leaf, so [areaId] is [survivorId].
	 */
	data class JoinAreas(val survivorId: String, val consumedId: String) : AreaCommand {
		override val areaId: String get() = survivorId
	}

	/**
	 * Dock a leaf as a full-span strip on one [edge] of the whole workspace: the leaf is lifted out of its
	 * current slot (which heals - its parent split collapses into the sibling) and re-inserted spanning the
	 * given edge of the root, taking [ratio] of the perpendicular axis.  Area count is preserved (the leaf
	 * moves, nothing is consumed); a no-op if the leaf is the workspace's sole area.  Used for the
	 * non-aligned drop, where a clean sibling join is impossible.
	 */
	data class DockArea(val sourceId: String, val edge: DockEdge, val ratio: Float) : AreaCommand {
		override val areaId: String get() = sourceId
	}
}

/**
 * Which edge of the workspace a [AreaCommand.DockArea] docks its leaf against.  Bottom/Top dock a
 * full-width strip; Left/Right dock a full-height one.
 *
 * ドック先のワークスペース端。Bottom/Top は横幅いっぱい、Left/Right は縦高いっぱいの帯。
 */
enum class DockEdge {
	Top,
	Bottom,
	Left,
	Right,
}

/**
 * Applies a structural [command] to [root], returning the new tree (the input is never mutated -
 * data classes + copy). Unknown ids are no-ops (the tree returns unchanged), so a stale command from
 * a closed area fails safely.
 *
 * 構造的コマンドを root に適用し、新しいツリーを返す（入力は不変）。未知の id は無操作。
 *
 * @param AreaNode root The tree to edit.
 * @param AreaCommand command The structural edit to apply.
 * @return AreaNode The resulting tree.
 */
fun reduce(root: AreaNode, command: AreaCommand): AreaNode =
	when (command) {
		is AreaCommand.SplitArea ->
			mapLeaf(root, command.areaId) { leaf ->
				SplitNode(
					orientation = command.orientation,
					ratio = command.ratio,
					first = leaf,
					second = LeafArea(newAreaId(), leaf.space),
				)
			}
		is AreaCommand.SwitchSpace ->
			mapLeaf(root, command.areaId) { leaf -> leaf.copy(space = command.kind) }
		is AreaCommand.CloseArea -> closeArea(root, command.areaId)
		is AreaCommand.JoinAreas ->
			// removeLeaf collapses a split to its OTHER child, so removing the consumed sibling promotes
			// the survivor - but only valid when the two really are direct siblings, else a no-op.
			if (command.survivorId != command.consumedId &&
				areDirectSiblings(root, command.survivorId, command.consumedId)
			) {
				removeLeaf(root, command.consumedId) ?: root
			} else {
				root
			}
		is AreaCommand.DockArea -> dockArea(root, command.sourceId, command.edge, command.ratio)
	}

/**
 * Lifts the leaf [sourceId] out of its slot (its parent split collapses into the sibling) and re-inserts
 * it as a full-span strip on [edge] of the whole workspace, the strip taking [ratio] of the perpendicular
 * axis.  A no-op when the leaf is missing or is the workspace's sole area (nothing to dock against).
 *
 * 葉を現在の位置から外し（親分割は兄弟に畳む）、ワークスペース端の帯として再挿入する。
 *
 * @param AreaNode root The tree to edit.
 * @param String sourceId The leaf to dock.
 * @param DockEdge edge The workspace edge to dock against.
 * @param Float ratio The docked strip's fraction of the perpendicular axis.
 * @return AreaNode The restructured tree, or [root] unchanged on a no-op.
 */
private fun dockArea(root: AreaNode, sourceId: String, edge: DockEdge, ratio: Float): AreaNode {
	val sourceLeaf = findLeaf(root, sourceId)
	val remainder = removeLeaf(root, sourceId)
	if (sourceLeaf == null || remainder == null) {
		return root
	}
	val stripRatio = ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	return when (edge) {
		// The strip is the first child for Top/Left and the second for Bottom/Right, so the ratio (always
		// the leading child's fraction) is the strip's share where it leads and the complement otherwise.
		DockEdge.Top -> SplitNode(SplitOrientation.Vertical, stripRatio, sourceLeaf, remainder)
		DockEdge.Bottom -> SplitNode(SplitOrientation.Vertical, 1f - stripRatio, remainder, sourceLeaf)
		DockEdge.Left -> SplitNode(SplitOrientation.Horizontal, stripRatio, sourceLeaf, remainder)
		DockEdge.Right -> SplitNode(SplitOrientation.Horizontal, 1f - stripRatio, remainder, sourceLeaf)
	}
}

/**
 * Finds the leaf with id [areaId] anywhere in [node], or null if absent.
 *
 * @param AreaNode node The subtree to search.
 * @param String areaId The leaf id to find.
 * @return LeafArea? The matching leaf, or null.
 */
private fun findLeaf(node: AreaNode, areaId: String): LeafArea? =
	when (node) {
		is LeafArea -> if (node.id == areaId) node else null
		is SplitNode -> findLeaf(node.first, areaId) ?: findLeaf(node.second, areaId)
	}

/**
 * Reports whether [firstId] and [secondId] are both leaves and the two direct children of one
 * [SplitNode] - i.e. they share a full edge and can be joined by collapsing their parent split.
 * Recurses the whole tree looking for such a parent.
 *
 * firstId と secondId が同一 SplitNode の 2 つの直接の子（葉）かどうかを返す。
 *
 * @param AreaNode node The subtree to search.
 * @param String firstId One leaf id.
 * @param String secondId The other leaf id.
 * @return Boolean True when the two are direct sibling leaves somewhere in [node].
 */
internal fun areDirectSiblings(node: AreaNode, firstId: String, secondId: String): Boolean =
	when (node) {
		is LeafArea -> false
		is SplitNode -> {
			val firstChild = node.first
			val secondChild = node.second
			val matchedHere =
				firstChild is LeafArea &&
					secondChild is LeafArea &&
					(
						(firstChild.id == firstId && secondChild.id == secondId) ||
							(firstChild.id == secondId && secondChild.id == firstId)
					)
			matchedHere ||
				areDirectSiblings(firstChild, firstId, secondId) ||
				areDirectSiblings(secondChild, firstId, secondId)
		}
	}

/**
 * Returns a copy of [node] with the leaf whose id is [areaId] replaced by [transform]'s result;
 * recurses into splits. Leaves that don't match (and the whole tree, if none matches) come back
 * structurally unchanged.
 *
 * id 一致の葉を transform で置換したコピーを返す。分割は再帰。一致が無ければ不変。
 *
 * @param AreaNode node The subtree to walk.
 * @param String areaId The leaf id to replace.
 * @param Function transform Maps the matched leaf to its replacement node.
 * @return AreaNode The rewritten subtree.
 */
private fun mapLeaf(node: AreaNode, areaId: String, transform: (LeafArea) -> AreaNode): AreaNode =
	when (node) {
		is LeafArea ->
			if (node.id == areaId) {
				transform(node)
			} else {
				node
			}
		is SplitNode ->
			node.copy(
				first = mapLeaf(node.first, areaId, transform),
				second = mapLeaf(node.second, areaId, transform),
			)
	}

/**
 * Closes the leaf [areaId] by collapsing its parent split into the surviving sibling. Closing the
 * sole leaf of a workspace is refused (returns the tree unchanged) - a workspace must always have at
 * least one area.
 *
 * 親分割を生き残る兄弟に畳んで葉を閉じる。ワークスペース唯一の葉は閉じない（不変を返す）。
 *
 * @param AreaNode root The tree to edit.
 * @param String areaId The leaf id to close.
 * @return AreaNode The tree with the leaf removed (or unchanged if it is the sole area / not found).
 */
private fun closeArea(root: AreaNode, areaId: String): AreaNode {
	if (root is LeafArea) {
		return root
	}
	return removeLeaf(root, areaId) ?: root
}

/**
 * Recursively removes the leaf [areaId] from a subtree, returning the rewritten subtree, or null when
 * the leaf is not in this subtree (the caller uses null to mean "keep looking / not here"). When a
 * split's direct child is the target, the split collapses to its other child.
 *
 * 部分木から葉を再帰的に取り除き、書き換えた部分木を返す。ここに無ければ null。
 *
 * @param node The subtree to search.
 * @param areaId The leaf id to remove.
 * @return AreaNode? The rewritten subtree, or null if the leaf was not found here.
 */
private fun removeLeaf(node: AreaNode, areaId: String): AreaNode? =
	when (node) {
		is LeafArea -> null
		is SplitNode -> {
			val firstChild = node.first
			val secondChild = node.second
			when {
				firstChild is LeafArea && firstChild.id == areaId -> secondChild
				secondChild is LeafArea && secondChild.id == areaId -> firstChild
				else -> {
					val rewrittenFirst = removeLeaf(firstChild, areaId)
					if (rewrittenFirst != null) {
						node.copy(first = rewrittenFirst)
					} else {
						val rewrittenSecond = removeLeaf(secondChild, areaId)
						if (rewrittenSecond != null) {
							node.copy(second = rewrittenSecond)
						} else {
							null
						}
					}
				}
			}
		}
	}
