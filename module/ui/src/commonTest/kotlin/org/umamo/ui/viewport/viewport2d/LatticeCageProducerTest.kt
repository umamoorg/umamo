package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.SelectionTarget
import org.umamo.edit.setMeshSelectMode
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OVERLAY_FLAG_ACTIVE
import org.umamo.render.puppet.OVERLAY_FLAG_SELECTED
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the 2D viewport's lattice cage: a lattice draws as its grid lines alone, never its cells' diagonals, its cells
 * fill whole and show one face dot at their center, its points sit where the rest lattice is (or where a drag has
 * them), and the value comes back by identity while nothing it shows changed.
 */
class LatticeCageProducerTest {
	private val sizes = MeshOverlaySizes(vertexDotRadiusPx = 3f, edgeWidthPx = 1f, faceDotRadiusPx = 2f)
	private val owner: ElementOwner = ElementOwner.Deformer(LATTICE_RIG_BODY)

	/**
	 * The rig in Edit mode on the body warp's lattice.
	 *
	 * @return EditorSession The session.
	 */
	private fun latticeSession(): EditorSession {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.setMode(EditorMode.Edit)

		return session
	}

	/**
	 * Asserts two position arrays agree within a float rounding, a signed zero equal to an unsigned one.
	 *
	 * @param FloatArray expected The expected positions.
	 * @param FloatArray actual The actual positions.
	 * @param String message What is compared.
	 */
	private fun assertNear(expected: FloatArray, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)

		for (componentIndex in expected.indices) {
			assertEquals(expected[componentIndex], actual[componentIndex], 1e-4f, "$message at $componentIndex")
		}
	}

	@Test
	fun aLatticeDrawsItsGridLinesAndOneCenteredDotPerCell() {
		val session = latticeSession()
		val cage = assertNotNull(LatticeCageProducer().produce(session.mode.value, session.meshSelection.value, session.model.value, sizes, null))
		val mesh = cage.overlay.meshes.single()

		assertEquals(MeshOverlayKind.Edit, cage.overlay.kind)
		assertEquals(owner, mesh.owner)
		assertEquals(4 + 1, mesh.vertexCount, "four points and the cell's center")
		assertEquals(4, mesh.vertexDotCount, "the center draws no vertex dot")
		assertEquals(
			setOf(setOf(0, 1), setOf(2, 3), setOf(0, 2), setOf(1, 3)),
			mesh.edgeEndpoints.toList().chunked(2).map { pair -> pair.toSet() }.toSet(),
			"the four sides, no diagonal",
		)
		assertContentEquals(intArrayOf(4, 4, 4, 4, 4, 4), mesh.faceDotCorners, "both triangles dot at the center")
		assertEquals(6, cage.triangleIndicesById.getValue(owner).size, "the cell fills as two triangles")

		// The body warp spans world x 0..40 and z -40..0, so its center is (20, -20).
		val positions = cage.positionsById.getValue(owner)
		assertNear(floatArrayOf(0f, 0f, 40f, 0f, 0f, -40f, 40f, -40f, 20f, -20f), positions, "the rest lattice and its center")
	}

	@Test
	fun aSelectedCellLightsBothItsTriangles() {
		val session = latticeSession()
		session.setMeshSelectMode(MeshSelectMode.Face)
		session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = mapOf(owner to setOf(MeshElement.Face(0))), activeElement = ActiveMeshElement(owner, MeshElement.Face(0))))
		val mesh = assertNotNull(LatticeCageProducer().produce(session.mode.value, session.meshSelection.value, session.model.value, sizes, null)).overlay.meshes.single()

		assertContentEquals(byteArrayOf(OVERLAY_FLAG_ACTIVE, OVERLAY_FLAG_ACTIVE), mesh.faceFlags, "the active cell, on both of its triangles")
		assertEquals(0, mesh.activeFace, "the active dot is read through the cell's first triangle")
		assertEquals(5, mesh.vertexFlags.size, "a flag for the center too")
		assertEquals(0.toByte(), mesh.vertexFlags[4], "which stays idle")
		assertContentEquals(ByteArray(4) { OVERLAY_FLAG_SELECTED }, mesh.edgeFlags, "the cell's four sides light, as a face's edges do")
	}

	@Test
	fun theCageComesBackByIdentityAndADragMovesOnlyThePositions() {
		val session = latticeSession()
		val producer = LatticeCageProducer()
		val first = assertNotNull(producer.produce(session.mode.value, session.meshSelection.value, session.model.value, sizes, null))

		assertSame(first, producer.produce(session.mode.value, session.meshSelection.value, session.model.value, sizes, null), "nothing changed")

		val dragged = floatArrayOf(10f, 0f, 40f, 0f, 0f, -40f, 40f, -40f)
		val moving = assertNotNull(producer.produce(session.mode.value, session.meshSelection.value, session.model.value, sizes, mapOf(LATTICE_RIG_BODY to dragged)))
		assertSame(first.overlay, moving.overlay, "a drag keeps the overlay")
		assertNear(floatArrayOf(10f, 0f, 40f, 0f, 0f, -40f, 40f, -40f, 22.5f, -20f), moving.positionsById.getValue(owner), "the cage follows the drag, its center with it")

		val settled = assertNotNull(producer.produce(session.mode.value, session.meshSelection.value, session.model.value, sizes, null))
		assertSame(first.positionsById.getValue(owner), settled.positionsById.getValue(owner), "the rest positions come back as they were")
	}

	@Test
	fun noCageOutsideALatticeSession() {
		val producer = LatticeCageProducer()
		val objectMode = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		assertNull(producer.produce(objectMode.mode.value, objectMode.meshSelection.value, objectMode.model.value, sizes, null), "Object mode draws the lattice layer instead")

		val meshSession = latticeRigSession(listOf(SelectionTarget.Drawable(LATTICE_RIG_ART)))
		meshSession.setMode(EditorMode.Edit)
		assertNull(producer.produce(meshSession.mode.value, meshSession.meshSelection.value, meshSession.model.value, sizes, null), "a mesh session's cage rides the art")
	}
}