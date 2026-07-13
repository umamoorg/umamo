package org.umamo.format.cmo3.xml

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * End-to-end XML fidelity: cmo3 -> CAFF -> main.xml bytes -> JDOM parse -> emit -> identical bytes.
 *
 * This locks the editor's XML formatting contract - re-emitted bytes must be byte-identical to the
 * original. Skips when the sample is unavailable.
 */
class XmlRoundTripTest {
	private val sample: File? =
		System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun mainXmlRoundTripsByteIdentical() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping XML round-trip test")
			return
		}
		val archive = CaffCodec.read(file.readBytes())
		val mainXml =
			archive.firstByTag(CaffArchive.TAG_MAIN_XML)?.content
				?: error("no main_xml entry")

		val reemitted = XmlCodec.write(XmlCodec.parse(mainXml))
		assertTrue(reemitted.isNotEmpty(), "non-empty output")
		assertContentEquals(mainXml, reemitted, "main.xml must round-trip byte-identical via JDOM")
	}
}
