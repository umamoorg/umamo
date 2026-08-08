package org.umamo.render

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract checks for [cmo3PuppetTextures] over hand-built graphs.  The walk takes a CModelSource
 * plus an injected pixel lookup rather than a Cmo3Model, which is exactly what lets this run without
 * a corpus `.cmo3` - and on every target, since the CMO3 node classes are commonMain.
 */
class Cmo3PuppetTexturesTest {
	private fun pngOfSize(width: Int, height: Int): ByteArray =
		PngCodec.write(RasterImage(width = width, height = height, rgba = ByteArray(width * height * 4) { -1 }))

	/**
	 * Builds an art mesh bound to [resource], mirroring the shape the CMO3 serializer produces.
	 *
	 * @param String          drawableId    The mesh's id string.
	 * @param CImageResource? resource      The page it samples, or null for a texture with no source.
	 * @param Boolean         premultiplied The page's GTexture2D.isPremultiplied flag.
	 * @return CArtMeshSource The assembled mesh.
	 */
	private fun artMesh(drawableId: String, resource: CImageResource?, premultiplied: Boolean = false): CArtMeshSource =
		CArtMeshSource().apply {
			id = Id("ArtMesh").apply { idstr = drawableId }
			texture =
				GTexture2D().apply {
					srcImageResource = resource
					isPremultiplied = premultiplied
				}
		}

	/**
	 * Wraps [meshes] in the drawable-source-set container a CModelSource holds them in.
	 *
	 * @param CArtMeshSource meshes The meshes to mount.
	 * @return CModelSource The assembled root.
	 */
	private fun modelSourceOf(vararg meshes: CArtMeshSource): CModelSource =
		CModelSource().apply {
			drawableSourceSet = CDrawableSourceSet().apply { _sources = meshes.toList() }
		}

	@Test
	fun sharedResourceDecodesOnceAndBothDrawablesPointAtIt() {
		val page = CImageResource()
		val root = modelSourceOf(artMesh("ArtMeshLeft", page), artMesh("ArtMeshRight", page))

		var reads = 0
		val textures =
			cmo3PuppetTextures(root) { requested ->
				reads++
				if (requested === page) pngOfSize(4, 4) else null
			}

		assertEquals(1, textures.atlases.size, "one distinct resource is one atlas page")
		assertEquals(4, textures.atlases[0].width)
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMeshLeft"])
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMeshRight"])
		assertEquals(1, reads, "the shared page's bytes are fetched once, not per drawable")
	}

	@Test
	fun distinctResourcesBecomeDistinctPages() {
		val firstPage = CImageResource()
		val secondPage = CImageResource()
		val root = modelSourceOf(artMesh("ArtMeshFirst", firstPage), artMesh("ArtMeshSecond", secondPage))

		val textures =
			cmo3PuppetTextures(root) { requested -> if (requested === firstPage) pngOfSize(2, 2) else pngOfSize(8, 8) }

		assertEquals(2, textures.atlases.size)
		assertEquals(2, textures.atlases[textures.atlasIndexByDrawableId.getValue("ArtMeshFirst")].width)
		assertEquals(8, textures.atlases[textures.atlasIndexByDrawableId.getValue("ArtMeshSecond")].width)
	}

	@Test
	fun aBrokenPageCostsOnlyItsOwnDrawables() {
		val goodPage = CImageResource()
		val absentPage = CImageResource()
		val undecodablePage = CImageResource()
		val root =
			modelSourceOf(
				artMesh("ArtMeshGood", goodPage),
				artMesh("ArtMeshAbsent", absentPage),
				artMesh("ArtMeshAbsentSibling", absentPage),
				artMesh("ArtMeshUndecodable", undecodablePage),
			)

		var reads = 0
		val textures =
			cmo3PuppetTextures(root) { requested ->
				reads++
				when {
					requested === goodPage -> pngOfSize(1, 1)
					requested === undecodablePage -> "not a png".encodeToByteArray()
					else -> null
				}
			}

		// A resource that yielded nothing is a remembered answer too, so its second drawable costs no
		// second lookup - three distinct resources, three reads.
		assertEquals(3, reads, "each distinct resource is resolved once, failures included")

		// The rig stays openable: a corrupt embedded resource must not cost the rigger the whole file.
		assertEquals(1, textures.atlases.size, "only the decodable page survives")
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMeshGood"])
		assertNull(textures.atlasIndexByDrawableId["ArtMeshAbsent"], "a resource with no bytes is skipped")
		assertNull(textures.atlasIndexByDrawableId["ArtMeshAbsentSibling"], "and so is everything else on it")
		assertNull(textures.atlasIndexByDrawableId["ArtMeshUndecodable"], "a resource with bad bytes is skipped")
	}

	@Test
	fun meshesWithoutAnIdOrATextureAreSkipped() {
		val page = CImageResource()
		val unnamed = artMesh("", page)
		val textureless = CArtMeshSource().apply { id = Id("ArtMesh").apply { idstr = "ArtMeshTextureless" } }
		val root = modelSourceOf(unnamed, textureless, artMesh("ArtMeshReal", page))

		val textures = cmo3PuppetTextures(root) { pngOfSize(1, 1) }

		assertEquals(mapOf("ArtMeshReal" to 0), textures.atlasIndexByDrawableId)
	}

	@Test
	fun premultipliedIsTheOrFoldAcrossTextures() {
		val page = CImageResource()
		val straight = modelSourceOf(artMesh("ArtMeshA", page, premultiplied = false))
		val mixed =
			modelSourceOf(
				artMesh("ArtMeshA", page, premultiplied = false),
				artMesh("ArtMeshB", page, premultiplied = true),
			)

		assertEquals(false, cmo3PuppetTextures(straight) { pngOfSize(1, 1) }.premultipliedAlpha)
		assertTrue(cmo3PuppetTextures(mixed) { pngOfSize(1, 1) }.premultipliedAlpha, "any premultiplied texture sets it")
	}

	@Test
	fun aRootWithNoDrawableSourceSetYieldsEmptyTextures() {
		val textures = cmo3PuppetTextures(CModelSource()) { pngOfSize(1, 1) }

		assertTrue(textures.atlases.isEmpty())
		assertTrue(textures.atlasIndexByDrawableId.isEmpty())
	}
}