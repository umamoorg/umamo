package org.umamo.ui.document

import org.umamo.format.uma.UmaModel

/** What writing a document produced. */
sealed interface UmaWriteOutcome {
	/**
	 * The file was written; [uma] is the document as written, the base the next save lays over.
	 *
	 * @property UmaModel uma The written document.
	 */
	class Written(val uma: UmaModel) : UmaWriteOutcome

	/**
	 * Nothing was written, or nothing complete; the target is whatever it was.
	 *
	 * @property String reason Why, in the codec's or the file system's words.
	 */
	class Failed(val reason: String) : UmaWriteOutcome
}