package org.umamo.ui.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.umamo.render.ViewportCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the per-document holder of area view state (docs/format/UMA.md §7.3, §7.5; D31): scopes live as long as
 * the holder rather than a leaf, a saved block seeds only the area whose id it carries, and a gather writes the
 * saver's layout and takes every other id out of the file.
 */
class AreaViewStatesTest {
	/** A stand-in for a space's view state holding one list. */
	private class ListState : PersistentSpaceState {
		var values: List<String> = emptyList()

		/**
		 * The state's member of its area block.
		 *
		 * @return JsonObject The member.
		 */
		override fun toJson(): JsonObject = buildJsonObject { put("values", stringArrayOrNull(values)) }

		/**
		 * Takes the saved member.
		 *
		 * @param JsonObject tree The member as the file held it.
		 */
		override fun restore(tree: JsonObject) {
			values = stringListOf(tree, "values").orEmpty()
		}
	}

	/**
	 * A saved `areas` member holding one list per area id.
	 *
	 * @param Map valuesByAreaId Each area's list.
	 * @return JsonObject The member.
	 */
	private fun savedAreas(valuesByAreaId: Map<String, List<String>>): JsonObject =
		buildJsonObject {
			for ((areaId, values) in valuesByAreaId) {
				put(areaId, buildJsonObject { put("outliner", buildJsonObject { put("values", JsonArray(values.map(::JsonPrimitive))) }) })
			}
		}

	/** The scope outlives the leaf that asked for it: asking again, as a recomposed leaf does, finds the same state. */
	@Test
	fun aScopeLivesAsLongAsTheHolder() {
		val holder = AreaViewStates()
		val firstMount = holder.scopeFor("area-1").spaceState("outliner") { ListState() }
		firstMount.values = listOf("part:1")

		val afterATabSwitch = holder.scopeFor("area-1").spaceState("outliner") { ListState() }

		assertSame(firstMount, afterATabSwitch)
		assertEquals(listOf("part:1"), afterATabSwitch.values)
		assertNotSame(firstMount, AreaViewStates().scopeFor("area-1").spaceState("outliner") { ListState() }, "another document starts over")
	}

	/** A saved block seeds the area whose id it carries, and only that one. */
	@Test
	fun aSavedBlockSeedsOnlyItsOwnArea() {
		val holder = AreaViewStates(savedAreas(mapOf("area-1" to listOf("part:1"))))

		assertEquals(listOf("part:1"), holder.scopeFor("area-1").spaceState("outliner") { ListState() }.values)
		assertEquals(emptyList(), holder.scopeFor("area-2").spaceState("outliner") { ListState() }.values, "an id the file does not have starts at its defaults")
	}

	/** A state that is not persistent is parked like any other and never written. */
	@Test
	fun aPlainStateIsNeverWritten() {
		val holder = AreaViewStates()
		holder.layoutAreaIds = listOf("area-1")
		holder.scopeFor("area-1").spaceState("logs") { Any() }

		assertEquals(JsonObject(emptyMap()), holder.gather()["area-1"])
	}

	/**
	 * A gather writes the shown areas of the layout in layout order, leaves an area it never showed unnamed so
	 * the file's block survives, and nulls every id the layout lacks.
	 */
	@Test
	fun aGatherWritesTheLayoutAndDropsTheRest() {
		val holder = AreaViewStates(savedAreas(mapOf("area-stale" to listOf("part:9"), "area-unshown" to listOf("part:7"))))
		holder.layoutAreaIds = listOf("area-2", "area-unshown", "area-1")
		holder.scopeFor("area-1").spaceState("outliner") { ListState() }.values = listOf("part:1")
		holder.scopeFor("area-2").spaceState("outliner") { ListState() }.values = listOf("part:2")
		holder.scopeFor("area-closed").spaceState("outliner") { ListState() }.values = listOf("part:3")

		val gathered = holder.gather()

		assertEquals(listOf("area-stale", "area-2", "area-1"), gathered.keys.toList(), "the removal first, then the layout's order")
		assertEquals(JsonNull, gathered["area-stale"], "an id the layout lacks leaves the file")
		assertEquals(null, gathered["area-unshown"], "an area never shown keeps the block the file had")
		assertEquals(null, gathered["area-closed"], "an area closed in the session is not written")
		assertEquals(savedAreas(mapOf("area-1" to listOf("part:1")))["area-1"], gathered["area-1"]!!.jsonObject)
	}

	/** A state back at its default names its member as a null, which is what takes the deviation out of the file. */
	@Test
	fun aStateAtItsDefaultWritesANull() {
		val holder = AreaViewStates(savedAreas(mapOf("area-1" to listOf("part:1"))))
		holder.layoutAreaIds = listOf("area-1")
		holder.scopeFor("area-1").spaceState("outliner") { ListState() }.values = emptyList()

		assertEquals(JsonNull, holder.gather()["area-1"]!!.jsonObject["outliner"]!!.jsonObject["values"])
	}

	/**
	 * Each area's camera goes out to seed the render service and comes back from it at a save, leading its block;
	 * an area with a camera and no space state is still written, and a camera of the wrong shape is skipped.
	 */
	@Test
	fun camerasAreRestoredAndGatheredByArea() {
		val saved =
			buildJsonObject {
				put("area-view", buildJsonObject { put("camera", JsonArray(listOf(JsonPrimitive(10f), JsonPrimitive(-20f), JsonPrimitive(1.5f)))) })
				put("area-flat", buildJsonObject { put("camera", JsonArray(listOf(JsonPrimitive(0f), JsonPrimitive(0f), JsonPrimitive(0f)))) })
				put("area-junk", buildJsonObject { put("camera", JsonPrimitive("wide")) })
			}
		val holder = AreaViewStates(saved)
		assertEquals(mapOf("area-view" to ViewportCamera(10f, -20f, 1.5f)), holder.restoredCameras(), "a zoom of zero and a non-array are skipped")

		holder.layoutAreaIds = listOf("area-view", "area-both", "area-panel")
		holder.scopeFor("area-both").spaceState("outliner") { ListState() }.values = listOf("part:1")
		holder.scopeFor("area-panel").spaceState("outliner") { ListState() }
		holder.cameraReader = { mapOf("area-view" to ViewportCamera(1f, 2f, 3f), "area-both" to ViewportCamera(4f, 5f, 6f), "area-closed" to ViewportCamera(7f, 8f, 9f)) }

		val gathered = holder.gather()

		assertEquals(JsonArray(listOf(JsonPrimitive(1f), JsonPrimitive(2f), JsonPrimitive(3f))), gathered["area-view"]!!.jsonObject["camera"])
		assertEquals(listOf("camera", "outliner"), gathered["area-both"]!!.jsonObject.keys.toList(), "the camera leads the block")
		assertEquals(listOf("outliner"), gathered["area-panel"]!!.jsonObject.keys.toList(), "an area with no camera names none, so a saved one survives")
		assertEquals(null, gathered["area-closed"], "a camera for an area the layout lacks is not written")
	}

	/** An area block's members come out in the spec's order whatever order the spaces were shown in. */
	@Test
	fun aBlockOrdersItsMembersByTheSpec() {
		val scope = AreaScope("area-1")
		scope.spaceState("uv") { ListState() }
		scope.spaceState("outliner") { ListState() }
		scope.spaceState("keyformSheet") { ListState() }

		assertEquals(listOf("outliner", "keyformSheet", "uv"), scope.gather().keys.toList())
	}
}