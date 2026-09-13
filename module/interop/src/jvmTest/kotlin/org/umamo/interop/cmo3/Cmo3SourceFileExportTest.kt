package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A relinked artwork file survives the CMO3 round trip: the record's new path and name lower onto
 * the layered image the editor decomposed the file into, so the re-read document points at the file
 * the rigger chose rather than at the path of the machine that first imported it.
 *
 * Skips without the corpus sample.
 */
class Cmo3SourceFileExportTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aRelinkedSourcePathAndNameSurviveTheRoundTrip() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping the source-file export gate")
			return
		}
		val cmo3 = Cmo3.read(file.readBytes())
		val puppet = Cmo3Import.fromModelSource(cmo3.root as CModelSource)
		assertTrue(puppet.sources.isNotEmpty(), "the sample lists its decomposed artwork")
		assertTrue(puppet.sources.all { source -> source.lastModified != null }, "the editor's psdFileLastModified comes in as the record's time")
		val relinked =
			puppet.copy(
				sources =
					puppet.sources.mapIndexed { index, source ->
						if (index == 0) {
							source.copy(name = "Relinked.clip", path = "/home/rigger/art/Relinked.clip", lastModified = 1_700_000_000_000L)
						} else {
							source.copy(path = "/home/rigger/art/${source.name}")
						}
					},
			)

		val report = Cmo3Export.apply(relinked, cmo3)
		assertTrue(report.notices.isEmpty(), "every listed file has an image to write to: ${report.notices}")

		val reread = Cmo3Import.fromModelSource(Cmo3.read(Cmo3.write(cmo3)).root as CModelSource)
		assertEquals(relinked.sources.map { source -> source.id to source.path }, reread.sources.map { source -> source.id to source.path }, "the new paths came back")
		val first = reread.sources.first()
		assertEquals("Relinked.clip", first.name, "the new name came back")
		assertEquals("clip", first.format, "and the format follows the name")
		assertEquals(1_700_000_000_000L, first.lastModified, "and so did the modification time")
		assertEquals(puppet.sources.drop(1).map { source -> source.lastModified }, reread.sources.drop(1).map { source -> source.lastModified }, "an untouched time stays")
		assertEquals(puppet.sources.first().layers.map { layer -> layer.key }, first.layers.map { layer -> layer.key }, "the inventory is untouched")
	}
}