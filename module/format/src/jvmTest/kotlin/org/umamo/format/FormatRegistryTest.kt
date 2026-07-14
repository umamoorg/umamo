package org.umamo.format

import org.umamo.format.bmp.BmpCodec
import org.umamo.format.clip.ClipReader
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.jpeg.JpegReader
import org.umamo.format.kra.KraReader
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdReader
import org.umamo.format.tiff.TiffReader
import org.umamo.format.webp.WebPReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Confirms the format dispatcher routes a file to the right codec by leading magic, falls back to the
 * file extension when the magic is unknown, and resolves a codec by [FileKind]. The fixtures are just
 * correctly-magicked headers - no full container is needed to exercise `matches`. Every reader,
 * PSD included, is registered on every target: PsdReader is pure Kotlin with no desktop-only
 * dependency, so these JVM-target assertions hold on Android too.
 */
class FormatRegistryTest {
	/** A 64-byte buffer whose first four bytes are the MOC3 magic (what `isMoc3` requires). */
	private fun mocHeaderBytes(): ByteArray = ByteArray(64).also { "MOC3".encodeToByteArray().copyInto(it) }

	/** A buffer whose first four bytes are the CAFF magic (what `isCaff` requires). */
	private fun caffHeaderBytes(): ByteArray = "CAFF".encodeToByteArray() + ByteArray(60)

	/** A buffer whose first four bytes are the PSD signature `8BPS`. */
	private fun psdHeaderBytes(): ByteArray = "8BPS".encodeToByteArray() + ByteArray(60)

	/** A ZIP header with the Krita `application/x-kra` mimetype marker placed in the first 128 bytes. */
	private fun kraHeaderBytes(): ByteArray =
		byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(30) + "application/x-kra".encodeToByteArray() + ByteArray(8)

	/** A buffer whose first eight bytes are the CLIP container magic `CSFCHUNK`. */
	private fun clipHeaderBytes(): ByteArray = "CSFCHUNK".encodeToByteArray() + ByteArray(56)

	/** A buffer whose first eight bytes are the PNG signature. */
	private fun pngHeaderBytes(): ByteArray =
		byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

	/** A buffer whose first two bytes are the BMP `BM` signature. */
	private fun bmpHeaderBytes(): ByteArray = byteArrayOf(0x42, 0x4D) + ByteArray(60)

	/** A buffer whose first three bytes are the JPEG SOI + marker prefix. */
	private fun jpegHeaderBytes(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)

	/** A RIFF container tagged `WEBP` in the form-type slot. */
	private fun webpHeaderBytes(): ByteArray =
		"RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + ByteArray(16)

	/** A little-endian TIFF header (`II` 0x2A00). */
	private fun tiffHeaderBytes(): ByteArray = byteArrayOf(0x49, 0x49, 0x2A, 0x00) + ByteArray(16)

	@Test
	fun detectRoutesByMagic() {
		assertEquals(FileKind.Moc3, FormatRegistry.detect(mocHeaderBytes())?.kind, "MOC3 magic -> Moc3")
		assertEquals(FileKind.Cmo3, FormatRegistry.detect(caffHeaderBytes())?.kind, "CAFF magic -> Cmo3")
		assertEquals(FileKind.Psd, FormatRegistry.detect(psdHeaderBytes())?.kind, "8BPS magic -> Psd")
		assertEquals(FileKind.Kra, FormatRegistry.detect(kraHeaderBytes())?.kind, "ZIP + x-kra marker -> Kra")
		assertEquals(FileKind.Clip, FormatRegistry.detect(clipHeaderBytes())?.kind, "CSFCHUNK magic -> Clip")
		assertEquals(FileKind.Png, FormatRegistry.detect(pngHeaderBytes())?.kind, "PNG signature -> Png")
		assertEquals(FileKind.Bmp, FormatRegistry.detect(bmpHeaderBytes())?.kind, "BM magic -> Bmp")
		assertEquals(FileKind.Jpeg, FormatRegistry.detect(jpegHeaderBytes())?.kind, "JPEG SOI -> Jpeg")
		assertEquals(FileKind.WebP, FormatRegistry.detect(webpHeaderBytes())?.kind, "RIFF/WEBP -> WebP")
		assertEquals(FileKind.Tiff, FormatRegistry.detect(tiffHeaderBytes())?.kind, "TIFF II* -> Tiff")
	}

	@Test
	fun detectFallsBackToExtensionWhenMagicUnknown() {
		val unknown = byteArrayOf(0, 1, 2, 3)
		assertEquals(FileKind.Kra, FormatRegistry.detect(unknown, "drawing.kra")?.kind, "unknown magic + .kra -> Kra")
		assertEquals(FileKind.Psd, FormatRegistry.detect(unknown, "art.PSD")?.kind, "extension match is case-insensitive")
		assertEquals(FileKind.Cmo3, FormatRegistry.detect(unknown, "/path/to/model.cmo3")?.kind, "full path -> extension")
		assertEquals(FileKind.Png, FormatRegistry.detect(unknown, "atlas.png")?.kind, "unknown magic + .png -> Png")
		assertEquals(FileKind.Bmp, FormatRegistry.detect(unknown, "dump.bmp")?.kind, "unknown magic + .bmp -> Bmp")
	}

	@Test
	fun detectReturnsNullForUnknownBytes() {
		assertNull(FormatRegistry.detect(byteArrayOf(0, 1, 2, 3)), "unrecognised magic, no name -> null")
		assertNull(FormatRegistry.detect(byteArrayOf(0, 1, 2, 3), "notes.txt"), "unrecognised magic + unknown ext -> null")
	}

	@Test
	fun forKindResolvesRegisteredCodecsOnly() {
		assertSame(Cmo3, FormatRegistry.forKind(FileKind.Cmo3), "Cmo3 codec for FileKind.Cmo3")
		assertSame(Moc3, FormatRegistry.forKind(FileKind.Moc3), "Moc3 codec for FileKind.Moc3")
		assertSame(KraReader, FormatRegistry.forKind(FileKind.Kra), "KraReader codec for FileKind.Kra")
		assertSame(PsdReader, FormatRegistry.forKind(FileKind.Psd), "PsdReader codec for FileKind.Psd")
		assertSame(ClipReader, FormatRegistry.forKind(FileKind.Clip), "ClipReader codec for FileKind.Clip")
		assertSame(PngCodec, FormatRegistry.forKind(FileKind.Png), "PngCodec codec for FileKind.Png")
		assertSame(BmpCodec, FormatRegistry.forKind(FileKind.Bmp), "BmpCodec codec for FileKind.Bmp")
		assertSame(JpegReader, FormatRegistry.forKind(FileKind.Jpeg), "JpegReader codec for FileKind.Jpeg")
		assertSame(WebPReader, FormatRegistry.forKind(FileKind.WebP), "WebPReader codec for FileKind.WebP")
		assertSame(TiffReader, FormatRegistry.forKind(FileKind.Tiff), "TiffReader codec for FileKind.Tiff")
	}
}
