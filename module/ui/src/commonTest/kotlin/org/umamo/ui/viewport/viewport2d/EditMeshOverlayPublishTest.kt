package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshRestPositions
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.commitMeshPositions
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.ui.viewport.StubPuppetViewportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins what the binding publishes to the render service as the session changes: the overlay follows
 * the mode in and out, a Grab's confirm publishes nothing (it commits positions, which the overlay does
 * not carry), a selection change publishes once, the brush stroke's preview shows before its commit and
 * the commit settles without another publish, and a size change republishes the same meshes.
 */
class EditMeshOverlayPublishTest {
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)

	@Test
	fun theOverlayFollowsTheModeInAndOut() =
		runTest {
			val session = gizmoObjectSession()
			val service = publishing(session)
			assertEquals(listOf<MeshOverlay?>(null), service.pushedOverlays, "Object mode publishes no overlay")

			session.setMode(EditorMode.Edit)
			runCurrent()
			assertEquals(2, service.pushedOverlays.size, "entering Edit publishes the overlay")
			assertEquals(listOf(RIG_QUAD), assertNotNull(service.pushedOverlays.last()).meshes.map { mesh -> mesh.drawableId })

			session.setMode(EditorMode.Object)
			runCurrent()
			assertEquals(3, service.pushedOverlays.size, "leaving Edit publishes once more")
			assertNull(service.pushedOverlays.last(), "and takes the overlay down")
		}

	@Test
	fun aGrabConfirmPublishesNothing() =
		runTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			val service = publishing(session)
			val published = service.pushedOverlays.size
			val modelBefore = session.model.value

			session.commitMeshPositions(
				MeshChange.TransformVertices(mapOf(RIG_QUAD to listOf(0)), MeshOperatorKind.Grab),
				mapOf(RIG_QUAD to MeshRestPositions.shared(floatArrayOf(5f, 5f, 20f, 0f, 20f, 20f, 0f, 20f))),
			)
			runCurrent()

			assertNotSame(modelBefore, session.model.value, "the confirm committed a new model")
			assertEquals(published, service.pushedOverlays.size, "and published no overlay: it holds no positions")
		}

	@Test
	fun aSelectionChangePublishesOnce() =
		runTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			val service = publishing(session)
			val before = service.pushedOverlays.last()

			session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, RIG_QUAD, MeshElement.Vertex(1)))
			runCurrent()

			assertEquals(2, service.pushedOverlays.size, "one publish at start, one for the change")
			val after = assertNotNull(service.pushedOverlays.last())
			assertNotSame(before, after)
			assertEquals(1, after.meshes.single().vertexFlags[0].toInt(), "vertex 0 stays selected")
			assertEquals(2, after.meshes.single().vertexFlags[1].toInt(), "vertex 1 joins, active")
		}

	@Test
	fun theBrushPreviewPublishesAndItsCommitSettles() =
		runTest {
			val session = gizmoEditSession()
			val service = publishing(session)
			val stroke = MeshSelectionOps.add(session.meshSelection.value, RIG_QUAD, MeshElement.Vertex(2))

			session.setMeshPreviewSelection(stroke)
			runCurrent()
			assertEquals(2, service.pushedOverlays.size, "the live stroke publishes")
			assertEquals(2, assertNotNull(service.pushedOverlays.last()).meshes.single().vertexFlags[2].toInt(), "showing what it painted")
			assertEquals(0, session.meshSelection.value.size, "while nothing is committed yet")

			// The brush commits, then clears its preview: the committed selection is the stroke itself, so the
			// overlay it shows does not change.
			session.setMeshSelection(stroke)
			session.setMeshPreviewSelection(null)
			runCurrent()
			assertEquals(2, service.pushedOverlays.size, "the commit settles without another publish")
		}

	@Test
	fun aSizeChangeRepublishesTheSameMeshes() =
		runTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			val sizeFlow = MutableStateFlow(sizes)
			val service = StubPuppetViewportService()
			backgroundScope.launch { publishEditMeshOverlay(service, session, sizeFlow, StandardTestDispatcher(testScheduler)) }
			runCurrent()
			val before = assertNotNull(service.pushedOverlays.last())

			sizeFlow.value = MeshOverlaySizes(7f, 2f, 5f)
			runCurrent()

			val after = assertNotNull(service.pushedOverlays.last())
			assertEquals(MeshOverlaySizes(7f, 2f, 5f), after.sizes, "a density change republishes at the new sizes")
			assertSame(before.meshes.single(), after.meshes.single(), "over the same mesh entries")
		}

	/**
	 * Starts the publish over [session] into a fresh stub, on the test's scheduler, and lets it settle.
	 *
	 * @param EditorSession session The session.
	 * @return StubPuppetViewportService The stub recording the publishes.
	 */
	private fun TestScope.publishing(session: EditorSession): StubPuppetViewportService {
		val service = StubPuppetViewportService()
		backgroundScope.launch { publishEditMeshOverlay(service, session, flowOf(sizes), StandardTestDispatcher(testScheduler)) }
		runCurrent()
		return service
	}
}