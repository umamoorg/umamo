package org.umamo.ui.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.format.art.SourceArt
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.art.placedFor
import org.umamo.interop.cmo3.cmo3SourceArtOf
import org.umamo.runtime.model.ArtSource
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.SourceFilePresence

/**
 * One listed file's art as an operation read it.
 *
 * @property SourceArt art          The art.
 * @property String?   contentHash  The whole-file hash of the bytes it came from, or null when it came from a CMO3's own layers.
 * @property Long?     lastModified The file's modification time when read, or null when it came from a CMO3's own layers.
 * @property Boolean   fromCmo3     Whether it was read from the CMO3's decomposed layer images rather than the file.
 */
internal class SourceRead(
	val art: SourceArt,
	val contentHash: String?,
	val lastModified: Long?,
	val fromCmo3: Boolean,
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
 * One listed file's art the way every operation over it reads it: from disk when the file is there
 * and reads, placed by the record's offset, else - for a CMO3-origin document - from the layer PNGs
 * the official editor decomposed into the CMO3 at import, whether the file is gone or is one our
 * reader refuses; null when neither can be read.  The CMO3's own layers already sit on the document
 * canvas, so that fallback is never placed.  Each step is logged, so a relink or a match that fell
 * back says which file it could not reach and why.
 *
 * @param PuppetDocument     puppetDocument The open document the file is listed on.
 * @param ArtSource          source         The listed file.
 * @param SourceFilePresence presence       The file-presence probe; the app's logged okio probe by default.
 * @return SourceRead? The art and where it came from, or null when nothing could be read.
 */
internal suspend fun readSourceArt(
	puppetDocument: PuppetDocument,
	source: ArtSource,
	presence: SourceFilePresence = systemSourceFilePresence,
): SourceRead? {
	val path = source.path
	if (path == null) {
		UmamoLog.info("read artwork: '${source.name}' has no recorded path; falling back to what the document holds")
	} else if (presence(path) != true) {
		UmamoLog.info("read artwork: '${source.name}' is not at $path; falling back to what the document holds")
	} else {
		val read = readListedArtworkAt(source, path)
		if (read != null) {
			UmamoLog.info("read artwork: '${source.name}' read from $path")
			return SourceRead(read.art, read.contentHash, read.lastModified, fromCmo3 = false)
		}
		UmamoLog.warn("read artwork: '${source.name}' at $path could not be read; falling back to what the document holds")
	}
	val cmo3Document = puppetDocument as? Cmo3Document ?: return null
	// CMO3: CModelSource is the graph root the decomposed CLayeredImage tree hangs off.
	val root = cmo3Document.cmo3.root as? CModelSource ?: return null
	val art = withContext(Dispatchers.Default) { cmo3SourceArtOf(root, source.id) { resource -> cmo3Document.cmo3.extractLayerPng(resource) } } ?: return null
	return SourceRead(art, contentHash = null, lastModified = null, fromCmo3 = true)
}