package org.umamo.ui.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.art.documentSourceArtOf
import org.umamo.interop.art.placedFor
import org.umamo.interop.cmo3.cmo3SourceArtOf
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.artwork.SourceFilePresence

/** Where a listed file's art came from when an operation read it. */
internal enum class SourceReadOrigin {
	/** The file itself, from disk. */
	File,

	/** The layer images the official editor decomposed into a CMO3 the document is open from. */
	Cmo3Layers,

	/** The art the document holds: each bound layer's tile. */
	DocumentTiles,
}

/**
 * One listed file's art as an operation read it.
 *
 * @property SourceArt        art          The art.
 * @property String?          contentHash  The whole-file hash of the bytes it came from, or null when it did not come from the file.
 * @property Long?            lastModified The file's modification time when read, or null when it did not come from the file.
 * @property SourceReadOrigin origin       Where the art came from.
 * @property List?            inventory    The inventory the operation must keep for the file, or null to take the art's own: a
 *   read of the document's tiles keeps the record's rows as they are, since those tiles carry no hashes or review flags of
 *   their own to rewrite them with.
 */
internal class SourceRead(
	val art: SourceArt,
	val contentHash: String?,
	val lastModified: Long?,
	val origin: SourceReadOrigin,
	val inventory: List<ArtSourceLayer>? = null,
)

/**
 * A listed file read from [path] and placed on the document canvas by its record's offset - the ONE
 * way a listed file's disk read reaches an operation, so the reload, the relink, and the match all see
 * its layers in the frame the drawables and the inventory share.  A file that shares the document's
 * frame (offset zero) comes back as read.
 *
 * @param ArtSource source The listed file, for its offset.
 * @param String    path   The path to read.
 * @return ReadArtwork? The placed read, or null when the file yields no art.
 */
internal suspend fun readListedArtworkAt(source: ArtSource, path: String): ReadArtwork? {
	val read = readArtworkAt(path) ?: return null
	return ReadArtwork(read.art.placedFor(source), read.kind, read.contentHash, read.lastModified)
}

/**
 * One listed file's art the way every operation over it reads it: from disk when the file is there and
 * reads, placed by the record's offset; else, for a document open from a CMO3, from the layer PNGs the
 * official editor decomposed into it at import; else from the art the document holds - each layer a tile
 * binds read as that tile, a layer no tile binds with no pixels.  Null only when the document holds nothing
 * for the file either.  The fallbacks already sit on the document canvas, so neither is placed again.  Each
 * step is logged, so a relink or a match says which file it could not reach, why, and what it read instead.
 *
 * @param PuppetDocument     puppetDocument The open document the file is listed on, for its CMO3 and its pixels.
 * @param PuppetModel        model          The session's model as it stands, for the tiles the file's layers bind.
 * @param ArtSource          source         The listed file.
 * @param SourceFilePresence presence       The file-presence probe; the app's logged okio probe by default.
 * @return SourceRead? The art and where it came from, or null when nothing could be read.
 */
internal suspend fun readSourceArt(
	puppetDocument: PuppetDocument,
	model: PuppetModel,
	source: ArtSource,
	presence: SourceFilePresence = systemSourceFilePresence,
): SourceRead? {
	val path = source.path
	if (path == null) {
		UmamoLog.info("read artwork: '${source.name}' has no recorded path")
	} else if (presence(path) != true) {
		UmamoLog.info("read artwork: '${source.name}' is not at $path")
	} else {
		val read = readListedArtworkAt(source, path)
		if (read != null) {
			UmamoLog.info("read artwork: '${source.name}' read from $path")
			return SourceRead(read.art, read.contentHash, read.lastModified, SourceReadOrigin.File)
		}
		UmamoLog.warn("read artwork: '${source.name}' at $path could not be read")
	}
	val cmo3Document = puppetDocument as? Cmo3Document
	// CMO3: CModelSource is the graph root the decomposed CLayeredImage tree hangs off.
	val root = cmo3Document?.cmo3?.root as? CModelSource
	if (cmo3Document != null && root != null) {
		val art = withContext(Dispatchers.Default) { cmo3SourceArtOf(root, source.id) { resource -> cmo3Document.cmo3.extractLayerPng(resource) } }
		if (art != null) {
			UmamoLog.info("read artwork: '${source.name}' read from the CMO3's own decomposed layers instead")
			return SourceRead(art, contentHash = null, lastModified = null, origin = SourceReadOrigin.Cmo3Layers)
		}
	}
	val held =
		documentSourceArtOf(model, source.id) { tileId ->
			puppetDocument.artRasters.decodeRaster(tileId)?.let { decoded -> LayerRaster(decoded.width, decoded.height, decoded.rgba) }
		}
	if (held == null) {
		UmamoLog.warn("read artwork: '${source.name}' has nothing the document holds to read instead")
		return null
	}
	val withPixels = held.layers.count { layer -> layer.kind == SourceLayerKind.Raster }
	UmamoLog.info("read artwork: '${source.name}' read from the art the document holds instead ($withPixels of ${held.layers.size} layers have pixels)")
	return SourceRead(held, contentHash = null, lastModified = null, origin = SourceReadOrigin.DocumentTiles, inventory = source.layers.filter { row -> row.present })
}