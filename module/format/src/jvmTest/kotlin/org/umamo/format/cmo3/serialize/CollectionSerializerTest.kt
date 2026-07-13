package org.umamo.format.cmo3.serialize

import org.jdom.Element
import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SerialTag("Mode")
enum class Mode { ALPHA, BETA }

@SerialTag("Point2")
class Point2 {
	var x: Float = 0f
	var y: Float = 0f
}

@SerialTag("Bag")
class Bag {
	var mode: Mode = Mode.ALPHA
	var coords: FloatArray = FloatArray(0)
	var labels: Map<String, Int> = emptyMap()
	var point: Point2? = null
}

/** A custom (attribute-based) serializer for a value-type. */
private object Point2Serializer : XmlSerializer {
	override fun createElement(name: String?, value: Any, ctx: WriteContext): Element {
		val point = value as Point2
		val element = Element("Point2")
		element.setFieldName(name)
		element.setAttribute("x", point.x.toString())
		element.setAttribute("y", point.y.toString())
		return element
	}

	override fun createInstance(element: Element, ctx: ReadContext): Any =
		Point2().apply {
			x = element.getAttributeValue("x").toFloat()
			y = element.getAttributeValue("y").toFloat()
		}
}

/** Exercises enums, typed arrays, maps, and a custom attribute serializer through full XML round-trip. */
class CollectionSerializerTest {
	private fun engine(): SerializeEngine {
		val registry = SerializerRegistry()
		registry.register(Bag::class)
		registry.register(Mode::class)
		registry.registerCustom(Point2::class, Point2Serializer)
		return SerializeEngine(registry, SerializeDiagnostics.None)
	}

	@Test
	fun roundTripsEnumArrayMapAndCustom() {
		val bag =
			Bag().apply {
				mode = Mode.BETA
				coords = floatArrayOf(1.5f, 2.0f, 3.25f)
				labels = linkedMapOf("a" to 1, "b" to 2)
				point =
					Point2().apply {
						x = 4f
						y = 5f
					}
			}

		val engine = engine()
		val xml = XmlCodec.write(engine.writeRoot(bag)).decodeToString()
		assertTrue("<Mode xs.n=\"mode\" v=\"BETA\" />" in xml, "enum stored as v attribute")
		assertTrue("<float-array xs.n=\"coords\" count=\"3\">1.5 2.0 3.25</float-array>" in xml, "float-array text")
		// String-keyed maps store each value as <Value xs.n="key"> (no <entry> wrapper).
		assertTrue("<linked_map" in xml && "<i xs.n=\"a\">1</i>" in xml, "string-keyed map form")
		assertTrue("<Point2 xs.n=\"point\" x=\"4.0\" y=\"5.0\" />" in xml, "custom attribute serializer")

		val restored = engine.readRoot(XmlCodec.parse(xml.toByteArray())) as Bag
		assertEquals(Mode.BETA, restored.mode)
		assertContentEquals(floatArrayOf(1.5f, 2.0f, 3.25f), restored.coords)
		assertEquals(mapOf("a" to 1, "b" to 2), restored.labels)
		assertEquals(4f, restored.point?.x)
		assertEquals(5f, restored.point?.y)
	}
}
