package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.ContentBounds
import org.umamo.ui.viewport.StubPuppetViewportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins Frame Selected over warps: a selected warp frames its lattice as the pose draws it, and a selection of
 * warps and art frames the box holding both.
 */
class LatticeFrameSelectedTest {
	@Test
	fun aSelectedWarpFramesItsLattice() {
		val service = StubPuppetViewportService()
		service.latticeExtents[LATTICE_RIG_HEAD] = ContentBounds(10f, -30f, 20f, 20f)
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD)))

		ViewportSpaceCamera(service, session, "area").frameSelected()

		assertEquals(listOf("area" to listOf(10f, -30f, 30f, -10f)), service.fitWorldRequests)
	}

	@Test
	fun warpsAndArtFrameTogether() {
		val service = StubPuppetViewportService()
		service.latticeExtents[LATTICE_RIG_HEAD] = ContentBounds(-50f, -30f, 20f, 20f)
		val session = latticeRigSession()
		session.setSelection(Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD), SelectionTarget.Drawable(LATTICE_RIG_ART)), SelectionTarget.Drawable(LATTICE_RIG_ART)))

		ViewportSpaceCamera(service, session, "area").frameSelected()

		val (minX, minZ, maxX, maxZ) = service.fitWorldRequests.single().second
		assertEquals(-50f, minX, "the lattice's left edge")
		assertEquals(-30f, minZ, "the lattice's bottom edge")
		assertEquals(20f, maxX, "the art's right edge: its quad spans world x 0..20")
		assertEquals(0f, maxZ, 0f, "the art's top edge")
	}

	@Test
	fun aWarpTheViewportDoesNotDrawFramesNothing() {
		val service = StubPuppetViewportService()
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_GUIDE)))

		ViewportSpaceCamera(service, session, "area").frameSelected()

		assertTrue(service.fitWorldRequests.isEmpty())
	}
}