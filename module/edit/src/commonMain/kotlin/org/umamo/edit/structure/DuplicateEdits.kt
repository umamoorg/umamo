package org.umamo.edit.structure

import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Duplicates one whole drawable (Object-mode Shift+D): the copy takes a Blender-style ".001"-suffixed
 * unique id and name, a fresh mesh instance (positions copied so the copy edits independently;
 * uvs / indices shared - the copy-on-write discipline), a re-wrapped keyform grid sharing the form
 * payloads by reference (immutable in practice; a later edit copy-on-writes them), and its source's
 * mask references.  It sits immediately after its source in the org tree (or appends at the root when
 * the source was never placed - the import safety net's rule), and carries NO glue membership: glues
 * reference meshes by id, so a fresh id naturally welds nothing.
 *
 * 描画オブジェクトの複製。".001" 形式の一意な id と名前を付け、元の直後にツリー挿入する。
 * グルーには参加しない。
 *
 * @param DrawableId id The drawable to duplicate.
 * @return Pair<PuppetModel, DrawableId>? The edited model and the copy's id, or null when the source
 *   is missing or carries no mesh.
 */
fun PuppetModel.withDrawableDuplicated(id: DrawableId): Pair<PuppetModel, DrawableId>? {
	val source = drawables.firstOrNull { candidate -> candidate.id == id } ?: return null
	val mesh = source.mesh ?: return null
	val copyId = uniqueDuplicateId(id)
	val copy =
		source.copy(
			id = copyId,
			name = uniqueDuplicateName(source.name),
			mesh = duplicatedMesh(mesh),
			geometryGrid =
				source.geometryGrid?.let { grid ->
					KeyformGrid(grid.axes, grid.cells.map { cell -> KeyformCell(cell.coordinate, cell.form) })
				},
			// The atlas mapping is keyed by the source format's ids, so the copy resolves its texture
			// through its source (or the original, when duplicating a duplicate).
			textureSourceId = source.textureSourceId ?: source.id,
		)

	// Insert the copy immediately after its source, in whichever child list holds it.
	var placed = false

	fun insertAfterSource(children: List<OrgChild>): List<OrgChild> {
		if (placed) {
			return children
		}

		val sourceIndex = children.indexOfFirst { child -> child is OrgChild.Drawable && child.id == id }

		if (sourceIndex < 0) {
			return children
		}

		placed = true

		return children.toMutableList().apply { add(sourceIndex + 1, OrgChild.Drawable(copyId)) }
	}
	val newRootChildren = insertAfterSource(rootChildren)
	val newParts = parts.map { part -> if (placed) part else part.copy(children = insertAfterSource(part.children)) }
	val finalRootChildren = if (placed) newRootChildren else newRootChildren + OrgChild.Drawable(copyId)
	val edited =
		copy(
			drawables = drawables + copy,
			parts = newParts,
			rootChildren = finalRootChildren,
		)

	return edited.withDerivedRenderRoot().let { derived -> derived to copyId }
}

/**
 * The first free ".NNN"-suffixed id derived from [id] (d -> d.001, d.002, ...), so repeated duplicates
 * never collide.
 *
 * @param DrawableId id The source id.
 * @return DrawableId The unique copy id.
 */
private fun PuppetModel.uniqueDuplicateId(id: DrawableId): DrawableId {
	var counter = 1

	while (true) {
		val candidate = DrawableId("${id.raw}.${counter.toString().padStart(3, '0')}")

		if (drawables.none { drawable -> drawable.id == candidate }) {
			return candidate
		}

		counter++
	}
}

/**
 * The first free ".NNN"-suffixed display name derived from [name], mirroring [uniqueDuplicateId].
 *
 * @param String name The source display name.
 * @return String The unique copy name.
 */
private fun PuppetModel.uniqueDuplicateName(name: String): String {
	var counter = 1

	while (true) {
		val candidate = "$name.${counter.toString().padStart(3, '0')}"

		if (drawables.none { drawable -> drawable.name == candidate }) {
			return candidate
		}

		counter++
	}
}

/**
 * Duplicates one warp (Object-mode Shift+D): the copy takes a Blender-style ".001"-suffixed unique id and name and
 * the same forms - its keyform grid re-wrapped, the form payloads and blend shapes shared by reference (immutable in
 * practice; a later edit copy-on-writes them) - under the same parent, right after its source in the deformer list.
 *
 * @param DeformerId id The warp to duplicate.
 * @return Pair<PuppetModel, DeformerId>? The edited model and the copy's id, or null when the model holds no warp
 *   by that id.
 */
fun PuppetModel.withDeformerDuplicated(id: DeformerId): Pair<PuppetModel, DeformerId>? {
	val sourceIndex = deformers.indexOfFirst { candidate -> candidate.id == id }
	val source = deformers.getOrNull(sourceIndex) as? Deformer.Warp ?: return null
	val copyId = uniqueDuplicateDeformerId(id)
	val copy =
		source.copy(
			id = copyId,
			name = uniqueDuplicateDeformerName(source.name),
			geometryGrid =
				source.geometryGrid?.let { grid ->
					KeyformGrid(grid.axes, grid.cells.map { cell -> KeyformCell(cell.coordinate, cell.form) })
				},
		)

	return copy(deformers = deformers.toMutableList().apply { add(sourceIndex + 1, copy) }) to copyId
}

/**
 * The first free ".NNN"-suffixed deformer id derived from [id], as [uniqueDuplicateId] does for drawables.
 *
 * @param DeformerId id The source id.
 * @return DeformerId The unique copy id.
 */
private fun PuppetModel.uniqueDuplicateDeformerId(id: DeformerId): DeformerId {
	var counter = 1

	while (true) {
		val candidate = DeformerId("${id.raw}.${counter.toString().padStart(3, '0')}")

		if (deformers.none { deformer -> deformer.id == candidate }) {
			return candidate
		}

		counter++
	}
}

/**
 * The first free ".NNN"-suffixed deformer name derived from [name], mirroring [uniqueDuplicateDeformerId].
 *
 * @param String name The source display name.
 * @return String The unique copy name.
 */
private fun PuppetModel.uniqueDuplicateDeformerName(name: String): String {
	var counter = 1

	while (true) {
		val candidate = "$name.${counter.toString().padStart(3, '0')}"

		if (deformers.none { deformer -> deformer.name == candidate }) {
			return candidate
		}

		counter++
	}
}

/**
 * A copy of [mesh] with its own position arrays, so moving the duplicate never moves the original; a mesh whose
 * canvas and keyform-space positions are one shared array stays shared in the copy.
 *
 * @param DrawableMesh mesh The mesh to copy.
 * @return DrawableMesh The copy.
 */
private fun duplicatedMesh(mesh: DrawableMesh): DrawableMesh {
	val positions = mesh.positions.copyOf()
	val localPositions = if (mesh.localPositions === mesh.positions) positions else mesh.localPositions.copyOf()

	return DrawableMesh(positions, localPositions, mesh.uvs, mesh.indices)
}

/**
 * Duplicates every selected meshed drawable and every selected warp (Object-mode Shift+D) as ONE undo step, keeping
 * the hierarchy among the copies: a copy whose source's parent warp was copied too sits under that parent's copy,
 * and every other copy keeps its source's parent (Blender).  An unselected child stays with its original.  Each
 * drawable copy lands after its source in the org tree, and the selection becomes the copies - the caller follows
 * with a Grab so they pull away under the pointer, Blender-style, and the Grab moves a copied warp with its copied
 * children inside it.  The active original's copy becomes the active target (else the last copy), and the
 * remembered owner follows it exactly as it follows a selection gesture.
 *
 * @return List<SelectionTarget> The created copies (empty when nothing was eligible).
 */
fun EditorSession.duplicateSelectedObjects(): List<SelectionTarget> {
	if (mode.value != EditorMode.Object) {
		return emptyList()
	}

	val source = model.value
	val selected = selection.value
	var newModel = source
	val drawableCopies = LinkedHashMap<DrawableId, DrawableId>()
	val warpCopies = LinkedHashMap<DeformerId, DeformerId>()

	for (target in selected.targets) {
		when (target) {
			is SelectionTarget.Drawable -> {
				val (edited, copyId) = newModel.withDrawableDuplicated(target.id) ?: continue
				newModel = edited
				drawableCopies[target.id] = copyId
			}

			is SelectionTarget.Deformer -> {
				val (edited, copyId) = newModel.withDeformerDuplicated(target.id) ?: continue
				newModel = edited
				warpCopies[target.id] = copyId
			}

			is SelectionTarget.Part -> {}
		}
	}

	if (drawableCopies.isEmpty() && warpCopies.isEmpty()) {
		return emptyList()
	}

	// The hierarchy among the copies: a copy under a copied parent moves under that parent's copy.
	val drawableCopyIds = drawableCopies.values.toSet()
	val warpCopyIds = warpCopies.values.toSet()
	newModel =
		newModel.copy(
			drawables =
				newModel.drawables.map { drawable ->
					val parentCopy = drawable.parentDeformerId?.let { parentId -> warpCopies[parentId] }
					if (drawable.id in drawableCopyIds && parentCopy != null) drawable.copy(parentDeformerId = parentCopy) else drawable
				},
			deformers =
				newModel.deformers.map { deformer ->
					val parentCopy = deformer.parent?.let { parentId -> warpCopies[parentId] }
					if (deformer.id in warpCopyIds && deformer is Deformer.Warp && parentCopy != null) deformer.copy(parent = parentCopy) else deformer
				},
		)

	val copies =
		drawableCopies.values.map { copyId -> SelectionTarget.Drawable(copyId) } +
			warpCopies.values.map { copyId -> SelectionTarget.Deformer(copyId) }
	val activeCopy =
		when (val active = selected.active) {
			is SelectionTarget.Drawable -> drawableCopies[active.id]?.let { copyId -> SelectionTarget.Drawable(copyId) }
			is SelectionTarget.Deformer -> warpCopies[active.id]?.let { copyId -> SelectionTarget.Deformer(copyId) }
			else -> null
		} ?: copies.last()
	val newSelection = Selection(copies.toSet(), activeCopy)
	// The remembered owner tracks whatever is shown active (the rule setSelection and restore keep).
	elementMemory.lastActiveOwner =
		when (activeCopy) {
			is SelectionTarget.Drawable -> ElementOwner.Drawable(activeCopy.id)
			is SelectionTarget.Deformer -> ElementOwner.Deformer(activeCopy.id)
			is SelectionTarget.Part -> elementMemory.lastActiveOwner
		}
	commitStep(DrawableChange.Duplicate(drawableCopies.values.toList(), warpCopies.values.toList()), model = newModel, selection = newSelection)

	return copies
}