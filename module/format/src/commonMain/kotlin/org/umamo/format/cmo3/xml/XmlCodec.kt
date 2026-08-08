package org.umamo.format.cmo3.xml

import org.umamo.format.xml.Document
import org.umamo.format.xml.XmlEmitter
import org.umamo.format.xml.XmlParser

/**
 * Parses and emits the serialized model XML (main.xml) through the common XML layer.
 *
 * The emission contract is JDOM 1.1.3's XMLOutputter with Format.getPrettyFormat(), indent "" and
 * TextMode TRIM (line separator "\r\n", UTF-8) — the editor's exact writer configuration, which
 * [XmlEmitter] reproduces byte-for-byte across the whole corpus.  This object centralises that
 * contract so every emit path (generic round-trip and the typed model) shares one formatter, and
 * the JDOM differential oracle (XmlDifferentialOracleTest, jvmTest) holds both halves to the
 * original library's behavior.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public object XmlCodec {
	/**
	 * Parses model XML bytes into a document, preserving element/attribute/PI order.
	 *
	 * @param ByteArray bytes The decompressed main.xml bytes.
	 * @return Document The parsed document.
	 */
	public fun parse(bytes: ByteArray): Document = XmlParser.parse(bytes)

	/**
	 * Serializes a document back to bytes using the editor's exact format.
	 *
	 * @param Document document The document to emit.
	 * @return ByteArray The UTF-8 encoded XML, byte-compatible with the editor.
	 */
	public fun write(document: Document): ByteArray = XmlEmitter.emit(document)
}