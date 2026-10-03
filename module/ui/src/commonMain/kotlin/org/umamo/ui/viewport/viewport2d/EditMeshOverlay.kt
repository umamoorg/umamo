package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OVERLAY_FLAG_ACTIVE
import org.umamo.render.puppet.OVERLAY_FLAG_SELECTED
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.buildHighlightSets

/*
 * The Edit-mode mesh overlay as the renderer draws it: the session meshes' edges, and the flags of their
 * vertices, edges, and faces, derived here from the session and published to the render service, which
 * draws them over the art from the art's own deformed positions.  Nothing here draws; the flags come from
 * buildHighlightSets, the same derive-up and flush-down rules the selection gestures use, so what lights
 * up cannot drift from what a click selects.
 */

/** The flags of a mesh with nothing selected and no active element: the renderer reads empty as all idle. */
private val NO_FLAGS = ByteArray(0)

/**
 * The overlay's sizes for a screen density: a 3.5 dp vertex dot radius, a 1 dp edge (full width; the
 * renderer halves it into the band's half-width), and a 2.5 dp face-centroid dot radius - the sizes the
 * UV editor's wireframe draws at too - in the display pixels the renderer multiplies by its render scale.
 *
 * @param Density density The screen density.
 * @return MeshOverlaySizes The sizes in display pixels.
 */
internal fun editMeshOverlaySizes(density: Density): MeshOverlaySizes =
	with(density) {
		MeshOverlaySizes(
			vertexDotRadiusPx = 3.5.dp.toPx(),
			edgeWidthPx = 1.dp.toPx(),
			faceDotRadiusPx = 2.5.dp.toPx(),
		)
	}

/**
 * Publishes the Edit-mode mesh overlay to [service] for as long as it runs: derived from the mode, the
 * brush stroke's preview or else the committed mesh selection, the model, and the sizes, and published
 * whenever the derived value changes by identity (the producer hands back the same instance while nothing
 * it shows changed, so a Grab's confirm, which commits positions only, publishes nothing).
 *
 * The derive runs on [deriveDispatcher], one input at a time with the latest winning (a selection over a
 * whole large rig costs about a tenth of a second), and the publish lands back on the caller's dispatcher.
 *
 * @param PuppetViewportService service The render service to publish to.
 * @param EditorSession session The session to derive from.
 * @param Flow<MeshOverlaySizes> sizes The overlay sizes, re-emitted when the density changes.
 * @param CoroutineDispatcher deriveDispatcher Where the derive runs.
 */
internal suspend fun publishEditMeshOverlay(
	service: PuppetViewportService,
	session: EditorSession,
	sizes: Flow<MeshOverlaySizes>,
	deriveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
	val producer = EditMeshOverlayProducer()
	combine(session.mode, session.meshSelection, session.meshPreviewSelection, session.model, sizes) { mode, committed, preview, model, overlaySizes ->
		EditMeshOverlayInputs(mode, preview ?: committed, model, overlaySizes)
	}
		.conflate()
		.map { inputs -> producer.produce(inputs.mode, inputs.selection, inputs.model, inputs.sizes) }
		.flowOn(deriveDispatcher)
		.conflate()
		.distinctUntilChanged { previous, next -> previous === next }
		.collect { overlay -> service.setMeshOverlay(overlay) }
}

/**
 * One derive's inputs, taken together so the derive never pairs one emission's selection with another's
 * model by accident of timing.
 *
 * @property EditorMode mode The editor mode.
 * @property MeshSelection selection The selection to show: the brush preview when one is live.
 * @property PuppetModel model The model.
 * @property MeshOverlaySizes sizes The overlay sizes.
 */
private class EditMeshOverlayInputs(
	val mode: EditorMode,
	val selection: MeshSelection,
	val model: PuppetModel,
	val sizes: MeshOverlaySizes,
)

/**
 * Turns the session's state into the renderer's [MeshOverlay], keeping everything it can from one call to
 * the next so the renderer re-uploads nothing it already has: a mesh's edge list lives as long as its
 * index array, its flag arrays as long as its selected set (by identity), active element, select mode, and
 * topology, and the whole value as long as every mesh entry and the sizes - the renderer compares edge and
 * flag arrays by identity, and the render service compares the value by identity.
 *
 * Not thread-safe: one sequential derive owns it.
 */
internal class EditMeshOverlayProducer {
	/**
	 * One mesh's topology as the overlay ships it.
	 *
	 * @property IntArray indices The triangle indices it was built from (compared by identity).
	 * @property Int vertexCount The vertex count it was built against.
	 * @property IntArray edgeEndpoints The unique edges, in uniqueEdges' order.
	 * @property Boolean wellFormed Whether every index names one of the mesh's vertices in whole triangles;
	 *   a mesh that does not is left out of the overlay rather than handed to the renderer.
	 */
	private class Topology(
		val indices: IntArray,
		val vertexCount: Int,
		val edgeEndpoints: IntArray,
		val wellFormed: Boolean,
	)

	/**
	 * One mesh's last entry and what it was built from.
	 *
	 * @property Set<MeshElement> elements The selected set it reflects (compared by identity).
	 * @property MeshElement? active The active element it reflects.
	 * @property MeshSelectMode selectMode The select mode it reflects.
	 * @property Topology topology The topology it was built over (compared by identity).
	 * @property MeshOverlayMesh entry The entry.
	 */
	private class Entry(
		val elements: Set<MeshElement>,
		val active: MeshElement?,
		val selectMode: MeshSelectMode,
		val topology: Topology,
		val entry: MeshOverlayMesh,
	)

	private val topologyById = HashMap<DrawableId, Topology>()
	private val entryById = HashMap<DrawableId, Entry>()
	private var lastOverlay: MeshOverlay? = null

	/**
	 * The overlay for one state: null outside Edit mode or when no session mesh can be shown, otherwise
	 * every session mesh with a mesh, in the session's order, the previous instance when nothing it shows
	 * changed.  A session mesh the renderer cannot pose (a hidden ancestor) is still listed; the renderer
	 * skips it per frame.
	 *
	 * @param EditorMode mode The editor mode.
	 * @param MeshSelection selection The selection to show.
	 * @param PuppetModel model The model the selection is over.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @return MeshOverlay? The overlay, or null for none.
	 */
	fun produce(mode: EditorMode, selection: MeshSelection, model: PuppetModel, sizes: MeshOverlaySizes): MeshOverlay? {
		if (mode != EditorMode.Edit) {
			return null
		}
		val sessionIds = selection.drawableIds.toSet()
		topologyById.keys.retainAll(sessionIds)
		entryById.keys.retainAll(sessionIds)
		val meshById = HashMap<DrawableId, DrawableMesh>(sessionIds.size)
		for (drawable in model.drawables) {
			val mesh = drawable.mesh
			if (mesh != null && drawable.id in sessionIds) {
				meshById[drawable.id] = mesh
			}
		}
		val entries = ArrayList<MeshOverlayMesh>(selection.drawableIds.size)
		for (drawableId in selection.drawableIds) {
			val mesh = meshById[drawableId] ?: continue
			val topology = topologyOf(drawableId, mesh)
			if (topology.wellFormed) {
				entries.add(entryOf(drawableId, topology, selection))
			}
		}
		if (entries.isEmpty()) {
			return null
		}
		val overlaySelectMode = overlaySelectModeOf(selection.selectMode)
		val previous = lastOverlay
		if (previous != null && previous.selectMode == overlaySelectMode && previous.sizes == sizes && sameEntries(previous.meshes, entries)) {
			return previous
		}
		val overlay = MeshOverlay(MeshOverlayKind.Edit, overlaySelectMode, entries, sizes)
		lastOverlay = overlay
		return overlay
	}

	/**
	 * A mesh's topology, kept while its index array and vertex count are the ones it was built from.
	 *
	 * @param DrawableId drawableId The mesh's drawable.
	 * @param DrawableMesh mesh The mesh.
	 * @return Topology The topology.
	 */
	private fun topologyOf(drawableId: DrawableId, mesh: DrawableMesh): Topology {
		val cached = topologyById[drawableId]
		if (cached != null && cached.indices === mesh.indices && cached.vertexCount == mesh.vertexCount) {
			return cached
		}
		val vertexCount = mesh.vertexCount
		val wellFormed = mesh.indices.size % 3 == 0 && mesh.indices.all { index -> index in 0 until vertexCount }
		val edgeEndpoints = if (wellFormed) MeshTopology.uniqueEdgeEndpoints(mesh.indices) else IntArray(0)
		val topology = Topology(mesh.indices, vertexCount, edgeEndpoints, wellFormed)
		topologyById[drawableId] = topology
		return topology
	}

	/**
	 * A mesh's overlay entry, kept while its selected set (by identity), active element, select mode, and
	 * topology are the ones it was built from.
	 *
	 * @param DrawableId drawableId The mesh's drawable.
	 * @param Topology topology Its topology.
	 * @param MeshSelection selection The selection to show.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun entryOf(drawableId: DrawableId, topology: Topology, selection: MeshSelection): MeshOverlayMesh {
		val elements = selection.elementsOf(drawableId)
		val active = selection.activeElement?.takeIf { activeElement -> activeElement.drawableId == drawableId }?.element
		val cached = entryById[drawableId]
		if (cached != null && cached.elements === elements && cached.active == active && cached.selectMode == selection.selectMode && cached.topology === topology) {
			return cached.entry
		}
		val entry = buildEntry(drawableId, topology, elements, active, selection.selectMode)
		entryById[drawableId] = Entry(elements, active, selection.selectMode, topology, entry)
		return entry
	}

	/**
	 * Builds one mesh's entry: the highlight sets of its selection as flag bytes, elements outside the mesh
	 * dropped, and flag 2 at exactly the active element's ordinal (an active element outside the mesh is no
	 * active element), since the renderer draws a flag-2 primitive only through the active ordinal.
	 *
	 * @param DrawableId drawableId The mesh's drawable.
	 * @param Topology topology Its topology.
	 * @param Set<MeshElement> elements Its selected elements.
	 * @param MeshElement? active Its active element, or null.
	 * @param MeshSelectMode selectMode The select mode.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun buildEntry(
		drawableId: DrawableId,
		topology: Topology,
		elements: Set<MeshElement>,
		active: MeshElement?,
		selectMode: MeshSelectMode,
	): MeshOverlayMesh {
		val vertexCount = topology.vertexCount
		val edgeEndpoints = topology.edgeEndpoints
		if (elements.isEmpty() && active == null) {
			return MeshOverlayMesh(drawableId, vertexCount, edgeEndpoints, NO_FLAGS, NO_FLAGS, NO_FLAGS, null, null, null)
		}
		val triangleCount = topology.indices.size / 3
		// A selection can name elements of an older shape of this mesh (the selection and the model reach the
		// derive from different commits for a moment), and the highlight derive indexes the triangle list
		// directly, so only the elements this mesh has go in.
		val inMesh = { element: MeshElement -> elementInMesh(element, vertexCount, triangleCount) }
		val meshElements = if (elements.all(inMesh)) elements else elements.filterTo(HashSet(), inMesh)
		val meshActive = active?.takeIf(inMesh)
		val highlight = buildHighlightSets(meshElements, meshActive, selectMode, topology.indices)

		val vertexFlags = ByteArray(vertexCount)
		for (vertexIndex in highlight.selectedVertexIndices) {
			vertexFlags[vertexIndex] = OVERLAY_FLAG_SELECTED
		}
		val activeVertex = highlight.activeVertexIndex
		if (activeVertex != null) {
			vertexFlags[activeVertex] = OVERLAY_FLAG_ACTIVE
		}

		val edgeCount = edgeEndpoints.size / 2
		val edgeFlags = ByteArray(edgeCount)
		val selectedEdges = highlight.selectedEdges
		val activeEdgeElement = highlight.activeEdge
		var activeEdge: Int? = null
		if (selectedEdges.isNotEmpty() || activeEdgeElement != null) {
			for (edgeOrdinal in 0 until edgeCount) {
				val edge = MeshElement.Edge(edgeEndpoints[edgeOrdinal * 2], edgeEndpoints[edgeOrdinal * 2 + 1])
				if (edge == activeEdgeElement) {
					edgeFlags[edgeOrdinal] = OVERLAY_FLAG_ACTIVE
					activeEdge = edgeOrdinal
				} else if (edge in selectedEdges) {
					edgeFlags[edgeOrdinal] = OVERLAY_FLAG_SELECTED
				}
			}
		}

		val faceFlags = ByteArray(triangleCount)
		for (faceIndex in highlight.selectedFaceIndices) {
			faceFlags[faceIndex] = OVERLAY_FLAG_SELECTED
		}
		val activeFace = highlight.activeFaceIndex
		if (activeFace != null) {
			faceFlags[activeFace] = OVERLAY_FLAG_ACTIVE
		}
		return MeshOverlayMesh(drawableId, vertexCount, edgeEndpoints, vertexFlags, edgeFlags, faceFlags, activeVertex, activeEdge, activeFace)
	}

	/**
	 * Whether an element is one of the mesh's own: a vertex it has, an edge between two of its vertices,
	 * or a triangle it has.
	 *
	 * @param MeshElement element The element.
	 * @param Int vertexCount The mesh's vertex count.
	 * @param Int triangleCount The mesh's triangle count.
	 * @return Boolean True when the mesh has it.
	 */
	private fun elementInMesh(element: MeshElement, vertexCount: Int, triangleCount: Int): Boolean =
		when (element) {
			is MeshElement.Vertex -> element.index in 0 until vertexCount
			is MeshElement.Edge -> element.endpointLow in 0 until vertexCount && element.endpointHigh in 0 until vertexCount
			is MeshElement.Face -> element.triangleIndex in 0 until triangleCount
		}

	/**
	 * Whether two entry lists hold the same entries, by identity and in order.
	 *
	 * @param List<MeshOverlayMesh> previous The previous overlay's entries.
	 * @param List<MeshOverlayMesh> next This call's entries.
	 * @return Boolean True when every entry is the same instance.
	 */
	private fun sameEntries(previous: List<MeshOverlayMesh>, next: List<MeshOverlayMesh>): Boolean =
		previous.size == next.size && previous.indices.all { entryIndex -> previous[entryIndex] === next[entryIndex] }

	/**
	 * The renderer's select mode for the session's.
	 *
	 * @param MeshSelectMode selectMode The session's select mode.
	 * @return MeshOverlaySelectMode The overlay's.
	 */
	private fun overlaySelectModeOf(selectMode: MeshSelectMode): MeshOverlaySelectMode =
		when (selectMode) {
			MeshSelectMode.Vertex -> MeshOverlaySelectMode.Vertex
			MeshSelectMode.Edge -> MeshOverlaySelectMode.Edge
			MeshSelectMode.Face -> MeshOverlaySelectMode.Face
		}
}