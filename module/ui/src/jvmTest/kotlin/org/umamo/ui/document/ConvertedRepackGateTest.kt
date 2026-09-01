package org.umamo.ui.document

import org.umamo.edit.withAtlasRepack
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.packAtlas
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.png.PngCodec
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.atlasPlacementFromPack
import org.umamo.render.generatedAtlasIndexByDrawableId
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasPixelOf
import org.umamo.ui.model.buildRepackPackInput
import org.umamo.ui.model.repackRefusals
import org.umamo.ui.viewport.initialLiveParams
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The converted-repack export gate, end to end on the modelG corpus fixture: a MOC3-origin
 * conversion with five pages repacks (consolidating pages, moving most tiles across them) and
 * exports through the SHIPPED policy; the re-read file must be exactly as consistent as the
 * original import.
 *
 * This is the modelG bug's regression net: before the atlas-web reconcile, this pipeline wrote new
 * placements and coordinates against old page membership and old images, and the reimport showed a
 * quarter of the drawables displaced.  The saved artifact recording that state lives OUTSIDE the
 * golden glob (test/corpus/cmo3/invalid/), because it fails the corpus invariants by nature.
 *
 * Skips without the fixture.
 */
class ConvertedRepackGateTest {
	private val sample: File? = System.getProperty("cmo3.repackSample")?.let(::File)?.takeIf { it.isFile }

	private class JumbleStats(
		val displacedDrawables: Int,
		val checkedDrawables: Int,
	)

	/** How many bound drawables' uv bboxes miss their own tile's trim rect on the page. */
	private fun jumbleStats(model: PuppetModel, decodeRaster: (AtlasTileId) -> DecodedImage?): JumbleStats {
		val tileById = model.atlas.tiles.associateBy { tile -> tile.id }
		val trimByTile = HashMap<AtlasTileId, FloatArray?>()
		var displaced = 0
		var checked = 0
		for (drawable in model.drawables) {
			val tileId = drawable.atlasTileId ?: continue
			val tile = tileById[tileId] ?: continue
			val placement = tile.placement ?: continue
			val page = model.atlas.pages.getOrNull(placement.pageIndex) ?: continue
			val uvs = drawable.mesh?.uvs ?: continue
			if (uvs.size < 2) {
				continue
			}
			val trim =
				trimByTile.getOrPut(tileId) {
					decodeRaster(tileId)?.let { raster ->
						analyzeAlpha(raster.width, raster.height, raster.rgba)?.opaqueBounds?.let { bounds ->
							floatArrayOf(
								placement.positionX + bounds.left,
								placement.positionY + bounds.top,
								placement.positionX + bounds.left + bounds.width,
								placement.positionY + bounds.top + bounds.height,
							)
						}
					}
				} ?: continue
			var minU = Float.MAX_VALUE
			var maxU = -Float.MAX_VALUE
			var minV = Float.MAX_VALUE
			var maxV = -Float.MAX_VALUE
			var componentIndex = 0
			while (componentIndex + 1 < uvs.size) {
				minU = min(minU, uvs[componentIndex])
				maxU = max(maxU, uvs[componentIndex])
				minV = min(minV, uvs[componentIndex + 1])
				maxV = max(maxV, uvs[componentIndex + 1])
				componentIndex += 2
			}
			checked++
			val left = minU * page.width
			val top = minV * page.height
			val right = maxU * page.width
			val bottom = maxV * page.height
			val intersectWidth = min(right, trim[2]) - max(left, trim[0])
			val intersectHeight = min(bottom, trim[3]) - max(top, trim[1])
			val bboxArea = max(1f, (right - left) * (bottom - top))
			val overlap =
				if (intersectWidth > 0f && intersectHeight > 0f) intersectWidth * intersectHeight / bboxArea else 0f
			if (overlap < 0.25f) {
				displaced++
			}
		}
		return JumbleStats(displaced, checked)
	}

	/** Flattens a CMO3 collection field, held as `Any?` by the serializer. */
	private fun elementsOf(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun decodeRasterOf(cmo3: Cmo3Model): (AtlasTileId) -> DecodedImage? {
		val ingest = cmo3AtlasIngest(cmo3.root as CModelSource)
		return { tileId ->
			ingest.imageResourceByTile[tileId]?.let(cmo3::extractLayerPng)?.let { bytes ->
				val image = PngCodec.read(bytes)
				DecodedImage(image.rgba, image.width, image.height)
			}
		}
	}

	@Test
	fun aConvertedRepackExportRoundTripsConsistently() {
		val file = sample
		if (file == null) {
			println("cmo3.repackSample not present; skipping the converted-repack export gate")
			return
		}
		val cmo3 = Cmo3.read(file.readBytes())
		val imported = Cmo3Import.fromModelSource(cmo3.root as CModelSource)
		assertTrue(imported.atlas.storedUvsAddressPages, "the fixture is page-addressed")
		val baselineDecode = decodeRasterOf(cmo3)
		val baselineJumble = jumbleStats(imported, baselineDecode)

		// The SHIPPED repack input, packed at the document's own page size.
		val packInput = buildRepackPackInput(imported) { tileId -> baselineDecode(tileId) }
		val options =
			AtlasPackOptions(maxPageSize = imported.atlas.pages.maxOf { page -> max(page.width, page.height) })
		val packResult = packAtlas(packInput.items, options)
		assertTrue(
			repackRefusals(imported, packResult.skipped, packInput.undecodableTileIds).isEmpty(),
			"the fixture must repack cleanly",
		)
		val packedByKey = packResult.placements.associateBy { placement -> placement.key }
		val placements = HashMap<AtlasTileId, AtlasPlacement?>()
		for (tile in imported.atlas.tiles) {
			placements[tile.id] = packedByKey[tile.id.raw]?.let { packed -> atlasPlacementFromPack(packed) }
		}
		val repacked =
			imported.withAtlasRepack(packResult.pages.map { page -> AtlasPage(page.width, page.height) }, placements)
		assertTrue(
			repacked.atlas.pages.size < imported.atlas.pages.size,
			"the fixture's repack consolidates pages (the page-delete arm)",
		)

		// The SHIPPED export policy: the effective page set is the repack's own.
		val effective =
			PuppetTextures(
				packResult.pages.map { page -> DecodedImage(page.rgba, page.width, page.height) },
				generatedAtlasIndexByDrawableId(repacked),
				premultipliedAlpha = false,
			)
		val probeDocument =
			Cmo3Document(
				path = file.path,
				cmo3 = cmo3,
				puppet = imported,
				textures = PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false),
				artRasters = org.umamo.render.SourceArtRasters { null },
				liveParams = initialLiveParams(imported),
			)
		val prepared = prepareCmo3Export(probeDocument, repacked, effective, "modelG", 0L, 0x5A17E5)
		assertFalse(
			prepared.report.notices.any { notice ->
				notice is ExportNotice.UnsupportedChange &&
					(
						notice.reason == ExportNoticeReason.AtlasPageNotRecomposed ||
							notice.reason == ExportNoticeReason.NoAtlasEntryToReconcile
					)
			},
			"the full reconcile owes no atlas notice: ${prepared.report.notices}",
		)
		val exportedBytes = Cmo3.write(prepared.model)
		File("build/modelG-repacked-export.cmo3").apply { parentFile?.mkdirs() }.writeBytes(exportedBytes)
		val rereadCmo3 = Cmo3.read(exportedBytes)
		val rereadSource = rereadCmo3.root as CModelSource
		val reimported = Cmo3Import.fromModelSource(rereadSource)

		// The reimport reads the repack's own page set and every placement, and stays page-addressed.
		assertEquals(repacked.atlas.pages, reimported.atlas.pages, "the page set follows the repack")
		assertTrue(reimported.atlas.storedUvsAddressPages)
		var placementMismatches = 0
		for (tile in repacked.atlas.tiles) {
			val reread = reimported.atlas.tiles.firstOrNull { candidate -> candidate.id == tile.id }
			if (reread?.placement != tile.placement) {
				placementMismatches++
			}
		}
		assertEquals(0, placementMismatches, "every placement round-trips")

		// The jumble metric is back at the baseline: the export is exactly as consistent as the import.
		val rereadJumble = jumbleStats(reimported, decodeRasterOf(rereadCmo3))
		assertEquals(baselineJumble.checkedDrawables, rereadJumble.checkedDrawables, "every mapping still checks")
		assertEquals(
			baselineJumble.displacedDrawables,
			rereadJumble.displacedDrawables,
			"the reimport shows no displacement beyond the import's own baseline",
		)

		// The composition invariant holds for EVERY re-read entry, non-pure canvas affines included.
		// CMO3: CModelSource field textureManager -> CTextureManager fields _textureAtlases /
		// _modelImageGroups.
		val textureManager = rereadSource.textureManager as CTextureManager
		val canvasByGuid = HashMap<String, CAffine>()
		for (group in elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()) {
			for (modelImage in elementsOf(group._modelImages).filterIsInstance<CModelImage>()) {
				val uuid = (modelImage.guid as? Guid)?.uuid ?: continue
				(modelImage._materialLocalToCanvasTransform as? CAffine)?.let { canvas -> canvasByGuid[uuid] = canvas }
			}
		}
		val atlases = elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()
		val placementByTileId =
			reimported.atlas.tiles.associateBy({ tile -> tile.id.raw }, { tile -> tile.placement })
		var compositionChecks = 0
		for (atlas in atlases) {
			for (entry in elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
				val uuid = (entry.modelImageGuid as? Guid)?.uuid ?: continue
				val placement = placementByTileId[uuid] ?: continue
				val canvas = canvasByGuid[uuid] ?: continue
				val entryHalf = entry.atlasLocalToCanvasTransform as? CAffine ?: continue
				for ((probeX, probeY) in listOf(0f to 0f, 137f to 0f, 0f to 251f)) {
					val onPage = atlasPixelOf(placement, probeX, probeY)
					val composedX = entryHalf.m00 * onPage[0] + entryHalf.m01 * onPage[1] + entryHalf.m02
					val composedY = entryHalf.m10 * onPage[0] + entryHalf.m11 * onPage[1] + entryHalf.m12
					val declaredX = canvas.m00 * probeX + canvas.m01 * probeY + canvas.m02
					val declaredY = canvas.m10 * probeX + canvas.m11 * probeY + canvas.m12
					assertTrue(
						abs(composedX - declaredX) < 1e-1f && abs(composedY - declaredY) < 1e-1f,
						"entry $uuid: composition ($composedX, $composedY) vs canvas ($declaredX, $declaredY)" +
							" at ($probeX, $probeY)",
					)
				}
				compositionChecks++
			}
		}
		assertTrue(compositionChecks > 100, "the composition invariant actually ran ($compositionChecks entries)")

		// Drawable page-consistency, zero disagreements: the sampled resource, the region's atlas
		// guid, and the entry's membership all name the same page.
		val atlasIndexByGuidUuid = HashMap<String, Int>()
		val atlasIndexByResource = java.util.IdentityHashMap<Any, Int>()
		val atlasIndexByEntryGuid = HashMap<String, Int>()
		for ((atlasIndex, atlas) in atlases.withIndex()) {
			(atlas.guid as? Guid)?.uuid?.let { uuid -> atlasIndexByGuidUuid[uuid] = atlasIndex }
			atlas.cachedAtlasImage?.let { resource -> atlasIndexByResource[resource] = atlasIndex }
			for (entry in elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
				(entry.modelImageGuid as? Guid)?.uuid?.let { uuid -> atlasIndexByEntryGuid[uuid] = atlasIndex }
			}
		}
		var consistencyChecks = 0
		var disagreements = 0
		val artMeshes =
			elementsOf((rereadSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
				.filterIsInstance<CArtMeshSource>()
		for (mesh in artMeshes) {
			val extension =
				elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
			val region =
				elementsOf(extension._textureInputs)
					.filterIsInstance<CTextureInput_TextureAtlasRegion>()
					.firstOrNull() ?: continue
			val tileUuid =
				(
					elementsOf(extension._textureInputs)
						.filterIsInstance<CTextureInput_ModelImage>()
						.firstOrNull()
						?._modelImageGuid as? Guid
				)?.uuid ?: continue
			val entryPage = atlasIndexByEntryGuid[tileUuid] ?: continue
			val guidPage = (region.textureAtlasGuid as? Guid)?.uuid?.let(atlasIndexByGuidUuid::get)
			val resourcePage = (mesh.texture as? GTexture2D)?.srcImageResource?.let(atlasIndexByResource::get)
			consistencyChecks++
			if (guidPage != entryPage || resourcePage != entryPage) {
				disagreements++
				if (disagreements <= 5) {
					println(
						"[repack-gate] '${(mesh.id as? Id)?.idstr}' pages disagree:" +
							" entry=$entryPage guid=$guidPage resource=$resourcePage",
					)
				}
			}
		}
		println(
			"[repack-gate] ${reimported.atlas.pages.size} page(s), jumble ${rereadJumble.displacedDrawables}/" +
				"${rereadJumble.checkedDrawables} (baseline ${baselineJumble.displacedDrawables}), " +
				"$compositionChecks compositions, $consistencyChecks drawables page-checked",
		)
		assertTrue(consistencyChecks > 100, "the page-consistency walk actually ran")
		assertEquals(0, disagreements, "every drawable's three page references agree")
	}
}