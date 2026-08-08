package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.withDerivedRenderRoot
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
						add(KeyformCell(intArrayOf(xIndex, yIndex), MeshDeltaForm(floatArrayOf(xIndex * 10f + yIndex))))
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
			geometryGrid = KeyformGrid(axes, cells),
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

		val grid = session.model.value.drawables.single().geometryGrid!!
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
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f))),
							KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(1f))),
							KeyformCell(intArrayOf(2), MeshDeltaForm(floatArrayOf(2f))),
						),
					),
			)
		val session = EditorSession(model(listOf(parameter(angleX)), drawables = listOf(singleAxis)))
		session.deleteParameter(angleX)
		assertNull(session.model.value.drawables.single().geometryGrid, "the last-axis grid becomes null")
	}

	/** Deleting a parameter not present in a grid leaves that grid's entity untouched by identity. */
	@Test
	fun deleteUnkeyedParamLeavesGridsUntouched() {
		val drawable = keyedDrawable()
		// angleZ exists as a parameter but is not an axis of the drawable's grid.
		val angleZ = ParameterId("ParamAngleZ")
		val start = model(listOf(parameter(angleX), parameter(angleY), parameter(angleZ)), drawables = listOf(drawable))
		val after = start.withParameterDeleted(angleZ)
		assertSame(drawable.geometryGrid, after.drawables.single().geometryGrid, "an untouched grid keeps its instance")
	}

	/** Deleting an unknown parameter is a no-op (same instance, no undo step). */
	@Test
	fun deleteUnknownIsNoOp() {
		val start = model(listOf(parameter(angleX)))
		assertSame(start, start.withParameterDeleted(ParameterId("ParamNope")))
	}

	/** A single-axis channel track over angleX with one [ChannelValue.Scalar] per key of [-1, 0, 1]. */
	private fun scalarTrack(vararg values: Float): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f))),
			values.mapIndexed { keyIndex, value -> KeyformCell<ChannelValue>(intArrayOf(keyIndex), ChannelValue.Scalar(value)) },
		)

	/**
	 * Dropping a channel track whose only axis is the deleted parameter lifts the kept slice's value into
	 * the owner's static, so the neutral look survives the drop instead of snapping back to the static.
	 */
	@Test
	fun deleteLiftsCollapsedOpacityTrackIntoStatic() {
		val drawable =
			keyedDrawable().copy(
				geometryGrid = null,
				opacity = 1f,
				channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to scalarTrack(0.5f, 0.3f, 0.9f))),
			)
		// angleX's default is 0, whose key is index 1 - the neutral look renders opacity 0.3.
		val start = model(listOf(parameter(angleX, default = 0f)), drawables = listOf(drawable))
		val after = start.withParameterDeleted(angleX)

		val scrubbed = after.drawables.single()
		assertNull(scrubbed.channelGrids[FormChannel.OPACITY], "the sole-axis track is dropped")
		assertEquals(0.3f, scrubbed.opacity, "the kept slice's value took over the static")
	}

	/** The same lift for a glue: the kept slice's intensity replaces the full-weld static. */
	@Test
	fun deleteLiftsGlueIntensityIntoStatic() {
		val glue =
			Glue(
				meshA = DrawableId("a"),
				meshB = DrawableId("b"),
				pairs = emptyList(),
				channelGrids = ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to scalarTrack(0.2f, 0.4f, 0.8f))),
			)
		val start = model(listOf(parameter(angleX, default = 0f))).copy(glues = listOf(glue))
		val after = start.withParameterDeleted(angleX)

		val scrubbed = after.glues.single()
		assertNull(scrubbed.channelGrids[FormChannel.GLUE_INTENSITY], "the sole-axis track is dropped")
		assertEquals(0.4f, scrubbed.intensity, "the kept slice's intensity took over the static")
	}

	/**
	 * A fractional part draw order cannot lift into the Int static slot, so it stays behind as a zero-axis
	 * constant track; an integral one lifts.
	 */
	@Test
	fun deletePartDrawOrderLiftsIntegralKeepsFractionalAsConstant() {
		val fractionalPart =
			Part(
				id = PartId("pFractional"),
				name = "pFractional",
				children = emptyList(),
				groupMode = PartGroupMode.Grouped,
				channelGrids = ChannelGrids(mapOf(FormChannel.DRAW_ORDER to scalarTrack(500f, 550.5f, 600f))),
			)
		val integralPart =
			Part(
				id = PartId("pIntegral"),
				name = "pIntegral",
				children = emptyList(),
				groupMode = PartGroupMode.Grouped,
				channelGrids = ChannelGrids(mapOf(FormChannel.DRAW_ORDER to scalarTrack(500f, 600f, 700f))),
			)
		val start =
			model(listOf(parameter(angleX, default = 0f)))
				.copy(parts = listOf(fractionalPart, integralPart))
		val after = start.withParameterDeleted(angleX)

		val fractional = after.parts.first { part -> part.id == PartId("pFractional") }
		val constantTrack = fractional.channelGrids[FormChannel.DRAW_ORDER]!!
		assertTrue(constantTrack.axes.isEmpty(), "the fractional value stays as a zero-axis constant track")
		assertEquals(550.5f, (constantTrack.cells.single().form as ChannelValue.Scalar).value)
		assertEquals(500, fractional.drawOrder, "the Int static keeps its value rather than rounding")

		val integral = after.parts.first { part -> part.id == PartId("pIntegral") }
		assertNull(integral.channelGrids[FormChannel.DRAW_ORDER], "the integral track is dropped")
		assertEquals(600, integral.drawOrder, "the kept slice's value lifted into the Int static")
	}

	/**
	 * The render root's per-group copies of the part tracks are re-derived after the delete, so the
	 * evaluator (which samples renderRoot, not the parts list) cannot keep blending a ghost axis.
	 */
	@Test
	fun deleteRederivesRenderRootPartTracks() {
		val twoAxisTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f))),
				buildList {
					for (xIndex in 0..2) {
						for (yIndex in 0..1) {
							add(KeyformCell<ChannelValue>(intArrayOf(xIndex, yIndex), ChannelValue.Scalar(500f + xIndex * 10f + yIndex)))
						}
					}
				},
			)
		val part =
			Part(
				id = PartId("p"),
				name = "p",
				children = emptyList(),
				groupMode = PartGroupMode.Grouped,
				channelGrids = ChannelGrids(mapOf(FormChannel.DRAW_ORDER to twoAxisTrack)),
			)
		val start =
			model(listOf(parameter(angleX, default = 0f), parameter(angleY)))
				.copy(parts = listOf(part), rootChildren = listOf(OrgChild.Part(PartId("p"))))
				.withDerivedRenderRoot()
		val staleAxes = start.renderRoot.children.filterIsInstance<RenderGroup>().single().channelGrids[FormChannel.DRAW_ORDER]!!.axes
		assertEquals(listOf(angleX, angleY), staleAxes.map { axis -> axis.parameterId }, "precondition: the group carries both axes")

		val after = start.withParameterDeleted(angleX)
		val group = after.renderRoot.children.filterIsInstance<RenderGroup>().single()
		val survivingAxes = group.channelGrids[FormChannel.DRAW_ORDER]!!.axes
		assertEquals(listOf(angleY), survivingAxes.map { axis -> axis.parameterId }, "the render tree lost the deleted axis too")
	}

	/**
	 * A glue's intensity TRACK is scrubbed too, so a deleted parameter leaves no dangling axis behind.
	 * Glue was the one owner the original scrub walk missed entirely.
	 */
	@Test
	fun deleteScrubsGlueIntensityTrack() {
		val intensityTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f))),
				buildList {
					for (xIndex in 0..2) {
						for (yIndex in 0..1) {
							add(KeyformCell<ChannelValue>(intArrayOf(xIndex, yIndex), ChannelValue.Scalar(xIndex * 10f + yIndex)))
						}
					}
				},
			)
		val glue =
			Glue(
				meshA = DrawableId("a"),
				meshB = DrawableId("b"),
				pairs = emptyList(),
				channelGrids = ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to intensityTrack)),
			)
		val start = model(listOf(parameter(angleX), parameter(angleY))).copy(glues = listOf(glue))
		val after = start.withParameterDeleted(angleX)
		val collapsed = after.glues.single().channelGrids[FormChannel.GLUE_INTENSITY]!!
		assertEquals(listOf(angleY), collapsed.axes.map { axis -> axis.parameterId }, "the deleted axis is gone")
		// angleX's default is 0, whose nearest key is index 1 - so the surviving intensities are 10 and 11.
		assertEquals(listOf(10f, 11f), collapsed.cells.map { cell -> (cell.form as ChannelValue.Scalar).value })
	}
}