package org.umamo.ui.document

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Failure classification of the byte-core document loader: each rejection path must report the
 * matching [DocumentOpenError] (the shell's alert message key) instead of a bare null, so a failed
 * open is always explainable to the user - plus the one success path synthetic bytes CAN prove, a
 * flat raster opening as a rig.  No corpus dependency.
 */
class DocumentLoadTest {
	private fun pngOf(width: Int, height: Int, alpha: Int): ByteArray {
		val rgba = ByteArray(width * height * 4) { index -> if (index % 4 == 3) alpha.toByte() else 0x40 }
		return PngCodec.write(RasterImage(width, height, rgba))
	}

	/** A flat raster is a one-layer artwork: it opens as a rig with one drawable, packed at open. */
	@Test
	fun aFlatRasterOpensAsAOneDrawableRig() {
		val load = loadDocument(pngOf(4, 4, alpha = 0xFF), "hero.png", "hero.png")
		val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
		val drawable = document.puppet.drawables.single()
		assertEquals("hero.png", drawable.name, "the one drawable is named after the file")
		assertNotNull(document.puppet.atlas.tiles.single().placement, "the pack at open placed the one tile")
		assertEquals(1, document.textures.atlases.size, "and composed the page it sits on")
		assertEquals("png", document.puppet.sources.single().format, "the source list records where it came from")
		assertNotNull(document.artRasters.rasterFor(drawable.atlasTileId!!), "the layer's pixels are the document's art")
	}

	/** A raster with no pixel over the threshold has nothing to rig; the open says so rather than opening blank. */
	@Test
	fun aFullyTransparentRasterFailsAsNoArtLayers() {
		val load = loadDocument(pngOf(4, 4, alpha = 0), "blank.png", "blank.png")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.NoArtLayers, failed.failure.error)
		assertEquals("blank.png", failed.failure.displayName)
	}

	@Test
	fun unrecognizedBytesFailAsUnrecognized() {
		val load = loadDocument(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "mystery.bin", "mystery.bin")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.Unrecognized, failed.failure.error)
		assertEquals("mystery.bin", failed.failure.displayName)
	}

	@Test
	fun recognizedNonEditorFormatFailsAsNotOpenable() {
		// MOC3 header: magic "MOC3" @ +0x00.  The BYTE-level loader keeps reporting MOC3 NotOpenable by
		// design: sidecar discovery needs a directory, so only the file-level loadDocument(PlatformFile)
		// routes a .moc3 to the sidecar loader (see Moc3DocumentLoadTest for that path).
		val bytes = ByteArray(64)
		"MOC3".encodeToByteArray().copyInto(bytes)
		val load = loadDocument(bytes, "puppet.moc3", "puppet.moc3")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.NotOpenable, failed.failure.error)
	}

	@Test
	fun corruptCmo3FailsAsParseFailed() {
		// CAFF magic @ +0x00 makes detection pick the CMO3 codec; the garbage tail makes its read throw.
		val bytes = ByteArray(64)
		"CAFF".encodeToByteArray().copyInto(bytes)
		val load = loadDocument(bytes, "broken.cmo3", "broken.cmo3")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.ParseFailed, failed.failure.error)
	}
}