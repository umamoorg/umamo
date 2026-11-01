package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.StringResource
import org.umamo.interop.ExportReport
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.model.repack.AtlasRepackReport
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.dialog_cancel
import org.umamo.ui.resources.dialog_confirm
import org.umamo.ui.settings.QuickSetupState
import org.umamo.ui.workspace.export.ExportOptionsRequest

/**
 * A dialog's extra choice beside its own buttons - "Don't Save" beside a confirmation's "Save", "Don't Show
 * Again" beside an alert's OK.
 *
 * @property StringResource label    The button's label.
 * @property Function       onSelect The action to run when picked.
 */
internal data class DialogAlternative(
	val label: StringResource,
	val onSelect: () -> Unit,
)

/**
 * A modal message with nothing to decide - a document that opened read-only, a save that failed - shown
 * until acknowledged.  An [alternative] offers one more way to acknowledge it, such as asking not to be
 * told again; OK, the scrim, Escape, and Enter never pick it.
 *
 * @property StringResource     message     The message resource.
 * @property List               arguments   The format arguments, in placeholder order: document data (file
 *   names, entry paths), never translated.
 * @property DialogAlternative? alternative A second button beside OK, or null for OK alone.
 */
internal data class AlertRequest(
	val message: StringResource,
	val arguments: List<Any> = emptyList(),
	val alternative: DialogAlternative? = null,
)

/**
 * A pending confirmation: the localized prompt to show, the buttons it names, and the action to run if the
 * user confirms.  The shell queues these and renders a ConfirmDialog for the first, so a destructive command
 * (reset, import-overwrite, export-overwrite) raises one instead of acting immediately; one raised while
 * another is up waits behind it rather than replacing it.
 *
 * @property StringResource      message      The localized prompt shown in the dialog.
 * @property List                arguments    The prompt's format arguments, in placeholder order; empty for
 *                                            an argument-free prompt.  Plain values (counts, file names) -
 *                                            document data is never translated.
 * @property StringResource      confirmLabel The confirm button's label, naming the action it takes.
 * @property StringResource      cancelLabel  The cancel button's label.
 * @property DialogAlternative?  alternative  A third choice, or null for a two-button dialog.
 * @property Function            onConfirm    The action to run when confirmed.
 */
internal data class ConfirmRequest(
	val message: StringResource,
	val arguments: List<Any> = emptyList(),
	val confirmLabel: StringResource = Res.string.dialog_confirm,
	val cancelLabel: StringResource = Res.string.dialog_cancel,
	val alternative: DialogAlternative? = null,
	val onConfirm: () -> Unit,
)

/**
 * One arrival of a modal: the payload it shows, wrapped so the same payload raised twice still counts as two
 * arrivals.  Its identity is what tells one arrival from the next when the topmost modal changes hands.
 *
 * @property Any payload The request, report, or failure the modal shows.
 */
internal class ModalArrival<T : Any>(
	val payload: T,
)

/**
 * One modal kind's arrivals in the order they came: the head shows and the rest wait behind it.  A snapshot
 * list, so a composition reading the head recomposes when it changes hands.
 */
internal class ModalQueue<T : Any> {
	private val arrivals = mutableStateListOf<ModalArrival<T>>()

	/** The showing arrival, or null while none of this kind is queued. */
	val head: ModalArrival<T>?
		get() = arrivals.firstOrNull()

	/**
	 * Queues an arrival behind every one already waiting; it shows at once only when none was.
	 *
	 * @param Any payload The arrival's payload.
	 */
	fun enqueue(payload: T) {
		arrivals.add(ModalArrival(payload))
	}

	/**
	 * Whether any arrival, showing or waiting, matches [predicate].
	 *
	 * @param Function predicate The test each payload is put to.
	 * @return Boolean True when one matches.
	 */
	fun anyQueued(predicate: (T) -> Boolean): Boolean = arrivals.any { arrival -> predicate(arrival.payload) }

	/**
	 * Removes the showing arrival, provided it is still the one the caller acted on.
	 *
	 * @param Any? showing The payload the caller acted on, or null for whatever shows now.
	 * @return Any? The removed payload, or null when none shows or [showing] is no longer the head.
	 */
	fun removeHead(showing: T?): T? {
		val current = arrivals.firstOrNull() ?: return null

		if (showing != null && showing !== current.payload) {
			return null
		}

		arrivals.removeAt(0)

		return current.payload
	}
}

/**
 * The modal alerts that outlive a document swap: the app-layer alert, the file-open failure, and the export
 * report.  The app raises these about work that can finish across a swap - a document that opened read-only
 * says so right after it is swapped in, and an export's report or failure lands just as a replace that waited
 * for the export goes ahead - while the shell, and its overlay state with it, is rebuilt for every document.
 * Raised through the outgoing shell's commands, they would be discarded with that shell unread.  So the app
 * holds these queues for its whole life, like [QuickSetupState], and provides them through [LocalAppAlerts].
 * Confirmations and the repack report stay with the shell: what they act on or describe is the document's.
 */
internal class AppAlertQueues {
	/** The app-layer alerts (document.alert). */
	val alerts = ModalQueue<AlertRequest>()

	/** The file-open failures (document.openFailed). */
	val openFailures = ModalQueue<DocumentOpenFailure>()

	/** The export reports (document.exportReport). */
	val exportReports = ModalQueue<ExportReport>()
}

/** The app's [AppAlertQueues], or null where no app provides them (the standalone shell, tests). */
internal val LocalAppAlerts = staticCompositionLocalOf<AppAlertQueues?> { null }

/**
 * The shell's transient overlay state in one place: which modal chrome is currently up.  The command
 * handlers raise these, the modal key ladder routes Escape/Enter by them, the focus-reclaim effect watches
 * which is topmost, and the shell renders the matching overlay for each - one holder instead of a loose var
 * per overlay, so the pieces that must agree read the same state.
 *
 * The modal alerts are queues, not slots: one of each kind shows at a time, and a later arrival of that kind
 * waits behind it in arrival order.  Exports and saves finish in the background, so a request can land while
 * one of its kind is still unread; replacing it would lose a prompt the rigger never saw, and a reflexive
 * Enter would answer the wrong one.
 *
 * @param QuickSetupState quickSetup The Quick Setup modal's visibility, which the app holds for its whole
 *   life because this state is rebuilt with the shell on every document swap; a standalone shell has its own.
 * @param AppAlertQueues  appAlerts  The alert, file-open failure, and export report queues, which the app holds
 *   for its whole life for the same reason; a standalone shell has its own.
 */
internal class ShellOverlayState(
	private val quickSetup: QuickSetupState = QuickSetupState(visible = false),
	appAlerts: AppAlertQueues = AppAlertQueues(),
) {
	/** The command palette's visible flag - toggled by palette.toggle. */
	var paletteVisible: Boolean by mutableStateOf(false)

	/** The preferences overlay's visible flag - toggled by the edit.preferences command. */
	var settingsVisible: Boolean by mutableStateOf(false)

	/** The Quick Setup modal's visible flag - open on a first run, toggled by the help.quickSetup command. */
	var quickSetupVisible: Boolean
		get() = quickSetup.visible
		set(value) {
			quickSetup.visible = value
		}

	/** The About dialog's visible flag - toggled by the help.about command. */
	var aboutVisible: Boolean by mutableStateOf(false)

	/** The Credits dialog's visible flag - toggled by the help.credits command. */
	var creditsVisible: Boolean by mutableStateOf(false)

	private val confirmQueue = ModalQueue<ConfirmRequest>()

	private val openFailureQueue = appAlerts.openFailures

	private val alertQueue = appAlerts.alerts

	private val exportReportQueue = appAlerts.exportReports

	private val repackReportQueue = ModalQueue<AtlasRepackReport>()

	/**
	 * The confirmation showing now, or null while none is queued.  A destructive command (reset,
	 * import-overwrite) sets this instead of acting; the rendered ConfirmDialog runs the action on confirm.
	 * Setting a request queues it behind any already waiting; setting null dismisses the showing one.
	 */
	var pendingConfirm: ConfirmRequest?
		get() = confirmQueue.head?.payload
		set(value) {
			raiseOrDismiss(confirmQueue, value)
		}

	/**
	 * Whether any confirmation, showing or waiting, matches [predicate] - for a caller that raises one prompt
	 * however often it is asked, such as the quit prompt behind a second click on the window's close button.
	 *
	 * @param Function predicate The test each confirmation is put to.
	 * @return Boolean True when one matches.
	 */
	fun confirmQueued(predicate: (ConfirmRequest) -> Boolean): Boolean = confirmQueue.anyQueued(predicate)

	/**
	 * Whether Enter is physically down, as the key ladder last saw it.  Compose's key-down carries no
	 * repeat flag, so this is the one honest signal for "the Enter that came before this dialog is still held".
	 */
	private var enterHeld: Boolean = false

	/**
	 * Whether Enter may act on the topmost modal alert - confirm the confirmation, acknowledge the others.
	 *
	 * False while an Enter that was already down when the topmost modal took over is still held, since its OS
	 * auto-repeat would otherwise answer a dialog nobody has seen.  Two things hand a modal the top under a
	 * held Enter: the palette runs its command on Enter's key-down, so a request it raises lands under that
	 * key; and the Enter that answers one modal is still down when the next queued one takes its place.  The
	 * release arms it.  A modal that takes the top with Enter up - raised by a menu row, a button, or a
	 * finished export - is armed at once, and the first Enter acts on it.
	 */
	var enterArmed: Boolean = true
		private set

	/**
	 * Records an Enter stroke the ladder saw, so a modal that takes the top under a held Enter waits for its
	 * release.
	 *
	 * @param Boolean isDown True for the key-down, false for the release.
	 */
	fun noteEnterStroke(isDown: Boolean) {
		enterHeld = isDown

		if (!isDown) {
			enterArmed = true
		}
	}

	/**
	 * Runs the showing confirmation's action - what its confirm button and Enter both do.
	 *
	 * The confirmation leaves the queue before the action runs, so a confirmation or an alert the action raises
	 * queues behind those already waiting instead of being dismissed along with it.  The key ladder and the
	 * rendered dialog both come through here, so the two cannot disagree about what confirming means.
	 *
	 * @param ConfirmRequest? showing The confirmation the rendered dialog was showing when its button was
	 *   clicked, so a click that lands after that one has gone never confirms the next; null (the key ladder)
	 *   acts on whichever shows now.
	 */
	fun confirmPending(showing: ConfirmRequest? = null) {
		val request = takeShowing(confirmQueue, showing) ?: return
		request.onConfirm()
	}

	/**
	 * Dismisses the showing confirmation without acting - what its cancel button, the scrim, and Escape do.
	 *
	 * @param ConfirmRequest? showing The confirmation the rendered dialog was showing, or null for whichever
	 *   shows now; see [confirmPending].
	 */
	fun cancelPending(showing: ConfirmRequest? = null) {
		takeShowing(confirmQueue, showing)
	}

	/**
	 * Runs the showing confirmation's third choice, when it has one, dequeuing it first as [confirmPending] does.
	 *
	 * @param ConfirmRequest? showing The confirmation the rendered dialog was showing, or null for whichever
	 *   shows now; see [confirmPending].
	 */
	fun choosePendingAlternative(showing: ConfirmRequest? = null) {
		val request = showingPayload(confirmQueue, showing) ?: return
		val alternative = request.alternative ?: return
		takeShowing(confirmQueue, request)
		alternative.onSelect()
	}

	/**
	 * The app-layer message showing now, or null while none is queued - raised by the document.alert command,
	 * acknowledged by its OK button, the scrim, Escape, Enter, or its alternative.  Setting a request queues it
	 * behind any already waiting; setting null dismisses the showing one.
	 */
	var pendingAlert: AlertRequest?
		get() = alertQueue.head?.payload
		set(value) {
			raiseOrDismiss(alertQueue, value)
		}

	/**
	 * Dismisses the showing app-layer alert - what its OK button, the scrim, Escape, and Enter do.
	 *
	 * @param AlertRequest? showing The alert the rendered dialog was showing, or null for whichever shows now;
	 *   see [confirmPending].
	 */
	fun dismissAlert(showing: AlertRequest? = null) {
		takeShowing(alertQueue, showing)
	}

	/**
	 * Runs the showing alert's alternative, when it has one, dequeuing the alert first as
	 * [choosePendingAlternative] does for a confirmation.
	 *
	 * @param AlertRequest? showing The alert the rendered dialog was showing, or null for whichever shows now;
	 *   see [confirmPending].
	 */
	fun chooseAlertAlternative(showing: AlertRequest? = null) {
		val request = showingPayload(alertQueue, showing) ?: return
		val alternative = request.alternative ?: return
		takeShowing(alertQueue, request)
		alternative.onSelect()
	}

	/**
	 * The file-open failure alert showing now, or null while none is queued - raised by the document.openFailed
	 * command (dispatched by the app's document layer), acknowledged by its OK button, the scrim, Escape, or
	 * Enter.  Setting a failure queues it behind any already waiting; setting null dismisses the showing one.
	 */
	var openFailure: DocumentOpenFailure?
		get() = openFailureQueue.head?.payload
		set(value) {
			raiseOrDismiss(openFailureQueue, value)
		}

	/**
	 * Dismisses the showing file-open failure alert.
	 *
	 * @param DocumentOpenFailure? showing The failure the rendered dialog was showing, or null for whichever
	 *   shows now; see [confirmPending].
	 */
	fun dismissOpenFailure(showing: DocumentOpenFailure? = null) {
		takeShowing(openFailureQueue, showing)
	}

	/**
	 * The export report showing now, or null while none is queued - raised by the document.exportReport command
	 * when a CMO3 or MOC3 export finished with advisory notices (edits the target format could not carry,
	 * features stripped for an older runtime target, weld divergence), acknowledged like the open-failure
	 * alert.  Non-blocking: the export has already been written when this shows.  Setting a report queues it
	 * behind any already waiting; setting null dismisses the showing one.
	 */
	var exportReport: ExportReport?
		get() = exportReportQueue.head?.payload
		set(value) {
			raiseOrDismiss(exportReportQueue, value)
		}

	/**
	 * Dismisses the showing export report.
	 *
	 * @param ExportReport? showing The report the rendered dialog was showing, or null for whichever shows now;
	 *   see [confirmPending].
	 */
	fun dismissExportReport(showing: ExportReport? = null) {
		takeShowing(exportReportQueue, showing)
	}

	/**
	 * The repack refusal report showing now, or null while none is queued - raised by the document.repackReport
	 * command when a repack aborted over tiles it could not carry, acknowledged like the open-failure alert.
	 * Unlike the export report this one is about work that did NOT happen: nothing was applied when it shows.
	 * Setting a report queues it behind any already waiting; setting null dismisses the showing one.
	 */
	var repackReport: AtlasRepackReport?
		get() = repackReportQueue.head?.payload
		set(value) {
			raiseOrDismiss(repackReportQueue, value)
		}

	/**
	 * Dismisses the showing repack refusal report.
	 *
	 * @param AtlasRepackReport? showing The report the rendered dialog was showing, or null for whichever shows
	 *   now; see [confirmPending].
	 */
	fun dismissRepackReport(showing: AtlasRepackReport? = null) {
		takeShowing(repackReportQueue, showing)
	}

	/**
	 * The arrival the key ladder routes Escape and Enter to, as an identity token: the head of the first
	 * non-empty queue in the ladder's order (confirm, file-open failure, app-layer alert, export report, repack
	 * refusal report), or null while no modal alert is up.  It changes whenever a different arrival takes the
	 * top - one opening, one closing, or a queued one showing after its predecessor - so the focus reclaim keys
	 * on it: a dismissed dialog can take a focused node (its selectable text) along while the next one shows.
	 */
	val topmostModalAlert: Any?
		get() = topmostArrival()

	/**
	 * The export-options dialog's payload - set by the document.exportOptions command when an
	 * export with options begins, cleared by Cancel, the scrim, Escape, or the Export button (which
	 * first runs the request's continuation).  Null while none shows.
	 */
	var pendingExportOptions: ExportOptionsRequest? by mutableStateOf(null)

	/**
	 * True while an overlay that holds its own focus is open (the palette's search field, the
	 * preferences window's and Quick Setup's popups, the Help dialogs, the export-options dialog's
	 * fields).  While one is up the shell must NOT steal focus; it reclaims when this flips false.
	 */
	val selfFocusedOverlayOpen: Boolean
		get() = paletteVisible || settingsVisible || quickSetupVisible || aboutVisible || creditsVisible || pendingExportOptions != null

	/**
	 * Closes the topmost open self-focused overlay, if any - what Escape does to this family.
	 *
	 * The order is the overlays' stacking order.  It lives here rather than as consecutive
	 * modal-ladder arms, so the flags and the precedence over them cannot drift apart.  It only matters
	 * when two are somehow open at once; with one open, any order closes it.
	 */
	fun closeTopmostSelfFocused() {
		when {
			pendingExportOptions != null -> pendingExportOptions = null
			quickSetupVisible -> quickSetupVisible = false
			settingsVisible -> settingsVisible = false
			aboutVisible -> aboutVisible = false
			creditsVisible -> creditsVisible = false
			paletteVisible -> paletteVisible = false
		}
	}

	/**
	 * True while a modal alert (confirm dialog, file-open failure, an app-layer alert, export report, repack
	 * refusal report) is up or queued.  These do NOT hold their own focus - the shell keeps root focus so their
	 * Escape/Enter route through the modal key ladder.
	 */
	val modalAlertOpen: Boolean
		get() = topmostArrival() != null

	/**
	 * What every modal alert property's setter does: a payload queues behind its kind's, and null dismisses the
	 * showing one.
	 *
	 * @param ModalQueue queue The kind's queue.
	 * @param Any?       value The payload to queue, or null to dismiss.
	 */
	private fun <T : Any> raiseOrDismiss(queue: ModalQueue<T>, value: T?) {
		if (value != null) {
			changingTopmost { queue.enqueue(value) }
		} else {
			takeShowing(queue, showing = null)
		}
	}

	/**
	 * Dequeues the showing payload of one kind, provided it is still the one the caller acted on.
	 *
	 * @param ModalQueue queue   The kind's queue.
	 * @param Any?       showing The payload the caller acted on, or null for whichever shows now.
	 * @return Any? The dequeued payload, or null when nothing was dequeued.
	 */
	private fun <T : Any> takeShowing(queue: ModalQueue<T>, showing: T?): T? = changingTopmost { queue.removeHead(showing) }

	/**
	 * The payload of one kind that shows now, provided it is the one the caller acted on.
	 *
	 * @param ModalQueue queue   The kind's queue.
	 * @param Any?       showing The payload the caller acted on, or null for whichever shows now.
	 * @return Any? The showing payload, or null when none shows or [showing] is no longer it.
	 */
	private fun <T : Any> showingPayload(queue: ModalQueue<T>, showing: T?): T? {
		val current = queue.head?.payload ?: return null

		return if (showing == null || showing === current) current else null
	}

	/**
	 * The arrival the key ladder routes to first: the head of the first non-empty queue, in the ladder's arm
	 * order.  The order here and the arms in handleModalKeyLadder must agree, and ShellModalOverlays paints
	 * in the reverse, so the dialog taking the keys is the one drawn on top.
	 *
	 * @return ModalArrival? The topmost arrival, or null while no modal alert is up.
	 */
	private fun topmostArrival(): ModalArrival<*>? =
		confirmQueue.head
			?: openFailureQueue.head
			?: alertQueue.head
			?: exportReportQueue.head
			?: repackReportQueue.head

	/**
	 * Runs one queue mutation, re-deciding whether Enter is armed when it hands the top to a different arrival.
	 *
	 * Every queue mutation comes through here, so no path can change what the ladder routes Enter to without
	 * this seeing it: a modal that takes the top under a held Enter stays unarmed until that Enter's release.
	 *
	 * @param Function mutation The mutation to run.
	 * @return Any? What the mutation returned.
	 */
	private inline fun <R> changingTopmost(mutation: () -> R): R {
		val topmostBefore = topmostArrival()
		val result = mutation()

		if (topmostArrival() !== topmostBefore) {
			enterArmed = !enterHeld
		}

		return result
	}
}