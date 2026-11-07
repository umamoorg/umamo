package org.umamo.interop.moc3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the cdi3 lookup the editor's MOC3 loader and the CLI share: the manifest's DisplayInfo reference wins,
 * the `<basename>.cdi3.json` sibling is the fallback, and a candidate that does not parse is reported and
 * skipped rather than ending the search.
 */
class Moc3DisplayInfoLookupTest {
	/**
	 * A minimal cdi3 naming one parameter [parameterName].
	 *
	 * @param String parameterName The display name to give ParamAngleX.
	 * @return ByteArray The file's bytes.
	 */
	private fun cdi3(parameterName: String): ByteArray =
		"""
		{
			"Version": 3,
			"Parameters": [{ "Id": "ParamAngleX", "GroupId": "", "Name": "$parameterName" }],
			"ParameterGroups": [],
			"Parts": []
		}
		""".trimIndent().encodeToByteArray()

	/**
	 * Runs the lookup over an in-memory family directory.
	 *
	 * @param String? manifestReference The manifest's DisplayInfo reference.
	 * @param Map     files             The directory's files by relative name.
	 * @param MutableList unparseable   Collects the references reported as unparseable.
	 * @return String? The parsed file's ParamAngleX display name, or null when nothing was read.
	 */
	private fun lookUp(manifestReference: String?, files: Map<String, ByteArray>, unparseable: MutableList<String>): String? =
		Moc3Sidecars.readDisplayInfo(manifestReference, "model", { reference -> files[reference] }) { reference, _ ->
			unparseable.add(reference)
		}?.parameters?.single()?.name

	@Test
	fun theManifestReferenceWinsOverTheSibling() {
		val unparseable = ArrayList<String>()
		val files = mapOf("display/named.cdi3.json" to cdi3("From manifest"), "model.cdi3.json" to cdi3("Sibling"))

		assertEquals("From manifest", lookUp("display/named.cdi3.json", files, unparseable))
		assertEquals(emptyList(), unparseable)
	}

	@Test
	fun theSiblingIsReadWithoutAManifestReference() {
		val unparseable = ArrayList<String>()

		assertEquals("Sibling", lookUp(null, mapOf("model.cdi3.json" to cdi3("Sibling")), unparseable))
		assertEquals("Sibling", lookUp("missing.cdi3.json", mapOf("model.cdi3.json" to cdi3("Sibling")), unparseable))
		assertEquals(emptyList(), unparseable)
	}

	@Test
	fun anUnparseableCandidateIsReportedAndSkipped() {
		val unparseable = ArrayList<String>()
		val files = mapOf("named.cdi3.json" to "{ not json".encodeToByteArray(), "model.cdi3.json" to cdi3("Sibling"))

		assertEquals("Sibling", lookUp("named.cdi3.json", files, unparseable))
		assertEquals(listOf("named.cdi3.json"), unparseable)
	}

	@Test
	fun nothingReadableGivesNull() {
		val unparseable = ArrayList<String>()

		assertNull(lookUp(null, emptyMap(), unparseable))
		assertNull(lookUp("model.cdi3.json", mapOf("model.cdi3.json" to "[]".encodeToByteArray()), unparseable))
		assertEquals(listOf("model.cdi3.json"), unparseable, "the sibling named by the manifest is tried once, not twice")
	}
}