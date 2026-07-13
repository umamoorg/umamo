package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reconciliation gate: deconstruct the real main.xml into a ModelGraph (root object + shared
 * pool + PIs + root attrs) and re-emit it byte-identical. With an empty registry everything is
 * verbatim, proving the read/write reconstruction preserves shared ids/order, PIs, and structure.
 * Skips when the sample is unavailable.
 */
class ReconciliationTest {
	private val sample: File? =
		System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun realMainXmlReconstructsByteIdentical() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping reconciliation test")
			return
		}
		val mainXml =
			CaffCodec.read(file.readBytes())
				.firstByTag(CaffArchive.TAG_MAIN_XML)?.content ?: error("no main_xml")

		var unmodeled = 0
		val engine = SerializeEngine.of(emptyList(), diagnostics = { _, _ -> unmodeled++ })

		val graph = engine.readModel(XmlCodec.parse(mainXml))
		// Sanity: a verbatim CModelSource root and a populated shared pool were captured.
		assertTrue(graph.root is VerbatimNode, "root captured verbatim")
		assertEquals("CModelSource", (graph.root).tag)
		assertTrue(graph.sharedOrder.isNotEmpty(), "shared pool captured")
		assertTrue(unmodeled > 0, "unmodeled tags reported (nothing typed yet)")

		val reemitted = XmlCodec.write(engine.writeModel(graph))
		assertByteIdentical(mainXml, reemitted)
	}

	/** Memory-safe byte comparison: reports the first divergence without building a 24 MB message. */
	private fun assertByteIdentical(expected: ByteArray, actual: ByteArray) {
		val limit = minOf(expected.size, actual.size)
		var firstDiff = -1
		for (index in 0 until limit) {
			if (expected[index] != actual[index]) {
				firstDiff = index
				break
			}
		}
		if (firstDiff < 0 && expected.size == actual.size) return
		val at = if (firstDiff < 0) limit else firstDiff
		val start = maxOf(0, at - 60)
		val expectedWindow = expected.decodeToString(start, minOf(expected.size, at + 80))
		val actualWindow = actual.decodeToString(start, minOf(actual.size, at + 80))
		fail(
			"not byte-identical: sizes expected=${expected.size} actual=${actual.size}, " +
				"first diff @ $at\n  expected: …${expectedWindow.replace("\r", "\\r").replace("\n", "\\n")}…" +
				"\n  actual:   …${actualWindow.replace("\r", "\\r").replace("\n", "\\n")}…",
		)
	}
}
