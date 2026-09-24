package org.umamo.storage

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit-tests [UmamoLog]'s retained buffer - the in-memory log the editor's Logs panel reads - and its sinks.
 * Resets the buffer before each case, and detaches every sink it attaches, because UmamoLog is an
 * app-lifetime singleton whose state would otherwise leak between tests.
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

	@Test
	fun aSinkReceivesEachLevelWithTheRetainedMessageAndTheCause() {
		val received = ArrayList<LogRecord>()
		val detach = UmamoLog.addSink { logged -> received.add(logged) }
		val failure = RuntimeException("missing")
		try {
			UmamoLog.info("started")
			UmamoLog.warn("odd")
			UmamoLog.error("could not open", failure)
		} finally {
			detach()
		}

		assertEquals(listOf(LogLevel.Info, LogLevel.Warn, LogLevel.Error), received.map { logged -> logged.level })
		// The sink sees exactly the text the buffer keeps, so the file and the Logs panel agree.
		assertEquals(UmamoLog.entries.value.map { entry -> entry.message }, received.map { logged -> logged.message })
		assertNull(received[0].cause)
		// The cause itself travels to the sink, which is where its stack trace survives.
		assertSame(failure, received[2].cause)
	}

	@Test
	fun aDetachedSinkReceivesNothingFurther() {
		val received = ArrayList<LogRecord>()
		val detach = UmamoLog.addSink { logged -> received.add(logged) }
		try {
			UmamoLog.info("before")
		} finally {
			detach()
		}
		UmamoLog.info("after")

		assertEquals(listOf("before"), received.map { logged -> logged.message })
		assertEquals(2, UmamoLog.entries.value.size, "the buffer still takes every line")
	}
}