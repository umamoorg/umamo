package org.umamo.ui.document

import org.umamo.format.png.PngCodec
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MOC3 export's naming and page-payload rules, on hand-built inputs.
 *
 * Deliberately NOT sample-gated: these are the branches the corpus round trip cannot reach.  A corpus
 * document always has a manifest and always has source page bytes, so that test only ever exercises the
 * "source name, verbatim bytes" side of both decisions.  The CMO3-origin side has neither, and is
 * covered here or nowhere.
 */
class Moc3ExportPolicyTest {
	/**
	 * A solid-color decoded page.
	 *
	 * @param Int width  Page width.
	 * @param Int height Page height.
	 * @return DecodedImage The page.
	 */
	private fun decodedPage(width: Int, height: Int): DecodedImage =
		DecodedImage(ByteArray(width * height * 4) { 0x7F }, width, height)

	/**
	 * A texture set over [pages] with no drawable bindings.
	 *
	 * @param List<DecodedImage> pages The decoded pages.
	 * @return PuppetTextures The set.
	 */
	private fun texturesOf(vararg pages: DecodedImage): PuppetTextures =
		PuppetTextures(pages.toList(), emptyMap(), premultipliedAlpha = false)

	@Test
	fun aCmo3OriginPageIsNamedAfterTheExport() {
		val pages = atlasPagesFor(texturesOf(decodedPage(4, 4), decodedPage(2, 2)), moc3Document = null, basename = "Model")
		assertEquals(listOf("Model.0.png", "Model.1.png"), pages.map { page -> page.fileName })
	}

	@Test
	fun aCmo3OriginPageIsReEncodedFromTheDecodedPixels() {
		// The one path that produces bytes rather than passing them through: a CMO3-origin document has
		// no source PNG at all, so a page that did not re-encode would be written empty.
		val pages = atlasPagesFor(texturesOf(decodedPage(6, 3)), moc3Document = null, basename = "Model")
		val decoded = PngCodec.read(pages.single().bytes)
		assertEquals(6, decoded.width, "re-encoded page width")
		assertEquals(3, decoded.height, "re-encoded page height")
	}

	@Test
	fun everyDecodedPageIsWritten() {
		// Driven by the decoded set, so the page a drawable's index refers to always exists.  Dropping
		// one would renumber every later page out from under the drawables referencing it.
		val pages = atlasPagesFor(texturesOf(decodedPage(1, 1), decodedPage(1, 1), decodedPage(1, 1)), null, "Model")
		assertEquals(3, pages.size)
		assertTrue(pages.all { page -> page.bytes.isNotEmpty() }, "no page may be written empty")
	}

	@Test
	fun theBasenameStripIgnoresExtensionCase() {
		// A destination spelled `.MOC3` that kept its extension would name every sibling - the manifest
		// included - after an `X.MOC3.moc3` that no write ever produces.
		assertEquals("Model", Moc3Sidecars.basenameFor("Model.MOC3"))
		assertEquals("Model", Moc3Sidecars.basenameFor("Model.moc3"))
		assertEquals("Model", Moc3Sidecars.basenameFor("Model.Moc3"))
	}

	@Test
	fun aNameWithoutTheExtensionIsItsOwnBasename() {
		assertEquals("Model", Moc3Sidecars.basenameFor("Model"))
		// Only a TRAILING `.moc3` is an extension; an interior one is part of the name.
		assertEquals("Model.moc3.backup", Moc3Sidecars.basenameFor("Model.moc3.backup"))
	}

	@Test
	fun theSuggestedNameStripsEitherSourceExtension() {
		// Both, regardless of the destination format: the point is to reach the model's own name, so a
		// rigger exporting Model.moc3 to CMO3 gets Model.cmo3 rather than Model.moc3.cmo3.
		assertEquals("Model", exportSuggestedName("Model.cmo3"))
		assertEquals("Model", exportSuggestedName("Model.moc3"))
		assertEquals("Model", exportSuggestedName("Model.CMO3"))
		assertEquals("Model", exportSuggestedName("Model"))
	}

	/**
	 * One retained sidecar of [kind].
	 *
	 * @param SidecarKind kind     The sidecar's kind.
	 * @param String      fileName Its relative file name.
	 * @return PassThroughSidecar The sidecar.
	 */
	private fun sidecarOf(kind: Moc3Sidecars.SidecarKind, fileName: String): Moc3Sidecars.PassThroughSidecar =
		Moc3Sidecars.PassThroughSidecar(kind, fileName, text = "{}")

	private fun retainedSidecars(): List<Moc3Sidecars.PassThroughSidecar> =
		listOf(
			sidecarOf(Moc3Sidecars.SidecarKind.Physics, "Model.physics3.json"),
			sidecarOf(Moc3Sidecars.SidecarKind.UserData, "Model.userdata3.json"),
			sidecarOf(Moc3Sidecars.SidecarKind.Pose, "Model.pose3.json"),
		)

	@Test
	fun defaultOptionsCarryEveryRetainedSidecar() {
		val exported = exportedSidecarsFor(retainedSidecars(), Moc3ExportOptions.Default)
		assertEquals(retainedSidecars().map { sidecar -> sidecar.fileName }, exported.map { sidecar -> sidecar.fileName })
	}

	@Test
	fun optingOutDropsExactlyTheOptedOutSidecarKinds() {
		val exported =
			exportedSidecarsFor(
				retainedSidecars(),
				Moc3ExportOptions(includePhysics = false, includeUserData = false),
			)
		// The pose sidecar has no toggle, so opting the other two out must never take it along.
		assertEquals(listOf("Model.pose3.json"), exported.map { sidecar -> sidecar.fileName })
	}
}
