package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
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

/*
 * The mesh overlay's derive, shared by both work surfaces: the Edit cage - the session meshes' edges and the
 * flags of their vertices, edges, and faces, as the renderer draws them - and the wireframe of the shown
 * meshes outside it (every shown mesh in Object mode), which only the 2D viewport asks for.  The 2D viewport
 * publishes the overlay alone and the renderer draws it from the art's deformed positions
 * (viewport2d/MeshOverlayPublish.kt); a UV area pairs the cage with its meshes' display positions
 * (uv/UvSceneOverlay.kt).  Nothing here draws; the flags come from buildHighlightSets, the same derive-up
 * and flush-down rules the selection gestures use, so what lights up cannot drift from what a click selects.
 */

/** The flags of a mesh with nothing selected and no active element: the renderer reads empty as all idle. */
private val NO_FLAGS = ByteArray(0)

/**
 * The overlay's sizes for a screen density: a 3.5 dp vertex dot radius, a 1 dp edge (full width; the
 * renderer halves it into the band's half-width), and a 2.5 dp face-centroid dot radius, the same in the
 * 2D viewport and the UV editor, in the display pixels the renderer multiplies by its render scale.
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
 * Turns the session's state into the renderer's [MeshOverlay], keeping everything it can from one call to
 * the next so the renderer re-uploads nothing it already has: a mesh's edge list lives as long as its
 * index array (and outlives a mode switch, since the cage and the wireframe share it), its flag arrays as
 * long as its selected set (by identity), active element, select mode, and topology, a wireframe entry as
 * long as its topology, and the whole value as long as its kind, every mesh entry, and the sizes - the
 * renderer compares edge and flag arrays by identity, and the render service compares the value by identity.
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

	/**
	 * One drawable's plain-wireframe entry and the topology it was built over.
	 *
	 * @property Topology topology The topology (compared by identity).
	 * @property MeshOverlayMesh entry The entry.
	 */
	private class WireframeEntry(
		val topology: Topology,
		val entry: MeshOverlayMesh,
	)

	private val topologyById = HashMap<DrawableId, Topology>()
	private val entryById = HashMap<DrawableId, Entry>()
	private val wireframeEntryById = HashMap<DrawableId, WireframeEntry>()
	private var lastOverlay: MeshOverlay? = null

	/** The model the topology cache was last trimmed to the drawables of, by identity. */
	private var trimmedToModel: PuppetModel? = null

	/**
	 * The overlay for one state, the previous instance when nothing it shows changed.  In Edit mode: the
	 * plain wireframe of every drawable in [wireframeOver] that is outside the session, in the model's order,
	 * then the cage - every session mesh with a mesh, in the session's order - or null when neither lists
	 * anything.  Outside Edit mode: the object wireframe of every drawable in [wireframeOver], or null when
	 * none is given or none can be shown.  A session mesh the renderer cannot pose (a hidden ancestor) is
	 * still listed; the renderer skips it per frame.  A surface that shows only some of the session's meshes
	 * (a UV area shows those mapped onto its page or layer) narrows the cage with [shownIds].
	 *
	 * @param EditorMode mode The editor mode.
	 * @param MeshSelection selection The selection to show.
	 * @param PuppetModel model The model the selection is over.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @param Set<DrawableId>? shownIds The session meshes the surface shows, or null for all of them.
	 * @param Set<DrawableId>? wireframeOver The shown drawables to wireframe, or null for no wireframe.
	 * @return MeshOverlay? The overlay, or null for none.
	 */
	fun produce(
		mode: EditorMode,
		selection: MeshSelection,
		model: PuppetModel,
		sizes: MeshOverlaySizes,
		shownIds: Set<DrawableId>? = null,
		wireframeOver: Set<DrawableId>? = null,
	): MeshOverlay? {
		val editing = mode == EditorMode.Edit
		val sessionIds = if (editing) selection.drawableIds.toSet() else emptySet()
		val wireframeIds = wireframeOver.orEmpty()
		// A drawable's edges are the same in the cage and in the wireframe, so its topology outlives a mode
		// switch and is dropped only once the drawable leaves the model.  A model never changes under its
		// identity, so the trim runs once per model rather than collecting every drawable's id per derive.
		if (model !== trimmedToModel) {
			val modelIds = model.drawables.mapTo(HashSet(model.drawables.size)) { drawable -> drawable.id }
			topologyById.keys.retainAll(modelIds)
			trimmedToModel = model
		}
		val meshById = HashMap<DrawableId, DrawableMesh>(sessionIds.size)
		val wireframeEntries = ArrayList<MeshOverlayMesh>()
		for (drawable in model.drawables) {
			val mesh = drawable.mesh ?: continue
			if (drawable.id in sessionIds) {
				meshById[drawable.id] = mesh
			} else if (drawable.id in wireframeIds && mesh.indices.isNotEmpty()) {
				val topology = topologyOf(drawable.id, mesh)
				if (topology.wellFormed) {
					wireframeEntries.add(wireframeEntryOf(drawable.id, topology))
				}
			}
		}
		wireframeEntryById.keys.retainAll(wireframeIds)
		if (!editing) {
			return overlayOf(MeshOverlayKind.ObjectWireframe, MeshOverlaySelectMode.Vertex, wireframeEntries, sizes)
		}
		entryById.keys.retainAll(sessionIds)
		val entries = ArrayList<MeshOverlayMesh>(wireframeEntries.size + selection.drawableIds.size)
		entries.addAll(wireframeEntries)
		for (drawableId in selection.drawableIds) {
			if (shownIds != null && drawableId !in shownIds) {
				continue
			}
			val mesh = meshById[drawableId] ?: continue
			val topology = topologyOf(drawableId, mesh)
			if (topology.wellFormed) {
				entries.add(entryOf(drawableId, topology, selection))
			}
		}
		return overlayOf(MeshOverlayKind.Edit, overlaySelectModeOf(selection.selectMode), entries, sizes)
	}

	/**
	 * The overlay over [entries], or the previous instance when its kind, select mode, sizes, and entries are
	 * the same; null for no entries.
	 *
	 * @param MeshOverlayKind kind The overlay kind.
	 * @param MeshOverlaySelectMode selectMode The select mode (read by the Edit kind alone).
	 * @param List<MeshOverlayMesh> entries The entries, in layout order.
	 * @param MeshOverlaySizes sizes The overlay sizes.
	 * @return MeshOverlay? The overlay, or null for none.
	 */
	private fun overlayOf(kind: MeshOverlayKind, selectMode: MeshOverlaySelectMode, entries: List<MeshOverlayMesh>, sizes: MeshOverlaySizes): MeshOverlay? {
		if (entries.isEmpty()) {
			return null
		}
		val previous = lastOverlay
		if (previous != null && previous.kind == kind && previous.selectMode == selectMode && previous.sizes == sizes && sameEntries(previous.meshes, entries)) {
			return previous
		}
		val overlay = MeshOverlay(kind, selectMode, entries, sizes)
		lastOverlay = overlay
		return overlay
	}

	/**
	 * A drawable's plain-wireframe entry - its edges, nothing flagged - kept while its topology is the one it
	 * was built over.  The same entry serves an Edit overlay (as a mesh outside the edit) and an object
	 * wireframe, which reads no role.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @param Topology topology Its topology.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun wireframeEntryOf(drawableId: DrawableId, topology: Topology): MeshOverlayMesh {
		val cached = wireframeEntryById[drawableId]
		if (cached != null && cached.topology === topology) {
			return cached.entry
		}
		val entry = MeshOverlayMesh(drawableId, topology.vertexCount, topology.edgeEndpoints, NO_FLAGS, NO_FLAGS, NO_FLAGS, null, null, null, wireframeOnly = true)
		wireframeEntryById[drawableId] = WireframeEntry(topology, entry)
		return entry
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