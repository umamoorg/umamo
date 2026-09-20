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
 * Pins the retained-tree merge on the puppet entry (docs/format/UMA.md §4.7): keys a newer writer adds
 * to any object kind survive an older writer's edits and saves, travel with their object's identity rather
 * than its position, and leave only with the object they belong to.
 */
class UmaTreeMergeTest {
	/**
	 * A saved sample puppet whose tree has had a newer writer's keys planted in every object kind, read back.
	 *
	 * @return UmaModel The document as an older writer opens it.
	 */
	private fun plantedDocument(): UmaModel {
		val saved = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(samplePuppet())))
		var tree = saved.liveContent(UmaEntryKind.Puppet)!!
		tree = withKey(tree, "futureRoot", plantedValue("root"))
		tree = withArray(tree, "parameters") { _, parameter -> withKey(parameter, "futureParameter", plantedValue((parameter["id"] as JsonPrimitive).content)) }
		tree = withArray(tree, "parameterLinks") { _, link -> withKey(link, "futureLink", plantedValue("link")) }
		tree =
			withArray(tree, "parameterTree") { _, group ->
				withArray(withKey(group, "futureGroup", plantedValue("group")), "children") { _, leaf ->
					withKey(leaf, "futureLeaf", plantedValue((leaf["parameter"] as JsonPrimitive).content))
				}
			}
		tree = withArray(tree, "rootChildren") { childIndex, reference -> if (childIndex == 0) withKey(reference, "futureReference", plantedValue("part:Head")) else reference }
		tree =
			withArray(tree, "parts") { _, part ->
				if ((part["id"] as JsonPrimitive).content != "Head") {
					part
				} else {
					val composite = withKey(part["composite"] as JsonObject, "futureComposite", plantedValue("composite"))
					withArray(withKey(withKey(part, "futurePart", plantedValue("Head")), "composite", composite), "children") { _, child -> withKey(child, "futureChild", plantedValue("Eye")) }
				}
			}
		tree = withArray(tree, "deformers") { _, deformer -> withKey(deformer, "futureDeformer", plantedValue("Warp1")) }
		tree = withArray(tree, "drawables") { _, drawable -> withKey(drawable, "futureDrawable", plantedValue((drawable["id"] as JsonPrimitive).content)) }
		return Uma.read(Uma.write(saved.withLiveContent(UmaEntryKind.Puppet, tree)))
	}

	/**
	 * Planted keys ride through a rename, a delete-and-shift, a reorder, and a reset to default: each stays
	 * with its object, leaves with a deleted one, and lands after the known keys.
	 */
	@Test
	fun unknownKeysSurviveEditsByIdentity() {
		val document = plantedDocument()
		val puppet = assertNotNull(document.puppet, "planted keys do not stop the entry decoding")
		val edited =
			puppet.copy(
				// Delete ParamA everywhere it appears, shifting ParamB to the front.
				parameters = puppet.parameters!!.filter { parameter -> parameter.id != "ParamA" },
				parameterLinks = null,
				parameterTree = puppet.parameterTree!!.map { group -> group.copy(children = group.children!!.filter { leaf -> leaf.parameter != "ParamA" }) },
				// Reorder the parts, and reset Head's visibility and composite to their defaults.
				parts = puppet.parts!!.reversed().map { part -> if (part.id == "Head") part.copy(isVisible = null, composite = null) else part },
				// Rename a drawable.
				drawables = puppet.drawables!!.map { drawable -> if (drawable.id == "Eye") drawable.copy(name = "Eye L") else drawable },
			)
		val tree = Uma.read(Uma.write(document.withPuppet(edited))).liveContent(UmaEntryKind.Puppet)!!

		assertEquals(plantedValue("root"), tree["futureRoot"], "a root key survives")
		assertEquals("futureRoot", tree.keys.last(), "and follows the known keys")

		assertNull(elementOf(tree, "parameters", "id", "ParamA"), "the deleted parameter is gone")
		val parameterB = assertNotNull(elementOf(tree, "parameters", "id", "ParamB"))
		assertEquals(plantedValue("ParamB"), parameterB["futureParameter"], "the shifted parameter keeps its own key, not its old neighbor's")
		assertNull(tree["parameterLinks"], "the deleted link leaves with its key")

		val group = (tree["parameterTree"] as JsonArray).single() as JsonObject
		assertEquals(plantedValue("group"), group["futureGroup"], "a group keeps its key")
		val leaf = (group["children"] as JsonArray).single() as JsonObject
		assertEquals(plantedValue("ParamB"), leaf["futureLeaf"], "the surviving leaf keeps its key")

		assertEquals(listOf("Body", "Head"), (tree["parts"] as JsonArray).map { part -> ((part as JsonObject)["id"] as JsonPrimitive).content }, "parts in the new order")
		val head = assertNotNull(elementOf(tree, "parts", "id", "Head"))
		assertEquals(plantedValue("Head"), head["futurePart"], "the moved part keeps its key")
		assertNull(head["isVisible"], "a value reset to its default drops its key")
		assertEquals(JsonObject(mapOf("futureComposite" to plantedValue("composite"))), head["composite"], "a composite reset to defaults keeps only its unknown key")
		assertEquals(plantedValue("Eye"), ((head["children"] as JsonArray).single() as JsonObject)["futureChild"], "an org reference keeps its key")

		val rootChildren = (tree["rootChildren"] as JsonArray).map { reference -> reference as JsonObject }
		assertEquals(plantedValue("part:Head"), rootChildren.first { reference -> reference.containsKey("part") }["futureReference"], "the part reference keeps its key")
		assertNull(rootChildren.first { reference -> reference.containsKey("drawable") }["futureReference"], "and the drawable sharing its raw id does not take it")

		assertEquals(plantedValue("Warp1"), assertNotNull(elementOf(tree, "deformers", "id", "Warp1"))["futureDeformer"], "a deformer keeps its key")
		val eye = assertNotNull(elementOf(tree, "drawables", "id", "Eye"))
		assertEquals(JsonPrimitive("Eye L"), eye["name"], "the rename lands")
		assertEquals(plantedValue("Eye"), eye["futureDrawable"], "and the renamed drawable keeps its key")
		assertEquals("futureDrawable", eye.keys.last(), "after its known keys")
	}

	/**
	 * A document with no unknown keys writes the same bytes whether it was created fresh or edited from a
	 * file with a different history.
	 */
	@Test
	fun outputDoesNotDependOnHistory() {
		val target = samplePuppet().copy(canvasWidth = 1024f, parts = samplePuppet().parts!!.reversed())
		val fresh = Uma.write(UmaModel.create(TEST_WRITER).withPuppet(target))
		val fromHistory = Uma.write(Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(samplePuppet()))).withPuppet(target))
		assertContentEquals(fresh, fromHistory)
	}

	/**
	 * Arrays of values are replaced whole, since their elements have no identity to carry keys by.
	 */
	@Test
	fun valueArraysAreReplacedWhole() {
		val saved = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(samplePuppet())))
		val edited = saved.puppet!!.let { puppet -> puppet.copy(drawables = puppet.drawables!!.map { drawable -> if (drawable.id == "Eye") drawable.copy(maskedBy = listOf("Body", "Head")) else drawable }) }
		val tree = Uma.read(Uma.write(saved.withPuppet(edited))).liveContent(UmaEntryKind.Puppet)!!
		assertEquals(JsonArray(listOf(JsonPrimitive("Body"), JsonPrimitive("Head"))), elementOf(tree, "drawables", "id", "Eye")!!["maskedBy"])
	}
}