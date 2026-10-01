package org.umamo.ui.document

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.modelPageIndexByDrawableId
import org.umamo.interop.cmo3.modelPageRenderIndices
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.deriveAtlasTextures
import org.umamo.render.encodeAtlasPng
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.thumbnails.DrawableThumbnailer

/*
 * Decides WHAT a CMO3 export writes; the app layer picks the destination and writes the bytes.
 *
 * The counterpart to Moc3ExportPolicy, and pure for the same reason: the origin branch below is the
 * difference between reconciling onto a retained graph and synthesizing a fresh one, which is the
 * single most consequential choice the export makes and so is the one that most needs a test.
 */

/**
 * Lowers [edited] into a CMO3 for [document], by whichever route the document's origin allows.
 *
 * A CMO3-origin document reconciles onto a fresh read of the graph it retained from import, which is
 * what keeps its real layered art and its byte-identity gates intact while the document's own graph is
 * never written.  A MOC3-origin or artwork-origin document has no retained graph, so a fresh one is
 * synthesized from the blank skeleton plus its atlas pages and then reconciled onto in exactly the same
 * way.  Either way the returned model is the export's own, and nothing else reads it.
 *
 * The clock and the container key are PARAMETERS, not calls: [Cmo3Conversion.freshCmo3] is
 * deterministic given them, and that determinism is only reachable if the seam extends to the app
 * boundary.  The caller is the one place that reads a wall clock or mints a key.
 *
 * @param PuppetDocument document     The document being exported.
 * @param PuppetModel    edited       The model to write; see [exportedModelFor].
 * @param PuppetTextures effectiveTextures The SESSION's page set; recomposed pages reach the
 *                                    archive through it, and the document's own instance means
 *                                    the archive is left untouched.
 * @param String         modelName    The display name a synthesized skeleton records.
 * @param Long           nowMillis    The timestamp a synthesized image chain, or a layer minted into a
 *                                    retained graph, records.
 * @param Int            obfuscateKey The container XOR key; the editor mints one per save.
 * @param RasterImage?   modelThumbnail The model's rest-pose thumbnail the file's model icons take,
 *                                    or null to leave them (blank on a fresh graph, the import's on a
 *                                    retained one).
 * @return PreparedCmo3Export The model to serialize plus its report.
 */
fun prepareCmo3Export(
	document: PuppetDocument,
	edited: PuppetModel,
	effectiveTextures: PuppetTextures,
	modelName: String,
	nowMillis: Long,
	obfuscateKey: Int,
	modelThumbnail: RasterImage? = null,
): PreparedCmo3Export =
	when (document) {
		// A CMO3-origin document reconciles onto a working copy of its retained graph, read fresh from the
		// archive's untouched main_xml entry.  The reconcile mutates its target pass by pass with no
		// rollback, so the document's own graph - which its rasters, a source read, and a UMA save keep
		// reading - is never the target: every export, a failed one included, leaves it as it was opened,
		// and each export diffs against the file as imported.  The page patch is gated by INSTANCE
		// identity: the session's resolver republishes the document's own textures whenever the atlas
		// sits at its imported baseline (an unedited document, or a repack undone), so passing pages here
		// happens exactly when the session composed new ones - the strict gate the byte-identity contract
		// needs.
		is Cmo3Document -> {
			val workingCopy = Cmo3.read(document.cmo3.archive)
			val recomposedPages =
				if (effectiveTextures !== document.textures) {
					effectiveTextures.atlases.map { page ->
						Cmo3Conversion.AtlasPage(encodeAtlasPng(page), page.width, page.height)
					}
				} else {
					emptyList()
				}
			// The document's own rasters ride along so a reloaded tile rewrites its retained layer and
			// an added tile mints one; the reconcile reads them only for tiles the graph must change, and
			// through the uncached decode, which is safe off the UI thread.
			val report =
				Cmo3Export.apply(
					edited,
					workingCopy,
					recomposedPages = recomposedPages,
					tileRasters = { tileId -> document.artRasters.decodeRaster(tileId)?.let { decoded -> RasterImage(decoded.width, decoded.height, decoded.rgba) } },
					nowMillis = nowMillis,
					modelThumbnail = modelThumbnail,
				)
			PreparedCmo3Export(workingCopy, report)
		}
		// An artwork-origin document has no retained graph either: the pages it packed at open, or the
		// session's repack of them, are re-encoded and synthesized into a fresh graph the same way,
		// with the document's own rasters handed over so every tile writes its real layer rather than
		// a slice of a page.  A new document that was never saved is the same case - whatever artwork
		// was brought into it is in its own store, and an untouched one synthesizes an empty rig.
		is ArtDocument, is BlankDocument -> {
			val result =
				Cmo3Conversion.freshCmo3(
					puppet = edited,
					pages = effectiveTextures.atlases.map { page -> Cmo3Conversion.AtlasPage(encodeAtlasPng(page), page.width, page.height, decoded = page.asRasterImage()) },
					pageIndexByDrawableId = effectiveTextures.atlasIndexByDrawableId,
					modelName = modelName,
					nowMillis = nowMillis,
					obfuscateKey = obfuscateKey,
					tileRasters = { tileId -> document.artRasters.decodeRaster(tileId)?.let { decoded -> RasterImage(decoded.width, decoded.height, decoded.rgba) } },
					modelThumbnail = modelThumbnail,
				)
			PreparedCmo3Export(result.model, result.report)
		}
		// A UMA document has no retained graph either (docs/plan/uma-format.md D34): a fresh graph is
		// synthesized as for an artwork document, with the file's stored render pages as the image chain while
		// the atlas is at the document's baseline (the same identity gate as the CMO3 branch), put into the
		// model's page order, and a re-encode of the effective pages otherwise.  The document's own rasters
		// ride along so every tile writes its real layer.
		is UmaDocument -> {
			val conversion = conversionPagesFor(document, edited, effectiveTextures)
			val result =
				Cmo3Conversion.freshCmo3(
					puppet = edited,
					pages = conversion.pages,
					pageIndexByDrawableId = conversion.pageIndexByDrawableId,
					modelName = modelName,
					nowMillis = nowMillis,
					obfuscateKey = obfuscateKey,
					tileRasters = { tileId -> document.artRasters.decodeRaster(tileId)?.let { decoded -> RasterImage(decoded.width, decoded.height, decoded.rgba) } },
					modelThumbnail = modelThumbnail,
				)
			PreparedCmo3Export(result.model, result.report)
		}
		// A MOC3-origin document has no retained graph: synthesize a fresh one from the blank skeleton
		// + the retained atlas pages, then reconcile onto it.
		is Moc3Document -> {
			val result =
				Cmo3Conversion.freshCmo3(
					puppet = edited,
					pages = conversionPagesFor(document),
					pageIndexByDrawableId = document.textures.atlasIndexByDrawableId,
					modelName = modelName,
					nowMillis = nowMillis,
					obfuscateKey = obfuscateKey,
					modelThumbnail = modelThumbnail,
				)
			PreparedCmo3Export(result.model, result.report)
		}
	}

/**
 * The encoded CMO3 an export writes, with the report of anything it could not represent.
 *
 * @property ByteArray    bytes  The whole `.cmo3` file.
 * @property ExportReport report The lowering's report.
 */
class RenderedCmo3Export(val bytes: ByteArray, val report: ExportReport)

/**
 * Renders [edited] into the bytes of a CMO3: the model icons' thumbnail, the lowering ([prepareCmo3Export]),
 * and the serialization, in one call that hands back only the bytes and the report.
 *
 * One call so that nothing else outlives it: the lowered model graph with every PNG entry it carries is
 * unreachable by the time the caller writes the bytes anywhere, rather than held across the write.  Reads only
 * immutable state - a [Cmo3Document]'s lowering included, since it reconciles onto a working copy read from the
 * document's archive and decodes the document's rasters uncached - so the caller may run it off the UI thread.
 *
 * @param PuppetDocument document          The document being exported.
 * @param PuppetModel    edited            The model to write; see [exportedModelFor].
 * @param PuppetTextures effectiveTextures The session's page set, which the thumbnail and the pages come from.
 * @param String         modelName         The display name a synthesized skeleton records.
 * @param Long           nowMillis         The timestamp a synthesized image chain records.
 * @param Int            obfuscateKey      The container XOR key.
 * @return RenderedCmo3Export The file's bytes and the report.
 */
fun renderCmo3Export(
	document: PuppetDocument,
	edited: PuppetModel,
	effectiveTextures: PuppetTextures,
	modelName: String,
	nowMillis: Long,
	obfuscateKey: Int,
): RenderedCmo3Export {
	// The model's own icons come from the outliner's rest-pose composite, over the same pages the export
	// writes - pure CPU, so the Android shell writes them too.
	val modelThumbnail = DrawableThumbnailer(edited, effectiveTextures).modelRasterFor()
	val prepared = prepareCmo3Export(document, edited, effectiveTextures, modelName, nowMillis, obfuscateKey, modelThumbnail)
	return RenderedCmo3Export(Cmo3.write(prepared.model), prepared.report)
}

/**
 * The atlas pages a fresh-graph synthesis builds its image chain from: the document's page PNGs
 * ([Moc3Document.pagePngs], which says why the decoded set is the list) with each decoded page's size and
 * pixels.
 *
 * The pixels come from the document's own page set, the one those PNGs were decoded into - never the
 * session's, which a repack of added artwork can replace while the PNGs stay the document's.
 *
 * @param Moc3Document document The MOC3-origin document being converted.
 * @return List The pages, in decoded page order.
 */
internal fun conversionPagesFor(document: Moc3Document): List<Cmo3Conversion.AtlasPage> =
	document.pagePngs().mapIndexed { pageIndex, pngBytes ->
		val decoded = document.textures.atlases[pageIndex]
		Cmo3Conversion.AtlasPage(pngBytes = pngBytes, width = decoded.width, height = decoded.height, decoded = decoded.asRasterImage())
	}

/**
 * The pages a fresh-graph synthesis builds its image chain from, and each drawable's page among them.
 *
 * @property List pages                 The pages, in the model's page order.
 * @property Map  pageIndexByDrawableId Each drawable id's page index into [pages].
 */
internal class ConversionPages(
	val pages: List<Cmo3Conversion.AtlasPage>,
	val pageIndexByDrawableId: Map<String, Int>,
)

/**
 * The pages a fresh-graph synthesis of a UMA document builds from.
 *
 * While the atlas is at the document's baseline and the file stores render pages, those are the pages - but
 * in the render numbering, where a CMO3 import lists every image a drawable samples in the order its loader
 * met them, an unpacked drawable's raster among them (docs/format/UMA.md §5.5).  Handed over as they are,
 * each raster would become an atlas of its own and a placed tile's entry would land on whatever page shares
 * its index.  So a document whose model has atlas pages takes them in the MODEL's order: each model page is
 * the render page its placed drawables sample ([modelPageRenderIndices]), else the page the tiles compose,
 * else a transparent page of the recorded size.  A document with no model pages (a MOC3's) keeps its render
 * pages, which are its texture order; a derived page set is in the model's order by construction.
 *
 * @param UmaDocument    document          The document being exported.
 * @param PuppetModel    edited            The model being written.
 * @param PuppetTextures effectiveTextures The session's page set.
 * @return ConversionPages The pages and the page map.
 */
internal fun conversionPagesFor(document: UmaDocument, edited: PuppetModel, effectiveTextures: PuppetTextures): ConversionPages {
	val stored = document.storedPages?.takeIf { pageSet -> effectiveTextures === document.textures && pageSet.pageBytes.size == effectiveTextures.atlases.size }
	val renderPages =
		effectiveTextures.atlases.mapIndexed { pageIndex, page ->
			Cmo3Conversion.AtlasPage(stored?.pageBytes?.get(pageIndex) ?: encodeAtlasPng(page), page.width, page.height, decoded = page.asRasterImage())
		}
	if (stored == null || edited.atlas.pages.isEmpty()) {
		return ConversionPages(renderPages, effectiveTextures.atlasIndexByDrawableId)
	}
	val renderIndices = modelPageRenderIndices(edited, effectiveTextures.atlasIndexByDrawableId)
	val derived by lazy { deriveAtlasTextures(edited, document.artRasters, effectiveTextures.premultipliedAlpha) }
	val pages =
		edited.atlas.pages.mapIndexed { modelPageIndex, modelPage ->
			renderIndices[modelPageIndex]?.let(renderPages::getOrNull)
				?: derived?.atlases?.getOrNull(modelPageIndex)?.let { page -> Cmo3Conversion.AtlasPage(encodeAtlasPng(page), page.width, page.height, decoded = page.asRasterImage()) }
				?: run {
					UmamoLog.warn("CMO3 export: no image shows atlas page ${modelPageIndex + 1}, so it is written transparent")
					val blank = RasterImage(modelPage.width, modelPage.height, ByteArray(modelPage.width * modelPage.height * 4))
					Cmo3Conversion.AtlasPage(PngCodec.write(blank), blank.width, blank.height, decoded = blank)
				}
		}
	return ConversionPages(pages, modelPageIndexByDrawableId(edited, renderIndices, effectiveTextures.atlasIndexByDrawableId))
}

/**
 * This decoded page as the raster the conversion reads, over the same pixel buffer rather than a copy.
 *
 * The viewport draws from that buffer and the conversion only reads it, so a copy would cost the export a
 * page's worth of memory for nothing.
 *
 * @return RasterImage The page's pixels.
 */
private fun DecodedImage.asRasterImage(): RasterImage = RasterImage(width, height, rgba)