package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.uma.TEST_WRITER
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the retained-tree merge on the puppet entry's geometry half (docs/format/UMA.md §4.7): keys a
 * newer writer puts in a mesh, a grid axis, a cell, a channel track, a blend shape, a limit, or a glue survive
 * a rename, a mesh edit, and deletions that shift what follows; a cell keeps its keys at its key values; blend forms
 * are values and are replaced whole; channel tracks merge by channel.
 */
class UmaGeometryMergeTest {
	private val axis = UmaAxis("P0", listOf(-1f, 0f, 1f))

	/**
	 * A one-axis channel track with the same value at every key.
	 *
	 * @param Float value The value.
	 * @return UmaChannelGrid The track.
	 */
	private fun track(value: Float): UmaChannelGrid = UmaChannelGrid(listOf(axis), (0 until 3).map { keyIndex -> UmaChannelCell(listOf(keyIndex), JsonPrimitive(value)) })

	/**
	 * A triangle mesh.
	 *
	 * @return UmaMesh The mesh.
	 */
	private fun triangle(): UmaMesh = UmaMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2))

	/**
	 * A puppet with one of every geometry object kind that carries keys of its own.
	 *
	 * @return UmaPuppet The puppet.
	 */
	private fun geometryPuppet(): UmaPuppet =
		UmaPuppet(
			parameters = listOf(UmaParameter("P0", "P0", -1f, 1f, 0f), UmaParameter("P1", "P1", 0f, 1f, 0f)),
			drawables =
				listOf(
					UmaDrawable(
						id = "A",
						name = "A",
						mesh = triangle(),
						geometry = UmaMeshGrid(listOf(axis), (0 until 3).map { keyIndex -> UmaMeshCell(listOf(keyIndex), FloatArray(6) { keyIndex.toFloat() }) }),
						channels = linkedMapOf(UmaFormChannel.DrawOrder to track(500f), UmaFormChannel.Opacity to track(0.5f)),
						blendShapes =
							listOf(
								UmaMeshBlendShape(
									parameter = "P1",
									keys = listOf(0f, 1f),
									neutralIndex = 0,
									forms = listOf(null, UmaMeshForm(FloatArray(6) { 1f })),
									limits = listOf(UmaBlendLimit("P0", listOf(UmaBlendLimitPoint(-1f, 0f), UmaBlendLimitPoint(1f, 1f)))),
								),
							),
					),
					UmaDrawable("B", "B", mesh = triangle()),
				),
			glues =
				listOf(
					UmaGlue("A", "B", UmaGluePairs(intArrayOf(0), intArrayOf(1), floatArrayOf(0.5f), floatArrayOf(0.5f))),
					UmaGlue("B", "A", UmaGluePairs(intArrayOf(2), intArrayOf(0), floatArrayOf(0.25f), floatArrayOf(0.75f))),
				),
		)

	/**
	 * The saved geometry puppet with a newer writer's keys planted in every geometry object kind, read back.
	 *
	 * @return UmaModel The document as an older writer opens it.
	 */
	private fun plantedDocument(): UmaModel {
		val saved = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(geometryPuppet())))
		var tree = saved.liveContent(UmaEntryKind.Puppet)!!
		tree =
			withArray(tree, "drawables") { _, drawable ->
				if ((drawable["id"] as JsonPrimitive).content != "A") {
					drawable
				} else {
					var planted = withKey(drawable, "mesh", withKey(drawable["mesh"] as JsonObject, "futureMesh", plantedValue("mesh")))
					var geometry = withArray(planted["geometry"] as JsonObject, "axes") { _, axisTree -> withKey(axisTree, "futureAxis", plantedValue("axis")) }
					geometry = withArray(geometry, "cells") { cellIndex, cell -> if (cellIndex == 1) withKey(cell, "futureCell", plantedValue("cell 1")) else cell }
					planted = withKey(planted, "geometry", geometry)
					val channels = planted["channels"] as JsonObject
					planted = withKey(planted, "channels", withKey(channels, "opacity", withKey(channels["opacity"] as JsonObject, "futureTrack", plantedValue("opacity"))))
					withArray(planted, "blendShapes") { _, binding ->
						val withLimit = withArray(withKey(binding, "futureBinding", plantedValue("binding")), "limits") { _, limit -> withKey(limit, "futureLimit", plantedValue("limit")) }
						val forms = withLimit["forms"] as JsonArray
						withKey(withLimit, "forms", JsonArray(listOf(forms[0], withKey(forms[1] as JsonObject, "futureForm", plantedValue("form")))))
					}
				}
			}
		tree = withArray(tree, "glues") { glueIndex, glue -> if (glueIndex == 1) withKey(glue, "futureGlue", plantedValue("B,A")) else glue }
		return Uma.read(Uma.write(saved.withLiveContent(UmaEntryKind.Puppet, tree)))
	}

	/**
	 * Planted geometry keys ride through a rename, a mesh edit, a deleted cell, a deleted channel, and a deleted
	 * glue, each staying with its own object; a blend form's key does not, since forms are replaced whole.
	 */
	@Test
	fun geometryKeysSurviveEditsByIdentity() {
		val document = plantedDocument()
		val puppet = assertNotNull(document.puppet)
		val drawableA = puppet.drawables!!.first { drawable -> drawable.id == "A" }
		val editedA =
			drawableA.copy(
				name = "A renamed",
				mesh = UmaMesh(floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometry = drawableA.geometry!!.copy(cells = drawableA.geometry.cells.drop(1).map { cell -> UmaMeshCell(cell.coordinate, FloatArray(8) { 3f }) }),
				channels = drawableA.channels!!.filterKeys { channel -> channel != UmaFormChannel.DrawOrder },
				blendShapes = drawableA.blendShapes!!.map { binding -> binding.copy(forms = listOf(null, UmaMeshForm(FloatArray(8) { 2f }))) },
			)
		val edited = puppet.copy(drawables = puppet.drawables.map { drawable -> if (drawable.id == "A") editedA else drawable }, glues = puppet.glues!!.drop(1))
		val saved = Uma.read(Uma.write(document.withPuppet(edited)))
		val tree = saved.liveContent(UmaEntryKind.Puppet)!!
		val drawable = assertNotNull(elementOf(tree, "drawables", "id", "A"))

		assertEquals(JsonPrimitive("A renamed"), drawable["name"], "the rename lands")
		assertEquals(plantedValue("mesh"), (drawable["mesh"] as JsonObject)["futureMesh"], "a mesh keeps its key through a mesh edit")
		assertContentEquals(floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f), saved.puppet!!.drawables!!.first { candidate -> candidate.id == "A" }.mesh!!.positions, "and the edit lands")

		val geometry = drawable["geometry"] as JsonObject
		assertEquals(plantedValue("axis"), ((geometry["axes"] as JsonArray).single() as JsonObject)["futureAxis"], "an axis keeps its key")
		val cells = (geometry["cells"] as JsonArray).map { cell -> cell as JsonObject }
		assertEquals(plantedValue("cell 1"), cells[0]["futureCell"], "the cell that shifted to the front keeps its own key")
		assertNull(cells[1]["futureCell"], "and its old neighbor does not take it")

		val channels = drawable["channels"] as JsonObject
		assertEquals(listOf("opacity"), channels.keys.toList(), "the deleted channel is gone")
		assertEquals(plantedValue("opacity"), (channels["opacity"] as JsonObject)["futureTrack"], "the remaining track keeps its key")

		val binding = (drawable["blendShapes"] as JsonArray).single() as JsonObject
		assertEquals(plantedValue("binding"), binding["futureBinding"], "a blend shape keeps its key")
		assertEquals(plantedValue("limit"), ((binding["limits"] as JsonArray).single() as JsonObject)["futureLimit"], "a limit keeps its key")
		assertNull(((binding["forms"] as JsonArray)[1] as JsonObject)["futureForm"], "a blend form is replaced whole")

		val glue = (tree["glues"] as JsonArray).single() as JsonObject
		assertEquals(JsonPrimitive("B"), glue["meshA"], "the surviving glue shifted to the front")
		assertEquals(plantedValue("B,A"), glue["futureGlue"], "and keeps its own key")
	}

	/**
	 * A puppet with one drawable whose opacity track is [track] and whose mesh grid is [geometry].
	 *
	 * @param UmaChannelGrid track    The opacity track.
	 * @param UmaMeshGrid?   geometry The mesh grid, if any.
	 * @return UmaPuppet The puppet.
	 */
	private fun trackPuppet(track: UmaChannelGrid, geometry: UmaMeshGrid? = null): UmaPuppet =
		UmaPuppet(
			parameters = listOf(UmaParameter("P0", "P0", -2f, 1f, 0f), UmaParameter("P1", "P1", 0f, 1f, 0f)),
			drawables = listOf(UmaDrawable("A", "A", mesh = geometry?.let { triangle() }, geometry = geometry, channels = mapOf(UmaFormChannel.Opacity to track))),
		)

	/**
	 * [document] with a planted key on each cell of drawable A's opacity track, and on each cell of its mesh grid when
	 * it has one, marked by what [markerOf] names the cell.
	 *
	 * @param UmaModel document The saved document.
	 * @param Function markerOf Names a cell by its index.
	 * @return UmaModel The planted document, as an older writer reads it.
	 */
	private fun plantedCells(document: UmaModel, markerOf: (Int) -> String): UmaModel {
		val tree =
			withArray(document.liveContent(UmaEntryKind.Puppet)!!, "drawables") { _, drawable ->
				val channels = drawable["channels"] as JsonObject
				var planted = withKey(drawable, "channels", withKey(channels, "opacity", withArray(channels["opacity"] as JsonObject, "cells") { cellIndex, cell -> withKey(cell, "futureCell", plantedValue(markerOf(cellIndex))) }))
				(planted["geometry"] as? JsonObject)?.let { geometry ->
					planted = withKey(planted, "geometry", withArray(geometry, "cells") { cellIndex, cell -> withKey(cell, "futureCell", plantedValue(markerOf(cellIndex))) })
				}
				planted
			}
		return Uma.read(Uma.write(document.withLiveContent(UmaEntryKind.Puppet, tree)))
	}

	/**
	 * The planted marker of each cell under [key] of drawable A in [document], by coordinate.
	 *
	 * @param UmaModel document The document.
	 * @param String   key      `opacity` for the track, `geometry` for the mesh grid.
	 * @return Map Each cell's coordinate as text, mapped to its planted value or null.
	 */
	private fun markersOf(document: UmaModel, key: String): Map<String, JsonElement?> {
		val drawable = assertNotNull(elementOf(document.liveContent(UmaEntryKind.Puppet)!!, "drawables", "id", "A"))
		val grid = if (key == "geometry") drawable["geometry"] as JsonObject else (drawable["channels"] as JsonObject)[key] as JsonObject
		return (grid["cells"] as JsonArray).associate { cell -> (cell as JsonObject)["coordinate"].toString() to cell["futureCell"] }
	}

	/**
	 * A key a newer writer put in a grid cell stays with the cell for the same key values: through a key added before
	 * it, through a reordering of the axes, and through a repeated key; an axis the grid loses takes its cells' keys
	 * with it.
	 */
	@Test
	fun cellKeysFollowTheirKeyValues() {
		val oneAxis = UmaAxis("P0", listOf(-1f, 0f, 1f))
		val saved = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(trackPuppet(UmaChannelGrid(listOf(oneAxis), (0 until 3).map { keyIndex -> UmaChannelCell(listOf(keyIndex), JsonPrimitive(keyIndex)) }), UmaMeshGrid(listOf(oneAxis), (0 until 3).map { keyIndex -> UmaMeshCell(listOf(keyIndex), FloatArray(6)) })))))
		val planted = plantedCells(saved) { cellIndex -> "at ${oneAxis.keys[cellIndex]}" }

		// A key added below every other: each old cell's coordinate moves up by one.
		val widened = UmaAxis("P0", listOf(-2f, -1f, 0f, 1f))
		val inserted = trackPuppet(UmaChannelGrid(listOf(widened), (0 until 4).map { keyIndex -> UmaChannelCell(listOf(keyIndex), JsonPrimitive(keyIndex)) }), UmaMeshGrid(listOf(widened), (0 until 4).map { keyIndex -> UmaMeshCell(listOf(keyIndex), FloatArray(6)) }))
		val afterInsert = Uma.read(Uma.write(planted.withPuppet(inserted)))
		val expected = mapOf("[0]" to null, "[1]" to plantedValue("at -1.0"), "[2]" to plantedValue("at 0.0"), "[3]" to plantedValue("at 1.0"))
		assertEquals(expected, markersOf(afterInsert, "opacity"), "a channel cell keeps its key at its key value")
		assertEquals(expected, markersOf(afterInsert, "geometry"), "and so does a mesh cell")

		// Two axes, then the same cells with the axes swapped.
		val first = UmaAxis("P0", listOf(0f, 1f))
		val second = UmaAxis("P1", listOf(0f, 1f))
		val coordinates = listOf(listOf(0, 0), listOf(1, 0), listOf(0, 1), listOf(1, 1))
		val twoAxes = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(trackPuppet(UmaChannelGrid(listOf(first, second), coordinates.map { coordinate -> UmaChannelCell(coordinate, JsonPrimitive(0)) })))))
		val plantedTwoAxes = plantedCells(twoAxes) { cellIndex -> "P0=${coordinates[cellIndex][0]} P1=${coordinates[cellIndex][1]}" }
		val swapped = trackPuppet(UmaChannelGrid(listOf(second, first), coordinates.map { coordinate -> UmaChannelCell(coordinate.reversed(), JsonPrimitive(0)) }))
		assertEquals(
			mapOf("[0,0]" to plantedValue("P0=0 P1=0"), "[0,1]" to plantedValue("P0=1 P1=0"), "[1,0]" to plantedValue("P0=0 P1=1"), "[1,1]" to plantedValue("P0=1 P1=1")),
			markersOf(Uma.read(Uma.write(plantedTwoAxes.withPuppet(swapped))), "opacity"),
			"a cell keeps its key when the axes swap",
		)

		// An axis the grid loses: its cells are different forms, so their keys leave.
		val collapsed = trackPuppet(UmaChannelGrid(listOf(first), listOf(UmaChannelCell(listOf(0), JsonPrimitive(0)), UmaChannelCell(listOf(1), JsonPrimitive(0)))))
		assertEquals(mapOf("[0]" to null, "[1]" to null), markersOf(Uma.read(Uma.write(plantedTwoAxes.withPuppet(collapsed))), "opacity"), "a lost axis takes its cells' keys")

		// A repeated key: the second of two equal keys stays the second through a key added before both.
		val repeated = UmaAxis("P0", listOf(0f, 0f, 1f))
		val withRepeat = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(trackPuppet(UmaChannelGrid(listOf(repeated), (0 until 3).map { keyIndex -> UmaChannelCell(listOf(keyIndex), JsonPrimitive(keyIndex)) })))))
		val plantedRepeat = plantedCells(withRepeat) { cellIndex -> "cell $cellIndex" }
		val repeatedWidened = UmaAxis("P0", listOf(-1f, 0f, 0f, 1f))
		val afterRepeat = Uma.read(Uma.write(plantedRepeat.withPuppet(trackPuppet(UmaChannelGrid(listOf(repeatedWidened), (0 until 4).map { keyIndex -> UmaChannelCell(listOf(keyIndex), JsonPrimitive(keyIndex)) })))))
		assertEquals(mapOf("[0]" to null, "[1]" to plantedValue("cell 0"), "[2]" to plantedValue("cell 1"), "[3]" to plantedValue("cell 2")), markersOf(afterRepeat, "opacity"))
	}
}