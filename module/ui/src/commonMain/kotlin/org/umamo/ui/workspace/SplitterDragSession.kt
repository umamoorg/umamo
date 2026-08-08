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

	/**
	 * True once Escape has abandoned this drag.  The pointer is very likely still held at that moment,
	 * so the session stays in place and inert rather than being discarded: clearing it would let the
	 * next delta rebase a fresh session on the live node and silently resume the drag the user just
	 * cancelled.
	 */
	var isCancelled: Boolean = false
		private set

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
	 * Abandons the drag and returns the split as it stood when the drag began, for the caller to
	 * publish - Escape's undo of an in-flight divider drag.
	 *
	 * Rewinding to the snapshot is exact rather than approximate: [accumulate] always rewrites from
	 * that same snapshot, so no incremental state has to be unwound.  The session marks itself
	 * [isCancelled] and stays alive so the deltas still arriving from the held pointer are ignored.
	 *
	 * @return SplitNode The drag-start snapshot to restore.
	 */
	fun cancel(): SplitNode {
		isCancelled = true
		totalDragPx = 0f
		// The restored snapshot is a publish like any other, so ownsNode keeps recognizing this split
		// as ours while the pointer is still down.
		publishedNodes.add(startNode)
		return startNode
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