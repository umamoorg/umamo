package org.umamo.ui.document

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end load of a real `.cmo3` through [loadDocument] into [buildCmo3Document], the counterpart
 * to [Moc3DocumentLoadTest]'s family check.  The failure paths are covered synthetically in
 * [DocumentLoadTest]; what this adds is the one thing fake bytes cannot prove - that a genuine
 * corpus model comes out with its embedded atlas decoded and bound to real drawables.
 *
 * That binding is what the atlas walk exists to produce, and a real model is the only thing that can
 * exercise it: synthetic bytes carry no embedded pages to decode.  Reads the corpus sample
 * (`-Dcmo3.sample`, defaulted to the local corpus by the build) and self-skips without it, so CI
 * stays green on a fresh clone.
 */
class Cmo3DocumentLoadTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aCorpusModelLoadsWithItsEmbeddedAtlasBound() {
		val file = sample ?: return
		val load = loadDocument(file.readBytes(), file.name, file.path)

		val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(load).document)
		assertTrue(document.puppet.drawables.isNotEmpty(), "the corpus model has drawables")

		// A CMO3 embeds its pixels, so a successful open must yield at least one decoded page - an empty
		// atlas list here is the silent failure mode (every drawable would render in fallback color, and
		// nothing else in the load path would complain).
		val textures = document.textures
		assertTrue(textures.atlases.isNotEmpty(), "at least one atlas page decoded")
		for (page in textures.atlases) {
			assertTrue(page.width > 0 && page.height > 0, "page has real dimensions")
			assertEquals(page.width * page.height * 4, page.rgba.size, "RGBA8888, tightly packed")
		}

		// Every mapped drawable must resolve to a page the renderer can actually index: PuppetRenderer
		// looks pages up by direct list indexing, so a stale index is a first-frame crash.
		assertTrue(textures.atlasIndexByDrawableId.isNotEmpty(), "drawables are bound to pages")
		for ((drawableId, pageIndex) in textures.atlasIndexByDrawableId) {
			assertTrue(pageIndex in textures.atlases.indices, "$drawableId points at a decoded page")
		}
	}
}
