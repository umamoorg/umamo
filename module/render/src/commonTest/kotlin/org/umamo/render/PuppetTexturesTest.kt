package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Contract checks for [buildPuppetTextures], the format-agnostic core both ingest paths end at, and
 * for the two opposite [UndecodablePagePolicy] answers layered on it: MOC3 fails the whole build so a
 * broken family surfaces as an import error, CMO3 drops the page and keeps the rest of the rig
 * openable.  Synthetic pixels only - no corpus dependency, so this runs on every target.
 */
class PuppetTexturesTest {
	private fun pngOfSize(width: Int, height: Int): ByteArray =
		PngCodec.write(RasterImage(width = width, height = height, rgba = ByteArray(width * height * 4) { -1 }))

	private val onePixelPng: ByteArray = pngOfSize(1, 1)
	private val notAPng: ByteArray = "not a png".encodeToByteArray()

	@Test
	fun decodesPagesAndKeepsTheIndexMap() {
		val textures = buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to 0))
		assertNotNull(textures)
		assertEquals(1, textures.atlases.size, "decoded page count")
		assertEquals(1, textures.atlases[0].width, "page width")
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMesh1"], "page index for the drawable")
	}

	@Test
	fun undecodablePageFailsTheBuild() {
		assertNull(buildPuppetTextures(listOf(notAPng), mapOf("ArtMesh1" to 0)))
	}

	@Test
	fun outOfRangePageIndexFailsTheBuild() {
		assertNull(buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to 1)), "index past the page list")
		assertNull(buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to -1)), "negative index")
	}

	@Test
	fun skipPolicyDropsOnlyTheDrawablesOnTheBrokenPage() {
		val textures =
			buildPuppetTextures(
				listOf(pngOfSize(1, 1), notAPng, pngOfSize(2, 2)),
				mapOf("ArtMeshFirst" to 0, "ArtMeshBroken" to 1, "ArtMeshLast" to 2),
				undecodablePage = UndecodablePagePolicy.Skip,
			)
		assertNotNull(textures, "Skip never fails the build")
		assertEquals(2, textures.atlases.size, "the undecodable page is dropped")
		assertNull(textures.atlasIndexByDrawableId["ArtMeshBroken"], "its drawable is unmapped, not left dangling")
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMeshFirst"], "an earlier page keeps its slot")
		// The renumbering is the whole point: page 2 became page 1 when page 1 went away, and a stale 2
		// here would index past the list and crash PuppetRenderer's direct lookup at first frame.
		assertEquals(1, textures.atlasIndexByDrawableId["ArtMeshLast"], "a later page is renumbered down")
		assertEquals(2, textures.atlases[1].width, "the renumbered slot holds the page it claims to")
	}

	@Test
	fun skipPolicyToleratesAnOutOfRangeIndex() {
		val textures =
			buildPuppetTextures(
				listOf(onePixelPng),
				mapOf("ArtMeshOk" to 0, "ArtMeshStale" to 7),
				undecodablePage = UndecodablePagePolicy.Skip,
			)
		assertNotNull(textures)
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMeshOk"])
		assertNull(textures.atlasIndexByDrawableId["ArtMeshStale"], "an unresolvable page number drops the drawable")
	}

	@Test
	fun premultipliedFlagIsCarriedThrough() {
		val textures = buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to 0), premultipliedAlpha = true)
		assertNotNull(textures)
		assertEquals(true, textures.premultipliedAlpha)
	}
}