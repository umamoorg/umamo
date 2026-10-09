package org.umamo.render.puppet

import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.OverlayMeshBuffers
import org.umamo.render.device.OverlayMeshSpec
import org.umamo.render.device.RenderDevice
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * What one overlay mesh's draws need, whichever scene it is drawn in: its per-instance buffers, its first
 * vertex's index in the position store, and its active primitive resolved to local vertex indices (-1 when
 * the domain has no active primitive), and its colors when it is an island.
 *
 * @property OverlayMeshBuffers buffers Its per-instance device buffers.
 * @property Int baseOffset Its first vertex's index in the position store.
 * @property Int activeVertex The active vertex, or -1.
 * @property Int activeEdgeA The active edge's first endpoint, or -1.
 * @property Int activeEdgeB The active edge's second endpoint, or -1.
 * @property Int activeFaceA The active triangle's first corner, or -1.
 * @property Int activeFaceB The active triangle's second corner, or -1.
 * @property Int activeFaceC The active triangle's third corner, or -1.
 * @property IslandStyle? islandStyle Its island colors, read only by an islands overlay.
 * @property Boolean wireframeOnly Whether it is a plain wireframe inside an Edit overlay: edges only, and
 *   only in a frame that draws the wireframe.
 * @property DrawableId drawableId The drawable the mesh belongs to, by which a culling frame finds its draw order.
 */
internal class OverlayDrawEntry(
	val buffers: OverlayMeshBuffers,
	val baseOffset: Int,
	val activeVertex: Int,
	val activeEdgeA: Int,
	val activeEdgeB: Int,
	val activeFaceA: Int,
	val activeFaceB: Int,
	val activeFaceC: Int,
	val islandStyle: IslandStyle?,
	val wireframeOnly: Boolean,
	val drawableId: DrawableId,
)

/**
 * One overlay mesh as the 2D renderer holds it: its value, the resident it draws over, and its draw entry.
 *
 * @property MeshOverlayMesh mesh The mesh's overlay value.
 * @property GpuDrawable gpuDrawable The resident drawable whose deformed positions it reads.
 * @property OverlayDrawEntry entry Its buffers, store offset, and resolved actives.
 */
internal class OverlayResident(
	val mesh: MeshOverlayMesh,
	val gpuDrawable: GpuDrawable,
	val entry: OverlayDrawEntry,
) {
	/** Its first vertex's index in the overlay store. */
	val baseOffset: Int get() = entry.baseOffset

	/** Its per-instance device buffers. */
	val buffers: OverlayMeshBuffers get() = entry.buffers
}

/**
 * One mesh's draw entry, with its active primitive resolved to local indices: an edge to its two
 * endpoints, a face to its three corners (a face outside [faceCorners] is no active face).
 *
 * @param MeshOverlayMesh mesh The mesh's overlay value.
 * @param OverlayMeshBuffers buffers Its device buffers.
 * @param Int baseOffset Its place in the position store.
 * @param IntArray faceCorners Its triangle indices.
 * @return OverlayDrawEntry The entry.
 */
internal fun overlayDrawEntry(mesh: MeshOverlayMesh, buffers: OverlayMeshBuffers, baseOffset: Int, faceCorners: IntArray): OverlayDrawEntry {
	val activeEdge = mesh.activeEdge
	val activeFace = mesh.activeFace
	val faceResolved = activeFace != null && activeFace * 3 + 2 < faceCorners.size
	val faceStart = if (faceResolved) activeFace * 3 else 0
	return OverlayDrawEntry(
		buffers = buffers,
		baseOffset = baseOffset,
		activeVertex = mesh.activeVertex ?: -1,
		activeEdgeA = if (activeEdge != null) mesh.edgeEndpoints[activeEdge * 2] else -1,
		activeEdgeB = if (activeEdge != null) mesh.edgeEndpoints[activeEdge * 2 + 1] else -1,
		activeFaceA = if (faceResolved) faceCorners[faceStart] else -1,
		activeFaceB = if (faceResolved) faceCorners[faceStart + 1] else -1,
		activeFaceC = if (faceResolved) faceCorners[faceStart + 2] else -1,
		islandStyle = mesh.islandStyle,
		wireframeOnly = mesh.wireframeOnly,
		drawableId = mesh.drawableId,
	)
}

/**
 * The flag array the device gets: the value's own, or all-idle zeros of the domain's count when the value
 * carries none (the object wireframe, the islands), so the device always sees exact sizes.
 *
 * @param ByteArray flags The value's flags, possibly empty.
 * @param Int count The domain's primitive count.
 * @return ByteArray The flags to upload.
 */
internal fun overlayFlagsOrZeros(flags: ByteArray, count: Int): ByteArray =
	if (flags.isEmpty()) {
		ByteArray(count)
	} else {
		flags
	}

/**
 * Whether two flag arrays differ by identity, with two empty arrays counting as the same (both mean all
 * idle, and a producer may mint a fresh empty array per value).
 *
 * @param ByteArray previous The flags the buffers hold.
 * @param ByteArray next The flags the new value carries.
 * @return Boolean True when the device must take [next].
 */
internal fun overlayFlagsDiffer(previous: ByteArray, next: ByteArray): Boolean = previous !== next && !(previous.isEmpty() && next.isEmpty())

/**
 * The mesh overlay's device objects and what they reflect: the overlay store the capture writes, and one
 * buffer set per overlay mesh.  Render thread only; [apply] is a self-contained resource operation that
 * runs between frames, never inside one.
 *
 * What costs what, by design: a preview push costs nothing here (the store is re-captured, the buffers
 * stand); a selection change costs one flag upload per mesh whose flag arrays changed by IDENTITY and
 * nothing else; a topology change or a new session mesh set re-creates only the meshes concerned; a new
 * value that reorders the meshes re-uploads nothing, since the buffers hold local indices and the draw
 * adds each mesh's store offset.  The store is sized to the overlay's total vertex count and only ever
 * grows: a larger overlay replaces it, a smaller one keeps it, and [dispose] frees it.
 *
 * @param RenderDevice device The backend the buffers and the store live on.
 */
internal class MeshOverlayResidency(
	private val device: RenderDevice,
) {
	/** The overlay store, or null before the first non-empty overlay. */
	var store: DeformedPositionStore? = null
		private set

	private var storeCapacity = 0

	/**
	 * Whether the store is behind the positions: set by a pose, by a model push of either kind, and by an
	 * apply that moved or added a placement; cleared by the capture.
	 */
	var storeStale: Boolean = true

	/**
	 * Whether the residents may have been rebuilt since the last apply (a structural model push), so a
	 * repeated value must be re-paired rather than taken as applied.
	 */
	var residencyChanged: Boolean = false

	/** The value the buffers reflect, or null when none is applied. */
	var applied: MeshOverlay? = null
		private set

	/** The placed meshes, in store order. */
	var entries: List<OverlayResident> = emptyList()
		private set

	/**
	 * Brings the device objects to [overlay] over the current [residents]: pairs each mesh, keeps or
	 * re-creates its buffers, updates flags in place, frees what left, and grows the store when needed.
	 *
	 * @param MeshOverlay? overlay The value to reflect, or null for no overlay.
	 * @param Map<DrawableId, GpuDrawable> residents The resident drawables.
	 * @param PuppetModel model The model the residents reflect, for each mesh's triangle indices.
	 */
	fun apply(overlay: MeshOverlay?, residents: Map<DrawableId, GpuDrawable>, model: PuppetModel) {
		if (overlay === applied && !residencyChanged) {
			return
		}
		residencyChanged = false
		val previousById = entries.associateBy { entry -> entry.mesh.drawableId }
		if (overlay == null) {
			for (entry in entries) {
				device.destroyOverlayMeshBuffers(entry.buffers)
			}
			entries = emptyList()
			applied = null
			return
		}
		val layout =
			planOverlayLayout(
				overlay,
				residents.mapValues { (_, resident) -> resident.vertexCount },
				residents.mapValues { (_, resident) -> resident.indexCount / 3 },
			)
		val drawableById = model.drawables.associateBy { drawable -> drawable.id }
		val next = ArrayList<OverlayResident>(layout.placed.size)
		val placedIds = HashSet<DrawableId>(layout.placed.size)
		for (placement in layout.placed) {
			val mesh = placement.mesh
			val resident = residents.getValue(mesh.drawableId)
			val faceCorners = drawableById[mesh.drawableId]?.mesh?.indices ?: IntArray(0)
			val previous = previousById[mesh.drawableId]
			val vertexFlags = overlayFlagsOrZeros(mesh.vertexFlags, mesh.vertexCount)
			val edgeFlags = overlayFlagsOrZeros(mesh.edgeFlags, mesh.edgeCount)
			val faceFlags = overlayFlagsOrZeros(mesh.faceFlags, faceCorners.size / 3)
			val buffers: OverlayMeshBuffers
			if (previous != null && previous.gpuDrawable === resident && previous.mesh.edgeEndpoints === mesh.edgeEndpoints) {
				buffers = previous.buffers
				if (overlayFlagsDiffer(previous.mesh.vertexFlags, mesh.vertexFlags) ||
					overlayFlagsDiffer(previous.mesh.edgeFlags, mesh.edgeFlags) ||
					overlayFlagsDiffer(previous.mesh.faceFlags, mesh.faceFlags)
				) {
					device.updateOverlayMeshFlags(buffers, vertexFlags, edgeFlags, faceFlags)
				}
			} else {
				if (previous != null) {
					device.destroyOverlayMeshBuffers(previous.buffers)
				}
				buffers = device.createOverlayMeshBuffers(OverlayMeshSpec(mesh.edgeEndpoints, faceCorners, vertexFlags, edgeFlags, faceFlags))
			}
			if (previous == null || previous.gpuDrawable !== resident || previous.baseOffset != placement.baseOffset) {
				storeStale = true
			}
			placedIds.add(mesh.drawableId)
			next.add(residentOf(mesh, resident, placement.baseOffset, buffers, faceCorners))
		}
		for (entry in entries) {
			if (entry.mesh.drawableId !in placedIds) {
				device.destroyOverlayMeshBuffers(entry.buffers)
			}
		}
		entries = next
		if (layout.totalVertexCount > storeCapacity) {
			store?.let { outgrown -> device.destroyDeformedPositionStore(outgrown) }
			store = device.createDeformedPositionStore(layout.totalVertexCount)
			storeCapacity = layout.totalVertexCount
			storeStale = true
		}
		applied = overlay
	}

	/** Frees every buffer set and the store.  Must run with the device's context current. */
	fun dispose() {
		for (entry in entries) {
			device.destroyOverlayMeshBuffers(entry.buffers)
		}
		entries = emptyList()
		store?.let { live -> device.destroyDeformedPositionStore(live) }
		store = null
		storeCapacity = 0
		applied = null
	}

	/**
	 * One placed mesh's resident, with its active primitive resolved to local indices.
	 *
	 * @param MeshOverlayMesh mesh The mesh's overlay value.
	 * @param GpuDrawable gpuDrawable The resident drawable.
	 * @param Int baseOffset Its place in the store.
	 * @param OverlayMeshBuffers buffers Its device buffers.
	 * @param IntArray faceCorners Its triangle indices.
	 * @return OverlayResident The resident.
	 */
	private fun residentOf(mesh: MeshOverlayMesh, gpuDrawable: GpuDrawable, baseOffset: Int, buffers: OverlayMeshBuffers, faceCorners: IntArray): OverlayResident =
		OverlayResident(mesh, gpuDrawable, overlayDrawEntry(mesh, buffers, baseOffset, faceCorners))
}