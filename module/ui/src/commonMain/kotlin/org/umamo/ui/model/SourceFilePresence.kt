package org.umamo.ui.model

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.storage.UmamoLog
import kotlin.concurrent.Volatile

/**
 * Whether an artwork file is still where the document last read it: true when present, false when
 * missing, null when the platform cannot say (a content uri, a path it will not probe).
 *
 * @param String path The advisory path the model recorded.
 * @return Boolean? The answer, or null for unknown.
 */
typealias SourceFilePresence = (path: String) -> Boolean?

/**
 * The app's file-presence probe for the Sources space, or null on a platform without one.  A probe
 * that answers null for a path leaves that source's status Unknown; without a probe every source
 * reads Unknown, never Missing - the space must not accuse a file it cannot check.
 */
val LocalSourceFilePresence = staticCompositionLocalOf<SourceFilePresence?> { null }

/**
 * The presence probe with a log line the first time a path is asked about and whenever its answer
 * changes - found, missing, or unknowable - so a document whose recorded path is wrong shows the
 * exact path being checked.  The Sources space asks on every refresh and the watcher every second,
 * so only a change earns a line.  Shared across documents for the app's life, like the probe itself.
 *
 * @property SourceFilePresence answers The probe the answers come from.
 */
internal class LoggedSourceFilePresence(
	private val answers: SourceFilePresence,
) {
	/** The last answer per path; copy-on-write, since the watcher asks off the UI thread. */
	@Volatile
	private var lastAnswerByPath: Map<String, Boolean?> = emptyMap()

	/**
	 * Answers for [path], logging when the answer is new.
	 *
	 * @param String path The advisory path the model recorded.
	 * @return Boolean? The answer: present, missing, or null for unknown.
	 */
	fun probe(path: String): Boolean? {
		val answer = answers(path)
		val known = lastAnswerByPath
		if (path !in known || known[path] != answer) {
			lastAnswerByPath = known + (path to answer)
			when (answer) {
				true -> UmamoLog.info("source artwork: found at $path")
				false -> UmamoLog.warn("source artwork: missing at $path")
				null -> UmamoLog.info("source artwork: presence unknown at $path (not a file path this platform can probe)")
			}
		}
		return answer
	}
}