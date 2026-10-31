package org.umamo.ui.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.WarpCreationParameterKeys
import org.umamo.edit.withParameter
import org.umamo.render.eval.parentSpaceAt
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins Create Warp Deformer: the warp is born around the selection's world box plus a margin, with one keyform,
 * under the selection's nearest common ancestor, named "Warp" (suffixed when taken); the wrapped items - the outermost
 * selected drawables and deformers, never parts - rest where they rested with their keyed motion; the commit is one
 * step that selects the new warp, and the strip reruns it with other cells, margin, and mapping; a target without
 * bilinear warps offers no mapping choice; and only Object mode creates.
 */
class WarpCreationTest {
	private val parameterId = ParameterId("P")
	private val cageId = DeformerId("cage")
	private val innerId = DeformerId("inner")
	private val spinId = DeformerId("spin")
	private val bodyId = PartId("body")
	private val headId = PartId("head")
	private val createdId = DeformerId("Warp")
	private val swung = mapOf(parameterId to 1f)

	/**
	 * A one-cell warp over [corners], keyed on P at both ends with the same lattice, so it shows at every value of P.
	 *
	 * @param DeformerId id      The id.
	 * @param DeformerId? parent The parent deformer.
	 * @param FloatArray corners The four corners, row-major, u across.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, parent: DeformerId?, corners: FloatArray): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = parent,
			partId = headId,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid = KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 1f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(corners)), KeyformCell(intArrayOf(1), WarpLatticeForm(corners)))),
		)

	/**
	 * A drawable over [canvas] with base [local] (the same array when [local] is omitted).
	 *
	 * @param String      id     The id.
	 * @param DeformerId? parent The parent deformer.
	 * @param FloatArray  canvas The canvas mesh.
	 * @param FloatArray  local  The base.
	 * @param Float       sweep  The delta its keyform at P = 1 moves every coordinate by; 0 for an unkeyed drawable.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, parent: DeformerId?, canvas: FloatArray, local: FloatArray = canvas, sweep: Float = 0f): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parent,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions = canvas, localPositions = local, uvs = FloatArray(canvas.size), indices = intArrayOf(0, 1, 2)),
			geometryGrid =
				if (sweep != 0f) {
					KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(canvas.size))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(canvas.size) { sweep }))))
				} else {
					null
				},
		)

	/**
	 * Two root drawables in the body part, one keyed; a root warp over canvas pixels 300..500 holding a keyed drawable,
	 * a warp with a drawable of its own, and a keyed rotation, all in the head part.
	 *
	 * @param RuntimeTarget target The runtime target.
	 * @return PuppetModel The model.
	 */
	private fun model(target: RuntimeTarget = RuntimeTarget.NoTarget): PuppetModel {
		val spin = Deformer.Rotation(spinId, "spin", cageId, headId, 0f, KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0.3f, 0.7f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0.35f, 0.7f, 20f, 1f)))))

		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts =
				listOf(
					Part(bodyId, "body", children = listOf(OrgChild.Drawable(DrawableId("left")), OrgChild.Drawable(DrawableId("right")))),
					Part(headId, "head", children = listOf(OrgChild.Drawable(DrawableId("caged")), OrgChild.Drawable(DrawableId("deep")))),
				),
			deformers =
				listOf(
					warp(cageId, null, floatArrayOf(300f, 300f, 500f, 300f, 300f, 500f, 500f, 500f)),
					warp(innerId, cageId, floatArrayOf(0.5f, 0.5f, 0.9f, 0.5f, 0.5f, 0.9f, 0.9f, 0.9f)),
					spin,
				),
			drawables =
				listOf(
					drawable("left", null, floatArrayOf(100f, 100f, 140f, 100f, 140f, 140f)),
					drawable("right", null, floatArrayOf(200f, 120f, 260f, 120f, 260f, 180f), sweep = 5f),
					drawable("caged", cageId, floatArrayOf(340f, 340f, 380f, 340f, 380f, 380f), floatArrayOf(0.2f, 0.2f, 0.4f, 0.2f, 0.4f, 0.4f), sweep = 0.05f),
					drawable("deep", innerId, floatArrayOf(420f, 420f, 440f, 420f, 440f, 440f), floatArrayOf(0.2f, 0.2f, 0.4f, 0.2f, 0.4f, 0.4f)),
				),
			rootChildren = listOf(OrgChild.Part(bodyId), OrgChild.Part(headId)),
			rootPartId = null,
			runtimeTarget = target,
		)
	}

	/**
	 * Where [id] sits on screen in [model] at [pose]: its posed shape through its deformer chain.
	 *
	 * @param PuppetModel model The model.
	 * @param String      id    The drawable.
	 * @param Map         pose  The pose.
	 * @return FloatArray The world positions.
	 */
	private fun worldAt(model: PuppetModel, id: String, pose: Map<ParameterId, Float> = emptyMap()): FloatArray = assertNotNull(captureDrawableWorld(model, pose, DrawableId(id))).world

	/**
	 * The new warp's lattice on screen in [model] at rest.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId  id    The warp.
	 * @return FloatArray The world lattice.
	 */
	private fun latticeAt(model: PuppetModel, id: DeformerId = createdId): FloatArray = assertNotNull(warpLatticeAt(model, emptyMap(), id)).world

	/**
	 * The warp [id] in [model].
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId  id    The warp.
	 * @return Deformer.Warp The warp.
	 */
	private fun warpOf(model: PuppetModel, id: DeformerId = createdId): Deformer.Warp = assertNotNull(model.deformerById[id] as? Deformer.Warp, "the warp $id")

	/**
	 * The parent of drawable [id] in [model].
	 *
	 * @param PuppetModel model The model.
	 * @param String      id    The drawable.
	 * @return DeformerId? Its parent.
	 */
	private fun parentOf(model: PuppetModel, id: String): DeformerId? = model.drawables.first { drawable -> drawable.id == DrawableId(id) }.parentDeformerId

	/**
	 * The world box of [shapes] grown by [marginPercent] of its larger side: its top-left and bottom-right corners.
	 *
	 * @param List<FloatArray> shapes The world shapes, interleaved x, z.
	 * @param Float marginPercent The margin.
	 * @return FloatArray The corners, left, top, right, bottom.
	 */
	private fun grownBox(shapes: List<FloatArray>, marginPercent: Float): FloatArray {
		val xs = shapes.flatMap { shape -> shape.filterIndexed { index, _ -> index % 2 == 0 } }
		val zs = shapes.flatMap { shape -> shape.filterIndexed { index, _ -> index % 2 == 1 } }
		val margin = maxOf(xs.max() - xs.min(), zs.max() - zs.min()) * marginPercent / 100f

		return floatArrayOf(xs.min() - margin, zs.max() + margin, xs.max() + margin, zs.min() - margin)
	}

	/**
	 * Asserts a lattice's first point is a box's top-left corner and its last point the bottom-right one.
	 *
	 * @param FloatArray box     The box, left, top, right, bottom.
	 * @param FloatArray lattice The world lattice.
	 */
	private fun assertSpans(box: FloatArray, lattice: FloatArray) {
		assertSamePlace(box, floatArrayOf(lattice[0], lattice[1], lattice[lattice.size - 2], lattice[lattice.size - 1]), "the lattice's corners")
	}

	/**
	 * Asserts two world shapes agree within a hundredth of a pixel.
	 *
	 * @param FloatArray expected The shape before.
	 * @param FloatArray actual   The shape after.
	 * @param String     label    The case.
	 */
	private fun assertSamePlace(expected: FloatArray, actual: FloatArray, label: String) {
		assertEquals(expected.size, actual.size, label)

		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= 0.01f, "$label: component $componentIndex moved from ${expected[componentIndex]} to ${actual[componentIndex]}")
		}
	}

	/**
	 * A session over [model] with [targets] selected, the last one active.
	 *
	 * @param PuppetModel model The model.
	 * @param List<SelectionTarget> targets The selection.
	 * @return EditorSession The session.
	 */
	private fun sessionSelecting(model: PuppetModel, vararg targets: SelectionTarget): EditorSession =
		EditorSession(model).apply {
			setSelection(Selection(targets.toSet(), targets.lastOrNull()))
		}

	@Test
	fun aWarpIsBornAroundTheSelectionWithAMarginAndOneKeyform() {
		val before = model()
		val creation = assertNotNull(warpCreationOf(before, setOf(SelectionTarget.Drawable(DrawableId("left")), SelectionTarget.Drawable(DrawableId("right"))), SelectionTarget.Drawable(DrawableId("right")), WarpBirth()))

		assertEquals(createdId, creation.warpId)
		val warp = warpOf(creation.model)
		assertEquals("Warp", warp.name)
		assertNull(warp.parent, "root art wraps under the root")
		assertEquals(bodyId, warp.partId, "the active item's part")
		assertEquals(5, warp.rows)
		assertEquals(5, warp.columns)
		assertTrue(warp.isQuadTransform)
		val grid = assertNotNull(warp.geometryGrid)
		assertTrue(grid.axes.isEmpty(), "born with no axis")
		assertEquals(1, grid.cells.size, "and one keyform")
		assertSpans(grownBox(listOf(worldAt(before, "left"), worldAt(before, "right")), 10f), latticeAt(creation.model))
		assertEquals(listOf(DrawableId("left"), DrawableId("right")), creation.wrappedDrawables)
		assertEquals(createdId, parentOf(creation.model, "left"))
		assertEquals(createdId, parentOf(creation.model, "right"))
		assertSamePlace(worldAt(before, "left"), worldAt(creation.model, "left"), "left at rest")
		assertSamePlace(worldAt(before, "right"), worldAt(creation.model, "right"), "right at rest")
		assertSamePlace(worldAt(before, "right", swung), worldAt(creation.model, "right", swung), "right swung")
		assertEquals(0, creation.rehome.unconvertedCount)
	}

	@Test
	fun mixedParentsGoUnderTheirNearestCommonAncestor() {
		val before = model()
		val nested = assertNotNull(warpCreationOf(before, setOf(SelectionTarget.Drawable(DrawableId("caged")), SelectionTarget.Drawable(DrawableId("deep"))), null, WarpBirth()))

		assertEquals(cageId, warpOf(nested.model).parent, "both sit under the cage")
		assertEquals(createdId, parentOf(nested.model, "deep"), "the deeper drawable comes up under the new warp")
		assertSamePlace(worldAt(before, "deep"), worldAt(nested.model, "deep"), "deep at rest")
		assertSamePlace(worldAt(before, "caged"), worldAt(nested.model, "caged"), "caged at rest")
		assertSamePlace(worldAt(before, "caged", swung), worldAt(nested.model, "caged", swung), "caged swung")
		assertSpans(grownBox(listOf(worldAt(before, "caged"), worldAt(before, "deep")), 10f), latticeAt(nested.model))

		val rooted = assertNotNull(warpCreationOf(before, setOf(SelectionTarget.Drawable(DrawableId("caged")), SelectionTarget.Drawable(DrawableId("left"))), null, WarpBirth()))

		assertNull(warpOf(rooted.model).parent, "the cage and the root share only the root")
		assertSamePlace(worldAt(before, "caged", swung), worldAt(rooted.model, "caged", swung), "caged leaves its warp in place")
	}

	@Test
	fun aWrappedRotationKeepsItsPivotAndItsTurn() {
		val before = model()
		val creation = assertNotNull(warpCreationOf(before, setOf(SelectionTarget.Deformer(spinId), SelectionTarget.Drawable(DrawableId("caged"))), null, WarpBirth()))

		assertEquals(createdId, creation.model.deformerById[spinId]?.parent)
		val throughSpin = { model: PuppetModel, pose: Map<ParameterId, Float> -> assertNotNull(parentSpaceAt(model, spinId, pose)).localToWorld(floatArrayOf(10f, 0f)) }
		assertSamePlace(throughSpin(before, emptyMap()), throughSpin(creation.model, emptyMap()), "the rotation at rest")
		assertSamePlace(throughSpin(before, swung), throughSpin(creation.model, swung), "the rotation swung")
	}

	@Test
	fun aSelectedDeformerCarriesWhatItHoldsAndPartsWrapNothing() {
		val before = model()
		val everything = setOf(SelectionTarget.Deformer(cageId), SelectionTarget.Drawable(DrawableId("caged")), SelectionTarget.Drawable(DrawableId("deep")), SelectionTarget.Part(bodyId))

		assertEquals(emptyList<DrawableId>() to listOf(cageId), wrappableTargets(before, everything), "the cage carries its art")
		assertNull(warpCreationOf(before, setOf(SelectionTarget.Part(bodyId)), null, WarpBirth()), "a part alone wraps nothing")

		val creation = assertNotNull(warpCreationOf(before, everything, SelectionTarget.Deformer(cageId), WarpBirth()))

		assertEquals(createdId, creation.model.deformerById[cageId]?.parent)
		assertEquals(listOf(createdId, cageId), creation.model.deformers.take(2).map { deformer -> deformer.id }, "the new warp lands before the first deformer it wraps")
		assertSamePlace(latticeAt(before, cageId), latticeAt(creation.model, cageId), "the cage")
		assertSamePlace(worldAt(before, "caged", swung), worldAt(creation.model, "caged", swung), "its art")
	}

	@Test
	fun aTakenNameIsSuffixed() {
		val first = assertNotNull(warpCreationOf(model(), setOf(SelectionTarget.Drawable(DrawableId("left"))), null, WarpBirth()))
		val second = assertNotNull(warpCreationOf(first.model, setOf(SelectionTarget.Drawable(DrawableId("right"))), null, WarpBirth()))

		assertEquals(DeformerId("Warp.001"), second.warpId)
		assertEquals("Warp.001", warpOf(second.model, second.warpId).name)
	}

	@Test
	fun creatingIsOneStepThatSelectsTheWarpAndTheStripReruns() {
		val before = model()
		val session = sessionSelecting(before, SelectionTarget.Drawable(DrawableId("left")), SelectionTarget.Drawable(DrawableId("right")))
		val stepsBefore = session.historyView.value.steps.size

		session.createWarpAdjustable("area")

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.deformer.create", session.historyView.value.steps.last().labelKey)
		assertEquals(Selection(setOf(SelectionTarget.Deformer(createdId)), SelectionTarget.Deformer(createdId)), session.selection.value, "the new warp alone is selected")
		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals("area", record.areaId)
		assertEquals(listOf(WarpCreationParameterKeys.ROWS, WarpCreationParameterKeys.COLUMNS, WarpCreationParameterKeys.MARGIN, WarpCreationParameterKeys.QUAD), record.parameters.map { parameter -> parameter.key })
		val rows = record.parameters.first { parameter -> parameter.key == WarpCreationParameterKeys.ROWS } as OperatorParameter.IntParameter

		session.adjustLastOperation(record.parameters.withParameter(rows.key, rows.copy(value = 3)), adjustedKey = rows.key)

		assertEquals(3, warpOf(session.model.value).rows)
		assertEquals(5, warpOf(session.model.value).columns)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the rerun replaces the step")
		assertEquals(SelectionTarget.Deformer(createdId), session.selection.value.active, "and the same warp stays selected")
		val margined = assertNotNull(session.adjustableOperation.value)
		val margin = margined.parameters.first { parameter -> parameter.key == WarpCreationParameterKeys.MARGIN } as OperatorParameter.FloatParameter

		session.adjustLastOperation(margined.parameters.withParameter(margin.key, margin.copy(value = 0f)), adjustedKey = margin.key)

		assertSpans(grownBox(listOf(worldAt(before, "left"), worldAt(before, "right")), 0f), latticeAt(session.model.value))
		assertEquals(3, warpOf(session.model.value).rows, "an earlier adjustment holds")
		val mapped = assertNotNull(session.adjustableOperation.value)
		val quad = mapped.parameters.first { parameter -> parameter.key == WarpCreationParameterKeys.QUAD } as OperatorParameter.BooleanParameter

		session.adjustLastOperation(mapped.parameters.withParameter(quad.key, quad.copy(value = false)), adjustedKey = quad.key)

		assertFalse(warpOf(session.model.value).isQuadTransform)
		assertSamePlace(worldAt(before, "right", swung), worldAt(session.model.value, "right", swung), "every rerun keeps the art in place")
		session.undo()
		assertNull(session.model.value.deformerById[createdId], "one undo removes the warp")
		assertNull(parentOf(session.model.value, "right"))
	}

	@Test
	fun aTargetWithoutBilinearWarpsOffersNoMappingChoice() {
		val session = sessionSelecting(model(RuntimeTarget.Cubism30), SelectionTarget.Drawable(DrawableId("left")))

		session.createWarpAdjustable("area")

		assertFalse(warpOf(session.model.value).isQuadTransform, "triangle split")
		val record = assertNotNull(session.adjustableOperation.value)
		assertTrue(record.parameters.none { parameter -> parameter.key == WarpCreationParameterKeys.QUAD }, "and no Bilinear row")
	}

	@Test
	fun onlyObjectModeCreates() {
		val session = sessionSelecting(model(), SelectionTarget.Drawable(DrawableId("left")))

		assertTrue(session.canCreateWarp())
		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, session.mode.value)
		assertFalse(session.canCreateWarp(), "Edit mode")
		val inEdit = session.model.value
		session.createWarpAdjustable("area")
		assertSame(inEdit, session.model.value, "Edit mode creates nothing")

		session.setMode(EditorMode.Object)
		session.setMode(EditorMode.Pose)
		assertEquals(EditorMode.Pose, session.mode.value)
		assertFalse(session.canCreateWarp(), "Pose mode")
		session.createWarpAdjustable("area")
		assertSame(inEdit, session.model.value, "Pose mode creates nothing")

		session.setMode(EditorMode.Object)
		session.setSelection(Selection(setOf(SelectionTarget.Part(bodyId)), SelectionTarget.Part(bodyId)))
		assertFalse(session.canCreateWarp(), "a part alone")
	}
}