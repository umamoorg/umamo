package org.umamo.ui.workspace

import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportReport
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.model.repack.AtlasRepackReport
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.resources.confirm_quit_unsaved
import org.umamo.ui.resources.dialog_discard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the modal alerts' queues: one of each kind shows at a time, and a later arrival of that kind waits behind
 * it in arrival order rather than replacing it.
 *
 * Exports and saves finish in the background, so an overwrite confirm or a failure alert can land while one of
 * its kind is still unread.  A replaced prompt is one the rigger never saw, and the Enter meant for it answers
 * the replacement - so what matters is that nothing is lost, nothing jumps the line, and a click drawn for one
 * arrival never answers the next.
 */
class ShellOverlayQueueTest {
	/**
	 * An alert whose arguments tell it apart, so two of them are never equal.
	 *
	 * @param String name The distinguishing argument.
	 * @return AlertRequest The alert.
	 */
	private fun alert(name: String): AlertRequest = AlertRequest(Res.string.cmd_mesh_grab, listOf(name))

	/**
	 * A confirmation that records its runs into [log] under [name].
	 *
	 * @param String              name The name it records.
	 * @param MutableList<String> log  The shared run log.
	 * @return ConfirmRequest The confirmation.
	 */
	private fun confirm(name: String, log: MutableList<String>): ConfirmRequest = ConfirmRequest(Res.string.cmd_mesh_grab, listOf(name)) { log.add(name) }

	/**
	 * A second alert waits behind the first, and shows once the first is acknowledged.
	 */
	@Test
	fun aSecondAlertWaitsBehindTheFirstAndShowsOnceItIsDismissed() {
		val overlays = ShellOverlayState()
		val first = alert("first")
		val second = alert("second")

		overlays.pendingAlert = first
		overlays.pendingAlert = second

		assertSame(first, overlays.pendingAlert, "the second does not replace the first")
		overlays.dismissAlert()
		assertSame(second, overlays.pendingAlert, "it shows once the first is dismissed")
		overlays.dismissAlert()
		assertNull(overlays.pendingAlert)
	}

	/**
	 * A confirmation waits behind another, and confirming the first runs only the first.
	 */
	@Test
	fun aConfirmWaitsBehindAConfirm() {
		val overlays = ShellOverlayState()
		val runs = ArrayList<String>()
		val first = confirm("first", runs)
		val second = confirm("second", runs)

		overlays.pendingConfirm = first
		overlays.pendingConfirm = second

		assertSame(first, overlays.pendingConfirm, "the second does not replace the first")
		overlays.confirmPending()
		assertEquals(listOf("first"), runs, "confirming answers the one showing")
		assertSame(second, overlays.pendingConfirm, "and the second shows next")
		overlays.cancelPending()
		assertEquals(listOf("first"), runs, "cancelling it runs nothing")
		assertNull(overlays.pendingConfirm)
	}

	/**
	 * Setting a kind's property to null dismisses only the arrival showing, never the ones waiting behind it.
	 */
	@Test
	fun settingNullDismissesOnlyTheHead() {
		val overlays = ShellOverlayState()
		val waiting = alert("waiting")
		overlays.pendingAlert = alert("showing")
		overlays.pendingAlert = waiting

		overlays.pendingAlert = null

		assertSame(waiting, overlays.pendingAlert)
	}

	/**
	 * The file-open failure, export report, and repack report queue the same way.
	 */
	@Test
	fun theReportKindsQueueTheSameWay() {
		val overlays = ShellOverlayState()
		val firstFailure = DocumentOpenFailure(DocumentOpenError.ReadFailed, "first.cmo3")
		val secondFailure = DocumentOpenFailure(DocumentOpenError.Unrecognized, "second.cmo3")
		val firstExport = ExportReport(ExportFormat.Cmo3, emptyList())
		val secondExport = ExportReport(ExportFormat.Moc3, emptyList())
		val firstRepack = AtlasRepackReport(emptyList())
		val secondRepack = AtlasRepackReport(emptyList())

		overlays.openFailure = firstFailure
		overlays.openFailure = secondFailure
		overlays.exportReport = firstExport
		overlays.exportReport = secondExport
		overlays.repackReport = firstRepack
		overlays.repackReport = secondRepack

		assertSame(firstFailure, overlays.openFailure)
		assertSame(firstExport, overlays.exportReport)
		assertSame(firstRepack, overlays.repackReport)
		overlays.dismissOpenFailure()
		overlays.dismissExportReport()
		overlays.dismissRepackReport()
		assertSame(secondFailure, overlays.openFailure)
		assertSame(secondExport, overlays.exportReport)
		assertSame(secondRepack, overlays.repackReport)
	}

	/**
	 * A confirmation raised by the showing confirmation's own action queues behind those already waiting, rather
	 * than jumping the line or being dismissed along with the one that raised it.
	 */
	@Test
	fun aConfirmRaisedByTheHeadsActionQueuesBehindThoseWaiting() {
		val overlays = ShellOverlayState()
		val runs = ArrayList<String>()
		val waiting = confirm("waiting", runs)
		val followUp = confirm("follow-up", runs)
		overlays.pendingConfirm =
			ConfirmRequest(Res.string.cmd_mesh_grab) {
				runs.add("head")
				overlays.pendingConfirm = followUp
			}
		overlays.pendingConfirm = waiting

		overlays.confirmPending()

		assertEquals(listOf("head"), runs)
		assertSame(waiting, overlays.pendingConfirm, "the one already waiting shows next")
		overlays.confirmPending()
		assertSame(followUp, overlays.pendingConfirm, "and the follow-up after it")
	}

	/**
	 * An alert raised by a confirmation's action - a save that failed - queues behind the alerts already waiting.
	 */
	@Test
	fun anAlertRaisedByAConfirmsActionQueuesBehindTheAlertsWaiting() {
		val overlays = ShellOverlayState()
		val waitingAlert = alert("waiting")
		val raisedAlert = alert("raised")
		overlays.pendingAlert = waitingAlert
		overlays.pendingConfirm = ConfirmRequest(Res.string.cmd_mesh_grab) { overlays.pendingAlert = raisedAlert }

		overlays.confirmPending()

		assertNull(overlays.pendingConfirm)
		assertSame(waitingAlert, overlays.pendingAlert)
		overlays.dismissAlert()
		assertSame(raisedAlert, overlays.pendingAlert)
	}

	/**
	 * An alternative's own follow-up queues behind those waiting too: the choice leaves the queue before it runs.
	 */
	@Test
	fun anAlternativeLeavesTheQueueBeforeItRuns() {
		val overlays = ShellOverlayState()
		val runs = ArrayList<String>()
		val waiting = confirm("waiting", runs)
		val followUp = confirm("follow-up", runs)
		overlays.pendingConfirm =
			ConfirmRequest(
				message = Res.string.confirm_quit_unsaved,
				alternative =
					DialogAlternative(Res.string.dialog_discard) {
						runs.add("alternative")
						overlays.pendingConfirm = followUp
					},
				onConfirm = { runs.add("head") },
			)
		overlays.pendingConfirm = waiting

		overlays.choosePendingAlternative()

		assertEquals(listOf("alternative"), runs)
		assertSame(waiting, overlays.pendingConfirm)
		overlays.cancelPending()
		assertSame(followUp, overlays.pendingConfirm)
	}

	/**
	 * A dismissal or answer that names an arrival no longer showing does nothing - the click a dialog drew for
	 * one arrival, landing after that one was answered, never answers the next.
	 */
	@Test
	fun aStaleIdentityActsOnNothing() {
		val overlays = ShellOverlayState()
		val runs = ArrayList<String>()
		val answered = confirm("answered", runs)
		val showing =
			ConfirmRequest(
				message = Res.string.cmd_mesh_grab,
				alternative = DialogAlternative(Res.string.dialog_discard) { runs.add("alternative") },
				onConfirm = { runs.add("showing") },
			)
		overlays.pendingConfirm = answered
		overlays.pendingConfirm = showing
		overlays.cancelPending(answered)
		assertSame(showing, overlays.pendingConfirm, "fixture: the answered one has gone")

		overlays.confirmPending(answered)
		overlays.cancelPending(answered)
		overlays.choosePendingAlternative(answered)

		assertSame(showing, overlays.pendingConfirm, "a stale click leaves the showing confirmation up")
		assertEquals(emptyList(), runs, "and runs nothing")

		overlays.confirmPending(showing)
		assertEquals(listOf("showing"), runs, "a click that names the showing one answers it")
	}

	/**
	 * The alert kinds' identity-scoped dismissals ignore a stale arrival the same way.
	 */
	@Test
	fun aStaleIdentityDismissesNoAlert() {
		val overlays = ShellOverlayState()
		var alternativeRuns = 0
		val staleAlert = alert("stale")
		val showingAlert = AlertRequest(Res.string.cmd_mesh_grab, listOf("showing"), DialogAlternative(Res.string.dialog_discard) { alternativeRuns++ })
		val staleFailure = DocumentOpenFailure(DocumentOpenError.ReadFailed, "stale.cmo3")
		val showingFailure = DocumentOpenFailure(DocumentOpenError.ReadFailed, "showing.cmo3")
		val staleExport = ExportReport(ExportFormat.Cmo3, emptyList())
		val showingExport = ExportReport(ExportFormat.Moc3, emptyList())
		val staleRepack = AtlasRepackReport(emptyList())
		val showingRepack = AtlasRepackReport(emptyList())
		overlays.pendingAlert = showingAlert
		overlays.openFailure = showingFailure
		overlays.exportReport = showingExport
		overlays.repackReport = showingRepack

		overlays.dismissAlert(staleAlert)
		overlays.chooseAlertAlternative(staleAlert)
		overlays.dismissOpenFailure(staleFailure)
		overlays.dismissExportReport(staleExport)
		overlays.dismissRepackReport(staleRepack)

		assertSame(showingAlert, overlays.pendingAlert)
		assertEquals(0, alternativeRuns)
		assertSame(showingFailure, overlays.openFailure)
		assertSame(showingExport, overlays.exportReport)
		assertSame(showingRepack, overlays.repackReport)

		overlays.dismissAlert(showingAlert)
		overlays.dismissOpenFailure(showingFailure)
		overlays.dismissExportReport(showingExport)
		overlays.dismissRepackReport(showingRepack)
		assertFalse(overlays.modalAlertOpen, "naming the showing arrival dismisses it")
	}

	/**
	 * modalAlertOpen holds while anything is queued, and drops only once every queue has drained.
	 */
	@Test
	fun modalAlertOpenStaysTrueWhileAnythingIsQueued() {
		val overlays = ShellOverlayState()
		assertFalse(overlays.modalAlertOpen)

		overlays.pendingAlert = alert("first")
		overlays.pendingAlert = alert("second")
		overlays.repackReport = AtlasRepackReport(emptyList())

		overlays.dismissAlert()
		assertTrue(overlays.modalAlertOpen, "the second alert is still queued")
		overlays.dismissAlert()
		assertTrue(overlays.modalAlertOpen, "the repack report is still queued")
		overlays.dismissRepackReport()
		assertFalse(overlays.modalAlertOpen)
	}

	/**
	 * The topmost modal changes hands when a queued arrival of the same kind shows after its predecessor, even an
	 * equal one - the focus reclaim keys on it, since a dismissed dialog can take its focused text along.
	 */
	@Test
	fun theTopmostModalChangesHandsOnASameKindHandoff() {
		val overlays = ShellOverlayState()
		assertNull(overlays.topmostModalAlert)
		val request = alert("same")
		overlays.pendingAlert = request
		overlays.pendingAlert = request
		val firstArrival = assertNotNull(overlays.topmostModalAlert)

		overlays.dismissAlert()

		assertSame(request, overlays.pendingAlert, "the same request raised twice shows twice")
		assertNotSame(firstArrival, overlays.topmostModalAlert, "and its second arrival is a new topmost")
		overlays.dismissAlert()
		assertNull(overlays.topmostModalAlert)
	}

	/**
	 * The topmost modal follows the key ladder's order: a confirm outranks every alert, and the file-open failure
	 * outranks the app-layer alert, which outranks the export report, which outranks the repack report.
	 */
	@Test
	fun theTopmostModalFollowsTheLaddersOrder() {
		val overlays = ShellOverlayState()
		overlays.repackReport = AtlasRepackReport(emptyList())
		val repackArrival = overlays.topmostModalAlert
		overlays.exportReport = ExportReport(ExportFormat.Cmo3, emptyList())
		val exportArrival = overlays.topmostModalAlert
		overlays.pendingAlert = alert("alert")
		val alertArrival = overlays.topmostModalAlert
		overlays.openFailure = DocumentOpenFailure(DocumentOpenError.ReadFailed, "model.cmo3")
		val failureArrival = overlays.topmostModalAlert
		overlays.pendingConfirm = ConfirmRequest(Res.string.cmd_mesh_grab) {}

		val arrivals = listOf(repackArrival, exportArrival, alertArrival, failureArrival, overlays.topmostModalAlert)
		assertEquals(arrivals.size, arrivals.distinct().size, "each higher kind takes the top as it arrives")

		overlays.cancelPending()
		assertSame(failureArrival, overlays.topmostModalAlert)
		overlays.dismissOpenFailure()
		assertSame(alertArrival, overlays.topmostModalAlert)
		overlays.dismissAlert()
		assertSame(exportArrival, overlays.topmostModalAlert)
		overlays.dismissExportReport()
		assertSame(repackArrival, overlays.topmostModalAlert)
	}

	/**
	 * An alert the app raises through the shell that a document swap is replacing shows in the shell that
	 * replaces it: a read-only document says so right after it is swapped in, and an export's report lands just
	 * as a replace that waited for the export goes ahead.  Confirmations stay with their shell.
	 */
	@Test
	fun theAppsAlertsSurviveADocumentSwap() {
		val appAlerts = AppAlertQueues()
		val outgoing = ShellOverlayState(appAlerts = appAlerts)
		val readOnly = alert("read-only")
		val report = ExportReport(ExportFormat.Cmo3, emptyList())
		val failure = DocumentOpenFailure(DocumentOpenError.Unrecognized, "notes.bin")
		outgoing.pendingAlert = readOnly
		outgoing.exportReport = report
		outgoing.openFailure = failure
		outgoing.pendingConfirm = confirm("replace", mutableListOf())

		val incoming = ShellOverlayState(appAlerts = appAlerts)

		assertSame(readOnly, incoming.pendingAlert)
		assertSame(report, incoming.exportReport)
		assertSame(failure, incoming.openFailure)
		assertNull(incoming.pendingConfirm, "a confirmation acts on its own shell's document, so it goes with that shell")
	}
}