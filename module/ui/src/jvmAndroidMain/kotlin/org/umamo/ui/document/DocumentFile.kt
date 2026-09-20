package org.umamo.ui.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import org.umamo.format.uma.UmaModel

/**
 * Where a document saves, held apart from the document itself.
 *
 * A [Document] is the identity the editing session keys on, so a save must not replace it: a CMO3-origin
 * document stays a Cmo3Document after its first save, and its exports keep reconciling onto the graph it
 * retained.  Everything a save changes lives here instead - the `.uma` the document now lives in and the
 * [UmaModel] the next save lays over - one holder per open document, remembered by the host beside the
 * session.  A document with no file yet (a new one, or an import that was never saved) has no path until
 * its first Save As, which is what makes that first Save a Save As.
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

	/**
	 * The save in flight, completing true when the file landed and false when it did not (a cancelled
	 * dialog, a refused or failed write); null before the first save.  Kept so that whatever would end the
	 * process or replace the document can wait for it ([afterPendingSave]).
	 */
	var saveJob: Deferred<Boolean>? = null

	/**
	 * Runs [action] once no save is being written: at once when none is in flight, else when the one in
	 * flight lands - and not at all when it fails.
	 *
	 * Quitting is what makes this necessary.  The write runs on a background thread the process does not
	 * wait for, so an exit that went ahead mid-save would kill it, leaving a partial temporary on disk and
	 * no file - even for a document that reads as clean, since an import that was never edited has nothing
	 * unsaved to stop the quit with.  A failed save abandons the action because the rigger asked for a file
	 * and did not get one; the failure alert says why, and quitting is theirs to ask for again.
	 *
	 * @param CoroutineScope scope     The scope the wait runs in.
	 * @param Function       onWaiting Called when there is a save to wait for, before the wait.
	 * @param Function       action    What to do once nothing is being written.
	 */
	fun afterPendingSave(scope: CoroutineScope, onWaiting: () -> Unit = {}, action: () -> Unit) {
		val pending = saveJob
		if (pending == null || pending.isCompleted) {
			action()
			return
		}
		onWaiting()
		scope.launch {
			if (pending.await()) {
				action()
			}
		}
	}

	/** Whether the once-per-document loss notice of a CMO3- or MOC3-origin document has been posted. */
	var lossNoticeShown: Boolean = false

	/**
	 * The name a Save As suggests: the origin's file name minus its extension, so `Erica.cmo3` suggests
	 * `Erica`; null for a document with no file, which the caller names the localized untitled name.
	 */
	val suggestedBaseName: String? = origin.path?.let { saveSuggestedName(origin.displayName) }
}