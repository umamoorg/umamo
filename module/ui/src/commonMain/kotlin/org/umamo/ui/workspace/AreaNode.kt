package org.umamo.ui.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a [SplitNode] divides its space. Horizontal lays the two children side by side (the divider is
 * a vertical bar you drag left/right); Vertical stacks them top over bottom (a horizontal divider).
 * Named for the axis the children occupy, and labelled in the UI by the visual result
 * ("Split Left/Right" vs "Split Top/Bottom") to avoid Blender's inverse "split horizontally" wording.
 *
 * 分割の向き。Horizontal は左右に並べ（縦の仕切り）、Vertical は上下に積む（横の仕切り）。
 */
@Serializable
enum class SplitOrientation {
	Horizontal,
	Vertical,
}

/**
 * A node in a workspace's recursive area tree - either a [SplitNode] (an internal split) or a
 * [LeafArea] (a hosted editor space). A sealed interface so the layout engine and reducer match
 * exhaustively (the compiler enforces both cases are handled), and `@Serializable` so the whole tree
 * round-trips through the interface.layout settings key. Polymorphism uses a "type" discriminator
 * with the per-subtype [SerialName] (see LayoutJson).
 *
 * ワークスペースの再帰的エリアツリーのノード。分割 (SplitNode) か葉 (LeafArea) のどちらか。
 */
@Serializable
sealed interface AreaNode

/**
 * An internal split: two child nodes divided along [orientation], with [ratio] giving the first
 * child's fraction of the axis (0..1). Splits nest arbitrarily, so [first]/[second] may themselves be
 * SplitNodes. A SplitNode has no stable id - only leaves do - because a split is addressed
 * structurally (by the leaves it contains), and ratio edits thread up through the tree, not through
 * an id lookup.
 *
 * 内部分割ノード。orientation 方向に 2 子を ratio（最初の子の割合）で分ける。任意に入れ子可能。
 *
 * @property SplitOrientation orientation The division axis.
 * @property Float ratio The first child's fraction of the axis (0..1).
 * @property AreaNode first The leading child (left or top).
 * @property AreaNode second The trailing child (right or bottom).
 */
@Serializable
@SerialName("split")
data class SplitNode(
	val orientation: SplitOrientation,
	val ratio: Float,
	val first: AreaNode,
	val second: AreaNode,
) : AreaNode

/**
 * A leaf area hosting exactly one editor [space], identified by a stable, position-independent [id].
 * The id is the linchpin of GL viewport identity: it is minted once, never reused, and never derived
 * from tree position, so a leaf keeps the same id when an unrelated area splits - which lets the
 * viewport host keep the same GL surface alive (via key(id) + movableContentOf) instead of tearing it
 * down and recreating it.
 *
 * 葉エリア。1 つのエディタ空間を表示し、安定で位置非依存の id を持つ。id は GL ビューポートの同一性の要。
 *
 * @property String id The stable, never-reused area identity.
 * @property SpaceKind space The editor space currently shown here.
 */
@Serializable
@SerialName("leaf")
data class LeafArea(
	val id: String,
	val space: SpaceKind,
) : AreaNode
