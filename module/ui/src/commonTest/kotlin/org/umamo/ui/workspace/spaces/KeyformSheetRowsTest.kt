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
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.tracks.TrackRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

	/** A drawable carrying all three track kinds at once, so a filter has something of each to drop. */
	private fun drawableWithEveryTrackKind(): Drawable =
		Drawable(
			id = DrawableId("d"),
			name = "d",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
					listOf(
						KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f))),
						KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(1f, 0f))),
					),
				),
			channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to intensityTrack())),
			blendShapes =
				listOf(
					BlendShapeBinding(
						parameterId = angleX,
						keys = floatArrayOf(0f, 1f),
						neutralIndex = 0,
						forms = listOf(null, MeshForm(floatArrayOf(0f, 0f))),
					),
				),
		)

	/**
	 * Each filter flag drops exactly its own kind of child row and leaves the other two alone.
	 *
	 * Gated while BUILDING the projection rather than while drawing, so a hidden track is absent from the
	 * tree entirely - which is what keeps a summary mark from standing for a key the sheet is not showing.
	 */
	@Test
	fun eachFilterFlagDropsOnlyItsOwnRows() {
		val puppet = model(drawables = listOf(drawableWithEveryTrackKind()))
		val unfiltered = allRows(keyformSheetRows(puppet, angleX, labels()).rows)
		assertTrue(unfiltered.any { row -> row.key.endsWith("/geometry") }, "the unfiltered sheet lists geometry")
		assertTrue(unfiltered.any { row -> row.key.endsWith("/OPACITY") }, "and the channel track")
		assertTrue(unfiltered.any { row -> row.key.contains("/blend") }, "and the blend shape")

		val withoutGeometry = allRows(keyformSheetRows(puppet, angleX, labels(), KeyformTrackFilter(geometry = false)).rows)
		assertFalse(withoutGeometry.any { row -> row.key.endsWith("/geometry") }, "the geometry row is gone")
		assertTrue(withoutGeometry.any { row -> row.key.endsWith("/OPACITY") }, "the channel row is untouched")
		assertTrue(withoutGeometry.any { row -> row.key.contains("/blend") }, "so is the blend row")

		val withoutChannels = allRows(keyformSheetRows(puppet, angleX, labels(), KeyformTrackFilter(channels = false)).rows)
		assertFalse(withoutChannels.any { row -> row.key.endsWith("/OPACITY") }, "the channel row is gone")
		assertTrue(withoutChannels.any { row -> row.key.endsWith("/geometry") }, "the geometry row is untouched")

		val withoutBlends = allRows(keyformSheetRows(puppet, angleX, labels(), KeyformTrackFilter(blendShapes = false)).rows)
		assertFalse(withoutBlends.any { row -> row.key.contains("/blend") }, "the blend row is gone")
		assertTrue(withoutBlends.any { row -> row.key.endsWith("/geometry") }, "the geometry row is untouched")
	}

	/**
	 * An owner filtered down to nothing loses its group row too, rather than sitting there empty.
	 *
	 * The group row is a disclosure for its children; an expandable row that expands into nothing reads as
	 * a bug, and its summary marks would stand for keys the sheet is deliberately not showing.
	 */
	@Test
	fun anOwnerWithEveryTrackFilteredOutLosesItsGroupRow() {
		val puppet = model(glues = listOf(glue("a", "b")))
		assertTrue(
			allRows(keyformSheetRows(puppet, angleX, labels()).rows).any { row -> row.key == "glue:a:b" },
			"the glue's only tracks are channels, so unfiltered it has a group row",
		)
		val filtered = keyformSheetRows(puppet, angleX, labels(), KeyformTrackFilter(channels = false))
		assertTrue(filtered.rows.isEmpty(), "with channels hidden the glue has no children left, so no group row either")
	}

	/**
	 * The projection reports whether a filter actually DROPPED something, not merely that one is off.
	 *
	 * "Nothing is keyed here" and "you hid it" are opposite diagnoses; a flag that is off but hides nothing
	 * on this rig must not make an empty sheet blame the filter for a rig that simply keys nothing.
	 */
	@Test
	fun theProjectionReportsWhetherTheFilterDroppedAnything() {
		val puppet = model(drawables = listOf(drawableWithEveryTrackKind()))
		assertFalse(keyformSheetRows(puppet, angleX, labels()).hiddenByFilter, "nothing is filtered by default")
		assertTrue(
			keyformSheetRows(puppet, angleX, labels(), KeyformTrackFilter(geometry = false)).hiddenByFilter,
			"the drawable has a geometry track, so hiding geometry hid something",
		)

		// A rig with only channel tracks: hiding GEOMETRY takes nothing away from it, so the sheet must not
		// claim a filter is responsible for what it shows.
		val glueOnly = model(glues = listOf(glue("a", "b")))
		assertFalse(
			keyformSheetRows(glueOnly, angleX, labels(), KeyformTrackFilter(geometry = false)).hiddenByFilter,
			"a glue has no geometry track, so the geometry filter dropped nothing",
		)
		assertTrue(keyformSheetRows(glueOnly, angleX, labels(), KeyformTrackFilter(channels = false)).hiddenByFilter)
	}

	/** Every group row carries the owner it names, so its own menu can act on the thing rather than a track. */
	@Test
	fun groupRowsCarryTheirOwner() {
		val puppet = model(drawables = listOf(drawableWithEveryTrackKind()), glues = listOf(glue("a", "b")))
		val projection = keyformSheetRows(puppet, angleX, labels())
		assertEquals(KeyformOwner.Drawable(DrawableId("d")), projection.ownerByRowKey["drawable:d"])
		assertEquals(KeyformOwner.Glue(DrawableId("a"), DrawableId("b")), projection.ownerByRowKey["glue:a:b"])
		assertEquals(
			projection.ownerKindByRowKey.keys,
			projection.ownerByRowKey.keys,
			"the two group-row maps are keyed alike - a row with an icon but no owner would show a dead menu",
		)
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
