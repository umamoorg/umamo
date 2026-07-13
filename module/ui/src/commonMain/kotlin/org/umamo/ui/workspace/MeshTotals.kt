package org.umamo.ui.workspace

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * Vertex and triangle counts summed over a set of drawable meshes, for the status bar's stats zone.
 *
 * @property Int vertexCount The summed vertex count.
 * @property Int triangleCount The summed triangle count.
 */
internal data class MeshTotals(val vertexCount: Int, val triangleCount: Int)

/**
 * The whole model's mesh totals: every drawable that carries a mesh contributes its vertex and
 * triangle counts (a drawable's mesh is nullable - some drawables carry no geometry, and skipping
 * them is the contract).
 *
 * @param PuppetModel puppet The loaded rig.
 * @return MeshTotals The model-wide vertex / triangle sums.
 */
internal fun meshTotals(puppet: PuppetModel): MeshTotals {
	var vertexCount = 0
	var triangleCount = 0
	for (drawable in puppet.drawables) {
		val mesh = drawable.mesh ?: continue
		vertexCount += mesh.vertexCount
		triangleCount += mesh.triangleCount
	}
	return MeshTotals(vertexCount, triangleCount)
}

/**
 * The mesh totals covered by an object-mode [selection]: directly selected drawables plus every
 * drawable nested under a selected part (a folder selection counts its art, recursively). Deformer
 * targets contribute nothing - a deformer's influence set is a rig relationship, not containment.
 *
 * @param PuppetModel puppet The loaded rig.
 * @param Selection selection The object-mode selection.
 * @return MeshTotals The selected vertex / triangle sums.
 */
internal fun selectedMeshTotals(puppet: PuppetModel, selection: Selection): MeshTotals {
	val partById = puppet.parts.associateBy { part -> part.id }
	val selectedDrawableIds = HashSet<DrawableId>()
	for (target in selection.targets) {
		when (target) {
			is SelectionTarget.Drawable -> selectedDrawableIds.add(target.id)
			is SelectionTarget.Part -> collectPartDrawableIds(target.id, partById, selectedDrawableIds)
			is SelectionTarget.Deformer -> {
				// Intentionally nothing: deformers influence drawables but do not contain them.
			}
		}
	}
	var vertexCount = 0
	var triangleCount = 0
	for (drawable in puppet.drawables) {
		if (drawable.id !in selectedDrawableIds) {
			continue
		}
		val mesh = drawable.mesh ?: continue
		vertexCount += mesh.vertexCount
		triangleCount += mesh.triangleCount
	}
	return MeshTotals(vertexCount, triangleCount)
}

/**
 * Recursively collects every drawable id nested under [partId] (through nested parts) into [out].
 *
 * @param PartId partId The part whose subtree is collected.
 * @param Map partById id -> Part, for resolving nested parts.
 * @param MutableSet out The accumulating drawable id set.
 */
private fun collectPartDrawableIds(partId: PartId, partById: Map<PartId, Part>, out: MutableSet<DrawableId>) {
	val part = partById[partId] ?: return
	for (child in part.children) {
		when (child) {
			is OrgChild.Drawable -> out.add(child.id)
			is OrgChild.Part -> collectPartDrawableIds(child.id, partById, out)
		}
	}
}
