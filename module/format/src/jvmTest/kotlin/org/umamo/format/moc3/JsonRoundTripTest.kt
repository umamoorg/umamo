package org.umamo.format.moc3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exact-string round-trip of the JSON sidecar fixtures(via the `moc3.samples` property).
 * Equality covers key order, tab indentation, and numeric-token fidelity. Skips
 * gracefully when a given sidecar type has no fixture.
 */
class JsonRoundTripTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun fixtures(suffix: String): List<File> =
		samplesDir?.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) }?.sortedBy { it.name } ?: emptyList()

	private fun checkRoundTrip(suffix: String, roundTrip: (String) -> String) {
		val files = fixtures(suffix)
		if (files.isEmpty()) {
			println("no *$suffix fixtures present; skipping")
			return
		}
		for (file in files) {
			val text = file.readText(Charsets.UTF_8)
			assertEquals(text, roundTrip(text), "${file.name}: exact-string round-trip")
		}
	}

	@Test
	fun model3RoundTrips(): Unit = checkRoundTrip(".model3.json") { Moc3.writeModel3(Moc3.readModel3(it)) }

	@Test
	fun physics3RoundTrips(): Unit = checkRoundTrip(".physics3.json") { Moc3.writePhysics3(Moc3.readPhysics3(it)) }

	@Test
	fun cdi3RoundTrips(): Unit = checkRoundTrip(".cdi3.json") { Moc3.writeCdi3(Moc3.readCdi3(it)) }

	@Test
	fun userData3RoundTrips(): Unit = checkRoundTrip(".userdata3.json") { Moc3.writeUserData3(Moc3.readUserData3(it)) }
}
