package org.umamo.ui.document

import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.render.PuppetTextures
import org.umamo.render.encodeAtlasPng
import org.umamo.runtime.model.PuppetModel

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
 * A CMO3-origin document reconciles onto the graph it retained from import, which is what keeps its
 * real layered art and its byte-identity gates intact.  A MOC3-origin document has no retained graph,
 * so a fresh one is synthesized from the blank skeleton plus the retained atlas pages and then
 * reconciled onto in exactly the same way.
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
 * @param Long           nowMillis    The timestamp a synthesized image chain records.
 * @param Int            obfuscateKey The container XOR key; the editor mints one per save.
 * @return PreparedCmo3Export The model to serialize plus its report.
 */
fun prepareCmo3Export(
	document: PuppetDocument,
	edited: PuppetModel,
	effectiveTextures: PuppetTextures,
	modelName: String,
	nowMillis: Long,
	obfuscateKey: Int,
): PreparedCmo3Export =
	when (document) {
		// A CMO3-origin document reconciles onto its retained graph.  The page patch is gated by
		// INSTANCE identity: the session's resolver republishes the document's own textures whenever
		// the atlas sits at its imported baseline (an unedited document, or a repack undone), so
		// passing pages here happens exactly when the session composed new ones - the strict gate the
		// byte-identity contract needs.
		is Cmo3Document -> {
			val recomposedPages =
				if (effectiveTextures !== document.textures) {
					effectiveTextures.atlases.map { page ->
						Cmo3Conversion.AtlasPage(encodeAtlasPng(page), page.width, page.height)
					}
				} else {
					emptyList()
				}
			PreparedCmo3Export(document.cmo3, Cmo3Export.apply(edited, document.cmo3, recomposedPages = recomposedPages))
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
				)
			PreparedCmo3Export(result.model, result.report)
		}
	}

/**
 * The atlas pages a fresh-graph synthesis builds its image chain from.
 *
 * Walks the DECODED set, because that is what the drawables' page indices were resolved against - the
 * retained source bytes are only the preferred payload for each of those pages, not the page list
 * itself.  A page the document has no source bytes for is re-encoded rather than skipped: dropping one
 * would renumber every later page out from under the drawables that reference it.
 *
 * @param Moc3Document document The MOC3-origin document being converted.
 * @return List The pages, in decoded page order.
 */
private fun conversionPagesFor(document: Moc3Document): List<Cmo3Conversion.AtlasPage> =
	document.textures.atlases.mapIndexed { pageIndex, decoded ->
		Cmo3Conversion.AtlasPage(
			pngBytes = document.atlasPages.getOrNull(pageIndex) ?: encodeAtlasPng(decoded),
			width = decoded.width,
			height = decoded.height,
		)
	}