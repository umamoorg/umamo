package org.umamo.interop.uma

import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.textures.UmaPixelSource
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
	 * @param UmaModel       base   The document to save over: the one the model was opened from, or a new one.
	 * @param PuppetModel    model  The model.
	 * @param UmaPixelSource pixels The pixels the save writes: the tiles' PNGs, what the drawables sample, and the
	 *   thumbnail.
	 * @return UmaModel The updated document.
	 * @throws UmaWriteException When the model holds a value the format cannot represent or a pixel entry is
	 *   missing.
	 */
	fun documentOf(base: UmaModel, model: PuppetModel, pixels: UmaPixelSource): UmaModel =
		base
			.withPuppet(UmaPuppetExport.puppetOf(model))
			.withTextures(UmaTexturesBridge.texturesOf(model), pixels)
			.withSources(UmaSourcesBridge.sourcesOf(model))

	/**
	 * The model [document] describes: the puppet with its atlas and linked source art.
	 *
	 * @param UmaModel document The document.
	 * @return PuppetModel The model.
	 * @throws UmaFormatException When the document has no puppet entry, or an entry holds a shape the schema
	 *   does not allow.
	 */
	fun modelOf(document: UmaModel): PuppetModel {
		val puppetEntry =
			document.entries.firstOrNull { entry -> entry.liveKind == UmaEntryKind.Puppet }
				?: throw UmaFormatException(UmaReadFailure.MissingEntry(UmaEntryKind.Puppet.defaultPath))
		val puppet = UmaPuppetImport.modelOf(checkNotNull(document.puppet), puppetEntry.path)
		return puppet.copy(atlas = UmaTexturesBridge.atlasOf(document.textures), sources = UmaSourcesBridge.sourcesOf(document.sources))
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