package org.umamo.editor.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.ui.app.TransportResponse
import org.umamo.ui.app.UpdateTransport
import java.net.HttpURLConnection
import java.net.URI

/**
 * The system property that points the update check somewhere else - a local server answering with a made-up release -
 * so the notice can be tried by hand.  Only a command line sets it: never application.jvmArgs, so no installed
 * launcher carries it, which the release workflow asserts.
 */
internal const val UPDATE_CHECK_URL_PROPERTY = "umamo.updateCheckUrl"

/** How long the update check waits to connect, and then for an answer, before giving up. */
private const val UPDATE_CHECK_TIMEOUT_MILLIS = 10_000

/**
 * The update check's HTTP GET, over the JDK's HttpURLConnection: it is in java.base, so every bundled runtime has it
 * with no module of its own, and it goes through the operating system's proxy settings (main switches
 * java.net.useSystemProxies on).  The request runs on the IO dispatcher; an error status comes back as an answer,
 * with the body GitHub explains it in, and only a request that got no answer at all throws.
 *
 * @param String? overrideUrl An address that replaces the one asked for, from [UPDATE_CHECK_URL_PROPERTY]; null for
 *   the real one.
 */
internal class HttpUpdateTransport(
	private val overrideUrl: String? = null,
) : UpdateTransport {
	/**
	 * Fetches [url] (or the override) with [headers], following redirects.
	 *
	 * @param String url     The address to fetch.
	 * @param Map    headers The request headers, by name.
	 * @return TransportResponse The status and the body, read as UTF-8.
	 */
	override suspend fun get(
		url: String,
		headers: Map<String, String>,
	): TransportResponse =
		withContext(Dispatchers.IO) {
			val connection = URI(overrideUrl ?: url).toURL().openConnection() as HttpURLConnection

			try {
				connection.requestMethod = "GET"
				connection.connectTimeout = UPDATE_CHECK_TIMEOUT_MILLIS
				connection.readTimeout = UPDATE_CHECK_TIMEOUT_MILLIS
				connection.instanceFollowRedirects = true

				for ((name, value) in headers) {
					connection.setRequestProperty(name, value)
				}

				val status = connection.responseCode
				val stream = if (status in 200..299) connection.inputStream else connection.errorStream
				TransportResponse(status, stream?.use { input -> input.readBytes().toString(Charsets.UTF_8) } ?: "")
			} finally {
				connection.disconnect()
			}
		}
}