package org.umamo.format.xml

import org.jdom.input.SAXBuilder
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differential oracle for the common XML layer: JDOM 1.1.3 — the exact library whose output the
 * fidelity contract is written against — parses and emits the same inputs, and every result must
 * match ours byte for byte.
 *
 * Three angles: the corpus main.xml files (both stacks must reproduce the original bytes), the
 * synthetic documents (both stacks must agree on escaping/whitespace/normalization paths the
 * unedited corpus never exercises), and programmatically built trees (writer-only escaping paths
 * that no parse can reach, e.g. a raw CR set through the API).
 */
class XmlDifferentialOracleTest {
	// --- The JDOM side: the old XmlCodec configuration, byte for byte (XMLOutputter with
	// --- Format.getPrettyFormat, indent "", TextMode.TRIM), kept here as the oracle.

	private fun jdomParse(bytes: ByteArray): org.jdom.Document = SAXBuilder().build(ByteArrayInputStream(bytes))

	private fun jdomEmit(document: org.jdom.Document): ByteArray {
		val format =
			Format.getPrettyFormat().apply {
				setIndent("")
				textMode = Format.TextMode.TRIM
			}
		val sink = ByteArrayOutputStream()
		XMLOutputter(format).output(document, sink)
		return sink.toByteArray()
	}

	private fun assertBytesEqual(expected: ByteArray, actual: ByteArray, label: String) {
		if (expected.contentEquals(actual)) {
			return
		}
		val limit = minOf(expected.size, actual.size)
		val firstDiff = (0 until limit).firstOrNull { index -> expected[index] != actual[index] } ?: limit
		val windowStart = maxOf(0, firstDiff - 60)

		fun ByteArray.window() =
			decodeToString(windowStart, minOf(size, firstDiff + 60)).replace("\r", "\\r").replace("\n", "\\n")
		throw AssertionError(
			"$label: first diff @ $firstDiff (sizes ${expected.size}/${actual.size})\n" +
				"  jdom: …${expected.window()}…\n" +
				"  ours: …${actual.window()}…",
		)
	}

	// --- Corpus: every local sample's main.xml must survive parse+emit byte-identically
	// --- through BOTH stacks.

	@Test
	fun corpusMainXmlRoundTripsByteIdenticallyThroughBothStacks() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping corpus oracle")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		if (files.isEmpty()) {
			println("cmo3.probe lists no readable samples; skipping")
			return
		}
		for (file in files) {
			val mainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			assertBytesEqual(mainXml, jdomEmit(jdomParse(mainXml)), "${file.name} (jdom oracle sanity)")
			assertBytesEqual(mainXml, XmlEmitter.emit(XmlParser.parse(mainXml)), "${file.name} (common stack)")
		}
	}

	// --- Synthetic documents: parsed and emitted by both stacks, outputs compared to each other
	// --- (the inputs are not canonical, so the input bytes are not the reference).

	private val syntheticDocuments =
		listOf(
			"escaped attributes" to """<r a="a&amp;b&lt;c&gt;d&quot;e'f" />""",
			"escaped text" to """<r>a&amp;b&lt;c&gt;d"e'f</r>""",
			"numeric references" to "<r t=\"&#x9;tab&#xA;lf&#xD;cr\">&#xD;&#x40;&#64;</r>",
			"cjk" to "<r name=\"前髪\">テキスト、日本語。</r>",
			"emoji astral plane" to "<r e=\"😀\">before 😀 after</r>",
			"boundary whitespace text" to "<r>  padded interior   runs  </r>",
			"bracket bracket gt" to "<r>a]]&gt;b</r>",
			"empty and explicit pairs" to """<r><a /><b></b><c attr="1" /></r>""",
			"mixed content" to "<r>lead<child />tail</r>",
			"elements between text" to "<r><a />between<b /></r>",
			"crlf pretty framing" to "<r>\r\n  <a />\r\n  <b />\r\n</r>",
			"lf inside text" to "<r>line1\nline2</r>",
			"crlf inside text" to "<r>line1\r\nline2</r>",
			"pi with and without data" to "<?first with data?><?second?><r />",
			"comment at doc level and inside" to "<!-- doc --><r><!-- in --><a /></r>",
			"deep nesting" to
				buildString {
					append("<r>")
					repeat(40) { depth -> append("<n$depth>") }
					append("core")
					(39 downTo 0).forEach { depth -> append("</n$depth>") }
					append("</r>")
				},
			"corpus-like shape" to
				"""<?version CModelSource:13?><root fileFormatVersion="504000000"><shared /><main>""" +
				"""<CModelSource xs.n="root"><s xs.n="name">パーツ01</s><f xs.n="scale">1.0</f>""" +
				"""<array_list count="2" xs.n="items"><i>1</i><i>2</i></array_list></CModelSource></main></root>""",
			"whitespace only element" to "<r> \t\n</r>",
			"adjacent entity text" to "<r>&amp;&amp;&lt;&gt;</r>",
		)

	@Test
	fun syntheticDocumentsEmitIdenticallyThroughBothStacks() {
		for ((label, xml) in syntheticDocuments) {
			val bytes = xml.encodeToByteArray()
			val jdomResult = jdomEmit(jdomParse(bytes))
			val oursResult = XmlEmitter.emit(XmlParser.parse(bytes))
			assertBytesEqual(jdomResult, oursResult, label)
		}
	}

	@Test
	fun literalWhitespaceInAttributeValuesNormalizesIdentically() {
		// XML 1.0 §3.3.3 attribute-value normalization: literal tab/LF become spaces, while the
		// numeric references stay tab/LF.  Exercised alone so a tokenizer divergence names itself.
		val bytes = "<r a=\"one\ttwo\nthree\" b=\"keep&#x9;tab\" />".encodeToByteArray()
		assertBytesEqual(
			jdomEmit(jdomParse(bytes)),
			XmlEmitter.emit(XmlParser.parse(bytes)),
			"attribute-value normalization",
		)
	}

	// --- Writer-only: the same tree built through both APIs, with strings a parse can never
	// --- produce (raw CR, raw tab in attributes, astral chars set programmatically).

	private sealed interface NodeSpec

	private class ElementSpec(
		val name: String,
		val attributes: List<Pair<String, String>> = emptyList(),
		val children: List<NodeSpec> = emptyList(),
	) : NodeSpec

	private class TextSpec(val text: String) : NodeSpec

	private class CommentSpec(val text: String) : NodeSpec

	private class PiSpec(val target: String, val data: String) : NodeSpec

	private fun NodeSpec.toOurs(): Content =
		when (this) {
			is ElementSpec -> {
				val element = Element(name)
				for ((attributeName, value) in attributes) {
					element.setAttribute(attributeName, value)
				}
				for (child in children) {
					element.addContent(child.toOurs())
				}
				element
			}
			is TextSpec -> Text(text)
			is CommentSpec -> Comment(text)
			is PiSpec -> ProcessingInstruction(target, data)
		}

	private fun NodeSpec.toJdom(): org.jdom.Content =
		when (this) {
			is ElementSpec -> {
				val element = org.jdom.Element(name)
				for ((attributeName, value) in attributes) {
					element.setAttribute(attributeName, value)
				}
				for (child in children) {
					element.addContent(child.toJdom())
				}
				element
			}
			is TextSpec -> org.jdom.Text(text)
			is CommentSpec -> org.jdom.Comment(text)
			is PiSpec -> org.jdom.ProcessingInstruction(target, data)
		}

	private val builtTrees =
		listOf(
			"hostile attribute values" to
				ElementSpec(
					"r",
					attributes =
						listOf(
							"controls" to "tab\there\nlf\rcr",
							"quotes" to "a\"b'c",
							"markup" to "<&>",
							"astral" to "pair😀end",
						),
				),
			"hostile text" to
				ElementSpec(
					"r",
					children = listOf(TextSpec("tab\there\nlf\rcr \"quote' <&> 😀")),
				),
			"boundary whitespace text nodes" to
				ElementSpec(
					"r",
					children = listOf(TextSpec("one "), TextSpec(" two"), TextSpec("three")),
				),
			"whitespace only and empty text" to
				ElementSpec(
					"r",
					children = listOf(TextSpec(""), TextSpec(" \t\n"), TextSpec("visible")),
				),
			"mixed with comment and pi" to
				ElementSpec(
					"r",
					children =
						listOf(
							TextSpec("lead"),
							CommentSpec(" c "),
							ElementSpec("child", attributes = listOf("k" to "v")),
							PiSpec("target", "data"),
							PiSpec("bare", ""),
							TextSpec(" tail "),
						),
				),
			"nested empties" to
				ElementSpec(
					"r",
					children =
						listOf(
							ElementSpec("a"),
							ElementSpec("b", children = listOf(ElementSpec("inner"))),
						),
				),
		)

	@Test
	fun programmaticallyBuiltTreesEmitIdenticallyThroughBothStacks() {
		for ((label, rootSpec) in builtTrees) {
			val oursBytes = XmlEmitter.emit(Document(rootSpec.toOurs() as Element))
			val jdomBytes = jdomEmit(org.jdom.Document(rootSpec.toJdom() as org.jdom.Element))
			assertBytesEqual(jdomBytes, oursBytes, label)
		}
	}

	@Test
	fun documentLevelPisEmitIdenticallyThroughBothStacks() {
		val ourDocument = Document(Element("root"))
		ourDocument.addContent(0, ProcessingInstruction("version", "CModelSource:13"))
		ourDocument.addContent(1, ProcessingInstruction("import", "a.b.C"))
		val jdomDocument = org.jdom.Document(org.jdom.Element("root"))
		jdomDocument.addContent(0, org.jdom.ProcessingInstruction("version", "CModelSource:13"))
		jdomDocument.addContent(1, org.jdom.ProcessingInstruction("import", "a.b.C"))
		assertEquals(
			jdomEmit(jdomDocument).decodeToString(),
			XmlEmitter.emit(ourDocument).decodeToString(),
			"document-level PIs",
		)
	}
}