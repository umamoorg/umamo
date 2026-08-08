package org.umamo.format.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Parses UTF-8 XML bytes into a [Document], enforcing the CMO3 XML envelope.
 *
 * The tokenizer is xmlutil's GENERIC reader — the same platform-independent implementation on
 * every target, chosen over platform readers so desktop, Android, and iOS parse identically.  No
 * xmlutil type escapes this file: the parser stays swappable behind this seam.
 *
 * The envelope is machine-written XML (the Cubism editor's serializer output): no DTD, no
 * namespaces, no entities beyond the five predefined and numeric character references.  Documents
 * outside it fail with an error naming the violated rule rather than parsing differently than the
 * editor would.  Comments are tolerated and preserved verbatim; CDATA sections are folded into
 * plain text (the corpus contains none, and their content is identical after JDOM's getText
 * aggregation).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public object XmlParser {
	/**
	 * Parses [bytes] into a document.
	 *
	 * @param ByteArray bytes The UTF-8 XML bytes (a leading BOM is tolerated).
	 * @return Document The parsed document.
	 */
	public fun parse(bytes: ByteArray): Document {
		val decoded = bytes.decodeToString().removePrefix("\uFEFF")
		// XML 1.0 §2.11 line-end normalization, applied to the RAW input before parsing.  At this
		// point character references are still unexpanded text, so "&#xD;" survives as an escaped
		// carriage return while literal CRLF/CR become LF — exactly the spec split, independent of
		// whatever the tokenizer would or would not normalize itself.
		val normalized = decoded.replace("\r\n", "\n").replace('\r', '\n')
		val reader = xmlStreaming.newGenericReader(normalized, expandEntities = true)
		try {
			return buildDocument(reader)
		} finally {
			reader.close()
		}
	}

	/**
	 * Drains the event stream into a [Document].
	 *
	 * @param XmlReader reader The tokenizer positioned before the first event.
	 * @return Document The built document.
	 */
	private fun buildDocument(reader: XmlReader): Document {
		val elementStack = ArrayDeque<Element>()
		val prologue = ArrayList<Content>()
		val trailing = ArrayList<Content>()
		var rootElement: Element? = null
		val textRun = StringBuilder()
		var textRunActive = false

		/**
		 * Closes the pending text run into a Text node on the open element, or validates that
		 * document-level text is insignificant whitespace.
		 */
		fun flushTextRun() {
			if (!textRunActive) {
				return
			}
			val runText = textRun.toString()
			textRun.setLength(0)
			textRunActive = false
			val openElement = elementStack.lastOrNull()
			if (openElement != null) {
				openElement.addContent(Text(runText))
				return
			}
			if (runText.any { ch -> ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n' }) {
				error("character data outside the root element")
			}
		}

		/** Routes a comment or PI to the open element, the prologue, or the trailing content. */
		fun placeStructuredNode(node: Content) {
			val openElement = elementStack.lastOrNull()
			if (openElement != null) {
				openElement.addContent(node)
			} else if (rootElement == null) {
				prologue.add(node)
			} else {
				trailing.add(node)
			}
		}

		while (reader.hasNext()) {
			when (reader.next()) {
				EventType.START_DOCUMENT -> {}
				EventType.DOCDECL -> error("DOCTYPE is outside the CMO3 XML envelope")
				EventType.START_ELEMENT -> {
					flushTextRun()
					if (reader.prefix.isNotEmpty() || reader.namespaceURI.isNotEmpty()) {
						error("namespaced element <${reader.prefix}:${reader.localName}> is outside the CMO3 XML envelope")
					}
					if (reader.namespaceDecls.isNotEmpty()) {
						error("namespace declaration on <${reader.localName}> is outside the CMO3 XML envelope")
					}
					val element = Element(reader.localName)
					for (attributeIndex in 0 until reader.attributeCount) {
						val attributePrefix = reader.getAttributePrefix(attributeIndex)
						if (attributePrefix.isNotEmpty()) {
							error(
								"namespaced attribute $attributePrefix:${reader.getAttributeLocalName(attributeIndex)}" +
									" is outside the CMO3 XML envelope",
							)
						}
						element.setAttribute(
							reader.getAttributeLocalName(attributeIndex),
							reader.getAttributeValue(attributeIndex),
						)
					}
					val parentElement = elementStack.lastOrNull()
					if (parentElement != null) {
						parentElement.addContent(element)
					} else {
						if (rootElement != null) {
							error("multiple root elements")
						}
						rootElement = element
					}
					elementStack.addLast(element)
				}
				EventType.END_ELEMENT -> {
					flushTextRun()
					elementStack.removeLast()
				}
				EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE -> {
					if (elementStack.isEmpty() && reader.text.all { ch -> ch == ' ' || ch == '\t' || ch == '\n' }) {
						// Whitespace between document-level nodes carries no information.
						continue
					}
					textRun.append(reader.text)
					textRunActive = true
				}
				EventType.ENTITY_REF -> {
					// With expandEntities the predefined entities arrive as TEXT; this branch is
					// the defensive net for tokenizer variants that still report them.
					textRun.append(resolveEntity(reader.localName))
					textRunActive = true
				}
				EventType.COMMENT -> {
					flushTextRun()
					placeStructuredNode(Comment(reader.text))
				}
				EventType.PROCESSING_INSTRUCTION -> {
					flushTextRun()
					placeStructuredNode(ProcessingInstruction(reader.piTarget, reader.piData))
				}
				EventType.END_DOCUMENT -> flushTextRun()
				EventType.ATTRIBUTE -> error("unexpected standalone attribute event")
			}
		}
		flushTextRun()

		val root = rootElement ?: error("no root element")
		val document = Document(root)
		for ((prologueIndex, node) in prologue.withIndex()) {
			document.addContent(prologueIndex, node)
		}
		for (node in trailing) {
			document.addContent(document.content.size, node)
		}
		return document
	}

	/**
	 * Resolves an entity reference by name: the five predefined XML entities and numeric
	 * character references.  Anything else needs a DTD, which the envelope forbids.
	 *
	 * @param String name The entity name between '&' and ';'.
	 * @return String The replacement text.
	 */
	private fun resolveEntity(name: String): String =
		when (name) {
			"amp" -> "&"
			"lt" -> "<"
			"gt" -> ">"
			"quot" -> "\""
			"apos" -> "'"
			else ->
				if (name.startsWith("#x") || name.startsWith("#X")) {
					name.substring(2).toInt(16).toChar().toString()
				} else if (name.startsWith("#")) {
					name.substring(1).toInt(10).toChar().toString()
				} else {
					error("entity &$name; needs a DTD, which is outside the CMO3 XML envelope")
				}
		}
}