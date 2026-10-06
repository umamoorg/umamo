package org.umamo.ui.viewport.uv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.render.ContentBounds
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.viewport.UvSceneContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins what a UV area publishes to the render service as its session and scene change: the surface and its
 * extent, with the Edit wireframe laid on it while the session edits and none in Object mode; one publish
 * per change of what is shown, none for inputs that show the same; the area's circle stroke shown before it
 * commits, its commit settling without another publish; and a density change republishing the same meshes.
 */
class UvScenePublishTest {
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)
	private val extent = ContentBounds(100f, 100f, 60f, 20f)

	@Test
	fun objectModePublishesTheSurfaceWithNoOverlay() =
		runTest {
			val publish = publishing(uvObjectSession())

			val pushed = publish.service.pushedUvContents.single()
			assertEquals("uv", pushed.areaId)
			assertEquals(UvSceneContent.AtlasPage(0), pushed.content, "the page, carrying no overlay")
			assertEquals(extent, pushed.islandExtent, "with the extent beside it")
		}

	@Test
	fun editPairsTheOverlayWithTheContent() =
		runTest {
			val session = uvEditSession()
			val publish = publishing(session)

			val pushed = publish.service.pushedUvContents.single()
			val content = pushed.content as UvSceneContent.AtlasPage
			assertEquals(0, content.pageIndex)
			val overlay = assertNotNull(content.overlay, "the wireframe rides the page")
			assertEquals(listOf(UV_RIG_QUAD), overlay.overlay.meshes.map { mesh -> mesh.drawableId })
			assertSame(publish.scene.value.geometries.first().positions, overlay.positionsById[UV_RIG_QUAD], "over the shown positions")

			session.setMode(EditorMode.Object)
			runCurrent()
			assertEquals(2, publish.service.pushedUvContents.size, "leaving Edit publishes once more")
			assertNull(publish.service.pushedUvContents.last().content.overlay, "and takes the wireframe down")
		}

	@Test
	fun aPageSwitchPublishesOnce() =
		runTest {
			val publish = publishing(uvEditSession())
			val overlay = publish.service.pushedUvContents.single().content.overlay

			val shown = publish.scene.value
			publish.scene.value = UvShownScene(UvSceneContent.AtlasPage(1), shown.islandExtent, shown.model, shown.geometries)
			runCurrent()

			assertEquals(2, publish.service.pushedUvContents.size)
			val switched = publish.service.pushedUvContents.last().content as UvSceneContent.AtlasPage
			assertEquals(1, switched.pageIndex, "the new page")
			assertSame(overlay, switched.overlay, "with the same wireframe, nothing of it having changed")
		}

	@Test
	fun aStrokePublishesOnceAndItsCommitSettles() =
		runTest {
			val session = uvEditSession()
			val publish = publishing(session)
			val stroke = MeshSelectionOps.add(session.meshSelection.value, UV_RIG_QUAD, MeshElement.Vertex(2))

			publish.stroke.value = stroke
			runCurrent()
			assertEquals(2, publish.service.pushedUvContents.size, "the live stroke publishes")
			val painted = assertNotNull(publish.service.pushedUvContents.last().content.overlay)
			assertEquals(2, painted.overlay.meshes.single().vertexFlags[2].toInt(), "showing what it painted")
			assertNull(session.meshPreviewSelection.value, "through the area alone, not the session's preview")

			// The marquee commits the stroke, then clears it: the committed selection is the stroke itself, so
			// the wireframe it shows does not change.
			session.setMeshSelection(stroke)
			publish.stroke.value = null
			runCurrent()
			assertEquals(2, publish.service.pushedUvContents.size, "the commit settles without another publish")
		}

	@Test
	fun identicalInputsPublishNothing() =
		runTest {
			val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
			val publish = publishing(session)

			val shown = publish.scene.value
			publish.scene.value = UvShownScene(shown.content, shown.islandExtent, shown.model, shown.geometries)
			session.setMeshSelection(session.meshSelection.value)
			publish.sizes.value = sizes.copy()
			runCurrent()

			assertEquals(1, publish.service.pushedUvContents.size, "a new scene value, the same selection, and equal sizes show the same")
		}

	@Test
	fun aDensityChangeRepublishes() =
		runTest {
			val publish = publishing(uvEditSession())
			val before = assertNotNull(publish.service.pushedUvContents.single().content.overlay)

			publish.sizes.value = MeshOverlaySizes(7f, 2f, 5f)
			runCurrent()

			val after = assertNotNull(publish.service.pushedUvContents.last().content.overlay)
			assertEquals(2, publish.service.pushedUvContents.size)
			assertEquals(MeshOverlaySizes(7f, 2f, 5f), after.overlay.sizes, "at the new sizes")
			assertSame(before.positionsById[UV_RIG_QUAD], after.positionsById[UV_RIG_QUAD], "over the same positions")
		}

	/**
	 * The publish's inputs a case drives, and the stub recording what it published.
	 *
	 * @property StubPuppetViewportService service The stub.
	 * @property MutableStateFlow<UvShownScene> scene What the area shows.
	 * @property MutableStateFlow<MeshSelection?> stroke The area's circle stroke.
	 * @property MutableStateFlow<MeshOverlaySizes> sizes The overlay sizes.
	 */
	private class Publishing(
		val service: StubPuppetViewportService,
		val scene: MutableStateFlow<UvShownScene>,
		val stroke: MutableStateFlow<MeshSelection?>,
		val sizes: MutableStateFlow<MeshOverlaySizes>,
	)

	/**
	 * Starts the publish for area "uv" over [session], showing the rig's page with every session mesh on it,
	 * on the test's scheduler, and lets it settle.
	 *
	 * @param EditorSession session The session.
	 * @return Publishing The inputs and the stub.
	 */
	private fun TestScope.publishing(session: EditorSession): Publishing {
		val model = session.model.value
		val shown = if (session.mode.value == EditorMode.Edit) session.meshSelection.value.drawableIds else listOf(UV_RIG_QUAD, UV_RIG_OTHER)
		val publishing =
			Publishing(
				StubPuppetViewportService(),
				MutableStateFlow(UvShownScene(UvSceneContent.AtlasPage(0), extent, model, uvRigGeometries(model, uvRigPageFrame(), shown))),
				MutableStateFlow(null),
				MutableStateFlow(sizes),
			)
		backgroundScope.launch {
			publishUvScene(publishing.service, "uv", session, publishing.scene, publishing.stroke, publishing.sizes, StandardTestDispatcher(testScheduler))
		}
		runCurrent()
		return publishing
	}
}