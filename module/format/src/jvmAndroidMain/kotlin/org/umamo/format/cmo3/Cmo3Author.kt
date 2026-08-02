package org.umamo.format.cmo3

import org.jdom.Element
import org.jdom.ProcessingInstruction
import org.umamo.format.cmo3.serialize.cubismEngine
import org.umamo.format.cmo3.xml.XmlCodec

/**
 * Authors a main.xml for a NEVER-READ object graph, producing the full editor prologue that
 * `SerializeEngine.writeRoot` alone cannot: the `fileFormatVersion` root attribute, the
 * `<?version?>` class-version PIs, and the `<?import?>` tag-to-FQCN PIs (docs/format/CMO3.md §3
 * Document Shape).  Read graphs never come through here - `Cmo3.write` replays their recorded
 * prologue verbatim; this is the entry point for fresh-graph synthesis (a from-scratch CMO3 for a
 * MOC3-origin document), whose bytes are then wrapped in a CAFF archive and fed back through
 * `Cmo3.read` to obtain a reconcilable [Cmo3Model].
 */
public object Cmo3Author {
	/**
	 * The fileFormatVersion a fresh document writes.
	 *
	 * CMO3: root attribute fileFormatVersion (BareMinimum.cmo3, official editor 5.4 New -> Save
	 * As).  Pinned to the 5.4 era because the prologue tables (Cmo3PiTables) are transcribed from
	 * the 504000000 corpus samples; changing one without the other would author a mismatched
	 * prologue.
	 */
	public const val FRESH_FILE_FORMAT_VERSION: String = "504000000"

	/**
	 * Serializes [root] as a complete main.xml document with the 5.4-era prologue.
	 *
	 * The `<?import?>` list is derived from the document itself: one FQCN per distinct
	 * non-structural element tag actually emitted, sorted - exactly the rule every corpus file
	 * follows.  An element tag with no FQCN table entry is a hard error: it means an unregistered
	 * or newly modeled class whose import the official editor's reader would miss.
	 *
	 * @param Any root The fresh model root (a CModelSource web built in memory, never read).
	 * @return ByteArray The UTF-8 main.xml bytes, CRLF-framed like the editor's writer.
	 */
	public fun writeFreshMainXml(root: Any): ByteArray {
		val document = cubismEngine().writeRoot(root)
		val tags = HashSet<String>()
		collectTags(document.rootElement, tags)
		val modelTags = tags - CMO3_STRUCTURAL_TAGS
		val imports =
			modelTags
				.map { tag ->
					CMO3_TAG_TO_FQCN[tag]
						?: error("element tag <$tag> has no FQCN table entry; add it to Cmo3PiTables from a corpus sample")
				}
				.sorted()
		// CMO3: <?version Name:N?> PIs - the always-written pair, then per-tag entries in a
		// deterministic (sorted-tag) order; the editor's own order is HashMap iteration and not
		// reproducible, and readers key these by name.
		val versions = ArrayList(CMO3_VERSIONS_ALWAYS_5_4)
		for (tag in modelTags.sorted()) {
			CMO3_VERSIONS_BY_TAG_5_4[tag]?.let { versionEntry -> versions.add(versionEntry) }
		}
		// CMO3: root attribute fileFormatVersion - the root's only attribute in every corpus file.
		document.rootElement.setAttribute("fileFormatVersion", FRESH_FILE_FORMAT_VERSION)
		var instructionIndex = 0
		for ((piName, versionNumber) in versions) {
			document.addContent(instructionIndex++, ProcessingInstruction("version", "$piName:$versionNumber"))
		}
		for (fqcn in imports) {
			document.addContent(instructionIndex++, ProcessingInstruction("import", fqcn))
		}
		return XmlCodec.write(document)
	}

	/**
	 * Collects every element name in the subtree into [into].
	 *
	 * @param Element element The subtree root.
	 * @param HashSet into The tag collector.
	 */
	private fun collectTags(element: Element, into: HashSet<String>) {
		into.add(element.name)
		for (child in element.children) {
			collectTags(child as Element, into)
		}
	}
}
