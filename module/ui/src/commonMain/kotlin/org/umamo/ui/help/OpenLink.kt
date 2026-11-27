package org.umamo.ui.help

import androidx.compose.ui.platform.UriHandler
import org.umamo.storage.UmamoLog

/**
 * Opens [url] through the platform's handler - the browser on desktop, an intent on Android - failing
 * quietly when the platform refuses: a log line, never a crash.  A link is a convenience, and a machine
 * with no browser registered must not take the editor down for following one.
 *
 * @param String url The URL to open.
 */
internal fun UriHandler.openLinkQuietly(url: String) {
	runCatching { openUri(url) }.onFailure { failure -> UmamoLog.error("could not open $url", failure) }
}