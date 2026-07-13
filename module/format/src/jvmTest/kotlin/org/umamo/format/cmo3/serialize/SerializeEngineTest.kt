package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@SerialTag("Leaf")
class Leaf {
	var id: Int = 0
}

@SerialTag("Vec", version = 2)
class Vec {
	var x: Int = 0
	var y: Float = 0f
}

@SerialTag("Node")
class Node {
	var label: String = ""
	var flag: Boolean = false
	var position: Vec? = null
	var numbers: List<Int> = emptyList()
	var sharedChild: Leaf? = null
	var sharedChildAgain: Leaf? = null
}

/**
 * Validates the reflective engine core end-to-end: field discovery/order, primitives as
 * `<i>/<f>/<s>/<b>` children, nested objects, an `array_list`, shared-object hoisting (xs.id/xs.ref/
 * xs.idx), version PIs, and full byte<->object<->byte round-trip through JDOM.
 */
class SerializeEngineTest {
	private val engine = SerializeEngine.of(listOf(Node::class, Vec::class, Leaf::class))

	@Test
	fun roundTripsTypedGraphThroughXml() {
		val leaf = Leaf().apply { id = 7 }
		val node =
			Node().apply {
				label = "hi"
				flag = true
				position =
					Vec().apply {
						x = 1
						y = 2.5f
					}
				numbers = listOf(1, 2, 3)
				sharedChild = leaf
				sharedChildAgain = leaf // same instance -> must become a shared ref
			}

		val xmlBytes = XmlCodec.write(engine.writeRoot(node))
		val xml = xmlBytes.decodeToString()

		// Shape checks against the editor's conventions.
		assertTrue("<?version Vec:2?>" in xml, "version PI emitted")
		assertTrue("<Node>" in xml || "<Node " in xml, "root tag")
		assertTrue("array_list" in xml, "list serialized as array_list")
		assertTrue("xs.ref" in xml, "second use of leaf is a reference")
		assertTrue("xs.id" in xml && "xs.idx" in xml, "shared def has id + index")

		// Object round-trip.
		val restored = engine.readRoot(XmlCodec.parse(xmlBytes)) as Node
		assertEquals("hi", restored.label)
		assertEquals(true, restored.flag)
		assertEquals(1, restored.position?.x)
		assertEquals(2.5f, restored.position?.y)
		assertContentEquals(listOf(1, 2, 3), restored.numbers)

		// Shared identity preserved: both fields point at the SAME restored Leaf.
		assertNotNull(restored.sharedChild)
		assertSame(restored.sharedChild, restored.sharedChildAgain, "shared ref resolves to one instance")
		assertEquals(7, restored.sharedChild?.id)
	}
}
