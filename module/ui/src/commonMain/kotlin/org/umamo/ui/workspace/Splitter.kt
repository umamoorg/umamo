package org.umamo.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import kotlin.math.max
import kotlin.math.min

/** The draggable thickness of a splitter bar. */
internal val SPLITTER_THICKNESS = 4.dp

/** The minimum on-axis size either child of a split may shrink to under a drag. */
internal val MIN_AREA_DP = 48.dp

/**
 * The hover cursor for a splitter of the given orientation - a horizontal-resize arrow for a
 * Horizontal split (children side by side, dragged left/right) and a vertical-resize arrow for a
 * Vertical split. expect/actual because the system resize cursors are a desktop (java.awt) concept;
 * touch platforms have no pointer and return the default.
 *
 * 指定向きのスプリッタのホバーカーソル（左右分割は横リサイズ、上下分割は縦リサイズ）。
 *
 * @param SplitOrientation orientation The split's orientation.
 * @return PointerIcon The resize cursor to show on hover.
 */
expect fun splitterPointerIcon(orientation: SplitOrientation): PointerIcon

/**
 * Which end of a subtree touches the divider being dragged: the divider sits after a Trailing-edge
 * subtree (the dragged split's first child) and before a Leading-edge one (its second child).
 */
internal enum class SpineEdge {
	Trailing,
	Leading,
}

/**
 * Rewrites a split's subtree after a pointer drag on its own divider so that ONLY the two panels
 * adjacent to the divider resize - every other panel keeps its pixel extent. A plain drag would just
 * move this node's ratio, which rescales an entire child subtree as a unit (dragging the divider next
 * to a three-column group visibly moves the far column); instead the drag delta telescopes down each
 * child's divider-adjacent spine of same-orientation splits, counter-adjusting every ratio along it
 * (see resizeEdgePanel). Pure (no Compose) so the pixels↔ratio math and the clamping are unit-tested
 * directly - this is the fiddliest bit of the layout math.
 *
 * The drag is clamped BEFORE rewriting (boundaryDragBounds) so no adjacent panel shrinks below
 * [minPx] and no stored ratio leaves the render clamp band [MIN_RATIO, 1 - MIN_RATIO] - a stored
 * ratio outside the band would be silently re-clamped at render time and break the pixel invariance.
 * A non-positive [axisPx] (first frame, not yet measured), an empty clamp interval (degenerate
 * geometry), or a fully clamped-away drag returns the node unchanged.
 *
 * 仕切りドラッグ後の部分木を、仕切りに隣接する 2 パネルだけが伸縮するよう書き換える純粋関数。
 * 差分は同方向スプリットの背骨に沿って各比を補正しながら隣接パネルまで伝わる。
 *
 * @param SplitNode node The split whose divider was dragged.
 * @param Float dragPx The drag delta along the split axis, in pixels (positive grows the first child).
 * @param Float axisPx The node's measured length along the split axis, in pixels.
 * @param Float minPx The minimum size the divider-adjacent panels may shrink to, in pixels.
 * @param Float splitterPx The divider bar's own thickness, in pixels (a fixed sibling of the two
 *        weighted children, so each split distributes axisPx minus splitterPx).
 * @return SplitNode The rewritten split, or the same instance when the drag is a no-op.
 */
fun dragSplitBoundary(node: SplitNode, dragPx: Float, axisPx: Float, minPx: Float, splitterPx: Float): SplitNode {
	val usablePx = axisPx - splitterPx
	if (axisPx <= 0f || usablePx <= 0f) {
		return node
	}
	val dragBounds = boundaryDragBounds(node, axisPx, minPx, splitterPx)
	if (dragBounds.isEmpty()) {
		return node
	}
	val clampedDragPx = dragPx.coerceIn(dragBounds.start, dragBounds.endInclusive)
	if (clampedDragPx == 0f) {
		return node
	}
	val clampedRatio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	val firstExtentPx = clampedRatio * usablePx
	val secondExtentPx = usablePx - firstExtentPx
	return node.copy(
		ratio = (firstExtentPx + clampedDragPx) / usablePx,
		first = resizeEdgePanel(node.first, node.orientation, firstExtentPx, clampedDragPx, splitterPx, SpineEdge.Trailing),
		second = resizeEdgePanel(node.second, node.orientation, secondExtentPx, -clampedDragPx, splitterPx, SpineEdge.Leading),
	)
}

/**
 * The pixel extent of the divider-adjacent terminal panel inside [node]. Walks the [edge] spine of
 * same-orientation splits (Trailing follows second children, Leading follows first children); a leaf
 * or a cross-orientation split terminates the walk, because a cross-axis subtree resizes as one unit
 * along the drag axis (its children fill the drag axis) and so absorbs the delta exactly like a leaf.
 *
 * @param AreaNode node The subtree on one side of the dragged divider.
 * @param SplitOrientation orientation The dragged split's orientation (the drag axis).
 * @param Float axisPx The subtree's current extent along the drag axis, in pixels.
 * @param Float splitterPx The divider bar thickness, in pixels.
 * @param SpineEdge edge Which end of the subtree touches the dragged divider.
 * @return Float The adjacent terminal panel's extent along the drag axis, in pixels.
 */
internal fun edgePanelSizePx(node: AreaNode, orientation: SplitOrientation, axisPx: Float, splitterPx: Float, edge: SpineEdge): Float {
	if (node !is SplitNode || node.orientation != orientation) {
		return axisPx
	}
	val clampedRatio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	val usablePx = axisPx - splitterPx
	return when (edge) {
		SpineEdge.Trailing -> edgePanelSizePx(node.second, orientation, (1f - clampedRatio) * usablePx, splitterPx, edge)
		SpineEdge.Leading -> edgePanelSizePx(node.first, orientation, clampedRatio * usablePx, splitterPx, edge)
	}
}

/**
 * The allowed drag interval for [node]'s divider: the intersection of the adjacent terminal panels'
 * [minPx] bounds with, for the node itself and every spine split the compensation will rewrite, the
 * bound keeping its new ratio inside the render clamp band [MIN_RATIO, 1 - MIN_RATIO]. Only these
 * nodes' ratios change, so only they constrain the drag; interior panels are pixel-invariant by
 * construction. May be empty when the geometry is degenerate (extents already below the minimums).
 *
 * @param SplitNode node The split whose divider is being dragged.
 * @param Float axisPx The node's measured length along the split axis, in pixels.
 * @param Float minPx The minimum size the divider-adjacent panels may shrink to, in pixels.
 * @param Float splitterPx The divider bar thickness, in pixels.
 * @return ClosedFloatingPointRange The inclusive drag bounds, in pixels.
 */
internal fun boundaryDragBounds(node: SplitNode, axisPx: Float, minPx: Float, splitterPx: Float): ClosedFloatingPointRange<Float> {
	val usablePx = axisPx - splitterPx
	val clampedRatio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	val firstExtentPx = clampedRatio * usablePx
	val secondExtentPx = usablePx - firstExtentPx
	// The dragged node's own new ratio (firstExtentPx + drag) / usablePx must stay in the band.
	var lowerBoundPx = MIN_RATIO * usablePx - firstExtentPx
	var upperBoundPx = (1f - MIN_RATIO) * usablePx - firstExtentPx
	// The divider-adjacent terminal panels must keep at least minPx.
	lowerBoundPx = max(lowerBoundPx, minPx - edgePanelSizePx(node.first, node.orientation, firstExtentPx, splitterPx, SpineEdge.Trailing))
	upperBoundPx = min(upperBoundPx, edgePanelSizePx(node.second, node.orientation, secondExtentPx, splitterPx, SpineEdge.Leading) - minPx)
	// First-side spine: each node's extent grows by the drag, its new ratio is s*u / (u + drag).
	var spineNode: AreaNode = node.first
	var spineExtentPx = firstExtentPx
	while (spineNode is SplitNode && spineNode.orientation == node.orientation) {
		val spineUsablePx = spineExtentPx - splitterPx
		val spineRatio = spineNode.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
		lowerBoundPx = max(lowerBoundPx, spineRatio * spineUsablePx / (1f - MIN_RATIO) - spineUsablePx)
		upperBoundPx = min(upperBoundPx, spineRatio * spineUsablePx / MIN_RATIO - spineUsablePx)
		spineExtentPx = (1f - spineRatio) * spineUsablePx
		spineNode = spineNode.second
	}
	// Second-side spine: each node's extent shrinks by the drag, its new ratio is
	// (s*u - drag) / (u - drag).
	spineNode = node.second
	spineExtentPx = secondExtentPx
	while (spineNode is SplitNode && spineNode.orientation == node.orientation) {
		val spineUsablePx = spineExtentPx - splitterPx
		val spineRatio = spineNode.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
		lowerBoundPx = max(lowerBoundPx, spineUsablePx * (spineRatio - (1f - MIN_RATIO)) / MIN_RATIO)
		upperBoundPx = min(upperBoundPx, spineUsablePx * (spineRatio - MIN_RATIO) / (1f - MIN_RATIO))
		spineExtentPx = spineRatio * spineUsablePx
		spineNode = spineNode.first
	}
	return lowerBoundPx..upperBoundPx
}

/**
 * Rewrites the ratios along [node]'s [edge] spine so that only the divider-adjacent terminal panel
 * absorbs [deltaPx] while every other panel in the subtree keeps its pixel extent. At each spine
 * split the far child's extent is held invariant - new ratio times new usable equals old extent
 * exactly - so the delta telescopes down to the terminal. A leaf or a cross-orientation split ends
 * the recursion unchanged (it absorbs the delta as a unit).
 *
 * @param AreaNode node The subtree on one side of the dragged divider.
 * @param SplitOrientation orientation The dragged split's orientation (the drag axis).
 * @param Float axisPx The subtree's current extent along the drag axis, in pixels.
 * @param Float deltaPx This subtree's own extent change, in pixels (the clamped drag, sign-adjusted
 *        by the caller: positive on the growing side, negative on the shrinking side).
 * @param Float splitterPx The divider bar thickness, in pixels.
 * @param SpineEdge edge Which end of the subtree touches the dragged divider.
 * @return AreaNode The rewritten subtree, or the same instance when nothing inside changes.
 */
private fun resizeEdgePanel(node: AreaNode, orientation: SplitOrientation, axisPx: Float, deltaPx: Float, splitterPx: Float, edge: SpineEdge): AreaNode {
	if (node !is SplitNode || node.orientation != orientation) {
		return node
	}
	val usablePx = axisPx - splitterPx
	val newUsablePx = usablePx + deltaPx
	val clampedRatio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	val firstExtentPx = clampedRatio * usablePx
	return when (edge) {
		SpineEdge.Trailing ->
			node.copy(
				ratio = firstExtentPx / newUsablePx,
				second = resizeEdgePanel(node.second, orientation, usablePx - firstExtentPx, deltaPx, splitterPx, edge),
			)
		SpineEdge.Leading ->
			node.copy(
				ratio = (firstExtentPx + deltaPx) / newUsablePx,
				first = resizeEdgePanel(node.first, orientation, firstExtentPx, deltaPx, splitterPx, edge),
			)
	}
}

/**
 * The draggable divider between a split's two children. Dragging it reports the pixel delta along the
 * split axis via [onDragByPx]; the parent (SplitContainer) rewrites its subtree with
 * [dragSplitBoundary]. Orientation picks both the bar's long axis and the drag axis: a Horizontal
 * split (children side by side) gets a vertical bar dragged horizontally; a Vertical split gets a
 * horizontal bar dragged vertically.
 *
 * [onDragByPx] is wrapped in rememberUpdatedState so each drag delta uses the latest ratio even though
 * the DraggableState itself is remembered once (avoids a stale-closure drag).
 *
 * 分割の 2 子の間のドラッグ可能な仕切り。ドラッグ量（px）を onDragByPx で親に伝える。
 *
 * @param SplitOrientation orientation The parent split's orientation.
 * @param Function onDragByPx Receives the drag delta in pixels along the split axis.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun Splitter(orientation: SplitOrientation, onDragByPx: (Float) -> Unit, modifier: Modifier = Modifier) {
	val latestOnDrag by rememberUpdatedState(onDragByPx)
	val dragState = remember { DraggableState { deltaPx -> latestOnDrag(deltaPx) } }
	val colors = LocalUmamoColors.current
	val bar =
		when (orientation) {
			SplitOrientation.Horizontal -> Modifier.width(SPLITTER_THICKNESS).fillMaxHeight()
			SplitOrientation.Vertical -> Modifier.height(SPLITTER_THICKNESS).fillMaxWidth()
		}
	val dragOrientation =
		when (orientation) {
			SplitOrientation.Horizontal -> Orientation.Horizontal
			SplitOrientation.Vertical -> Orientation.Vertical
		}
	Box(
		modifier =
			modifier
				.then(bar)
				.pointerHoverIcon(splitterPointerIcon(orientation))
				.draggable(state = dragState, orientation = dragOrientation)
				.background(colors.divider),
	)
}
