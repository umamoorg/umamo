package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import org.umamo.edit.EditorSession
import org.umamo.format.FileKind
import org.umamo.format.FormatRegistry
import org.umamo.format.binary.contentHashOf
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.LiveParams

/**
 * An open editor document - a parsed source file the viewport renders. Lives in jvmAndroidMain (not
 * commonMain) because loading goes through :format's FormatRegistry / CMO3 codec, which is JVM-only
 * API shared by the desktop-JVM and Android targets.
 */
sealed interface Document {
	/**
	 * The stored path or URI string this document was read from (the recent-files key + window title
	 * source), or null for a document that was never on disk - a new, unsaved one.
	 *
	 * Nullable rather than a placeholder string: a stand-in path would be recorded in recent files and
	 * offered as a save target, both of which name a file that does not exist.
	 */
	val path: String?

	/**
	 * File name for titles/menus, falling back to [UNTITLED_DOCUMENT_NAME] for a document with no file.
	 *
	 * Deliberately not localized - this also reaches the log and an export's suggested file name, which
	 * are stable-text surfaces.  The window title localizes the unsaved case itself.
	 */
	val displayName: String get() = path?.let(::fileDisplayName) ?: UNTITLED_DOCUMENT_NAME
}

/** What a document with no file is called wherever a plain string is needed (the log, an export's name). */
const val UNTITLED_DOCUMENT_NAME = "Untitled"

/**
 * A document the editor shell runs a full puppet session over - the shared face of every format that
 * imports to a [PuppetModel].  The session, viewport, and panels consume only this surface, so a new
 * puppet-producing format plugs in by adding a subtype; format-specific state (the CMO3 model kept for
 * export reconcile, the MOC3 container kept for a future re-bake) stays on the concrete type.
 *
 * Each subtype lives with its loader rather than here - `Cmo3Document` in Cmo3DocumentLoader.kt,
 * `Moc3Document` in Moc3DocumentLoader.kt - so this file holds only what every format shares.
 */
sealed interface PuppetDocument : Document {
	/** The imported runtime puppet the session edits and the viewport renders. */
	val puppet: PuppetModel

	/** The decoded atlas pages + per-drawable page index backing the puppet's preview. */
	val textures: PuppetTextures

	/**
	 * The source artwork's pixels the puppet was authored against, keyed by atlas tile.  Each
	 * drawable's link to its source tile lives in the model now (`PuppetModel.atlas`), not here - this
	 * is only the lazy byte supplier.
	 *
	 * A document that retains no source art still supplies a store of its own, empty to begin with: a
	 * CMO3 keeps every layer's pixels in its graph, a MOC3 is the packed endpoint of that pipeline and
	 * has none, and artwork brought into either afterwards decodes into whichever store it was handed.
	 * That is why there is no shared empty instance to fall back on - one document's added art would
	 * read back out of the next document's store.  A surface that shows source art therefore checks for
	 * emptiness rather than assuming.
	 */
	val artRasters: SourceArtRasters

	/** The live parameter values driving the preview pose. */
	val liveParams: LiveParams
}

/**
 * The model an export should write for [document]: the session's edited model when [session] belongs
 * to this document, else the document's own unedited puppet.
 *
 * A session left over from a previously opened document would export the WRONG puppet if trusted
 * blindly, so a mismatch is logged as an error and the document's own unedited puppet is exported
 * instead - giving logged proof if a desync like that ever recurs.
 *
 * @param PuppetDocument  document The document being exported.
 * @param EditorSession?  session  The shell's current session, if any.
 * @return PuppetModel The model to export.
 */
fun exportedModelFor(document: PuppetDocument, session: EditorSession?): PuppetModel {
	val documentSession = session?.takeIf { candidate -> candidate.baselineModel === document.puppet }
	if (session != null && documentSession == null) {
		UmamoLog.error("export: session does not belong to ${document.displayName}; exporting the unedited document")
	}
	return documentSession?.model?.value ?: document.puppet
}

/**
 * The outcome of loading a file into a [Document]: the document, or the reason there is none.
 * Failures carry a [DocumentOpenFailure] so the caller can surface a localized alert (the shell's
 * document.openFailed command) instead of failing silently to the log.
 */
sealed interface DocumentLoad {
	/** Loaded successfully; [document] is ready to open. */
	class Loaded(val document: Document) : DocumentLoad

	/** Failed to load; [failure] says why and names the file. */
	class Failed(val failure: DocumentOpenFailure) : DocumentLoad
}

/**
 * Loads a picked/stored file into a [Document] via [loadDocument]'s byte core, reading through
 * FileKit's common API so desktop paths and Android SAF URIs take the same route.  A `.moc3` is the
 * one format routed to the sidecar-discovering loader instead: its manifest, display info, and atlas
 * pages live in sibling files, so it can only open from a file with a resolvable directory - which is
 * also why the byte-level overload keeps reporting it NotOpenable.
 *
 * @param PlatformFile           file          The picked or reconstructed file handle.
 * @param SourceArtImportOptions importOptions What an artwork import seeds and trims with; ignored by
 *   the model formats.
 * @return DocumentLoad The loaded document, or the failure reason (missing, unrecognized, or failed to parse).
 */
suspend fun loadDocument(file: PlatformFile, importOptions: SourceArtImportOptions = artworkImportOptions()): DocumentLoad {
	val bytes =
		runCatching { file.readBytes() }.getOrElse {
			UmamoLog.error("failed to read ${file.name}", it)
			return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ReadFailed, file.name))
		}
	if (FormatRegistry.detect(bytes, file.name)?.kind == FileKind.Moc3) {
		return loadMoc3Document(file, bytes)
	}
	return loadDocument(bytes, file.name, file.absolutePath(), importOptions)
}

/**
 * Loads [bytes] into a [Document] by detecting the format from the contents - magic bytes via
 * [FormatRegistry], with a file-extension fallback on [name] - then building the matching document:
 * a `.cmo3` imports as the puppet it holds, a layered artwork file (PSD / CLIP / KRA) or a flat
 * raster (PNG / BMP / JPEG / WebP / TIFF) becomes a fresh rig through the artwork import, packed at
 * open.  Returns a [DocumentLoad.Failed] if the content is unrecognized, not openable in the editor
 * shell, or fails to parse - failures are logged, never thrown, so the UI keeps the document it had.
 *
 * @param ByteArray              bytes         The file contents.
 * @param String                 name          The file name (the extension fallback for detection; the failure display name).
 * @param String                 path          The stored path or URI string recorded on the document.
 * @param SourceArtImportOptions importOptions What an artwork import seeds and trims with.
 * @return DocumentLoad The loaded document, or the failure reason.
 */
fun loadDocument(
	bytes: ByteArray,
	name: String,
	path: String,
	importOptions: SourceArtImportOptions = artworkImportOptions(),
): DocumentLoad =
	runCatching {
		val codec = FormatRegistry.detect(bytes, name)
		if (codec == null) {
			UmamoLog.warn("$path is not a format Umamo recognizes")
			return@runCatching DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.Unrecognized, name))
		}
		// The native document: read whole, never imported.
		if (codec.kind == FileKind.Uma) {
			return@runCatching buildUmaDocument(bytes, name, path)
		}
		if (codec.kind == FileKind.Cmo3) {
			// detect returns a star-projected FormatCodec<*>; each kind's read result is cast to the model
			// type that kind's codec is known to produce.
			return@runCatching buildCmo3Document(codec.read(bytes) as Cmo3Model, name, path)
		}
		val artwork = artworkOf(codec, bytes, name)
		if (artwork == null) {
			UmamoLog.warn("$path is a .${codec.kind.extension} file, which the editor shell can't open")
			return@runCatching DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.NotOpenable, name))
		}
		buildArtDocument(artwork, codec.kind, name, path, importOptions, contentHashOf(bytes), fileModifiedAtMillis(path))
	}.getOrElse {
		UmamoLog.error("failed to open $path", it)
		DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
	}