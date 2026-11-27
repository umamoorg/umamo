package org.umamo.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the way in each file kind declares, because the answer decides what a file DOES to the editor: a
 * document replaces the open one, artwork is added to it, and a sidecar is never handed over on its own.
 * Getting a role wrong would make a dropped PSD discard a rig, or a dropped `.uma` land as source art.
 *
 * Listed exhaustively rather than sampled: the table is the specification, so a kind added without a
 * considered role fails here instead of quietly inheriting one.
 */
class FileKindTest {
	@Test
	fun everyKindDeclaresItsWayIn() {
		assertEquals(FileRole.Document, FileKind.Uma.role, "the native document")
		assertEquals(FileRole.Document, FileKind.Cmo3.role, "the Cubism source project, imported whole")
		assertEquals(FileRole.Document, FileKind.Moc3.role, "the baked runtime, imported whole")
		assertEquals(FileRole.Sidecar, FileKind.Json.role, "the MOC3 family, found beside a document")
		assertEquals(FileRole.Artwork, FileKind.Psd.role)
		assertEquals(FileRole.Artwork, FileKind.Clip.role)
		assertEquals(FileRole.Artwork, FileKind.Kra.role)
		assertEquals(FileRole.Artwork, FileKind.Png.role, "a flat raster is art, not an atlas page, when it arrives on its own")
		assertEquals(FileRole.Artwork, FileKind.Bmp.role)
		assertEquals(FileRole.Artwork, FileKind.Jpeg.role)
		assertEquals(FileRole.Artwork, FileKind.WebP.role)
		assertEquals(FileRole.Artwork, FileKind.Tiff.role)
	}

	/**
	 * Only the two formats written under a second extension declare one, and an alias is never the same
	 * string as some kind's primary extension - one name must resolve to one format.
	 */
	@Test
	fun onlyTheFormatsWithASecondNameDeclareAliases() {
		assertEquals(listOf("jpeg"), FileKind.Jpeg.extensionAliases)
		assertEquals(listOf("tif"), FileKind.Tiff.extensionAliases)
		assertTrue(FileKind.Uma.extensionAliases.isEmpty(), "a kind declares none by default")
		assertTrue(FileKind.Png.extensionAliases.isEmpty())
	}
}