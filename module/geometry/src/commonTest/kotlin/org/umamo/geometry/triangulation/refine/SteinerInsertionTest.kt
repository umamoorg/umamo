package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.packVertexPair
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import org.umamo.geometry.triangulation.areaOf
import org.umamo.geometry.triangulation.buildConstrained
import org.umamo.geometry.triangulation.classifyRegions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One vertex at a time: a split exactly on a boundary edge, beside it on the outside (across a hull
 * edge and into a hole) and on the inside, either side of an interior segment, a refused split, and the
 * straight walk's results - inside, blocked, on an edge, along a constrained edge, through vertices,
 * and onto a vertex.  The store is validated and its regions checked against a fresh classification
 * after every change.
 */
class SteinerInsertionTest {
	private val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

	@Test
	fun aPointOnAnAxisAlignedBoundarySplitsItExactly() {
		val store = storeOf(square, listOf(ringOf(0, 4)))
		val vertex =
			SteinerInsertion(store).splitSegment(0, 1, 5.0, 0.0, false) { _, cutsInside, _ ->
				assertFalse(cutsInside)
				true
			}

		assertEquals(4, vertex)
		assertEquals(5.0, store.pointX(vertex))
		assertEquals(0.0, store.pointY(vertex))
		assertSplitConstraint(store, 0, 1, vertex)
		assertEquals(100.0, insideArea(store))
	}

	@Test
	fun aPointBesideAHullBoundaryGoesOutwardAndCarriesTheConstraint() {
		val store = storeOf(square, listOf(ringOf(0, 4)))
		val vertex =
			SteinerInsertion(store).splitSegment(0, 1, 5.0, -0.001, false) { _, cutsInside, _ ->
				assertFalse(cutsInside, "the outside is tried first")
				true
			}

		assertEquals((-0.001).toFloat().toDouble(), store.pointY(vertex))
		assertSplitConstraint(store, 0, 1, vertex)
		assertEquals(100.0 + 5.0 * 0.001, insideArea(store), 1e-9, "the sliver outside joined the inside")
	}

	@Test
	fun aPointBesideABoundaryOnItsInsideCutsTheSliverOut() {
		val store = storeOf(square, listOf(ringOf(0, 4)))
		val vertex =
			SteinerInsertion(store).splitSegment(0, 1, 5.0, 0.001, false) { _, cutsInside, _ ->
				assertTrue(cutsInside, "every candidate lies inside")
				true
			}

		assertSplitConstraint(store, 0, 1, vertex)
		assertEquals(100.0 - 5.0 * 0.001, insideArea(store), 1e-9, "the sliver inside left the domain")
	}

	@Test
	fun aHoleEdgeSplitsOutwardIntoTheHole() {
		val points = square + doubleArrayOf(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0)
		val store = storeOf(points, listOf(ringOf(0, 4), ringOf(4, 4)))
		val holeBottom = store.findHalfEdge(4, 5)
		// The hole's inside (the hole) is left of 4 -> 5, so the domain is on the right.
		assertFalse(store.regions[holeBottom / 3])
		val vertex =
			SteinerInsertion(store).splitSegment(4, 5, 5.0, 4.001, true) { _, cutsInside, _ ->
				assertFalse(cutsInside)
				true
			}

		assertSplitConstraint(store, 4, 5, vertex)
		assertEquals(96.0 + (store.pointY(vertex) - 4.0), insideArea(store), 1e-9, "the sliver in the hole joined the domain")
	}

	@Test
	fun anInteriorSegmentSplitsOnEitherSideWithoutChangingTheDomain() {
		val points = square + doubleArrayOf(2.0, 3.0, 8.0, 7.0)
		val store = storeOf(points, listOf(ringOf(0, 4)), listOf(intArrayOf(4, 5)))
		val insertion = SteinerInsertion(store)
		val above = insertion.splitSegment(4, 5, 5.0, 5.001, null) { _, cutsInside, _ -> !cutsInside }
		assertSplitConstraint(store, 4, 5, above)
		assertTrue(store.pointY(above) > 5.0, "the side tried first is the left")

		val below = insertion.splitSegment(4, above, 3.5, 3.999, null) { _, cutsInside, _ -> !cutsInside }
		assertSplitConstraint(store, 4, above, below)
		assertEquals(100.0, insideArea(store), 1e-9)
	}

	@Test
	fun aRefusedSplitLeavesTheStoreAsItWas() {
		val store = storeOf(square, listOf(ringOf(0, 4)))
		val before = store.realTriangles(store.regions)
		val vertex = SteinerInsertion(store).splitSegment(0, 1, 5.0, -0.001, false) { _, _, _ -> false }

		assertEquals(SteinerInsertion.NO_VERTEX, vertex)
		assertEquals(4, store.pointCount)
		assertContentEquals(before, store.realTriangles(store.regions))
		store.validate()
	}

	@Test
	fun theWalkFindsTheTriangleItEndsIn() {
		val points = square + doubleArrayOf(3.0, 3.0, 7.0, 4.0, 4.0, 7.0)
		val store = storeOf(points, listOf(ringOf(0, 4)))
		val insertion = SteinerInsertion(store)
		val candidate = store.appendVertex(9.0, 9.25)

		assertEquals(SteinerInsertion.LOCATED_INSIDE, insertion.locate(triangleWith(store, 4, 5, 6), candidate))
		assertStrictlyInside(store, insertion.locatedAt, candidate)
	}

	@Test
	fun aConstraintAcrossTheWalkBlocksIt() {
		val points = square + doubleArrayOf(5.0, 1.0, 5.0, 9.0, 2.0, 5.0)
		val store = storeOf(points, listOf(ringOf(0, 4)), listOf(intArrayOf(4, 5)))
		val insertion = SteinerInsertion(store)
		val candidate = store.appendVertex(8.0, 5.0)
		val start = triangleWith(store, 4, 5, 6)

		assertEquals(SteinerInsertion.LOCATED_BLOCKED, insertion.locate(start, candidate))
		assertEquals(packVertexPair(4, 5), edgeKey(store, insertion.locatedAt))
	}

	@Test
	fun theWalkPassesThroughVerticesOnItsLineAndAlongAConstraint() {
		// The start triangle's centroid lies on y = 5, and so do three vertices; the last two are joined
		// by a constraint, which the walk may run along but not land on.
		val points = square + doubleArrayOf(1.0, 4.0, 3.0, 5.0, 1.0, 6.0, 5.0, 5.0, 7.0, 5.0)
		val store = storeOf(points, listOf(ringOf(0, 4)), listOf(intArrayOf(7, 8)))
		val insertion = SteinerInsertion(store)
		val start = triangleWith(store, 4, 5, 6)

		val beyond = store.appendVertex(9.0, 5.0)
		assertEquals(SteinerInsertion.LOCATED_INSIDE, insertion.locate(start, beyond))
		assertStrictlyInside(store, insertion.locatedAt, beyond)
		store.removeLastVertex()

		val onConstraint = store.appendVertex(6.0, 5.0)
		assertEquals(SteinerInsertion.LOCATED_BLOCKED, insertion.locate(start, onConstraint))
		assertEquals(packVertexPair(7, 8), edgeKey(store, insertion.locatedAt))
		store.removeLastVertex()

		val onVertex = store.appendVertex(5.0, 5.0)
		assertEquals(SteinerInsertion.LOCATED_COINCIDENT, insertion.locate(start, onVertex))
	}

	@Test
	fun aPointOnAnUnconstrainedEdgeIsFoundThere() {
		val points = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 6.0, 5.0)
		val store = storeOf(points, listOf(ringOf(0, 4)))
		val insertion = SteinerInsertion(store)
		val edge = store.findHalfEdge(4, 2)
		assertNotEquals(HalfEdgeTriangulation.NO_HALF_EDGE, edge)
		val candidate = store.appendVertex(8.0, 7.5)

		assertEquals(SteinerInsertion.LOCATED_ON_EDGE, insertion.locate(edge / 3, candidate))
		assertEquals(packVertexPair(2, 4), edgeKey(store, insertion.locatedAt))
	}

	@Test
	fun theCavityNamesTheVerticesAndConstraintsAPointWouldJoin() {
		val store = storeOf(square + doubleArrayOf(5.0, 5.0), listOf(ringOf(0, 4)))
		val insertion = SteinerInsertion(store)
		val candidate = store.appendVertex(5.0, 1.0)
		val located = insertion.locate(triangleWith(store, 0, 1, 4), candidate)
		assertEquals(SteinerInsertion.LOCATED_INSIDE, located)
		insertion.collectCavity(candidate, insertion.locatedAt, -1)

		val vertices = (0 until insertion.cavityVertices.size).map { position -> insertion.cavityVertices[position] }.toSet()
		assertEquals(setOf(0, 1, 4), vertices)
		assertEquals(1, insertion.cavityConstraints.size)
		assertEquals(packVertexPair(0, 1), edgeKey(store, insertion.cavityConstraints[0]))
		assertEquals(16.0, insertion.nearestCavityDistanceSquared(candidate))
	}

	/**
	 * A classified store over the points, with room for a few appended vertices.
	 *
	 * @param DoubleArray    points     The points.
	 * @param List<IntArray> boundaries The boundary rings.
	 * @param List<IntArray> segments   The segment polylines.
	 * @return HalfEdgeTriangulation The store.
	 */
	private fun storeOf(points: DoubleArray, boundaries: List<IntArray>, segments: List<IntArray> = emptyList()): HalfEdgeTriangulation {
		val store = buildConstrained(points, boundaries, segments, points.size / 2 + 4).store!!
		store.adoptRegions(classifyRegions(store).inside)
		store.validate()

		return store
	}

	/**
	 * Asserts that a constraint now runs through a split vertex instead of straight, and that the store
	 * is valid with current regions.
	 *
	 * @param HalfEdgeTriangulation store  The store.
	 * @param Int                   start  The old constraint's start.
	 * @param Int                   end    Its end.
	 * @param Int                   vertex The split vertex.
	 */
	private fun assertSplitConstraint(store: HalfEdgeTriangulation, start: Int, end: Int, vertex: Int) {
		assertNotEquals(SteinerInsertion.NO_VERTEX, vertex)
		store.validate()
		assertRegionsCurrent(store)
		assertTrue(isConstrained(store, start, vertex), "$start - $vertex is a constraint")
		assertTrue(isConstrained(store, vertex, end), "$vertex - $end is a constraint")
		assertFalse(isConstrained(store, start, end), "$start - $end is no longer a constraint")
	}

	/**
	 * Whether the edge between two vertices is a constraint.
	 *
	 * @param HalfEdgeTriangulation store The store.
	 * @param Int                   start One end.
	 * @param Int                   end   The other.
	 * @return Boolean True when it exists and is constrained.
	 */
	private fun isConstrained(store: HalfEdgeTriangulation, start: Int, end: Int): Boolean {
		val halfEdge = store.findHalfEdge(start, end)

		return halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.constrained[halfEdge]
	}

	/**
	 * The area of the inside triangles.
	 *
	 * @param HalfEdgeTriangulation store The store.
	 * @return Double The area.
	 */
	private fun insideArea(store: HalfEdgeTriangulation): Double = areaOf(store.copyPoints(), store.realTriangles(store.regions))

	/**
	 * The slot of the triangle with three given corners.
	 *
	 * @param HalfEdgeTriangulation store  The store.
	 * @param Int                   first  A corner.
	 * @param Int                   second Another.
	 * @param Int                   third  The third.
	 * @return Int The triangle slot.
	 */
	private fun triangleWith(store: HalfEdgeTriangulation, first: Int, second: Int, third: Int): Int {
		for (triangle in 0 until store.triangleCount) {
			val corners = setOf(store.origin[3 * triangle], store.origin[3 * triangle + 1], store.origin[3 * triangle + 2])

			if (corners == setOf(first, second, third)) {
				return triangle
			}
		}

		error("no triangle ($first, $second, $third)")
	}

	/**
	 * A half-edge's edge as a packed (low, high) pair.
	 *
	 * @param HalfEdgeTriangulation store    The store.
	 * @param Int                   halfEdge The half-edge.
	 * @return Long The key.
	 */
	private fun edgeKey(store: HalfEdgeTriangulation, halfEdge: Int): Long {
		val start = store.origin[halfEdge]
		val end = store.destination(halfEdge)

		return packVertexPair(minOf(start, end), maxOf(start, end))
	}

	/**
	 * Asserts that a vertex lies strictly inside a triangle.
	 *
	 * @param HalfEdgeTriangulation store    The store.
	 * @param Int                   triangle The triangle.
	 * @param Int                   vertex   The vertex.
	 */
	private fun assertStrictlyInside(store: HalfEdgeTriangulation, triangle: Int, vertex: Int) {
		for (offset in 0 until 3) {
			val halfEdge = 3 * triangle + offset
			assertTrue(store.orient(store.origin[halfEdge], store.destination(halfEdge), vertex) > 0.0, "vertex $vertex is strictly inside triangle $triangle")
		}
	}
}