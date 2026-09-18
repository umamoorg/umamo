package org.umamo.interop.uma

import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.format.uma.textures.UmaTextures
import org.umamo.interop.AtlasPageSet
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel

/**
 * What a reopened document's renderer needs beside its model (docs/format/UMA.md §5.5): the page images its drawables
 * sample, or null when the pages derive from the tiles, and every tile's PNG for the document's pixel store.
 *
 * @property AtlasPageSet? pageSet The imported page images with each drawable's image, or null when the loader
 *   composes the pages from the tiles (`deriveAtlasTextures`).
 * @property Function      tilePng Yields a tile's PNG by tile id, or null for a tile the document does not hold.
 */
class UmaDocumentPages(
	val pageSet: AtlasPageSet?,
	val tilePng: (AtlasTileId) -> ByteArray?,
)

/**
 * The whole-document bridge: a [PuppetModel] with its atlas and linked source art saved into a UMA document's
 * puppet, textures, and sources entries, and read back out.
 */
object UmaDocumentBridge {
	/**
	 * [base] with its puppet, textures, and sources entries set from [model], each laid over the entry as read so
	 * every key this writer does not own survives.
	 *
	 * An entry too new for this reader occupies its kind and is carried byte for byte (UMA §3.3).  Only an optional
	 * one leaves the document editable, so a save skips its kind while [model] has nothing to write there - no linked
	 * source art for the sources entry; no atlas and no stored render pages for the textures entry, whose thumbnail
	 * goes unwritten with it - and fails once it does.
	 *
	 * @param UmaModel       base   The document to save over: the one the model was opened from, or a new one.
	 * @param PuppetModel    model  The model.
	 * @param UmaPixelSource pixels The pixels the save writes: the tiles' PNGs, what the drawables sample, and the
	 *   thumbnail.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the model holds a value the format cannot represent, a pixel entry is missing,
	 *   or the model has content for a kind whose entry is too new to replace.
	 */
	fun documentOf(base: UmaModel, model: PuppetModel, pixels: UmaPixelSource): UmaModel {
		var document = base.withPuppet(UmaPuppetExport.puppetOf(model))
		val textures = UmaTexturesBridge.texturesOf(model)
		val texturesHaveContent = textures != UmaTextures() || pixels.renderPages is UmaRenderPagePixels.Stored
		if (!base.holdsTooNewEntry(UmaEntryKind.Textures) || texturesHaveContent) {
			document = document.withTextures(textures, pixels)
		}
		if (!base.holdsTooNewEntry(UmaEntryKind.Sources) || model.sources.isNotEmpty()) {
			document = document.withSources(UmaSourcesBridge.sourcesOf(model))
		}
		return document
	}

	/**
	 * The model [document] describes: the puppet with its atlas and linked source art.
	 *
	 * The editor entry is not read here, and that is the whole of Goal 6: a file with `editor/` stripped loads the
	 * same model because nothing in the model ever came from it (docs/format/UMA.md § 7, D29).
	 *
	 * @param UmaModel document The document.
	 * @return PuppetModel The model.
	 * @throws UmaFormatException When the document has no puppet entry this reader can read.
	 */
	fun modelOf(document: UmaModel): PuppetModel {
		val puppet = document.puppet ?: throw UmaFormatException(UmaReadFailure.MissingEntry(UmaEntryKind.Puppet.defaultPath))
		return UmaPuppetImport.modelOf(puppet).copy(atlas = UmaTexturesBridge.atlasOf(document.textures), sources = UmaSourcesBridge.sourcesOf(document.sources))
	}

	/**
	 * The page images a reopened [document]'s drawables sample, and its tiles' PNGs (UMA §5.5): the stored render
	 * pages with their map when the document was saved with its imported pages, or no page set when its pages derive
	 * from the tiles.
	 *
	 * @param UmaModel document The document.
	 * @return UmaDocumentPages The page set and the tile PNGs.
	 */
	fun pagesOf(document: UmaModel): UmaDocumentPages {
		val textures = document.textures
		val tilePathById = textures?.tiles.orEmpty().mapNotNull { tile -> tile.path?.let { path -> tile.id to path } }.toMap()
		val tilePng: (AtlasTileId) -> ByteArray? = { tileId -> tilePathById[tileId.raw]?.let(document::payloadBytes) }
		val pageSet =
			textures?.renderPages?.let { renderPages ->
				// UMA §5.5: the format layer has already checked every render page's entry is there.
				AtlasPageSet(renderPages.pages.map { page -> checkNotNull(document.payloadBytes(page.path)) }, renderPages.drawablePages.orEmpty())
			}
		return UmaDocumentPages(pageSet, tilePng)
	}
}