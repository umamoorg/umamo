package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies the pure boundary-drag math: a divider drag resizes ONLY its two adjacent panels (far
 * panels are pixel-invariant through the spine compensation), the min-size and render-ratio clamps,
 * and the no-op guards (unmeasured axis, degenerate geometry). Most cases use splitterPx = 0 for
 * readable round numbers; one case proves exactness with the real 4px bar.
 */
class SplitterTest {
	private val tolerance = 1e-3f

	/** A leaf hosting an arbitrary space; the id names the panel in assertions. */
	private fun leaf(id: String): LeafArea = LeafArea(id = id, space = SpaceKind.Outliner)

	/** A horizontal split (children side by side, the drag axis for every test tree). */
	private fun hsplit(ratio: Float, first: AreaNode, second: AreaNode): SplitNode =
		SplitNode(SplitOrientation.Horizontal, ratio, first, second)

	/**
	 * The pixel extent of the named leaf inside [node], resolving nested weights against [axisPx]
	 * exactly the way SplitContainer's weighted Row does (each split distributes its extent minus
	 * splitterPx between the two ratio-weighted children). Null when the leaf is not in the subtree.
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

	/**
	 * A drag of +100px on a 1000px leaf-pair axis moves the ratio by +0.1 - the spine walk terminates
	 * immediately at the leaves, so a lone split behaves like a simple proportional ratio move.
	 */
	@Test
	fun leafPairDragMovesRatioProportionally() {
		val node = hsplit(0.5f, leaf("a"), leaf("b"))
		val dragged = dragSplitBoundary(node, dragPx = 100f, axisPx = 1000f, minPx = 50f, splitterPx = 0f)
		assertEquals(0.6f, dragged.ratio, tolerance)
	}

	/** An overshooting drag pins the adjacent leaf at minPx on either side. */
	@Test
	fun leafPairClampsToMinOnBothSides() {
		val node = hsplit(0.5f, leaf("a"), leaf("b"))
		// minPx 100 of 1000px usable: the first leaf pins at 100px (ratio 0.1) and 900px (ratio 0.9).
		assertEquals(0.1f, dragSplitBoundary(node, -1000f, 1000f, 100f, 0f).ratio, tolerance)
		assertEquals(0.9f, dragSplitBoundary(node, 1000f, 1000f, 100f, 0f).ratio, tolerance)
	}

	/** Before the parent is measured (axisPx == 0) the node is returned untouched - no divide-by-zero. */
	@Test
	fun zeroAxisIsNoOp() {
		val node = hsplit(0.42f, leaf("a"), leaf("b"))
		assertSame(node, dragSplitBoundary(node, 50f, 0f, 10f, 0f))
	}

	/**
	 * When the minimums cannot be satisfied at all (minPx over half the axis) the drag interval is
	 * empty and the node is returned untouched rather than snapped somewhere arbitrary.
	 */
	@Test
	fun degenerateMinIsNoOp() {
		val node = hsplit(0.3f, leaf("a"), leaf("b"))
		assertSame(node, dragSplitBoundary(node, -200f, 1000f, 800f, 0f))
	}

	/**
	 * The reported bug shape: in ((left | center) | right) dragging the OUTER divider (between center
	 * and right) must not move the left column - the inner ratio counter-adjusts so center absorbs
	 * the full delta.
	 */
	@Test
	fun outerDragKeepsFarLeftColumnFixed() {
		val node = hsplit(0.5f, hsplit(0.5f, leaf("left"), leaf("center")), leaf("right"))
		val dragged = dragSplitBoundary(node, dragPx = 100f, axisPx = 1000f, minPx = 48f, splitterPx = 0f)
		assertEquals(0.6f, dragged.ratio, tolerance)
		assertEquals(250f, leafExtentPx(dragged, "left", 1000f, 0f)!!, tolerance)
		assertEquals(350f, leafExtentPx(dragged, "center", 1000f, 0f)!!, tolerance)
		assertEquals(400f, leafExtentPx(dragged, "right", 1000f, 0f)!!, tolerance)
	}

	/** The mirrored nesting (left | (center | right)): the outer drag must not move the right column. */
	@Test
	fun outerDragKeepsFarRightColumnFixed() {
		val node = hsplit(0.5f, leaf("left"), hsplit(0.5f, leaf("center"), leaf("right")))
		val dragged = dragSplitBoundary(node, dragPx = 100f, axisPx = 1000f, minPx = 48f, splitterPx = 0f)
		assertEquals(0.6f, dragged.ratio, tolerance)
		val innerSplit = dragged.second as SplitNode
		assertEquals(0.375f, innerSplit.ratio, tolerance)
		assertEquals(600f, leafExtentPx(dragged, "left", 1000f, 0f)!!, tolerance)
		assertEquals(150f, leafExtentPx(dragged, "center", 1000f, 0f)!!, tolerance)
		assertEquals(250f, leafExtentPx(dragged, "right", 1000f, 0f)!!, tolerance)
	}

	/**
	 * Three same-axis levels (((a | b) | c) | d): dragging the outermost divider compensates every
	 * spine level, so both a and b keep their pixels and only c (the adjacent panel) absorbs.
	 */
	@Test
	fun deepSpineCompensatesEveryLevel() {
		val node = hsplit(0.5f, hsplit(0.5f, hsplit(0.5f, leaf("a"), leaf("b")), leaf("c")), leaf("d"))
		val dragged = dragSplitBoundary(node, dragPx = 100f, axisPx = 1000f, minPx = 48f, splitterPx = 0f)
		assertEquals(125f, leafExtentPx(dragged, "a", 1000f, 0f)!!, tolerance)
		assertEquals(125f, leafExtentPx(dragged, "b", 1000f, 0f)!!, tolerance)
		assertEquals(350f, leafExtentPx(dragged, "c", 1000f, 0f)!!, tolerance)
		assertEquals(400f, leafExtentPx(dragged, "d", 1000f, 0f)!!, tolerance)
	}

	/**
	 * A cross-orientation subtree beside the divider ends the spine walk: it scales as one unit along
	 * the drag axis (its children fill that axis), so it is returned as the SAME instance and only the
	 * dragged node's own ratio changes.
	 */
	@Test
	fun crossOrientationSubtreeScalesAsUnit() {
		val verticalStack = SplitNode(SplitOrientation.Vertical, 0.3f, leaf("top"), leaf("bottom"))
		val node = hsplit(0.5f, verticalStack, leaf("right"))
		val dragged = dragSplitBoundary(node, dragPx = 100f, axisPx = 1000f, minPx = 48f, splitterPx = 0f)
		assertEquals(0.6f, dragged.ratio, tolerance)
		assertSame(verticalStack, dragged.first)
	}

	/**
	 * The min clamp binds on the ADJACENT panel (the inner center leaf), not on the dragged node's
	 * whole child subtree - and the far column stays pixel-invariant even at the clamp.
	 */
	@Test
	fun clampRespectsAdjacentLeafMin() {
		val node = hsplit(0.5f, hsplit(0.5f, leaf("left"), leaf("center")), leaf("right"))
		// center is 250px; dragging left by 500px is clamped to -202px so center pins at minPx 48.
		val dragged = dragSplitBoundary(node, dragPx = -500f, axisPx = 1000f, minPx = 48f, splitterPx = 0f)
		assertEquals(48f, leafExtentPx(dragged, "center", 1000f, 0f)!!, tolerance)
		assertEquals(250f, leafExtentPx(dragged, "left", 1000f, 0f)!!, tolerance)
	}

	/**
	 * On a wide axis the pixel min alone would let a stored ratio fall below MIN_RATIO; the render
	 * clamp would then silently re-clamp it and break the invariance, so the drag bound keeps every
	 * stored ratio inside [MIN_RATIO, 1 - MIN_RATIO].
	 */
	@Test
	fun clampRespectsRenderMinRatio() {
		val node = hsplit(0.5f, leaf("a"), leaf("b"))
		// minPx 48 of 10000px would allow ratio 0.0048; the band clamp stops at MIN_RATIO instead.
		val dragged = dragSplitBoundary(node, dragPx = -6000f, axisPx = 10000f, minPx = 48f, splitterPx = 0f)
		assertEquals(MIN_RATIO, dragged.ratio, tolerance)
	}

	/**
	 * With the real 4px divider bar each split distributes axisPx - 4 between its children; the
	 * compensation must use that usable extent or the far column drifts by a few pixels per drag.
	 */
	@Test
	fun splitterThicknessIsExact() {
		val splitterPx = 4f
		val node = hsplit(0.5f, hsplit(0.5f, leaf("left"), leaf("center")), leaf("right"))
		val leftBeforePx = leafExtentPx(node, "left", 1000f, splitterPx)!!
		val dragged = dragSplitBoundary(node, dragPx = 100f, axisPx = 1000f, minPx = 48f, splitterPx = splitterPx)
		assertEquals(leftBeforePx, leafExtentPx(dragged, "left", 1000f, splitterPx)!!, tolerance)
	}
}
