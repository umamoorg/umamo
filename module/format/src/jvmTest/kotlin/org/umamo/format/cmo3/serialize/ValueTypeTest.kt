package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CColor
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the value-type serializers reproduce the editor's exact forms (matched against the real
 * sample fragments) and round-trip. These types live inside containers, so they are exercised here
 * directly rather than via the whole-file gate.
 */
class ValueTypeTest {
	private val engine = cubismEngine()

	private fun roundTrip(value: Any): Pair<String, Any?> {
		val xml = XmlCodec.write(engine.writeRoot(value)).decodeToString()
		val restored = engine.readRoot(XmlCodec.parse(xml.toByteArray()))
		return xml to restored
	}

	@Test
	fun gvector2MatchesSampleForm() {
		val (xml, restored) =
			roundTrip(
				GVector2().apply {
					x = 6742.0273f
					y = 6710.0273f
				},
			)
		assertTrue("<GVector2>\r\n<f xs.n=\"x\">6742.0273</f>\r\n<f xs.n=\"y\">6710.0273</f>\r\n</GVector2>" in xml, xml)
		restored as GVector2
		assertEquals(6742.0273f, restored.x)
		assertEquals(6710.0273f, restored.y)
	}

	@Test
	fun crectMatchesSampleForm() {
		val (xml, restored) =
			roundTrip(
				CRect().apply {
					x = 2197
					y = 535
					width = 309
					height = 439
				},
			)
		assertTrue("<i xs.n=\"x\">2197</i>" in xml && "<i xs.n=\"height\">439</i>" in xml, xml)
		restored as CRect
		assertEquals(2197, restored.x)
		assertEquals(439, restored.height)
	}

	@Test
	fun caffineMatchesSampleForm() {
		val (xml, restored) = roundTrip(CAffine()) // identity
		assertTrue("<CAffine m00=\"1.0\" m01=\"0.0\" m02=\"0.0\" m10=\"0.0\" m11=\"1.0\" m12=\"0.0\" />" in xml, xml)
		restored as CAffine
		assertEquals(1.0f, restored.m00)
		assertEquals(1.0f, restored.m11)
	}

	@Test
	fun ccolorSerializesEmpty() {
		val (xml, restored) = roundTrip(CColor().apply { argb = 0x11223344 })
		assertTrue("<CColor />" in xml, "CColor serializes empty (argb is @DontSerialize): $xml")
		assertTrue(restored is CColor)
	}
}
