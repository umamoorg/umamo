package org.umamo.ui.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.help.ProjectInfo
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.confirm_update_available
import org.umamo.ui.resources.dialog_later
import org.umamo.ui.resources.dialog_open_download_page
import org.umamo.ui.resources.dialog_skip_this_version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins what the update check makes of GitHub's answers - a newer release, the same or an older one, none published
 * yet, an error status, no answer at all, an unreadable one - and the settings that decide when the check at launch
 * runs and what it stays quiet about.
 */
class UpdateCheckTest {
	/**
	 * A transport that answers every request with one response and remembers what it was asked.
	 *
	 * @property TransportResponse? response The answer, or null to fail with no answer at all.
	 */
	private class FakeTransport(
		private val response: TransportResponse?,
	) : UpdateTransport {
		/** The requests made, as address and headers. */
		val requests = ArrayList<Pair<String, Map<String, String>>>()

		/**
		 * Records the request and answers it.
		 *
		 * @param String url     The address asked for.
		 * @param Map    headers The request headers.
		 * @return TransportResponse The fixed answer.
		 */
		override suspend fun get(
			url: String,
			headers: Map<String, String>,
		): TransportResponse {
			requests.add(url to headers)

			return response ?: throw IllegalStateException("connect timed out")
		}
	}

	/**
	 * GitHub's answer for a release, trimmed to the fields the check reads and one it ignores.
	 *
	 * @param String  tag        The release's tag.
	 * @param Boolean draft      Whether it is a draft.
	 * @param Boolean prerelease Whether it is a prerelease.
	 * @return TransportResponse A 200 answer.
	 */
	private fun release(
		tag: String,
		draft: Boolean = false,
		prerelease: Boolean = false,
	): TransportResponse =
		TransportResponse(
			200,
			"""{"tag_name":"$tag","html_url":"https://github.com/umamoorg/umamo/releases/tag/$tag","draft":$draft,"prerelease":$prerelease,"assets":[]}""",
		)

	/**
	 * Settings over an empty in-memory config directory.
	 *
	 * @return Settings The settings.
	 */
	private fun freshSettings(): Settings {
		val fileSystem = FakeFileSystem()
		val configDirectory = "/config".toPath()
		fileSystem.createDirectories(configDirectory)

		return Settings.load(OkioAppStorage(fileSystem, configDirectory, "/data".toPath()), "{}")
	}

	@Test
	fun aNewerReleaseIsReportedWithDownloadPage() =
		runTest {
			val outcome = checkForUpdate(FakeTransport(release("v0.5.0")), currentVersion = "0.4.0")

			assertEquals(
				UpdateCheckOutcome.Newer(ReleaseVersion(0, 5, 0, null), "0.4.0", "https://www.umamo.org/#download"),
				outcome,
			)
		}

	@Test
	fun theReleaseADevelopmentBuildPrecedesIsNewer() =
		runTest {
			assertIs<UpdateCheckOutcome.Newer>(checkForUpdate(FakeTransport(release("v0.4.0")), currentVersion = "0.4.0-dev"))
		}

	@Test
	fun theSameOrAnOlderReleaseIsUpToDate() =
		runTest {
			assertEquals(UpdateCheckOutcome.UpToDate("0.4.0"), checkForUpdate(FakeTransport(release("v0.4.0")), currentVersion = "0.4.0"))
			assertEquals(UpdateCheckOutcome.UpToDate("0.4.1-dev"), checkForUpdate(FakeTransport(release("v0.4.0")), currentVersion = "0.4.1-dev"))
		}

	@Test
	fun noFullReleaseMeansNothingPublished() =
		runTest {
			val notFound = TransportResponse(404, """{"message":"Not Found"}""")
			assertEquals(UpdateCheckOutcome.NothingPublished, checkForUpdate(FakeTransport(notFound), currentVersion = "0.4.0"))
			assertEquals(UpdateCheckOutcome.NothingPublished, checkForUpdate(FakeTransport(release("v0.9.0", prerelease = true)), currentVersion = "0.4.0"))
			assertEquals(UpdateCheckOutcome.NothingPublished, checkForUpdate(FakeTransport(release("v0.9.0", draft = true)), currentVersion = "0.4.0"))
		}

	@Test
	fun anErrorStatusFailsWithGitHubsMessage() =
		runTest {
			val rateLimited = TransportResponse(403, """{"message":"API rate limit exceeded for 203.0.113.7."}""")

			assertEquals(
				UpdateCheckOutcome.Failed("GitHub answered HTTP 403: API rate limit exceeded for 203.0.113.7.", answered = true),
				checkForUpdate(FakeTransport(rateLimited), currentVersion = "0.4.0"),
			)
			assertEquals(
				UpdateCheckOutcome.Failed("GitHub answered HTTP 502", answered = true),
				checkForUpdate(FakeTransport(TransportResponse(502, "<html>Bad gateway</html>")), currentVersion = "0.4.0"),
			)
		}

	@Test
	fun noAnswerFailsUnanswered() =
		runTest {
			assertEquals(
				UpdateCheckOutcome.Failed("connect timed out", answered = false),
				checkForUpdate(FakeTransport(null), currentVersion = "0.4.0"),
			)
		}

	@Test
	fun anUnreadableAnswerFails() =
		runTest {
			assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(FakeTransport(TransportResponse(200, "not json")), currentVersion = "0.4.0"))
			assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(FakeTransport(release("nightly")), currentVersion = "0.4.0"))
		}

	@Test
	fun theRequestNamesTheAppAndGitHubsApi() =
		runTest {
			val transport = FakeTransport(release("v0.4.0"))

			checkForUpdate(transport, currentVersion = "0.4.0")

			val (url, headers) = transport.requests.single()
			assertEquals(ProjectInfo.LATEST_RELEASE_API_URL, url)
			assertEquals("Umamo/0.4.0", headers["User-Agent"], "GitHub refuses a request without a User-Agent")
			assertEquals("application/vnd.github+json", headers["Accept"])
		}

	@Test
	fun theCheckAtLaunchRunsOnceADayUntilTurnedOff() {
		val settings = freshSettings()
		val now = 1_800_000_000_000L

		assertTrue(updateCheckDue(settings, now), "never checked: a settings file from before the keys existed")
		recordUpdateCheck(UpdateCheckOutcome.NothingPublished, settings, now)
		assertFalse(updateCheckDue(settings, now + UPDATE_CHECK_INTERVAL_MILLIS - 1), "within the day")
		assertTrue(updateCheckDue(settings, now + UPDATE_CHECK_INTERVAL_MILLIS), "a day later")
		assertTrue(updateCheckDue(settings, now - 1), "a clock that went backwards")
		settings.setBoolean(CHECK_FOR_UPDATES_KEY, false)
		assertFalse(updateCheckDue(settings, now + 10 * UPDATE_CHECK_INTERVAL_MILLIS), "turned off")
	}

	@Test
	fun onlyAnAnsweredCheckIsRecorded() {
		val settings = freshSettings()

		recordUpdateCheck(UpdateCheckOutcome.Failed("connect timed out", answered = false), settings, 5_000L)
		assertEquals(null, settings.get(LAST_UPDATE_CHECK_KEY), "no answer: the next launch tries again")
		recordUpdateCheck(UpdateCheckOutcome.Failed("GitHub answered HTTP 403", answered = true), settings, 6_000L)
		assertEquals(JsonPrimitive(6_000L), settings.get(LAST_UPDATE_CHECK_KEY), "a rate limit waits a day")
	}

	@Test
	fun skippingAReleaseSilencesItAndOlderOnes() {
		val settings = freshSettings()
		val newer = UpdateCheckOutcome.Newer(ReleaseVersion(0, 5, 0, null), "0.4.0", "https://example.invalid/0.5.0")
		var opened: String? = null

		val request = updateAvailableRequest(newer, settings) { url -> opened = url }
		assertEquals(Res.string.confirm_update_available, request.message)
		assertEquals(listOf<Any>("0.5.0", "0.4.0"), request.arguments)
		assertEquals(Res.string.dialog_open_download_page, request.confirmLabel)
		assertEquals(Res.string.dialog_later, request.cancelLabel)
		assertEquals(Res.string.dialog_skip_this_version, request.alternative?.label)
		request.onConfirm()
		assertEquals("https://example.invalid/0.5.0", opened)

		assertFalse(updateSkipped(newer, settings))
		checkNotNull(request.alternative).onSelect()
		assertTrue(updateSkipped(newer, settings), "the skipped release")
		assertTrue(updateSkipped(newer.copy(latest = ReleaseVersion(0, 4, 5, null)), settings), "an older one")
		assertFalse(updateSkipped(newer.copy(latest = ReleaseVersion(0, 5, 1, null)), settings), "a newer one is shown again")
	}
}