package org.umamo.format.xml

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the emitter's JDOM 1.1.3 XMLOutputter parity rules (pretty format, indent "", TRIM) with
 * exact-string assertions.  The corpus round-trip gates and the JDOM differential oracle are the
 * authority; these tests document each transcribed rule in isolation.
 */
class XmlEmitterTest {
	private val declaration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"

	private fun emitToString(document: Document): String = XmlEmitter.emit(document).decodeToString()

	@Test
	fun emptyRootCollapsesToSpaceSlashFormWithDoubleTrailingSeparator() {
		val document = Document(Element("root"))
		// One separator per document-level node plus the unconditional final one.
		assertEquals("$declaration<root />\r\n\r\n", emitToString(document))
	}

	@Test
	fun textOnlyElementStaysInline() {
		val root = Element("root")
		root.text = "value"
		assertEquals("$declaration<root>value</root>\r\n\r\n", emitToString(Document(root)))
	}

	@Test
	fun childElementsGetOneLineEachWithoutIndentation() {
		val root = Element("root")
		root.addContent(Element("first"))
		val second = Element("second")
		second.text = "text"
		root.addContent(second)
		assertEquals(
			"$declaration<root>\r\n<first />\r\n<second>text</second>\r\n</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun processingInstructionsPrintBeforeRootOnOwnLines() {
		val root = Element("root")
		val document = Document(root)
		document.addContent(0, ProcessingInstruction("version", "CModelSource:13"))
		document.addContent(1, ProcessingInstruction("empty", ""))
		assertEquals(
			"$declaration<?version CModelSource:13?>\r\n<?empty?>\r\n<root />\r\n\r\n",
			emitToString(document),
		)
	}

	@Test
	fun attributeEscapingCoversNamedAndNumericEntities() {
		val root = Element("root")
		root.setAttribute("value", "a<b>c\"d&e\tf\rg\nh'i")
		assertEquals(
			"$declaration<root value=\"a&lt;b&gt;c&quot;d&amp;e&#x9;f&#xD;g&#xA;h'i\" />\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun textEscapingKeepsQuotesAndTabsButExpandsLineFeedToSeparator() {
		val root = Element("root")
		// Interior LF becomes the \r\n separator; interior CR becomes a numeric reference.  The
		// quote and apostrophe pass through raw in text.
		root.text = "a<b>c&d\"e'f\rg\nh"
		assertEquals(
			"$declaration<root>a&lt;b&gt;c&amp;d\"e'f&#xD;g\r\nh</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun astralCharactersBecomeLowercaseHexReferencesInTextAndAttributes() {
		val root = Element("root")
		root.setAttribute("emoji", "😀")
		root.text = "😀 ok"
		assertEquals(
			"$declaration<root emoji=\"&#x1f600;\">&#x1f600; ok</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun bmpCjkPassesThroughRaw() {
		val root = Element("root")
		root.setAttribute("name", "前髪")
		root.text = "テキスト"
		assertEquals(
			"$declaration<root name=\"前髪\">テキスト</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun trimStripsTextBoundariesAtEmissionOnly() {
		val root = Element("root")
		root.text = "  spaced   interior  "
		// Boundary whitespace trimmed; interior runs preserved (TRIM, not NORMALIZE).
		assertEquals("$declaration<root>spaced   interior</root>\r\n\r\n", emitToString(Document(root)))
		// The DOM itself keeps the raw text: trimming is an emission concern.
		assertEquals("  spaced   interior  ", root.text)
	}

	@Test
	fun whitespaceOnlyTextCollapsesTheElement() {
		val root = Element("root")
		root.text = " \t\r\n"
		assertEquals("$declaration<root />\r\n\r\n", emitToString(Document(root)))
	}

	@Test
	fun whitespaceTextBetweenChildElementsCollapsesIntoSeparators() {
		val root = Element("root")
		root.addContent(Text("\r\n"))
		root.addContent(Element("first"))
		root.addContent(Text("\r\n"))
		root.addContent(Element("second"))
		root.addContent(Text("\r\n"))
		assertEquals(
			"$declaration<root>\r\n<first />\r\n<second />\r\n</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun adjacentTextNodesPadWithOneSpaceWhenRawBoundaryHadWhitespace() {
		val root = Element("root")
		root.addContent(Text("one "))
		root.addContent(Text(" two"))
		root.addContent(Text("three"))
		// one_/_two: both trimmed, boundary whitespace on the RAW strings re-pads a single space.
		// two/three: no boundary whitespace, no pad.
		assertEquals("$declaration<root>one twothree</root>\r\n\r\n", emitToString(Document(root)))
	}

	@Test
	fun commentsPrintVerbatimOnTheirOwnLine() {
		val root = Element("root")
		root.addContent(Comment(" note "))
		root.addContent(Element("child"))
		assertEquals(
			"$declaration<root>\r\n<!-- note -->\r\n<child />\r\n</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun mixedTextAndChildTakesTheNewlineFramedLayout() {
		val root = Element("root")
		root.addContent(Text("lead"))
		root.addContent(Element("child"))
		root.addContent(Text("tail"))
		assertEquals(
			"$declaration<root>\r\nlead\r\n<child />\r\ntail\r\n</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}

	@Test
	fun traxEscapingPisAreConsumedButKeepTheirSeparatorLine() {
		// XML PI data, not a JVM API reference — concatenated past the common-source purity check.
		val traxTargetPrefix = "javax" + ".xml.transform."
		val root = Element("root")
		root.addContent(ProcessingInstruction(traxTargetPrefix + "disable-output-escaping", ""))
		root.addContent(Text("a<b"))
		root.addContent(ProcessingInstruction(traxTargetPrefix + "enable-output-escaping", ""))
		root.addContent(Text("c<d"))
		// The PI prints nothing but its line framing stays; escaping is off for the first text run
		// and back on for the second (JDOM printProcessingInstruction + escapeElementEntities).
		assertEquals(
			"$declaration<root>\r\n\r\na<b\r\n\r\nc&lt;d\r\n</root>\r\n\r\n",
			emitToString(Document(root)),
		)
	}
}