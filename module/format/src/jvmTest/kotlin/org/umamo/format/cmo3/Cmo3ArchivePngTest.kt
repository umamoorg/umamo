package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.type.FileRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the retained-archive PNG primitives a page mint/delete leans on: an added entry round-trips
 * by name with `main_xml` still last and the corpus obfuscation convention, the path allocator
 * continues past the retained maximum, and a removal leaves every other entry served.  Skips
 * without the sample.
 */
class Cmo3ArchivePngTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun anAddedPngRoundTripsAndKeepsMainXmlLast() {
		val file =
			sample ?: run {
				println("cmo3.sample not present; skipping archive png test")
				return
			}
		val model = Cmo3.read(file)
		val donorPng = assertNotNull(model.extractLayerPng(model.imageResources().first()))
		val mintedPath = model.nextImageFileBufPath()
		// CMO3: CImageResource fields width, height, type, imageFileBuf, imageFileBuf_size.
		val resource =
			CImageResource().apply {
				width = 16
				height = 16
				type = "INT_ARGB"
				imageFileBuf = FileRef().apply { archivePath = mintedPath }
			}

		model.addLayerPng(resource, donorPng)

		assertEquals(donorPng.size, resource.imageFileBuf_size, "the size attribute follows the bytes")
		val rewritten = Cmo3.write(model)
		val rereadArchive = CaffCodec.read(rewritten)
		assertContentEquals(donorPng, rereadArchive.byPath(mintedPath)?.content, "the added entry serves by name")
		assertEquals(
			CaffArchive.TAG_MAIN_XML,
			rereadArchive.entries.last().tag,
			"main_xml stays the last entry in the table",
		)
		val added = assertNotNull(rereadArchive.byPath(mintedPath))
		assertTrue(added.obfuscated, "pixel entries follow the corpus obfuscation convention")

		// A second add under the same path must refuse rather than shadow.
		assertFailsWith<IllegalArgumentException> { model.addLayerPng(resource, donorPng) }
	}

	@Test
	fun thePathAllocatorContinuesPastTheRetainedMax() {
		val file = sample ?: return
		val model = Cmo3.read(file)
		val highestRetained =
			model.archive.entries
				.mapNotNull { entry ->
					when {
						entry.path == "imageFileBuf.png" -> -1
						else ->
							entry.path
								.removePrefix("imageFileBuf_")
								.removeSuffix(".png")
								.takeIf { middle -> "imageFileBuf_$middle.png" == entry.path }
								?.toIntOrNull()
					}
				}.maxOrNull()

		val minted = model.nextImageFileBufPath()

		assertNotNull(highestRetained, "the sample retains imageFileBuf entries")
		assertEquals("imageFileBuf_${highestRetained + 1}.png", minted, "the allocator continues past the retained max")
		assertNull(model.archive.byPath(minted), "the minted path is unused")
	}

	@Test
	fun aRemovedEntryLeavesTheOthersServed() {
		val file = sample ?: return
		val model = Cmo3.read(file)
		val resources = model.imageResources()
		val removed = resources.first()
		val removedPath = assertNotNull(removed.imageFileBuf?.archivePath)
		val keptResource = resources[1]
		val keptBytes = assertNotNull(model.extractLayerPng(keptResource))

		model.removeLayerPng(removed)

		assertNull(model.archive.byPath(removedPath), "the entry is gone")
		val reread = CaffCodec.read(Cmo3.write(model))
		assertNull(reread.byPath(removedPath), "and stays gone through a write")
		assertContentEquals(
			keptBytes,
			reread.byPath(assertNotNull(keptResource.imageFileBuf?.archivePath))?.content,
			"every other entry still serves",
		)
	}
}