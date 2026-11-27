package org.umamo.ui.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.settings.Settings
import org.umamo.storage.UmamoLog
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_no_release_published
import org.umamo.ui.resources.alert_up_to_date
import org.umamo.ui.resources.alert_update_check_failed
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.ConfirmRequest

/**
 * Help > Check for Updates: asks GitHub whatever the setting, the day, or a skipped release say, and always answers -
 * the newer release's dialog, or an alert saying the running version is the newest, that nothing is published yet,
 * or why the check failed.  The request runs off the UI thread.
 *
 * @param EditorAppServices services  The app's shared collaborators: the scope the check runs in, the settings it
 *   records itself in, and the registry its dialogs go through.
 * @param UpdateTransport   transport The host's HTTP GET.
 * @param Function          openPage  Opens a page in the browser.
 * @param Function          nowMillis The time now, in milliseconds since the epoch.
 */
internal fun checkForUpdatesOnRequest(
	services: EditorAppServices,
	transport: UpdateTransport,
	openPage: (String) -> Unit,
	nowMillis: () -> Long,
) {
	services.scope.launch {
		val outcome = withContext(Dispatchers.IO) { checkForUpdate(transport) }
		logUpdateOutcome("requested", outcome)
		recordUpdateCheck(outcome, services.settings, nowMillis())

		when (outcome) {
			is UpdateCheckOutcome.Newer -> {
				services.commandRegistry.invoke("document.confirm", updateAvailableRequest(outcome, services.settings, openPage))
			}

			is UpdateCheckOutcome.UpToDate -> {
				services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_up_to_date, listOf(outcome.current)))
			}

			UpdateCheckOutcome.NothingPublished -> {
				services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_no_release_published))
			}

			is UpdateCheckOutcome.Failed -> {
				services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_update_check_failed, listOf(outcome.reason)))
			}
		}
	}
}

/**
 * The check at launch, when it is due: the newer release's dialog, unless the rigger skipped it.  Anything else -
 * nothing newer, nothing published, no network - is only logged: a launch never opens with a dialog about a check
 * the rigger did not ask for.  An answered check is recorded, so the next one waits a day.
 *
 * @param UpdateTransport transport The host's HTTP GET.
 * @param Settings        settings  The settings the check reads and records itself in.
 * @param Function        openPage  Opens a page in the browser.
 * @param Function        nowMillis The time now, in milliseconds since the epoch.
 * @return ConfirmRequest? The dialog to raise, or null for nothing to show.
 */
internal suspend fun updateNoticeAtLaunch(
	transport: UpdateTransport,
	settings: Settings,
	openPage: (String) -> Unit,
	nowMillis: () -> Long,
): ConfirmRequest? {
	if (!updateCheckDue(settings, nowMillis())) {
		return null
	}

	val outcome = withContext(Dispatchers.IO) { checkForUpdate(transport) }
	logUpdateOutcome("at launch", outcome)
	recordUpdateCheck(outcome, settings, nowMillis())

	if (outcome !is UpdateCheckOutcome.Newer) {
		return null
	}

	if (updateSkipped(outcome, settings)) {
		UmamoLog.info("update check (at launch): ${outcome.latest} was skipped, not shown")

		return null
	}

	return updateAvailableRequest(outcome, settings, openPage)
}

/**
 * Writes an update check's outcome to the session log.
 *
 * @param String             trigger What started the check, for the log line.
 * @param UpdateCheckOutcome outcome What the check found.
 */
private fun logUpdateOutcome(
	trigger: String,
	outcome: UpdateCheckOutcome,
) {
	when (outcome) {
		is UpdateCheckOutcome.Newer -> UmamoLog.info("update check ($trigger): ${outcome.latest} is available, running ${outcome.current}")
		is UpdateCheckOutcome.UpToDate -> UmamoLog.info("update check ($trigger): ${outcome.current} is the newest release")
		UpdateCheckOutcome.NothingPublished -> UmamoLog.info("update check ($trigger): no full release is published yet")
		is UpdateCheckOutcome.Failed -> UmamoLog.warn("update check ($trigger) failed: ${outcome.reason}")
	}
}