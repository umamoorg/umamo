package org.umamo.render.pick

import org.umamo.render.ViewportCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the pure screen-space geometry the gizmo overlays (and the future UV editor) pick with:
 * the world-to-screen projection and its inverse, nearest-within-radius vertex and edge hit tests,
 * the centroid tie-break for overlapping faces, and the box / circle enclosure rules (vertex inside,
 * edge both-endpoints / segment-overlap, face by centroid).
 */
class ScreenSpacePickTest {
	private val viewportWidth = 400
	private val viewportHeight = 300

	// A centered 1:1 camera: world (0, 0) projects to screen (200, 150) and one world unit is one pixel.
	private val camera = ViewportCamera(0f, 0f, 1f)

	// A unit-quad-scaled mesh: v0 bottom-left origin, v1 right, v2 up, v3 diagonal (world y is up).
	// Screens at the fixed camera: v0 (200, 150), v1 (210, 150), v2 (200, 140), v3 (210, 140).
	private val positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)
	private val triangleIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// The quad's five unique edges, in a fixed ordinal order the assertions reference.
	private val edgeEndpoints = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)

	/** The projection round-trips exactly and flips Y (world up = screen up = smaller y). */
	@Test
	fun projectionRoundTripsAndFlipsY() {
		val zoomedCamera = ViewportCamera(centerX = 5f, centerY = -3f, zoom = 2f)
		val worldX = 12.5f
		val worldY = -7.25f
		val screenX = worldToScreenX(worldX, zoomedCamera, viewportWidth)
		val screenY = worldToScreenY(worldY, zoomedCamera, viewportHeight)
		assertEquals(worldX, screenToWorldX(screenX, zoomedCamera, viewportWidth), 1e-4f, "x round-trips")
		assertEquals(worldY, screenToWorldY(screenY, zoomedCamera, viewportHeight), 1e-4f, "y round-trips")

		val higherY = worldToScreenY(worldY + 1f, zoomedCamera, viewportHeight)
		assertTrue(higherY < screenY, "a higher world point draws at a smaller screen y")
	}

	/** hitTestVertex picks the nearest vertex within the radius and misses outside it. */
	@Test
	fun hitTestVertexPicksNearestWithinRadius() {
		// Screen (208, 150): 8 px from v1's projection, 8+ px from v0's - v1 is nearer.
		assertEquals(1, hitTestVertex(positions, 208f, 150f, 10f, camera, viewportWidth, viewportHeight))
		// Nothing within a 3 px radius at that point.
		assertNull(hitTestVertex(positions, 205f, 150f, 2f, camera, viewportWidth, viewportHeight))
	}

	/** distanceToSegment projects onto the segment, clamps at the endpoints, and survives degeneracy. */
	@Test
	fun distanceToSegmentClampsToEndpoints() {
		// Perpendicular drop onto the middle of a horizontal segment.
		assertEquals(4f, distanceToSegment(5f, 4f, 0f, 0f, 10f, 0f), 1e-4f)
		// Beyond the end: the distance is to the endpoint, not the infinite line.
		assertEquals(5f, distanceToSegment(13f, 4f, 0f, 0f, 10f, 0f), 1e-4f)
		// A zero-length segment measures as the distance to its single point.
		assertEquals(5f, distanceToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)
	}

	/** hitTestEdge returns the ordinal of the nearest segment within the radius. */
	@Test
	fun hitTestEdgePicksNearestSegment() {
		// Screen (205, 152): 2 px below the v0-v1 segment (ordinal 0), farther from every other edge.
		assertEquals(0, hitTestEdge(positions, edgeEndpoints, 205f, 152f, 10f, camera, viewportWidth, viewportHeight))
		// Screen (210, 145): on the vertical v1-v3 segment (ordinal 3).
		assertEquals(3, hitTestEdge(positions, edgeEndpoints, 210f, 145f, 10f, camera, viewportWidth, viewportHeight))
		// Far from everything within a tight radius.
		assertNull(hitTestEdge(positions, edgeEndpoints, 250f, 200f, 5f, camera, viewportWidth, viewportHeight))
		// An edge referencing a missing vertex is skipped rather than crashing.
		assertNull(hitTestEdge(positions, intArrayOf(0, 9), 205f, 150f, 10f, camera, viewportWidth, viewportHeight))
	}

	/** hitTestFace picks a containing triangle, breaking overlaps by the nearest centroid. */
	@Test
	fun hitTestFaceUsesCentroidTieBreak() {
		// Screen (202, 148) = world (2, 2): inside triangle 0 only.
		assertEquals(0, hitTestFace(positions, triangleIndices, 202f, 148f, camera, viewportWidth, viewportHeight))
		// Screen (208, 142) = world (8, 8): inside triangle 1 only.
		assertEquals(1, hitTestFace(positions, triangleIndices, 208f, 142f, camera, viewportWidth, viewportHeight))
		// Outside the quad entirely.
		assertNull(hitTestFace(positions, triangleIndices, 250f, 250f, camera, viewportWidth, viewportHeight))

		// Two coincident triangles both contain the pointer: the one with the nearer centroid wins.
		// Triangle 0 spans the quad's lower-left half; triangle 1 (here duplicated smaller) hugs v0.
		val overlappingPositions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 4f, 0f, 0f, 4f)
		val overlappingTriangles = intArrayOf(0, 1, 2, 0, 3, 4)
		// World (1, 1) is inside both; the small triangle's centroid is much closer.
		assertEquals(1, hitTestFace(overlappingPositions, overlappingTriangles, 201f, 149f, camera, viewportWidth, viewportHeight))
	}

	/** verticesInBox encloses by screen position, corner order agnostic. */
	@Test
	fun verticesInBoxEnclosesByScreenPosition() {
		// A box around v0 and v2's column (screen x 195..205, y 135..155).
		assertEquals(setOf(0, 2), verticesInBox(positions, 205f, 135f, 195f, 155f, camera, viewportWidth, viewportHeight))
		// The whole quad.
		assertEquals(setOf(0, 1, 2, 3), verticesInBox(positions, 195f, 135f, 215f, 155f, camera, viewportWidth, viewportHeight))
		// An empty region.
		assertEquals(emptySet(), verticesInBox(positions, 0f, 0f, 50f, 50f, camera, viewportWidth, viewportHeight))
	}

	/** edgesInBox requires both endpoints inside (Blender's rule); facesInBox uses the centroid. */
	@Test
	fun boxRulesForEdgesAndFaces() {
		// The v0-v2 column box encloses edge ordinal 2 (0-2) only - every other edge has an endpoint outside.
		assertEquals(setOf(2), edgesInBox(positions, edgeEndpoints, 205f, 135f, 195f, 155f, camera, viewportWidth, viewportHeight))
		// Triangle 0's centroid is world (10/3, 10/3) = screen (~203.3, ~146.7); a tight box around it
		// selects face 0 only even though its corners poke out.
		assertEquals(setOf(0), facesInBox(positions, triangleIndices, 202f, 145f, 205f, 148f, camera, viewportWidth, viewportHeight))
	}

	/** The circle brush encloses vertices by distance, edges by segment overlap, faces by centroid. */
	@Test
	fun circleRulesPerDomain() {
		// A 6 px brush at v1's projection: only v1 within reach.
		assertEquals(setOf(1), verticesInCircle(positions, 210f, 150f, 6f, camera, viewportWidth, viewportHeight))
		// The same brush touches every edge incident to v1 (ordinals 0: 0-1, 1: 1-2, 3: 1-3).
		assertEquals(setOf(0, 1, 3), edgesInCircle(positions, edgeEndpoints, 210f, 150f, 6f, camera, viewportWidth, viewportHeight))
		// A 3 px brush at triangle 0's centroid paints face 0 only.
		assertEquals(setOf(0), facesInCircle(positions, triangleIndices, 203.3f, 146.7f, 3f, camera, viewportWidth, viewportHeight))
		// A brush over empty canvas paints nothing.
		assertEquals(emptySet(), verticesInCircle(positions, 50f, 50f, 10f, camera, viewportWidth, viewportHeight))
	}
}
