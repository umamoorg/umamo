package org.umamo.edit

import org.umamo.runtime.keyform.CollapsedChannels
import org.umamo.runtime.keyform.colorOr
import org.umamo.runtime.keyform.flagOr
import org.umamo.runtime.keyform.hasFractionalDrawOrder
import org.umamo.runtime.keyform.integralDrawOrderOrNull
import org.umamo.runtime.keyform.scalarOr
import org.umamo.runtime.keyform.withAxisCollapsedKeepingCell
import org.umamo.runtime.keyform.withAxisCollapsedLifting
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Parameter document edits: create a new axis, rename one (its display name), and delete one everywhere.
 * Create and rename mirror the group edits (ParameterGroupEdits.kt) - pure PuppetModel.withX plus thin
 * EditorSession wrappers through mutate. Delete is the heavier one: besides the flat parameters list, the
 * panel tree, and any link, it must scrub the parameter out of every object's keyform grid, since each
 * grid axis references a parameter by id (KeyformAxis.parameterId). Dropping an axis collapses that grid
 * one dimension, keeping the slice at the parameter's default value (the neutral pose) and discarding the
 * off-axis motion. The live pose entry is dropped in the session wrapper (pose lives on EditorSession).
 *
 * パラメータの作成・改名・削除。削除は各オブジェクトのキーフォーム格子から該当軸を取り除き、既定値の
 * スライスへ畳み込む。
 */

/**
 * A fresh CParameterSource-style idstr ("Param", then "Param2", "Param3", ...) that collides with no
 * existing parameter id. Deterministic from the model alone so the same edit replays identically under
 * undo / redo - no clock, no random.
 *
 * // CMO3: CParameterSource id idstr - editor-minted sequential parameter identifiers.
 *
 * @return ParameterId A parameter id not already present in this model.
 */
fun PuppetModel.freshParameterId(): ParameterId {
	val existing = parameters.map { parameter -> parameter.id }.toSet()
	if (ParameterId("Param") !in existing) {
		return ParameterId("Param")
	}
	var suffix = 2
	while (ParameterId("Param$suffix") in existing) {
		suffix++
	}
	return ParameterId("Param$suffix")
}

/**
 * A copy of this model with a new animatable parameter [newId] named [name] of [kind] appended to the axis
 * list and its leaf prepended at the root top of the panel tree (so it lands on-screen for the immediate
 * inline rename, like a new group). The default -1..1 / 0 range makes it animatable (min < max) so it
 * renders as a slider; it is a neutral starting point the user retargets in the range editor. A
 * BLEND_SHAPE kind renders with square knob / key marks (it carries no keys until authoring captures
 * them). Materializes a flat tree first for a group-less model, so the existing rows keep their order
 * beneath the new parameter. Refuses (returns this same instance) a colliding id.
 *
 * @param ParameterId newId The minted id of the new parameter.
 * @param String name The initial display name.
 * @param ParameterKind kind The parameter kind (NORMAL key-form or BLEND_SHAPE); defaults to NORMAL.
 * @return PuppetModel The model with the parameter added, or this if the id already exists.
 */
fun PuppetModel.withParameterCreated(
	newId: ParameterId,
	name: String,
	kind: ParameterKind = ParameterKind.NORMAL,
): PuppetModel {
	if (parameters.any { parameter -> parameter.id == newId }) {
		return this
	}
	val newParameter = Parameter(newId, name, min = -1f, max = 1f, default = 0f, kind = kind)
	val newLeaf = ParameterNode.Param(newId)
	return copy(
		parameters = parameters + newParameter,
		parameterTree = listOf(newLeaf) + materializedParameterTree(),
	)
}

/**
 * A copy of this model with parameter [id] renamed to [newName] (trimmed). The id is format-level and
 * never changes - only the display name. A blank name is a no-op (returns this same instance), so an empty
 * commit keeps the old label; an unchanged name likewise returns this.
 *
 * @param ParameterId id The parameter to rename.
 * @param String newName The new display name (trimmed; blank is ignored).
 * @return PuppetModel The model with the parameter renamed, or this if unchanged or blank.
 */
fun PuppetModel.withParameterRenamed(id: ParameterId, newName: String): PuppetModel {
	val trimmed = newName.trim()
	if (trimmed.isEmpty()) {
		return this
	}
	var changed = false
	val newParameters =
		parameters.map { parameter ->
			if (parameter.id == id && parameter.name != trimmed) {
				changed = true
				parameter.copy(name = trimmed)
			} else {
				parameter
			}
		}
	return if (changed) {
		copy(parameters = newParameters)
	} else {
		this
	}
}

/**
 * A copy of this model with parameter [id] removed everywhere: the axis list, every panel-tree leaf, any
 * link it belongs to (its partner reverts to a plain slider), every keyform grid in the rig - drawables,
 * both deformer kinds, parts, and glue intensities - and every blend shape that names it. Dropping the
 * deleted axis collapses each grid to the slice at the parameter's default value (the neutral look),
 * discarding that axis's motion. A channel track left with no axes is dropped, and the value of its kept
 * slice is lifted into the owner's static so the neutral look survives the drop; a fractional part draw
 * order stays behind as a constant track because the Int static slot cannot hold it without rounding. A
 * geometry grid left with no axes keeps its kept slice as a one-cell grid with no axes: a deformer's
 * lattice or pivot lives only in its cells, and a drawable keeps the look it had rather than snapping back
 * to its base mesh (the base mesh is authored, never baked from a slice). A blend binding the parameter
 * drives is dropped, and a weight limit over it is dropped or baked into its binding's forms
 * ([blendBindingScrubOf]), so no id the model lacks survives; [ParameterDeletion.restChangedOwners] names the
 * owners where that, or a default between two keys, moves the rest pose. The render root is re-derived at
 * the end so its per-group copies of the part tracks lose the deleted axis too. The live pose entry is
 * dropped by the session wrapper. A no-op (no such parameter) returns this same instance.
 *
 * @param ParameterId id The parameter to delete.
 * @return PuppetModel The model with the parameter removed, or this if it was absent.
 */
fun PuppetModel.withParameterDeleted(id: ParameterId): PuppetModel = parameterDeletionOf(id)?.model ?: this

/**
 * What deleting a parameter produces: the model without it ([withParameterDeleted]), and the owners whose look
 * at the default pose the delete moved - a grid or track keyed on it with its default on none of its keys (the
 * collapse keeps the nearest key's slice rather than the look at the default), a sole-axis track or deformer
 * grid whose kept key has no cell (the static, or no grid at all, takes over from the nothing a missing cell
 * evaluated to), or a blend shape the scrub cannot keep exact ([blendBindingScrubOf]).  Everything else a
 * delete does leaves the rest pose as it was.
 *
 * @property PuppetModel model             The model with the parameter removed.
 * @property List        restChangedOwners The owners whose rest pose changes, in model order: drawables,
 *   deformers, parts, then glues.
 */
class ParameterDeletion(val model: PuppetModel, val restChangedOwners: List<KeyformOwner>)

/**
 * The delete of parameter [id] with the owners it moves, decided in the one walk that collapses them: each
 * grid, track, and binding answers the rest-pose question as it is scrubbed, rather than the whole model being
 * walked a second time to ask it.
 *
 * @param ParameterId id The parameter to delete.
 * @return ParameterDeletion? The deletion, or null when the model has no such parameter.
 */
fun PuppetModel.parameterDeletionOf(id: ParameterId): ParameterDeletion? {
	val deleted = parameters.firstOrNull { parameter -> parameter.id == id } ?: return null
	val keepValue = deleted.default
	val defaultOf = defaultValueLookup()
	val drawableMoves = BooleanArray(drawables.size)
	val deformerMoves = BooleanArray(deformers.size)
	val partMoves = BooleanArray(parts.size)
	val glueMoves = BooleanArray(glues.size)
	// Both halves of a drawable key on parameters: the geometry grid and every channel track.  A sparse
	// geometry grid losing its absent kept cell moves nothing: zero deltas and no grid both draw the base mesh.
	val newDrawables =
		drawables.mapIndexed { drawableIndex, drawable ->
			drawableMoves[drawableIndex] =
				collapseMovesRest(drawable.geometryGrid, id, keepValue, missingKeptCellMoves = false) ||
				collapseMovesRest(drawable.channelGrids, id, keepValue)
			val collapsed = drawable.geometryGrid?.withAxisCollapsedKeepingCell(id, keepValue)
			val scrubbed = drawable.channelGrids.withAxisCollapsedLifting(id, keepValue)
			if (collapsed === drawable.geometryGrid && scrubbed.channelGrids === drawable.channelGrids) {
				drawable
			} else {
				drawable.copy(
					geometryGrid = collapsed,
					channelGrids = scrubbed.channelGrids,
					drawOrder = scrubbed.lifted.scalarOr(FormChannel.DRAW_ORDER, drawable.drawOrder),
					opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, drawable.opacity),
					multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor),
					screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, drawable.screenColor),
				)
			}
		}
	// Both halves of a deformer key on parameters: the geometry grid and every channel track.  A deformer's
	// shape lives only in its cells, so a sparse grid losing its absent kept cell leaves it no grid where the
	// evaluator had read a zero form.
	val newDeformers =
		deformers.mapIndexed { deformerIndex, deformer ->
			val scrubbed = deformer.channelGrids.withAxisCollapsedLifting(id, keepValue)
			deformerMoves[deformerIndex] = collapseMovesRest(deformer.channelGrids, id, keepValue)
			when (deformer) {
				is Deformer.Warp -> {
					deformerMoves[deformerIndex] = deformerMoves[deformerIndex] || collapseMovesRest(deformer.geometryGrid, id, keepValue)
					val collapsed = deformer.geometryGrid?.withAxisCollapsedKeepingCell(id, keepValue)
					if (collapsed === deformer.geometryGrid && scrubbed.channelGrids === deformer.channelGrids) {
						deformer
					} else {
						deformer.copy(
							geometryGrid = collapsed,
							channelGrids = scrubbed.channelGrids,
							opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, deformer.opacity),
							multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor),
							screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, deformer.screenColor),
						)
					}
				}

				is Deformer.Rotation -> {
					deformerMoves[deformerIndex] = deformerMoves[deformerIndex] || collapseMovesRest(deformer.geometryGrid, id, keepValue)
					val collapsed = deformer.geometryGrid?.withAxisCollapsedKeepingCell(id, keepValue)
					if (collapsed === deformer.geometryGrid && scrubbed.channelGrids === deformer.channelGrids) {
						deformer
					} else {
						deformer.copy(
							geometryGrid = collapsed,
							channelGrids = scrubbed.channelGrids,
							opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, deformer.opacity),
							multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor),
							screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, deformer.screenColor),
							flipX = scrubbed.lifted.flagOr(FormChannel.FLIP_X, deformer.flipX),
							flipY = scrubbed.lifted.flagOr(FormChannel.FLIP_Y, deformer.flipY),
						)
					}
				}
			}
		}
	val newParts =
		parts.mapIndexed { partIndex, part ->
			partMoves[partIndex] = collapseMovesRest(part.channelGrids, id, keepValue)
			val scrubbed = part.channelGrids.withAxisCollapsedLifting(id, keepValue)
			if (scrubbed.channelGrids === part.channelGrids) {
				part
			} else {
				part.copy(
					channelGrids = scrubbed.withFractionalDrawOrderKept().channelGrids,
					drawOrder = scrubbed.lifted.integralDrawOrderOrNull() ?: part.drawOrder,
					composite =
						part.composite.copy(
							opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, part.composite.opacity),
							multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, part.composite.multiplyColor),
							screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, part.composite.screenColor),
						),
				)
			}
		}
	// Glue tracks key on parameters exactly like the rest, so they need the same scrub - without it a
	// deleted parameter survives as a dangling KeyformAxis.parameterId that nothing can ever resolve.
	val newGlues =
		glues.mapIndexed { glueIndex, glue ->
			glueMoves[glueIndex] = collapseMovesRest(glue.channelGrids, id, keepValue)
			val scrubbed = glue.channelGrids.withAxisCollapsedLifting(id, keepValue)
			if (scrubbed.channelGrids === glue.channelGrids) {
				glue
			} else {
				glue.copy(
					channelGrids = scrubbed.channelGrids,
					intensity = scrubbed.lifted.scalarOr(FormChannel.GLUE_INTENSITY, glue.intensity),
				)
			}
		}
	// The blend shapes last, over each owner as collapsed: a form a limit's cap is baked into moves around the
	// owner's look at the default pose, which is the collapsed grid's.
	val scrubbedDrawables =
		newDrawables.mapIndexed { drawableIndex, drawable ->
			val scrub = drawable.blendShapes.scrubbedOf(deleted, defaultOf) { drawable.meshFormScaler(defaultOf) }
			if (scrub.movesRest) {
				drawableMoves[drawableIndex] = true
			}
			if (scrub.bindings === drawable.blendShapes) drawable else drawable.copy(blendShapes = scrub.bindings)
		}
	val scrubbedDeformers =
		newDeformers.mapIndexed { deformerIndex, deformer ->
			when (deformer) {
				is Deformer.Warp -> {
					val scrub = deformer.blendShapes.scrubbedOf(deleted, defaultOf) { deformer.warpFormScaler(defaultOf) }
					if (scrub.movesRest) {
						deformerMoves[deformerIndex] = true
					}
					if (scrub.bindings === deformer.blendShapes) deformer else deformer.copy(blendShapes = scrub.bindings)
				}

				is Deformer.Rotation -> {
					val scrub = deformer.blendShapes.scrubbedOf(deleted, defaultOf) { deformer.rotationFormScaler(defaultOf) }
					if (scrub.movesRest) {
						deformerMoves[deformerIndex] = true
					}
					if (scrub.bindings === deformer.blendShapes) deformer else deformer.copy(blendShapes = scrub.bindings)
				}
			}
		}
	val scrubbedParts =
		newParts.mapIndexed { partIndex, part ->
			val scrub = part.blendShapes.scrubbedOf(deleted, defaultOf) { part.partFormScaler(defaultOf) }
			if (scrub.movesRest) {
				partMoves[partIndex] = true
			}
			if (scrub.bindings === part.blendShapes) part else part.copy(blendShapes = scrub.bindings)
		}
	val restChangedOwners = ArrayList<KeyformOwner>()
	for ((drawableIndex, drawable) in drawables.withIndex()) {
		if (drawableMoves[drawableIndex]) {
			restChangedOwners.add(KeyformOwner.Drawable(drawable.id))
		}
	}
	for ((deformerIndex, deformer) in deformers.withIndex()) {
		if (deformerMoves[deformerIndex]) {
			restChangedOwners.add(KeyformOwner.Deformer(deformer.id))
		}
	}
	for ((partIndex, part) in parts.withIndex()) {
		if (partMoves[partIndex]) {
			restChangedOwners.add(KeyformOwner.Part(part.id))
		}
	}
	for ((glueIndex, glue) in glues.withIndex()) {
		if (glueMoves[glueIndex]) {
			restChangedOwners.add(KeyformOwner.Glue(glue.meshA, glue.meshB))
		}
	}
	val model =
		copy(
			parameters = parameters.filterNot { parameter -> parameter.id == id },
			parameterLinks = parameterLinks.filterNot { link -> link.horizontal == id || link.vertical == id },
			parameterTree = removeParameterLeaf(parameterTree, id),
			drawables = scrubbedDrawables,
			deformers = scrubbedDeformers,
			parts = scrubbedParts,
			glues = newGlues,
		).withDerivedRenderRoot()
	return ParameterDeletion(model, restChangedOwners)
}

/**
 * These collapsed channels with a fractional lifted DRAW_ORDER kept as a zero-axis constant track.
 *
 * A part's static draw-order slot is an Int, so a fractional slice value cannot lift without rounding,
 * and rounding could reorder two parts the track kept distinct.  The deleted axis cannot stay either, so
 * the value is kept as a single-cell track with no axes - the same degenerate shape MOC3 import already
 * produces - which the evaluator reads as a constant.
 *
 * @return CollapsedChannels The channels with the fractional draw order retained as a constant track.
 */
private fun CollapsedChannels.withFractionalDrawOrderKept(): CollapsedChannels {
	if (!lifted.hasFractionalDrawOrder()) {
		return this
	}
	val constantTrack = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), lifted[FormChannel.DRAW_ORDER]!!)))
	return CollapsedChannels(
		ChannelGrids(channelGrids.gridsByChannel + (FormChannel.DRAW_ORDER to constantTrack)),
		lifted,
	)
}

/** This node list with every [ParameterNode.Param] leaf for [id] spliced out, recursing into groups. */
private fun removeParameterLeaf(nodes: List<ParameterNode>, id: ParameterId): List<ParameterNode> =
	nodes.mapNotNull { node ->
		when (node) {
			is ParameterNode.Param -> if (node.id == id) null else node
			is ParameterNode.Group -> node.copy(children = removeParameterLeaf(node.children, id))
		}
	}

/**
 * Creates a new animatable parameter (a freshly minted id, default -1..1 range) named [name] of [kind]
 * and returns its id, so the caller can immediately open inline rename on it. One undo step.
 *
 * @param String name The initial display name.
 * @param ParameterKind kind The parameter kind (NORMAL key-form or BLEND_SHAPE); defaults to NORMAL.
 * @return ParameterId The id of the created parameter.
 */
fun EditorSession.createParameter(name: String, kind: ParameterKind = ParameterKind.NORMAL): ParameterId {
	val id = model.value.freshParameterId()
	mutate(ParameterChange.Create(id)) { model -> model.withParameterCreated(id, name, kind) }
	return id
}

/**
 * Renames parameter [id]'s display name to [newName] (trimmed) as one undo step. A blank or unchanged
 * name records nothing.
 *
 * @param ParameterId id The parameter to rename.
 * @param String newName The new display name.
 */
fun EditorSession.renameParameter(id: ParameterId, newName: String) {
	mutate(ParameterChange.Rename(id, newName.trim())) { model -> model.withParameterRenamed(id, newName) }
}

/**
 * Deletes parameter [id] everywhere - the axis list, the panel tree, any link, every object's keyform
 * grid (its axis collapses to the default slice), and the live pose - as one undo step. A model edit,
 * so it marks the document dirty; dropping the pose entry rides the same step so undo restores both.
 * Goes through [EditorSession.commitStep] rather than [EditorSession.mutate] because it commits a new
 * model, a new pose, and the pruned target together, like [setParameterRange]. A no-op (no such parameter) records nothing. When the delete moves the rest pose
 * of any object - a default between two keys, a sparse sole-axis track or deformer grid, or a blend shape
 * its scrub cannot keep exact ([ParameterDeletion.restChangedOwners]) - a notice says how many.
 *
 * @param ParameterId id The parameter to delete.
 */
fun EditorSession.deleteParameter(id: ParameterId) {
	val before = model.value
	// One walk gives both the model and the owners it moved; asking the question of the model again
	// would walk every grid and binding a second time.
	val deletion = before.parameterDeletionOf(id) ?: return
	val newModel = deletion.model
	val restChanged = deletion.restChangedOwners
	// The target must never dangle on a parameter the model no longer has - pruned in the SAME step, so
	// the pushed snapshot holds the pruned selection and a later redo (or a History jump to this entry)
	// cannot restore the dangling id.
	val prunedTarget = parameterSelection.value.prunedTo(newModel.parameters.mapTo(HashSet()) { parameter -> parameter.id })
	commitStep(ParameterChange.Delete(id), model = newModel, pose = pose.value - id, parameterSelection = prunedTarget)
	if (restChanged.isNotEmpty()) {
		emitNotice("notice.parameter.deleteChangedRest", arguments = listOf(restChanged.size.toString()))
	}
}