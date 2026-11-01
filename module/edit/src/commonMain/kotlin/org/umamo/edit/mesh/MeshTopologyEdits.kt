package org.umamo.edit.mesh

import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.NoticePlacement
import org.umamo.edit.editTopologyOf
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.meshOf

/**
 * How one vertex of a topology-edited mesh derives its keyform deltas, its keyform-space base, and its
 * glue identity from the old mesh's vertices.  A topology operation changes the vertex count, and every
 * keyform cell's positionDeltas array is parallel to the positions (stride 2 * vertexCount) - so each
 * NEW vertex must say where its per-cell deltas come from:
 *
 *   - [FromOld] copies one old vertex's deltas verbatim (a kept or duplicated vertex).
 *   - [AverageOf] averages several old vertices' deltas (a merge's surviving vertex).
 *   - [LerpOf] interpolates two old vertices' deltas by t (a split point on an old edge).
 *   - [Weighted] blends any old vertices' deltas by weights summing to one (a regenerated mesh's
 *     vertex, placed by where it falls in the old mesh).
 */
sealed interface VertexSource {
	/**
	 * The new vertex is old vertex [oldIndex], kept or duplicated: its deltas copy verbatim.
	 *
	 * @property Int oldIndex The old vertex the deltas copy from.
	 */
	data class FromOld(val oldIndex: Int) : VertexSource

	/**
	 * The new vertex merges several old vertices: its deltas are their per-cell mean.
	 *
	 * @property List<Int> oldIndices The merged old vertices.
	 */
	data class AverageOf(val oldIndices: List<Int>) : VertexSource

	/**
	 * The new vertex splits the old edge (oldA, oldB) at parameter [t]: its deltas lerp accordingly.
	 *
	 * @property Int oldA The edge's first old endpoint.
	 * @property Int oldB The edge's second old endpoint.
	 * @property Float t The split parameter (0 = at oldA, 1 = at oldB).
	 */
	data class LerpOf(val oldA: Int, val oldB: Int, val t: Float) : VertexSource

	/**
	 * The new vertex blends old vertices by weights summing to one: its deltas are the same weighted
	 * sum.  It claims no old identity, so no glue pair follows it - only a vertex that IS an old one
	 * carries that vertex's glue.  A plain class, since it holds arrays.
	 *
	 * @property IntArray   oldIndices The old vertices blended, at least one.
	 * @property FloatArray weights    One weight per old vertex, in the same order.
	 */
	class Weighted(val oldIndices: IntArray, val weights: FloatArray) : VertexSource {
		init {
			require(oldIndices.isNotEmpty()) { "a weighted source needs at least one old vertex" }
			require(weights.size == oldIndices.size) { "${weights.size} weights for ${oldIndices.size} old vertices" }
		}
	}
}

/**
 * One topology edit of one drawable's mesh, ready to commit: the complete replacement mesh (canvas
 * positions, keyform-space base, uvs, and indices all sized to the new vertex count; the base already
 * carried over by [editedMesh] or by the mesh regeneration builder) plus one [VertexSource] per NEW
 * vertex, from which [withMeshTopologyEdit] rebuilds every keyform cell's deltas and remaps the glue
 * pairs.  The op builders in MeshTopologyOps produce these; they never touch the model themselves.
 *
 * @property DrawableMesh newMesh The replacement mesh (a freshly built instance).
 * @property List<VertexSource> vertexSources One source per new vertex, in vertex order.
 */
class MeshTopologyEdit(
	val newMesh: DrawableMesh,
	val vertexSources: List<VertexSource>,
)

/**
 * Applies a topology edit to one drawable: swaps in the full replacement mesh, rebuilds EVERY keyform
 * cell's positionDeltas to the new vertex count per the edit's [VertexSource]s (copy / average / lerp / blend -
 * so a parameter scrub after the edit still deforms sanely), and remaps the model's glue pairs through
 * the old-to-new vertex mapping (a pair whose vertex was removed is dropped rather than left dangling).
 * Everything else structurally shares with the receiver (the copy-on-write discipline).  Returns the
 * same instance when the drawable is missing, carries no mesh, or the edit's source list does not match
 * its new mesh (a malformed edit must not corrupt the model).
 *
 * @param DrawableId id The drawable whose mesh the edit replaces.
 * @param MeshTopologyEdit edit The edit to apply.
 * @return PuppetModel The edited model, or this instance on a no-op.
 */
fun PuppetModel.withMeshTopologyEdit(id: DrawableId, edit: MeshTopologyEdit): PuppetModel {
	val drawable = drawables.firstOrNull { candidate -> candidate.id == id } ?: return this
	val oldMesh = drawable.mesh ?: return this
	val newVertexCount = edit.newMesh.vertexCount

	if (edit.vertexSources.size != newVertexCount) {
		return this
	}

	// Rebuild each geometry cell's deltas at the new stride, deriving per vertex from the old deltas.
	// The channel tracks (multiply / screen tints, opacity, draw order, ...) carry no per-vertex data, so a
	// topology edit leaves them untouched entirely - only the per-vertex geometry cells need this remap.
	val newGeometry =
		drawable.geometryGrid?.let { grid ->
			KeyformGrid(
				axes = grid.axes,
				cells =
					grid.cells.map { cell ->
						KeyformCell(cell.coordinate, remapMeshDeltas(cell.form, edit.vertexSources, oldMesh.vertexCount))
					},
			)
		}
	// Blend-shape forms are per-vertex too, so they need the same remap to the new vertex count; leaving
	// them at the old count would silently zero every blend contribution after a topology edit.
	val newBlendShapes =
		drawable.blendShapes.map { binding ->
			binding.copy(
				forms =
					binding.forms.map { form ->
						form?.let {
							MeshForm(
								remapMeshDeltas(MeshDeltaForm(it.positionDeltas), edit.vertexSources, oldMesh.vertexCount).positionDeltas,
								it.drawOrder,
								it.opacity,
								it.multiplyColor,
								it.screenColor,
							)
						}
					},
			)
		}

	// The old-to-new vertex mapping glue remapping resolves through: a kept / duplicated / merged old
	// vertex maps to the FIRST new vertex that claims it; an unclaimed old vertex maps to -1 (removed).
	// First-claim is deliberate for duplicates: the kept originals (enumerated before the copies) claim
	// their old indices, so a glue weld stays on the original and the copy floats free.
	val oldToNewIndex = IntArray(oldMesh.vertexCount) { -1 }
	edit.vertexSources.forEachIndexed { newIndex, source ->
		when (source) {
			is VertexSource.FromOld -> {
				if (source.oldIndex in 0 until oldMesh.vertexCount && oldToNewIndex[source.oldIndex] == -1) {
					oldToNewIndex[source.oldIndex] = newIndex
				}
			}

			is VertexSource.AverageOf -> {
				for (oldIndex in source.oldIndices) {
					if (oldIndex in 0 until oldMesh.vertexCount && oldToNewIndex[oldIndex] == -1) {
						oldToNewIndex[oldIndex] = newIndex
					}
				}
			}

			// A split point and an interpolated vertex are brand-new vertices; they claim no old identity.
			is VertexSource.LerpOf, is VertexSource.Weighted -> {}
		}
	}

	val newDrawables =
		drawables.map { candidate ->
			if (candidate.id == id) {
				candidate.copy(mesh = edit.newMesh, geometryGrid = newGeometry, blendShapes = newBlendShapes)
			} else {
				candidate
			}
		}
	val newGlues =
		glues.map { glue ->
			if (glue.meshA != id && glue.meshB != id) {
				glue
			} else {
				val remappedPairs =
					glue.pairs.mapNotNull { pair ->
						val newIndexA = if (glue.meshA == id) oldToNewIndex.getOrElse(pair.indexA) { -1 } else pair.indexA
						val newIndexB = if (glue.meshB == id) oldToNewIndex.getOrElse(pair.indexB) { -1 } else pair.indexB

						if (newIndexA < 0 || newIndexB < 0) {
							// The welded vertex no longer exists: the pair is dropped, never left dangling.
							null
						} else {
							GluePair(newIndexA, newIndexB, pair.weightA, pair.weightB)
						}
					}
				glue.copy(pairs = remappedPairs)
			}
		}

	return copy(drawables = newDrawables, glues = newGlues)
}

/**
 * Applies topology edits to several drawables at once, in the map's order.  Each pass maps only its own
 * drawable's side of a glue pair through its own old mesh, so the passes compose in any order - a glue
 * between two edited drawables ends up remapped on both sides.
 *
 * @param Map<DrawableId, MeshTopologyEdit> edits The edit for each drawable.
 * @return PuppetModel? The edited model, or null when any edit does not apply (so no caller ever keeps a partial result).
 */
fun PuppetModel.withMeshTopologyEdits(edits: Map<DrawableId, MeshTopologyEdit>): PuppetModel? {
	var edited = this

	for ((drawableId, edit) in edits) {
		val next = edited.withMeshTopologyEdit(drawableId, edit)

		if (next === edited) {
			return null
		}

		edited = next
	}

	return edited
}

/**
 * Rebuilds one keyform cell's [MeshDeltaForm] at the new vertex count: its deltas through [remapPerVertex],
 * one (x, y) pair per new vertex.
 *
 * @param MeshDeltaForm form The old cell form (deltas at the old stride).
 * @param List<VertexSource> vertexSources One source per new vertex.
 * @param Int oldVertexCount The old mesh's vertex count (bounds the old delta reads).
 * @return MeshDeltaForm The rebuilt form (deltas at the new stride).
 */
private fun remapMeshDeltas(form: MeshDeltaForm, vertexSources: List<VertexSource>, oldVertexCount: Int): MeshDeltaForm =
	MeshDeltaForm(remapPerVertex(form.positionDeltas, vertexSources, oldVertexCount))

/**
 * Rebuilds a per-vertex (x, y) array at the new vertex count: each new vertex's pair copies, averages,
 * lerps, or blends from the old array per its [VertexSource] - the one recipe a topology edit applies to
 * every per-vertex array it carries over (keyform deltas, blend-shape deltas, the keyform-space base).  An
 * old index beyond the old array (a malformed grid) contributes zero, keeping the rebuild total.  A
 * weighted sum starts from its first term, so a single weight of one copies the old value exactly,
 * negative zero included.
 *
 * @param FloatArray values The old interleaved (x, y) values.
 * @param List<VertexSource> vertexSources One source per new vertex.
 * @param Int oldVertexCount The old mesh's vertex count (bounds the old reads).
 * @return FloatArray The rebuilt values, two per new vertex.
 */
internal fun remapPerVertex(values: FloatArray, vertexSources: List<VertexSource>, oldVertexCount: Int): FloatArray {
	fun oldX(oldIndex: Int): Float = if (oldIndex in 0 until oldVertexCount && oldIndex * 2 < values.size) values[oldIndex * 2] else 0f

	fun oldY(oldIndex: Int): Float = if (oldIndex in 0 until oldVertexCount && oldIndex * 2 + 1 < values.size) values[oldIndex * 2 + 1] else 0f
	val newValues = FloatArray(vertexSources.size * 2)
	vertexSources.forEachIndexed { newIndex, source ->
		when (source) {
			is VertexSource.FromOld -> {
				newValues[newIndex * 2] = oldX(source.oldIndex)
				newValues[newIndex * 2 + 1] = oldY(source.oldIndex)
			}

			is VertexSource.AverageOf -> {
				if (source.oldIndices.isNotEmpty()) {
					var sumX = 0f
					var sumY = 0f

					for (oldIndex in source.oldIndices) {
						sumX += oldX(oldIndex)
						sumY += oldY(oldIndex)
					}

					newValues[newIndex * 2] = sumX / source.oldIndices.size
					newValues[newIndex * 2 + 1] = sumY / source.oldIndices.size
				}
			}

			is VertexSource.LerpOf -> {
				newValues[newIndex * 2] = oldX(source.oldA) + (oldX(source.oldB) - oldX(source.oldA)) * source.t
				newValues[newIndex * 2 + 1] = oldY(source.oldA) + (oldY(source.oldB) - oldY(source.oldA)) * source.t
			}

			is VertexSource.Weighted -> {
				var sumX = source.weights[0] * oldX(source.oldIndices[0])
				var sumY = source.weights[0] * oldY(source.oldIndices[0])

				for (term in 1 until source.oldIndices.size) {
					sumX += source.weights[term] * oldX(source.oldIndices[term])
					sumY += source.weights[term] * oldY(source.oldIndices[term])
				}

				newValues[newIndex * 2] = sumX
				newValues[newIndex * 2 + 1] = sumY
			}
		}
	}

	return newValues
}

/**
 * Commits a topology operation on one session mesh as ONE undo step: the model takes the edit (mesh
 * swap, keyform-delta rebuild, glue remap - see [withMeshTopologyEdit]) and the mesh selection
 * becomes the operation's result elements on that mesh, in the SAME history push - splitting them
 * would let undo tear the selection from the topology it indexes into.  The ops produce vertex
 * results; they are re-derived into the CURRENT select mode (Blender keeps the mode across a
 * topology op - a face-mode duplicate leaves the new faces selected in face mode), falling back to
 * vertex mode only when nothing in the current domain covers them (e.g. a duplicated lone edge
 * copies as loose vertices, which no edge or face contains - stranding them unselected would hide
 * the copies and starve the follow-up auto-grab).  A no-op edit records nothing.
 *
 * @param String labelKey The operation's history label key (change.mesh.duplicate / merge / rip / connect).
 * @param DrawableId drawableId The edited mesh.
 * @param TopologyOpResult result The op builder's outcome.
 * @return Boolean True when a step was recorded; false for a no-op edit, after which a caller must
 *   not register the operation as adjustable (there is no step of its own to amend).
 */
fun EditorSession.commitMeshTopology(labelKey: String, drawableId: DrawableId, result: TopologyOpResult): Boolean {
	val newModel = model.value.withMeshTopologyEdit(drawableId, result.edit)

	if (newModel === model.value) {
		return false
	}

	val current = meshSelection.value
	val owner = ElementOwner.Drawable(drawableId)
	val vertexResult =
		MeshSelection(
			owners = current.owners,
			activeOwner = owner,
			selectMode = MeshSelectMode.Vertex,
			elementsByOwner = if (result.newElements.isEmpty()) emptyMap() else mapOf(owner to result.newElements),
			activeElement = result.newElements.firstOrNull()?.let { element -> ActiveMeshElement(owner, element) },
		)
	val newSelection = rederiveTopologyResult(vertexResult, current.selectMode, drawableId, newModel)
	commitStep(MeshChange.TopologyEdit(drawableId, labelKey), model = newModel, meshSelection = newSelection)

	return true
}

/**
 * Converts a topology op's vertex-mode result selection into [selectMode] against [newModel] (the
 * post-edit topology, where the new elements exist), via the strict derive-up rules of
 * [MeshSelectionOps.changeSelectMode]; the first derived element becomes active.  Returns the
 * vertex result unchanged when the session is already in vertex mode, when the op selected
 * nothing, or when nothing in the target domain covers the new vertices (see
 * [commitMeshTopology]'s docblock for that fallback's rationale).
 *
 * @param MeshSelection vertexResult The op's result selection, in vertex mode.
 * @param MeshSelectMode selectMode The session's current select mode to re-derive into.
 * @param DrawableId drawableId The edited mesh.
 * @param PuppetModel newModel The model with the topology edit applied.
 * @return MeshSelection The result selection in the kept mode, or the vertex fallback.
 */
private fun rederiveTopologyResult(
	vertexResult: MeshSelection,
	selectMode: MeshSelectMode,
	drawableId: DrawableId,
	newModel: PuppetModel,
): MeshSelection {
	if (selectMode == MeshSelectMode.Vertex || vertexResult.elementsOf(drawableId).isEmpty()) {
		return vertexResult
	}

	val rederived =
		MeshSelectionOps.changeSelectMode(vertexResult, selectMode) { candidate ->
			newModel.editTopologyOf(candidate)
		}
	val rederivedElements = rederived.elementsOf(drawableId)

	if (rederivedElements.isEmpty()) {
		return vertexResult
	}

	return rederived.copy(activeElement = ActiveMeshElement(ElementOwner.Drawable(drawableId), rederivedElements.first()))
}

/**
 * Whether a topology command refuses [selection] because it edits a lattice, saying so near the cursor: a lattice's
 * points, grid lines, and cells are fixed by its rows and columns, so nothing can be added, merged, ripped, or
 * connected on it.
 *
 * @param MeshSelection selection The session's element selection.
 * @return Boolean True when the command must stop here.
 */
fun EditorSession.refusesLatticeTopology(selection: MeshSelection): Boolean {
	if (!selection.editsLattices) {
		return false
	}

	emitNotice("notice.edit.latticeTopologyFixed", NoticePlacement.NearCursor)

	return true
}

/**
 * Duplicates the ACTIVE session mesh's covered elements in place (Edit-mode Shift+D) as one undo
 * step, leaving the copies selected - the caller follows with a Grab so the copies pull away under
 * the pointer, Blender-style.  A no-op outside Edit mode or with nothing covered on the active mesh.
 */
fun EditorSession.duplicateSelectedElements() {
	if (mode.value != EditorMode.Edit) {
		return
	}

	val selection = meshSelection.value

	if (refusesLatticeTopology(selection)) {
		return
	}

	val drawableId = selection.activeDrawableId ?: return
	val mesh = model.value.meshOf(drawableId) ?: return
	val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), ElementTopology.ofMesh(mesh))
	val result = MeshTopologyOps.duplicateElements(mesh, covered) ?: return
	commitMeshTopology("change.mesh.duplicate", drawableId, result)
}

/**
 * Merges the ACTIVE session mesh's selected vertices (Blender's M) as one undo step, leaving the
 * survivor selected, and registers the step on the operation settings strip with its one row, Merge
 * At - so a merge landed at the center can be re-landed at the first or last vertex without undoing.
 * The rerun re-merges the SAME vertices from the record's base; every target keeps the survivor at
 * the same index, so the survivor selection the step carries stays valid across an adjustment.
 * Vertex mode only - the first / last targets read the selection order, which only vertex elements
 * carry directly.  Refusals explain themselves with a near-cursor notice.
 *
 * @param MergeTarget target Where the survivor lands (center / first / last).
 * @param String? areaId The area the strip shows in (opaque here, like the operator latches), or null.
 */
fun EditorSession.mergeSelectedVertices(target: MergeTarget, areaId: String? = null) {
	if (mode.value != EditorMode.Edit) {
		return
	}

	val selection = meshSelection.value

	if (refusesLatticeTopology(selection)) {
		return
	}

	val drawableId = selection.activeDrawableId ?: return

	if (selection.selectMode != MeshSelectMode.Vertex) {
		emitNotice("notice.merge.needsVertices", NoticePlacement.NearCursor)

		return
	}

	// The element set is insertion-ordered (a LinkedHashSet built by the gestures), so "first" is
	// the earliest-selected vertex; "last" prefers the active element (the most recent touch).
	val orderedVertices = selection.elementsOf(drawableId).filterIsInstance<MeshElement.Vertex>().map { vertex -> vertex.index }.toMutableList()
	(selection.activeElement?.element as? MeshElement.Vertex)?.let { activeVertex ->
		if (orderedVertices.remove(activeVertex.index)) {
			orderedVertices.add(activeVertex.index)
		}
	}

	if (orderedVertices.size < 2) {
		emitNotice("notice.merge.needsVertices", NoticePlacement.NearCursor)

		return
	}

	val mesh = model.value.meshOf(drawableId) ?: return
	val result = MeshTopologyOps.mergeVertices(mesh, orderedVertices, target) ?: return

	if (!commitMeshTopology("change.mesh.merge", drawableId, result)) {
		return
	}

	val mergedVertices = orderedVertices.toList()
	registerAdjustableOperation(model.value, areaId, mergeParameters(target)) { record ->
		val adjustedTarget = mergeTargetOf(record.parameters, target)
		val baseModel = record.baseSnapshot.model
		val baseMesh = baseModel.meshOf(drawableId) ?: return@registerAdjustableOperation
		val rerun = MeshTopologyOps.mergeVertices(baseMesh, mergedVertices, adjustedTarget) ?: return@registerAdjustableOperation
		amendLastCommit(record, baseModel.withMeshTopologyEdit(drawableId, rerun.edit))
	}
}

/**
 * Connects the ACTIVE session mesh's two selected vertices with a cut (Blender's J) as one undo
 * step, leaving the cut path selected.  Exactly two selected vertices in vertex mode; a refusal
 * (already connected, nothing crossed, degenerate geometry) explains itself with a notice.
 */
fun EditorSession.connectSelectedVertices() {
	if (mode.value != EditorMode.Edit) {
		return
	}

	val selection = meshSelection.value

	if (refusesLatticeTopology(selection)) {
		return
	}

	val drawableId = selection.activeDrawableId ?: return
	val vertices = selection.elementsOf(drawableId).filterIsInstance<MeshElement.Vertex>().map { vertex -> vertex.index }

	if (selection.selectMode != MeshSelectMode.Vertex || vertices.size != 2) {
		emitNotice("notice.connect.needsTwoVertices", NoticePlacement.NearCursor)

		return
	}

	val mesh = model.value.meshOf(drawableId) ?: return
	val result = MeshTopologyOps.connectVertices(mesh, vertices[0], vertices[1])

	if (result == null) {
		emitNotice("notice.connect.refused", NoticePlacement.NearCursor)

		return
	}

	commitMeshTopology("change.mesh.connect", drawableId, result)
}