package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.rename
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.ui.viewport.StubPuppetViewportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the lattice cage's publish to the render service: entering Edit mode on a warp publishes its cage, a drag's
 * moved points publish a cage over the same overlay with new positions, an edit that leaves the lattice where it
 * was publishes nothing, and leaving Edit mode takes the cage down.
 */
class LatticeCagePublishTest {
	private val sizes = MeshOverlaySizes(3f, 1f, 2f)
	private val owner: ElementOwner = ElementOwner.Deformer(LATTICE_RIG_BODY)

	@Test
	fun theCageFollowsTheSessionAndTheDrag() =
		runTest {
			val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
			val live = MutableStateFlow<Map<DeformerId, FloatArray>?>(null)
			val service = StubPuppetViewportService()
			backgroundScope.launch { publishLatticeCage(service, session, MutableStateFlow(sizes), live.map { world -> LatticeDragPreview(world, null) }, MutableStateFlow(emptyMap())) }
			runCurrent()
			assertEquals(listOf<DirectMeshOverlay?>(null), service.pushedEditDirectOverlays, "Object mode publishes no cage")

			session.setMode(EditorMode.Edit)
			runCurrent()
			val rest = assertNotNull(service.pushedEditDirectOverlays.last(), "Tab on the warp publishes its cage")
			assertEquals(listOf(owner), rest.overlay.meshes.map { mesh -> mesh.owner })

			live.value = mapOf(LATTICE_RIG_BODY to floatArrayOf(5f, 0f, 40f, 0f, 0f, -40f, 40f, -40f))
			runCurrent()
			val dragged = assertNotNull(service.pushedEditDirectOverlays.last())
			assertSame(rest.overlay, dragged.overlay, "a drag frame keeps the overlay")
			assertEquals(5f, dragged.positionsById.getValue(owner)[0], "and moves the cage")

			live.value = null
			runCurrent()
			val pushesBefore = service.pushedEditDirectOverlays.size
			session.rename(SelectionTarget.Drawable(LATTICE_RIG_ART), "renamed art")
			runCurrent()
			assertEquals(pushesBefore, service.pushedEditDirectOverlays.size, "an edit that leaves the lattice where it was publishes nothing")

			session.setMode(EditorMode.Object)
			runCurrent()
			assertNull(service.pushedEditDirectOverlays.last(), "leaving Edit mode takes the cage down")
		}
}