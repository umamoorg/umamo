package org.umamo.ui.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.OperatorParameter
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.mesh.LatticeResizeParameterKeys
import org.umamo.edit.withParameter
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
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
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.assertArrayNear
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_HEAD
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_PARAMETER
import org.umamo.ui.viewport.viewport2d.latticeRigModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the lattice rewrites at rest: a quad-mode refinement by whole multiples keeps the map exactly and touches no
 * child, any other size or a triangle-split one resamples approximately and keeps the children where they rest, nested
 * warps land parents first, the Quad toggle keeps the children too, and the session commits each as one step - the
 * resizes adjustable on the operation strip, Edit mode dropping only the resized lattice's points, Pose mode refused.
 */
class LatticeResizeTest {
	private val inner = DrawableId("inner")

	// How close a kept world point must be, in world units: well inside the keep-in-place landing tolerance.
	private val tolerance = 1e-3f

	/**
	 * The lattice rig with the body warp bent into a 2 x 2 lattice (its center point pulled off the straight grid, so
	 * no other grid reproduces its map), a blend shape on it, and a quad of art inside it, in its normalized space.
	 *
	 * @param Boolean quad Whether the body maps bilinearly.
	 * @return PuppetModel The model.
	 */
	private fun model(quad: Boolean = true): PuppetModel {
		val base = latticeRigModel()
		val bent = floatArrayOf(0f, 0f, 20f, 0f, 40f, 0f, 0f, 20f, 26f, 24f, 40f, 20f, 0f, 40f, 20f, 40f, 40f, 40f)
		val body =
			(base.deformerById[LATTICE_RIG_BODY] as Deformer.Warp).copy(
				rows = 2,
				columns = 2,
				isQuadTransform = quad,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(LATTICE_RIG_PARAMETER, floatArrayOf(-1f, 1f))),
						listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(bent)), KeyformCell(intArrayOf(1), WarpLatticeForm(bent))),
					),
				blendShapes = listOf(BlendShapeBinding(LATTICE_RIG_PARAMETER, floatArrayOf(0f, 1f), 0, listOf(null, WarpForm(FloatArray(bent.size) { componentIndex -> bent[componentIndex] + 2f })))),
			)
		val art =
			Drawable(
				id = inner,
				name = "inner",
				parentDeformerId = LATTICE_RIG_BODY,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(floatArrayOf(4f, 4f, 16f, 4f, 16f, 16f, 4f, 16f), floatArrayOf(0.1f, 0.1f, 0.6f, 0.1f, 0.6f, 0.7f, 0.1f, 0.7f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)

		return base.copy(
			deformers = base.deformers.map { deformer -> if (deformer.id == LATTICE_RIG_BODY) body else deformer },
			drawables = base.drawables + art,
			rootChildren = base.rootChildren + OrgChild.Drawable(inner),
		)
	}

	/**
	 * The inner art's rest world positions.
	 *
	 * @param PuppetModel model The model.
	 * @return FloatArray The world positions.
	 */
	private fun innerWorld(model: PuppetModel): FloatArray = captureDrawableWorlds(model, emptyMap(), listOf(inner)).single().world

	/**
	 * A warp's rest lattice in world space.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId deformerId The warp.
	 * @return FloatArray The world points.
	 */
	private fun latticeWorld(model: PuppetModel, deformerId: DeformerId): FloatArray = assertNotNull(warpLatticeAt(model, emptyMap(), deformerId)).world

	/**
	 * A warp's four rest corners in world space, top-left, top-right, bottom-left, bottom-right: what any resolution of
	 * the same map shares.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId deformerId The warp.
	 * @return FloatArray The corners, interleaved x, z.
	 */
	private fun corners(model: PuppetModel, deformerId: DeformerId): FloatArray {
		val warp = model.deformerById[deformerId] as Deformer.Warp
		val world = latticeWorld(model, deformerId)
		val pointsPerRow = warp.columns + 1

		return listOf(0, warp.columns, warp.rows * pointsPerRow, warp.rows * pointsPerRow + warp.columns)
			.flatMap { pointIndex -> listOf(world[pointIndex * 2], world[pointIndex * 2 + 1]) }
			.toFloatArray()
	}

	/**
	 * The body warp of [model].
	 *
	 * @param PuppetModel model The model.
	 * @return Deformer.Warp The warp.
	 */
	private fun body(model: PuppetModel): Deformer.Warp = model.deformerById[LATTICE_RIG_BODY] as Deformer.Warp

	@Test
	fun anExactRefinementKeepsTheMapAndTouchesNoChild() {
		val before = model()
		val resize = latticeResizeOf(before, mapOf(LATTICE_RIG_BODY to LatticeSize(4, 4)))

		assertEquals(4, body(resize.model).rows)
		assertFalse(resize.approximate)
		assertEquals(0, resize.unconvertedCount)
		assertSame(before.drawables.single { drawable -> drawable.id == inner }, resize.model.drawables.single { drawable -> drawable.id == inner }, "the art keeps its numbers")
		assertSame(before.deformerById[LATTICE_RIG_HEAD], resize.model.deformerById[LATTICE_RIG_HEAD], "the child warp keeps its numbers")
		assertArrayNear(innerWorld(before), innerWorld(resize.model), tolerance, "and rests where it rested")
		assertEquals(25 * 2, assertNotNull(body(resize.model).blendShapes.single().forms[1]).controlPoints.size, "the blend shape is resampled with the grid")
	}

	@Test
	fun anInexactSizeKeepsTheChildrenWhereTheyRest() {
		val before = model()
		val resize = latticeResizeOf(before, mapOf(LATTICE_RIG_BODY to LatticeSize(3, 3)))

		assertTrue(resize.approximate)
		assertEquals(0, resize.unconvertedCount)
		assertArrayNear(innerWorld(before), innerWorld(resize.model), tolerance, "the art rests where it rested")
		assertArrayNear(latticeWorld(before, LATTICE_RIG_HEAD), latticeWorld(resize.model, LATTICE_RIG_HEAD), tolerance, "the child warp too")
	}

	@Test
	fun aTriangleSplitRefinementIsApproximate() {
		val before = model(quad = false)
		val resize = latticeResizeOf(before, mapOf(LATTICE_RIG_BODY to LatticeSize(4, 4)))

		assertTrue(resize.approximate)
		assertArrayNear(innerWorld(before), innerWorld(resize.model), tolerance, "the art rests where it rested")
	}

	@Test
	fun nestedWarpsLandParentsFirst() {
		val before = model()
		// Listed child first: the resize takes parents first whatever the order it is handed.
		val resize = latticeResizeOf(before, linkedMapOf(LATTICE_RIG_HEAD to LatticeSize(3, 2), LATTICE_RIG_BODY to LatticeSize(3, 3)))

		assertEquals(3, (resize.model.deformerById[LATTICE_RIG_HEAD] as Deformer.Warp).rows)
		assertArrayNear(corners(before, LATTICE_RIG_HEAD), corners(resize.model, LATTICE_RIG_HEAD), tolerance, "the child warp's corners rest where they rested")
		assertArrayNear(innerWorld(before), innerWorld(resize.model), tolerance, "and so does the art")
	}

	@Test
	fun theQuadToggleKeepsTheChildrenWhereTheyRest() {
		val before = model()
		val toggled = quadToggledOf(before, LATTICE_RIG_BODY, false)

		assertFalse(body(toggled.model).isQuadTransform)
		assertFalse(toggled.approximate, "a toggle resamples nothing")
		assertArrayNear(innerWorld(before), innerWorld(toggled.model), tolerance, "the art rests where it rested")
		assertArrayNear(latticeWorld(before, LATTICE_RIG_HEAD), latticeWorld(toggled.model, LATTICE_RIG_HEAD), tolerance, "the child warp too")
		assertSame(before, quadToggledOf(before, LATTICE_RIG_BODY, true).model, "the warp's own mapping changes nothing")
	}

	@Test
	fun aSizeItHasOrOutOfRangeChangesNothing() {
		val before = model()

		for (size in listOf(LatticeSize(2, 2), LatticeSize(0, 2), LatticeSize(2, 101))) {
			assertSame(before, latticeResizeOf(before, mapOf(LATTICE_RIG_BODY to size)).model, "$size")
		}
	}

	@Test
	fun aResizeIsOneStepTheStripAdjusts() {
		val session = EditorSession(model())
		val stepsBefore = session.historyView.value.steps.size

		session.setLatticeSizeAdjustable(LATTICE_RIG_BODY, 3, 3, "area")

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.lattice.resize", session.historyView.value.steps.last().labelKey)
		assertEquals("notice.lattice.resampled", session.notice.value?.messageKey, "an inexact size says so")
		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals("area", record.areaId)
		val rows = record.parameters.first { parameter -> parameter.key == LatticeResizeParameterKeys.ROWS } as OperatorParameter.IntParameter
		assertEquals(LatticeResizeParameterKeys.COLUMNS, rows.ratioPartnerKey, "rows and columns pair")

		session.adjustLastOperation(record.parameters.withParameter(rows.key, rows.copy(value = 4)), adjustedKey = rows.key)

		assertEquals(4, body(session.model.value).rows)
		assertEquals(3, body(session.model.value).columns)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the rerun replaces the step")
		session.undo()
		assertEquals(2, body(session.model.value).rows, "one undo restores the lattice")
	}

	@Test
	fun editModeDropsOnlyTheResizedLatticesPoints() {
		val session = EditorSession(model())
		val warps = listOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD), SelectionTarget.Deformer(LATTICE_RIG_BODY))
		session.setSelection(Selection(warps.toSet(), warps.last()))
		session.setMode(EditorMode.Edit)
		val point = MeshElement.Vertex(0)
		session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = mapOf(ElementOwner.Deformer(LATTICE_RIG_BODY) to setOf(point), ElementOwner.Deformer(LATTICE_RIG_HEAD) to setOf(point))))

		session.setLatticeSizeAdjustable(LATTICE_RIG_BODY, 4, 4, null)

		assertEquals(4, body(session.model.value).rows)
		assertTrue(session.meshSelection.value.elementsOf(ElementOwner.Deformer(LATTICE_RIG_BODY)).isEmpty(), "the renumbered lattice's points go")
		assertEquals(setOf<MeshElement>(point), session.meshSelection.value.elementsOf(ElementOwner.Deformer(LATTICE_RIG_HEAD)), "another lattice keeps its points")
	}

	@Test
	fun subdivideSplitsEverySelectedWarp() {
		val session = EditorSession(model())
		val warps = listOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD), SelectionTarget.Deformer(LATTICE_RIG_BODY))
		session.setSelection(Selection(warps.toSet(), warps.last()))

		session.subdivideLatticesAdjustable(session.subdivisionTargets(), "area")

		assertEquals(4, body(session.model.value).rows)
		assertEquals(2, (session.model.value.deformerById[LATTICE_RIG_HEAD] as Deformer.Warp).columns)
		assertEquals("change.lattice.subdivide", session.historyView.value.steps.last().labelKey)
		val record = assertNotNull(session.adjustableOperation.value)
		val splits = record.parameters.first { parameter -> parameter.key == LatticeResizeParameterKeys.ROW_SPLITS } as OperatorParameter.IntParameter

		session.adjustLastOperation(record.parameters.withParameter(splits.key, splits.copy(value = 3)), adjustedKey = splits.key)

		assertEquals(6, body(session.model.value).rows, "the rerun splits the base again")
		assertEquals(4, body(session.model.value).columns)
	}

	@Test
	fun theQuadToggleIsOneStep() {
		val before = model()
		val session = EditorSession(before)
		val stepsBefore = session.historyView.value.steps.size

		session.setWarpQuadTransform(LATTICE_RIG_BODY, false)

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.deformer.quadTransform", session.historyView.value.steps.last().labelKey)
		assertArrayNear(innerWorld(before), innerWorld(session.model.value), tolerance, "the art rests where it rested")
	}

	@Test
	fun poseModeRewritesNothing() {
		val session = EditorSession(model())
		session.setSelection(Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)), SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.setMode(EditorMode.Pose)
		val modelBefore = session.model.value

		session.setLatticeSizeAdjustable(LATTICE_RIG_BODY, 3, 3, null)
		session.subdivideLatticesAdjustable(listOf(LATTICE_RIG_BODY), null)
		session.setWarpQuadTransform(LATTICE_RIG_BODY, false)

		assertSame(modelBefore, session.model.value)
		assertTrue(session.subdivisionTargets().isEmpty())
	}
}