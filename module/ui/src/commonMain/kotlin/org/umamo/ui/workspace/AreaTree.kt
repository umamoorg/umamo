package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/** The smallest fraction either child of a split may shrink to (keeps both areas usable). */
internal const val MIN_RATIO = 0.05f

/**
 * Renders an area tree recursively: a [LeafArea] becomes an [AreaLeaf]; a [SplitNode] becomes a
 * [SplitContainer]. Two callbacks flow down: [onNodeChange] threads a rewritten subtree back up to the
 * root (used by splitter ratio drags), and [onCommand] carries structural edits (split / close /
 * switch) to the shell's reducer. A leaf is wrapped in `key(id)` so its composition identity follows
 * the stable area id, not its position - the basis for keeping a hosted GL surface alive across
 * unrelated tree mutations.
 *
 * エリアツリーを再帰的に描画する。葉は AreaLeaf、分割は SplitContainer。onNodeChange は書き換えた部分木を
 * 上へ、onCommand は構造的編集を伝える。葉は key(id) で同一性を位置でなく安定 id に固定する。
 *
 * @param AreaNode node The node to render.
 * @param Function onNodeChange Receives a rewritten replacement for this node (ratio edits).
 * @param Function onCommand Sink for structural edits from area headers.
 * @param Modifier modifier The layout modifier.
 * @param Function onSplitterDragChange Receives true when a splitter drag begins anywhere in this
 *        subtree and false when it ends; the persistence layer paces layout saves on it.
 */
@Composable
fun AreaTree(
	node: AreaNode,
	onNodeChange: (AreaNode) -> Unit,
	onCommand: (AreaCommand) -> Unit,
	modifier: Modifier = Modifier,
	onSplitterDragChange: (Boolean) -> Unit = {},
) {
	when (node) {
		is LeafArea ->
			key(node.id) {
				AreaLeaf(area = node, onCommand = onCommand, modifier = modifier)
			}
		is SplitNode ->
			SplitContainer(
				node = node,
				onNodeChange = onNodeChange,
				onCommand = onCommand,
				modifier = modifier,
				onSplitterDragChange = onSplitterDragChange,
			)
	}
}

/**
 * Lays out a split's two children with a divider between them, weighted by the node's ratio. Each
 * child recurses through [AreaTree], threading its own rewrite back into the parent via [onNodeChange]
 * so an edit deep in the tree rebuilds only the path to the root. A divider drag rewrites this node's
 * whole subtree through a [SplitterDragSession]: the session snapshots the node when the drag begins
 * and rewrites snapshot + accumulated total per delta, so deltas arriving faster than recomposition
 * commits are never lost (see the session's docblock).  The ancestor onNodeChange chain
 * (node.copy(first = ...)) stays value-correct under those publishes because a ratio drag never
 * changes any ancestor's other fields.
 *
 * @param SplitNode node The split to lay out.
 * @param Function onNodeChange Receives the rewritten split.
 * @param Function onCommand Sink for structural edits.
 * @param Modifier modifier The layout modifier.
 * @param Function onSplitterDragChange Receives this split's (and its children's) drag start/end.
 */
@Composable
private fun SplitContainer(
	node: SplitNode,
	onNodeChange: (AreaNode) -> Unit,
	onCommand: (AreaCommand) -> Unit,
	modifier: Modifier,
	onSplitterDragChange: (Boolean) -> Unit,
) {
	val ratio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	var dragSession by remember { mutableStateOf<SplitterDragSession?>(null) }
	// Release the persistence hold if this container leaves composition MID-DRAG (a keyboard-driven
	// workspace switch or area close while the bar is held): whether draggable's onDragStopped fires
	// on detach is not guaranteed, and a stranded drag-active flag would silently suppress structural
	// layout saves until the next completed drag.  Guarded on the session so a normally-ended drag
	// (session already cleared) never double-signals.  rememberUpdatedState because onDispose runs
	// with the closure captured at first composition, which may hold a stale callback by then.
	val currentOnSplitterDragChange by rememberUpdatedState(onSplitterDragChange)
	DisposableEffect(Unit) {
		onDispose {
			if (dragSession != null) {
				currentOnSplitterDragChange(false)
			}
		}
	}
	// Park this drag's cancel where the shell's Escape precedence can reach it (see
	// SplitterDragCancelController): the session below is remembered per container, so nothing outside
	// this composable could otherwise abort a divider drag.  Cleared by identity rather than blindly,
	// because nested splits mean sibling containers run this same effect and must not blank the
	// dragging one's callback.
	val splitterDragCancel = LocalSplitterDragCancel.current
	val activeDragSession = dragSession
	DisposableEffect(activeDragSession, splitterDragCancel) {
		val cancelDrag: (() -> Unit)? =
			if (activeDragSession == null) {
				null
			} else {
				{
					onNodeChange(activeDragSession.cancel())
					currentOnSplitterDragChange(false)
				}
			}
		if (cancelDrag != null) {
			splitterDragCancel.cancel = cancelDrag
		}
		onDispose {
			if (cancelDrag != null && splitterDragCancel.cancel === cancelDrag) {
				splitterDragCancel.cancel = null
			}
		}
	}
	val first: @Composable () -> Unit = {
		AreaTree(
			node = node.first,
			onNodeChange = { rewritten -> onNodeChange(node.copy(first = rewritten)) },
			onCommand = onCommand,
			modifier = Modifier.fillMaxSize(),
			onSplitterDragChange = onSplitterDragChange,
		)
	}
	val second: @Composable () -> Unit = {
		AreaTree(
			node = node.second,
			onNodeChange = { rewritten -> onNodeChange(node.copy(second = rewritten)) },
			onCommand = onCommand,
			modifier = Modifier.fillMaxSize(),
			onSplitterDragChange = onSplitterDragChange,
		)
	}
	// BoxWithConstraints measures the parent so the splitter can convert a pixel drag into a ratio
	// against the actual on-axis length (recomputed live as the window resizes).
	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		val density = LocalDensity.current
		val axisPx =
			with(density) {
				when (node.orientation) {
					SplitOrientation.Horizontal -> maxWidth.toPx()
					SplitOrientation.Vertical -> maxHeight.toPx()
				}
			}
		val minPx = with(density) { MIN_AREA_DP.toPx() }
		val splitterPx = with(density) { SPLITTER_THICKNESS.toPx() }
		val onDragStart: () -> Unit = {
			dragSession = SplitterDragSession(node, axisPx, minPx, splitterPx)
			onSplitterDragChange(true)
		}
		val onDrag: (Float) -> Unit = { deltaPx ->
			val activeSession = dragSession
			// Escape already abandoned this drag and restored the starting ratio; swallow every further
			// delta from the still-held pointer rather than rebasing, which would resume the drag.  The
			// check precedes the ownsNode test so a structural edit landing after the cancel cannot
			// resurrect it either.
			if (activeSession == null || !activeSession.isCancelled) {
				val session =
					if (activeSession != null && activeSession.ownsNode(node)) {
						activeSession
					} else {
						// A structural edit rewrote the tree mid-drag (a keyboard command while holding
						// the bar), or the start callback was missed: rebase a fresh session on the live
						// node so the drag continues from the tree as it now stands.
						SplitterDragSession(node, axisPx, minPx, splitterPx).also { rebased -> dragSession = rebased }
					}
				onNodeChange(session.accumulate(deltaPx))
			}
		}
		val onDragEnd: () -> Unit = {
			dragSession = null
			onSplitterDragChange(false)
		}
		when (node.orientation) {
			SplitOrientation.Horizontal ->
				Row(modifier = Modifier.fillMaxSize()) {
					Box(modifier = Modifier.weight(ratio).fillMaxHeight()) { first() }
					Splitter(
						orientation = SplitOrientation.Horizontal,
						onDragByPx = onDrag,
						onDragStarted = onDragStart,
						onDragStopped = onDragEnd,
					)
					Box(modifier = Modifier.weight(1f - ratio).fillMaxHeight()) { second() }
				}
			SplitOrientation.Vertical ->
				Column(modifier = Modifier.fillMaxSize()) {
					Box(modifier = Modifier.weight(ratio).fillMaxWidth()) { first() }
					Splitter(
						orientation = SplitOrientation.Vertical,
						onDragByPx = onDrag,
						onDragStarted = onDragStart,
						onDragStopped = onDragEnd,
					)
					Box(modifier = Modifier.weight(1f - ratio).fillMaxWidth()) { second() }
				}
		}
	}
}