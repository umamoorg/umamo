package org.umamo.ui.workspace

/**
 * Accumulates one splitter drag against a snapshot of the dragged split taken when the drag began.
 * Every delta rewrites from that snapshot with the RUNNING TOTAL of drag pixels, never from the
 * currently composed node: pointer deltas can arrive faster than recomposition commits, and a
 * per-delta rewrite of the composed node would apply each of them to the same stale tree, silently
 * discarding all but the last delta per frame (the divider then crawls behind the pointer on a slow
 * machine until the pointer walks off the bar).  Rewriting snapshot + total also makes the clamp
 * exact: dragging past the minimum and back retraces to the starting extents, which incremental
 * application cannot do once a delta has been clamped away.
 *
 * The geometry inputs (axis length, minimum panel size, splitter thickness) are frozen at drag
 * start on purpose: only an OS-driven window resize can change them mid-hold, and the next drag
 * re-measures.
 *
 * スプリッターの 1 回のドラッグを、開始時に取ったスナップショットに対して累積する。各デルタは合成中の
 * ノードではなく、スナップショット＋累計ピクセルから書き換える（フレーム間のデルタ欠落を防ぐ）。
 *
 * @property SplitNode startNode The dragged split as it was when the drag began.
 * @property Float axisPx The split's measured length along the drag axis, in pixels.
 * @property Float minPx The minimum size the divider-adjacent panels may shrink to, in pixels.
 * @property Float splitterPx The divider bar's own thickness, in pixels.
 */
internal class SplitterDragSession(
	private val startNode: SplitNode,
	private val axisPx: Float,
	private val minPx: Float,
	private val splitterPx: Float,
) {
	private var totalDragPx: Float = 0f

	// Every node this session has published, oldest first.  Recomposition can lag several publishes
	// behind the pointer, so the composed node handed back to ownsNode may legitimately be ANY
	// instance this session ever produced, not just the latest.
	private val publishedNodes = ArrayList<SplitNode>()

	/**
	 * Folds one drag delta into the running total and rewrites the snapshot with it.  The returned
	 * node is recorded so [ownsNode] can later recognize it as this session's own publish.
	 *
	 * @param Float deltaPx The pointer delta along the split axis, in pixels.
	 * @return SplitNode The snapshot rewritten by the accumulated drag.
	 */
	fun accumulate(deltaPx: Float): SplitNode {
		totalDragPx += deltaPx
		val rewritten = dragSplitBoundary(startNode, totalDragPx, axisPx, minPx, splitterPx)
		publishedNodes.add(rewritten)
		return rewritten
	}

	/**
	 * Whether [composedNode] is this session's own work: the drag-start snapshot or any node this
	 * session has published.  A false answer means something else rewrote the tree mid-drag (a
	 * keyboard structural edit) and the caller must rebase a fresh session on the live node.
	 * Compares by identity - the publishes are the exact instances handed to onNodeChange.  On a
	 * match, every older publish is pruned (composition has moved past them; they cannot reappear).
	 *
	 * @param AreaNode composedNode The node the composition currently holds for this split.
	 * @return Boolean True when the composed node originated from this session.
	 */
	fun ownsNode(composedNode: AreaNode): Boolean {
		if (composedNode === startNode) {
			return true
		}
		val matchIndex = publishedNodes.indexOfFirst { published -> published === composedNode }
		if (matchIndex < 0) {
			return false
		}
		if (matchIndex > 0) {
			publishedNodes.subList(0, matchIndex).clear()
		}
		return true
	}
}
