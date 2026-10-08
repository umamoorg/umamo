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
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.commitMeshPositions
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.ui.viewport.StubPuppetViewportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the binding publishes to the render service as the session changes: the cage follows the mode
 * in and out, a Grab's confirm publishes nothing (it commits positions, which the overlay does not carry),
 * a selection change publishes once, the brush stroke's preview shows before its commit and the commit
 * settles without another publish, a size change republishes the same meshes, and the wireframe follows
 * the areas' demand - over every shown drawable in Object mode, over those outside the edit under the cage
 * in Edit mode, and not at all while no area asks.
 */
class MeshOverlayPublishTest {
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
			backgroundScope.launch { publishMeshOverlay(service, session, sizeFlow, MutableStateFlow(false), StandardTestDispatcher(testScheduler)) }
			runCurrent()
			val before = assertNotNull(service.pushedOverlays.last())

			sizeFlow.value = MeshOverlaySizes(7f, 2f, 5f)
			runCurrent()

			val after = assertNotNull(service.pushedOverlays.last())
			assertEquals(MeshOverlaySizes(7f, 2f, 5f), after.sizes, "a density change republishes at the new sizes")
			assertSame(before.meshes.single(), after.meshes.single(), "over the same mesh entries")
		}

	/** The wireframe is published while some area asks and taken down when none does, over every shown drawable. */
	@Test
	fun theWireframeFollowsTheAreasDemand() =
		runTest {
			val session = gizmoObjectSession()
			val wanted = MutableStateFlow(false)
			val service = publishing(session, wanted)
			assertEquals(listOf<MeshOverlay?>(null), service.pushedOverlays, "Object mode with no area asking publishes no overlay")

			wanted.value = true
			runCurrent()
			val wireframe = assertNotNull(service.pushedOverlays.last(), "an area asking publishes the wireframe")
			assertEquals(MeshOverlayKind.ObjectWireframe, wireframe.kind)
			assertEquals(listOf(RIG_QUAD, RIG_OTHER, RIG_HIDDEN), wireframe.meshes.map { mesh -> mesh.drawableId }, "over every shown drawable, in the model's order")
			assertTrue(wireframe.meshes.all { mesh -> mesh.wireframeOnly && mesh.vertexFlags.isEmpty() }, "each a plain wireframe")

			wanted.value = false
			runCurrent()
			assertEquals(3, service.pushedOverlays.size, "the last area no longer asking publishes once more")
			assertNull(service.pushedOverlays.last(), "and takes the wireframe down")
		}

	/** In Edit mode the wireframe covers the shown drawables outside the edit, ahead of the cage, and leaves with the demand. */
	@Test
	fun inEditModeTheWireframeCoversWhatIsOutsideTheEditUnderTheCage() =
		runTest {
			val session = gizmoObjectSession()
			val wanted = MutableStateFlow(true)
			val service = publishing(session, wanted)
			assertEquals(MeshOverlayKind.ObjectWireframe, assertNotNull(service.pushedOverlays.last()).kind)

			session.setMode(EditorMode.Edit)
			runCurrent()
			val edit = assertNotNull(service.pushedOverlays.last())
			assertEquals(MeshOverlayKind.Edit, edit.kind, "entering Edit publishes the cage")
			assertEquals(listOf(RIG_OTHER, RIG_HIDDEN, RIG_QUAD), edit.meshes.map { mesh -> mesh.drawableId }, "with the wireframe of what is outside the edit ahead of it")
			assertEquals(listOf(true, true, false), edit.meshes.map { mesh -> mesh.wireframeOnly }, "the cage alone carries flags")

			wanted.value = false
			runCurrent()
			assertEquals(listOf(RIG_QUAD), assertNotNull(service.pushedOverlays.last()).meshes.map { mesh -> mesh.drawableId }, "no area asking leaves the cage alone")

			wanted.value = true
			session.setMode(EditorMode.Object)
			runCurrent()
			assertEquals(MeshOverlayKind.ObjectWireframe, assertNotNull(service.pushedOverlays.last()).kind, "leaving Edit brings the wireframe back")
		}

	/** An object selection is no part of the wireframe, so changing it publishes nothing. */
	@Test
	fun anObjectSelectionChangePublishesNoNewWireframe() =
		runTest {
			val session = gizmoObjectSession()
			val service = publishing(session, MutableStateFlow(true))
			val published = service.pushedOverlays.size
			val wireframe = assertNotNull(service.pushedOverlays.last())

			session.setSelection(Selection(setOf(SelectionTarget.Drawable(RIG_OTHER)), SelectionTarget.Drawable(RIG_OTHER)))
			runCurrent()

			assertEquals(published, service.pushedOverlays.size, "the selection is not part of the wireframe")
			assertSame(wireframe, service.pushedOverlays.last())
		}

	/** A drawable under a hidden part is not shown, so the wireframe leaves it out, as the renderer does the art. */
	@Test
	fun aDrawableUnderAHiddenPartIsLeftOutOfTheWireframe() =
		runTest {
			val folder = PartId("folder")
			val rig = gizmoRigModel()
			val model =
				rig.copy(
					parts = listOf(Part(id = folder, name = "folder", children = listOf(OrgChild.Drawable(RIG_OTHER)), isVisible = false)),
					rootChildren = listOf(OrgChild.Drawable(RIG_QUAD), OrgChild.Part(folder), OrgChild.Drawable(RIG_HIDDEN)),
				)
			val service = publishing(EditorSession(model), MutableStateFlow(true))

			assertEquals(listOf(RIG_QUAD, RIG_HIDDEN), assertNotNull(service.pushedOverlays.last()).meshes.map { mesh -> mesh.drawableId })
		}

	/**
	 * Starts the publish over [session] into a fresh stub, on the test's scheduler, and lets it settle.
	 *
	 * @param EditorSession session The session.
	 * @param MutableStateFlow<Boolean> wanted Whether any area asks for the wireframe; none by default.
	 * @return StubPuppetViewportService The stub recording the publishes.
	 */
	private fun TestScope.publishing(session: EditorSession, wanted: MutableStateFlow<Boolean> = MutableStateFlow(false)): StubPuppetViewportService {
		val service = StubPuppetViewportService()
		backgroundScope.launch { publishMeshOverlay(service, session, flowOf(sizes), wanted, StandardTestDispatcher(testScheduler)) }
		runCurrent()
		return service
	}
}