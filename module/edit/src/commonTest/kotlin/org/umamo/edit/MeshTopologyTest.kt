package org.umamo.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the pure topology queries: edge deduplication and canonicalization, per-element vertex
 * coverage, and the three derive-up rules the select-mode conversions and display highlights share.
 */
class MeshTopologyTest {
	// Two triangles sharing the (1, 2) edge - the minimal mesh with an interior edge.
	private val stripIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// The "spoke" fixture: center triangle T (face 0, vertices 0-1-2) shares each of its three edges with
	// exactly one neighbor - A (face 1) across (0,1), B (face 2) across (1,2), C (face 3) across (0,2).
	private val spokeIndices = intArrayOf(0, 1, 2, 0, 1, 3, 1, 2, 4, 2, 0, 5)

	/** uniqueEdges canonicalizes to (low, high) and emits a shared interior edge once. */
	@Test
	fun uniqueEdgesDedupsAndCanonicalizes() {
		val edges = MeshTopology.uniqueEdges(stripIndices)
		assertEquals(5, edges.size, "two triangles sharing one edge have five unique edges")
		assertEquals(
			setOf(
				MeshElement.Edge(0, 1),
				MeshElement.Edge(1, 2),
				MeshElement.Edge(0, 2),
				MeshElement.Edge(1, 3),
				MeshElement.Edge(2, 3),
			),
			edges.toSet(),
		)
		assertTrue(edges.all { edge -> edge.endpointLow <= edge.endpointHigh }, "every edge is canonical")
	}

	/** edgesOfTriangle and verticesOfTriangle read one triangle's corners. */
	@Test
	fun perTriangleAccessors() {
		assertEquals(setOf(1, 2, 3), MeshTopology.verticesOfTriangle(stripIndices, 1))
		assertEquals(
			setOf(MeshElement.Edge(1, 3), MeshElement.Edge(2, 3), MeshElement.Edge(1, 2)),
			MeshTopology.edgesOfTriangle(stripIndices, 1).toSet(),
		)
	}

	/** coveredVertexIndices resolves each element kind: a vertex itself, an edge its endpoints, a face its corners. */
	@Test
	fun coveredVertexIndicesResolvesEachElementKind() {
		val elements =
			setOf(
				MeshElement.Vertex(5),
				MeshElement.Edge.of(3, 1),
				MeshElement.Face(0),
			)
		assertEquals(setOf(0, 1, 2, 3, 5), MeshTopology.coveredVertexIndices(elements, spokeIndices))
	}

	/** An edge derives from a vertex set only when both endpoints are selected. */
	@Test
	fun edgesWithBothEndpointsSelectedIsStrict() {
		val edges = MeshTopology.edgesWithBothEndpointsSelected(spokeIndices, setOf(0, 1, 3))
		assertEquals(
			setOf(MeshElement.Edge(0, 1), MeshElement.Edge(0, 3), MeshElement.Edge(1, 3)),
			edges,
		)
	}

	/** A face derives from a vertex set only when all three of its vertices are selected. */
	@Test
	fun facesWithAllVerticesSelectedIsStrict() {
		assertEquals(
			setOf(MeshElement.Face(0)),
			MeshTopology.facesWithAllVerticesSelected(spokeIndices, setOf(0, 1, 2)),
		)
		assertTrue(
			MeshTopology.facesWithAllVerticesSelected(spokeIndices, setOf(0, 1, 4)).isEmpty(),
			"two of three vertices never derive a face",
		)
	}

	/** A face derives from an edge set only when all three of its OWN edges are selected, never by coverage. */
	@Test
	fun facesWithAllEdgesSelectedIgnoresVertexCoverage() {
		val neighborEdges =
			setOf(
				MeshElement.Edge.of(0, 3),
				MeshElement.Edge.of(1, 3),
				MeshElement.Edge.of(1, 4),
				MeshElement.Edge.of(2, 4),
			)
		assertTrue(
			MeshTopology.facesWithAllEdgesSelected(spokeIndices, neighborEdges).isEmpty(),
			"edges covering T's vertices do not select T",
		)
		assertEquals(
			setOf(MeshElement.Face(0)),
			MeshTopology.facesWithAllEdgesSelected(
				spokeIndices,
				setOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(1, 2), MeshElement.Edge.of(0, 2)),
			),
		)
	}

	// Two disconnected components: the strip (vertices 0-3) plus a lone triangle (vertices 4-6).
	private val twoIslandIndices = intArrayOf(0, 1, 2, 1, 3, 2, 4, 5, 6)

	/** buildVertexAdjacency lists each vertex's edge-sharing neighbors once, empty for unused vertices. */
	@Test
	fun adjacencyListsEdgeNeighborsOnce() {
		val adjacency = MeshTopology.buildVertexAdjacency(5, stripIndices)
		assertEquals(setOf(1, 2), adjacency[0].toSet(), "vertex 0 neighbors")
		assertEquals(setOf(0, 2, 3), adjacency[1].toSet(), "vertex 1 neighbors")
		assertEquals(setOf(0, 1, 3), adjacency[2].toSet(), "vertex 2 neighbors; the shared (1,2) edge appears once")
		assertEquals(setOf(1, 2), adjacency[3].toSet(), "vertex 3 neighbors")
		assertEquals(emptySet(), adjacency[4].toSet(), "a vertex outside every triangle has no neighbors")
	}

	/** connectedVertices floods a whole component and never crosses to a disconnected one. */
	@Test
	fun connectedVerticesFloodsOneComponent() {
		val adjacency = MeshTopology.buildVertexAdjacency(7, twoIslandIndices)
		assertEquals(setOf(0, 1, 2, 3), MeshTopology.connectedVertices(adjacency, 0), "the strip floods from vertex 0")
		assertEquals(setOf(4, 5, 6), MeshTopology.connectedVertices(adjacency, 5), "the lone triangle floods from vertex 5")
		assertEquals(emptySet(), MeshTopology.connectedVertices(adjacency, 99), "an out-of-range seed yields nothing")
	}

	/** selectionIslands splits by connectivity WITHIN the subset: an unselected bridge vertex separates islands. */
	@Test
	fun selectionIslandsSplitOnUnselectedBridges() {
		val adjacency = MeshTopology.buildVertexAdjacency(7, twoIslandIndices)

		// The whole strip selected is one island; adding the lone triangle's vertices makes a second.
		val islands = MeshTopology.selectionIslands(adjacency, setOf(0, 1, 2, 3, 4, 5, 6))
		assertEquals(2, islands.size, "two mesh components make two islands")
		assertEquals(setOf(setOf(0, 1, 2, 3), setOf(4, 5, 6)), islands.toSet())

		// Selecting the strip's two ends WITHOUT the middle vertices splits them: 0 and 3 share no
		// direct edge, so the sub-graph induced on {0, 3} is two singleton islands.
		val split = MeshTopology.selectionIslands(adjacency, setOf(0, 3))
		assertEquals(setOf(setOf(0), setOf(3)), split.toSet(), "an unselected bridge separates the ends")

		// A connected sub-selection stays one island even when more of the mesh exists around it.
		val joined = MeshTopology.selectionIslands(adjacency, setOf(0, 1, 3))
		assertEquals(setOf(setOf(0, 1, 3)), joined.toSet(), "0-1 and 1-3 edges join all three")

		assertEquals(emptyList(), MeshTopology.selectionIslands(adjacency, emptySet()), "an empty selection has no islands")
	}
}
