package org.umamo.edit.mesh

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/*
 * Mesh array edits: the copy-on-write folds that replace a drawable's rest shape (the canvas mesh plus the
 * keyform-space base) or its texture coordinates, one drawable or many in one pass, and the session commits
 * that record a finished modal gesture over them as ONE undo step.  Copy-on-write at the mesh leaf: a new
 * DrawableMesh shares every array it did not change, so a prior snapshot's arrays are never mutated.
 */

/**
 * Returns a copy of [this] with the drawable [id]'s rest shape replaced by [rest] - both the canvas editable
 * mesh and the keyform-space base the deltas are measured from - sharing every other drawable and the rest
 * of the model.  The one-drawable form of the batch below, under its rules.
 *
 * The caller must pass freshly built arrays (e.g. from [MeshTransforms]); never the live mesh arrays.
 *
 * @param DrawableId        id   The drawable whose mesh to retarget.
 * @param MeshRestPositions rest The new canvas mesh and base, each the current length.
 * @return PuppetModel The model with that mesh updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshPositions(id: DrawableId, rest: MeshRestPositions): PuppetModel = withMeshPositions(mapOf(id to rest))

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
	updated[index] = updated[index].copy(mesh = mesh.withUvs(newUvs))
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with several drawables' rest shapes replaced at once - each the canvas editable
 * mesh and the keyform-space base the deltas are measured from - in one pass over the drawables rather than
 * one per entry.  Copy-on-write at the mesh leaf: each new [DrawableMesh] shares the unchanged uvs / indices
 * arrays, so a prior snapshot's arrays are never mutated.  No keyform delta is touched: a delta measured from
 * the moved base moves with it, which is what keeps every keyed shape following a rest-shape edit.  An
 * unknown id, a drawable with no mesh, both arrays the instances it holds, or a length mismatch on either is
 * skipped, and only the first drawable of an id is touched.  The drawable list is copied on the first real
 * change and every untouched drawable is shared, and when nothing changes the same instance comes back,
 * which is how the session tells a no-op commit from an edit.
 *
 * @param Map<DrawableId, MeshRestPositions> restById Each drawable's new canvas mesh and base.
 * @return PuppetModel The model with those meshes updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshPositions(restById: Map<DrawableId, MeshRestPositions>): PuppetModel =
	withMeshEntries(
		restById,
		{ mesh, rest ->
			(rest.positions === mesh.positions && rest.localPositions === mesh.localPositions) ||
				rest.positions.size != mesh.positions.size ||
				rest.localPositions.size != mesh.localPositions.size
		},
	) { mesh, rest -> DrawableMesh(positions = rest.positions, localPositions = rest.localPositions, uvs = mesh.uvs, indices = mesh.indices) }

/**
 * Returns a copy of [this] with several drawables' texture UVs replaced at once: the batch form of
 * [withMeshUvs], under the same rules as the positions batch.
 *
 * @param Map<DrawableId, FloatArray> newUvsById Each drawable's new interleaved (u, v) atlas coordinates.
 * @return PuppetModel The model with those meshes' UVs updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshUvs(newUvsById: Map<DrawableId, FloatArray>): PuppetModel =
	withMeshEntries(newUvsById, { mesh, uvs -> uvs === mesh.uvs || uvs.size != mesh.uvs.size }) { mesh, uvs -> mesh.withUvs(uvs) }

/**
 * The one pass both batch folds share: each drawable named in [entriesById], at its first occurrence, takes
 * its entry through [rebuild] unless it has no mesh or [skips] says the entry changes nothing it can apply.
 *
 * @param Map<DrawableId, TEntry> entriesById The new values by drawable.
 * @param Function skips Whether an entry leaves a mesh as it is: the arrays it already holds, or a length mismatch.
 * @param Function rebuild A new mesh carrying the entry.
 * @return PuppetModel The edited model, or [this] if nothing changed.
 */
private inline fun <TEntry> PuppetModel.withMeshEntries(
	entriesById: Map<DrawableId, TEntry>,
	skips: (DrawableMesh, TEntry) -> Boolean,
	rebuild: (DrawableMesh, TEntry) -> DrawableMesh,
): PuppetModel {
	if (entriesById.isEmpty()) {
		return this
	}
	var updated: MutableList<Drawable>? = null
	val visited = HashSet<DrawableId>(entriesById.size)
	for ((drawableIndex, drawable) in drawables.withIndex()) {
		val next = entriesById[drawable.id] ?: continue
		if (!visited.add(drawable.id)) {
			continue
		}
		val mesh = drawable.mesh
		if (mesh == null || skips(mesh, next)) {
			continue
		}
		val target = updated ?: drawables.toMutableList().also { copied -> updated = copied }
		target[drawableIndex] = drawable.copy(mesh = rebuild(mesh, next))
	}
	return updated?.let { edited -> copy(drawables = edited) } ?: this
}

/**
 * Commits a mesh-vertex edit (a finished modal G / S / R gesture) as ONE undo step: each session
 * drawable's rest shape (canvas mesh and keyform-space base) becomes its entry in [restByDrawable].  An
 * Edit session spans several meshes, so the copy-on-write [withMeshPositions] batch folds them into a
 * single model (one history step, like [commitObjectPositions]).  Mid-gesture preview frames reach
 * the renderer directly (transient), so a whole drag is a single step.  A model edit (rest geometry
 * is document content), so it marks the document dirty; a no-op (every array unchanged / mismatched)
 * records nothing.
 *
 * @param MeshChange change The edit descriptor (a [MeshChange.TransformVertices]).
 * @param Map<DrawableId, MeshRestPositions> restByDrawable Each edited drawable's committed rest shape.
 */
fun EditorSession.commitMeshPositions(change: MeshChange, restByDrawable: Map<DrawableId, MeshRestPositions>) {
	mutate(change) { current -> current.withMeshPositions(restByDrawable) }
}

/**
 * Commits an Object-mode transform of several drawables (a finished modal G / S / R gesture) as ONE undo
 * step: each drawable's rest shape (canvas mesh and keyform-space base) becomes its entry in
 * [restByDrawable]. The copy-on-write [withMeshPositions] batch folds them into a single model, so N moved
 * drawables are one history step (not N). Mid-gesture preview frames reach the renderer directly
 * (transient), so a whole drag is a single step. A model edit (rest geometry is document content), so it
 * marks the document dirty; a no-op (every array unchanged / mismatched, so the fold returns the same
 * instance) records nothing.
 *
 * @param MeshChange change The edit descriptor (a [MeshChange.TransformDrawables]).
 * @param Map<DrawableId, MeshRestPositions> restByDrawable Each moved drawable's committed rest shape.
 */
fun EditorSession.commitObjectPositions(change: MeshChange, restByDrawable: Map<DrawableId, MeshRestPositions>) {
	mutate(change) { current -> current.withMeshPositions(restByDrawable) }
}

/**
 * Commits a UV edit (a finished modal G / S / R gesture in the UV editor, or a Mirror command) as
 * ONE undo step: each edited drawable's texture coordinates become its entry in [newUvsByDrawable].
 * The texture-mapping twin of [commitMeshPositions] - the copy-on-write [withMeshUvs] batch folds the
 * edits into a single model, so N edited meshes are one history step.  Mid-gesture preview
 * frames reach the renderer directly (transient), so a whole drag is a single step.  A model edit
 * (the sampled texels are document content), so it marks the document dirty; a no-op (every
 * array unchanged / mismatched) records nothing.
 *
 * @param MeshChange change The edit descriptor (a [MeshChange.TransformUvs] or [MeshChange.MirrorUvs]).
 * @param Map<DrawableId, FloatArray> newUvsByDrawable Each edited drawable's committed atlas UVs.
 */
fun EditorSession.commitMeshUvs(change: MeshChange, newUvsByDrawable: Map<DrawableId, FloatArray>) {
	mutate(change) { current -> current.withMeshUvs(newUvsByDrawable) }
}