package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
 */
@Composable
fun AreaTree(node: AreaNode, onNodeChange: (AreaNode) -> Unit, onCommand: (AreaCommand) -> Unit, modifier: Modifier = Modifier) {
	when (node) {
		is LeafArea ->
			key(node.id) {
				AreaLeaf(area = node, onCommand = onCommand, modifier = modifier)
			}
		is SplitNode ->
			SplitContainer(node = node, onNodeChange = onNodeChange, onCommand = onCommand, modifier = modifier)
	}
}

/**
 * Lays out a split's two children with a divider between them, weighted by the node's ratio. Each
 * child recurses through [AreaTree], threading its own rewrite back into the parent via [onNodeChange]
 * so an edit deep in the tree rebuilds only the path to the root. A divider drag rewrites this node's
 * whole subtree through [dragSplitBoundary] so only the two divider-adjacent panels resize.
 *
 * 分割の 2 子を ratio の重みで仕切り付きに配置する。各子は AreaTree で再帰し、自身の書き換えを親へ返す。
 *
 * @param SplitNode node The split to lay out.
 * @param Function onNodeChange Receives the rewritten split.
 * @param Function onCommand Sink for structural edits.
 * @param Modifier modifier The layout modifier.
 */
@Composable
private fun SplitContainer(node: SplitNode, onNodeChange: (AreaNode) -> Unit, onCommand: (AreaCommand) -> Unit, modifier: Modifier) {
	val ratio = node.ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	val first: @Composable () -> Unit = {
		AreaTree(
			node = node.first,
			onNodeChange = { rewritten -> onNodeChange(node.copy(first = rewritten)) },
			onCommand = onCommand,
			modifier = Modifier.fillMaxSize(),
		)
	}
	val second: @Composable () -> Unit = {
		AreaTree(
			node = node.second,
			onNodeChange = { rewritten -> onNodeChange(node.copy(second = rewritten)) },
			onCommand = onCommand,
			modifier = Modifier.fillMaxSize(),
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
		val onDrag: (Float) -> Unit = { deltaPx ->
			onNodeChange(dragSplitBoundary(node, deltaPx, axisPx, minPx, splitterPx))
		}
		when (node.orientation) {
			SplitOrientation.Horizontal ->
				Row(modifier = Modifier.fillMaxSize()) {
					Box(modifier = Modifier.weight(ratio).fillMaxHeight()) { first() }
					Splitter(orientation = SplitOrientation.Horizontal, onDragByPx = onDrag)
					Box(modifier = Modifier.weight(1f - ratio).fillMaxHeight()) { second() }
				}
			SplitOrientation.Vertical ->
				Column(modifier = Modifier.fillMaxSize()) {
					Box(modifier = Modifier.weight(ratio).fillMaxWidth()) { first() }
					Splitter(orientation = SplitOrientation.Vertical, onDragByPx = onDrag)
					Box(modifier = Modifier.weight(1f - ratio).fillMaxWidth()) { second() }
				}
		}
	}
}
