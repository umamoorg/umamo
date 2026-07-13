package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-version parity gate: every sample listed in -Dcmo3.probe (comma-separated, normally the local
 * set of `.cmo3` files under `cmo3/` spanning Cubism 3.x/4.x/5.3) must round-trip byte-identical with
 * zero verbatim (unmodeled) tags. The samples are git-ignored, so a public build skips this gracefully.
 */
class AllVersionsGateTest {
	@Test
	fun everySampleRoundTripsFullyTypedAndByteIdentical() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping cross-version gate")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		if (files.isEmpty()) {
			println("cmo3.probe lists no readable samples; skipping")
			return
		}

		for (file in files) {
			val mainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			val unmodeled = sortedSetOf<String>()
			val engine = cubismEngine { tag, _ -> unmodeled += tag }
			val reemitted = XmlCodec.write(engine.writeModel(engine.readModel(XmlCodec.parse(mainXml))))

			assertEquals(sortedSetOf<String>(), unmodeled, "${file.name} has unmodeled tags")

			val limit = minOf(mainXml.size, reemitted.size)
			val diff = (0 until limit).firstOrNull { mainXml[it] != reemitted[it] } ?: -1
			val identical = diff < 0 && mainXml.size == reemitted.size
			assertTrue(
				identical,
				buildString {
					append("${file.name} not byte-identical: sizes expected=${mainXml.size} actual=${reemitted.size}")
					val at = if (diff < 0) limit else diff
					append(", first diff @ $at\n")
					val start = maxOf(0, at - 80)

					fun ByteArray.window() = decodeToString(start, minOf(size, at + 90)).replace("\r", "\\r").replace("\n", "\\n")
					append("  expected: …${mainXml.window()}\n")
					append("  actual:   …${reemitted.window()}")
				},
			)
		}
	}
}
