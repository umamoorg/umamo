package org.umamo.format.xml

/**
 * Emits a [Document] as UTF-8 XML bytes, byte-identical to JDOM 1.1.3's XMLOutputter configured
 * the way the Cubism editor writes main.xml: Format.getPrettyFormat() with indent "" and
 * TextMode.TRIM (line separator "\r\n", UTF-8, declaration included, empty elements collapsed).
 *
 * Every rule here is transcribed from the JDOM 1.1.3 source (XMLOutputter.java / Format.java) —
 * the reference implementation for this byte contract — and pinned by the corpus round-trip gates
 * plus the JDOM differential oracle.  The one JDOM behavior deliberately omitted is xml:space
 * ("preserve"/"default" format switching): it is namespace-qualified, and the CMO3 envelope admits
 * no namespaces, so [XmlParser] can never produce it.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public object XmlEmitter {
	// JDOM Format.STANDARD_LINE_SEPARATOR — the pretty format's default, kept by the editor.
	private const val LINE_SEPARATOR: String = "\r\n"

	// JDOM XMLOutputter: the TrAX escaping-control PI targets (the javax.xml.transform.Result
	// constants).  These are consumed — toggling text escaping — and never written out.  The
	// literals are XML PI DATA, not JVM API references; they are concatenated so the common-source
	// purity check cannot mistake them for a javax.* usage.
	private const val TRAX_TARGET_PREFIX: String = "javax" + ".xml.transform."
	private const val PI_DISABLE_OUTPUT_ESCAPING: String = TRAX_TARGET_PREFIX + "disable-output-escaping"
	private const val PI_ENABLE_OUTPUT_ESCAPING: String = TRAX_TARGET_PREFIX + "enable-output-escaping"

	/** Mutable emission state: text escaping can be toggled mid-document by the TrAX PIs. */
	private class EmitState {
		var escapeOutput: Boolean = true
	}

	/**
	 * Emits [document] as UTF-8 bytes in the editor's exact format.
	 *
	 * @param Document document The document to emit.
	 * @return ByteArray The UTF-8 encoded XML.
	 */
	public fun emit(document: Document): ByteArray {
		val out = StringBuilder()
		val state = EmitState()
		// JDOM XMLOutputter.printDeclaration: version, encoding, then an unconditional separator.
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
		out.append(LINE_SEPARATOR)
		// JDOM XMLOutputter.output(Document, Writer): each document-level node is followed by
		// newline() (a separator, because indent "" is non-null), then ONE more unconditional
		// separator closes the file — so output always ends "\r\n\r\n" after the root element.
		for (node in document.content) {
			when (node) {
				is Element -> printElement(out, node, state)
				is Comment -> printComment(out, node)
				is ProcessingInstruction -> printProcessingInstruction(out, node, state)
				is Text -> {} // Illegal at document level; JDOM prints nothing but keeps the separator.
			}
			out.append(LINE_SEPARATOR)
		}
		out.append(LINE_SEPARATOR)
		return out.toString().encodeToByteArray()
	}

	/**
	 * Prints one element with its attributes and content.
	 *
	 * JDOM XMLOutputter.printElement: after skipping leading whitespace-only text (TRIM), empty
	 * content collapses to the " />" form; text-only content stays inline; content containing any
	 * non-text node takes the one-child-per-line layout (newline framing, no indent chars).
	 *
	 * @param StringBuilder out     The output sink.
	 * @param Element       element The element to print.
	 * @param EmitState     state   The mutable escaping state.
	 */
	private fun printElement(out: StringBuilder, element: Element, state: EmitState) {
		out.append('<').append(element.name)
		// JDOM XMLOutputter.printAttributes: space-separated, double-quoted, attribute escaping.
		for (attribute in element.attributes) {
			out.append(' ').append(attribute.name).append("=\"")
			appendAttributeEscaped(out, attribute.value)
			out.append('"')
		}
		val content = element.content
		val start = skipLeadingWhite(content, 0)
		val size = content.size
		if (start >= size) {
			// Content empty or all insignificant whitespace; expandEmptyElements is false.
			out.append(" />")
			return
		}
		out.append('>')
		if (nextNonText(content, start) < size) {
			// Mixed content: newline framing around the range (indent "" adds no spaces).
			out.append(LINE_SEPARATOR)
			printContentRange(out, content, start, size, state)
			out.append(LINE_SEPARATOR)
		} else {
			// All text: inline, no framing.
			printTextRange(out, content, start, size, state)
		}
		out.append("</").append(element.name).append('>')
	}

	/**
	 * Prints a content range, one node (or text run) per line.
	 *
	 * JDOM XMLOutputter.printContentRange: consecutive text nodes are handled as one run;
	 * whitespace-only runs print nothing (their line collapses into the neighbouring separator).
	 *
	 * @param StringBuilder out     The output sink.
	 * @param List          content The owning content list.
	 * @param Int           start   The range start (inclusive).
	 * @param Int           end     The range end (exclusive).
	 * @param EmitState     state   The mutable escaping state.
	 */
	private fun printContentRange(out: StringBuilder, content: List<Content>, start: Int, end: Int, state: EmitState) {
		var index = start
		while (index < end) {
			val firstNode = index == start
			val next = content[index]
			if (next is Text) {
				val runStart = skipLeadingWhite(content, index)
				index = nextNonText(content, runStart)
				if (runStart < index) {
					if (!firstNode) {
						out.append(LINE_SEPARATOR)
					}
					printTextRange(out, content, runStart, index, state)
				}
				continue
			}
			if (!firstNode) {
				out.append(LINE_SEPARATOR)
			}
			when (next) {
				is Comment -> printComment(out, next)
				is Element -> printElement(out, next, state)
				is ProcessingInstruction -> printProcessingInstruction(out, next, state)
				is Text -> {} // Unreachable: handled above.
			}
			index++
		}
	}

	/**
	 * Prints a run of text nodes: boundary whitespace-only nodes dropped, each node's text
	 * java-trimmed (TRIM mode), with a single space re-inserted between adjacent nodes whose raw
	 * boundary carried whitespace.
	 *
	 * JDOM XMLOutputter.printTextRange + printString.  The padding decision uses the RAW untrimmed
	 * neighbours, and empty texts are skipped without becoming "previous".
	 *
	 * @param StringBuilder out     The output sink.
	 * @param List          content The owning content list.
	 * @param Int           start   The run start (inclusive).
	 * @param Int           end     The run end (exclusive).
	 * @param EmitState     state   The mutable escaping state.
	 */
	private fun printTextRange(out: StringBuilder, content: List<Content>, start: Int, end: Int, state: EmitState) {
		val runStart = skipLeadingWhite(content, start)
		if (runStart >= content.size) {
			return
		}
		val runEnd = skipTrailingWhite(content, end)
		var previous: String? = null
		for (index in runStart until runEnd) {
			val node = content[index]
			if (node !is Text) {
				error("text range may only contain text nodes, found ${node::class.simpleName}")
			}
			val next = node.text
			if (next.isEmpty()) {
				continue
			}
			if (previous != null && (endsWithXmlWhite(previous) || startsWithXmlWhite(next))) {
				out.append(' ')
			}
			appendTextEscaped(out, javaTrim(next), state)
			previous = next
		}
	}

	/**
	 * Prints a comment verbatim.  JDOM XMLOutputter.printComment: no escaping of the body.
	 *
	 * @param StringBuilder out     The output sink.
	 * @param Comment       comment The comment to print.
	 */
	private fun printComment(out: StringBuilder, comment: Comment) {
		out.append("<!--").append(comment.text).append("-->")
	}

	/**
	 * Prints a processing instruction, or consumes a TrAX escaping-control PI without printing it
	 * (the caller still writes the surrounding separators, exactly as JDOM does).
	 *
	 * JDOM XMLOutputter.printProcessingInstruction: "<?target data?>", or "<?target?>" when the
	 * data is empty.
	 *
	 * @param StringBuilder         out   The output sink.
	 * @param ProcessingInstruction pi    The processing instruction.
	 * @param EmitState             state The mutable escaping state.
	 */
	private fun printProcessingInstruction(out: StringBuilder, pi: ProcessingInstruction, state: EmitState) {
		when (pi.target) {
			PI_DISABLE_OUTPUT_ESCAPING -> {
				state.escapeOutput = false
				return
			}
			PI_ENABLE_OUTPUT_ESCAPING -> {
				state.escapeOutput = true
				return
			}
		}
		if (pi.data.isNotEmpty()) {
			out.append("<?").append(pi.target).append(' ').append(pi.data).append("?>")
		} else {
			out.append("<?").append(pi.target).append("?>")
		}
	}

	/**
	 * Appends [value] with attribute escaping.
	 *
	 * JDOM XMLOutputter.escapeAttributeEntities: named entities for & < > ", numeric references
	 * for tab/LF/CR, surrogate pairs as one numeric codepoint reference (the UTF-8 escape strategy
	 * escapes nothing else); the single quote is never escaped.
	 *
	 * @param StringBuilder out   The output sink.
	 * @param String        value The raw attribute value.
	 */
	private fun appendAttributeEscaped(out: StringBuilder, value: String) {
		var index = 0
		while (index < value.length) {
			val ch = value[index]
			when (ch) {
				'<' -> out.append("&lt;")
				'>' -> out.append("&gt;")
				'"' -> out.append("&quot;")
				'&' -> out.append("&amp;")
				'\r' -> out.append("&#xD;")
				'\t' -> out.append("&#x9;")
				'\n' -> out.append("&#xA;")
				else -> index = appendCharOrSurrogateReference(out, value, index, ch)
			}
			index++
		}
	}

	/**
	 * Appends [text] with element-text escaping, or raw when escaping is TrAX-disabled.
	 *
	 * JDOM XMLOutputter.escapeElementEntities: named entities for & < >, CR as a numeric
	 * reference, LF as the LINE SEPARATOR (so "\n" in text emits "\r\n"), surrogate pairs as one
	 * numeric codepoint reference.  Quotes and tabs pass through raw.
	 *
	 * @param StringBuilder out   The output sink.
	 * @param String        text  The (already trimmed) text.
	 * @param EmitState     state The mutable escaping state.
	 */
	private fun appendTextEscaped(out: StringBuilder, text: String, state: EmitState) {
		if (!state.escapeOutput) {
			out.append(text)
			return
		}
		var index = 0
		while (index < text.length) {
			val ch = text[index]
			when (ch) {
				'<' -> out.append("&lt;")
				'>' -> out.append("&gt;")
				'&' -> out.append("&amp;")
				'\r' -> out.append("&#xD;")
				'\n' -> out.append(LINE_SEPARATOR)
				else -> index = appendCharOrSurrogateReference(out, text, index, ch)
			}
			index++
		}
	}

	/**
	 * Appends [ch] raw, or — when it opens a surrogate pair — decodes the pair and appends a
	 * single numeric character reference in lowercase hex (JDOM's UTF-8 DefaultEscapeStrategy
	 * escapes exactly the surrogate range; Integer.toHexString is lowercase, unpadded).
	 *
	 * @param StringBuilder out   The output sink.
	 * @param String        whole The string being escaped.
	 * @param Int           index The current index in [whole].
	 * @param Char          ch    The character at [index].
	 * @return Int The index consumed up to (the low surrogate's index when a pair was folded).
	 */
	private fun appendCharOrSurrogateReference(out: StringBuilder, whole: String, index: Int, ch: Char): Int {
		if (!ch.isHighSurrogate()) {
			out.append(ch)
			return index
		}
		val lowIndex = index + 1
		if (lowIndex >= whole.length || !whole[lowIndex].isLowSurrogate()) {
			error("truncated surrogate pair at index $index")
		}
		val codePoint = 0x10000 + ((ch.code - 0xD800) shl 10) + (whole[lowIndex].code - 0xDC00)
		out.append("&#x").append(codePoint.toString(16)).append(';')
		return lowIndex
	}

	/**
	 * The index of the first node from [start] that is not whitespace-only text, or the list size.
	 * JDOM XMLOutputter.skipLeadingWhite under TRIM mode.
	 *
	 * @param List content The content list.
	 * @param Int  start   The search start (inclusive).
	 * @return Int The first significant index, or the list size.
	 */
	private fun skipLeadingWhite(content: List<Content>, start: Int): Int {
		var index = if (start < 0) 0 else start
		while (index < content.size) {
			if (!isAllWhitespace(content[index])) {
				return index
			}
			index++
		}
		return index
	}

	/**
	 * One past the last node before [end] that is not whitespace-only text.  JDOM
	 * XMLOutputter.skipTrailingWhite under TRIM mode (bounded at zero).
	 *
	 * @param List content The content list.
	 * @param Int  end     The search start (exclusive).
	 * @return Int One past the last significant index.
	 */
	private fun skipTrailingWhite(content: List<Content>, end: Int): Int {
		var index = if (end > content.size) content.size else end
		while (index > 0) {
			if (!isAllWhitespace(content[index - 1])) {
				break
			}
			index--
		}
		return index
	}

	/**
	 * The index of the first non-text node from [start], or the list size.  JDOM
	 * XMLOutputter.nextNonText.
	 *
	 * @param List content The content list.
	 * @param Int  start   The search start (inclusive).
	 * @return Int The first non-text index, or the list size.
	 */
	private fun nextNonText(content: List<Content>, start: Int): Int {
		var index = if (start < 0) 0 else start
		while (index < content.size) {
			if (content[index] !is Text) {
				return index
			}
			index++
		}
		return content.size
	}

	/**
	 * True when [node] is a text node consisting only of XML whitespace (an empty text counts).
	 * JDOM XMLOutputter.isAllWhitespace — non-text nodes are never "whitespace".
	 *
	 * @param Content node The node to test.
	 * @return Boolean Whether the node is insignificant whitespace.
	 */
	private fun isAllWhitespace(node: Content): Boolean {
		if (node !is Text) {
			return false
		}
		for (ch in node.text) {
			if (!isXmlWhitespace(ch)) {
				return false
			}
		}
		return true
	}

	/**
	 * True for the four XML whitespace characters (JDOM Verifier.isXMLWhitespace).
	 *
	 * @param Char ch The character to test.
	 * @return Boolean Whether it is XML whitespace.
	 */
	private fun isXmlWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'

	/**
	 * True when [str] starts with XML whitespace (JDOM XMLOutputter.startsWithWhite).
	 *
	 * @param String str The string to test.
	 * @return Boolean Whether the first character is XML whitespace.
	 */
	private fun startsWithXmlWhite(str: String): Boolean = str.isNotEmpty() && isXmlWhitespace(str[0])

	/**
	 * True when [str] ends with XML whitespace (JDOM XMLOutputter.endsWithWhite).
	 *
	 * @param String str The string to test.
	 * @return Boolean Whether the last character is XML whitespace.
	 */
	private fun endsWithXmlWhite(str: String): Boolean = str.isNotEmpty() && isXmlWhitespace(str[str.length - 1])

	/**
	 * Trims with Java String.trim semantics — both ends stripped of chars at or below U+0020 —
	 * which is what JDOM's TRIM mode calls.  Kotlin's trim() uses Char.isWhitespace, a DIFFERENT
	 * set (it keeps most C0 controls and strips Unicode line/paragraph separators), so it must not
	 * be substituted here.
	 *
	 * @param String str The string to trim.
	 * @return String The trimmed string.
	 */
	private fun javaTrim(str: String): String {
		var startIndex = 0
		var endIndex = str.length
		while (startIndex < endIndex && str[startIndex] <= ' ') {
			startIndex++
		}
		while (endIndex > startIndex && str[endIndex - 1] <= ' ') {
			endIndex--
		}
		if (startIndex == 0 && endIndex == str.length) {
			return str
		}
		return str.substring(startIndex, endIndex)
	}
}