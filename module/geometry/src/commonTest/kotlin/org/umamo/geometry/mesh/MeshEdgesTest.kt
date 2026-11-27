package org.umamo.geometry.mesh

import org.umamo.geometry.packVertexPair
import org.umamo.geometry.pairFirst
import org.umamo.geometry.pairSecond
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared edge walk: every edge once, boundary edges told from interior ones, and the pair key it
 * hashes on unpacking to what went in.
 */
class MeshEdgesTest {
	@Test
	fun twoTrianglesSharingAnEdgeVisitFiveEdgesOnce() {
		// A square split along its diagonal, both triangles counter-clockwise.
		val mesh = PlanarTriangleMesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3), IntArray(0))
		val visited = ArrayList<Triple<Int, Int, Boolean>>()

		mesh.forEachEdge { start, end, isBoundary -> visited.add(Triple(start, end, isBoundary)) }

		assertEquals(5, visited.size, "four boundary edges and the one diagonal")
		assertEquals(4, visited.count { (_, _, isBoundary) -> isBoundary })
		assertEquals(listOf(Triple(0, 2, false)), visited.filter { (_, _, isBoundary) -> !isBoundary }, "the diagonal once, from its lower vertex")
		assertEquals(setOf(0 to 1, 1 to 2, 2 to 3, 3 to 0), visited.filter { (_, _, isBoundary) -> isBoundary }.map { (start, end, _) -> start to end }.toSet(), "boundary edges keep their winding")
	}

	@Test
	fun aLoneTriangleIsAllBoundary() {
		val mesh = PlanarTriangleMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2), IntArray(0))
		var boundaryCount = 0

		mesh.forEachEdge { _, _, isBoundary ->
			if (isBoundary) {
				boundaryCount++
			}
		}

		assertEquals(3, boundaryCount)
	}

	@Test
	fun aPackedPairUnpacksToItsVertices() {
		val key = packVertexPair(7, 123456789)
		assertEquals(7, pairFirst(key))
		assertEquals(123456789, pairSecond(key))
		assertEquals(packVertexPair(0, 1), packVertexPair(0, 1))
		assertEquals(false, packVertexPair(0, 1) == packVertexPair(1, 0), "direction is part of the key")
	}
}