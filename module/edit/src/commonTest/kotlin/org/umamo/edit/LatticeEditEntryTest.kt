package org.umamo.edit

import org.umamo.edit.mesh.MergeTarget
import org.umamo.edit.mesh.commitLatticeReshape
import org.umamo.edit.mesh.connectSelectedVertices
import org.umamo.edit.mesh.duplicateSelectedElements
import org.umamo.edit.mesh.mergeSelectedVertices
import org.umamo.runtime.keyform.withLatticeShifted
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins how Edit mode opens on a warp's lattice: Tab on an active warp edits its points, the active item's kind
 * decides who joins, a warp with nothing to edit refuses with its reason near the cursor, the element kit selects
 * points and whole cells, the topology commands refuse, and a reshape commits as one step that reports the
 * children it could not keep in place.
 */
class LatticeEditEntryTest {
	private val parameter = ParameterId("P")
	private val art = DrawableId("art")
	private val cage = DeformerId("cage")
	private val second = DeformerId("second")
	private val bare = DeformerId("bare")
	private val offRest = DeformerId("offRest")
	private val turn = DeformerId("turn")

	/**
	 * A 2 x 2 lattice over a 40-unit square at [originX], as nine points row by row.
	 *
	 * @param Float originX The square's left edge.
	 * @return FloatArray The points.
	 */
	private fun square(originX: Float): FloatArray =
		FloatArray(18) { componentIndex ->
			val pointIndex = componentIndex / 2

			if (componentIndex % 2 == 0) originX + (pointIndex % 3) * 20f else (pointIndex / 3) * 20f
		}

	/**
	 * A 2 x 2 warp keyed at [keys], every key holding [points]; null keys leave it without a lattice.
	 *
	 * @param DeformerId id The warp.
	 * @param FloatArray points Its nine points.
	 * @param FloatArray? keys The parameter keys, or null for no grid.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, points: FloatArray, keys: FloatArray?): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			geometryGrid =
				keys?.let { values ->
					KeyformGrid(listOf(KeyformAxis(parameter, values)), values.indices.map { keyIndex -> KeyformCell(intArrayOf(keyIndex), WarpLatticeForm(points.copyOf())) })
				},
		)

	/**
	 * The rig: a quad of art, two keyed warps, a warp with no lattice, a warp keyed only away from the default, and
	 * a rotation.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameter, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					warp(cage, square(0f), floatArrayOf(-1f, 1f)),
					warp(second, square(100f), floatArrayOf(-1f, 1f)),
					warp(bare, square(200f), null),
					warp(offRest, square(300f), floatArrayOf(0.5f, 1f)),
					Deformer.Rotation(
						id = turn,
						name = "turn",
						parent = null,
						partId = null,
						baseAngle = 0f,
						geometryGrid =
							KeyformGrid(
								listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
								listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0f, 0f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0f, 0f, 0f, 1f))),
							),
					),
				),
			drawables =
				listOf(
					Drawable(
						id = art,
						name = "art",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
						geometryGrid = null,
					),
				),
			rootChildren = listOf(OrgChild.Drawable(art)),
			rootPartId = null,
		)

	/**
	 * A session over the rig with [selected] selected, the last active, then Tab into Edit mode.
	 *
	 * @param List<SelectionTarget> selected The targets to select.
	 * @return EditorSession The session.
	 */
	private fun tabInto(selected: List<SelectionTarget>): EditorSession {
		val session = EditorSession(model())
		session.setSelection(Selection(selected.toSet(), selected.lastOrNull()))
		session.setMode(EditorMode.Edit)

		return session
	}

	@Test
	fun tabOnAnActiveWarpEditsItsLattice() {
		val session = tabInto(listOf(SelectionTarget.Deformer(cage)))

		assertEquals(EditorMode.Edit, session.mode.value)
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage)), session.meshSelection.value.owners)
		assertEquals(ElementOwner.Deformer(cage), session.meshSelection.value.activeOwner)
		assertTrue(session.meshSelection.value.drawableIds.isEmpty(), "a lattice session holds no drawable")
	}

	@Test
	fun theActiveItemsKindDecidesWhoJoins() {
		val warps = tabInto(listOf(SelectionTarget.Drawable(art), SelectionTarget.Deformer(second), SelectionTarget.Deformer(cage)))
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(second), ElementOwner.Deformer(cage)), warps.meshSelection.value.owners, "an active warp brings the selected warps, not the art")

		val meshes = tabInto(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Drawable(art)))
		assertEquals(listOf<ElementOwner>(ElementOwner.Drawable(art)), meshes.meshSelection.value.owners, "an active drawable brings the drawables, not the warp")
	}

	@Test
	fun aWarpWithNothingToEditRefusesWithItsReason() {
		val cases =
			listOf(
				bare to "notice.edit.warpHasNoLattice",
				offRest to "notice.edit.warpHiddenAtRest",
				turn to "notice.edit.rotationNotEditable",
			)

		for ((deformerId, reason) in cases) {
			val session = tabInto(listOf(SelectionTarget.Deformer(deformerId)))

			assertEquals(EditorMode.Object, session.mode.value, "$deformerId stays in Object mode")
			assertEquals(reason, session.notice.value?.messageKey, "$deformerId says why")
			assertEquals(NoticePlacement.NearCursor, session.notice.value?.placement)
		}
	}

	@Test
	fun reenteringWithNothingSelectedReturnsToTheLatticeAndItsPoints() {
		val session = tabInto(listOf(SelectionTarget.Deformer(cage)))
		val owner: ElementOwner = ElementOwner.Deformer(cage)
		session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = mapOf(owner to setOf(MeshElement.Vertex(4))), activeElement = ActiveMeshElement(owner, MeshElement.Vertex(4))))
		session.setMode(EditorMode.Object)
		session.setSelection(Selection(emptySet(), null))
		session.setMode(EditorMode.Edit)

		assertEquals(listOf(owner), session.meshSelection.value.owners, "the remembered lattice, not the topmost drawable")
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(4)), session.meshSelection.value.elementsOf(owner), "with its points")
	}

	@Test
	fun selectAllCoversEveryPointAndEveryCell() {
		val session = tabInto(listOf(SelectionTarget.Deformer(cage)))
		val owner: ElementOwner = ElementOwner.Deformer(cage)
		session.selectAllMeshElements()
		assertEquals(9, session.meshSelection.value.elementsOf(owner).size, "nine points")

		session.setMeshSelectMode(MeshSelectMode.Face)
		assertEquals((0 until 4).map { cellIndex -> MeshElement.Face(cellIndex) }.toSet<MeshElement>(), session.meshSelection.value.elementsOf(owner), "four whole cells")

		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(12, session.meshSelection.value.elementsOf(owner).size, "twelve grid-line sides, no diagonal")
	}

	@Test
	fun topologyCommandsRefuseOnALattice() {
		val session = tabInto(listOf(SelectionTarget.Deformer(cage)))
		session.selectAllMeshElements()
		val before = session.model.value
		val commands =
			listOf<(EditorSession) -> Unit>(
				{ edited -> edited.duplicateSelectedElements() },
				{ edited -> edited.mergeSelectedVertices(MergeTarget.AtCenter) },
				{ edited -> edited.connectSelectedVertices() },
			)

		for (command in commands) {
			session.clearNotice(session.notice.value?.serial ?: 0L)
			command(session)

			assertSame(before, session.model.value, "the lattice keeps its grid")
			assertEquals("notice.edit.latticeTopologyFixed", session.notice.value?.messageKey)
		}
	}

	@Test
	fun aReshapeIsOneStepThatReportsTheChildrenItMoved() {
		val session = tabInto(listOf(SelectionTarget.Deformer(cage)))
		val before = session.model.value
		val reshaped = before.copy(deformers = before.deformers.map { deformer -> if (deformer.id == cage) (deformer as Deformer.Warp).withLatticeShifted(FloatArray(18) { 1f }) else deformer })
		val change = DeformerChange.ReshapeLattice(listOf(cage), MeshOperatorKind.Grab)
		val stepsBefore = session.historyView.value.steps.size

		session.commitLatticeReshape(change, reshaped, unconvertedCount = 2)

		assertSame(reshaped, session.model.value)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.lattice.move", session.historyView.value.steps.last().labelKey)
		assertEquals("notice.edit.latticeChildrenMoved", session.notice.value?.messageKey)
		assertEquals(listOf("2"), session.notice.value?.arguments)

		session.undo()
		assertSame(before, session.model.value, "undo puts the lattice back")

		session.clearNotice(session.notice.value?.serial ?: 0L)
		session.commitLatticeReshape(change, session.model.value, unconvertedCount = 3)
		assertNull(session.notice.value, "a reshape that changed nothing records and reports nothing")
	}
}