package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.diffPuppetModels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The export reconcile's no-change gate, across the whole corpus: importing a CMO3 and applying the
 * unchanged model back must leave the graph untouched - zero notices, and the re-emitted
 * decompressed main.xml byte-identical to the source.  Also pins that two imports of the same graph
 * diff empty (import determinism), which is what the reconcile baseline relies on.
 */
class Cmo3ExportIdentityTest {
	@Test
	fun unchangedModelAppliesAsAByteIdenticalNoOp() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping export identity gate")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		if (files.isEmpty()) {
			println("cmo3.probe lists no readable samples; skipping")
			return
		}

		val failures = ArrayList<String>()
		for (file in files) {
			val bytes = file.readBytes()
			val sourceMainXml = CaffCodec.read(bytes).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			val cmo3 = Cmo3.read(bytes)
			val modelSource = cmo3.root as? CModelSource
			if (modelSource == null) {
				failures.add("${file.name}: root is not a CModelSource")
				continue
			}
			val puppet = Cmo3Import.fromModelSource(modelSource)

			// Import determinism: the reconcile baseline is a second import of the same graph, so
			// two imports must diff empty or every export would see phantom changes.
			val baseline = Cmo3Import.fromModelSource(modelSource)
			val selfDiff = diffPuppetModels(baseline, puppet)
			if (!selfDiff.isEmpty) {
				failures.add("${file.name}: two imports of one graph diff non-empty: $selfDiff")
				continue
			}

			val report = Cmo3Export.apply(puppet, cmo3)
			if (!report.isEmpty) {
				failures.add("${file.name}: no-change apply produced notices: ${report.notices}")
				continue
			}

			val reemittedMainXml =
				CaffCodec.read(Cmo3.write(cmo3)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			firstByteDiff(sourceMainXml, reemittedMainXml)?.let { message ->
				failures.add("${file.name}: $message")
			}
		}
		assertTrue(failures.isEmpty(), "export identity gate failed:\n" + failures.joinToString("\n"))
	}

	/**
	 * The first byte difference between the two buffers as a windowed diagnostic, or null when they
	 * are byte-identical (the AllVersionsGateTest reporter shape).
	 *
	 * @param ByteArray expected The source main.xml bytes.
	 * @param ByteArray actual   The re-emitted main.xml bytes.
	 * @return String? The diagnostic, or null on identity.
	 */
	private fun firstByteDiff(expected: ByteArray, actual: ByteArray): String? {
		val limit = minOf(expected.size, actual.size)
		val diff = (0 until limit).firstOrNull { expected[it] != actual[it] } ?: -1
		if (diff < 0 && expected.size == actual.size) {
			return null
		}
		val at = if (diff < 0) limit else diff
		val start = maxOf(0, at - 80)

		fun ByteArray.window() = decodeToString(start, minOf(size, at + 90)).replace("\r", "\\r").replace("\n", "\\n")
		return buildString {
			append("not byte-identical: sizes expected=${expected.size} actual=${actual.size}, first diff @ $at\n")
			append("  expected: …${expected.window()}\n")
			append("  actual:   …${actual.window()}")
		}
	}
}