package org.umamo.format.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks the DOM semantics the CMO3 serializer depends on: identity-based content lookup, deep
 * detached clones, JDOM-style text aggregation, and in-place attribute replacement.
 */
class XmlDomTest {
	@Test
	fun indexOfUsesIdentityNotEquality() {
		val parent = Element("parent")
		val firstText = Text("same")
		val secondText = Text("same")
		parent.addContent(firstText)
		parent.addContent(secondText)
		assertEquals(0, parent.indexOf(firstText))
		assertEquals(1, parent.indexOf(secondText))
		assertEquals(-1, parent.indexOf(Text("same")))
	}

	@Test
	fun removeContentUsesIdentityAndDetaches() {
		val parent = Element("parent")
		val child = Element("child")
		parent.addContent(child)
		assertFalse(parent.removeContent(Element("child")))
		assertTrue(parent.removeContent(child))
		assertNull(child.parent)
		assertEquals(-1, parent.indexOf(child))
	}

	@Test
	fun cloneIsDeepAndDetached() {
		val original = Element("original")
		original.setAttribute("key", "value")
		val child = Element("child")
		child.addContent(Text("payload"))
		original.addContent(child)
		original.parentedUnder(Element("holder"))

		val copy = original.clone()
		assertNull(copy.parent)
		assertEquals("value", copy.getAttributeValue("key"))
		val copiedChild = copy.getChild("child")!!
		assertEquals("payload", copiedChild.text)
		// Mutating the copy must not reach the original subtree.
		copiedChild.text = "changed"
		assertEquals("payload", original.getChild("child")!!.text)
	}

	@Test
	fun textAggregatesAllTextChildren() {
		val element = Element("mixed")
		element.addContent(Text("one "))
		element.addContent(Element("gap"))
		element.addContent(Text("two"))
		assertEquals("one two", element.text)
		assertEquals("", Element("empty").text)
	}

	@Test
	fun setTextReplacesAllContentEvenWithEmptyString() {
		val element = Element("holder")
		val displaced = Element("displaced")
		element.addContent(displaced)
		element.text = ""
		assertNull(displaced.parent)
		assertEquals(0, element.children.size)
		assertEquals(1, element.content.size)
		assertEquals("", element.text)
	}

	@Test
	fun setAttributeReplacesInPlaceKeepingPosition() {
		val element = Element("attrs")
		element.setAttribute("first", "1")
		element.setAttribute("second", "2")
		element.setAttribute("first", "updated")
		assertEquals(listOf("first", "second"), element.attributes.map { attribute -> attribute.name })
		assertEquals("updated", element.getAttributeValue("first"))
		assertTrue(element.removeAttribute("first"))
		assertFalse(element.removeAttribute("first"))
		assertNull(element.getAttributeValue("first"))
	}

	@Test
	fun getChildReturnsFirstMatchOnly() {
		val parent = Element("parent")
		val firstMatch = Element("target")
		parent.addContent(Text("noise"))
		parent.addContent(firstMatch)
		parent.addContent(Element("target"))
		assertSame(firstMatch, parent.getChild("target"))
		assertNull(parent.getChild("absent"))
	}

	@Test
	fun addingAttachedContentThrows() {
		val owner = Element("owner")
		val child = Element("child")
		owner.addContent(child)
		assertFailsWith<IllegalArgumentException> { Element("thief").addContent(child) }
	}

	@Test
	fun documentHoldsPisBeforeRootAndSecondRootIsRejected() {
		val root = Element("root")
		val document = Document(root)
		assertSame(root, document.rootElement)
		val versionPi = ProcessingInstruction("version", "CModelSource:13")
		document.addContent(0, versionPi)
		assertEquals(listOf<Content>(versionPi, root), document.content)
		assertEquals(0, document.indexOf(versionPi))
		assertFailsWith<IllegalArgumentException> { document.addContent(0, Element("second")) }
		assertTrue(document.removeContent(versionPi))
		assertNull(versionPi.parent)
	}

	@Test
	fun indexedInsertPlacesContentExactly() {
		val parent = Element("parent")
		val first = Element("first")
		val third = Element("third")
		parent.addContent(first)
		parent.addContent(third)
		val second = Element("second")
		parent.addContent(1, second)
		assertEquals(listOf("first", "second", "third"), parent.children.map { child -> child.name })
	}
}

/**
 * Attaches this element under [holder], for tests that need a parented subtree.
 *
 * @param Element holder The parent to attach under.
 */
private fun Element.parentedUnder(holder: Element) {
	holder.addContent(this)
}