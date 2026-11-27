package org.umamo.ui.workspace.spaces.sources

import kotlinx.coroutines.test.runTest
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how a probe's answers read as a file's presence: present, missing, or unknown, and unknown
 * wherever nothing could be checked.  No composition: the asking is a plain suspending function.
 */
class SourcesPresenceTest {
	private val artA = ArtSourceId("art-0")
	private val artB = ArtSourceId("art-1")
	private val artC = ArtSourceId("art-2")
	private val artD = ArtSourceId("art-3")

	private fun file(id: ArtSourceId, path: String?): ArtSource = ArtSource(id, "${id.raw}.psd", path, "psd")

	/** Each file reads as the probe answers for its path, and a path the probe cannot answer for reads unknown. */
	@Test
	fun eachFileReadsAsTheProbeAnswers() =
		runTest {
			val sources = listOf(file(artA, "/art/here.psd"), file(artB, "/art/gone.psd"), file(artC, "content://art/elsewhere"))
			val answers = mapOf("/art/here.psd" to true, "/art/gone.psd" to false)

			val presence = probeSourcePresence(sources) { path -> answers[path] }

			assertEquals(mapOf(artA to SourcePresence.Present, artB to SourcePresence.Missing, artC to SourcePresence.Unknown), presence)
		}

	/** A file that records no path is asked about nowhere, and reads unknown. */
	@Test
	fun aFileWithNoPathIsNeverAskedAbout() =
		runTest {
			val asked = ArrayList<String>()

			val presence =
				probeSourcePresence(listOf(file(artA, "/art/here.psd"), file(artD, null))) { path ->
					asked.add(path)
					true
				}

			assertEquals(listOf("/art/here.psd"), asked)
			assertEquals(SourcePresence.Unknown, presence[artD])
		}

	/** A platform with no probe leaves every file unknown, never missing. */
	@Test
	fun withNoProbeEveryFileReadsUnknown() =
		runTest {
			val presence = probeSourcePresence(listOf(file(artA, "/art/here.psd"), file(artB, "/art/gone.psd")), probe = null)

			assertTrue(presence.values.all { answer -> answer == SourcePresence.Unknown })
			assertEquals(setOf(artA, artB), presence.keys, "every file has its answer")
		}

	/** A document that lists no file has nothing to ask. */
	@Test
	fun noFilesNoAnswers() =
		runTest {
			assertTrue(probeSourcePresence(emptyList()) { true }.isEmpty())
		}
}