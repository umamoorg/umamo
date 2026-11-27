package org.umamo.ui.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_no_release_published
import org.umamo.ui.resources.alert_up_to_date
import org.umamo.ui.resources.alert_update_check_failed
import org.umamo.ui.resources.confirm_update_available
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.ConfirmRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the rigger is shown.  Help > Check for Updates always answers - the newer release's dialog, or an alert
 * for the newest version, for nothing published, and for a failure.  The check at launch shows only a newer release
 * the rigger has not skipped, stays silent otherwise, and records only an answered check.
 */
class UpdateCheckControllerTest {
	/** The time the tests run at, in milliseconds since the epoch. */
	private val now = 1_800_000_000_000L

	/**
	 * A transport with one fixed answer, or none at all.
	 *
	 * @param TransportResponse? response The answer, or null to fail with no answer.
	 * @return UpdateTransport The transport.
	 */
	private fun answering(response: TransportResponse?): UpdateTransport =
		UpdateTransport { _, _ -> response ?: throw IllegalStateException("network is unreachable") }

	/**
	 * GitHub's answer for a full release.
	 *
	 * @param String tag The release's tag.
	 * @return TransportResponse A 200 answer.
	 */
	private fun release(tag: String): TransportResponse =
		TransportResponse(200, """{"tag_name":"$tag","html_url":"https://github.com/umamoorg/umamo/releases/tag/$tag"}""")

	@Test
	fun aRequestedCheckOffersTheNewerRelease() =
		runTest {
			val fixture = AppControllerFixture(this)

			checkForUpdatesOnRequest(fixture.services, answering(release("v99.0.0")), openPage = {}, nowMillis = { now })
			fixture.settle()

			val request = fixture.argumentsOf("document.confirm").single() as ConfirmRequest
			assertEquals(Res.string.confirm_update_available, request.message)
			assertEquals("99.0.0", request.arguments.first())
			assertEquals(JsonPrimitive(now), fixture.settings.get(LAST_UPDATE_CHECK_KEY))
		}

	@Test
	fun aRequestedCheckAlwaysAnswers() =
		runTest {
			val fixture = AppControllerFixture(this)

			// Each check fetches on a real IO thread, so checks left running together raise their alerts in the
			// order their threads finish.  Settling after each one keeps the alerts in the order asked.
			for (response in listOf(release("v0.0.1"), TransportResponse(404, "{}"), null)) {
				checkForUpdatesOnRequest(fixture.services, answering(response), openPage = {}, nowMillis = { now })
				fixture.settle()
			}

			val alerts = fixture.argumentsOf("document.alert").map { argument -> argument as AlertRequest }
			assertEquals(
				listOf(Res.string.alert_up_to_date, Res.string.alert_no_release_published, Res.string.alert_update_check_failed),
				alerts.map { alert -> alert.message },
			)
			assertEquals(listOf<Any>("network is unreachable"), alerts.last().arguments)
		}

	@Test
	fun aRequestedCheckIgnoresTheSettingAndASkip() =
		runTest {
			val fixture = AppControllerFixture(this)
			fixture.settings.setBoolean(CHECK_FOR_UPDATES_KEY, false)
			fixture.settings.setString(SKIPPED_UPDATE_VERSION_KEY, "99.0.0")

			checkForUpdatesOnRequest(fixture.services, answering(release("v99.0.0")), openPage = {}, nowMillis = { now })
			fixture.settle()

			assertEquals(1, fixture.argumentsOf("document.confirm").size, "the rigger asked")
		}

	@Test
	fun theCheckAtLaunchOffersOnlyAnUnskippedNewerRelease() =
		runTest {
			val fixture = AppControllerFixture(this)

			assertNotNull(updateNoticeAtLaunch(answering(release("v99.0.0")), fixture.settings, {}, { now }))
			fixture.settings.setString(SKIPPED_UPDATE_VERSION_KEY, "99.0.0")
			assertNull(
				updateNoticeAtLaunch(answering(release("v99.0.0")), fixture.settings, {}, { now + UPDATE_CHECK_INTERVAL_MILLIS }),
				"a skipped release",
			)
			assertNotNull(
				updateNoticeAtLaunch(answering(release("v99.0.1")), fixture.settings, {}, { now + 2 * UPDATE_CHECK_INTERVAL_MILLIS }),
				"a newer one than the skipped",
			)
		}

	@Test
	fun theCheckAtLaunchIsSilentWhenNothingIsNewer() =
		runTest {
			val fixture = AppControllerFixture(this)

			assertNull(updateNoticeAtLaunch(answering(release("v0.0.1")), fixture.settings, {}, { now }))
			assertNull(updateNoticeAtLaunch(answering(TransportResponse(404, "{}")), fixture.settings, {}, { now + UPDATE_CHECK_INTERVAL_MILLIS }))
			assertTrue(fixture.invocations.isEmpty(), "a launch never opens with an alert about a check the rigger did not ask for")
		}

	@Test
	fun theCheckAtLaunchRetriesAfterNoAnswerButWaitsADayAfterOne() =
		runTest {
			val fixture = AppControllerFixture(this)

			assertNull(updateNoticeAtLaunch(answering(null), fixture.settings, {}, { now }))
			assertNull(fixture.settings.get(LAST_UPDATE_CHECK_KEY), "offline: the next launch tries again")
			assertNull(updateNoticeAtLaunch(answering(release("v0.0.1")), fixture.settings, {}, { now }))
			assertEquals(JsonPrimitive(now), fixture.settings.get(LAST_UPDATE_CHECK_KEY))
			assertNull(
				updateNoticeAtLaunch(answering(release("v99.0.0")), fixture.settings, {}, { now + 1 }),
				"within the day: not asked at all",
			)
		}
}