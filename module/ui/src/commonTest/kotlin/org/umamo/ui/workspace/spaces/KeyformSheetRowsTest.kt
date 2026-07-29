package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.tracks.TrackRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the sheet projection's row identities and mark flags: glue rows are keyed by the mesh PAIR (the
 * stable identity KeyformOwner.Glue itself encodes), and blend-shape marks are not editable (they have
 * no track ref, so none of the sheet's ops can apply to them).
 */
class KeyformSheetRowsTest {
	private val angleX = ParameterId("ParamAngleX")

	/** Chrome-free labels: the projection is Compose-free, so tests inject plain strings. */
	private fun labels(): KeyformTrackLabels =
		KeyformTrackLabels(
			channelName = { channel -> channel.name },
			geometry = "Geometry",
			blendShape = "Blend Shape",
			ownerKindName = { kind -> kind.name },
		)

	/** A single-axis intensity track on angleX, so a glue owner has rows to project. */
	private fun intensityTrack(): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
			listOf<KeyformCell<ChannelValue>>(
				KeyformCell(intArrayOf(0), ChannelValue.Scalar(0.5f)),
				KeyformCell(intArrayOf(1), ChannelValue.Scalar(1f)),
			),
		)

	/** A glue welding [meshA] and [meshB] with a keyed intensity track. */
	private fun glue(meshA: String, meshB: String): Glue =
		Glue(DrawableId(meshA), DrawableId(meshB), emptyList(), ChannelGrids(mapOf(FormChannel.GLUE_INTENSITY to intensityTrack())))

	/** A model holding [glues] and [drawables] under one angleX parameter. */
	private fun model(glues: List<Glue> = emptyList(), drawables: List<Drawable> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			glues = glues,
		)

	/** Every row in the tree, flattened. */
	private fun allRows(rows: List<TrackRow>): List<TrackRow> = rows.flatMap { row -> listOf(row) + allRows(row.children) }

	/**
	 * Removing an earlier glue must not migrate a later glue's row keys: selection and collapse state key
	 * off the rowKey, and an ordinal key handed one glue's state to the next after a removal.
	 */
	@Test
	fun glueRowKeysSurviveAnEarlierRemoval() {
		val glueAB = glue("a", "b")
		val glueCD = glue("c", "d")
		val bothProjection = keyformSheetRows(model(glues = listOf(glueAB, glueCD)), angleX, labels())
		val cdOnlyProjection = keyformSheetRows(model(glues = listOf(glueCD)), angleX, labels())

		val cdKeysBefore = bothProjection.tracksByRowKey.keys.filter { key -> key.startsWith("glue:c:d") }
		val cdKeysAfter = cdOnlyProjection.tracksByRowKey.keys.filter { key -> key.startsWith("glue:c:d") }
		assertTrue(cdKeysBefore.isNotEmpty(), "the glue's track rows are keyed by its mesh pair")
		assertEquals(cdKeysBefore, cdKeysAfter, "removing another glue leaves this glue's row keys untouched")
	}

	/** Glue group rows are labeled with the meshes' display names, not their raw ids. */
	@Test
	fun glueRowsAreLabeledWithDisplayNames() {
		val eyeLeft =
			Drawable(
				id = DrawableId("a"),
				name = "Eye L",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
			)
		val eyeRight = eyeLeft.copy(id = DrawableId("b"), name = "Eye R")
		val projection = keyformSheetRows(model(glues = listOf(glue("a", "b")), drawables = listOf(eyeLeft, eyeRight)), angleX, labels())

		val glueGroupRow = allRows(projection.rows).first { row -> row.key == "glue:a:b" }
		assertEquals("Eye L ↔ Eye R", glueGroupRow.label)
	}

	/**
	 * Blend-shape marks are NOT editable: the row has no track ref, so an editable mark would accept a
	 * drag that silently reverts and a selection that Delete can never resolve.
	 */
	@Test
	fun blendShapeMarksAreNotEditable() {
		val binding =
			BlendShapeBinding(
				parameterId = angleX,
				keys = floatArrayOf(0f, 1f),
				neutralIndex = 0,
				forms = listOf(null, MeshForm(floatArrayOf(0f, 0f))),
			)
		val drawable =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
				blendShapes = listOf(binding),
			)
		val projection = keyformSheetRows(model(drawables = listOf(drawable)), angleX, labels())

		val blendRows = allRows(projection.rows).filter { row -> row.key.contains("/blend") }
		assertTrue(blendRows.isNotEmpty(), "the binding projects a blend row")
		val blendMarks = blendRows.flatMap { row -> row.marks }
		assertTrue(blendMarks.isNotEmpty(), "the blend row carries its key marks")
		assertTrue(blendMarks.none { mark -> mark.editable }, "no blend mark may accept a drag or a selection")
	}
}
