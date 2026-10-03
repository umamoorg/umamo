package org.umamo.ui.viewport.gizmo

import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.viewport2d.RIG_HIDDEN
import org.umamo.ui.viewport.viewport2d.RIG_OTHER
import org.umamo.ui.viewport.viewport2d.RIG_QUAD
import org.umamo.ui.viewport.viewport2d.gizmoRigModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the Edit overlay's derive: which meshes it lists, the flags each select mode lights (the same
 * derive-up and flush-down rules the selection uses), that flag 2 and the active ordinal always agree
 * (the renderer draws a flag-2 primitive only through the ordinal), that elements outside a mesh drop
 * rather than throw, and what it keeps by identity from one call to the next - which is what spares the
 * renderer its uploads.
 *
 * The rig's quad is two triangles, 0-1-2 and 0-2-3, so its edges in first-encounter order are (0,1),
 * (1,2), (0,2), (2,3), (0,3); edges are read here through [flagOf] rather than by ordinal, so these pins
 * stand whatever order the edge list takes.
 */
class EditMeshOverlayProducerTest {
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)

	@Test
	fun outsideEditModeThereIsNoOverlay() {
		assertNull(EditMeshOverlayProducer().produce(EditorMode.Object, MeshSelection.editing(listOf(RIG_QUAD)), gizmoRigModel(), sizes))
	}

	@Test
	fun anEmptySelectionListsEverySessionMeshWithEmptyFlags() {
		val overlay = assertNotNull(produce(MeshSelection.editing(listOf(RIG_QUAD, RIG_OTHER))))
		assertEquals(listOf(RIG_QUAD, RIG_OTHER), overlay.meshes.map { mesh -> mesh.drawableId }, "every session mesh, in the session's order")
		assertEquals(MeshOverlaySelectMode.Vertex, overlay.selectMode)
		for (mesh in overlay.meshes) {
			assertTrue(mesh.vertexFlags.isEmpty() && mesh.edgeFlags.isEmpty() && mesh.faceFlags.isEmpty(), "${mesh.drawableId} has no flags")
			assertNull(mesh.activeVertex ?: mesh.activeEdge ?: mesh.activeFace, "${mesh.drawableId} has no active element")
		}
		assertEquals(5, overlay.meshes.first().edgeCount, "the quad's five unique edges")
	}

	/** A surface showing only some session meshes narrows the list to them, still in the session's order. */
	@Test
	fun shownIdsRestrictTheEntries() {
		val producer = EditMeshOverlayProducer()
		val selection = MeshSelection.editing(listOf(RIG_QUAD, RIG_OTHER))

		val narrowed = assertNotNull(producer.produce(EditorMode.Edit, selection, gizmoRigModel(), sizes, shownIds = setOf(RIG_OTHER)))
		assertEquals(listOf(RIG_OTHER), narrowed.meshes.map { mesh -> mesh.drawableId }, "only the shown session mesh")
		assertNull(producer.produce(EditorMode.Edit, selection, gizmoRigModel(), sizes, shownIds = emptySet()), "a surface showing none of them shows no overlay")
		val unnarrowed = assertNotNull(producer.produce(EditorMode.Edit, selection, gizmoRigModel(), sizes, shownIds = null))
		assertEquals(listOf(RIG_QUAD, RIG_OTHER), unnarrowed.meshes.map { mesh -> mesh.drawableId }, "no shown set lists every session mesh")
	}

	@Test
	fun anUnprojectableSessionMeshIsListedForTheRendererToSkip() {
		val overlay = assertNotNull(produce(MeshSelection.editing(listOf(RIG_QUAD, RIG_HIDDEN))))
		assertEquals(listOf(RIG_QUAD, RIG_HIDDEN), overlay.meshes.map { mesh -> mesh.drawableId }, "the renderer, not the derive, skips what it cannot pose")
	}

	@Test
	fun vertexModeFlagsTheSelectionAndDerivesEdgesAndFaces() {
		val quad = quadEntry(selectionOf(MeshSelectMode.Vertex, listOf(MeshElement.Vertex(0), MeshElement.Vertex(1), MeshElement.Vertex(2))))
		assertEquals(listOf(1, 1, 2, 0), quad.vertexFlags.map { flag -> flag.toInt() }, "the selected vertices, the last one active")
		assertEquals(2, quad.activeVertex)
		assertEquals(1, flagOf(quad, 0, 1), "an edge with both endpoints selected")
		assertEquals(1, flagOf(quad, 1, 2))
		assertEquals(1, flagOf(quad, 0, 2))
		assertEquals(0, flagOf(quad, 2, 3), "an edge with one endpoint selected")
		assertEquals(0, flagOf(quad, 0, 3))
		assertEquals(listOf(1, 0), quad.faceFlags.map { flag -> flag.toInt() }, "the face whose three vertices are all selected")
	}

	@Test
	fun edgeModeFlagsTheEdgesAndTheFacesTheyClose() {
		val quad =
			quadEntry(
				selectionOf(
					MeshSelectMode.Edge,
					listOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(1, 2), MeshElement.Edge.of(0, 2)),
				),
			)
		assertEquals(1, flagOf(quad, 0, 1))
		assertEquals(1, flagOf(quad, 1, 2))
		assertEquals(2, flagOf(quad, 0, 2), "the last edge is the active one")
		assertEquals(0, flagOf(quad, 2, 3))
		assertEquals(0, flagOf(quad, 0, 3))
		assertEquals(listOf(1, 0), quad.faceFlags.map { flag -> flag.toInt() }, "the face its three edges close")
		assertTrue(quad.vertexFlags.all { flag -> flag.toInt() == 0 }, "no vertex lights in Edge mode")
	}

	@Test
	fun faceModeFlagsTheFacesAndFlushesTheirEdges() {
		val quad = quadEntry(selectionOf(MeshSelectMode.Face, listOf(MeshElement.Face(1))))
		assertEquals(listOf(0, 2), quad.faceFlags.map { flag -> flag.toInt() }, "the selected face, active")
		assertEquals(1, quad.activeFace)
		assertEquals(1, flagOf(quad, 0, 2), "the face's own edges light")
		assertEquals(1, flagOf(quad, 2, 3))
		assertEquals(1, flagOf(quad, 0, 3))
		assertEquals(0, flagOf(quad, 0, 1), "the other face's edges do not")
		assertEquals(0, flagOf(quad, 1, 2))
	}

	@Test
	fun flagTwoAndTheActiveOrdinalAlwaysAgree() {
		val selections =
			listOf(
				selectionOf(MeshSelectMode.Vertex, listOf(MeshElement.Vertex(3), MeshElement.Vertex(1))),
				selectionOf(MeshSelectMode.Edge, listOf(MeshElement.Edge.of(2, 3), MeshElement.Edge.of(0, 3))),
				selectionOf(MeshSelectMode.Face, listOf(MeshElement.Face(0), MeshElement.Face(1))),
			)
		for (selection in selections) {
			val quad = quadEntry(selection)
			assertEquals(listOfNotNull(quad.activeVertex), activePositions(quad.vertexFlags), "vertices in ${selection.selectMode} mode")
			assertEquals(listOfNotNull(quad.activeEdge), activePositions(quad.edgeFlags), "edges in ${selection.selectMode} mode")
			assertEquals(listOfNotNull(quad.activeFace), activePositions(quad.faceFlags), "faces in ${selection.selectMode} mode")
			assertEquals(1, listOfNotNull(quad.activeVertex, quad.activeEdge, quad.activeFace).size, "one active element, in the mode's own domain")
		}
	}

	@Test
	fun anElementOutsideTheMeshIsDroppedAndAnOutOfRangeActiveIsNull() {
		val vertexQuad = quadEntry(selectionOf(MeshSelectMode.Vertex, listOf(MeshElement.Vertex(1), MeshElement.Vertex(9))))
		assertEquals(listOf(0, 1, 0, 0), vertexQuad.vertexFlags.map { flag -> flag.toInt() }, "vertex 9 is not the quad's")
		assertNull(vertexQuad.activeVertex, "an active vertex the quad lacks is no active vertex")

		val edgeQuad = quadEntry(selectionOf(MeshSelectMode.Edge, listOf(MeshElement.Edge.of(1, 3))))
		assertTrue(edgeQuad.edgeFlags.all { flag -> flag.toInt() == 0 }, "(1, 3) is not one of the quad's edges")
		assertNull(edgeQuad.activeEdge)

		val faceQuad = quadEntry(selectionOf(MeshSelectMode.Face, listOf(MeshElement.Face(5))))
		assertTrue(faceQuad.faceFlags.all { flag -> flag.toInt() == 0 }, "triangle 5 is not the quad's")
		assertNull(faceQuad.activeFace)
	}

	@Test
	fun aMalformedMeshIsLeftOutRatherThanThrown() {
		val broken = withQuadMesh(gizmoRigModel()) { mesh -> DrawableMesh.withLocalEqualToCanvas(mesh.positions, mesh.uvs, intArrayOf(0, 1, 7, 0, 2, 3)) }
		val producer = EditMeshOverlayProducer()
		assertNull(producer.produce(EditorMode.Edit, MeshSelection.editing(listOf(RIG_QUAD)), broken, sizes), "the only session mesh is left out")
		val overlay = assertNotNull(producer.produce(EditorMode.Edit, MeshSelection.editing(listOf(RIG_QUAD, RIG_OTHER)), broken, sizes))
		assertEquals(listOf(RIG_OTHER), overlay.meshes.map { mesh -> mesh.drawableId }, "the well-formed mesh stays")
	}

	@Test
	fun anUnchangedInputReturnsTheSameOverlay() {
		val producer = EditMeshOverlayProducer()
		val selection = selectionOf(MeshSelectMode.Vertex, listOf(MeshElement.Vertex(0)))
		val model = gizmoRigModel()
		val first = producer.produce(EditorMode.Edit, selection, model, MeshOverlaySizes(3.5f, 1f, 2.5f))
		val second = producer.produce(EditorMode.Edit, selection, model, MeshOverlaySizes(3.5f, 1f, 2.5f))
		assertSame(first, second, "equal sizes and the same selection and model hand back the same overlay")
	}

	@Test
	fun aPositionsOnlyModelKeepsEveryMesh() {
		val producer = EditMeshOverlayProducer()
		val selection = selectionOf(MeshSelectMode.Vertex, listOf(MeshElement.Vertex(0)))
		val model = gizmoRigModel()
		val before = assertNotNull(producer.produce(EditorMode.Edit, selection, model, sizes))
		val moved = withQuadMesh(model) { mesh -> DrawableMesh.withLocalEqualToCanvas(mesh.positions.map { coordinate -> coordinate + 5f }.toFloatArray(), mesh.uvs, mesh.indices) }
		val after = producer.produce(EditorMode.Edit, selection, moved, sizes)
		assertSame(before, after, "a Grab's commit moves positions only, which the overlay does not carry")
	}

	@Test
	fun aSelectionChangeOnOneMeshKeepsTheOtherMeshes() {
		val producer = EditMeshOverlayProducer()
		val model = gizmoRigModel()
		// The quad's vertex goes in last, so the active element stays on the quad through the change below and
		// the other mesh is untouched in every respect.
		val seeded =
			MeshSelectionOps.add(
				MeshSelectionOps.add(MeshSelection.editing(listOf(RIG_QUAD, RIG_OTHER)), RIG_OTHER, MeshElement.Vertex(1)),
				RIG_QUAD,
				MeshElement.Vertex(0),
			)
		val before = assertNotNull(producer.produce(EditorMode.Edit, seeded, model, sizes))
		val after = assertNotNull(producer.produce(EditorMode.Edit, MeshSelectionOps.add(seeded, RIG_QUAD, MeshElement.Vertex(2)), model, sizes))
		assertNotSame(before, after, "a selection change is a new overlay")
		assertSame(before.meshes[1], after.meshes[1], "the untouched mesh keeps its entry, flags and all")
		assertNotSame(before.meshes[0], after.meshes[0], "the touched mesh is rebuilt")
		assertSame(before.meshes[0].edgeEndpoints, after.meshes[0].edgeEndpoints, "over the same edge list")
	}

	@Test
	fun aTopologyChangeRebuildsOnlyThatMeshsEdges() {
		val producer = EditMeshOverlayProducer()
		val model = gizmoRigModel()
		val selection = MeshSelection.editing(listOf(RIG_QUAD, RIG_OTHER))
		val before = assertNotNull(producer.produce(EditorMode.Edit, selection, model, sizes))
		val flipped = withQuadMesh(model) { mesh -> DrawableMesh.withLocalEqualToCanvas(mesh.positions, mesh.uvs, intArrayOf(0, 1, 3, 1, 2, 3)) }
		val after = assertNotNull(producer.produce(EditorMode.Edit, selection, flipped, sizes))
		assertNotSame(before.meshes[0].edgeEndpoints, after.meshes[0].edgeEndpoints, "the re-triangulated quad gets a new edge list")
		assertTrue(flagOf(after.meshes[0], 1, 3) >= 0, "with the new diagonal")
		assertSame(before.meshes[1], after.meshes[1], "the other mesh keeps its entry")
	}

	/**
	 * One fresh derive in Edit mode.
	 *
	 * @param MeshSelection selection The selection.
	 * @return MeshOverlay? The overlay.
	 */
	private fun produce(selection: MeshSelection): MeshOverlay? = EditMeshOverlayProducer().produce(EditorMode.Edit, selection, gizmoRigModel(), sizes)

	/**
	 * The quad's entry for a selection over the quad alone.
	 *
	 * @param MeshSelection selection The selection.
	 * @return MeshOverlayMesh The quad's entry.
	 */
	private fun quadEntry(selection: MeshSelection): MeshOverlayMesh = assertNotNull(produce(selection)).meshes.single()

	/**
	 * A selection over the quad alone in [selectMode] holding [elements], the last one active; written
	 * directly, so elements the quad lacks can be named.
	 *
	 * @param MeshSelectMode selectMode The select mode.
	 * @param List<MeshElement> elements The elements, the last active.
	 * @return MeshSelection The selection.
	 */
	private fun selectionOf(selectMode: MeshSelectMode, elements: List<MeshElement>): MeshSelection =
		MeshSelection(
			drawableIds = listOf(RIG_QUAD),
			activeDrawableId = RIG_QUAD,
			selectMode = selectMode,
			elementsByDrawable = mapOf(RIG_QUAD to elements.toSet()),
			activeElement = ActiveMeshElement(RIG_QUAD, elements.last()),
		)

	/**
	 * The flag an entry gives the edge between two vertices, found through its edge list; an entry with no
	 * edge flags reads as all idle.
	 *
	 * @param MeshOverlayMesh mesh The entry.
	 * @param Int low One endpoint.
	 * @param Int high The other endpoint.
	 * @return Int The flag, or -1 when the entry has no such edge.
	 */
	private fun flagOf(mesh: MeshOverlayMesh, low: Int, high: Int): Int {
		for (edgeOrdinal in 0 until mesh.edgeCount) {
			val from = mesh.edgeEndpoints[edgeOrdinal * 2]
			val to = mesh.edgeEndpoints[edgeOrdinal * 2 + 1]
			if ((from == low && to == high) || (from == high && to == low)) {
				return if (mesh.edgeFlags.isEmpty()) 0 else mesh.edgeFlags[edgeOrdinal].toInt()
			}
		}
		return -1
	}

	/**
	 * Where a flag array holds the active flag.
	 *
	 * @param ByteArray flags The flags.
	 * @return List<Int> The positions holding 2.
	 */
	private fun activePositions(flags: ByteArray): List<Int> = flags.indices.filter { position -> flags[position].toInt() == 2 }

	/**
	 * The model with the quad's mesh replaced.
	 *
	 * @param PuppetModel model The model.
	 * @param Function replace The quad's new mesh from its old one.
	 * @return PuppetModel The edited model.
	 */
	private fun withQuadMesh(model: PuppetModel, replace: (DrawableMesh) -> DrawableMesh): PuppetModel =
		model.copy(
			drawables =
				model.drawables.map { drawable ->
					if (drawable.id == RIG_QUAD) {
						drawable.copy(mesh = replace(drawable.mesh!!))
					} else {
						drawable
					}
				},
		)
}