package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.uma.TEST_WRITER
import org.umamo.format.uma.TestEntry
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.manifestJson
import org.umamo.format.uma.recordJson
import org.umamo.format.uma.umaArchiveOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the puppet entry's shape rules (docs/format/UMA.md §4.8): each shape the flat schema allows but the model does
 * not is named by its path, refused by a save, and refused by a read, so a document Umamo saves always reopens.
 */
class UmaPuppetShapeTest {
	private val positions = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
	private val mesh = UmaMesh(positions, positions.copyOf(), intArrayOf(0, 1, 2))
	private val axis = UmaAxis("P0", listOf(0f, 1f))
	private val warp = UmaDeformer("W", UmaDeformerKind.Warp, "W", rows = 1, columns = 1, isQuadTransform = true)
	private val rotation = UmaDeformer("R", UmaDeformerKind.Rotation, "R", baseAngle = 0f)

	/**
	 * Asserts that [puppet] breaks a shape rule the problem names at [where], and that a save refuses it.
	 *
	 * @param UmaPuppet puppet The puppet.
	 * @param String    where  A fragment of the path the problem must name.
	 */
	private fun assertRefused(puppet: UmaPuppet, where: String) {
		val problem = assertNotNull(UmaPuppetShape.firstProblem(puppet), where)
		assertTrue(problem.contains(where), "'$problem' names $where")
		val failure = assertFailsWith<UmaWriteException>(where) { UmaModel.create(TEST_WRITER).withPuppet(puppet) }
		assertTrue(failure.detail.contains(where), "the save's refusal '${failure.detail}' names $where")
	}

	/**
	 * A puppet holding one drawable.
	 *
	 * @param UmaDrawable drawable The drawable.
	 * @return UmaPuppet The puppet.
	 */
	private fun withDrawable(drawable: UmaDrawable): UmaPuppet = UmaPuppet(drawables = listOf(drawable))

	/**
	 * The shapes a valid puppet holds pass, so the refusals below are the rules and not noise.
	 */
	@Test
	fun soundShapesPass() {
		assertNull(UmaPuppetShape.firstProblem(samplePuppet()))
		val geometry =
			UmaPuppet(
				deformers =
					listOf(
						warp.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(8))))),
						rotation.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(1), originX = 0f, originY = 0f, angle = 0f, scale = 1f)))),
					),
				drawables =
					listOf(
						UmaDrawable(
							"D",
							"D",
							mesh = mesh,
							geometry = UmaMeshGrid(listOf(UmaAxis("P0", listOf(0f, 0f, 1f))), listOf(UmaMeshCell(listOf(2), FloatArray(6)))),
							channels = mapOf(UmaFormChannel.MultiplyColor to UmaChannelGrid(emptyList(), listOf(UmaChannelCell(emptyList(), JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0.5), JsonPrimitive(0))))))),
						),
					),
			)
		assertNull(UmaPuppetShape.firstProblem(geometry), "a repeated key, a zero-axis track, and whole forms")
	}

	/**
	 * Each structural shape the model cannot hold is refused.
	 */
	@Test
	fun impossibleStructureIsRefused() {
		assertRefused(UmaPuppet(rootChildren = listOf(UmaOrgRef())), "rootChildren[0]")
		assertRefused(UmaPuppet(rootChildren = listOf(UmaOrgRef(part = "P", drawable = "D"))), "rootChildren[0]")
		assertRefused(UmaPuppet(parts = listOf(UmaPart("P", "P", children = listOf(UmaOrgRef())))), "parts[P].children[0]")
		assertRefused(UmaPuppet(parameterTree = listOf(UmaParameterNode(parameter = "P", group = "G"))), "parameterTree[0]")
		assertRefused(UmaPuppet(parameterTree = listOf(UmaParameterNode(group = "G"))), "parameterTree[0] is a group without a name")
		assertRefused(UmaPuppet(parameterTree = listOf(UmaParameterNode(parameter = "P", name = "P"))), "parameterTree[0] is a parameter leaf")
		assertRefused(UmaPuppet(parameterTree = listOf(UmaParameterNode(group = "G", name = "G", children = listOf(UmaParameterNode())))), "parameterTree[0].children[0]")
		assertRefused(UmaPuppet(deformers = listOf(UmaDeformer("W", UmaDeformerKind.Warp, "W", columns = 2, isQuadTransform = true))), "deformers[W] is a warp without rows")
		assertRefused(UmaPuppet(deformers = listOf(UmaDeformer("W", UmaDeformerKind.Warp, "W", rows = 2, columns = 2))), "deformers[W] is a warp without isQuadTransform")
		assertRefused(UmaPuppet(deformers = listOf(warp.copy(baseAngle = 1f))), "deformers[W] is a warp but carries rotation fields")
		assertRefused(UmaPuppet(deformers = listOf(warp.copy(rows = -1))), "deformers[W] has a negative lattice size")
		assertRefused(UmaPuppet(deformers = listOf(UmaDeformer("R", UmaDeformerKind.Rotation, "R"))), "deformers[R] is a rotation without baseAngle")
		assertRefused(UmaPuppet(deformers = listOf(rotation.copy(rows = 2))), "deformers[R] is a rotation but carries warp fields")
		assertRefused(withDrawable(UmaDrawable("D", "D", multiplyColor = listOf(1f, 1f))), "drawables[D].multiplyColor")
		assertRefused(UmaPuppet(parts = listOf(UmaPart("P", "P", composite = UmaPartComposite(screenColor = listOf(0f))))), "parts[P].composite.screenColor")
	}

	/**
	 * Each geometry invariant the renderer relies on is refused.
	 */
	@Test
	fun brokenGeometryIsRefused() {
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 1f), intArrayOf()))), "drawables[D].mesh.positions")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(positions, floatArrayOf(0f, 0f), intArrayOf(0, 1, 2)))), "drawables[D].mesh.uvs")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(positions, positions.copyOf(), intArrayOf(0, 1)))), "drawables[D].mesh.indices")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(positions, positions.copyOf(), intArrayOf(0, 1, 3)))), "drawables[D].mesh.indices[2]")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(axis), listOf(UmaMeshCell(listOf(0), FloatArray(4)))))), "drawables[D].geometry.cells[0].positionDeltas")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(axis), listOf(UmaMeshCell(listOf(2), FloatArray(6)))))), "drawables[D].geometry.cells[0].coordinate[0]")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(axis), listOf(UmaMeshCell(listOf(0, 0), FloatArray(6)))))), "drawables[D].geometry.cells[0].coordinate")
		assertRefused(
			withDrawable(UmaDrawable("D", "D", channels = mapOf(UmaFormChannel.Opacity to UmaChannelGrid(listOf(axis), listOf(UmaChannelCell(listOf(0), JsonPrimitive(true))))))),
			"drawables[D].channels.opacity.cells[0].value",
		)
		assertRefused(
			withDrawable(UmaDrawable("D", "D", channels = mapOf(UmaFormChannel.FlipX to UmaChannelGrid(listOf(axis), listOf(UmaChannelCell(listOf(0), JsonPrimitive(1))))))),
			"drawables[D].channels.flipX.cells[0].value",
		)
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(0f, 1f), 0, listOf(null))))), "drawables[D].blendShapes[0]")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(0f, 1f), 2, listOf(null, UmaMeshForm(FloatArray(6))))))), "drawables[D].blendShapes[0].neutralIndex")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(UmaAxis("P0", listOf(1f, 0f))), listOf(UmaMeshCell(listOf(0), FloatArray(6)))))), "drawables[D].geometry.axes[0].keys[1]")
		assertRefused(withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(1f, 0f), 0, listOf(null, UmaMeshForm(FloatArray(6))))))), "drawables[D].blendShapes[0].keys[1]")
		val descendingLimit = UmaBlendLimit("P0", listOf(UmaBlendLimitPoint(1f, 1f), UmaBlendLimitPoint(0f, 0f)))
		assertRefused(
			withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(0f, 1f), 0, listOf(null, UmaMeshForm(FloatArray(6))), listOf(descendingLimit))))),
			"drawables[D].blendShapes[0].limits[0].points[1]",
		)

		assertRefused(UmaPuppet(deformers = listOf(warp.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(6))))))), "deformers[W].geometry.cells[0].controlPoints")
		assertRefused(UmaPuppet(deformers = listOf(warp.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(8), angle = 1f)))))), "deformers[W].geometry.cells[0]")
		// 65536 x 65536 x 2 floats wraps to zero in 32 bits, which an empty lattice would then match.
		val hugeWarp = warp.copy(rows = 65535, columns = 65535, geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(0)))))
		assertRefused(UmaPuppet(deformers = listOf(hugeWarp)), "deformers[W] has a 65535 x 65535 lattice")
		assertRefused(
			UmaPuppet(deformers = listOf(warp.copy(blendShapes = listOf(UmaDeformerBlendShape("P1", listOf(0f, 1f), 0, listOf(null, UmaDeformerForm(controlPoints = FloatArray(8), flipX = true))))))),
			"deformers[W].blendShapes[0].forms[1]",
		)
		assertRefused(UmaPuppet(deformers = listOf(rotation.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), originX = 0f, originY = 0f, angle = 0f)))))), "deformers[R].geometry.cells[0]")
		assertRefused(
			UmaPuppet(deformers = listOf(rotation.copy(blendShapes = listOf(UmaDeformerBlendShape("P1", listOf(0f, 1f), 0, listOf(null, UmaDeformerForm(controlPoints = FloatArray(8)))))))),
			"deformers[R].blendShapes[0].forms[1] belongs to a rotation but carries controlPoints",
		)

		val glueMeshes = listOf(UmaDrawable("A", "A", mesh = mesh), UmaDrawable("B", "B", mesh = mesh))
		assertRefused(UmaPuppet(drawables = glueMeshes, glues = listOf(UmaGlue("A", "B", UmaGluePairs(intArrayOf(0, 1), intArrayOf(0), FloatArray(2), FloatArray(2))))), "glues[A,B].pairs")
		assertRefused(UmaPuppet(drawables = glueMeshes, glues = listOf(UmaGlue("A", "B", UmaGluePairs(intArrayOf(0), intArrayOf(5), FloatArray(1), FloatArray(1))))), "glues[A,B].pairs.indicesB[0]")
	}

	/**
	 * A save refuses a puppet that repeats an identity a reader would refuse, not only a broken shape.
	 */
	@Test
	fun repeatedIdentitiesRefuseTheSave() {
		val glueMeshes = listOf(UmaDrawable("A", "A", mesh = mesh), UmaDrawable("B", "B", mesh = mesh))
		val pairs = UmaGluePairs(intArrayOf(0), intArrayOf(0), FloatArray(1), FloatArray(1))
		assertFailsWith<UmaWriteException>("two glues on one mesh pair") {
			UmaModel.create(TEST_WRITER).withPuppet(UmaPuppet(drawables = glueMeshes, glues = listOf(UmaGlue("A", "B", pairs), UmaGlue("A", "B", pairs))))
		}
		assertFailsWith<UmaWriteException>("a drawable listed twice in a part") {
			UmaModel.create(TEST_WRITER).withPuppet(UmaPuppet(parts = listOf(UmaPart("P", "P", children = listOf(UmaOrgRef(drawable = "A"), UmaOrgRef(drawable = "A"))))))
		}
		assertFailsWith<UmaWriteException>("two drawables with one id") {
			UmaModel.create(TEST_WRITER).withPuppet(UmaPuppet(drawables = listOf(UmaDrawable("A", "A"), UmaDrawable("A", "Again"))))
		}
	}

	/**
	 * A file that breaks a shape rule fails the read with the rule's path, whether or not the rule involves a buffer.
	 */
	@Test
	fun brokenShapesFailTheRead() {
		/**
		 * The read failure of a file holding [puppetJson] and a buffer of [buffer].
		 *
		 * @param String    puppetJson The puppet entry's JSON text.
		 * @param ByteArray buffer     The buffer entry's bytes.
		 * @return String The failure's detail.
		 */
		fun readFailure(puppetJson: String, buffer: ByteArray = ByteArray(0)): String {
			val file =
				umaArchiveOf(
					manifestJson(listOf(recordJson("model/puppet.json", "puppet", required = true))),
					listOf(TestEntry("model/puppet.json", puppetJson.encodeToByteArray()), TestEntry("model/buffers.bin", buffer, deflated = false)),
				)
			val failure = assertFailsWith<UmaFormatException> { Uma.read(file) }.failure
			return assertIs<UmaReadFailure.MalformedEntry>(failure).detail
		}
		assertTrue(readFailure("""{ "rootChildren": [ {} ] }""").contains("rootChildren[0] must name exactly one"))

		// Six floats of positions and uvs, then the int32 indices 0, 1, and 3 of a three-vertex mesh.
		val buffer = ByteArray(60)
		for ((intIndex, value) in listOf(0, 1, 3).withIndex()) {
			for (byteIndex in 0 until 4) {
				buffer[48 + intIndex * 4 + byteIndex] = (value shr (8 * byteIndex)).toByte()
			}
		}

		/**
		 * An accessor into the test buffer.
		 *
		 * @param Int    offset        Where the run starts.
		 * @param Int    count         How many components.
		 * @param String componentType The component type.
		 * @return String The accessor's JSON text.
		 */
		fun accessor(offset: Int, count: Int, componentType: String): String =
			"""{ "buffer": "model/buffers.bin", "byteOffset": $offset, "byteLength": ${count * 4}, "count": $count, "componentType": "$componentType" }"""
		val meshJson = """{ "positions": ${accessor(0, 6, "float32")}, "uvs": ${accessor(24, 6, "float32")}, "indices": ${accessor(48, 3, "int32")} }"""
		val detail = readFailure("""{ "drawables": [ { "id": "D", "name": "D", "mesh": $meshJson } ] }""", buffer)
		assertTrue(detail.contains("drawables[D].mesh.indices[2] is 3"), detail)
	}
}