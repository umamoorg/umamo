package org.umamo.edit

/**
 * Pure topology queries over an [ElementTopology] - a drawable's triangles or a lattice's cells.  The single
 * source of truth for Blender's select-mode rules: [MeshSelectionOps.changeSelectMode] uses these to convert a
 * selection between domains, and the gizmo overlay uses the same derive-up functions for its cross-domain
 * display highlights - so the flush / derive semantics live (and are tested) once.
 *
 * 三角形インデックス列に対する純粋なトポロジ照会。選択モード変換と表示ハイライトの共通基盤。
 */
object MeshTopology {
	/**
	 * The deduplicated undirected edges of a triangle index list.  Each interior edge is shared by two
	 * triangles but must appear once, so edges are canonicalized to (low, high) before deduplication.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return List<MeshElement.Edge> The unique edges, in first-encounter order.
	 */
	fun uniqueEdges(triangleIndices: IntArray): List<MeshElement.Edge> = ElementTopology.edgesOf(uniqueEdgeEndpoints(triangleIndices))

	/**
	 * The unique undirected edges of a triangle index list as flat endpoint pairs: exactly [uniqueEdges]'
	 * edges, in the same first-encounter order, low endpoint first, so an edge's ordinal means the same
	 * here as in the boxed list - and the same as in the triangles' [ElementTopology.edgeEndpoints].  For a
	 * consumer that ships edges as data (the GPU mesh overlay), without a boxed element per edge.
	 *
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return IntArray Two endpoints per unique edge.
	 */
	fun uniqueEdgeEndpoints(triangleIndices: IntArray): IntArray = ElementTopology.ringEdgeEndpoints(triangleIndices, 3)

	/**
	 * The union of vertices covered by a set of elements: a vertex covers itself, an edge its two
	 * endpoints, a face its corners.  This is the flush-down rule (any mode to vertex mode), and
	 * what the G / S / R operators transform for an edge or face selection.
	 *
	 * @param Set<MeshElement> elements The selected elements (any mix of domains).
	 * @param ElementTopology topology The owner's topology.
	 * @return Set<Int> The covered vertex indices.
	 */
	fun coveredVertexIndices(elements: Set<MeshElement>, topology: ElementTopology): Set<Int> {
		val covered = HashSet<Int>()

		for (element in elements) {
			when (element) {
				is MeshElement.Vertex -> {
					covered.add(element.index)
				}

				is MeshElement.Edge -> {
					covered.add(element.endpointLow)
					covered.add(element.endpointHigh)
				}

				is MeshElement.Face -> {
					covered.addAll(topology.verticesOfFace(element.index))
				}
			}
		}

		return covered
	}

	/**
	 * The vertices [elements] cover that a mesh of [vertexCount] vertices has: [coveredVertexIndices], less any index
	 * at or past the count.  A reader that holds a selection and a mesh it captured separately needs this, since the
	 * two can arrive a moment apart (a duplicate or a rip grows the selection before the reader sees the larger mesh).
	 * A set that needs no such drop is returned as [coveredVertexIndices] built it, so a sum over it runs in the
	 * order a modal transform's capture sums the same vertices in.
	 *
	 * @param Set<MeshElement> elements The selected elements (any mix of domains).
	 * @param ElementTopology topology The owner's topology.
	 * @param Int vertexCount How many vertices the reader's copy of the mesh has.
	 * @return Set<Int> The covered vertex indices inside the mesh.
	 */
	fun coveredVertexIndicesWithin(elements: Set<MeshElement>, topology: ElementTopology, vertexCount: Int): Set<Int> {
		val covered = coveredVertexIndices(elements, topology)

		if (covered.all { vertexIndex -> vertexIndex < vertexCount }) {
			return covered
		}

		return covered.filterTo(HashSet()) { vertexIndex -> vertexIndex < vertexCount }
	}

	/**
	 * The edges whose both endpoints are in the selected vertex set - the vertex-to-edge derive-up rule,
	 * also used to highlight edges while selecting vertices.
	 *
	 * @param ElementTopology topology The owner's topology.
	 * @param Set<Int> selectedVertexIndices The selected vertex indices.
	 * @return Set<MeshElement.Edge> The qualifying edges.
	 */
	fun edgesWithBothEndpointsSelected(topology: ElementTopology, selectedVertexIndices: Set<Int>): Set<MeshElement.Edge> =
		topology.edges
			.filter { edge -> edge.endpointLow in selectedVertexIndices && edge.endpointHigh in selectedVertexIndices }
			.toSet()

	/**
	 * The faces whose every vertex is in the selected vertex set - the vertex-to-face derive-up rule,
	 * also used to highlight faces while selecting vertices.
	 *
	 * @param ElementTopology topology The owner's topology.
	 * @param Set<Int> selectedVertexIndices The selected vertex indices.
	 * @return Set<MeshElement.Face> The qualifying faces.
	 */
	fun facesWithAllVerticesSelected(topology: ElementTopology, selectedVertexIndices: Set<Int>): Set<MeshElement.Face> {
		val faces = HashSet<MeshElement.Face>()

		for (faceIndex in 0 until topology.faceCount) {
			if (topology.verticesOfFace(faceIndex).all { vertexIndex -> vertexIndex in selectedVertexIndices }) {
				faces.add(MeshElement.Face(faceIndex))
			}
		}

		return faces
	}

	/**
	 * The faces whose every own edge is in the selected edge set - the edge-to-face derive-up rule,
	 * also used to highlight faces while selecting edges.  Strict per Blender: a face whose vertices are
	 * merely covered by neighboring faces' selected edges does not qualify.
	 *
	 * @param ElementTopology topology The owner's topology.
	 * @param Set<MeshElement.Edge> selectedEdges The selected (canonical) edges.
	 * @return Set<MeshElement.Face> The qualifying faces.
	 */
	fun facesWithAllEdgesSelected(topology: ElementTopology, selectedEdges: Set<MeshElement.Edge>): Set<MeshElement.Face> {
		val faces = HashSet<MeshElement.Face>()

		for (faceIndex in 0 until topology.faceCount) {
			if (topology.edgesOfFace(faceIndex).all { edge -> edge in selectedEdges }) {
				faces.add(MeshElement.Face(faceIndex))
			}
		}

		return faces
	}

	/**
	 * Every vertex connected to [seedVertexIndex] through the mesh's edges, the seed included - the flood
	 * fill behind Select Linked (Blender's L).  An out-of-range seed yields an empty set.
	 *
	 * @param List<IntArray> adjacency The neighbor table ([ElementTopology.adjacency]).
	 * @param Int seedVertexIndex The vertex to flood from.
	 * @return Set<Int> The seed's whole connected component.
	 */
	fun connectedVertices(adjacency: List<IntArray>, seedVertexIndex: Int): Set<Int> {
		if (seedVertexIndex !in adjacency.indices) {
			return emptySet()
		}

		val reached = HashSet<Int>()
		val frontier = ArrayDeque<Int>()
		reached.add(seedVertexIndex)
		frontier.add(seedVertexIndex)

		while (frontier.isNotEmpty()) {
			val vertexIndex = frontier.removeFirst()

			for (neighbor in adjacency[vertexIndex]) {
				if (reached.add(neighbor)) {
					frontier.add(neighbor)
				}
			}
		}

		return reached
	}

	/**
	 * Splits a vertex subset into its connected islands: the components of the sub-graph induced on
	 * [vertexIndices] (two selected vertices join an island only when a mesh edge connects them within
	 * the subset).  This is Blender's Individual Origins grouping - each island transforms about its own
	 * pivot.  Out-of-range indices are dropped; the island order follows the subset's iteration order.
	 *
	 * @param List<IntArray> adjacency The neighbor table ([ElementTopology.adjacency]).
	 * @param Set<Int> vertexIndices The vertex subset to split.
	 * @return List<Set<Int>> The islands, each a disjoint subset of [vertexIndices].
	 */
	fun selectionIslands(adjacency: List<IntArray>, vertexIndices: Set<Int>): List<Set<Int>> {
		val unvisited = vertexIndices.filterTo(LinkedHashSet()) { vertexIndex -> vertexIndex in adjacency.indices }
		val islands = ArrayList<Set<Int>>()

		while (unvisited.isNotEmpty()) {
			val seed = unvisited.first()
			val island = HashSet<Int>()
			val frontier = ArrayDeque<Int>()
			island.add(seed)
			unvisited.remove(seed)
			frontier.add(seed)

			while (frontier.isNotEmpty()) {
				val vertexIndex = frontier.removeFirst()

				for (neighbor in adjacency[vertexIndex]) {
					if (neighbor in unvisited) {
						unvisited.remove(neighbor)
						island.add(neighbor)
						frontier.add(neighbor)
					}
				}
			}

			islands.add(island)
		}

		return islands
	}
}