package org.umamo.ui.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one model export (CMO3 or MOC3) allowed to run at a time, for the application's life.
 *
 * An export works on a background thread and holds its own copy of the model's pixels and file bytes while it
 * does, a large share of the heap on a big atlas: two at once can run out of memory where either alone would
 * finish.  So a second export is refused while one runs, and anything that would replace the document or end
 * the process waits for the running export first - a replaced document would otherwise stay resident under the
 * export while the next one loads beside it, and a quit would kill the write half done.
 *
 * App-lifetime rather than per document: the export controllers are rebuilt with every document, and the export
 * that must be waited for belongs to the document being replaced.  Every call comes from the UI thread.
 */
internal class ModelExportGate {
	private var running: Job? = null

	/** Whether a model export is running now. */
	val isBusy: Boolean
		get() = running?.isActive == true

	/**
	 * Starts [work] in [scope] as the running export, unless one already runs.
	 *
	 * @param CoroutineScope scope The scope the export runs in.
	 * @param Function       work  The export, from its file dialog to its report.
	 * @return Boolean True when [work] started; false when another export is running and it did not.
	 */
	fun tryStart(scope: CoroutineScope, work: suspend () -> Unit): Boolean {
		if (isBusy) {
			return false
		}
		running = scope.launch { work() }
		return true
	}

	/**
	 * Runs [action] once no model export is running: at once when none is, else when the running one ends,
	 * whether or not it wrote its file - a failed export has already said so in its alert, and nothing about it
	 * is a reason to keep the rigger from quitting or opening another document.
	 *
	 * @param CoroutineScope scope     The scope the wait runs in.
	 * @param Function       onWaiting Called when there is an export to wait for, before the wait.
	 * @param Function       action    What to do once no export is running.
	 */
	fun afterPendingExport(scope: CoroutineScope, onWaiting: () -> Unit = {}, action: () -> Unit) {
		val pending = running
		if (pending == null || pending.isCompleted) {
			action()
			return
		}
		onWaiting()
		scope.launch {
			pending.join()
			action()
		}
	}
}