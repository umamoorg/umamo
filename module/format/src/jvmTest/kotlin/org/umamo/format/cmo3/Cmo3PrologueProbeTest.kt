package org.umamo.format.cmo3

import org.jdom.Element
import org.jdom.ProcessingInstruction
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the main.xml prologue and CAFF-table invariants the fresh-graph writer builds against,
 * corpus-wide (see docs/format/CMO3.md §2 invariants and §3 Document Shape):
 *
 *  - the main_xml entry is the LAST entry in the file table, and every entry is obfuscated;
 *  - all processing instructions precede the root element, versions before imports, and no
 *    other PI target exists;
 *  - the root element carries exactly one attribute, fileFormatVersion;
 *  - no element tag anywhere is a fully-qualified name (no '.' or '$');
 *  - the import PI set maps 1:1 onto the document's distinct non-primitive element tags (the
 *    FQCN's segment after the last '$' or '.' is the tag), and the import list is sorted;
 *  - SerializeFormatVersion:2 is present in every file's version PIs.
 */
class Cmo3PrologueProbeTest {
	/** Tags owned by the serializer itself rather than a model class - excluded from the import mapping. */
	private val structuralTags =
		setOf(
			"root",
			"main",
			"shared",
			"entry",
			"null",
			"file",
			"i",
			"f",
			"d",
			"l",
			"short",
			"byte",
			"char",
			"b",
			"s",
			"array_list",
			"carray_list",
			"hash_map",
			"linked_map",
			"linked_set",
			"float-array",
			"int-array",
			"long-array",
			"short-array",
			"double-array",
			"byte-array",
			"char-array",
			"bool-array",
		)

	@Test
	fun prologueInvariantsHoldAcrossTheCorpus() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping prologue probe")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		val failures = ArrayList<String>()
		for (file in files) {
			probeFile(file, failures)
		}
		assertTrue(files.isNotEmpty(), "cmo3.probe matched no files")
		assertTrue(failures.isEmpty(), "prologue invariants violated:\n" + failures.joinToString("\n"))
	}

	/**
	 * Checks every invariant on one corpus file, appending human-readable violations.
	 *
	 * @param File file The corpus .cmo3 file.
	 * @param ArrayList failures The shared violation collector.
	 */
	private fun probeFile(file: File, failures: ArrayList<String>) {
		val archive = CaffCodec.read(file.readBytes())
		if (archive.entries.last().tag != CaffArchive.TAG_MAIN_XML) {
			failures.add("${file.name}: main_xml is not the last CAFF entry")
		}
		if (archive.entries.count { entry -> entry.tag == CaffArchive.TAG_MAIN_XML } != 1) {
			failures.add("${file.name}: expected exactly one main_xml entry")
		}
		archive.entries.filterNot { entry -> entry.obfuscated }.forEach { entry ->
			failures.add("${file.name}: entry ${entry.path} is not obfuscated")
		}
		val mainXml = archive.firstByTag(CaffArchive.TAG_MAIN_XML) ?: return
		val document = XmlCodec.parse(mainXml.content)

		// Prologue: version PIs, then import PIs, all before <root>; no other PI target.
		val instructions = document.content.filterIsInstance<ProcessingInstruction>()
		val targets = instructions.map { instruction -> instruction.target }
		targets.filterNot { target -> target == "version" || target == "import" }.forEach { target ->
			failures.add("${file.name}: unexpected PI target <?$target?>")
		}
		val lastVersionIndex = targets.lastIndexOf("version")
		val firstImportIndex = targets.indexOf("import")
		if (firstImportIndex >= 0 && lastVersionIndex > firstImportIndex) {
			failures.add("${file.name}: a version PI follows an import PI")
		}
		val rootIndex = document.content.indexOf(document.rootElement)
		if (instructions.isNotEmpty() && document.content.indexOf(instructions.last()) > rootIndex) {
			failures.add("${file.name}: a PI appears after <root>")
		}

		// Root attributes: exactly fileFormatVersion.
		val attributeNames = document.rootElement.attributes.map { attribute -> (attribute as org.jdom.Attribute).name }
		if (attributeNames != listOf("fileFormatVersion")) {
			failures.add("${file.name}: root attributes are $attributeNames")
		}

		// Version PIs parse as Name:Int and include SerializeFormatVersion:2.
		val versions =
			instructions.filter { instruction -> instruction.target == "version" }.map { instruction -> instruction.data }
		versions.filterNot { data -> data.substringAfterLast(':').toIntOrNull() != null }.forEach { data ->
			failures.add("${file.name}: unparseable version PI <?version $data?>")
		}
		if ("SerializeFormatVersion:2" !in versions) {
			failures.add("${file.name}: SerializeFormatVersion:2 missing from version PIs")
		}

		// Element tags: never fully qualified; import PIs map 1:1 onto the non-structural tag set.
		val tags = HashSet<String>()
		collectTags(document.rootElement, tags)
		tags.filter { tag -> '.' in tag || '$' in tag }.forEach { tag ->
			failures.add("${file.name}: fully-qualified element tag <$tag>")
		}
		val modelTags = tags - structuralTags
		val imports =
			instructions.filter { instruction -> instruction.target == "import" }.map { instruction -> instruction.data }
		if (imports != imports.sorted()) {
			failures.add("${file.name}: import PIs are not sorted")
		}
		val importedTags = imports.map { fqcn -> fqcn.substringAfterLast('$').substringAfterLast('.') }.toSet()
		if (importedTags.size != imports.size) {
			failures.add("${file.name}: two import FQCNs map to one tag")
		}
		(modelTags - importedTags).forEach { tag ->
			failures.add("${file.name}: tag <$tag> has no import PI")
		}
		(importedTags - modelTags).forEach { tag ->
			failures.add("${file.name}: import for <$tag> matches no element tag")
		}
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