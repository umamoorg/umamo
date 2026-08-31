package org.umamo.edit.export

import org.umamo.edit.withAtlasPlacement
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.RecomposedAtlasPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The recomposed-page export gate: a same-membership repack's pages replace the file's stored page
 * images (clearing the stale-page notice), a count mismatch or a membership change patches NOTHING
 * and keeps the honest notice, and a resize updates every dimension field including the cached-image
 * manager's padding diagonal - the field whose identity value is the documented squished-art symptom.
 */
class Cmo3ExportRepackTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private fun skipMessageOrNull(): File? {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping export repack test")
		}
		return file
	}

	/** A tiny valid PNG whose bytes are recognizably NOT any corpus page. */
	private fun markerPng(): ByteArray {
		val rgba = ByteArray(2 * 2 * 4) { pixelByte -> (pixelByte * 17 + 3).toByte() }
		return PngCodec.write(RasterImage(2, 2, rgba))
	}

	/** The page resources in _textureAtlases order - the model's own page numbering. */
	private fun pageResourcesOf(modelSource: CModelSource): List<Pair<CTextureAtlas, CImageResource>> {
		val textureManager = modelSource.textureManager as? CTextureManager ?: return emptyList()
		return elementsOf(textureManager._textureAtlases)
			.filterIsInstance<CTextureAtlas>()
			.mapNotNull { atlas -> (atlas.cachedAtlasImage as? CImageResource)?.let { resource -> atlas to resource } }
	}

	private fun elementsOf(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun hasStalePageNotice(report: ExportReport): Boolean =
		report.notices.any { notice ->
			notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.AtlasPageNotRecomposed
		}

	/** Opens the sample and applies a same-page placement nudge, or null when it packs nothing. */
	private fun nudgedSample(file: File): Triple<Cmo3Model, org.umamo.runtime.model.PuppetModel, Int>? {
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(modelSource)
		val packed = imported.atlas.tiles.firstOrNull { tile -> tile.placement != null }
		if (packed == null) {
			println("${file.name} packs no artwork; skipping the repack export gate")
			return null
		}
		val moved = packed.placement!!.copy(positionX = packed.placement!!.positionX + 8f)
		return Triple(cmo3, imported.withAtlasPlacement(packed.id, moved), imported.atlas.pages.size)
	}

	@Test
	fun aSameMembershipPatchClearsTheNoticeAndLandsInTheArchive() {
		val file = skipMessageOrNull() ?: return
		val (cmo3, edited, _) = nudgedSample(file) ?: return
		val modelSource = cmo3.root as CModelSource
		val resources = pageResourcesOf(modelSource)
		assertTrue(resources.isNotEmpty(), "the sample stores its pages")
		val marker = markerPng()
		// Same dimensions, so only the bytes move - the resize fields have their own gate below.
		val pages =
			resources.mapIndexed { pageIndex, (_, resource) ->
				RecomposedAtlasPage(pageIndex, marker, resource.width, resource.height)
			}

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		assertFalse(hasStalePageNotice(report), "patched pages are not stale: ${report.notices}")
		val rereadSource = Cmo3.read(Cmo3.write(cmo3)).root as? CModelSource ?: error("re-read root is not a CModelSource")
		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		for ((_, resource) in pageResourcesOf(rereadCmo3.root as CModelSource)) {
			assertContentEquals(marker, rereadCmo3.extractLayerPng(resource), "the archive serves the recomposed bytes by name")
		}
		assertNotNull(rereadSource, "the patched file still parses")
	}

	@Test
	fun aResizedPatchUpdatesEveryDimensionField() {
		val file = skipMessageOrNull() ?: return
		val (cmo3, edited, _) = nudgedSample(file) ?: return
		val modelSource = cmo3.root as CModelSource
		val resources = pageResourcesOf(modelSource)
		val marker = markerPng()
		val pages =
			resources.mapIndexed { pageIndex, (_, resource) ->
				RecomposedAtlasPage(pageIndex, marker, resource.width / 2, resource.height / 2)
			}
		val expectedDims = pages.map { page -> page.width to page.height }

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		assertFalse(hasStalePageNotice(report))
		val reread = pageResourcesOf(Cmo3.read(Cmo3.write(cmo3)).root as CModelSource)
		for ((pageIndex, pair) in reread.withIndex()) {
			val (atlas, resource) = pair
			val (expectedWidth, expectedHeight) = expectedDims[pageIndex]
			// CMO3: CImageResource fields width / height.
			assertEquals(expectedWidth, resource.width, "resource width follows the recomposed page")
			assertEquals(expectedHeight, resource.height)
			// CMO3: CTextureAtlas fields width / height.
			assertEquals(expectedWidth, atlas.width, "atlas width follows the recomposed page")
			assertEquals(expectedHeight, atlas.height)
			// CMO3: CCachedImage field transformRawImageToCachedImage - rawDim / ceil64(rawDim) per
			// axis; identity here is the documented squished-art symptom, so this is the mutation gate.
			val manager = assertNotNull(atlas.cachedImageManager as? CCachedImageManager)
			val cached = assertNotNull(elementsOf(manager.cachedImages).filterIsInstance<CCachedImage>().firstOrNull())
			val diagonal = assertNotNull(cached.transformRawImageToCachedImage as? CAffine)
			assertEquals(expectedWidth.toFloat() / ((expectedWidth + 63) / 64 * 64), diagonal.m00, 1e-6f)
			assertEquals(expectedHeight.toFloat() / ((expectedHeight + 63) / 64 * 64), diagonal.m11, 1e-6f)
		}
	}

	@Test
	fun aCountMismatchPatchesNothingAndKeepsTheNotice() {
		val file = skipMessageOrNull() ?: return
		val (cmo3, edited, _) = nudgedSample(file) ?: return
		val modelSource = cmo3.root as CModelSource
		val resources = pageResourcesOf(modelSource)
		val originalBytes = resources.map { (_, resource) -> assertNotNull(cmo3.extractLayerPng(resource)) }
		val marker = markerPng()
		// One page more than the file holds: the repack changed the page count, which the patch
		// cannot represent without minting atlases - so it must decline whole.
		val pages =
			(0..resources.size).map { pageIndex ->
				RecomposedAtlasPage(pageIndex, marker, 64, 64)
			}

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		assertTrue(hasStalePageNotice(report), "an unpatchable page set keeps the honest notice")
		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		for ((pageIndex, pair) in pageResourcesOf(rereadCmo3.root as CModelSource).withIndex()) {
			assertContentEquals(
				originalBytes[pageIndex],
				rereadCmo3.extractLayerPng(pair.second),
				"a declined patch leaves the stored pages byte-identical",
			)
		}
	}

	@Test
	fun aMembershipChangeBlocksThePatch() {
		val file = skipMessageOrNull() ?: return
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(modelSource)
		val packed = imported.atlas.tiles.firstOrNull { tile -> tile.placement != null }
		if (packed == null) {
			println("${file.name} packs no artwork; skipping the membership gate")
			return
		}
		val resources = pageResourcesOf(modelSource)
		val originalBytes = resources.map { (_, resource) -> assertNotNull(cmo3.extractLayerPng(resource)) }
		// Packing a tile OUT is a membership change: its entry would go stale on a recomposed page.
		val edited = imported.withAtlasPlacement(packed.id, null)
		val marker = markerPng()
		val pages =
			resources.mapIndexed { pageIndex, (_, resource) ->
				RecomposedAtlasPage(pageIndex, marker, resource.width, resource.height)
			}

		Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		for ((pageIndex, pair) in pageResourcesOf(rereadCmo3.root as CModelSource).withIndex()) {
			assertContentEquals(
				originalBytes[pageIndex],
				rereadCmo3.extractLayerPng(pair.second),
				"a membership change must not patch the pages",
			)
		}
	}
}