package org.umamo.edit.export

import org.umamo.edit.withAtlasPlacement
import org.umamo.edit.withAtlasRepack
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The recomposed-page export gate over the atlas-web reconcile: a repack's pages replace the file's
 * stored page images (clearing the stale-page notice), a resize updates every dimension field
 * including the cached-image manager's padding diagonal, a page-count growth mints an atlas and
 * re-homes the moved entries with their drawables retargeted, an unbound pack-out removes its
 * entry - while a page inventory disagreeing with the edited model, or a pack-out a drawable still
 * binds, declines the WHOLE reconcile and keeps the honest notices.
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
			resources.map { (_, resource) ->
				Cmo3Conversion.AtlasPage(marker, resource.width, resource.height)
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
			resources.map { (_, resource) ->
				Cmo3Conversion.AtlasPage(marker, resource.width / 2, resource.height / 2)
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
	fun pagesDisagreeingWithTheEditedModelsPageCountDeclineWhole() {
		val file = skipMessageOrNull() ?: return
		val (cmo3, edited, _) = nudgedSample(file) ?: return
		val modelSource = cmo3.root as CModelSource
		val resources = pageResourcesOf(modelSource)
		val originalBytes = resources.map { (_, resource) -> assertNotNull(cmo3.extractLayerPng(resource)) }
		val marker = markerPng()
		// One page more than the EDITED model tracks: the pages are the repack's own output, so a
		// count disagreeing with the model is caller inconsistency and declines whole.
		val pages =
			List(resources.size + 1) { Cmo3Conversion.AtlasPage(marker, 64, 64) }

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

	/** One tile's entry inside [atlas], or null. */
	private fun entryIn(atlas: CTextureAtlas, tileId: String): ModelImageEntry? =
		elementsOf(atlas.modelImages)
			.filterIsInstance<ModelImageEntry>()
			.firstOrNull { entry -> (entry.modelImageGuid as? Guid)?.uuid == tileId }

	/** The drawable source with [drawableId], or null. */
	private fun drawableSourceOf(modelSource: CModelSource, drawableId: String): CArtMeshSource? =
		elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
			.filterIsInstance<CArtMeshSource>()
			.firstOrNull { source -> (source.id as? Id)?.idstr == drawableId }

	@Test
	fun aPageCountGrowthMintsAnAtlasAndRehomesMovedEntries() {
		val file = skipMessageOrNull() ?: return
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(modelSource)
		val boundTileIds = imported.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
		val movers = imported.atlas.tiles.filter { tile -> tile.placement != null && tile.id in boundTileIds }.take(2)
		if (movers.size < 2) {
			println("${file.name} packs too little bound artwork; skipping the growth gate")
			return
		}
		val newPages = imported.atlas.pages + AtlasPage(512, 512)
		val mintedPageIndex = newPages.size - 1
		val placements =
			imported.atlas.tiles.associateTo(HashMap()) { tile -> tile.id to tile.placement }
		placements[movers[0].id] = AtlasPlacement(mintedPageIndex, 16f, 16f, 1f, 1f, 0f)
		placements[movers[1].id] = AtlasPlacement(mintedPageIndex, 260f, 260f, 1f, 1f, 0f)
		val edited = imported.withAtlasRepack(newPages, placements)
		val marker = markerPng()
		val pages =
			newPages.map { page -> Cmo3Conversion.AtlasPage(marker, page.width, page.height) }

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		assertFalse(hasStalePageNotice(report), "a full reconcile owes no stale-page notice: ${report.notices}")
		assertFalse(
			report.notices.any { notice ->
				notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.NoAtlasEntryToReconcile
			},
			"every moved entry reconciled: ${report.notices}",
		)
		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		val rereadSource = rereadCmo3.root as CModelSource
		val atlases = pageResourcesOf(rereadSource).map { (atlas, _) -> atlas }
		assertEquals(newPages.size, atlases.size, "the page count grew")
		val minted = atlases.last()
		// CMO3: CTextureAtlas fields name / width / height / guid / cachedImageManager.
		assertEquals("TextureAtlas${newPages.size}", minted.name)
		assertEquals(512, minted.width)
		assertEquals(512, minted.height)
		assertNotNull(minted.guid, "the minted atlas carries a guid")
		val mintedResource = assertNotNull(minted.cachedAtlasImage as? CImageResource)
		assertContentEquals(marker, rereadCmo3.extractLayerPng(mintedResource), "the minted page PNG serves by name")
		val manager = assertNotNull(minted.cachedImageManager as? CCachedImageManager)
		val cached = assertNotNull(elementsOf(manager.cachedImages).filterIsInstance<CCachedImage>().firstOrNull())
		val diagonal = assertNotNull(cached.transformRawImageToCachedImage as? CAffine)
		assertEquals(1f, diagonal.m00, 1e-6f, "512 is 64-aligned, so the padding diagonal is one")

		for (mover in movers) {
			val entry = assertNotNull(entryIn(minted, mover.id.raw), "'${mover.name}' re-homed into the minted atlas")
			assertSame(minted, entry.atlas, "and carries the owning-page back-reference")
			val entryHalf = assertNotNull(entry.atlasLocalToCanvasTransform as? CAffine)
			for (drawable in edited.drawables.filter { candidate -> candidate.atlasTileId == mover.id }) {
				val source = assertNotNull(drawableSourceOf(rereadSource, drawable.id.raw))
				val texture = assertNotNull(source.texture as? GTexture2D, "the mover samples a texture")
				assertSame(
					mintedResource,
					texture.srcImageResource,
					"'${drawable.id.raw}' samples the minted page's own resource",
				)
				val extension =
					assertNotNull(elementsOf(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull())
				val region =
					assertNotNull(
						elementsOf(extension._textureInputs).filterIsInstance<CTextureInput_TextureAtlasRegion>().firstOrNull(),
					)
				assertEquals(
					(minted.guid as? Guid)?.uuid,
					(region.textureAtlasGuid as? Guid)?.uuid,
					"the region input names the minted page",
				)
				val input = assertNotNull(region.inputImageLocalToCanvasTransform as? CAffine)
				assertEquals(entryHalf.m00, input.m00, 1e-3f)
				assertEquals(entryHalf.m02, input.m02, 1e-1f)
				assertEquals(entryHalf.m11, input.m11, 1e-3f)
				assertEquals(entryHalf.m12, input.m12, 1e-1f)
			}
		}

		val reimported = Cmo3Import.fromModelSource(rereadSource)
		assertEquals(newPages.size, reimported.atlas.pages.size, "the reimport sees the grown page set")
		assertTrue(reimported.atlas.storedUvsAddressPages, "the document stays page-addressed")
		for (mover in movers) {
			assertEquals(
				placements[mover.id],
				reimported.atlas.tiles.first { tile -> tile.id == mover.id }.placement,
				"'${mover.name}' reimports at its new placement",
			)
		}
	}

	@Test
	fun aNeverPackedBoundTileGainsAnEntryAndARegionInput() {
		val file = skipMessageOrNull() ?: return
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(modelSource)
		val boundTileIds = imported.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
		val packIn = imported.atlas.tiles.firstOrNull { tile -> tile.placement == null && tile.id in boundTileIds }
		if (packIn == null) {
			println("${file.name} has no bound never-packed art; skipping the pack-in gate")
			return
		}
		val placements =
			imported.atlas.tiles.associateTo(HashMap()) { tile -> tile.id to tile.placement }
		placements[packIn.id] = AtlasPlacement(0, 100f, 100f, 1f, 1f, 0f)
		val edited = imported.withAtlasRepack(imported.atlas.pages, placements)
		val marker = markerPng()
		val pages =
			imported.atlas.pages.map { page ->
				Cmo3Conversion.AtlasPage(marker, page.width, page.height)
			}

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		assertFalse(hasStalePageNotice(report), "the pack-in reconciled: ${report.notices}")
		assertFalse(
			report.notices.any { notice ->
				notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.NoAtlasEntryToReconcile
			},
			"the minted entry serves the placement diff: ${report.notices}",
		)
		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		val rereadSource = rereadCmo3.root as CModelSource
		val atlasZero = pageResourcesOf(rereadSource).first().first
		val entry = assertNotNull(entryIn(atlasZero, packIn.id.raw), "'${packIn.name}' gained an entry on page 0")
		assertSame(atlasZero, entry.atlas, "with the owning-page back-reference")
		val pageResource = assertNotNull(atlasZero.cachedAtlasImage as? CImageResource)
		for (drawable in edited.drawables.filter { candidate -> candidate.atlasTileId == packIn.id }) {
			val source = assertNotNull(drawableSourceOf(rereadSource, drawable.id.raw))
			val texture = assertNotNull(source.texture as? GTexture2D)
			assertSame(pageResource, texture.srcImageResource, "'${drawable.id.raw}' now samples the page itself")
			val extension =
				assertNotNull(elementsOf(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull())
			val region =
				assertNotNull(
					elementsOf(extension._textureInputs).filterIsInstance<CTextureInput_TextureAtlasRegion>().firstOrNull(),
					"'${drawable.id.raw}' gained a region input",
				)
			assertEquals(
				((atlasZero.guid as? Guid))?.uuid,
				(region.textureAtlasGuid as? Guid)?.uuid,
				"the region names page 0",
			)
			assertSame(region, extension.currentTextureInputData, "the atlas-mode document samples the region")
		}
		val reimported = Cmo3Import.fromModelSource(rereadSource)
		assertEquals(
			placements[packIn.id],
			reimported.atlas.tiles.first { tile -> tile.id == packIn.id }.placement,
			"the reimport reads the minted placement",
		)
		val editedUvs = edited.drawables.first { drawable -> drawable.atlasTileId == packIn.id }.mesh!!.uvs
		val rereadUvs =
			reimported.drawables.first { drawable -> drawable.atlasTileId == packIn.id }.mesh!!.uvs
		assertEquals(editedUvs.size, rereadUvs.size)
		for (componentIndex in editedUvs.indices) {
			assertEquals(
				editedUvs[componentIndex],
				rereadUvs[componentIndex],
				1e-4f,
				"page-frame coordinates round-trip verbatim (component $componentIndex)",
			)
		}
	}

	@Test
	fun anUnboundTilePacksOutByRemovingItsEntry() {
		val file = skipMessageOrNull() ?: return
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(modelSource)
		val boundTileIds = imported.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
		val packedTiles = imported.atlas.tiles.filter { tile -> tile.placement != null && tile.id in boundTileIds }
		if (packedTiles.size < 2) {
			println("${file.name} packs too little bound artwork; skipping the pack-out gate")
			return
		}
		val packedOut = packedTiles[0]
		val rebindTarget = packedTiles[1]
		// Manufacture the unbound-placed shape the repack packs out: rebind the tile's drawables to a
		// sibling tile (the rebinding itself is not lowered and takes its own notice, which this gate
		// does not assert on), then pack the now-unbound tile out.
		val edited =
			imported
				.copy(
					drawables =
						imported.drawables.map { drawable ->
							if (drawable.atlasTileId == packedOut.id) drawable.copy(atlasTileId = rebindTarget.id) else drawable
						},
				).withAtlasPlacement(packedOut.id, null)
		val resources = pageResourcesOf(modelSource)
		val marker = markerPng()
		val pages =
			resources.map { (_, resource) ->
				Cmo3Conversion.AtlasPage(marker, resource.width, resource.height)
			}

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		assertFalse(hasStalePageNotice(report), "the pack-out reconciled: ${report.notices}")
		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		val rereadSource = rereadCmo3.root as CModelSource
		for (atlas in pageResourcesOf(rereadSource).map { (atlas, _) -> atlas }) {
			assertEquals(null, entryIn(atlas, packedOut.id.raw), "the packed-out entry left every page")
		}
		val reimported = Cmo3Import.fromModelSource(rereadSource)
		assertEquals(
			null,
			reimported.atlas.tiles.first { tile -> tile.id == packedOut.id }.placement,
			"the reimport reads the tile as unplaced",
		)
	}

	@Test
	fun aBoundTilePackedOutDeclinesWhole() {
		val file = skipMessageOrNull() ?: return
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(modelSource)
		val boundTileIds = imported.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
		val packed = imported.atlas.tiles.firstOrNull { tile -> tile.placement != null && tile.id in boundTileIds }
		if (packed == null) {
			println("${file.name} packs no bound artwork; skipping the bound pack-out gate")
			return
		}
		val resources = pageResourcesOf(modelSource)
		val originalBytes = resources.map { (_, resource) -> assertNotNull(cmo3.extractLayerPng(resource)) }
		// Packing a BOUND tile out would strand its drawables' page sampling - the reconcile must
		// decline whole rather than remove the entry from under them.
		val edited = imported.withAtlasPlacement(packed.id, null)
		val marker = markerPng()
		val pages =
			resources.map { (_, resource) ->
				Cmo3Conversion.AtlasPage(marker, resource.width, resource.height)
			}

		val report = Cmo3Export.apply(edited, cmo3, recomposedPages = pages)

		// The declined reconcile leaves the null placement to the ordinary lowering, whose honest
		// notice for a placement without an entry to write is NoAtlasEntryToReconcile.
		assertTrue(
			report.notices.any { notice ->
				notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.NoAtlasEntryToReconcile
			},
			"a declined reconcile keeps the honest notice: ${report.notices}",
		)
		val rereadCmo3 = Cmo3.read(Cmo3.write(cmo3))
		for ((pageIndex, pair) in pageResourcesOf(rereadCmo3.root as CModelSource).withIndex()) {
			assertContentEquals(
				originalBytes[pageIndex],
				rereadCmo3.extractLayerPng(pair.second),
				"a bound pack-out must not patch the pages",
			)
		}
	}
}