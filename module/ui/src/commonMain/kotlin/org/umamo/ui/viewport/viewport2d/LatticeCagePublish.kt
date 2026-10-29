package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.render.eval.warpLatticeAt
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.latticeLayout
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.NO_OVERLAY_FLAGS
import org.umamo.ui.viewport.gizmo.editCageFlagsOf
import org.umamo.ui.viewport.gizmo.overlaySelectModeOf
import org.umamo.ui.viewport.gizmo.sameOverlayEntries

/*
 * The 2D viewport's lattice cage: the Edit-mode cage of each warp a session shapes, drawn from positions of its own
 * - its rest lattice, or the points a drag has moved - since shaping a lattice moves the cage and never the art.  The
 * flags come from the same derive as a mesh's cage (gizmo/EditMeshOverlayProducer.kt), so a lattice lights up by the
 * rules a click selects by.  A cell is a quad: it fills as its two triangles, and its one face dot sits on an anchor
 * vertex appended past the lattice's own points, at the cell's center.
 */

/**
 * Turns a lattice session's state into the renderer's direct cage, keeping everything it can from one call to the
 * next: a lattice's topology, triangles, and dot anchors live as long as its grid size, its flags as long as its
 * selected set (by identity), active element, and select mode, its rest positions as long as their values, and the
 * whole value as long as every entry and position array - the renderer uploads only what changed by identity.
 *
 * Not thread-safe: one sequential derive owns it.
 */
internal class LatticeCageProducer {
	/**
	 * One lattice's cage topology: its points, grid lines, and cells, the triangles its cells fill as, and the face-dot
	 * anchors that put both triangles' dot at the cell's center.
	 *
	 * @property Int rows The lattice's cell count along v.
	 * @property Int columns The lattice's cell count along u.
	 * @property ElementTopology elements Its points, grid lines, and cells.
	 * @property IntArray triangleIndices Two triangles per cell, cell by cell.
	 * @property IntArray faceDotCorners Three anchors per triangle, each the cell's center vertex.
	 */
	private class CageTopology(
		val rows: Int,
		val columns: Int,
		val elements: ElementTopology,
		val triangleIndices: IntArray,
		val faceDotCorners: IntArray,
	) {
		/** The lattice's own points, which draw vertex dots. */
		val pointCount: Int get() = elements.vertexCount

		/** The points and the appended cell centers. */
		val vertexCount: Int get() = elements.vertexCount + elements.faceCount
	}

	/**
	 * One lattice's last entry and what it was built from.
	 *
	 * @property Set<MeshElement> elements The selected set it reflects (compared by identity).
	 * @property MeshElement? active The active element it reflects.
	 * @property MeshSelectMode selectMode The select mode it reflects.
	 * @property CageTopology topology The topology it was built over (compared by identity).
	 * @property MeshOverlayMesh entry The entry.
	 */
	private class Entry(
		val elements: Set<MeshElement>,
		val active: MeshElement?,
		val selectMode: MeshSelectMode,
		val topology: CageTopology,
		val entry: MeshOverlayMesh,
	)

	/**
	 * One lattice's cage positions and the world points they were built from.
	 *
	 * @property PuppetModel? model The model a rest lattice was read off (by identity), or null for a drag's points.
	 * @property FloatArray world The lattice's world points, interleaved x, y.
	 * @property FloatArray cage The points with the cell centers appended.
	 */
	private class CagePositions(
		val model: PuppetModel?,
		val world: FloatArray,
		val cage: FloatArray,
	)

	private val topologyById = HashMap<DeformerId, CageTopology>()
	private val entryById = HashMap<DeformerId, Entry>()
	private val restById = HashMap<DeformerId, CagePositions>()
	private val liveById = HashMap<DeformerId, CagePositions>()
	private var lastOverlay: MeshOverlay? = null
	private var lastCage: DirectMeshOverlay? = null

	/**
	 * The cage for one state, the previous instance when nothing it shows changed: in Edit mode, every lattice of the
	 * session in the session's order, each at the points [liveWorldById] holds for it or else at its rest lattice;
	 * null outside Edit mode, or when the session shapes no lattice the rest pose draws.
	 *
	 * @param EditorMode mode The editor mode.
	 * @param MeshSelection selection The selection to show.
	 * @param PuppetModel model The model the selection is over.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @param Map<DeformerId, FloatArray>? liveWorldById A drag's moved world points per lattice, or null for none.
	 * @return DirectMeshOverlay? The cage, or null for none.
	 */
	fun produce(
		mode: EditorMode,
		selection: MeshSelection,
		model: PuppetModel,
		sizes: MeshOverlaySizes,
		liveWorldById: Map<DeformerId, FloatArray>?,
	): DirectMeshOverlay? {
		val latticeIds =
			if (mode == EditorMode.Edit) {
				selection.owners.mapNotNullTo(LinkedHashSet()) { owner -> (owner as? ElementOwner.Deformer)?.id }
			} else {
				emptySet()
			}

		topologyById.keys.retainAll(latticeIds)
		entryById.keys.retainAll(latticeIds)
		restById.keys.retainAll(latticeIds)
		liveById.keys.retainAll(liveWorldById?.keys.orEmpty())

		if (latticeIds.isEmpty()) {
			lastOverlay = null
			lastCage = null

			return null
		}

		val warpById = model.deformers.filter { deformer -> deformer.id in latticeIds }.filterIsInstance<Deformer.Warp>().associateBy { warp -> warp.id }
		val entries = ArrayList<MeshOverlayMesh>(latticeIds.size)
		val positionsById = LinkedHashMap<ElementOwner, FloatArray>(latticeIds.size)
		val trianglesById = LinkedHashMap<ElementOwner, IntArray>(latticeIds.size)

		for (deformerId in latticeIds) {
			val warp = warpById[deformerId] ?: continue
			val topology = topologyOf(deformerId, warp)
			val positions = livePositionsOf(deformerId, topology, liveWorldById?.get(deformerId)) ?: restPositionsOf(deformerId, model, topology) ?: continue
			val owner = ElementOwner.Deformer(deformerId)
			entries.add(entryOf(deformerId, owner, topology, selection))
			positionsById[owner] = positions
			trianglesById[owner] = topology.triangleIndices
		}

		return cageOf(overlaySelectModeOf(selection.selectMode), entries, sizes, positionsById, trianglesById)
	}

	/**
	 * The cage over [entries] at [positionsById], or the previous instance when its overlay and every position array
	 * are the same; null for no entries.
	 *
	 * @param MeshOverlaySelectMode selectMode The select mode.
	 * @param List<MeshOverlayMesh> entries The entries, in layout order.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @param Map<ElementOwner, FloatArray> positionsById Each entry's cage positions.
	 * @param Map<ElementOwner, IntArray> trianglesById Each entry's triangles.
	 * @return DirectMeshOverlay? The cage, or null for none.
	 */
	private fun cageOf(
		selectMode: MeshOverlaySelectMode,
		entries: List<MeshOverlayMesh>,
		sizes: MeshOverlaySizes,
		positionsById: Map<ElementOwner, FloatArray>,
		trianglesById: Map<ElementOwner, IntArray>,
	): DirectMeshOverlay? {
		if (entries.isEmpty()) {
			lastOverlay = null
			lastCage = null

			return null
		}

		val previousOverlay = lastOverlay
		val overlay =
			if (previousOverlay != null && previousOverlay.selectMode == selectMode && previousOverlay.sizes == sizes && sameOverlayEntries(previousOverlay.meshes, entries)) {
				previousOverlay
			} else {
				MeshOverlay(MeshOverlayKind.Edit, selectMode, entries, sizes)
			}

		lastOverlay = overlay
		val previousCage = lastCage

		if (previousCage != null && previousCage.overlay === overlay && samePositions(previousCage.positionsById, positionsById)) {
			return previousCage
		}

		val cage = DirectMeshOverlay(overlay, positionsById, trianglesById)
		lastCage = cage

		return cage
	}

	/**
	 * A lattice's cage topology, kept while its grid size is the one it was built for.
	 *
	 * @param DeformerId deformerId The warp.
	 * @param Deformer.Warp warp The warp as the model holds it.
	 * @return CageTopology The topology.
	 */
	private fun topologyOf(deformerId: DeformerId, warp: Deformer.Warp): CageTopology {
		val cached = topologyById[deformerId]

		if (cached != null && cached.rows == warp.rows && cached.columns == warp.columns) {
			return cached
		}

		val layout = warp.latticeLayout
		val elements = ElementTopology.ofLattice(layout)
		val faceDotCorners = IntArray(layout.cellCount * 6)

		for (cellIndex in 0 until layout.cellCount) {
			faceDotCorners.fill(layout.pointCount + cellIndex, cellIndex * 6, cellIndex * 6 + 6)
		}

		val topology = CageTopology(warp.rows, warp.columns, elements, layout.cellTriangles, faceDotCorners)
		topologyById[deformerId] = topology

		return topology
	}

	/**
	 * A lattice's cage positions at a drag's moved points, kept while the drag hands over the same array.
	 *
	 * @param DeformerId deformerId The warp.
	 * @param CageTopology topology Its topology.
	 * @param FloatArray? world The drag's world points for it, or null when the drag has not moved it.
	 * @return FloatArray? The cage positions, or null when there is no drag for it (or one of another grid size).
	 */
	private fun livePositionsOf(deformerId: DeformerId, topology: CageTopology, world: FloatArray?): FloatArray? {
		if (world == null || world.size != topology.pointCount * 2) {
			liveById.remove(deformerId)

			return null
		}

		val cached = liveById[deformerId]

		if (cached != null && cached.world === world && cached.cage.size == topology.vertexCount * 2) {
			return cached.cage
		}

		val cage = cagePositionsOf(world, topology)
		liveById[deformerId] = CagePositions(null, world, cage)

		return cage
	}

	/**
	 * A lattice's cage positions at its rest lattice, read off [model] at the default pose (Edit mode shows the rest
	 * pose), and kept while the values are the same, so a commit that leaves the lattice where it was re-uploads
	 * nothing.
	 *
	 * @param DeformerId deformerId The warp.
	 * @param PuppetModel model The model.
	 * @param CageTopology topology Its topology.
	 * @return FloatArray? The cage positions, or null when the rest pose does not draw the warp.
	 */
	private fun restPositionsOf(deformerId: DeformerId, model: PuppetModel, topology: CageTopology): FloatArray? {
		val cached = restById[deformerId]
		val fits = cached != null && cached.cage.size == topology.vertexCount * 2

		if (cached != null && fits && cached.model === model) {
			return cached.cage
		}

		val world = warpLatticeAt(model, emptyMap(), deformerId)?.world

		if (world == null || world.size != topology.pointCount * 2) {
			restById.remove(deformerId)

			return null
		}

		if (cached != null && fits && cached.world.contentEquals(world)) {
			restById[deformerId] = CagePositions(model, cached.world, cached.cage)

			return cached.cage
		}

		val cage = cagePositionsOf(world, topology)
		restById[deformerId] = CagePositions(model, world, cage)

		return cage
	}

	/**
	 * A lattice's entry, kept while its selected set (by identity), active element, select mode, and topology are the
	 * ones it was built from.
	 *
	 * @param DeformerId deformerId The warp.
	 * @param ElementOwner owner The lattice's owner.
	 * @param CageTopology topology Its topology.
	 * @param MeshSelection selection The selection to show.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun entryOf(deformerId: DeformerId, owner: ElementOwner, topology: CageTopology, selection: MeshSelection): MeshOverlayMesh {
		val elements = selection.elementsOf(owner)
		val active = selection.activeElement?.takeIf { activeElement -> activeElement.owner == owner }?.element
		val cached = entryById[deformerId]

		if (cached != null && cached.elements === elements && cached.active == active && cached.selectMode == selection.selectMode && cached.topology === topology) {
			return cached.entry
		}

		val entry = buildEntry(owner, topology, elements, active, selection.selectMode)
		entryById[deformerId] = Entry(elements, active, selection.selectMode, topology, entry)

		return entry
	}

	/**
	 * Builds one lattice's entry: its points' and grid lines' flags as the mesh derive lights them, the appended
	 * centers left idle and drawing no vertex dot, and each cell's flag on both of its triangles, the active cell's
	 * dot read through its first.
	 *
	 * @param ElementOwner owner The lattice's owner.
	 * @param CageTopology topology Its topology.
	 * @param Set<MeshElement> elements Its selected elements.
	 * @param MeshElement? active Its active element, or null.
	 * @param MeshSelectMode selectMode The select mode.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun buildEntry(
		owner: ElementOwner,
		topology: CageTopology,
		elements: Set<MeshElement>,
		active: MeshElement?,
		selectMode: MeshSelectMode,
	): MeshOverlayMesh {
		val edgeEndpoints = topology.elements.edgeEndpoints
		val flags =
			editCageFlagsOf(topology.elements, elements, active, selectMode)
				?: return MeshOverlayMesh(
					owner,
					topology.vertexCount,
					edgeEndpoints,
					NO_OVERLAY_FLAGS,
					NO_OVERLAY_FLAGS,
					NO_OVERLAY_FLAGS,
					null,
					null,
					null,
					faceDotCorners = topology.faceDotCorners,
					vertexDotCount = topology.pointCount,
				)
		val triangleFlags = ByteArray(flags.faceFlags.size * 2) { triangleIndex -> flags.faceFlags[triangleIndex / 2] }

		return MeshOverlayMesh(
			owner,
			topology.vertexCount,
			edgeEndpoints,
			flags.vertexFlags.copyOf(topology.vertexCount),
			flags.edgeFlags,
			triangleFlags,
			flags.activeVertex,
			flags.activeEdge,
			flags.activeFace?.let { cellIndex -> cellIndex * 2 },
			faceDotCorners = topology.faceDotCorners,
			vertexDotCount = topology.pointCount,
		)
	}

	/**
	 * A lattice's world points with each cell's center appended, the mean of its four corners.
	 *
	 * @param FloatArray world The world points, interleaved x, y.
	 * @param CageTopology topology The lattice's topology.
	 * @return FloatArray The cage positions.
	 */
	private fun cagePositionsOf(world: FloatArray, topology: CageTopology): FloatArray {
		val cage = world.copyOf(topology.vertexCount * 2)
		val cellCorners = topology.elements.faceCorners

		for (cellIndex in 0 until topology.elements.faceCount) {
			var sumX = 0f
			var sumY = 0f

			for (cornerSlot in 0 until 4) {
				val pointIndex = cellCorners[cellIndex * 4 + cornerSlot]
				sumX += world[pointIndex * 2]
				sumY += world[pointIndex * 2 + 1]
			}

			val centerIndex = topology.pointCount + cellIndex
			cage[centerIndex * 2] = sumX / 4f
			cage[centerIndex * 2 + 1] = sumY / 4f
		}

		return cage
	}

	/**
	 * Whether two position maps hold the same arrays for the same owners, in the same order.
	 *
	 * @param Map<ElementOwner, FloatArray> previous The previous cage's positions.
	 * @param Map<ElementOwner, FloatArray> next This call's.
	 * @return Boolean True when every array is the same instance.
	 */
	private fun samePositions(previous: Map<ElementOwner, FloatArray>, next: Map<ElementOwner, FloatArray>): Boolean =
		previous.size == next.size && previous.keys.zip(next.keys).all { (previousOwner, nextOwner) -> previousOwner == nextOwner && previous[previousOwner] === next[nextOwner] }
}

/**
 * Publishes the lattice cage to [service] for as long as it runs: derived from the mode, the brush stroke's preview
 * or else the committed mesh selection, the model, the sizes, and a drag's moved lattice points, and published
 * whenever the derived value changes by identity - a drag frame publishes a value whose moved lattices carry new
 * position arrays, and a commit that leaves every lattice where it was publishes nothing.
 *
 * @param PuppetViewportService service The render service to publish to.
 * @param EditorSession session The session to derive from.
 * @param Flow<MeshOverlaySizes> sizes The overlay sizes, re-emitted when the density changes.
 * @param Flow<Map<DeformerId, FloatArray>?> liveWorld A drag's moved world points per lattice, or null for none.
 */
internal suspend fun publishLatticeCage(
	service: PuppetViewportService,
	session: EditorSession,
	sizes: Flow<MeshOverlaySizes>,
	liveWorld: Flow<Map<DeformerId, FloatArray>?>,
) {
	val producer = LatticeCageProducer()
	combine(session.mode, session.meshSelection, session.meshPreviewSelection, session.model, sizes) { mode, committed, preview, model, cageSizes ->
		LatticeCageInputs(mode, preview ?: committed, model, cageSizes)
	}
		.combine(liveWorld) { inputs, live -> producer.produce(inputs.mode, inputs.selection, inputs.model, inputs.sizes, live) }
		.distinctUntilChanged { previous, next -> previous === next }
		.collect { cage -> service.setEditDirectOverlay(cage) }
}

/**
 * One derive's session inputs, taken together so the derive never pairs one emission's selection with another's
 * model by accident of timing.
 *
 * @property EditorMode mode The editor mode.
 * @property MeshSelection selection The selection to show: the brush preview when one is live.
 * @property PuppetModel model The model.
 * @property MeshOverlaySizes sizes The overlay sizes.
 */
private class LatticeCageInputs(
	val mode: EditorMode,
	val selection: MeshSelection,
	val model: PuppetModel,
	val sizes: MeshOverlaySizes,
)