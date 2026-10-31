package org.umamo.edit

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.WarpLatticeLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the element topology Edit mode works over: a triangle mesh keeps exactly the edges and neighbors its triangle
 * indices give, and a lattice is quads - its edges are the grid lines alone, a face is a whole cell, and the select-mode
 * rules derive cells from their four corners and four sides, never from a diagonal.
 */
class ElementTopologyTest {
	// A 2 x 2 lattice: points row by row, (row, column) at 3 * row + column, cells 0..3 in the same order.
	//   0 - 1 - 2
	//   |   |   |
	//   3 - 4 - 5
	//   |   |   |
	//   6 - 7 - 8
	private val lattice = ElementTopology.ofLattice(WarpLatticeLayout(2, 2))

	@Test
	fun aTriangleTopologyKeepsItsEdgeOrderAndNeighbors() {
		val spokeIndices = intArrayOf(0, 1, 2, 0, 1, 3, 1, 2, 4, 2, 0, 5)
		val topology = ElementTopology.ofTriangles(6, spokeIndices)

		assertEquals(MeshTopology.uniqueEdgeEndpoints(spokeIndices).toList(), topology.edgeEndpoints.toList(), "the same edges in the same order")
		assertEquals(MeshTopology.uniqueEdges(spokeIndices), topology.edges)
		assertEquals(4, topology.faceCount)
		assertEquals(setOf(0, 1, 2), topology.verticesOfFace(0))
		assertEquals(setOf(0, 1, 4, 5), topology.adjacency[2].toSet(), "vertex 2 joins every corner it shares a triangle with")
	}

	@Test
	fun aLatticesEdgesAreItsGridLinesAlone() {
		assertEquals(12, lattice.edges.size, "three rows of two sides and three columns of two")
		assertEquals(WarpLatticeLayout(2, 2).edgeEndpoints.toList().chunked(2).map { pair -> MeshElement.Edge.of(pair[0], pair[1]) }.toSet(), lattice.edges.toSet())

		for (diagonal in listOf(MeshElement.Edge.of(0, 4), MeshElement.Edge.of(1, 3), MeshElement.Edge.of(4, 8), MeshElement.Edge.of(5, 7))) {
			assertFalse(diagonal in lattice.edges, "no cell is split by $diagonal")
		}

		assertEquals(setOf(1, 3, 5, 7), lattice.adjacency[4].toSet(), "the middle point's neighbors are along the grid lines")
		assertEquals(setOf(1, 3), lattice.adjacency[0].toSet(), "a corner has two")
	}

	@Test
	fun aCellIsOneFaceOfFourCornersAndFourSides() {
		assertEquals(4, lattice.faceCount)
		assertEquals(4, lattice.cornersPerFace)
		assertEquals(setOf(4, 5, 8, 7), lattice.verticesOfFace(3))
		assertEquals(
			setOf(MeshElement.Edge.of(4, 5), MeshElement.Edge.of(5, 8), MeshElement.Edge.of(7, 8), MeshElement.Edge.of(4, 7)),
			lattice.edgesOfFace(3).toSet(),
		)
		assertEquals(setOf(0, 1, 3, 4, 7, 8, 5), MeshTopology.coveredVertexIndices(setOf(MeshElement.Face(0), MeshElement.Face(3)), lattice))
		assertTrue(lattice.hasFace(3))
		assertFalse(lattice.hasFace(4))
	}

	@Test
	fun theSelectModeRulesDeriveCellsFromCornersAndSides() {
		assertEquals(setOf(MeshElement.Face(0)), MeshTopology.facesWithAllVerticesSelected(lattice, setOf(0, 1, 3, 4)), "all four corners")
		assertTrue(MeshTopology.facesWithAllVerticesSelected(lattice, setOf(0, 1, 4)).isEmpty(), "three corners are no cell")
		assertEquals(
			setOf(MeshElement.Face(0)),
			MeshTopology.facesWithAllEdgesSelected(lattice, lattice.edgesOfFace(0).toSet()),
			"all four sides",
		)
		assertEquals(
			setOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(0, 3), MeshElement.Edge.of(1, 4), MeshElement.Edge.of(3, 4)),
			MeshTopology.edgesWithBothEndpointsSelected(lattice, setOf(0, 1, 3, 4)),
			"a cell's corners light its four sides and not a diagonal",
		)

		val owner: ElementOwner = ElementOwner.Deformer(DeformerId("lattice"))
		val corners = MeshSelection.editing(listOf(owner)).copy(elementsByOwner = mapOf(owner to setOf<MeshElement>(MeshElement.Vertex(4), MeshElement.Vertex(5), MeshElement.Vertex(7), MeshElement.Vertex(8))))
		val cells = MeshSelectionOps.changeSelectMode(corners, MeshSelectMode.Face) { lattice }
		assertEquals(setOf<MeshElement>(MeshElement.Face(3)), cells.elementsOf(owner))
		val flushed = MeshSelectionOps.changeSelectMode(cells, MeshSelectMode.Edge) { lattice }
		assertEquals(lattice.edgesOfFace(3).toSet<MeshElement>(), flushed.elementsOf(owner), "a cell flushes to its four sides")
		assertEquals(4, MeshSelectionOps.selectAll(cells.copy(elementsByOwner = emptyMap())) { lattice }.elementsOf(owner).size, "every cell")
	}

	@Test
	fun anUnconnectedTopologyHasVerticesAndNothingElse() {
		val topology = ElementTopology.unconnected(3)

		assertEquals(3, topology.vertexCount)
		assertEquals(0, topology.faceCount)
		assertTrue(topology.edges.isEmpty())
		assertTrue(topology.adjacency.all { neighbors -> neighbors.isEmpty() })
	}
}