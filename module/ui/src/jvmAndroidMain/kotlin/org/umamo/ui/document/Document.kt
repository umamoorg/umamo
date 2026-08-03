package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import org.umamo.format.FileKind
import org.umamo.format.FormatRegistry
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.LiveParams

/**
 * An open editor document - a parsed source file the viewport renders. Lives in jvmAndroidMain (not
 * commonMain) because loading goes through :format's FormatRegistry / CMO3 codec, which is JVM-only
 * API shared by the desktop-JVM and Android targets.
 */
sealed interface Document {
	/** The stored path or URI string (the recent-files key + window title source). */
	val path: String

	/** File name for titles/menus. */
	val displayName: String get() = fileDisplayName(path)
}

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

	/** The live parameter values driving the preview pose. */
	val liveParams: LiveParams
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
 * @param PlatformFile file The picked or reconstructed file handle.
 * @return DocumentLoad The loaded document, or the failure reason (missing, unrecognised, or failed to parse).
 */
suspend fun loadDocument(file: PlatformFile): DocumentLoad {
	val bytes =
		runCatching { file.readBytes() }.getOrElse {
			UmamoLog.error("failed to read ${file.name}", it)
			return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ReadFailed, file.name))
		}
	if (FormatRegistry.detect(bytes, file.name)?.kind == FileKind.Moc3) {
		return loadMoc3Document(file, bytes)
	}
	return loadDocument(bytes, file.name, file.absolutePath())
}

/**
 * Loads [bytes] into a [Document] by detecting the format from the contents - magic bytes via
 * [FormatRegistry], with a file-extension fallback on [name] - then building the matching document
 * (`.cmo3` → puppet preview). Returns a [DocumentLoad.Failed] if the content is unrecognised, not
 * openable in the editor shell, or fails to parse - failures are logged, never thrown, so the UI
 * keeps the document it had.
 *
 * @param ByteArray bytes The file contents.
 * @param String name The file name (the extension fallback for detection; the failure display name).
 * @param String path The stored path or URI string recorded on the document.
 * @return DocumentLoad The loaded document, or the failure reason.
 */
fun loadDocument(bytes: ByteArray, name: String, path: String): DocumentLoad =
	runCatching {
		val codec = FormatRegistry.detect(bytes, name)
		when {
			codec == null -> {
				UmamoLog.warn("$path is not a format Umamo recognizes")
				DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.Unrecognized, name))
			}
			codec.kind == FileKind.Cmo3 -> {
				// detect returns a star-projected FormatCodec<*>; each kind's read result is cast to the model
				// type that kind's codec is known to produce.
				buildCmo3Document(codec.read(bytes) as Cmo3Model, name, path)
			}
			else -> {
				UmamoLog.warn("$path is a .${codec.kind.extension} file, which the editor shell can't open")
				DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.NotOpenable, name))
			}
		}
	}.getOrElse {
		UmamoLog.error("failed to open $path", it)
		DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
	}
