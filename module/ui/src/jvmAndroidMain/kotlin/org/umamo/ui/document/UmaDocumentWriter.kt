package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.IOException
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.fittedInto
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.uma.UmaDocumentBridge
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.storage.writeReplacing
import org.umamo.ui.model.DrawableThumbnailer
import org.umamo.ui.viewport.AtlasPageBinding

/** The size the saved thumbnail is fitted into (UMA D22). */
private const val THUMBNAIL_SIZE = 256

/**
 * Writes [model] as a `.uma` at [destination], laid over [base].
 *
 * Pure given its inputs, which is what lets a gate test drive the real save with no picker: the caller
 * snapshots the session's model, the atlas page binding, and the editor state on the UI thread and hands them in, and this
 * runs the rest off it - the pixel gathering and the encode on the default dispatcher, the write on the
 * platform's own file-system route.  Nothing here marks the session saved; the caller does that with the
 * same snapshot once the file is on disk.
 *
 * What the file gets for pixels (docs/format/UMA.md § 5):
 *  - A tile the base already holds is copied by the codec and never asked for.  Every other tile takes
 *    its PNG from the document's own bytes where it has them (a CMO3's embedded layer, a `.uma`'s stored
 *    entry) and is encoded from its decoded raster otherwise.
 *  - The render pages are stored as the file or the origin had them while the atlas is at the document's
 *    baseline - the same identity gate the CMO3 export uses - and derive from the tiles after any repack (D20).
 *  - The thumbnail is the rest pose fitted into [THUMBNAIL_SIZE] pixels (D22).
 *
 * Editor state rides on the save as a merge patch over what the base holds (docs/format/UMA.md § 7.5) and is never
 * a reason to refuse one: an editor entry too new for this version to merge into is carried as it is.
 *
 * @param PuppetDocument   document    The open document, for the pixels only it can supply.
 * @param UmaModel         base        The document the save lays over.
 * @param PuppetModel      model       The session's model as snapshotted.
 * @param AtlasPageBinding binding     The session's atlas pages as snapshotted.
 * @param JsonObject       editorState The editor entry's merge patch as gathered; empty when there is none to write.
 * @param PlatformFile     destination Where to write.
 * @return UmaWriteOutcome The written document, or why nothing complete was written.
 */
suspend fun writeUmaDocument(
	document: PuppetDocument,
	base: UmaModel,
	model: PuppetModel,
	binding: AtlasPageBinding,
	editorState: JsonObject,
	destination: PlatformFile,
): UmaWriteOutcome {
	val encoded =
		withContext(Dispatchers.Default) {
			try {
				val modelDocument = UmaDocumentBridge.documentOf(base, model, pixelSourceFor(document, base, model, binding))
				// UMA §7.5: the patch is laid over the entry the base holds, unless that entry is one this version cannot read.
				val written = if (modelDocument.holdsTooNewEntry(UmaEntryKind.Editor)) modelDocument else modelDocument.withEditorState(editorState)
				UmaWriteOutcome.Written(written) to Uma.write(written)
			} catch (failure: UmaWriteException) {
				UmamoLog.error("save: ${destination.name} refused", failure)
				return@withContext UmaWriteOutcome.Failed(failure.message ?: failure.detail) to null
			} catch (failure: IllegalStateException) {
				// The codec's own refusal of a read-only document; the app gates that earlier, so reaching it is a bug
				// worth a log line, not a crash.
				UmamoLog.error("save: ${destination.name} refused", failure)
				return@withContext UmaWriteOutcome.Failed(failure.message.orEmpty()) to null
			}
		}
	val (outcome, bytes) = encoded
	if (bytes == null) {
		return outcome
	}
	return try {
		// Not cancellable: a composition torn down mid-save (Android's back, a closing window) cancels the
		// scope this runs in, and a write interrupted there leaves a partial file - in place on Android,
		// where there is no temporary to discard.  Once the bytes exist, they land.
		withContext(NonCancellable) { destination.writeReplacing(bytes) }
		outcome
	} catch (failure: IOException) {
		UmamoLog.error("save: could not write ${destination.name}", failure)
		UmaWriteOutcome.Failed(failure.message ?: "the file could not be written")
	}
}

/**
 * The pixels a save hands the bridge: each tile the base lacks, the render pages' mode, and the thumbnail.
 *
 * The tile PNGs are gathered up front rather than inside the source's lambda, so the layout - which asks
 * for them one by one - never runs a decode or an encode of its own.
 *
 * @param PuppetDocument   document The open document.
 * @param UmaModel         base     The document the save lays over.
 * @param PuppetModel      model    The model being written.
 * @param AtlasPageBinding binding  The atlas pages as snapshotted.
 * @return UmaPixelSource The pixels.
 */
private fun pixelSourceFor(document: PuppetDocument, base: UmaModel, model: PuppetModel, binding: AtlasPageBinding): UmaPixelSource {
	val heldByBase = base.textures?.tiles.orEmpty().mapNotNull { tile -> tile.path?.let { tile.id } }.toHashSet()
	val tilePngs = HashMap<String, ByteArray?>()
	for (tile in model.atlas.tiles) {
		if (tile.id.raw !in heldByBase) {
			tilePngs[tile.id.raw] = tilePngFor(document, tile.id)
		}
	}
	val atBaseline = binding.textures === document.textures
	val renderPages: UmaRenderPagePixels =
		when {
			!atBaseline -> UmaRenderPagePixels.Derived
			document is UmaDocument -> if (document.storedPages != null) UmaRenderPagePixels.Retained else UmaRenderPagePixels.Derived
			document is Cmo3Document && document.pageSet.pageBytes.isNotEmpty() -> UmaRenderPagePixels.Stored(document.pageSet.pageBytes, document.pageSet.atlasIndexByDrawableId)
			document is Moc3Document -> UmaRenderPagePixels.Stored(document.pagePngs(), document.textures.atlasIndexByDrawableId)
			else -> UmaRenderPagePixels.Derived
		}
	val thumbnail = DrawableThumbnailer(model, binding.textures).modelRasterFor()?.fittedInto(THUMBNAIL_SIZE)?.let(PngCodec::write)
	return UmaPixelSource({ tileId -> tilePngs[tileId] }, renderPages, thumbnail)
}

/**
 * A tile's PNG for a file that does not hold it yet: the document's own bytes where it has them, else an
 * encode of the decoded raster; null when the document has no pixels for the tile at all, which the
 * bridge refuses the save over.
 *
 * @param PuppetDocument document The open document.
 * @param AtlasTileId    tileId   The tile.
 * @return ByteArray? The PNG, or null.
 */
private fun tilePngFor(document: PuppetDocument, tileId: AtlasTileId): ByteArray? {
	val embedded = (document as? Cmo3Document)?.tilePng?.invoke(tileId)
	if (embedded != null) {
		return embedded
	}
	val decoded = document.artRasters.decodeRaster(tileId) ?: return null
	return PngCodec.write(RasterImage(decoded.width, decoded.height, decoded.rgba))
}