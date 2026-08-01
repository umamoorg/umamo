package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault
import org.umamo.format.cmo3.serialize.annotations.SerialAttribute
import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SerialTag("Badge")
@DontSerializeIfDefault
class Badge {
	@SerialAttribute
	var kind: String = ""

	@SerialAttribute
	var level: Int = 0
}

/**
 * Pins the Cmo3GraphEditor surface: the attribute-presence trap and its ensurePresentAttr escape
 * hatch, hasRecordedOrder distinguishing read objects from fresh ones, and pruneUnreachableShared
 * dropping only pool entries nothing references anymore - including keeping entries referenced
 * solely from verbatim (untyped) subtrees.
 */
class GraphEditorTest {
	private val nodeEngine = SerializeEngine.of(listOf(Node::class, Vec::class, Leaf::class))
	private val badgeEngine = SerializeEngine.of(listOf(Badge::class))

	private fun sharedLeafSourceXml(): ByteArray {
		val sharedLeaf = Leaf().apply { id = 7 }
		val node =
			Node().apply {
				label = "src"
				sharedChild = sharedLeaf
				sharedChildAgain = sharedLeaf
			}
		return XmlCodec.write(nodeEngine.writeRoot(node))
	}

	@Test
	fun attributeAssignedAfterReadIsDroppedWithoutEnsurePresentAttr() {
		val source = XmlCodec.write(badgeEngine.writeRoot(Badge().apply { kind = "gold" }))
		val graph = badgeEngine.readModel(XmlCodec.parse(source))
		val badge = graph.root as Badge
		badge.level = 3

		// The trap: the writer emits exactly the attributes recorded present at read, so the bare
		// assignment does not reach the document.
		val dropped = XmlCodec.write(badgeEngine.writeModel(graph)).decodeToString()
		assertTrue("kind=\"gold\"" in dropped, "recorded attribute survives")
		assertFalse("level" in dropped, "unrecorded attribute is dropped by the replay")

		Cmo3GraphEditor(graph).ensurePresentAttr(badge, "Badge", "level")
		val kept = XmlCodec.write(badgeEngine.writeModel(graph)).decodeToString()
		assertTrue("level=\"3\"" in kept, "ensurePresentAttr makes the assignment reach the document")
	}

	@Test
	fun hasRecordedOrderDistinguishesReadObjectsFromFreshOnes() {
		val graph = nodeEngine.readModel(XmlCodec.parse(sharedLeafSourceXml()))
		val editor = Cmo3GraphEditor(graph)
		assertTrue(editor.hasRecordedOrder(graph.root as Node, "Node"), "read object has a recorded order")
		assertFalse(editor.hasRecordedOrder(Leaf(), "Leaf"), "fresh object has none")
	}

	@Test
	fun pruneDropsOrphanedPoolEntriesAndKeepsReferencedOnes() {
		val graph = nodeEngine.readModel(XmlCodec.parse(sharedLeafSourceXml()))
		val editor = Cmo3GraphEditor(graph)

		// Still referenced: the pool entry stays.
		editor.pruneUnreachableShared()
		assertEquals(1, graph.sharedOrder.size, "referenced pool entry survives a prune")

		// Orphaned by a structural delete: the pool entry goes, and the document re-reads cleanly.
		val root = graph.root as Node
		root.sharedChild = null
		root.sharedChildAgain = null
		editor.pruneUnreachableShared()
		assertEquals(0, graph.sharedOrder.size, "orphaned pool entry is dropped")
		val output = XmlCodec.write(nodeEngine.writeModel(graph))
		val restored = nodeEngine.readRoot(XmlCodec.parse(output)) as Node
		assertNull(restored.sharedChild, "re-read reflects the delete")
	}

	@Test
	fun pruneKeepsEntriesReferencedOnlyFromVerbatimSubtrees() {
		// An empty registry reads everything verbatim: the root becomes a VerbatimNode whose xs.ref
		// attributes are the only remaining links to the pool.
		val verbatimEngine = SerializeEngine.of(emptyList())
		val graph = verbatimEngine.readModel(XmlCodec.parse(sharedLeafSourceXml()))
		Cmo3GraphEditor(graph).pruneUnreachableShared()
		assertEquals(1, graph.sharedOrder.size, "verbatim xs.ref keeps its pool entry alive")
	}
}
