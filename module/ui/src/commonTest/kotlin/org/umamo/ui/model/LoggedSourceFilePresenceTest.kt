package org.umamo.ui.model

import org.umamo.storage.LogLevel
import org.umamo.storage.UmamoLog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The presence probe's log: one line the first time a path is asked about, one more whenever the
 * answer changes, and nothing for the same answer again - the Sources space and the watcher ask
 * constantly, and a diagnosis needs the path once, not once a second.
 */
class LoggedSourceFilePresenceTest {
	@Test
	fun aPathIsLoggedOnItsFirstAnswerAndOnEveryChange() {
		val path = "/art/presence-${kotlin.random.Random.nextInt()}.psd"
		val uri = "content://art/presence-${kotlin.random.Random.nextInt()}"
		var onDisk: Boolean? = true
		val logged = LoggedSourceFilePresence { probed -> if (probed == path) onDisk else null }

		fun lines(): List<Pair<LogLevel, String>> = UmamoLog.entries.value.filter { entry -> path in entry.message || uri in entry.message }.map { entry -> entry.level to entry.message }

		assertEquals(true, logged.probe(path))
		assertEquals(true, logged.probe(path))
		assertEquals(listOf(LogLevel.Info to "source artwork: found at $path"), lines(), "the same answer again earns no line")
		onDisk = false
		assertEquals(false, logged.probe(path))
		assertEquals(false, logged.probe(path))
		assertEquals(LogLevel.Warn to "source artwork: missing at $path", lines().last(), "a change earns one line, at warn for a missing file")
		assertEquals(2, lines().size)
		onDisk = true
		logged.probe(path)
		assertEquals(3, lines().size, "and coming back earns another")
		assertEquals(null, logged.probe(uri))
		logged.probe(uri)
		assertEquals(listOf(LogLevel.Info to "source artwork: presence unknown at $uri (not a file path this platform can probe)"), lines().filter { (_, message) -> uri in message })
	}
}