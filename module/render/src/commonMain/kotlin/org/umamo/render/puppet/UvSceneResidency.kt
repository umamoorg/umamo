package org.umamo.render.puppet

import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.OverlayMeshBuffers
import org.umamo.render.device.OverlayMeshSpec
import org.umamo.render.device.RenderDevice
import org.umamo.runtime.model.DrawableId

/**
 * One direct overlay mesh as a UV scene holds it: its value, the arrays it was uploaded from, and its draw
 * entry.
 *
 * @property MeshOverlayMesh mesh The mesh's overlay value.
 * @property FloatArray positions The display positions in the store (compared by identity).
 * @property IntArray triangleIndices The triangle indices its buffers were built from (by identity).
 * @property OverlayDrawEntry entry Its buffers, store offset, and resolved actives.
 */
internal class DirectOverlayResident(
	val mesh: MeshOverlayMesh,
	val positions: FloatArray,
	val triangleIndices: IntArray,
	val entry: OverlayDrawEntry,
)

/**
 * The device objects one UV area's scene keeps between renders: the store its overlay's positions are
 * uploaded into and one buffer set per overlay mesh.  Kept per area, so two UV areas showing different
 * surfaces never re-upload each other's meshes.
 *
 * What costs what, by design: a value whose position arrays are the same instances uploads nothing; a
 * moved mesh (a new positions array) uploads its own positions alone; a selection change updates the flags
 * of the meshes whose flag arrays changed by identity; a new edge or index array re-creates that mesh's
 * buffers; the store only grows, and a larger one re-uploads every mesh into it.
 *
 * Render thread only; [applyOverlay] is a resource operation that runs between frames, never inside one.
 *
 * @param RenderDevice device The backend the buffers and the store live on.
 */
internal class UvSceneResidency(
	private val device: RenderDevice,
) {
	/** The area's position store, or null before its first overlay. */
	var store: DeformedPositionStore? = null
		private set

	private var storeCapacity = 0

	/** The value the device objects reflect, or null when the area shows no overlay. */
	var applied: DirectMeshOverlay? = null
		private set

	/** The placed meshes, in store order. */
	var residents: List<DirectOverlayResident> = emptyList()
		private set

	/**
	 * Brings the device objects to [direct]: plans its layout, grows the store when needed, keeps or
	 * re-creates each mesh's buffers, updates flags in place, uploads the positions that changed, and frees
	 * what left.  A null value frees the buffers and keeps the store for the next overlay.
	 *
	 * @param DirectMeshOverlay? direct The overlay to reflect, or null for none.
	 */
	fun applyOverlay(direct: DirectMeshOverlay?) {
		if (direct === applied) {
			return
		}
		if (direct == null) {
			freeBuffers(residents)
			residents = emptyList()
			applied = null
			return
		}
		val layout = planDirectOverlayLayout(direct)
		var storeReplaced = false
		if (layout.totalVertexCount > storeCapacity) {
			store?.let { outgrown -> device.destroyDeformedPositionStore(outgrown) }
			store = device.createDeformedPositionStore(layout.totalVertexCount)
			storeCapacity = layout.totalVertexCount
			storeReplaced = true
		}
		val previousById = residents.associateBy { resident -> resident.mesh.drawableId }
		val placedIds = HashSet<DrawableId>(layout.placed.size)
		val next = ArrayList<DirectOverlayResident>(layout.placed.size)
		for (placement in layout.placed) {
			val mesh = placement.mesh
			val previous = previousById[mesh.drawableId]
			val buffers = buffersFor(mesh, placement.triangleIndices, previous)
			val positionsMoved = previous == null || previous.positions !== placement.positions || previous.entry.baseOffset != placement.baseOffset
			if (storeReplaced || positionsMoved) {
				store?.let { liveStore -> device.updateDeformedPositions(liveStore, placement.baseOffset, placement.positions) }
			}
			placedIds.add(mesh.drawableId)
			next.add(DirectOverlayResident(mesh, placement.positions, placement.triangleIndices, overlayDrawEntry(mesh, buffers, placement.baseOffset, placement.triangleIndices)))
		}
		freeBuffers(residents.filter { resident -> resident.mesh.drawableId !in placedIds })
		residents = next
		applied = direct
	}

	/** Frees every buffer set and the store.  Must run with the device's context current. */
	fun dispose() {
		freeBuffers(residents)
		residents = emptyList()
		store?.let { live -> device.destroyDeformedPositionStore(live) }
		store = null
		storeCapacity = 0
		applied = null
	}

	/**
	 * One mesh's buffers: the previous set when its edge and index arrays are the same instances (flags
	 * updated in place when they changed by identity), else a fresh set, freeing the previous one.
	 *
	 * @param MeshOverlayMesh mesh The mesh's overlay value.
	 * @param IntArray triangleIndices Its triangle indices.
	 * @param DirectOverlayResident? previous The mesh as the last value placed it, or null.
	 * @return OverlayMeshBuffers The buffers.
	 */
	private fun buffersFor(mesh: MeshOverlayMesh, triangleIndices: IntArray, previous: DirectOverlayResident?): OverlayMeshBuffers {
		val vertexFlags = overlayFlagsOrZeros(mesh.vertexFlags, mesh.vertexCount)
		val edgeFlags = overlayFlagsOrZeros(mesh.edgeFlags, mesh.edgeCount)
		val faceFlags = overlayFlagsOrZeros(mesh.faceFlags, triangleIndices.size / 3)
		if (previous != null && previous.mesh.edgeEndpoints === mesh.edgeEndpoints && previous.triangleIndices === triangleIndices) {
			if (overlayFlagsDiffer(previous.mesh.vertexFlags, mesh.vertexFlags) ||
				overlayFlagsDiffer(previous.mesh.edgeFlags, mesh.edgeFlags) ||
				overlayFlagsDiffer(previous.mesh.faceFlags, mesh.faceFlags)
			) {
				device.updateOverlayMeshFlags(previous.entry.buffers, vertexFlags, edgeFlags, faceFlags)
			}
			return previous.entry.buffers
		}
		previous?.let { stale -> device.destroyOverlayMeshBuffers(stale.entry.buffers) }
		return device.createOverlayMeshBuffers(OverlayMeshSpec(mesh.edgeEndpoints, triangleIndices, vertexFlags, edgeFlags, faceFlags))
	}

	/**
	 * Frees the buffer sets of the given residents.
	 *
	 * @param List<DirectOverlayResident> leaving The residents whose buffers go.
	 */
	private fun freeBuffers(leaving: List<DirectOverlayResident>) {
		for (resident in leaving) {
			device.destroyOverlayMeshBuffers(resident.entry.buffers)
		}
	}
}