package org.umamo.ui.viewport.uv

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.UvSceneContent
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the UV Edit wireframe's derive over the rig's page: it lists the session meshes the scene shows,
 * each paired with exactly the display positions and triangle indices the shown geometry holds, with the
 * selection's flags on each mesh; and it keeps the value, and every array a mesh did not change, by
 * identity, which is what spares the renderer its uploads.
 */
class UvEditOverlayProducerTest {
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)

	@Test
	fun outsideEditModeThereIsNone() {
		val model = uvRigModel()
		val selection = MeshSelection.editing(listOf(UV_RIG_QUAD))
		assertNull(UvEditOverlayProducer().produce(EditorMode.Object, selection, sceneOf(model, uvRigGeometries(model, uvRigPageFrame())), sizes))
	}

	@Test
	fun theShownSessionMeshesPairWithTheirDisplayArrays() {
		val model = uvRigModel()
		val geometries = uvRigGeometries(model, uvRigPageFrame())

		val direct = assertNotNull(UvEditOverlayProducer().produce(EditorMode.Edit, MeshSelection.editing(listOf(UV_RIG_QUAD, UV_RIG_OTHER)), sceneOf(model, geometries), sizes))

		assertEquals(listOf(UV_RIG_QUAD, UV_RIG_OTHER), direct.overlay.meshes.map { mesh -> mesh.drawableId }, "the session meshes, in the session's order")
		for (geometry in geometries) {
			assertSame(geometry.positions, direct.positionsById[geometry.drawableId], "${geometry.drawableId} draws the shown positions themselves")
			assertSame(geometry.indices, direct.triangleIndicesById[geometry.drawableId], "${geometry.drawableId} fills over the shown indices")
		}
	}

	@Test
	fun aSessionMeshTheSceneDoesNotShowIsLeftOut() {
		val model = uvRigModel()
		val quadOnly = uvRigGeometries(model, uvRigPageFrame(), shown = listOf(UV_RIG_QUAD))

		val direct = assertNotNull(UvEditOverlayProducer().produce(EditorMode.Edit, MeshSelection.editing(listOf(UV_RIG_QUAD, UV_RIG_OTHER)), sceneOf(model, quadOnly), sizes))

		assertEquals(listOf(UV_RIG_QUAD), direct.overlay.meshes.map { mesh -> mesh.drawableId })
		assertEquals(setOf(UV_RIG_QUAD), direct.positionsById.keys)
		assertNull(
			UvEditOverlayProducer().produce(EditorMode.Edit, MeshSelection.editing(listOf(UV_RIG_OTHER)), sceneOf(model, quadOnly), sizes),
			"a scene showing none of the session's meshes shows no overlay",
		)
	}

	@Test
	fun theFlagsFollowTheSelectionOnEachMesh() {
		val session = uvEditSession(editing = listOf(UV_RIG_QUAD, UV_RIG_OTHER), elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2)))
		val model = session.model.value

		val direct = assertNotNull(UvEditOverlayProducer().produce(EditorMode.Edit, session.meshSelection.value, sceneOf(model, uvRigGeometries(model, uvRigPageFrame())), sizes))

		val quad = direct.overlay.meshes.first { mesh -> mesh.drawableId == UV_RIG_QUAD }
		assertEquals(listOf(1, 0, 2, 0), quad.vertexFlags.map { flag -> flag.toInt() }, "the selected vertices, the last one active")
		assertEquals(2, quad.activeVertex)
		val other = direct.overlay.meshes.first { mesh -> mesh.drawableId == UV_RIG_OTHER }
		assertTrue(other.vertexFlags.isEmpty(), "the other mesh has nothing selected")
		assertNull(other.activeVertex, "and the active element is on its own mesh only")
	}

	@Test
	fun anUnchangedSceneKeepsTheValue() {
		val producer = UvEditOverlayProducer()
		val model = uvRigModel()
		val geometries = uvRigGeometries(model, uvRigPageFrame())
		val selection = MeshSelection.editing(listOf(UV_RIG_QUAD, UV_RIG_OTHER))
		val first = producer.produce(EditorMode.Edit, selection, sceneOf(model, geometries), sizes)

		val again = producer.produce(EditorMode.Edit, selection, sceneOf(model, geometries), sizes)

		assertSame(first, again, "a new scene value over the same geometry is the same overlay")
	}

	@Test
	fun aMovedMeshTakesItsNewArrayAndKeepsTheOthers() {
		val producer = UvEditOverlayProducer()
		val model = uvRigModel()
		val geometries = uvRigGeometries(model, uvRigPageFrame())
		val selection = MeshSelection.editing(listOf(UV_RIG_QUAD, UV_RIG_OTHER))
		val before = assertNotNull(producer.produce(EditorMode.Edit, selection, sceneOf(model, geometries), sizes))
		val quad = geometries.first { geometry -> geometry.drawableId == UV_RIG_QUAD }
		val movedPositions = FloatArray(quad.positions.size) { componentIndex -> quad.positions[componentIndex] + 4f }
		val moved = geometries.map { geometry -> if (geometry === quad) GizmoMeshGeometry(quad.drawableId, quad.indices, quad.edges, movedPositions) else geometry }

		val after = assertNotNull(producer.produce(EditorMode.Edit, selection, sceneOf(model, moved), sizes))

		assertNotSame(before, after, "a moved mesh is a new value")
		assertSame(before.overlay, after.overlay, "with the same edges and flags")
		assertSame(movedPositions, after.positionsById[UV_RIG_QUAD], "the moved mesh takes its new positions")
		assertSame(before.positionsById[UV_RIG_OTHER], after.positionsById[UV_RIG_OTHER], "the other keeps its array")
	}

	@Test
	fun aSelectionChangeKeepsThePositions() {
		val producer = UvEditOverlayProducer()
		val model = uvRigModel()
		val geometries = uvRigGeometries(model, uvRigPageFrame())
		val editing = MeshSelection.editing(listOf(UV_RIG_QUAD))
		val before = assertNotNull(producer.produce(EditorMode.Edit, editing, sceneOf(model, geometries), sizes))

		val selected = MeshSelectionOps.add(editing, UV_RIG_QUAD, MeshElement.Vertex(1))
		val after = assertNotNull(producer.produce(EditorMode.Edit, selected, sceneOf(model, geometries), sizes))

		assertNotSame(before.overlay, after.overlay, "the flags changed")
		assertSame(before.positionsById[UV_RIG_QUAD], after.positionsById[UV_RIG_QUAD], "the positions did not")
	}

	/**
	 * A scene over the rig's page showing the given geometry.
	 *
	 * @param PuppetModel model The model the geometry was projected from.
	 * @param List<GizmoMeshGeometry> geometries The shown geometry.
	 * @return UvShownScene The scene, a new instance each call.
	 */
	private fun sceneOf(model: PuppetModel, geometries: List<GizmoMeshGeometry>): UvShownScene = UvShownScene(UvSceneContent.AtlasPage(0), null, model, geometries)
}