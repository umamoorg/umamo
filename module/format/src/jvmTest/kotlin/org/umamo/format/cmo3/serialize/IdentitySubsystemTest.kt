package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exercises the identity subsystem (*Guid / *Id). Confirms the reconciliation gate stays byte-identical
 * with these typed, and that GUID/Id tags drop out of the unmodeled set (typing has real effect).
 */
class IdentitySubsystemTest {
	private val sample: File? =
		System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun identitySubsystemTypedAndStillByteIdentical() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping identity subsystem test")
			return
		}
		val mainXml =
			CaffCodec.read(file.readBytes())
				.firstByTag(CaffArchive.TAG_MAIN_XML)?.content ?: error("no main_xml")

		// Baseline: nothing typed -> count distinct unmodeled tags.
		val baseline = sortedSetOf<String>()
		SerializeEngine.of(emptyList()) { tag, _ -> baseline += tag }
			.readModel(XmlCodec.parse(mainXml))

		// Typed identity subsystem.
		val unmodeled = sortedSetOf<String>()
		val typedShared = mutableListOf<Any>()
		val engine = cubismEngine { tag, _ -> unmodeled += tag }
		val graph = engine.readModel(XmlCodec.parse(mainXml))

		// GUID/Id shared defs are typed objects, so they don't appear in the unmodeled set.
		assertTrue(unmodeled.none { it.endsWith("Guid") || it.endsWith("Id") }, "no GUID/Id left unmodeled: $unmodeled")
		assertTrue(unmodeled.size < baseline.size, "typing reduced unmodeled tags (${unmodeled.size} < ${baseline.size})")
		graph.sharedOrder.filterTo(typedShared) { it is Guid || it is Id }
		assertTrue(typedShared.isNotEmpty(), "some shared defs deserialized into Guid/Id")
		assertTrue(typedShared.any { it is Guid && it.uuid.isNotEmpty() }, "a Guid carries its uuid")

		// The gate: re-emit byte-identical.
		val reemitted = XmlCodec.write(engine.writeModel(graph))
		assertByteIdentical(mainXml, reemitted)
	}

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
		fail(
			"not byte-identical: sizes expected=${expected.size} actual=${actual.size}, first diff @ $at\n" +
				"  expected: …${expected.decodeToString(start, minOf(expected.size, at + 80)).replace("\r", "\\r").replace("\n", "\\n")}…\n" +
				"  actual:   …${actual.decodeToString(start, minOf(actual.size, at + 80)).replace("\r", "\\r").replace("\n", "\\n")}…",
		)
	}
}
