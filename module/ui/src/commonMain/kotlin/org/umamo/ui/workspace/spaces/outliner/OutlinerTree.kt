package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.runtime.Immutable
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel

/** The stable node id of the synthetic puppet root - the one row open by default in the outliner. */
internal const val OUTLINER_ROOT_ID = "root"

/**
 * The icon a row carries, drawn as themed vector art from the shared UmamoIcons set inside a reserved
 * fixed-width slot (see OutlinerIconSlot). The two synthetic roots mirror Blender's armature object /
 * armature data icons.
 */
internal enum class OutlinerIcon {
	/** The puppet root (Blender's orange armature-object icon). */
	PuppetRoot,

	/** The deformer-hierarchy group node (Blender's green armature-data icon). */
	Armature,

	/** An organizational part / folder. */
	Part,

	/** A drawable art mesh. */
	Drawable,

	/** A warp (FFD lattice) deformer. */
	WarpDeformer,

	/** A rotation (pivot) deformer. */
	RotationDeformer,
}

/**
 * One node in the unified outliner tree, the Blender-style single tree of the rig: a part, a drawable, a
 * deformer, or one of the two synthetic grouping rows (the puppet root and the Armature deformer-hierarchy
 * node).  Synthetic rows carry a null [target] - they only expand / collapse, they do not select.
 *
 * Immutable: a node is built once with its tree and never changed after, its children included.  The
 * annotation is that promise made to Compose, which then compares a node by value, so a row handed a
 * node equal to the one it has skips.
 *
 * @property String id A stable, unique key for expand-state and reveal lookup (never reused).
 * @property String label The display text (already localized for synthetic rows; raw document name otherwise).
 * @property OutlinerIcon icon The icon kind.
 * @property Boolean dimmed Whether the row renders muted (hidden / sketch).
 * @property SelectionTarget? target What the row selects when clicked, or null for a synthetic row.
 * @property List children The child rows, in display order (a part's in its org-tree order).
 * @property Boolean selectable Whether the row's entity is viewport-selectable (synthetic rows true).
 */
@Immutable
internal data class OutlinerNode(
	val id: String,
	val label: String,
	val icon: OutlinerIcon,
	val dimmed: Boolean,
	val target: SelectionTarget?,
	val children: List<OutlinerNode>,
	val selectable: Boolean = true,
)

/**
 * Builds the unified outliner tree from a puppet: one root holding the Armature deformer hierarchy
 * followed by the top-level org tree, each part listing its drawables and sub-parts in their org-tree
 * order.  Cross-links (which deformer deforms a drawable, which part owns a deformer) are deliberately
 * absent - they belong in the Properties panel, not duplicated into the tree.
 *
 * @param PuppetModel puppet The rig to walk.
 * @param String rootLabel The localized label for the puppet root row.
 * @param String armatureLabel The localized label for the deformer-hierarchy row.
 * @return OutlinerNode The root node of the unified tree.
 */
internal fun buildOutlinerTree(puppet: PuppetModel, rootLabel: String = "Armature", armatureLabel: String = "Armature"): OutlinerNode {
	val armature = buildArmatureNode(puppet, armatureLabel)

	val partsById = puppet.parts.associateBy { part -> part.id }
	val drawablesById = puppet.drawables.associateBy { drawable -> drawable.id }

	/**
	 * Builds the outliner node for a drawable.
	 *
	 * @param Drawable drawable The drawable.
	 * @return OutlinerNode The leaf node.
	 */
	fun drawableNode(drawable: Drawable): OutlinerNode =
		OutlinerNode(
			id = "drawable:${drawable.id.raw}",
			label = drawable.name,
			icon = OutlinerIcon.Drawable,
			dimmed = !drawable.isVisible,
			target = SelectionTarget.Drawable(drawable.id),
			children = emptyList(),
			selectable = drawable.isSelectable,
		)

	// The org tree is already a single interleaved order of sub-parts and drawables per level, so the
	// outliner just reads it - no separate merge. A node that does not resolve (a dangling id) is dropped.
	// childNodes and partNode are mutually recursive (a part's children may be parts), so childNodes is a
	// var the part builder closes over and that is assigned the real body once partNode exists.
	var childNodes: (List<OrgChild>) -> List<OutlinerNode> = { emptyList() }

	/**
	 * Builds the outliner node for a part, with its children under it.
	 *
	 * @param Part part The part.
	 * @return OutlinerNode The part's node.
	 */
	fun partNode(part: Part): OutlinerNode =
		OutlinerNode(
			id = "part:${part.id.raw}",
			label = part.name,
			icon = OutlinerIcon.Part,
			dimmed = !part.isVisible || part.isSketch,
			target = SelectionTarget.Part(part.id),
			children = childNodes(part.children),
			selectable = part.isSelectable,
		)

	childNodes = { children ->
		children.mapNotNull { child ->
			when (child) {
				is OrgChild.Drawable -> drawablesById[child.id]?.let { drawableNode(it) }
				is OrgChild.Part -> partsById[child.id]?.let { partNode(it) }
			}
		}
	}

	return OutlinerNode(
		id = OUTLINER_ROOT_ID,
		label = rootLabel,
		icon = OutlinerIcon.PuppetRoot,
		dimmed = false,
		target = null,
		children = listOf(armature) + childNodes(puppet.rootChildren),
	)
}

/**
 * Prunes the tree for the outliner header's search box and kind toggles, returning a new root: the kind
 * toggles drop whole kinds first, then [query] keeps only the paths down to matching rows.  Turning Parts
 * off hoists their drawables up rather than hiding them, and the puppet root always survives so the panel
 * never goes fully blank.
 *
 * @param OutlinerNode root The full tree.
 * @param String query The case-insensitive name filter (blank = no name filter).
 * @param Boolean showParts Whether to keep part / folder rows (false hoists their drawables up).
 * @param Boolean showDrawables Whether to keep drawable rows.
 * @param Boolean showDeformers Whether to keep the Armature deformer hierarchy.
 * @return OutlinerNode The pruned root.
 */
internal fun filterOutliner(root: OutlinerNode, query: String, showParts: Boolean, showDrawables: Boolean, showDeformers: Boolean): OutlinerNode {
	val kindRoot = kindFiltered(root, showParts, showDrawables, showDeformers).firstOrNull() ?: root.copy(children = emptyList())

	return queryPruned(kindRoot, query.trim()) ?: kindRoot.copy(children = emptyList())
}

/**
 * Computes a Shift-click range selection over the outliner's visible rows: the contiguous run of targets
 * from the active anchor to the clicked row (inclusive, in either direction) is added to the existing
 * selection, and the clicked target becomes active.  Takes [orderedTargets] rather than the tree because
 * the visible-row order is what the range runs over.
 *
 * @param List orderedTargets The visible rows' targets in display order (null for non-selectable rows).
 * @param Selection current The selection before the click.
 * @param Int clickedIndex The clicked row's index in [orderedTargets].
 * @param SelectionTarget clickedTarget The clicked row's target (becomes active).
 * @return Selection The selection after the range add.
 */
internal fun outlinerRangeSelection(orderedTargets: List<SelectionTarget?>, current: Selection, clickedIndex: Int, clickedTarget: SelectionTarget): Selection {
	val anchorIndex = current.active?.let { active -> orderedTargets.indexOfFirst { it == active } }?.takeIf { it >= 0 } ?: clickedIndex
	val low = minOf(anchorIndex, clickedIndex)
	val high = maxOf(anchorIndex, clickedIndex)
	val rangeTargets = orderedTargets.subList(low, high + 1).filterNotNull()

	return Selection(current.targets + rangeTargets, clickedTarget)
}

/**
 * The kind-toggle pass: returns the node (with kind-filtered children) as a one-element list, an empty
 * list when its whole kind is hidden, or - for a hidden part - just its kept children, hoisting the
 * drawables up so they flatten under the part's parent instead of disappearing. Returns a list (not a
 * nullable node) precisely so a dropped part can splice multiple children into its parent.
 *
 * @param OutlinerNode node The node to filter.
 * @param Boolean showParts Whether part rows are kept.
 * @param Boolean showDrawables Whether drawable rows are kept.
 * @param Boolean showDeformers Whether the deformer hierarchy is kept.
 * @return List the node (possibly hoisted away), as a list.
 */
private fun kindFiltered(node: OutlinerNode, showParts: Boolean, showDrawables: Boolean, showDeformers: Boolean): List<OutlinerNode> {
	val isDeformerKind = node.icon == OutlinerIcon.Armature || node.icon == OutlinerIcon.WarpDeformer || node.icon == OutlinerIcon.RotationDeformer

	if (!showDeformers && isDeformerKind) {
		return emptyList()
	}

	if (!showDrawables && node.icon == OutlinerIcon.Drawable) {
		return emptyList()
	}

	val keptChildren = node.children.flatMap { child -> kindFiltered(child, showParts, showDrawables, showDeformers) }

	return if (!showParts && node.icon == OutlinerIcon.Part) {
		keptChildren
	} else {
		listOf(node.copy(children = keptChildren))
	}
}

/**
 * The name-search pass over an already kind-filtered tree: a node survives when it matches [query] or any
 * descendant does, but only the path down to each match is kept - a matched node keeps just its matching
 * descendants, not its whole subtree, so searching "bag" shows the branches down to the bag rows and not
 * every unrelated child under them. A blank query keeps the tree unchanged; the puppet root survives even
 * with no match so the panel never goes blank.
 *
 * @param OutlinerNode node The kind-filtered node.
 * @param String query The trimmed, case-insensitive name filter (empty matches everything).
 * @return OutlinerNode? The pruned node, or null when neither it nor any descendant matches.
 */
private fun queryPruned(node: OutlinerNode, query: String): OutlinerNode? {
	if (query.isEmpty()) {
		return node
	}

	val selfMatches = node.label.contains(query, ignoreCase = true)
	val keptChildren = node.children.mapNotNull { child -> queryPruned(child, query) }

	return when {
		selfMatches || keptChildren.isNotEmpty() -> node.copy(children = keptChildren)
		node.icon == OutlinerIcon.PuppetRoot -> node.copy(children = emptyList())
		else -> null
	}
}

/**
 * Builds the Armature node: every deformer nested by [Deformer.parent] (parentless deformers are the
 * roots), preserving the model's list order among siblings. Always present even when the rig has no
 * deformers, so the tree's structure is stable. A [visited] guard tolerates a malformed cyclic parent
 * reference, mirroring the parts / parameter-tree walks in the importer.
 *
 * @param PuppetModel puppet The rig whose deformers populate the node.
 * @param String armatureLabel The localized label for the node.
 * @return OutlinerNode The Armature deformer-hierarchy node.
 */
private fun buildArmatureNode(puppet: PuppetModel, armatureLabel: String): OutlinerNode {
	val childrenByParent = puppet.deformers.groupBy { deformer -> deformer.parent }
	val visited = HashSet<String>()

	/**
	 * Builds the outliner node for a deformer, with the deformers nested in it; a deformer already visited gets no
	 * children.
	 *
	 * @param Deformer deformer The deformer.
	 * @return OutlinerNode The deformer's node.
	 */
	fun deformerNode(deformer: Deformer): OutlinerNode {
		val icon =
			when (deformer) {
				is Deformer.Warp -> OutlinerIcon.WarpDeformer
				is Deformer.Rotation -> OutlinerIcon.RotationDeformer
			}
		val children =
			if (visited.add(deformer.id.raw)) {
				childrenByParent[deformer.id].orEmpty().map { child -> deformerNode(child) }
			} else {
				emptyList()
			}

		return OutlinerNode(
			id = "deformer:${deformer.id.raw}",
			label = deformer.name,
			icon = icon,
			dimmed = false,
			target = SelectionTarget.Deformer(deformer.id),
			children = children,
			selectable = deformer.isSelectable,
		)
	}

	val roots = childrenByParent[null].orEmpty().map { deformer -> deformerNode(deformer) }

	return OutlinerNode(
		id = "armature",
		label = armatureLabel,
		icon = OutlinerIcon.Armature,
		dimmed = false,
		target = null,
		children = roots,
	)
}