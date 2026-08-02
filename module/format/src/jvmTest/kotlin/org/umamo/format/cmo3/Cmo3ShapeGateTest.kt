package org.umamo.format.cmo3

import org.jdom.Element
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Element-SHAPE gate: every class element an Umamo-written CMO3 emits must carry a field set the
 * official editor also writes somewhere in the corpus.
 *
 * The editor's custom deserializers dereference fields unconditionally, so a shape it has never
 * produced is a shape it may not survive - and the failure is a load-time NPE, not a validation
 * message.  Two rounds of official-editor rejection were exactly this: a CPartForm carrying
 * drawOrder + opacity but no multiply/screen colors (the corpus only ever has all five, or the
 * pre-5.3 drawOrder-only shape), and forms missing the mandatory notes string.  Presence audits
 * per field miss these because each individual field IS legal - it is the COMBINATION that is
 * novel.
 *
 * Corpus files whose name contains "ExportTest" are Umamo-written (promoted conversion outputs);
 * every other corpus file is editor-written and defines the legal shape vocabulary.
 */
class Cmo3ShapeGateTest {
	/** Serializer-owned tags whose children are elements rather than named fields. */
	private val collectionTags: Set<String> = CMO3_STRUCTURAL_TAGS

	@Test
	fun writtenElementShapesAllOccurInAnEditorWrittenFile() {
		val probe =
			System.getProperty("cmo3.probe") ?: run {
				println("cmo3.probe not present; skipping shape gate")
				return
			}
		val files = probe.split(',').map { path -> File(path.trim()) }.filter { file -> file.isFile }
		val (ours, official) = files.partition { file -> file.name.contains("ExportTest") }
		if (ours.isEmpty() || official.isEmpty()) {
			println("shape gate needs both Umamo-written and editor-written corpus files; skipping")
			return
		}
		val knownShapes = HashMap<String, MutableSet<List<String>>>()
		for (file in official) {
			collectShapes(rootOf(file)) { tag, shape ->
				knownShapes.getOrPut(tag) { HashSet() }.add(shape)
			}
		}
		val violations = ArrayList<String>()
		for (file in ours) {
			val seen = HashSet<Pair<String, List<String>>>()
			collectShapes(rootOf(file)) { tag, shape ->
				// A tag absent from every editor-written file is not a shape question (the coverage
				// and prologue gates own unknown tags); only novel shapes of KNOWN tags are reported.
				val known = knownShapes[tag] ?: return@collectShapes
				if (shape !in known && seen.add(tag to shape)) {
					violations.add(
						"${file.name}: <$tag> field set $shape occurs in no editor-written corpus file " +
							"(closest known: ${known.minByOrNull { candidate -> candidate.size }})",
					)
				}
			}
		}
		assertTrue(violations.isEmpty(), "novel element shapes:\n" + violations.joinToString("\n"))
	}

	/**
	 * Parses a CMO3's decompressed main.xml and returns its root element.
	 *
	 * @param File file The corpus file.
	 * @return Element The parsed root.
	 */
	private fun rootOf(file: File): Element {
		val mainXml =
			CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)
				?: error("${file.name}: no main.xml entry")
		return XmlCodec.parse(mainXml.content).rootElement
	}

	/**
	 * Walks [element] depth-first, reporting each class element's direct-child field-name sequence.
	 *
	 * @param Element  element The subtree root.
	 * @param Function report  Receives (tag, field-name sequence) per class element.
	 */
	private fun collectShapes(element: Element, report: (String, List<String>) -> Unit) {
		@Suppress("UNCHECKED_CAST")
		val children = element.children as List<Element>
		if (element.name !in collectionTags) {
			report(element.name, children.map { child -> child.getAttributeValue("xs.n") ?: child.name })
		}
		for (child in children) {
			collectShapes(child, report)
		}
	}
}
