package org.umamo.ui.viewport

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTopology
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformPivotMode
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Object-mode mapping move over a source layer: the capture takes every vertex of exactly
 * the selected islands shown on the layer, Individual Origins turns each island about its own
 * centroid, the anchor follows the pivot mode, and a Grab moves whole islands and commits through
 * the layer's frame with nothing left for the untouched island.
 */
class UvMappingGestureTest {
	// A two-triangle quad: v0 (0,0), v1 (20,0), v2 (0,20), v3 (20,20).
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadPositions = floatArrayOf(0f, 0f, 20f, 0f, 0f, 20f, 20f, 20f)
	private val everyVertex = setOf(0, 1, 2, 3)

	/**
	 * Builds the test quad as one shown island.
	 *
	 * @param String id The drawable id.
	 * @param Float shiftX How far along x the quad sits.
	 * @return GizmoMeshGeometry The island.
	 */
	private fun quad(id: String, shiftX: Float = 0f): GizmoMeshGeometry {
		val positions = FloatArray(quadPositions.size) { index -> if (index % 2 == 0) quadPositions[index] + shiftX else quadPositions[index] }
		return GizmoMeshGeometry(DrawableId(id), quadIndices, MeshTopology.uniqueEdges(quadIndices), positions)
	}

	private fun target(id: String): SelectionTarget.Drawable = SelectionTarget.Drawable(DrawableId(id))

	private fun selectionOf(vararg ids: String): Selection = Selection(ids.mapTo(LinkedHashSet()) { id -> target(id) }, ids.lastOrNull()?.let { id -> target(id) })

	private val pageFrame = atlasPageEditFrame(pageWidth = 200, pageHeight = 100)

	@Test
	fun coversEveryVertexOfExactlyTheSelectedIslands() {
		val shown = listOf(quad("a"), quad("b", shiftX = 50f))
		val gesture = assertNotNull(buildUvMappingGesture(shown, selectionOf("a"), TransformPivotMode.MedianPoint, null, pageFrame, MeshOperatorKind.Grab))
		val entry = gesture.transform.entries.single()
		assertEquals(DrawableId("a"), entry.drawableId, "only the selected island is captured")
		assertEquals(everyVertex, entry.coveredIndices, "every vertex of it is covered")
		assertEquals(everyVertex, entry.movedIndices, "and every vertex moves - there is no halo")
		assertTrue(entry.influence.isEmpty(), "Object mode weights no unselected vertices")
		assertEquals(MeshOperatorKind.Grab, gesture.transform.operatorKind)
		assertTrue(gesture.frame === pageFrame, "the frame freezes onto the gesture")
	}

	@Test
	fun individualOriginsTurnEachIslandAboutItsOwnCentroid() {
		val shown = listOf(quad("a"), quad("b", shiftX = 50f))
		val gesture = assertNotNull(buildUvMappingGesture(shown, selectionOf("a", "b"), TransformPivotMode.IndividualOrigins, null, pageFrame, MeshOperatorKind.Rotate))
		val byId = gesture.transform.entries.associateBy { entry -> entry.drawableId }
		val groupA = byId.getValue(DrawableId("a")).groups.single()
		val groupB = byId.getValue(DrawableId("b")).groups.single()
		assertEquals(everyVertex, groupA.vertexIndices, "one group per island, not per connectivity island")
		assertEquals(10f to 10f, groupA.pivotX to groupA.pivotY, "island a turns about its own centroid")
		assertEquals(60f to 10f, groupB.pivotX to groupB.pivotY, "island b turns about its own centroid")
	}

	@Test
	fun theAnchorFollowsThePivotMode() {
		val shown = listOf(quad("a"), quad("b", shiftX = 50f))
		val selection = selectionOf("a", "b")
		val median = assertNotNull(buildUvMappingGesture(shown, selection, TransformPivotMode.MedianPoint, 3f to 4f, pageFrame, MeshOperatorKind.Scale))
		assertEquals(35f to 10f, median.transform.anchor, "Median Point is the combined centroid of the moving islands")
		val active = assertNotNull(buildUvMappingGesture(shown, selection, TransformPivotMode.ActiveElement, 3f to 4f, pageFrame, MeshOperatorKind.Scale))
		assertEquals(60f to 10f, active.transform.anchor, "Active Element is the active island's own centroid")
		val cursor = assertNotNull(buildUvMappingGesture(shown, selection, TransformPivotMode.Cursor, 3f to 4f, pageFrame, MeshOperatorKind.Scale))
		assertEquals(3f to 4f, cursor.transform.anchor, "Cursor is the UV cursor in display space")
		val unplaced = assertNotNull(buildUvMappingGesture(shown, selection, TransformPivotMode.Cursor, null, pageFrame, MeshOperatorKind.Scale))
		assertEquals(35f to 10f, unplaced.transform.anchor, "an unplaced cursor falls back to the median")
	}

	@Test
	fun refusesWhenNothingSelectedIsShownOnTheLayer() {
		val shown = listOf(quad("a"))
		assertNull(buildUvMappingGesture(shown, selectionOf("c"), TransformPivotMode.MedianPoint, null, pageFrame, MeshOperatorKind.Grab), "a selection shown elsewhere has nothing here to move")
		assertNull(buildUvMappingGesture(shown, Selection(), TransformPivotMode.MedianPoint, null, pageFrame, MeshOperatorKind.Grab), "an empty selection moves nothing")
		assertNull(buildUvMappingGesture(emptyList(), selectionOf("a"), TransformPivotMode.MedianPoint, null, pageFrame, MeshOperatorKind.Grab), "an empty layer has nothing to move")
	}

	@Test
	fun aGrabMovesWholeIslandsAndCommitsThroughTheLayerFrame() {
		val binding = DrawableLayerBinding("layer", AtlasPlacement(0, 128f, 256f, 0.86f, 0.86f, 37.5f), pageWidth = 2048, pageHeight = 1024)
		val frame = assertNotNull(sourceLayerEditFrame(binding, 576, 646))
		val shown = listOf(quad("a"), quad("b", shiftX = 50f))
		val gesture = assertNotNull(buildUvMappingGesture(shown, selectionOf("a"), TransformPivotMode.MedianPoint, null, frame, MeshOperatorKind.Grab))
		val preview = mappingPreview(gesture.transform, MeshOperatorKind.Grab, TransformGestureParameters(10f, -5f, 1f, 1f, 0f))
		assertEquals(setOf(DrawableId("a")), preview.keys, "the untouched island is absent from the preview")
		val moved = preview.getValue(DrawableId("a"))
		for (vertexIndex in 0 until 4) {
			assertEquals(quadPositions[vertexIndex * 2] + 10f, moved[vertexIndex * 2], 1e-5f, "vertex $vertexIndex x moved by the delta")
			assertEquals(quadPositions[vertexIndex * 2 + 1] - 5f, moved[vertexIndex * 2 + 1], 1e-5f, "vertex $vertexIndex y moved by the delta")
		}
		// The commit converts every vertex (all moved) back through the layer frame onto the stored coordinates.
		val entry = gesture.transform.entries.single()
		val committed = storedUvsForCommit(modelWith("a", FloatArray(8)), DrawableId("a"), entry.movedIndices, moved, frame)
		val expected = frame.storedUvs(moved)
		assertEquals(expected.size, committed.size)
		for (componentIndex in expected.indices) {
			assertEquals(expected[componentIndex], committed[componentIndex], 0f, "component $componentIndex commits the authored coordinate")
		}
	}

	/**
	 * A one-drawable model carrying the given stored coordinates.
	 *
	 * @param String id The drawable id.
	 * @param FloatArray uvs The stored coordinates.
	 * @return PuppetModel The model.
	 */
	private fun modelWith(id: String, uvs: FloatArray): PuppetModel {
		val drawableId = DrawableId(id)
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = id,
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(quadPositions.copyOf(), uvs, quadIndices),
						geometryGrid = null,
					),
				),
			rootChildren = listOf(OrgChild.Drawable(drawableId)),
			rootPartId = null,
		)
	}
}