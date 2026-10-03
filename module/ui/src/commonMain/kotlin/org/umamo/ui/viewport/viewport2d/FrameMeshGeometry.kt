package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * One session mesh's geometry as the DISPLAYED frame rendered it, for the draw pass (the wireframe must
 * lag together with the raster, not lead it).  worldPosed is null when the frame could not project the
 * drawable (a hidden ancestor) - the draw pass then falls back to the live shape.
 *
 * @property IntArray indices The frame mesh's triangle vertex indices.
 * @property List<MeshElement.Edge> edges The frame mesh's unique edges.
 * @property FloatArray? worldPosed The frame's world-projected shape, or null when unprojectable.
 */
internal class FrameMeshGeometry(
	val indices: IntArray,
	val edges: List<MeshElement.Edge>,
	val worldPosed: FloatArray?,
)

/**
 * The DISPLAYED frame's geometry per session mesh - what the raster under the Edit overlay actually
 * shows.  The draw pass poses the wireframes from this (not the live session shape / working arrays),
 * so during a transform every mesh lags together with the raster instead of racing ahead of it.
 *
 * Recomputed per rendered frame, but ONLY the frame's changed positions are per-frame work: the
 * topology (indices, edge set) and the deformer-chain mapping are invariant during a drag, so they
 * are reused from liveGeometry rather than rebuilt every frame.  withMeshPositions shares the indices
 * array by reference, and Edit mode pins the pose so the deformers never move - rebuilding them per
 * frame (a uniqueEdges plus a full buildDeformerWorlds per drawable) would make an all-vertex
 * transform lag, unlike Object mode's transform, which caches.  A missing member falls the draw pass
 * back to the session shape (a pathological transient, e.g. a hidden ancestor).
 *
 * @param PuppetModel frameModel The model the displayed frame was rendered from.
 * @param List<EditMeshGeometry> liveGeometry The session meshes' live geometry.
 * @param MutableMap frameGeometryReuse The per-drawable reuse cache, written as entries are posed (see
 *   [rememberFrameMeshGeometries]).
 * @return Map The frame geometry per drawable, missing the drawables the frame cannot show.
 */
internal fun frameMeshGeometries(
	frameModel: PuppetModel,
	liveGeometry: List<EditMeshGeometry>,
	frameGeometryReuse: MutableMap<DrawableId, Pair<Drawable, FrameMeshGeometry>>,
): Map<DrawableId, FrameMeshGeometry> {
	// One O(D) index of the frame's drawables, so the per-mesh lookups below are not an O(D^2) scan.
	val frameDrawablesById = frameModel.drawables.associateBy { it.id }
	return liveGeometry.mapNotNull { liveEntry ->
		val drawableId = liveEntry.drawableId
		val frameDrawable = frameDrawablesById[drawableId] ?: return@mapNotNull null
		val frameMesh = frameDrawable.mesh ?: return@mapNotNull null
		val reused = frameGeometryReuse[drawableId]
		if (reused != null && reused.first === frameDrawable) {
			return@mapNotNull drawableId to reused.second
		}
		// Topology is unchanged during a transform (indices shared by reference), so reuse the edge set;
		// recompute only across a topology edit, where the frame briefly trails on the old indices.
		val edges =
			if (frameMesh.indices === liveEntry.mesh.indices) {
				liveEntry.edges
			} else {
				MeshTopology.uniqueEdges(frameMesh.indices)
			}
		// The deformers do not move during a mesh drag, so liveEntry.mapping (built once from the session
		// model) projects the frame's changed local positions correctly - no per-frame buildDeformerWorlds.
		val frameDisplayed = drawableLocalPosed(frameModel, emptyMap(), drawableId) ?: frameMesh.localPositions
		val frameGeometry =
			FrameMeshGeometry(
				indices = frameMesh.indices,
				edges = edges,
				worldPosed = liveEntry.mapping.localToWorld(frameDisplayed),
			)
		frameGeometryReuse[drawableId] = frameDrawable to frameGeometry
		drawableId to frameGeometry
	}.toMap()
}

/**
 * [frameMeshGeometries] over a reuse cache held for the live geometry's lifetime.  A modal drive's
 * folded frame model replaces ONLY the moving meshes' drawables, so a bystander's posed world geometry
 * carries over by drawable INSTANCE identity instead of re-posing every session mesh per rendered
 * frame - the per-frame work during a drag is the moving meshes alone.  The cache is keyed on
 * liveGeometry so a committed model swap (new mappings / topology) drops every entry.
 *
 * @param PuppetModel frameModel The model the displayed frame was rendered from.
 * @param List<EditMeshGeometry> liveGeometry The session meshes' live geometry.
 * @return Map The frame geometry per drawable.
 */
@Composable
internal fun rememberFrameMeshGeometries(frameModel: PuppetModel, liveGeometry: List<EditMeshGeometry>): Map<DrawableId, FrameMeshGeometry> {
	val frameGeometryReuse = remember(liveGeometry) { HashMap<DrawableId, Pair<Drawable, FrameMeshGeometry>>() }
	return remember(frameModel, liveGeometry) { frameMeshGeometries(frameModel, liveGeometry, frameGeometryReuse) }
}