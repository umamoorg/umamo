package org.umamo.ui.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.umamo.format.uma.UmaModel

/**
 * Where a document saves, held apart from the document itself.
 *
 * A [Document] is the identity the editing session keys on, so a save must not replace it: a CMO3-origin
 * document stays a Cmo3Document after its first save, and its exports keep reconciling onto the graph it
 * retained.  Everything a save changes lives here instead - the `.uma` the document now lives in and the
 * [UmaModel] the next save lays over - one holder per open document, remembered by the host beside the
 * session.  A document with no file yet (a new one, or an import that was never saved) has no path until
 * its first Save As, which is what makes that first Save a Save As (D24).
 */
class DocumentFile(origin: Document) {
	/**
	 * The `.uma` a plain Save writes without asking: the file the document was opened from, or the one its
	 * first Save As wrote; null until then.  The window title follows it.
	 */
	var umaPath: String? by mutableStateOf((origin as? UmaDocument)?.path)

	/**
	 * The document the next save lays over, so every key this version does not know and every tile the
	 * file already holds survive in place: the file as opened, then each save's result; null until the first
	 * save mints an empty one.
	 */
	var base: UmaModel? = (origin as? UmaDocument)?.uma

	/** Whether the document opened read-only - it holds a required entry this version cannot interpret. */
	val readOnly: Boolean = (origin as? UmaDocument)?.isReadOnly == true

	/** Whether a save can be written at all: a puppet document that did not open read-only. */
	val canSave: Boolean = origin is PuppetDocument && !readOnly

	/** True while a save is running; a second save asked meanwhile is refused with a notice. */
	var saving: Boolean by mutableStateOf(false)

	/** Whether the once-per-document loss notice of a CMO3- or MOC3-origin document has been posted. */
	var lossNoticeShown: Boolean = false

	/**
	 * The name a Save As suggests: the origin's file name minus its extension, so `Erica.cmo3` suggests
	 * `Erica`; null for a document with no file, which the caller names the localized untitled name.
	 */
	val suggestedBaseName: String? = origin.path?.let { saveSuggestedName(origin.displayName) }
}