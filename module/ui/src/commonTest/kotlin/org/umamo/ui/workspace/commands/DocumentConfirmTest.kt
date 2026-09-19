package org.umamo.ui.workspace.commands

import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.confirm_quit_unsaved
import org.umamo.ui.resources.confirm_save_before_quit
import org.umamo.ui.resources.confirm_save_before_replace
import org.umamo.ui.resources.dialog_discard
import org.umamo.ui.resources.dialog_dont_save
import org.umamo.ui.resources.dialog_quit_without_saving
import org.umamo.ui.resources.dialog_save
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.ConfirmAlternative
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ShellOverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the destructive confirms ask and what answering them runs.
 *
 * The prompts guard work that cannot be recovered - a replaced document's unsaved edits, a quit over
 * them - so two things matter beyond the dialog appearing at all.  The button has to name the
 * destructive verb rather than a generic Confirm, since that label is the whole warning once the
 * message has been skimmed; and the action has to run on confirm and only on confirm, which nothing
 * else in the tree checks without a composition.
 */
class DocumentConfirmTest {
	/**
	 * A registry carrying the document table, over the given overlay state.
	 *
	 * @param ShellOverlayState overlays The state the commands raise their requests into.
	 * @return CommandRegistry The registry to dispatch through.
	 */
	private fun registry(overlays: ShellOverlayState): CommandRegistry =
		CommandRegistry().also { registry -> registry.registerAll(documentCommands(overlays)) }

	@Test
	fun confirmExitAsksBeforeQuittingAndRunsTheExitOnConfirm() {
		val overlays = ShellOverlayState()
		var exitCount = 0

		assertTrue(registry(overlays).invoke("document.confirmExit", DirtyDocumentPrompt(discard = { exitCount++ }, save = null)))

		val request = overlays.pendingConfirm
		assertEquals(Res.string.confirm_quit_unsaved, request?.message)
		assertEquals(Res.string.dialog_quit_without_saving, request?.confirmLabel, "with no save possible, the button names what quitting costs")
		assertNull(request?.alternative, "and there is no third choice")
		assertEquals(0, exitCount, "raising the prompt must not quit")

		overlays.confirmPending()

		assertNull(overlays.pendingConfirm)
		assertEquals(1, exitCount)
	}

	@Test
	fun cancellingTheExitPromptLeavesTheAppRunning() {
		val overlays = ShellOverlayState()
		var exitCount = 0
		registry(overlays).invoke("document.confirmExit", DirtyDocumentPrompt(discard = { exitCount++ }, save = null))

		overlays.cancelPending()

		assertNull(overlays.pendingConfirm)
		assertEquals(0, exitCount)
	}

	@Test
	fun confirmReplaceNamesTheDiscard() {
		val overlays = ShellOverlayState()
		var proceedCount = 0
		registry(overlays).invoke("document.confirmReplace", DirtyDocumentPrompt(discard = { proceedCount++ }, save = null))

		assertEquals(Res.string.dialog_discard, overlays.pendingConfirm?.confirmLabel)

		overlays.confirmPending()

		assertEquals(1, proceedCount)
	}

	/**
	 * When the document can be saved, both prompts lead with Save: Enter and the primary button save and go on,
	 * the third choice goes on without saving, and Cancel stays put.  The wording says which act follows.
	 */
	@Test
	fun aSaveablePromptLeadsWithSaveAndOffersDontSave() {
		val overlays = ShellOverlayState()
		var saveCount = 0
		var discardCount = 0
		val prompt = DirtyDocumentPrompt(discard = { discardCount++ }, save = { saveCount++ })

		registry(overlays).invoke("document.confirmExit", prompt)
		val quitRequest = assertNotNull(overlays.pendingConfirm)
		assertEquals(Res.string.confirm_save_before_quit, quitRequest.message)
		assertEquals(Res.string.dialog_save, quitRequest.confirmLabel, "Save is the default")
		assertEquals(Res.string.dialog_dont_save, quitRequest.alternative?.label, "Don't Save is the third choice")
		overlays.confirmPending()
		assertEquals(1, saveCount, "confirming saves")
		assertEquals(0, discardCount)

		registry(overlays).invoke("document.confirmReplace", prompt)
		assertEquals(Res.string.confirm_save_before_replace, overlays.pendingConfirm?.message, "the replace prompt is worded for a replace")
		overlays.choosePendingAlternative()
		assertEquals(1, discardCount, "the third choice goes on without saving")
		assertEquals(1, saveCount)
	}

	@Test
	fun anAlertRoutesIntoTheAlertSlot() {
		val overlays = ShellOverlayState()
		val alert = AlertRequest(Res.string.confirm_quit_unsaved)

		registry(overlays).invoke("document.alert", alert)

		assertSame(alert, overlays.pendingAlert)
	}

	@Test
	fun aReadyBuiltConfirmRoutesIntoTheOneSlotUnchanged() {
		val overlays = ShellOverlayState()
		val request = ConfirmRequest(Res.string.confirm_quit_unsaved) {}

		registry(overlays).invoke("document.confirm", request)

		assertSame(request, overlays.pendingConfirm)
	}

	@Test
	fun theAlternativeRunsAloneAndClearsTheSlot() {
		val overlays = ShellOverlayState()
		var confirmCount = 0
		var alternativeCount = 0
		overlays.pendingConfirm =
			ConfirmRequest(
				message = Res.string.confirm_quit_unsaved,
				alternative = ConfirmAlternative(Res.string.dialog_discard) { alternativeCount++ },
				onConfirm = { confirmCount++ },
			)

		overlays.choosePendingAlternative()

		assertNull(overlays.pendingConfirm)
		assertEquals(1, alternativeCount)
		assertEquals(0, confirmCount, "the third choice is not the confirm")
	}
}