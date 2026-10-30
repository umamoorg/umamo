package org.umamo.render.puppet

import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.OverlayMeshBuffers
import org.umamo.render.device.RenderDevice
import org.umamo.runtime.model.ElementOwner

/**
 * One direct overlay mesh as its residency holds it: its value, the arrays it was uploaded from, and its draw
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
 * The device objects a direct overlay keeps between renders: the store its meshes' own positions are uploaded
 * into and one buffer set per mesh.  A UV area's scene keeps one; the 2D viewport's lattice layer keeps another.
 * Each overlay keeps its own, so two overlays showing different meshes never re-upload each other's.
 *
 * What costs what, by design: a value whose position arrays are the same instances uploads nothing; a moved
 * mesh (a new positions array) uploads its own positions alone; a selection change updates the flags of the
 * meshes whose flag arrays changed by identity; a new edge or index array re-creates that mesh's buffers; the
 * store only grows, and a larger one re-uploads every mesh into it.
 *
 * Render thread only; [applyOverlay] is a resource operation that runs between frames, never inside one.
 *
 * @param RenderDevice device The backend the buffers and the store live on.
 */
internal class DirectOverlayResidency(
	private val device: RenderDevice,
) {
	private val positionStore = GrowingPositionStore(device)

	/** The overlay's position store, or null before its first overlay. */
	val store: DeformedPositionStore? get() = positionStore.store

	/** The value the device objects reflect, or null when there is no overlay. */
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
		val storeReplaced = positionStore.ensureCapacity(layout.totalVertexCount)

		val previousByOwner = residents.associateBy { resident -> resident.mesh.owner }
		val placedOwners = HashSet<ElementOwner>(layout.placed.size)
		val next = ArrayList<DirectOverlayResident>(layout.placed.size)

		for (placement in layout.placed) {
			val mesh = placement.mesh
			val previous = previousByOwner[mesh.owner]
			val buffers = device.overlayBuffersFor(mesh, placement.triangleIndices, previous?.mesh, previous?.entry?.buffers, sameSource = previous?.triangleIndices === placement.triangleIndices)
			val positionsMoved = previous == null || previous.positions !== placement.positions || previous.entry.baseOffset != placement.baseOffset

			if (storeReplaced || positionsMoved) {
				store?.let { liveStore -> device.updateDeformedPositions(liveStore, placement.baseOffset, placement.positions) }
			}

			placedOwners.add(mesh.owner)
			next.add(DirectOverlayResident(mesh, placement.positions, placement.triangleIndices, overlayDrawEntry(mesh, buffers, placement.baseOffset, placement.triangleIndices)))
		}

		freeBuffers(residents.filter { resident -> resident.mesh.owner !in placedOwners })
		residents = next
		applied = direct
	}

	/** Frees every buffer set and the store.  Must run with the device's context current. */
	fun dispose() {
		freeBuffers(residents)
		residents = emptyList()
		positionStore.dispose()
		applied = null
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