package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract checks for [cmo3AtlasPages] over hand-built graphs.  The walk takes a CModelSource plus an
 * injected pixel lookup rather than a Cmo3Model, which is exactly what lets this run without a corpus
 * `.cmo3` - and on every target, since the CMO3 node classes are commonMain.
 *
 * Only the WALK is covered here: which resources become pages and who samples them.  Whether a page's
 * bytes decode, and what a broken one costs, belongs to the renderer's shared builder and is covered by
 * PuppetTexturesTest - this walk never decodes anything.
 */
class Cmo3AtlasPagesTest {
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
	fun sharedResourceIsFetchedOnceAndBothDrawablesPointAtIt() {
		val page = CImageResource()
		val root = modelSourceOf(artMesh("ArtMeshLeft", page), artMesh("ArtMeshRight", page))

		var reads = 0
		val pageSet =
			cmo3AtlasPages(root) { requested ->
				reads++
				if (requested === page) byteArrayOf(1, 2, 3) else null
			}

		assertEquals(1, pageSet.pageBytes.size, "one distinct resource is one atlas page")
		assertEquals(0, pageSet.atlasIndexByDrawableId["ArtMeshLeft"])
		assertEquals(0, pageSet.atlasIndexByDrawableId["ArtMeshRight"])
		assertEquals(1, reads, "the shared page's bytes are fetched once, not per drawable")
	}

	@Test
	fun distinctResourcesBecomeDistinctPagesInEncounterOrder() {
		val firstPage = CImageResource()
		val secondPage = CImageResource()
		val root = modelSourceOf(artMesh("ArtMeshFirst", firstPage), artMesh("ArtMeshSecond", secondPage))

		val pageSet =
			cmo3AtlasPages(root) { requested -> if (requested === firstPage) byteArrayOf(1) else byteArrayOf(2, 2) }

		assertEquals(2, pageSet.pageBytes.size)
		assertEquals(0, pageSet.atlasIndexByDrawableId["ArtMeshFirst"])
		assertEquals(1, pageSet.atlasIndexByDrawableId["ArtMeshSecond"])
		assertEquals(1, pageSet.pageBytes[0].size, "page order follows drawable encounter order")
		assertEquals(2, pageSet.pageBytes[1].size)
	}

	@Test
	fun aResourceCarryingNoBytesLeavesItsDrawablesUnbound() {
		val goodPage = CImageResource()
		val absentPage = CImageResource()
		val root =
			modelSourceOf(
				artMesh("ArtMeshGood", goodPage),
				artMesh("ArtMeshAbsent", absentPage),
				artMesh("ArtMeshAbsentSibling", absentPage),
			)

		var reads = 0
		val pageSet =
			cmo3AtlasPages(root) { requested ->
				reads++
				if (requested === goodPage) byteArrayOf(1) else null
			}

		// A resource that yielded nothing is a remembered answer too, so its second drawable costs no
		// second lookup - two distinct resources, two reads.
		assertEquals(2, reads, "each distinct resource is resolved once, failures included")
		assertEquals(1, pageSet.pageBytes.size, "only the resource with bytes becomes a page")
		assertEquals(0, pageSet.atlasIndexByDrawableId["ArtMeshGood"])
		assertNull(pageSet.atlasIndexByDrawableId["ArtMeshAbsent"], "a resource with no bytes is skipped")
		assertNull(pageSet.atlasIndexByDrawableId["ArtMeshAbsentSibling"], "and so is everything else on it")
	}

	@Test
	fun meshesWithoutAnIdOrATextureAreSkipped() {
		val page = CImageResource()
		val unnamed = artMesh("", page)
		val textureless = CArtMeshSource().apply { id = Id("ArtMesh").apply { idstr = "ArtMeshTextureless" } }
		val root = modelSourceOf(unnamed, textureless, artMesh("ArtMeshReal", page))

		val pageSet = cmo3AtlasPages(root) { byteArrayOf(1) }

		assertEquals(mapOf("ArtMeshReal" to 0), pageSet.atlasIndexByDrawableId)
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

		assertEquals(false, cmo3AtlasPages(straight) { byteArrayOf(1) }.premultipliedAlpha)
		assertTrue(cmo3AtlasPages(mixed) { byteArrayOf(1) }.premultipliedAlpha, "any premultiplied texture sets it")
	}

	@Test
	fun aRootWithNoDrawableSourceSetYieldsNoPages() {
		val pageSet = cmo3AtlasPages(CModelSource()) { byteArrayOf(1) }

		assertTrue(pageSet.pageBytes.isEmpty())
		assertTrue(pageSet.atlasIndexByDrawableId.isEmpty())
	}
}
