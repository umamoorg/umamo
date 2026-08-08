package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.caff.CaffEntry
import org.umamo.format.cmo3.caff.CompressOption
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CImageCanvas
import org.umamo.format.cmo3.xml.XmlCodec
import org.umamo.format.xml.Element
import org.umamo.format.xml.ProcessingInstruction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gates the fresh-document prologue authoring (Cmo3Author + Cmo3PiTables) two ways: the corpus
 * sweep re-derives every sample's <?import?> list through our tables and compares VERBATIM (plus
 * the <?version?> set for the 5.4-era files the tables target), and the synthetic case authors a
 * minimal never-read CModelSource, wraps it in a CAFF archive, and proves the result reads back
 * through Cmo3.read and re-emits its main.xml byte-identically (the prologue replays).
 */
class Cmo3AuthorTest {
	@Test
	fun tableDerivedPrologueMatchesTheCorpusVerbatim() {
		val spec =
			System.getProperty("cmo3.probe")
				?: run {
					println("cmo3.probe not present; skipping prologue derivation sweep")
					return
				}
		val files = spec.split(',').map { File(it.trim()) }.filter { it.isFile }
		val failures = ArrayList<String>()
		for (file in files) {
			val archive = CaffCodec.read(file.readBytes())
			val mainXml = archive.firstByTag(CaffArchive.TAG_MAIN_XML) ?: continue
			val document = XmlCodec.parse(mainXml.content)
			val instructions = document.content.filterIsInstance<ProcessingInstruction>()
			val actualImports =
				instructions.filter { instruction -> instruction.target == "import" }.map { instruction -> instruction.data }
			val tags = HashSet<String>()
			collectTags(document.rootElement, tags)
			val modelTags = tags - CMO3_STRUCTURAL_TAGS
			val unmapped = modelTags.filter { tag -> tag !in CMO3_TAG_TO_FQCN }
			if (unmapped.isNotEmpty()) {
				failures.add("${file.name}: tags missing from CMO3_TAG_TO_FQCN: $unmapped")
				continue
			}
			val derivedImports = modelTags.map { tag -> CMO3_TAG_TO_FQCN.getValue(tag) }.sorted()
			if (derivedImports != actualImports) {
				val missing = actualImports.toSet() - derivedImports.toSet()
				val extra = derivedImports.toSet() - actualImports.toSet()
				failures.add("${file.name}: import derivation differs (missing=$missing extra=$extra)")
			}
			// The version tables target the 5.4 era only; compare as a set (the editor's own PI
			// order is HashMap iteration and carries no meaning).
			val fileFormatVersion = document.rootElement.getAttributeValue("fileFormatVersion")
			if (fileFormatVersion == Cmo3Author.FRESH_FILE_FORMAT_VERSION) {
				val actualVersions =
					instructions
						.filter { instruction -> instruction.target == "version" }
						.map { instruction -> instruction.data }
						.toSet()
				val derivedVersions = HashSet<String>()
				CMO3_VERSIONS_ALWAYS_5_4.forEach { (piName, number) -> derivedVersions.add("$piName:$number") }
				for (tag in modelTags) {
					CMO3_VERSIONS_BY_TAG_5_4[tag]?.let { (piName, number) -> derivedVersions.add("$piName:$number") }
				}
				if (derivedVersions != actualVersions) {
					failures.add(
						"${file.name}: version derivation differs " +
							"(missing=${actualVersions - derivedVersions} extra=${derivedVersions - actualVersions})",
					)
				}
			}
		}
		assertTrue(files.isNotEmpty(), "cmo3.probe matched no files")
		assertTrue(failures.isEmpty(), "prologue derivation drift:\n" + failures.joinToString("\n"))
	}

	@Test
	fun freshDocumentReadsBackAndReEmitsByteIdentically() {
		val root =
			CModelSource().apply {
				name = "Untitled Model"
				canvas =
					CImageCanvas().apply {
						pixelWidth = 1000
						pixelHeight = 2000
					}
			}
		val mainXml = Cmo3Author.writeFreshMainXml(root)

		// The authored prologue is present and well-formed.
		val document = XmlCodec.parse(mainXml)
		assertEquals(
			Cmo3Author.FRESH_FILE_FORMAT_VERSION,
			document.rootElement.getAttributeValue("fileFormatVersion"),
			"fileFormatVersion attribute",
		)
		val instructions = document.content.filterIsInstance<ProcessingInstruction>()
		val imports = instructions.filter { instruction -> instruction.target == "import" }.map { instruction -> instruction.data }
		assertTrue(imports.isNotEmpty(), "import PIs authored")
		assertEquals(imports.sorted(), imports, "import PIs sorted")
		assertTrue("com.live2d.graphics.CImageCanvas" in imports, "canvas class imported")
		val versions = instructions.filter { instruction -> instruction.target == "version" }.map { instruction -> instruction.data }
		assertTrue("SerializeFormatVersion:2" in versions, "SerializeFormatVersion authored")
		assertTrue("CModelSource:16" in versions, "CModelSource version authored")

		// Wrapped in a CAFF archive, the fresh document round-trips through the normal codec and
		// re-emits its main.xml byte-for-byte (the read graph replays the authored prologue).
		val archive =
			CaffArchive(
				obfuscateKey = 0x49C74776.toInt(),
				entries = listOf(CaffEntry("main.xml", CaffArchive.TAG_MAIN_XML, mainXml, CompressOption.FAST, obfuscated = true)),
			)
		val model = Cmo3.read(CaffCodec.write(archive))
		val readRoot = model.root as? CModelSource ?: error("fresh root did not read back as CModelSource")
		assertEquals("Untitled Model", readRoot.name, "name survives the round trip")
		val reEmitted = CaffCodec.read(Cmo3.write(model)).firstByTag(CaffArchive.TAG_MAIN_XML) ?: error("no main_xml after write")
		assertContentEquals(mainXml, reEmitted.content, "re-emitted main.xml is byte-identical")
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
			collectTags(child, into)
		}
	}
}