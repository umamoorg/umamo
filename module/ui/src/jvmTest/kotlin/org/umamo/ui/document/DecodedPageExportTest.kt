package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import org.umamo.format.png.PngCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what lets a CMO3 export of a MOC3-origin document read the document's own decoded pages instead of
 * decoding its page PNGs a second time: those pages ARE the PNGs decoded, page for page in the same order, and
 * the export hands them over without copying them and gives them back untouched - the viewport draws from the
 * very same buffers.
 *
 * Reads the corpus sample (`-Dmoc3.sample`, defaulted to the local corpus by the build) and self-skips without it.
 * The whole-corpus proof that the files an export writes did not change is the before-and-after comparison
 * recorded in the distribution plan; this is the invariant that comparison rests on.
 */
class DecodedPageExportTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	/**
	 * The corpus sample opened the way File > Import MOC3 opens it, or null without a sample.
	 *
	 * @return Moc3Document? The loaded document.
	 */
	private fun loadSample(): Moc3Document? {
		val file = sample ?: return null
		val load = runBlocking { loadMoc3Document(PlatformFile(file), file.readBytes()) }
		return assertIs<Moc3Document>(assertIs<DocumentLoad.Loaded>(load).document)
	}

	@Test
	fun theHandedOverPagesAreThePagePngsDecodedWithoutACopy() {
		val document = loadSample()
		if (document == null) {
			println("moc3.sample not present; skipping the decoded-page export gate")
			return
		}
		val pages = conversionPagesFor(document)
		assertEquals(document.textures.atlases.size, pages.size, "one conversion page per decoded page")
		for ((pageIndex, page) in pages.withIndex()) {
			val decoded = assertNotNull(page.decoded, "page $pageIndex carries its decoded pixels")
			assertSame(document.textures.atlases[pageIndex].rgba, decoded.rgba, "page $pageIndex is the document's own buffer, not a copy")
			assertContentEquals(PngCodec.read(page.pngBytes).rgba, decoded.rgba, "page $pageIndex decoded pixels are its PNG decoded")
		}
	}

	@Test
	fun anExportLeavesTheDocumentsPagesUntouched() {
		val document = loadSample()
		if (document == null) {
			println("moc3.sample not present; skipping the decoded-page export gate")
			return
		}
		val before = document.textures.atlases.map { page -> page.rgba.contentHashCode() }

		val rendered = renderCmo3Export(document, exportedModelFor(document, null), document.textures, "gate", nowMillis = 0L, obfuscateKey = 0)

		assertTrue(rendered.bytes.isNotEmpty(), "the export wrote a file")
		assertEquals(before, document.textures.atlases.map { page -> page.rgba.contentHashCode() }, "every page the viewport draws from is as it was")
	}
}