package org.umamo.format

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Version
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corpus-gated `getVersion` probes: real files must report a known version without a full read.
 * Skips (with a note) when the sample properties are absent, like the other corpus tests.
 */
class GetVersionCorpusTest {
	private val cmo3Sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }
	private val mocSamplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	@Test
	fun cmo3SampleProbesToAKnownGeneration() {
		val file =
			cmo3Sample ?: run {
				println("cmo3.sample not present; skipping cmo3 getVersion corpus test")
				return
			}
		val version = Cmo3.getVersion(file.readBytes())
		assertTrue(version is Cmo3Version, "cmo3 sample probes to a Cmo3Version, got $version")
	}

	@Test
	fun mocSamplesProbeToTheirVersionByte() {
		val directory =
			mocSamplesDir ?: run {
				println("moc3.samples not present; skipping moc3 getVersion corpus test")
				return
			}
		val samples =
			directory.walkTopDown().filter { it.isFile && it.extension.equals("moc3", ignoreCase = true) }.toList()
		if (samples.isEmpty()) {
			println("moc3.samples contains no .moc3 files; skipping")
			return
		}
		for (sample in samples) {
			val bytes = sample.readBytes()
			val version = Moc3.getVersion(bytes)
			assertTrue(version is MocVersion, "${sample.name} probes to a MocVersion, got $version")
			// MOC3.md §version gating: the probe must agree with the raw byte @ +0x04.
			assertEquals(bytes[4].toInt() and 0xFF, version.byteValue, "${sample.name} version byte")
		}
	}
}
