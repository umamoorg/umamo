package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SerialTag("Holder")
class Holder {
	var name: String = ""
	var payload: Any? = null
}

/**
 * Verifies the verbatim-JDOM fallback: an unmodeled tag inside a typed object is preserved
 * losslessly on round-trip and reported via SerializeDiagnostics.
 */
class VerbatimFallbackTest {
	@Test
	fun unmodeledSubtreeRoundTripsAndReports() {
		// A Holder (typed) whose `payload` is a class the engine does not know (<Mystery>).
		val sourceXml =
			"""
			<root><shared /><main>
			<Holder><s xs.n="name">hi</s><Mystery xs.n="payload" magic="42"><i xs.n="depth">5</i></Mystery></Holder>
			</main></root>
			""".trimIndent().toByteArray()

		val reported = mutableListOf<String>()
		val engine =
			SerializeEngine.of(
				listOf(Holder::class),
				diagnostics = { tag, path -> reported += "$tag@$path" },
			)

		val holder = engine.readRoot(XmlCodec.parse(sourceXml)) as Holder
		assertEquals("hi", holder.name)
		assertTrue(holder.payload is VerbatimNode, "unmodeled child kept verbatim")
		assertEquals("Mystery", (holder.payload as VerbatimNode).tag)

		// Diagnostics fired once, naming the unmodeled tag and its owning field.
		assertEquals(listOf("Mystery@payload"), reported)

		// Re-emit: the unmodeled subtree (attributes + nested element) survives intact.
		val out = XmlCodec.write(engine.writeRoot(holder)).decodeToString()
		assertTrue("<Mystery" in out && "magic=\"42\"" in out, "Mystery attributes preserved")
		assertTrue("xs.n=\"payload\"" in out, "field name re-stamped on verbatim node")
		assertTrue("<i xs.n=\"depth\">5</i>" in out, "nested unmodeled content preserved")
	}
}
