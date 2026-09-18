package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import org.umamo.format.binary.ZipArchive
import org.umamo.format.binary.ZipRecords
import org.umamo.format.uma.TEST_WRITER
import org.umamo.format.uma.TestEntry
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaAccessor
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.manifestJson
import org.umamo.format.uma.recordJson
import org.umamo.format.uma.umaArchiveOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the puppet entry's buffer (docs/format/UMA.md §4.9, D9, D10, D19): what an accessor is, what a sound one
 * must satisfy, how a save lays the owned buffer out, and that an accessor under a key this reader does not know
 * keeps its bytes when the buffer is rebuilt around it.
 */
class UmaBufferTest {
	private val bufferPath = "model/buffers.bin"

	/**
	 * Little-endian float32 bytes.
	 *
	 * @param Float values The values.
	 * @return ByteArray The bytes.
	 */
	private fun float32(vararg values: Float): ByteArray = Buffer().also { buffer -> values.forEach { value -> buffer.writeIntLe(value.toRawBits()) } }.readByteArray()

	/**
	 * Little-endian int32 bytes.
	 *
	 * @param Int values The values.
	 * @return ByteArray The bytes.
	 */
	private fun int32(vararg values: Int): ByteArray = Buffer().also { buffer -> values.forEach { value -> buffer.writeIntLe(value) } }.readByteArray()

	/**
	 * An accessor's JSON text.
	 *
	 * @param String buffer        The buffer path.
	 * @param Int    byteOffset    The offset.
	 * @param Int    count         The component count.
	 * @param String componentType The component type.
	 * @param Int    byteLength    The byte length; four per component unless given.
	 * @return String The JSON.
	 */
	private fun accessor(buffer: String, byteOffset: Int, count: Int, componentType: String, byteLength: Int = count * 4): String =
		"""{ "buffer": "$buffer", "byteOffset": $byteOffset, "byteLength": $byteLength, "count": $count, "componentType": "$componentType" }"""

	/**
	 * A file holding a puppet entry and a buffer.
	 *
	 * @param String    puppetJson The puppet entry's JSON text.
	 * @param ByteArray buffer     The buffer's bytes, or null for no buffer.
	 * @param List      extra      Further entries.
	 * @return ByteArray The file.
	 */
	private fun fileWith(puppetJson: String, buffer: ByteArray?, extra: List<TestEntry> = emptyList()): ByteArray =
		umaArchiveOf(
			manifestJson(listOf(recordJson("model/puppet.json", "puppet", required = true))),
			listOfNotNull(TestEntry("model/puppet.json", puppetJson.encodeToByteArray()), buffer?.let { bytes -> TestEntry(bufferPath, bytes, deflated = false) }) + extra,
		)

	/**
	 * A one-triangle mesh whose arrays sit in the buffer after [prefixBytes] bytes of something else.
	 *
	 * @param Int prefixBytes Bytes before the mesh arrays.
	 * @return String The mesh's JSON text.
	 */
	private fun triangleMeshJson(prefixBytes: Int): String =
		"""{ "positions": ${accessor(bufferPath, prefixBytes, 6, "float32")}, "uvs": ${accessor(bufferPath, prefixBytes + 24, 6, "float32")}, "indices": ${accessor(bufferPath, prefixBytes + 48, 3, "int32")} }"""

	/** The triangle mesh's bytes, in the order [triangleMeshJson] names them. */
	private val triangleBytes: ByteArray = float32(0f, 0f, 10f, 0f, 0f, 10f) + float32(0f, 0f, 1f, 0f, 0f, 1f) + int32(0, 1, 2)

	/**
	 * An accessor is exactly five keys of the right types; anything else is ordinary data.
	 */
	@Test
	fun accessorShapeIsExact() {
		/**
		 * Parses JSON text as an object.
		 *
		 * @param String text The JSON.
		 * @return JsonObject The object.
		 */
		fun objectOf(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
		assertNotNull(UmaAccessor.of(objectOf(accessor(bufferPath, 0, 2, "float32"))), "the five keys")
		assertNotNull(UmaAccessor.of(objectOf(accessor(bufferPath, 0, 2, "float64", byteLength = 16))), "an unknown component type is still an accessor")
		assertNull(UmaAccessor.of(objectOf("""{ "buffer": "b", "byteOffset": 0, "byteLength": 8, "count": 2, "componentType": "float32", "stride": 4 }""")), "an extra key")
		assertNull(UmaAccessor.of(objectOf("""{ "buffer": "b", "byteOffset": "0", "byteLength": 8, "count": 2, "componentType": "float32" }""")), "a quoted offset")
		assertNull(UmaAccessor.of(objectOf("""{ "buffer": "b", "byteOffset": -4, "byteLength": 8, "count": 2, "componentType": "float32" }""")), "a negative offset")
		assertNull(UmaAccessor.of(objectOf("""{ "buffer": "b", "byteOffset": 0, "byteLength": 8, "count": 2 }""")), "a missing key")
	}

	/**
	 * A sound file reads its mesh straight out of the buffer.
	 */
	@Test
	fun meshReadsFromTheBuffer() {
		val model = Uma.read(fileWith("""{ "drawables": [ { "id": "D", "name": "D", "mesh": ${triangleMeshJson(0)} } ] }""", triangleBytes))
		val mesh = assertNotNull(model.puppet!!.drawables!!.single().mesh)
		assertContentEquals(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), mesh.positions)
		assertContentEquals(intArrayOf(0, 1, 2), mesh.indices)
	}

	/**
	 * Each unsound accessor fails the read as a malformed entry, including one under a key this reader does not
	 * know, since a save would have to copy its bytes.
	 */
	@Test
	fun unsoundAccessorsAreMalformed() {
		/**
		 * Asserts that a puppet entry over [buffer] fails as malformed.
		 *
		 * @param String     puppetJson The puppet entry's JSON text.
		 * @param ByteArray? buffer     The buffer's bytes.
		 * @param String     label      What is wrong.
		 */
		fun assertMalformed(puppetJson: String, buffer: ByteArray?, label: String) {
			val failure = assertFailsWith<UmaFormatException>(label) { Uma.read(fileWith(puppetJson, buffer)) }.failure
			assertIs<UmaReadFailure.MalformedEntry>(failure, label)
		}

		/**
		 * A one-drawable puppet whose mesh positions use [positions].
		 *
		 * @param String positions The positions accessor's JSON.
		 * @return String The puppet's JSON.
		 */
		fun withPositions(positions: String): String =
			"""{ "drawables": [ { "id": "D", "name": "D", "mesh": { "positions": $positions, "uvs": ${accessor(bufferPath, 24, 6, "float32")}, "indices": ${accessor(bufferPath, 48, 3, "int32")} } } ] }"""

		assertMalformed(withPositions(accessor(bufferPath, 0, 6, "float32")), null, "no buffer at all")
		assertMalformed(withPositions(accessor("model/other.bin", 0, 6, "float32")), triangleBytes, "a buffer the archive does not hold")
		assertMalformed(withPositions(accessor(bufferPath, 40, 6, "float32")), triangleBytes, "bytes past the buffer's end")
		assertMalformed(withPositions(accessor(bufferPath, 0, 6, "float32", byteLength = 20)), triangleBytes, "a length that is not four per component")
		assertMalformed(withPositions(accessor(bufferPath, 2, 5, "float32")), triangleBytes, "an offset off the component alignment")
		assertMalformed(withPositions(accessor(bufferPath, 0, 6, "int32")), triangleBytes, "an int32 accessor on a float field")
		assertMalformed(
			"""{ "drawables": [ { "id": "D", "name": "D", "mesh": ${triangleMeshJson(0)}, "futureWeights": ${accessor(bufferPath, 56, 4, "float32")} } ] }""",
			triangleBytes,
			"an unsound accessor under an unknown key",
		)
	}

	/**
	 * A save writes the buffer stored, right after the puppet entry, with every array in document order on a
	 * four-byte boundary; with nothing unknown in it, the bytes do not depend on the file's history.
	 */
	@Test
	fun saveLaysTheBufferOut() {
		val first = UmaDrawable("A", "A", mesh = UmaMesh(floatArrayOf(1f, 2f, 3f, 4f), floatArrayOf(0f, 0f, 1f, 1f), intArrayOf(0, 1, 1)))
		val second = UmaDrawable("B", "B", mesh = UmaMesh(floatArrayOf(5f, 6f), floatArrayOf(0.5f, 0.5f), intArrayOf(0, 0, 0)))
		val puppet = UmaPuppet(drawables = listOf(first, second))
		val bytes = Uma.write(UmaModel.create(TEST_WRITER).withPuppet(puppet))

		val archive = ZipArchive.read(bytes)
		assertEquals(listOf("mimetype", "manifest.json", "model/puppet.json", bufferPath), archive.entries.map { entry -> entry.name }, "the buffer follows its entry")
		assertEquals(ZipRecords.METHOD_STORED, archive.entry(bufferPath)!!.method, "the buffer is stored")

		val tree = Uma.read(bytes).liveContent(UmaEntryKind.Puppet)!!
		val offsets =
			(tree["drawables"] as JsonArray).flatMap { drawable ->
				val mesh = (drawable as JsonObject)["mesh"] as JsonObject
				listOf("positions", "uvs", "indices").map { key -> UmaAccessor.of(mesh[key]!!)!!.byteOffset }
			}
		assertEquals(listOf(0, 16, 32, 44, 52, 60), offsets, "document order, four bytes per component, no gaps")

		val otherHistory = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(UmaPuppet(drawables = listOf(second, first)))))
		assertContentEquals(bytes, Uma.write(otherHistory.withPuppet(puppet)), "the same puppet writes the same bytes whatever came before")
		assertContentEquals(bytes, Uma.write(Uma.read(bytes)), "an unedited reopen rewrites byte for byte")
	}

	/**
	 * An accessor a newer writer put under a key this reader does not know keeps its bytes when a mesh edit
	 * rebuilds the buffer around it (the D10 hazard), and an accessor naming another buffer is copied into the
	 * owned one while that other buffer stays in the file.
	 */
	@Test
	fun unknownAccessorsKeepTheirBytesThroughARebuild() {
		val weights = int32(7, 8, 9)
		val extraBytes = float32(0.25f, 0.5f)
		val puppetJson =
			"""{ "drawables": [ { "id": "D", "name": "D", "mesh": ${triangleMeshJson(12)}, "futureWeights": ${accessor(bufferPath, 0, 3, "int32")}, "futureCurve": ${accessor("model/extra.bin", 0, 2, "float32")} } ] }"""
		val document = Uma.read(fileWith(puppetJson, weights + triangleBytes, listOf(TestEntry("model/extra.bin", extraBytes, deflated = false))))

		val edited = document.puppet!!.let { puppet -> puppet.copy(drawables = listOf(puppet.drawables!!.single().copy(mesh = UmaMesh(FloatArray(8) { value -> value.toFloat() }, FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3))))) }
		val saved = Uma.read(Uma.write(document.withPuppet(edited)))
		val drawable = ((saved.liveContent(UmaEntryKind.Puppet)!!["drawables"] as JsonArray).single() as JsonObject)

		val weightsAccessor = assertNotNull(UmaAccessor.of(drawable["futureWeights"]!!), "the unknown accessor survives")
		assertEquals(bufferPath, weightsAccessor.buffer)
		val buffer = saved.bufferBytes(bufferPath)!!
		assertContentEquals(weights, buffer.copyOfRange(weightsAccessor.byteOffset, weightsAccessor.byteOffset + weightsAccessor.byteLength), "with its own bytes")

		val curveAccessor = assertNotNull(UmaAccessor.of(drawable["futureCurve"]!!))
		assertEquals(bufferPath, curveAccessor.buffer, "an accessor naming another buffer now names the owned one")
		assertContentEquals(extraBytes, buffer.copyOfRange(curveAccessor.byteOffset, curveAccessor.byteOffset + curveAccessor.byteLength), "with the bytes it named")
		assertTrue(saved.payloads.any { payload -> payload.path == "model/extra.bin" }, "and the other buffer stays in the file")

		assertContentEquals(FloatArray(8) { value -> value.toFloat() }, saved.puppet!!.drawables!!.single().mesh!!.positions, "the edit lands")
	}

	/**
	 * An accessor of a component type this reader does not know is laid out on a multiple of 8, so a newer reader that
	 * knows the type finds it aligned however odd the run before it.
	 */
	@Test
	fun unknownComponentTypesAlignToEight() {
		val oddRun = byteArrayOf(1, 2, 3)
		val doubleBytes = byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)
		val puppetJson =
			"""{ "drawables": [ { "id": "D", "name": "D", "futureBytes": ${accessor(bufferPath, 0, 3, "uint8", byteLength = 3)}, "futureDouble": ${accessor(bufferPath, 3, 1, "float64", byteLength = 8)} } ] }"""
		val document = Uma.read(fileWith(puppetJson, oddRun + doubleBytes))
		val saved = Uma.read(Uma.write(document.withPuppet(document.puppet!!)))
		val drawable = ((saved.liveContent(UmaEntryKind.Puppet)!!["drawables"] as JsonArray).single() as JsonObject)
		val doubleAccessor = assertNotNull(UmaAccessor.of(drawable["futureDouble"]!!))
		assertEquals(0, doubleAccessor.byteOffset % 8, "the run starts on a multiple of 8: ${doubleAccessor.byteOffset}")
		val buffer = saved.bufferBytes(bufferPath)!!
		assertContentEquals(doubleBytes, buffer.copyOfRange(doubleAccessor.byteOffset, doubleAccessor.byteOffset + doubleAccessor.byteLength), "with its own bytes")
	}

	/**
	 * An owned buffer nothing references any more is not written.
	 */
	@Test
	fun unreferencedOwnedBufferIsDropped() {
		val document = Uma.read(fileWith("""{ "drawables": [ { "id": "D", "name": "D", "mesh": ${triangleMeshJson(0)} } ] }""", triangleBytes))
		val saved = Uma.write(document.withPuppet(UmaPuppet(drawables = listOf(UmaDrawable("D", "D")))))
		assertFalse(ZipArchive.read(saved).entries.any { entry -> entry.name == bufferPath }, "no buffer without an accessor")
		assertEquals(JsonPrimitive("D"), ((Uma.read(saved).liveContent(UmaEntryKind.Puppet)!!["drawables"] as JsonArray).single() as JsonObject)["id"])
	}
}