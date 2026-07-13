package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * Pure transforms over the immutable PuppetModel: each returns a new model that structurally shares
 * every unchanged entity with its input (a data class copy replaces only the touched list element),
 * so producing a snapshot costs O(changed spine), not O(model). They never mutate their input, so they
 * are trivially unit-testable and safe to use as undo snapshots. This is the model-mutation half of the
 * editing core; the EditorSession wraps these with history and change events.
 *
 * 不変な PuppetModel への純粋変換。変更点だけを差し替え、残りは構造共有する。入力は変更しない。
 */

/**
 * Returns a copy of [this] with the part [id]'s Parts-panel visibility set to [visible], sharing every
 * other part and the rest of the model. A no-op id (no such part, or the flag already matches) returns
 * the same instance, so callers can compare by reference to detect a real change.
 *
 * @param PartId id The part to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with that part's visibility updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartVisibility(id: PartId, visible: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isVisible == visible) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isVisible = visible)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s own Parts-panel visibility set to [visible], sharing
 * every other drawable and the rest of the model. A no-op id (no such drawable, or the flag already
 * matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with that drawable's visibility updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableVisibility(id: DrawableId, visible: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].isVisible == visible) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(isVisible = visible)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s display name set to [name], sharing every other entity.
 * A no-op id (no such part, or the name already matches) returns the same instance.
 *
 * @param PartId id The part to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that part renamed, or [this] if nothing changed.
 */
fun PuppetModel.withPartName(id: PartId, name: String): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].name == name) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(name = name)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s display name set to [name], sharing every other
 * entity. A no-op id (no such drawable, or the name already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that drawable renamed, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableName(id: DrawableId, name: String): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].name == name) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(name = name)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s display name set to [name], sharing every other
 * entity. Handles both deformer kinds. A no-op id (no such deformer, or the name already matches)
 * returns the same instance.
 *
 * @param DeformerId id The deformer to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that deformer renamed, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerName(id: DeformerId, name: String): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].name == name) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(name = name)
			is Deformer.Rotation -> deformer.copy(name = name)
		}
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s selectable flag set to [selectable], sharing every other
 * entity. A no-op id (no such part, or the flag already matches) returns the same instance.
 *
 * @param PartId id The part whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that part's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartSelectable(id: PartId, selectable: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isSelectable == selectable) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isSelectable = selectable)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s selectable flag set to [selectable], sharing every
 * other entity. A no-op id (no such drawable, or the flag already matches) returns the same instance.
 *
 * @param DrawableId id The drawable whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that drawable's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableSelectable(id: DrawableId, selectable: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].isSelectable == selectable) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(isSelectable = selectable)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s selectable flag set to [selectable], handling both
 * deformer kinds and sharing every other entity. A no-op id returns the same instance.
 *
 * @param DeformerId id The deformer whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that deformer's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerSelectable(id: DeformerId, selectable: Boolean): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].isSelectable == selectable) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(isSelectable = selectable)
			is Deformer.Rotation -> deformer.copy(isSelectable = selectable)
		}
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s base art-mesh positions replaced by [newPositions],
 * sharing every other drawable and the rest of the model. Copy-on-write at the mesh leaf: it wraps
 * [newPositions] in a NEW [DrawableMesh] and shares the unchanged uvs / indices arrays by reference, so a
 * prior snapshot's positions array is never mutated. A no-op (no such drawable, no mesh, the same array
 * instance, or a length mismatch - vertex count is fixed in this slice) returns the same instance so the
 * session records nothing.
 *
 * The caller must pass a freshly built array (e.g. from [MeshTransforms]); never the live mesh array.
 *
 * @param DrawableId id The drawable whose mesh to retarget.
 * @param FloatArray newPositions The new interleaved (x, y) rest positions, same length as the current.
 * @return PuppetModel The model with that mesh updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshPositions(id: DrawableId, newPositions: FloatArray): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0) {
		return this
	}
	val mesh = drawables[index].mesh
	if (mesh == null || newPositions === mesh.positions || newPositions.size != mesh.positions.size) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(mesh = DrawableMesh(newPositions, mesh.uvs, mesh.indices))
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s texture UVs replaced by [newUvs], sharing every
 * other drawable and the rest of the model. The mirror image of [withMeshPositions], copy-on-write at
 * the mesh leaf: it wraps [newUvs] in a NEW [DrawableMesh] and shares the unchanged positions / indices
 * arrays by reference, so retargeting which atlas texels a mesh samples never disturbs its rest
 * geometry - the mesh/UV decoupling invariant seen from the UV side. A no-op (no such drawable, no
 * mesh, the same array instance, or a length mismatch - vertex count never changes here) returns the
 * same instance so the session records nothing.
 *
 * The caller must pass a freshly built array (e.g. from [MeshTransforms]); never the live mesh array.
 *
 * @param DrawableId id The drawable whose texture mapping to retarget.
 * @param FloatArray newUvs The new interleaved (u, v) atlas coordinates, same length as the current.
 * @return PuppetModel The model with that mesh's UVs updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshUvs(id: DrawableId, newUvs: FloatArray): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0) {
		return this
	}
	val mesh = drawables[index].mesh
	if (mesh == null || newUvs === mesh.uvs || newUvs.size != mesh.uvs.size) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(mesh = DrawableMesh(mesh.positions, newUvs, mesh.indices))
	return copy(drawables = updated)
}
