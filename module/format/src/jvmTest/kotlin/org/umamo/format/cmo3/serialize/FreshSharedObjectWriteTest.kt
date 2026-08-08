package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins reconcile-mode writing of FRESH shared objects (objects attached after read and referenced
 * more than once): the def must hoist into the shared pool after the preserved entries, its id must
 * not collide with any preserved id, and a re-read must resolve every reference to one instance.
 * Also pins that an unedited graph still re-emits byte-identically (the hoist is a no-op for it).
 */
class FreshSharedObjectWriteTest {
	private val engine = SerializeEngine.of(listOf(Node::class, Vec::class, Leaf::class))

	private fun sourceXml(): ByteArray {
		val sharedLeaf = Leaf().apply { id = 7 }
		val node =
			Node().apply {
				label = "src"
				sharedChild = sharedLeaf
				sharedChildAgain = sharedLeaf // second use -> hoisted into <shared> with xs.id "#0"
			}
		return XmlCodec.write(engine.writeRoot(node))
	}

	@Test
	fun uneditedGraphStaysByteIdentical() {
		val source = sourceXml()
		val reemitted = XmlCodec.write(engine.writeModel(engine.readModel(XmlCodec.parse(source))))
		assertContentEquals(source, reemitted, "unedited graph re-emits byte-identically")
	}

	@Test
	fun freshSharedObjectHoistsWithoutCollidingWithPreservedIds() {
		val graph = engine.readModel(XmlCodec.parse(sourceXml()))
		val root = graph.root as Node

		// A fresh object attached after read, referenced twice.
		val freshLeaf = Leaf().apply { id = 42 }
		root.sharedChild = freshLeaf
		root.sharedChildAgain = freshLeaf

		val output = XmlCodec.write(engine.writeModel(graph))
		val document = XmlCodec.parse(output)

		val defs = document.rootElement.getChild("shared")!!.children
		assertEquals(2, defs.size, "preserved def + hoisted fresh def")
		val preservedDef = defs[0]
		val freshDef = defs[1]
		assertEquals("#0", preservedDef.getAttributeValue("xs.id"), "preserved def keeps its id")
		assertEquals("#1", freshDef.getAttributeValue("xs.id"), "fresh id continues past the preserved maximum")
		assertNotEquals(
			preservedDef.getAttributeValue("xs.id"),
			freshDef.getAttributeValue("xs.id"),
			"fresh id never reuses a preserved id",
		)
		val preservedIndex = preservedDef.getAttributeValue("xs.idx")!!.toInt()
		assertEquals(preservedIndex + 1, freshDef.getAttributeValue("xs.idx")!!.toInt(), "xs.idx continues")

		// Both field uses in <main> are references; the inline def was replaced by the hoist.
		val mainNode = document.rootElement.getChild("main")!!.children.first()
		val nodeChildren = mainNode.children
		val childRef = nodeChildren.first { it.getAttributeValue("xs.n") == "sharedChild" }
		val childAgainRef = nodeChildren.first { it.getAttributeValue("xs.n") == "sharedChildAgain" }
		assertEquals("#1", childRef.getAttributeValue("xs.ref"), "first use replaced by a reference")
		assertEquals("#1", childAgainRef.getAttributeValue("xs.ref"), "second use is a reference")
		assertTrue(childRef.children.isEmpty(), "no inline def remains in <main>")

		// Re-read resolves both references to the one fresh instance.
		val restored = engine.readRoot(XmlCodec.parse(output)) as Node
		assertNotNull(restored.sharedChild)
		assertSame(restored.sharedChild, restored.sharedChildAgain, "one instance behind both refs")
		assertEquals(42, restored.sharedChild?.id)
	}
}