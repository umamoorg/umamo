package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs

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
 * A copy of this model with a new animatable parameter [newId] named [name] appended to the axis list and
 * its leaf prepended at the root top of the panel tree (so it lands on-screen for the immediate inline
 * rename, like a new group). The default -1..1 / 0 range makes it animatable (min < max) so it renders as
 * a slider; it is a neutral starting point the user retargets in the range editor. Materializes a flat
 * tree first for a group-less model, so the existing rows keep their order beneath the new parameter.
 * Refuses (returns this same instance) a colliding id.
 *
 * @param ParameterId newId The minted id of the new parameter.
 * @param String name The initial display name.
 * @return PuppetModel The model with the parameter added, or this if the id already exists.
 */
fun PuppetModel.withParameterCreated(newId: ParameterId, name: String): PuppetModel {
	if (parameters.any { parameter -> parameter.id == newId }) {
		return this
	}
	val newParameter = Parameter(newId, name, min = -1f, max = 1f, default = 0f)
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
 * link it belongs to (its partner reverts to a plain slider), and every object's keyform grid - dropping
 * the deleted axis collapses each grid to the slice at the parameter's default value (the neutral look),
 * discarding that axis's motion; a grid left with no axes becomes null (the object is unkeyed). The live
 * pose entry is dropped by the session wrapper. A no-op (no such parameter) returns this same instance.
 *
 * @param ParameterId id The parameter to delete.
 * @return PuppetModel The model with the parameter removed, or this if it was absent.
 */
fun PuppetModel.withParameterDeleted(id: ParameterId): PuppetModel {
	val deleted = parameters.firstOrNull { parameter -> parameter.id == id } ?: return this
	val keepValue = deleted.default
	val newDrawables =
		drawables.map { drawable ->
			val grid = drawable.keyforms ?: return@map drawable
			val collapsed = grid.withAxisCollapsed(id, keepValue)
			if (collapsed === grid) {
				drawable
			} else {
				drawable.copy(keyforms = collapsed)
			}
		}
	val newDeformers =
		deformers.map { deformer ->
			when (deformer) {
				is Deformer.Warp -> {
					val grid = deformer.keyforms ?: return@map deformer
					val collapsed = grid.withAxisCollapsed(id, keepValue)
					if (collapsed === grid) {
						deformer
					} else {
						deformer.copy(keyforms = collapsed)
					}
				}

				is Deformer.Rotation -> {
					val grid = deformer.keyforms ?: return@map deformer
					val collapsed = grid.withAxisCollapsed(id, keepValue)
					if (collapsed === grid) {
						deformer
					} else {
						deformer.copy(keyforms = collapsed)
					}
				}
			}
		}
	val newParts =
		parts.map { part ->
			val grid = part.drawOrderGrid ?: return@map part
			val collapsed = grid.withAxisCollapsed(id, keepValue)
			if (collapsed === grid) {
				part
			} else {
				part.copy(drawOrderGrid = collapsed)
			}
		}
	return copy(
		parameters = parameters.filterNot { parameter -> parameter.id == id },
		parameterLinks = parameterLinks.filterNot { link -> link.horizontal == id || link.vertical == id },
		parameterTree = removeParameterLeaf(parameterTree, id),
		drawables = newDrawables,
		deformers = newDeformers,
		parts = newParts,
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
 * This grid with the axis for [parameterId] removed, collapsing that dimension to the key nearest
 * [keepKeyValue]. Returns this same instance when the grid has no such axis (so a caller can skip
 * untouched entities by identity), or null when removing the axis leaves no axes at all (the entity
 * becomes unkeyed). The surviving cells are those whose coordinate on the dropped axis is the kept key
 * index, each re-projected to the N-1 coordinate; the form payloads are carried through untouched.
 *
 * @param ParameterId parameterId The axis parameter to remove.
 * @param Float keepKeyValue The parameter value whose nearest key slice survives (the deleted default).
 * @return KeyformGrid The collapsed grid, this if the axis was absent, or null if no axes remain.
 */
internal fun <TForm> KeyformGrid<TForm>.withAxisCollapsed(parameterId: ParameterId, keepKeyValue: Float): KeyformGrid<TForm>? {
	val axisIndex = axes.indexOfFirst { axis -> axis.parameterId == parameterId }
	if (axisIndex < 0) {
		return this
	}
	val newAxes = axes.filterIndexed { index, _ -> index != axisIndex }
	if (newAxes.isEmpty()) {
		return null
	}
	val keepIndex = nearestKeyIndex(axes[axisIndex].keys, keepKeyValue)
	val newCells =
		cells
			.filter { cell -> cell.coordinate[axisIndex] == keepIndex }
			.map { cell -> KeyformCell(cell.coordinate.withElementRemoved(axisIndex), cell.form) }
	return KeyformGrid(newAxes, newCells)
}

/** The index of the key in [keys] nearest [value] (0 for an empty axis, defensively). */
private fun nearestKeyIndex(keys: FloatArray, value: Float): Int {
	if (keys.isEmpty()) {
		return 0
	}
	var bestIndex = 0
	var bestDistance = abs(keys[0] - value)
	for (keyIndex in 1 until keys.size) {
		val distance = abs(keys[keyIndex] - value)
		if (distance < bestDistance) {
			bestDistance = distance
			bestIndex = keyIndex
		}
	}
	return bestIndex
}

/** A copy of this coordinate array with the element at [removeIndex] dropped (length shrinks by one). */
private fun IntArray.withElementRemoved(removeIndex: Int): IntArray {
	val result = IntArray(size - 1)
	var writeIndex = 0
	for (readIndex in indices) {
		if (readIndex != removeIndex) {
			result[writeIndex] = this[readIndex]
			writeIndex++
		}
	}
	return result
}

/**
 * Creates a new animatable parameter (a freshly minted id, default -1..1 range) named [name] and returns
 * its id, so the caller can immediately open inline rename on it. One undo step.
 *
 * @param String name The initial display name.
 * @return ParameterId The id of the created parameter.
 */
fun EditorSession.createParameter(name: String): ParameterId {
	val id = model.value.freshParameterId()
	mutate(ParameterChange.Create(id)) { model -> model.withParameterCreated(id, name) }
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
