package org.umamo.storage

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests [UmamoLog]'s retained buffer - the in-memory log the editor's Logs panel reads.  Resets the
 * buffer before each case because UmamoLog is an app-lifetime singleton whose state would otherwise leak
 * between tests.
 */
class UmamoLogTest {
	@BeforeTest
	fun reset() {
		UmamoLog.clear()
	}

	@Test
	fun infoRecordsAnInfoEntry() {
		UmamoLog.info("started")

		val entry = UmamoLog.entries.value.single()
		assertEquals(LogLevel.Info, entry.level)
		assertEquals("started", entry.message)
	}

	@Test
	fun warnRecordsAWarnEntry() {
		UmamoLog.warn("odd")

		assertEquals(LogLevel.Warn, UmamoLog.entries.value.single().level)
	}

	@Test
	fun errorWithCauseRecordsTheComposedMessage() {
		UmamoLog.error("could not open", RuntimeException("missing"))

		val entry = UmamoLog.entries.value.single()
		assertEquals(LogLevel.Error, entry.level)
		// The cause is folded into the message exactly as the terminal prints it, so the panel keeps it.
		assertEquals("could not open: missing", entry.message)
	}

	@Test
	fun errorWithoutCauseRecordsTheBareMessage() {
		UmamoLog.error("plain failure")

		assertEquals("plain failure", UmamoLog.entries.value.single().message)
	}

	@Test
	fun bufferCapsAtTheMaximumDroppingOldest() {
		// Append past the documented cap (MAX_LOG_ENTRIES = 2000); the oldest lines fall off the front.
		val cap = 2000
		val overflow = 50
		for (lineIndex in 0 until cap + overflow) {
			UmamoLog.info("line $lineIndex")
		}

		val entries = UmamoLog.entries.value
		assertEquals(cap, entries.size)
		// The first `overflow` lines were dropped: the window now starts at `overflow` and ends at the last.
		assertEquals("line $overflow", entries.first().message)
		assertEquals("line ${cap + overflow - 1}", entries.last().message)
	}
}
