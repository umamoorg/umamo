package org.umamo.ui.document

import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaReadOnlyReason
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.interop.AtlasPageSet
import org.umamo.interop.uma.UmaDocumentBridge
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.render.UndecodablePagePolicy
import org.umamo.render.buildPuppetTextures
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.help.ProjectInfo
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.initialLiveParams

/**
 * A rig opened from Umamo's own `.uma` file - the one format the editor opens and saves rather than
 * imports and exports.
 *
 * The opened [uma] is kept whole: a save lays the session's model over it, so every key this version
 * does not know survives in place and every tile the file already holds is copied byte for byte
 * (docs/format/UMA.md § 3.3).  A file that carries a required entry this version cannot interpret opens
 * read-only ([isReadOnly]): edits are allowed, but no save can be written that would keep the file
 * honest, so Save is unavailable and the shell says why.
 *
 * @property UmaModel      uma         The file as read, the base the next save lays over.
 * @property AtlasPageSet? storedPages The render pages the file stores, in page order, or null when it
 *   stores none and the pages derive from the tiles at open.
 */
class UmaDocument(
	override val path: String,
	val uma: UmaModel,
	override val puppet: PuppetModel,
	override val textures: PuppetTextures,
	override val artRasters: SourceArtRasters,
	override val liveParams: LiveParams,
	val storedPages: AtlasPageSet?,
) : PuppetDocument {
	/** Each required entry this version cannot interpret; empty for a document that can be saved. */
	val readOnlyReasons: List<UmaReadOnlyReason>
		get() = uma.readOnlyReasons

	/** True when the file holds something this version must not write over, so Save is unavailable. */
	val isReadOnly: Boolean
		get() = uma.isReadOnly
}

/**
 * The writer record this application stamps into every `.uma` it saves: the manifest's `writer` names
 * the app and the version, which is what a later reader shows for "made by a newer Umamo".
 * Do NOT attempt to localize the "Umamo" application name or pull in the app_name string.
 *
 * @return UmaWriterInfo The record.
 */
fun umamoWriterInfo(): UmaWriterInfo = UmaWriterInfo("Umamo", ProjectInfo.VERSION)

/**
 * Assembles a [UmaDocument] from a file's bytes: the codec reads the container, the bridge rebuilds the
 * puppet with its atlas and sources, and the pages come from the file when it stores them or derive
 * from the tiles when it does not.
 *
 * Failures map to the open errors the shell shows: a file that is not a UMA at all, a container or a
 * puppet entry newer than this reader, and a damaged or malformed file each get their own.  Any OTHER
 * required entry this version cannot interpret is not a failure - the codec opens the file read-only and
 * the document says so; only the puppet is needed to have a rig to show at all.
 *
 * @param ByteArray bytes The file's contents.
 * @param String    name  The file name, for the failure alert.
 * @param String    path  The stored path or uri string recorded on the document.
 * @return DocumentLoad The document, or the failure reason.
 */
internal fun buildUmaDocument(bytes: ByteArray, name: String, path: String): DocumentLoad {
	val uma =
		try {
			Uma.read(bytes)
		} catch (failure: UmaFormatException) {
			UmamoLog.error("failed to open $path: ${failure.message}", failure)
			return DocumentLoad.Failed(DocumentOpenFailure(openErrorOf(failure.failure), name))
		}
	// A puppet entry newer than this version reads is a file from a newer Umamo, not a damaged one: the
	// codec keeps it byte for byte and reads nothing from it, so there is no rig to open, and saying "the
	// file may be damaged" about a healthy file sends the rigger looking for the wrong problem.
	if (uma.holdsTooNewEntry(UmaEntryKind.Puppet)) {
		UmamoLog.warn("cannot open $path: its puppet entry needs a newer version of Umamo")
		return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.NewerFormat, name))
	}
	// A puppet entry the file lacks fails here as a parse failure, through the caller's catch.
	val puppet = UmaDocumentBridge.modelOf(uma)
	val pages = UmaDocumentBridge.pagesOf(uma)
	// The document's own store, over the file's tile PNGs: artwork brought in later decodes into it.
	val artRasters = SourceArtRasters.fromPng { tileId -> pages.tilePng(tileId) }
	val stored = pages.pageSet
	// Stored pages decode with the Skip policy, as a CMO3's do: one page that will not decode must not
	// cost the rigger the rest of an editable rig.  Without stored pages the atlas composes from the
	// tiles, the way an artwork document's does after its pack.
	val textures =
		stored?.let { pageSet -> buildPuppetTextures(pageSet.pageBytes, pageSet.atlasIndexByDrawableId, pageSet.premultipliedAlpha, UndecodablePagePolicy.Skip) }
			?: deriveAtlasTextures(puppet, artRasters, premultipliedAlpha = false)
			?: PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false)
	if (uma.isReadOnly) {
		UmamoLog.warn("opened $path read-only: " + uma.readOnlyReasons.joinToString { reason -> "'${reason.path}' (${reason.kind}): ${reason.cause}" })
	}
	return DocumentLoad.Loaded(UmaDocument(path, uma, puppet, textures, artRasters, initialLiveParams(puppet), stored))
}

/**
 * The open error a codec read failure reports.
 *
 * @param UmaReadFailure failure Why the codec refused the file.
 * @return DocumentOpenError The reason the shell shows.
 */
private fun openErrorOf(failure: UmaReadFailure): DocumentOpenError =
	when (failure) {
		is UmaReadFailure.NotUma -> DocumentOpenError.Unrecognized
		is UmaReadFailure.UnsupportedContainerVersion -> DocumentOpenError.NewerFormat
		is UmaReadFailure.CorruptContainer,
		is UmaReadFailure.MalformedManifest,
		is UmaReadFailure.MissingEntry,
		is UmaReadFailure.MalformedEntry,
		-> DocumentOpenError.ParseFailed
	}