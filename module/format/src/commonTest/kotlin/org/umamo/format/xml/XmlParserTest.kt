package org.umamo.format.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Pins the parser's envelope enforcement and its JDOM-SAXBuilder-parity behaviors: attribute
 * order, PI placement, whitespace preservation, entity decoding, and line-end normalization.
 */
class XmlParserTest {
	private fun parse(xml: String): Document = XmlParser.parse(xml.encodeToByteArray())

	@Test
	fun attributesKeepDocumentOrder() {
		val root = parse("""<root zeta="1" alpha="2" mid="3" />""").rootElement
		assertEquals(listOf("zeta", "alpha", "mid"), root.attributes.map { attribute -> attribute.name })
		assertEquals("2", root.getAttributeValue("alpha"))
	}

	@Test
	fun processingInstructionsBeforeRootLandInDocumentContent() {
		val document =
			parse(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
					"<?version CModelSource:13?>\r\n<?import a.b.C?>\r\n<root />\r\n",
			)
		val instructions = document.content.filterIsInstance<ProcessingInstruction>()
		assertEquals(listOf("version", "import"), instructions.map { pi -> pi.target })
		assertEquals("CModelSource:13", instructions[0].data)
		assertEquals("a.b.C", instructions[1].data)
		assertEquals(2, document.indexOf(document.rootElement))
	}

	@Test
	fun whitespaceOnlyTextInsideElementsIsPreserved() {
		val root = parse("<root>\r\n<child />\r\n</root>").rootElement
		// The whitespace between the tags stays as Text nodes (SAXBuilder keeps it; the TRIM
		// emitter is what drops it), normalized to LF by input line-end normalization.
		assertEquals(3, root.content.size)
		assertEquals("\n", (root.content[0] as Text).text)
		assertEquals("child", (root.content[1] as Element).name)
		assertEquals("\n", (root.content[2] as Text).text)
	}

	@Test
	fun textCoalescesEntitiesAndCdataIntoOneNode() {
		val root = parse("<root>a&amp;b<![CDATA[<raw>]]>c&#x40;d&#64;e</root>").rootElement
		assertEquals(1, root.content.size)
		assertEquals("a&b<raw>c@d@e", root.text)
	}

	@Test
	fun predefinedEntitiesDecodeInAttributes() {
		val root = parse("""<root value="a&amp;b&lt;c&gt;d&quot;e&apos;f&#x9;g" />""").rootElement
		assertEquals("a&b<c>d\"e'f\tg", root.getAttributeValue("value"))
	}

	@Test
	fun literalLineEndsNormalizeButReferencesSurvive() {
		val root = parse("<root>a\r\nb\rc&#xD;d&#13;e</root>").rootElement
		// Literal CRLF and CR become LF (XML 1.0 §2.11); the character references stay CR.
		assertEquals("a\nb\nc\rd\re", root.text)
	}

	@Test
	fun commentsArePreservedVerbatim() {
		val root = parse("<root><!--  keep   spacing\tand <chars> &amp; raw  --></root>").rootElement
		val comment = root.content.filterIsInstance<Comment>().single()
		// The body is verbatim: no entity expansion, no whitespace handling inside comments.
		assertEquals("  keep   spacing\tand <chars> &amp; raw  ", comment.text)
	}

	@Test
	fun emptyElementAndExplicitPairParseTheSame() {
		val collapsed = parse("<root><a /><b></b></root>").rootElement
		assertEquals(listOf("a", "b"), collapsed.children.map { child -> child.name })
		assertEquals(0, collapsed.getChild("a")!!.content.size)
		assertEquals(0, collapsed.getChild("b")!!.content.size)
	}

	@Test
	fun bomIsTolerated() {
		val document = XmlParser.parse("<root />".encodeToByteArray())
		assertEquals("root", document.rootElement.name)
	}

	@Test
	fun doctypeIsRejected() {
		val failure =
			assertFails {
				parse("<!DOCTYPE root><root />")
			}
		assertTrue(failure.message!!.contains("DOCTYPE"), "unexpected message: ${failure.message}")
	}

	@Test
	fun namespaceDeclarationIsRejected() {
		val failure =
			assertFails {
				parse("""<root xmlns="http://example.org/ns" />""")
			}
		assertTrue(failure.message!!.contains("envelope"), "unexpected message: ${failure.message}")
	}

	@Test
	fun prefixedElementIsRejected() {
		val failure =
			assertFails {
				parse("""<ns:root xmlns:ns="http://example.org/ns" />""")
			}
		assertTrue(failure.message!!.contains("envelope"), "unexpected message: ${failure.message}")
	}

	@Test
	fun undeclaredEntityIsRejected() {
		assertFails {
			parse("<root>&undeclared;</root>")
		}
	}

	@Test
	fun characterDataOutsideRootIsRejected() {
		assertFails {
			parse("<root />stray")
		}
	}
}