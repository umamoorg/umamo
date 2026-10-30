package org.umamo.edit

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerChain

/**
 * A selectable rig entity, addressed by its typed id. A closed taxonomy (part, drawable, or deformer)
 * so a `when` over a selection is exhaustive; adding a fourth selectable kind becomes a compile error
 * until handled everywhere. This is object-mode selection — whole entities, not their interior mesh
 * vertices or deformer control points (those belong to Edit and Pose mode, see [EditorMode]).
 */
sealed interface SelectionTarget {
	/** An organizational tree part. */
	data class Part(val id: PartId) : SelectionTarget

	/** A textured drawable mesh. */
	data class Drawable(val id: DrawableId) : SelectionTarget

	/** A warp or rotation deformer. */
	data class Deformer(val id: DeformerId) : SelectionTarget
}

/**
 * An immutable object-mode selection: the set of selected targets plus the active (primary) one — the
 * last target the user added, which the Inspector features when several are selected and which anchors
 * future range operations. The active target is always a member of [targets], or null when the
 * selection is empty.
 */
data class Selection(
	/** The selected targets, empty when nothing is selected. */
	val targets: Set<SelectionTarget> = emptySet(),
	/** The primary target (last added), or null when [targets] is empty. */
	val active: SelectionTarget? = null,
) {
	/** True when nothing is selected. */
	val isEmpty: Boolean get() = targets.isEmpty()

	/** The number of selected targets. */
	val size: Int get() = targets.size

	/**
	 * Reports whether the given target is part of this selection.
	 *
	 * @param SelectionTarget target The target to test.
	 * @return Boolean True when [target] is selected.
	 */
	operator fun contains(target: SelectionTarget): Boolean = target in targets
}

/**
 * The parameters currently targeted for keyform authoring: the selected set plus the active (primary) one.
 *
 * Separate from [Selection] because it addresses a different axis of the document - you pick WHICH OBJECT
 * to key from the object selection, and WHICH PARAMETER to key it on from here, and the two move
 * independently.  Held on the session rather than a panel's view state because it is shared: a keyform
 * sheet in one area follows the parameter chosen in a parameters panel in another.
 */
data class ParameterSelection(
	/** The selected parameters, empty when none is targeted. */
	val ids: Set<ParameterId> = emptySet(),
	/** The primary parameter (last added), or null when [ids] is empty. */
	val active: ParameterId? = null,
) {
	/** True when no parameter is targeted. */
	val isEmpty: Boolean get() = ids.isEmpty()

	/**
	 * Reports whether the given parameter is selected.
	 *
	 * @param ParameterId id The parameter to test.
	 * @return Boolean True when [id] is selected.
	 */
	operator fun contains(id: ParameterId): Boolean = id in ids

	companion object {
		/**
		 * A selection of just [id], the plain-click result.
		 *
		 * @param ParameterId id The sole parameter to select.
		 * @return ParameterSelection A selection holding only [id], with it active.
		 */
		fun of(id: ParameterId): ParameterSelection = ParameterSelection(setOf(id), id)
	}

	/**
	 * This selection with [id] toggled (a Shift or primary-modifier click, matching [SelectionOps.toggle]).
	 *
	 * @param ParameterId id The parameter to toggle.
	 * @return ParameterSelection The resulting selection.
	 */
	fun toggled(id: ParameterId): ParameterSelection =
		if (id in ids) {
			val remaining = ids - id
			ParameterSelection(remaining, remaining.lastOrNull())
		} else {
			ParameterSelection(ids + id, id)
		}

	/**
	 * This selection with every parameter not in [surviving] dropped - applied after a parameter is
	 * deleted, so the target can never dangle on an id the model no longer has.
	 *
	 * @param Set<ParameterId> surviving The parameter ids still present in the model.
	 * @return ParameterSelection The pruned selection, or this when nothing was dropped.
	 */
	fun prunedTo(surviving: Set<ParameterId>): ParameterSelection {
		val kept = ids intersect surviving

		return if (kept.size == ids.size) this else ParameterSelection(kept, active?.takeIf { it in kept } ?: kept.lastOrNull())
	}
}

/**
 * Pure transformations over a [Selection], one per selection gesture. Each returns a new immutable
 * [Selection] and never mutates its input, so they are trivially unit-testable without Compose or a
 * render context. The active target is maintained per the [Selection] invariant: it is always a member
 * of the result's targets, or null when the result is empty.
 */
object SelectionOps {
	/**
	 * Replaces the whole selection with a single target (a plain click).
	 *
	 * @param SelectionTarget target The sole target to select.
	 * @return Selection A selection holding only [target], with it active.
	 */
	fun replace(target: SelectionTarget): Selection = Selection(setOf(target), target)

	/**
	 * Toggles a target's membership (a Shift or Ctrl / Cmd click, Blender-style): removes it when
	 * present, adds it otherwise. When added it becomes active; when removed the active target falls
	 * back to another remaining member, or null if the selection became empty.
	 *
	 * @param Selection selection The selection to modify.
	 * @param SelectionTarget target The target to toggle.
	 * @return Selection The resulting selection.
	 */
	fun toggle(selection: Selection, target: SelectionTarget): Selection =
		if (target in selection.targets) {
			val remaining = selection.targets - target
			Selection(remaining, remaining.lastOrNull())
		} else {
			Selection(selection.targets + target, target)
		}

	/**
	 * Adds a target without removing anything (an additive gesture with no current default binding),
	 * making it active. Adding a target already present simply promotes it to active.
	 *
	 * @param Selection selection The selection to extend.
	 * @param SelectionTarget target The target to add.
	 * @return Selection The extended selection.
	 */
	fun add(selection: Selection, target: SelectionTarget): Selection = Selection(selection.targets + target, target)

	/**
	 * Clears the selection.
	 *
	 * @return Selection An empty selection.
	 */
	fun clear(): Selection = Selection()

	/**
	 * Every selectable entity in the model as a target set, in parts-then-deformers-then-drawables order.
	 * A locked entity (isSelectable == false) is excluded, matching the pointer picker.  The full set
	 * [selectAll] fills and [invert] complements against.
	 *
	 * @param PuppetModel model The rig to enumerate.
	 * @return Set<SelectionTarget> Every selectable target.
	 */
	private fun allSelectableTargets(model: PuppetModel): Set<SelectionTarget> {
		val targets = LinkedHashSet<SelectionTarget>()

		for (part in model.parts) {
			if (part.isSelectable) {
				targets.add(SelectionTarget.Part(part.id))
			}
		}

		for (deformer in model.deformers) {
			if (deformer.isSelectable) {
				targets.add(SelectionTarget.Deformer(deformer.id))
			}
		}

		for (drawable in model.drawables) {
			if (drawable.isSelectable) {
				targets.add(SelectionTarget.Drawable(drawable.id))
			}
		}

		return targets
	}

	/**
	 * Selects every selectable entity in the model (Blender's Select All in object mode).  Keeps the current
	 * active target (it is necessarily still selected); with none, the last enumerated target becomes active.
	 *
	 * @param Selection selection The current selection (its active target is preserved when possible).
	 * @param PuppetModel model The rig whose entities to select.
	 * @return Selection The selection holding every selectable target.
	 */
	fun selectAll(selection: Selection, model: PuppetModel): Selection {
		val allTargets = allSelectableTargets(model)
		val active = selection.active?.takeIf { target -> target in allTargets } ?: allTargets.lastOrNull()

		return Selection(allTargets, active)
	}

	/**
	 * Inverts the object selection (Blender's Ctrl+I): every selectable entity not currently selected
	 * becomes selected and vice versa.  The active target is kept only when it survives the inversion, which
	 * in practice drops it (a selected active target is deselected by the inversion).
	 *
	 * @param Selection selection The selection to invert.
	 * @param PuppetModel model The rig whose entities define the domain.
	 * @return Selection The inverted selection.
	 */
	fun invert(selection: Selection, model: PuppetModel): Selection {
		val inverted = allSelectableTargets(model) - selection.targets

		return Selection(inverted, selection.active?.takeIf { target -> target in inverted })
	}

	/**
	 * Expands every selected target to its outliner subtree (the Select Hierarchy context action): a
	 * part adds its descendant parts and their drawables, a deformer its descendant deformers, a
	 * drawable just itself - exactly the rows the outliner shows beneath each (see
	 * [PuppetModel.subtreeTargets]).  The active target is kept (still selected); an empty selection
	 * stays empty.
	 *
	 * @param Selection selection The selection whose targets to expand.
	 * @param PuppetModel model The rig the subtrees are read from.
	 * @return Selection The selection with every subtree included.
	 */
	fun selectHierarchy(selection: Selection, model: PuppetModel): Selection {
		if (selection.isEmpty) {
			return selection
		}

		val expanded = LinkedHashSet<SelectionTarget>()

		for (target in selection.targets) {
			expanded.addAll(model.subtreeTargets(target))
		}

		return Selection(expanded, selection.active ?: expanded.lastOrNull())
	}
}

/**
 * Whether [target] names an object this model has.  A selection is not pruned when an object is deleted, so
 * a target read from one can name an object that is gone.
 *
 * @param SelectionTarget target The target to look up.
 * @return Boolean True when the model has the part, drawable, or deformer the target names.
 */
fun PuppetModel.hasTarget(target: SelectionTarget): Boolean =
	when (target) {
		is SelectionTarget.Part -> parts.any { part -> part.id == target.id }
		is SelectionTarget.Drawable -> drawables.any { drawable -> drawable.id == target.id }
		is SelectionTarget.Deformer -> deformers.any { deformer -> deformer.id == target.id }
	}

/**
 * Enumerates the outliner subtree rooted at [target], the target itself first, in tree order.  Matches
 * exactly what the outliner shows as children: a part yields itself plus every descendant part and every
 * drawable in those parts' org-tree children; a deformer yields itself plus its descendant deformers only
 * (never the drawables bound to them via parentDeformerId — those rows live under their parts); a
 * drawable is a leaf, so it yields just itself.  An id that no longer resolves is still yielded (the
 * transforms no-op on it) but never descended into, and a malformed cycle is visited once.  Pure over the
 * model, so it unit-tests without Compose.
 *
 * @param SelectionTarget target The subtree root.
 * @return List The subtree's targets, [target] first.
 */
fun PuppetModel.subtreeTargets(target: SelectionTarget): List<SelectionTarget> =
	when (target) {
		is SelectionTarget.Part -> {
			val partsById = parts.associateBy { part -> part.id }
			val visitedPartIds = HashSet<PartId>()
			val collected = mutableListOf<SelectionTarget>()

			fun visitPart(partId: PartId) {
				if (!visitedPartIds.add(partId)) {
					return
				}

				collected += SelectionTarget.Part(partId)
				val part = partsById[partId] ?: return

				for (childEntry in part.children) {
					when (childEntry) {
						is OrgChild.Part -> visitPart(childEntry.id)
						is OrgChild.Drawable -> collected += SelectionTarget.Drawable(childEntry.id)
					}
				}
			}
			visitPart(target.id)
			collected
		}

		is SelectionTarget.Drawable -> {
			listOf(target)
		}

		is SelectionTarget.Deformer -> {
			val deformersByParent = deformers.groupBy { deformer -> deformer.parent }
			val visitedDeformerIds = HashSet<DeformerId>()
			val collected = mutableListOf<SelectionTarget>()

			fun visitDeformer(deformerId: DeformerId) {
				if (!visitedDeformerIds.add(deformerId)) {
					return
				}

				collected += SelectionTarget.Deformer(deformerId)

				for (childDeformer in deformersByParent[deformerId].orEmpty()) {
					visitDeformer(childDeformer.id)
				}
			}
			visitDeformer(target.id)
			collected
		}
	}

/**
 * The selected drawables that carry a mesh, or null when there are none: what an Object-mode operation over
 * drawables alone works on (Generate Mesh, Frame Selected's art).  Parts, deformers, and mesh-less drawables are
 * silently IGNORED rather than blocking: a Select All / Invert sweeps them into the selection, and the user
 * expectation is that the operation still reaches the meshes it can.  An Object-mode transform moves warps too,
 * and resolves its items through [eligibleTransformTargets] instead.
 *
 * @param Selection selection The object-mode selection to evaluate.
 * @param PuppetModel model The rig the targets index into.
 * @return List<DrawableId>? The selected meshed drawable ids, or null when there are none.
 */
fun eligibleTransformDrawables(selection: Selection, model: PuppetModel): List<DrawableId>? {
	if (selection.isEmpty) {
		return null
	}

	val drawableIds = ArrayList<DrawableId>(selection.size)

	for (target in selection.targets) {
		// Parts and deformers are not this function's items (see the docblock); skip them, not the operation.
		val drawableTarget = target as? SelectionTarget.Drawable ?: continue
		// A drawable that carries no source geometry has no positions to move; skip it too.
		val drawable = model.drawables.firstOrNull { it.id == drawableTarget.id }

		if (drawable?.mesh == null) {
			continue
		}

		drawableIds.add(drawableTarget.id)
	}

	return drawableIds.ifEmpty { null }
}

/**
 * What an Object-mode Grab / Scale / Rotate moves: the outermost transformable items of a selection.
 *
 * @property List<DrawableId> drawableIds The drawables, each carrying a mesh.
 * @property List<DeformerId> warpIds The warps, each with a lattice the default pose draws.
 */
class TransformTargets(
	val drawableIds: List<DrawableId>,
	val warpIds: List<DeformerId>,
)

/**
 * The items an Object-mode Grab / Scale / Rotate moves, or null when the gesture must be blocked.  A drawable
 * moves by its rest arrays, so it needs a mesh; a warp moves as a whole deformer, every form it carries at once,
 * so it needs a lattice the default pose draws.  Parts and rotation deformers cannot be transformed yet and are
 * silently skipped, as are mesh-less drawables and warps hidden at rest: a Select All sweeps them in, and G still
 * moves what it can.
 *
 * Each item moves once.  An item under a warp that moves follows that warp - its art lives in the warp's
 * lattice - so it is left out rather than moved a second time on its own (Blender's rule for a selected parent
 * and child).
 *
 * @param Selection selection The object-mode selection to evaluate.
 * @param PuppetModel model The rig the targets index into.
 * @return TransformTargets? The outermost transformable items, or null when nothing is transformable.
 */
fun eligibleTransformTargets(selection: Selection, model: PuppetModel): TransformTargets? {
	if (selection.isEmpty) {
		return null
	}

	val drawableById = model.drawables.associateBy { drawable -> drawable.id }
	val drawableIds = ArrayList<DrawableId>(selection.size)
	val warpIds = LinkedHashSet<DeformerId>()

	for (target in selection.targets) {
		when (target) {
			is SelectionTarget.Drawable -> {
				if (drawableById[target.id]?.mesh != null) {
					drawableIds.add(target.id)
				}
			}

			is SelectionTarget.Deformer -> {
				if (model.hasRestLattice(target.id)) {
					warpIds.add(target.id)
				}
			}

			is SelectionTarget.Part -> {}
		}
	}

	val outerDrawables = drawableIds.filterNot { drawableId -> model.movesWithAncestor(drawableById[drawableId]?.parentDeformerId, warpIds) }
	val outerWarps = warpIds.filterNot { warpId -> model.movesWithAncestor(model.deformerById[warpId]?.parent, warpIds) }

	if (outerDrawables.isEmpty() && outerWarps.isEmpty()) {
		return null
	}

	return TransformTargets(outerDrawables, outerWarps)
}

/**
 * Whether the deformer chain from [parent] up ([deformerChain]) holds one of [movingWarps].
 *
 * @param DeformerId? parent The first deformer up the chain, or null at the root.
 * @param Set<DeformerId> movingWarps The warps that move.
 * @return Boolean True when an ancestor moves.
 */
private fun PuppetModel.movesWithAncestor(parent: DeformerId?, movingWarps: Set<DeformerId>): Boolean =
	parent != null && deformerChain(parent).any { deformer -> deformer.id in movingWarps }