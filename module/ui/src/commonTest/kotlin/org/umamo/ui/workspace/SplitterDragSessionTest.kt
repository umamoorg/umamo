package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the drag-session accumulation over the pure boundary-drag math: deltas fold into one
 * running total applied to the drag-start snapshot (so deltas arriving faster than recomposition can
 * never be lost), overshoot past the clamp retraces exactly, and ownership tracking tells the
 * session's own publishes apart from a foreign mid-drag rewrite.
 */
class SplitterDragSessionTest {
	private val tolerance = 1e-3f

	/** A leaf hosting an arbitrary space; the id names the panel in assertions. */
	private fun leaf(id: String): LeafArea = LeafArea(id = id, space = SpaceKind.Outliner)

	/** A horizontal split (children side by side, the drag axis for every test tree). */
	private fun hsplit(ratio: Float, first: AreaNode, second: AreaNode): SplitNode =
		SplitNode(SplitOrientation.Horizontal, ratio, first, second)

	/**
	 * The pixel extent of the named leaf inside [node], resolving nested weights against [axisPx]
	 * exactly the way SplitContainer's weighted Row does.  Null when the leaf is not in the subtree.
	 *
	 * @param AreaNode node The subtree to search.
	 * @param String leafId The leaf whose on-axis extent is wanted.
	 * @param Float axisPx The subtree's extent along the drag axis, in pixels.
	 * @param Float splitterPx The divider bar thickness, in pixels.
	 * @return Float? The leaf's extent, or null when absent.
	 */
	private fun leafExtentPx(node: AreaNode, leafId: String, axisPx: Float, splitterPx: Float): Float? =
		when (node) {
			is LeafArea -> if (node.id == leafId) axisPx else null
			is SplitNode -> {
				val clampedRatio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
				val usablePx = axisPx - splitterPx
				leafExtentPx(node.first, leafId, clampedRatio * usablePx, splitterPx)
					?: leafExtentPx(node.second, leafId, (1f - clampedRatio) * usablePx, splitterPx)
			}
		}

	/** Three accumulated deltas land on the same tree as one dragSplitBoundary call with their sum. */
	@Test
	fun accumulationMatchesSingleTotalDrag() {
		val start = hsplit(0.5f, leaf("a"), leaf("b"))
		val session = SplitterDragSession(start, axisPx = 1000f, minPx = 50f, splitterPx = 0f)
		session.accumulate(10f)
		session.accumulate(10f)
		val accumulated = session.accumulate(10f)
		val direct = dragSplitBoundary(start, dragPx = 30f, axisPx = 1000f, minPx = 50f, splitterPx = 0f)
		assertEquals(direct.ratio, accumulated.ratio, tolerance)
	}

	/**
	 * Dragging far past the min clamp and then all the way back restores the starting extents - the
	 * property incremental per-delta application loses (a clamped-away delta would never be paid back).
	 */
	@Test
	fun overshootRetracesExactly() {
		val start = hsplit(0.5f, leaf("a"), leaf("b"))
		val session = SplitterDragSession(start, axisPx = 1000f, minPx = 100f, splitterPx = 0f)
		val overshot = session.accumulate(1000f)
		assertEquals(0.9f, overshot.ratio, tolerance)
		val retraced = session.accumulate(-1000f)
		assertEquals(start.ratio, retraced.ratio, tolerance)
		assertEquals(500f, leafExtentPx(retraced, "a", 1000f, 0f)!!, tolerance)
		assertEquals(500f, leafExtentPx(retraced, "b", 1000f, 0f)!!, tolerance)
	}

	/**
	 * The session recognizes the snapshot and EVERY node it has published - recomposition may lag
	 * several publishes behind the pointer, so any of them can be the currently composed node.
	 */
	@Test
	fun ownsNodeAcceptsSnapshotAndEveryPublish() {
		val start = hsplit(0.5f, leaf("a"), leaf("b"))
		val session = SplitterDragSession(start, axisPx = 1000f, minPx = 50f, splitterPx = 0f)
		val firstPublish = session.accumulate(10f)
		val secondPublish = session.accumulate(10f)
		assertTrue(session.ownsNode(start))
		assertTrue(session.ownsNode(firstPublish))
		assertTrue(session.ownsNode(secondPublish))
	}

	/**
	 * A structurally identical but distinct instance is NOT owned - it means something else rewrote
	 * the tree mid-drag and the caller must rebase.  Ownership is identity, not equality, because the
	 * publishes are the exact instances handed to onNodeChange.
	 */
	@Test
	fun ownsNodeRejectsForeignRewrite() {
		val start = hsplit(0.5f, leaf("a"), leaf("b"))
		val session = SplitterDragSession(start, axisPx = 1000f, minPx = 50f, splitterPx = 0f)
		session.accumulate(10f)
		val foreign = start.copy()
		assertFalse(session.ownsNode(foreign))
	}

	/**
	 * Accumulated deltas on the deep spine (((a | b) | c) | d) land exactly where one direct drag of
	 * the total does: far panels stay pixel-invariant and only the adjacent panel absorbs.
	 */
	@Test
	fun deepSpineAccumulation() {
		val start = hsplit(0.5f, hsplit(0.5f, hsplit(0.5f, leaf("a"), leaf("b")), leaf("c")), leaf("d"))
		val session = SplitterDragSession(start, axisPx = 1000f, minPx = 48f, splitterPx = 0f)
		session.accumulate(40f)
		session.accumulate(35f)
		val accumulated = session.accumulate(25f)
		assertEquals(125f, leafExtentPx(accumulated, "a", 1000f, 0f)!!, tolerance)
		assertEquals(125f, leafExtentPx(accumulated, "b", 1000f, 0f)!!, tolerance)
		assertEquals(350f, leafExtentPx(accumulated, "c", 1000f, 0f)!!, tolerance)
		assertEquals(400f, leafExtentPx(accumulated, "d", 1000f, 0f)!!, tolerance)
	}
}
