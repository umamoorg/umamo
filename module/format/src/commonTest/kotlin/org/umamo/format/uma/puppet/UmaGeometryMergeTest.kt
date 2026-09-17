package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonArray
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
 * Pins the retained-tree merge on the puppet entry's geometry half (docs/format/UMA.md §4.7, D10): keys a
 * newer writer puts in a mesh, a grid axis, a cell, a channel track, a blend shape, a limit, or a glue survive
 * a rename, a mesh edit, and deletions that shift what follows; blend forms are values and are replaced whole;
 * channel tracks merge by channel.
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
}