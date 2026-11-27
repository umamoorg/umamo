package org.umamo.ui.workspace.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.umamo.ui.workspace.SpaceKind

/*
 * The workspace layout as data: the area tree (this file), the workspaces that hold one each
 * (Workspace.kt), the structural edits and their pure reducer (WorkspaceReducer.kt), the seeded defaults
 * (WorkspaceDefaults.kt), the controller owning the live layout (WorkspaceLayoutController.kt), and
 * persistence to settings and to files (LayoutPersistence.kt, LayoutSavePacer.kt,
 * WorkspaceLayoutFiles.kt).  No composable lives here - the tree is drawn by org.umamo.ui.workspace.area
 * - so the whole package tests without a composition.
 */

/**
 * How a [SplitNode] divides its space.  Horizontal lays the two children side by side (the divider is
 * a vertical bar you drag left/right); Vertical stacks them top over bottom (a horizontal divider).
 * Named for the axis the children occupy, and labelled in the UI by the visual result
 * ("Split Left/Right" vs "Split Top/Bottom") to avoid Blender's inverse "split horizontally" wording.
 */
@Serializable
enum class SplitOrientation {
	Horizontal,
	Vertical,
}

/**
 * A node in a workspace's recursive area tree - either a [SplitNode] (an internal split) or a
 * [LeafArea] (a hosted editor space).  A sealed interface so the layout engine and reducer match
 * exhaustively (the compiler enforces both cases are handled), and `@Serializable` so the whole tree
 * round-trips through the interface.layout settings key.  Polymorphism uses a "type" discriminator
 * with the per-subtype [SerialName] (see LayoutJson).
 */
@Serializable
sealed interface AreaNode

/** The smallest fraction either child of a split may shrink to (keeps both areas usable). */
internal const val MIN_RATIO = 0.05f

/**
 * An internal split: two child nodes divided along [orientation], with [ratio] giving the first
 * child's fraction of the axis (0..1).  Splits nest arbitrarily, so [first]/[second] may themselves be
 * SplitNodes.  A SplitNode has no stable id - only leaves do - because a split is addressed
 * structurally (by the leaves it contains), and ratio edits thread up through the tree, not through
 * an id lookup.
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
 * The id is the linchpin of an area's identity: it is minted once, never reused, and never derived
 * from tree position, so a leaf keeps its id when an unrelated area splits.  A split or a close
 * rebuilds the leaf's composition, so what an area keeps across a layout change is keyed on this id:
 * its view state, and the render service's slot and camera.
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

/**
 * The first leaf under this node that [predicate] accepts, in tree order (a split's first child before
 * its second: left before right, top before bottom), or null when none does - how a caller finds "some
 * area of this kind" when the pointer has named none.
 *
 * @param Function predicate Whether a leaf qualifies.
 * @return LeafArea? The first qualifying leaf, or null.
 */
fun AreaNode.firstLeafOrNull(predicate: (LeafArea) -> Boolean): LeafArea? =
	when (this) {
		is LeafArea -> if (predicate(this)) this else null
		is SplitNode -> first.firstLeafOrNull(predicate) ?: second.firstLeafOrNull(predicate)
	}

/**
 * Every leaf's id under this node, in tree order (a split's first child before its second) - the order a saved
 * document lists a layout's areas in (docs/format/UMA.md §7.5).
 *
 * @return List<String> The leaf ids.
 */
fun AreaNode.leafAreaIds(): List<String> =
	when (this) {
		is LeafArea -> listOf(id)
		is SplitNode -> first.leafAreaIds() + second.leafAreaIds()
	}