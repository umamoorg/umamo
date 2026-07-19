package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the parameter document edits: create mints a fresh animatable axis at the top and returns its id,
 * rename changes the display name (id-stable) with blank / unchanged no-ops, and delete removes the axis
 * from the parameters list, the panel tree, any link, and every keyform grid - collapsing a grid to the
 * deleted parameter's default slice or to null when it was the last axis. Also pins the generic
 * withAxisCollapsed coordinate math, and that each edit undoes.
 */
class ParameterCrudEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	/** An animatable parameter over -1..1 with the given [default]. */
	private fun parameter(id: ParameterId, default: Float = 0f): Parameter =
		Parameter(id, id.raw, min = -1f, max = 1f, default = default)

	/** A drawable-less model holding [parameters], [links], [tree], and optional [drawables]. */
	private fun model(
		parameters: List<Parameter>,
		links: List<ParameterLink> = emptyList(),
		tree: List<ParameterNode> = emptyList(),
		drawables: List<Drawable> = emptyList(),
	): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			parameterLinks = links,
			parameterTree = tree,
		)

	/**
	 * A drawable keyed on angleX (keys -1, 0, 1) then angleY (keys 0, 1) - a 3x2 grid whose every cell's
	 * MeshForm carries a distinctive value (xIndex*10 + yIndex), so a collapse can be traced by payload.
	 */
	private fun keyedDrawable(): Drawable {
		val axes = listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f)))
		val cells =
			buildList {
				for (xIndex in 0..2) {
					for (yIndex in 0..1) {
						add(KeyformCell(intArrayOf(xIndex, yIndex), MeshForm(floatArrayOf(xIndex * 10f + yIndex))))
					}
				}
			}
		return Drawable(
			id = DrawableId("d"),
			name = "d",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms = KeyformGrid(axes, cells),
		)
	}

	/** Create appends the axis, prepends its leaf at the top, and returns the minted id. */
	@Test
	fun createAppendsAxisAndPrependsLeaf() {
		val session = EditorSession(model(listOf(parameter(angleX))))
		val id = session.createParameter("New Parameter")

		assertEquals(ParameterId("Param"), id, "the first minted id is Param")
		val created = session.model.value.parameters.first { it.id == id }
		assertEquals("New Parameter", created.name)
		assertTrue(created.min < created.max, "the new parameter is animatable")
		assertEquals(ParameterNode.Param(id), session.model.value.parameterTree.first(), "the leaf is at the top")
		assertEquals(ParameterKind.NORMAL, created.kind, "the default kind is a key-form parameter")

		session.undo()
		assertTrue(session.model.value.parameters.none { it.id == id }, "undo removes the new parameter")
	}

	/** Create carries the requested kind (a blend-shape parameter authored with no keys yet). */
	@Test
	fun createUsesRequestedKind() {
		val session = EditorSession(model(listOf(parameter(angleX))))

		val normalId = session.createParameter("A", ParameterKind.NORMAL)
		val blendId = session.createParameter("B", ParameterKind.BLEND_SHAPE)

		assertEquals(
			ParameterKind.NORMAL,
			session.model.value.parameters.first { it.id == normalId }.kind,
			"explicit NORMAL stays a key-form parameter",
		)
		val blend = session.model.value.parameters.first { it.id == blendId }
		assertEquals(ParameterKind.BLEND_SHAPE, blend.kind, "BLEND_SHAPE is carried through to the model")
		assertTrue(session.model.value.drawables.all { it.blendShapes.isEmpty() }, "a fresh blend parameter keys nothing yet")
	}

	/** The minted parameter id skips ids already present. */
	@Test
	fun freshIdAvoidsCollision() {
		val existing = model(listOf(parameter(ParameterId("Param")), parameter(ParameterId("Param2"))))
		assertEquals(ParameterId("Param3"), existing.freshParameterId())
	}

	/** Rename changes the display name; blank and unchanged names are no-ops. */
	@Test
	fun renameChangesNameBlankAndUnchangedAreNoOps() {
		val start = model(listOf(parameter(angleX)))
		val renamed = start.withParameterRenamed(angleX, "  Eyes  ")
		assertEquals("Eyes", renamed.parameters.single().name)

		assertSame(start, start.withParameterRenamed(angleX, "   "), "a blank rename is a no-op")
		assertSame(renamed, renamed.withParameterRenamed(angleX, "Eyes"), "an unchanged rename is a no-op")
	}

	/** Delete scrubs the parameters list, the tree leaf, and any link containing it. */
	@Test
	fun deleteRemovesFromParametersTreeAndLink() {
		val tree = listOf(ParameterNode.Param(angleX), ParameterNode.Param(angleY))
		val links = listOf(ParameterLink(angleX, angleY))
		val session = EditorSession(model(listOf(parameter(angleX), parameter(angleY)), links, tree))
		session.deleteParameter(angleX)

		val after = session.model.value
		assertTrue(after.parameters.none { it.id == angleX }, "the axis is gone")
		assertTrue(after.parameterTree.none { it is ParameterNode.Param && it.id == angleX }, "the leaf is gone")
		assertTrue(after.parameterLinks.isEmpty(), "the link that contained it is gone")

		session.undo()
		assertTrue(session.model.value.parameters.any { it.id == angleX }, "undo restores the parameter")
		assertEquals(1, session.model.value.parameterLinks.size, "undo restores the link")
	}

	/** Delete collapses a multi-axis grid to the deleted axis's default slice, re-projecting coordinates. */
	@Test
	fun deleteCollapsesGridToDefaultSlice() {
		// default 0 on angleX -> keeps key index 1 (the value-0 key) of [-1, 0, 1].
		val session = EditorSession(model(listOf(parameter(angleX, default = 0f), parameter(angleY)), drawables = listOf(keyedDrawable())))
		session.deleteParameter(angleX)

		val grid = session.model.value.drawables.single().keyforms!!
		assertEquals(listOf(angleY), grid.axes.map { it.parameterId }, "only angleY remains")
		assertEquals(2, grid.cells.size, "the 3x2 grid collapsed to the 2 cells of the kept slice")
		// The surviving forms are the angleX-index-1 slice: xIndex*10 + yIndex = 10 and 11.
		val byCoordinate = grid.cells.associate { it.coordinate.single() to it.form.positionDeltas.single() }
		assertEquals(10f, byCoordinate[0], "cell (angleY key 0) kept the default-slice form")
		assertEquals(11f, byCoordinate[1], "cell (angleY key 1) kept the default-slice form")
	}

	/** Deleting the sole axis of a grid leaves the entity unkeyed (null grid). */
	@Test
	fun deleteSoleAxisGridBecomesNull() {
		val singleAxis =
			keyedDrawable().copy(
				keyforms =
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(0f))),
							KeyformCell(intArrayOf(1), MeshForm(floatArrayOf(1f))),
							KeyformCell(intArrayOf(2), MeshForm(floatArrayOf(2f))),
						),
					),
			)
		val session = EditorSession(model(listOf(parameter(angleX)), drawables = listOf(singleAxis)))
		session.deleteParameter(angleX)
		assertNull(session.model.value.drawables.single().keyforms, "the last-axis grid becomes null")
	}

	/** Deleting a parameter not present in a grid leaves that grid's entity untouched by identity. */
	@Test
	fun deleteUnkeyedParamLeavesGridsUntouched() {
		val drawable = keyedDrawable()
		// angleZ exists as a parameter but is not an axis of the drawable's grid.
		val angleZ = ParameterId("ParamAngleZ")
		val start = model(listOf(parameter(angleX), parameter(angleY), parameter(angleZ)), drawables = listOf(drawable))
		val after = start.withParameterDeleted(angleZ)
		assertSame(drawable.keyforms, after.drawables.single().keyforms, "an untouched grid keeps its instance")
	}

	/** Deleting an unknown parameter is a no-op (same instance, no undo step). */
	@Test
	fun deleteUnknownIsNoOp() {
		val start = model(listOf(parameter(angleX)))
		assertSame(start, start.withParameterDeleted(ParameterId("ParamNope")))
	}

	/** withAxisCollapsed drops the named axis and re-projects each surviving cell's coordinate. */
	@Test
	fun withAxisCollapsedReprojectsCoordinates() {
		val axes = listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f)))
		val cells =
			listOf(
				KeyformCell(intArrayOf(0, 0), MeshForm(floatArrayOf(0f))),
				KeyformCell(intArrayOf(0, 1), MeshForm(floatArrayOf(1f))),
				KeyformCell(intArrayOf(1, 0), MeshForm(floatArrayOf(2f))),
				KeyformCell(intArrayOf(1, 1), MeshForm(floatArrayOf(3f))),
			)
		// Collapse angleX keeping the value-0 slice (key index 0): surviving cells are (0,0) and (0,1).
		val collapsed = KeyformGrid(axes, cells).withAxisCollapsed(angleX, keepKeyValue = 0f)!!
		assertEquals(listOf(angleY), collapsed.axes.map { it.parameterId })
		assertEquals(listOf(intArrayOf(0).toList(), intArrayOf(1).toList()), collapsed.cells.map { it.coordinate.toList() })
		assertEquals(listOf(0f, 1f), collapsed.cells.map { it.form.positionDeltas.single() })
	}

	/** withAxisCollapsed returns the same instance when the grid has no such axis. */
	@Test
	fun withAxisCollapsedAbsentAxisReturnsSame() {
		val grid = KeyformGrid(listOf(KeyformAxis(angleY, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(0f)))))
		assertSame(grid, grid.withAxisCollapsed(angleX, keepKeyValue = 0f))
	}
}
