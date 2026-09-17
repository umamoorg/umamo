package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.uma.TEST_WRITER
import org.umamo.format.uma.TestEntry
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.manifestJson
import org.umamo.format.uma.recordJson
import org.umamo.format.uma.umaArchiveOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins how the puppet entry decodes (docs/format/UMA.md §4.8): what the schema refuses fails the read with
 * a malformed-entry reason, and what it does not know is ignored while the tree keeps it.
 */
class UmaPuppetReadTest {
	/**
	 * A UMA file whose puppet entry is [puppetJson].
	 *
	 * @param String puppetJson The puppet entry's JSON text.
	 * @return ByteArray The file.
	 */
	private fun fileWithPuppet(puppetJson: String): ByteArray =
		umaArchiveOf(
			manifestJson(listOf(recordJson("model/puppet.json", "puppet", required = true))),
			listOf(TestEntry("model/puppet.json", puppetJson.encodeToByteArray())),
		)

	/**
	 * Asserts that reading a puppet entry fails as malformed, and returns the detail.
	 *
	 * @param String puppetJson The puppet entry's JSON text.
	 * @param String label      What is wrong with it.
	 * @return String The failure's detail.
	 */
	private fun assertMalformed(puppetJson: String, label: String): String {
		val failure = assertFailsWith<UmaFormatException>(label) { Uma.read(fileWithPuppet(puppetJson)) }.failure
		return assertIs<UmaReadFailure.MalformedEntry>(failure, label).detail
	}

	/**
	 * Each thing the schema refuses fails the read.
	 */
	@Test
	fun schemaViolationsAreMalformed() {
		assertMalformed("""{ "parameters": [ { "id": "ParamA", "name": "A", "max": 1, "default": 0 } ] }""", "a missing required field")
		assertMalformed("""{ "drawables": [ { "id": "D", "name": "D", "blendMode": "plusDarker" } ] }""", "an unknown enum value")
		assertMalformed("""{ "parts": [ { "id": "P", "name": "P", "drawOrder": "high" } ] }""", "a wrong type")
		assertMalformed("""{ "parts": [ { "id": "P", "name": "P", "drawOrder": 500.5 } ] }""", "a fraction where an integer belongs")
		assertMalformed("""{ "canvasWidth": 1e400 }""", "a number beyond a float")
		assertMalformed("""{ "drawables": [ { "id": "D", "name": 5 } ] }""", "a number where a string belongs")
		val duplicate = assertMalformed("""{ "parts": [ { "id": "P", "name": "P" }, { "id": "P", "name": "Q" } ] }""", "a repeated id")
		assertTrue(duplicate.contains("parts[1]"), "the failure names the repeat: $duplicate")
		assertMalformed("""{ "rootChildren": [ {} ] }""", "an org reference naming nothing")
		assertMalformed("""{ "rootChildren": [ { "part": "X" }, { "part": "X" } ] }""", "a repeated org reference")
	}

	/**
	 * Keys the schema does not know decode without complaint, and the tree keeps them.
	 */
	@Test
	fun unknownKeysAreIgnoredAndKept() {
		val model = Uma.read(fileWithPuppet("""{ "canvasWidth": 800, "futureSetting": { "enabled": true }, "parts": [ { "id": "P", "name": "P", "futurePartKey": 3 } ] }"""))
		assertEquals(800f, model.puppet!!.canvasWidth)
		assertEquals(listOf(UmaPart("P", "P")), model.puppet!!.parts)
		assertEquals(JsonPrimitive(3), elementOf(model.liveContent(UmaEntryKind.Puppet)!!, "parts", "id", "P")!!["futurePartKey"])
	}

	/**
	 * A part and a drawable may share a raw id, since org references are identified by kind.
	 */
	@Test
	fun kindsKeepSharedRawIdsApart() {
		val model = Uma.read(fileWithPuppet("""{ "rootChildren": [ { "part": "Head" }, { "drawable": "Head" } ] }"""))
		assertEquals(listOf(UmaOrgRef(part = "Head"), UmaOrgRef(drawable = "Head")), model.puppet!!.rootChildren)
	}

	/**
	 * A non-finite float refuses the save rather than writing something JSON cannot hold.
	 */
	@Test
	fun nonFiniteValuesRefuseTheSave() {
		assertFailsWith<UmaWriteException> { UmaModel.create(TEST_WRITER).withPuppet(UmaPuppet(canvasWidth = Float.NaN)) }
		assertFailsWith<UmaWriteException> { UmaModel.create(TEST_WRITER).withPuppet(UmaPuppet(drawables = listOf(UmaDrawable("D", "D", opacity = Float.POSITIVE_INFINITY)))) }
	}

	/**
	 * Floats survive a save bit for bit, including the ones a decimal round trip is most likely to bend.
	 */
	@Test
	fun floatsRoundTripBitExact() {
		val values = listOf(-0.0f, 0.0f, Float.MIN_VALUE, 1.4e-44f, 0.1f, 1f / 3f, 16777217f, Float.MAX_VALUE, -Float.MAX_VALUE)
		val puppet = UmaPuppet(parameters = values.mapIndexed { valueIndex, value -> UmaParameter("P$valueIndex", "P", value, value, value) })
		val decoded = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withPuppet(puppet))).puppet!!.parameters!!
		for ((valueIndex, value) in values.withIndex()) {
			assertEquals(value.toRawBits(), decoded[valueIndex].default.toRawBits(), "$value survives bit for bit")
		}
	}
}