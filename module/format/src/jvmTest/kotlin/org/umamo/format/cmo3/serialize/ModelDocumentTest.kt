package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the ModelDocument backbone: parses the sample's main.xml into navigable parts (versions,
 * imports, shared pool, main root) and re-emits byte-identically. Skips without the sample.
 */
class ModelDocumentTest {
	private val sample: File? =
		System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun parsesPartsAndReemitsByteIdentical() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping ModelDocument test")
			return
		}
		val mainXml =
			CaffCodec.read(file.readBytes())
				.firstByTag(CaffArchive.TAG_MAIN_XML)?.content ?: error("no main_xml")

		val doc = ModelDocument.parse(mainXml)

		// Version PIs (sample has 8, including the known CModelSource:13 / CArtMeshSource:4).
		assertEquals(13, doc.versionOf("CModelSource"), "CModelSource version")
		assertEquals(4, doc.versionOf("CArtMeshSource"), "CArtMeshSource version")
		assertEquals(8, doc.versions.size, "version PI count")

		// Import PIs (sample has 158, all fully-qualified class names).
		assertEquals(158, doc.imports.size, "import PI count")
		assertTrue(doc.imports.all { '.' in it }, "imports are fully-qualified")
		assertTrue(
			doc.imports.any { it.endsWith("controller.CControllerExtension") },
			"imports include CControllerExtension",
		)

		// Structure: a non-empty shared pool and a single CModelSource main root.
		assertTrue(doc.sharedElements.isNotEmpty(), "shared pool non-empty")
		assertEquals(1, doc.mainElements.size, "single main root")
		assertEquals("CModelSource", doc.mainElements.single().name, "main root is CModelSource")

		// Backbone must re-emit byte-identical (verbatim fallback intact).
		assertContentEquals(mainXml, doc.write(), "ModelDocument round-trip byte-identical")
	}
}
