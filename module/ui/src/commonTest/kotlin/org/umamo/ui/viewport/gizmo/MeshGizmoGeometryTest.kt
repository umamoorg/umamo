package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared gizmo element queries over synthetic GizmoMeshGeometry - the geometry-source-agnostic
 * record the Edit overlay (deformer-projected world shapes) and the UV editor (raw texture
 * coordinates) both feed.  Camera at origin with zoom 1 over a 200x200 area, so world (x, y) lands at
 * screen (x + 100, 100 - y).
 */
class MeshGizmoGeometryTest {
	private val camera = ViewportCamera(centerX = 0f, centerY = 0f, zoom = 1f)
	private val size = IntSize(200, 200)

	// A two-triangle quad: v0 (0,0), v1 (20,0), v2 (0,20), v3 (20,20).
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadPositions = floatArrayOf(0f, 0f, 20f, 0f, 0f, 20f, 20f, 20f)

	/**
	 * Builds the standard test quad as one gizmo geometry.
	 *
	 * @param String id The drawable id.
	 * @param FloatArray positions The interleaved positions; defaults to the standard quad.
	 * @return GizmoMeshGeometry The geometry under test.
	 */
	private fun quad(id: String, positions: FloatArray = quadPositions): GizmoMeshGeometry =
		GizmoMeshGeometry(ElementOwner.Drawable(DrawableId(id)), ElementTopology.ofTriangles(positions.size / 2, quadIndices), positions)

	@Test
	fun hitTestPicksTheVertexUnderThePointer() {
		// v1 (20, 0) is at screen (120, 100); a pointer 1px off still hits within HIT_RADIUS_PX.
		val hit = hitTestMeshes(MeshSelectMode.Vertex, listOf(quad("a")), Offset(121f, 101f), camera, size)
		assertNotNull(hit)
		assertEquals(DrawableId("a"), hit.drawableId)
		assertEquals(MeshElement.Vertex(1), hit.element)
	}

	@Test
	fun hitTestMissesFarFromEveryVertex() {
		assertNull(hitTestMeshes(MeshSelectMode.Vertex, listOf(quad("a")), Offset(160f, 160f), camera, size))
	}

	@Test
	fun hitTestResolvesOverlappingMeshesToTheNearestElement() {
		// Mesh b is the same quad shifted +2 world x, so its v0 sits at screen (102, 100).  A pointer at
		// (103, 100) is within radius of both meshes' v0; the visually closest (mesh b) must win.
		val shifted = FloatArray(quadPositions.size) { index -> if (index % 2 == 0) quadPositions[index] + 2f else quadPositions[index] }
		val hit = hitTestMeshes(MeshSelectMode.Vertex, listOf(quad("a"), quad("b", shifted)), Offset(103f, 100f), camera, size)
		assertNotNull(hit)
		assertEquals(DrawableId("b"), hit.drawableId)
		assertEquals(MeshElement.Vertex(0), hit.element)
	}

	@Test
	fun boxEnclosesVerticesByScreenPosition() {
		// A screen box over the quad's bottom edge (v0, v1 at screen y=100; v2, v3 sit higher at y=80).
		val inside = elementsInBox(MeshSelectMode.Vertex, quad("a"), Offset(95f, 95f), Offset(125f, 105f), camera, size)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), inside)
	}

	@Test
	fun boxEnclosesOnlyEdgesWithBothEndpointsInside() {
		val inside = elementsInBox(MeshSelectMode.Edge, quad("a"), Offset(95f, 95f), Offset(125f, 105f), camera, size)
		assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), inside)
	}

	@Test
	fun circleStampAddsThenErasesElements() {
		val geometry = quad("a")
		val seed = MeshSelection.editingDrawables(listOf(DrawableId("a")))
		// v3 (20, 20) is at screen (120, 80): a small brush there selects only it.
		val painted = circleSelection(seed, erasing = false, Offset(120f, 80f), 5f, listOf(geometry), camera, size)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(3)), painted.elementsOf(DrawableId("a")))
		// The erase stamp over the same spot removes it again.
		val erased = circleSelection(painted, erasing = true, Offset(120f, 80f), 5f, listOf(geometry), camera, size)
		assertTrue(erased.elementsOf(DrawableId("a")).isEmpty())
	}

	@Test
	fun highlightSetsDeriveUpFromSelectedVertices() {
		// All three vertices of the first triangle selected: its edges and the face light up derived.
		val highlight =
			buildHighlightSets(
				elements = setOf(MeshElement.Vertex(0), MeshElement.Vertex(1), MeshElement.Vertex(2)),
				active = MeshElement.Vertex(2),
				selectMode = MeshSelectMode.Vertex,
				topology = ElementTopology.ofTriangles(4, quadIndices),
			)
		assertEquals(setOf(0, 1, 2), highlight.selectedVertexIndices)
		assertEquals(2, highlight.activeVertexIndex)
		assertTrue(MeshElement.Edge(0, 1) in highlight.selectedEdges)
		assertEquals(setOf(0), highlight.selectedFaceIndices)
	}

	/** The active vertex's median is the vertex; an active edge's is its midpoint. */
	@Test
	fun theActiveElementMedianIsWhatTheElementCovers() {
		val geometries = listOf(quad("a"))
		val vertexActive = MeshSelection(listOf(ElementOwner.Drawable(DrawableId("a"))), ElementOwner.Drawable(DrawableId("a")), activeElement = ActiveMeshElement(ElementOwner.Drawable(DrawableId("a")), MeshElement.Vertex(3)))
		assertEquals(20f to 20f, activeElementMedian(vertexActive, geometries))
		val edgeActive = vertexActive.copy(selectMode = MeshSelectMode.Edge, activeElement = ActiveMeshElement(ElementOwner.Drawable(DrawableId("a")), MeshElement.Edge(0, 1)))
		assertEquals(10f to 0f, activeElementMedian(edgeActive, geometries))
	}

	/** Nothing active, or an active element on a mesh that is not shown, has no median. */
	@Test
	fun noShownActiveElementHasNoMedian() {
		val geometries = listOf(quad("a"))
		assertNull(activeElementMedian(MeshSelection(listOf(ElementOwner.Drawable(DrawableId("a"))), ElementOwner.Drawable(DrawableId("a"))), geometries))
		val elsewhere = MeshSelection(listOf(ElementOwner.Drawable(DrawableId("b"))), ElementOwner.Drawable(DrawableId("b")), activeElement = ActiveMeshElement(ElementOwner.Drawable(DrawableId("b")), MeshElement.Vertex(0)))
		assertNull(activeElementMedian(elsewhere, geometries))
	}
}