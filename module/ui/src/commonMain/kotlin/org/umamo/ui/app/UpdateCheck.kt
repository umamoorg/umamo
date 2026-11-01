package org.umamo.ui.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.umamo.settings.Settings
import org.umamo.ui.help.ProjectInfo
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.confirm_update_available
import org.umamo.ui.resources.dialog_later
import org.umamo.ui.resources.dialog_open_download_page
import org.umamo.ui.resources.dialog_skip_this_version
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.DialogAlternative
import kotlin.coroutines.cancellation.CancellationException

/** Whether Umamo looks for a newer release when it starts; Help > Check for Updates works either way. */
internal const val CHECK_FOR_UPDATES_KEY = "app.checkForUpdates"

/** When GitHub last answered an update check, in milliseconds since the epoch. */
internal const val LAST_UPDATE_CHECK_KEY = "app.lastUpdateCheck"

/** The release the rigger chose to skip: the check at launch says nothing of it, or of anything older. */
internal const val SKIPPED_UPDATE_VERSION_KEY = "app.skippedUpdateVersion"

/** How long the check at launch waits after an answer before asking GitHub again: once a day. */
internal const val UPDATE_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

/**
 * One HTTP GET, performed by the host: the update check's only contact with the network, so everything else about
 * it - the answer's reading, the comparison, what the rigger is shown - is shared and tested without one.  The
 * desktop implements it over the JDK; a host without one passes none and the feature is absent.
 */
fun interface UpdateTransport {
	/**
	 * Fetches [url] with [headers] and returns whatever the server answered, error statuses included.
	 *
	 * @param String url     The address to fetch.
	 * @param Map    headers The request headers, by name.
	 * @return TransportResponse The status and the body, read as UTF-8.
	 * @note Throws when no answer arrived at all: no network, a refused connection, a timeout.
	 */
	suspend fun get(
		url: String,
		headers: Map<String, String>,
	): TransportResponse
}

/**
 * A server's answer to an [UpdateTransport] request.
 *
 * @property Int    status The HTTP status code.
 * @property String body   The response body, UTF-8.
 */
data class TransportResponse(
	val status: Int,
	val body: String,
)

/**
 * A version as Umamo's tags and ProjectInfo.VERSION spell it: MAJOR.MINOR.PATCH, a released version, or with a
 * suffix such as `-dev`, the build master carries before that release (RELEASING.md).  A suffixed version sorts
 * before the same numbers without one, so `0.4.0-dev` < `0.4.0` < `0.4.1-dev`.
 *
 * @property Int     major  The major version.
 * @property Int     minor  The minor version.
 * @property Int     patch  The patch version.
 * @property String? suffix The part after the hyphen, or null for a released version.
 */
internal data class ReleaseVersion(
	val major: Int,
	val minor: Int,
	val patch: Int,
	val suffix: String?,
) : Comparable<ReleaseVersion> {
	/**
	 * Orders by the numbers, then a suffixed version before the released one; two suffixes compare as text.
	 *
	 * @param ReleaseVersion other The version to compare with.
	 * @return Int Negative, zero, or positive, as this version is older, the same, or newer.
	 */
	override fun compareTo(other: ReleaseVersion): Int =
		compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch).takeIf { order -> order != 0 }
			?: when {
				suffix == other.suffix -> 0
				suffix == null -> 1
				other.suffix == null -> -1
				else -> suffix.compareTo(other.suffix)
			}

	/**
	 * The version as Umamo writes it, without a tag's `v`.
	 *
	 * @return String For example `0.4.0` or `0.4.1-dev`.
	 */
	override fun toString(): String = "$major.$minor.$patch" + (suffix?.let { text -> "-$text" } ?: "")

	companion object {
		private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")

		/**
		 * Reads a version or a release tag.
		 *
		 * @param String text A version such as `0.4.0-dev`, or a tag such as `v0.4.0`.
		 * @return ReleaseVersion? The version, or null for anything else.
		 */
		fun parse(text: String): ReleaseVersion? {
			val match = PATTERN.matchEntire(text.trim()) ?: return null
			val (major, minor, patch, suffix) = match.destructured

			return ReleaseVersion(
				major = major.toIntOrNull() ?: return null,
				minor = minor.toIntOrNull() ?: return null,
				patch = patch.toIntOrNull() ?: return null,
				suffix = suffix.ifEmpty { null },
			)
		}
	}
}

/** What an update check found. */
internal sealed interface UpdateCheckOutcome {
	/**
	 * A newer release is published.
	 *
	 * @property ReleaseVersion latest  The newest release.
	 * @property String         current The running version, as ProjectInfo.VERSION spells it.
	 * @property String         pageUrl The page to download it from.
	 */
	data class Newer(
		val latest: ReleaseVersion,
		val current: String,
		val pageUrl: String,
	) : UpdateCheckOutcome

	/**
	 * The running version is the newest release, or newer (a development build).
	 *
	 * @property String current The running version.
	 */
	data class UpToDate(
		val current: String,
	) : UpdateCheckOutcome

	/** GitHub has no full release to compare with yet: every release so far is a draft or a prerelease. */
	data object NothingPublished : UpdateCheckOutcome

	/**
	 * No verdict.
	 *
	 * @property String  reason   Why, in English: a network error, or GitHub's status and message.
	 * @property Boolean answered Whether GitHub answered at all - a rate limit or a server error did, a network
	 *   failure did not - which decides whether the check at launch waits a day before asking again.
	 */
	data class Failed(
		val reason: String,
		val answered: Boolean,
	) : UpdateCheckOutcome
}

/** The fields of GitHub's release object the check reads; everything else is ignored. */
@Serializable
private class LatestReleaseJson(
	@SerialName("tag_name") val tagName: String,
	@SerialName("html_url") val htmlUrl: String? = null,
	val draft: Boolean = false,
	val prerelease: Boolean = false,
)

private val releaseJson = Json { ignoreUnknownKeys = true }

/**
 * The request headers: GitHub's JSON media type and API version, and the User-Agent GitHub requires, naming the app
 * and its version.
 *
 * @param String currentVersion The running version.
 * @return Map The headers, by name.
 */
internal fun updateCheckHeaders(currentVersion: String): Map<String, String> =
	mapOf(
		"Accept" to "application/vnd.github+json",
		"X-GitHub-Api-Version" to "2022-11-28",
		"User-Agent" to "Umamo/$currentVersion",
	)

/**
 * Asks GitHub for the newest full release and compares it with the running version.  Never throws but for
 * cancellation: every failure is an outcome.
 *
 * @param UpdateTransport transport      The host's HTTP GET.
 * @param String          currentVersion The running version.
 * @param String          url            The latest-release endpoint.
 * @return UpdateCheckOutcome What the check found.
 */
internal suspend fun checkForUpdate(
	transport: UpdateTransport,
	currentVersion: String = ProjectInfo.VERSION,
	url: String = ProjectInfo.LATEST_RELEASE_API_URL,
): UpdateCheckOutcome {
	val current =
		ReleaseVersion.parse(currentVersion)
			?: return UpdateCheckOutcome.Failed("this build's version $currentVersion is not a release version", answered = false)
	val response =
		try {
			transport.get(url, updateCheckHeaders(currentVersion))
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (failure: Exception) {
			return UpdateCheckOutcome.Failed(failure.message ?: failure::class.simpleName ?: "no answer", answered = false)
		}

	if (response.status == 404) {
		return UpdateCheckOutcome.NothingPublished
	}

	if (response.status != 200) {
		val message = gitHubMessage(response.body)?.let { text -> ": $text" } ?: ""

		return UpdateCheckOutcome.Failed("GitHub answered HTTP ${response.status}$message", answered = true)
	}

	val release =
		runCatching { releaseJson.decodeFromString(LatestReleaseJson.serializer(), response.body) }.getOrNull()
			?: return UpdateCheckOutcome.Failed("GitHub's answer could not be read", answered = true)

	if (release.draft || release.prerelease) {
		return UpdateCheckOutcome.NothingPublished
	}

	val latest =
		ReleaseVersion.parse(release.tagName)
			?: return UpdateCheckOutcome.Failed("the latest release's tag ${release.tagName} is not a version", answered = true)

	return if (latest > current) {
		UpdateCheckOutcome.Newer(latest, currentVersion, ProjectInfo.DOWNLOAD_URL)
	} else {
		UpdateCheckOutcome.UpToDate(currentVersion)
	}
}

/**
 * The `message` GitHub puts in an error body, such as its rate-limit notice.
 *
 * @param String body The response body.
 * @return String? The message, or null when the body carries none.
 */
private fun gitHubMessage(body: String): String? =
	runCatching {
		releaseJson
			.parseToJsonElement(body)
			.jsonObject["message"]
			?.jsonPrimitive
			?.content
	}.getOrNull()

/**
 * Whether the check at launch is due: the rigger has not turned it off, and GitHub last answered a day or more ago.
 * A settings file from before these keys existed reads as due; so does a clock that went backwards.
 *
 * @param Settings settings   The settings holding [CHECK_FOR_UPDATES_KEY] and [LAST_UPDATE_CHECK_KEY].
 * @param Long     nowMillis  The time now, in milliseconds since the epoch.
 * @return Boolean True when the check should run.
 */
internal fun updateCheckDue(
	settings: Settings,
	nowMillis: Long,
): Boolean {
	if (settings.getBoolean(CHECK_FOR_UPDATES_KEY) == false) {
		return false
	}

	val lastCheck = (settings.get(LAST_UPDATE_CHECK_KEY) as? JsonPrimitive)?.longOrNull ?: 0L

	return nowMillis - lastCheck >= UPDATE_CHECK_INTERVAL_MILLIS || nowMillis < lastCheck
}

/**
 * Records an answered check, so the next check at launch waits a day.  A check that got no answer is not recorded,
 * and the next launch tries again.
 *
 * @param UpdateCheckOutcome outcome   What the check found.
 * @param Settings           settings  The settings to record it in.
 * @param Long               nowMillis The time now, in milliseconds since the epoch.
 */
internal fun recordUpdateCheck(
	outcome: UpdateCheckOutcome,
	settings: Settings,
	nowMillis: Long,
) {
	if (outcome !is UpdateCheckOutcome.Failed || outcome.answered) {
		settings.set(LAST_UPDATE_CHECK_KEY, JsonPrimitive(nowMillis))
	}
}

/**
 * Whether the rigger skipped this release, or a newer one: the check at launch then says nothing of it.
 *
 * @param UpdateCheckOutcome.Newer outcome  The newer release found.
 * @param Settings                 settings The settings holding [SKIPPED_UPDATE_VERSION_KEY].
 * @return Boolean True when the release is skipped.
 */
internal fun updateSkipped(
	outcome: UpdateCheckOutcome.Newer,
	settings: Settings,
): Boolean {
	val skipped = settings.getString(SKIPPED_UPDATE_VERSION_KEY)?.let(ReleaseVersion::parse) ?: return false

	return outcome.latest <= skipped
}

/**
 * The dialog for a newer release: Open Download Page, Later - which asks again at the next check - and Skip This
 * Version, which silences the check at launch until a newer one appears.
 *
 * @param UpdateCheckOutcome.Newer outcome  The newer release found.
 * @param Settings                 settings The settings Skip This Version writes.
 * @param Function                 openPage Opens a page in the browser.
 * @return ConfirmRequest The dialog to raise through document.confirm.
 */
internal fun updateAvailableRequest(
	outcome: UpdateCheckOutcome.Newer,
	settings: Settings,
	openPage: (String) -> Unit,
): ConfirmRequest =
	ConfirmRequest(
		message = Res.string.confirm_update_available,
		arguments = listOf(outcome.latest.toString(), outcome.current),
		confirmLabel = Res.string.dialog_open_download_page,
		cancelLabel = Res.string.dialog_later,
		alternative =
			DialogAlternative(Res.string.dialog_skip_this_version) {
				settings.setString(SKIPPED_UPDATE_VERSION_KEY, outcome.latest.toString())
			},
		onConfirm = { openPage(outcome.pageUrl) },
	)