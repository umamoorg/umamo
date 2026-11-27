package org.umamo.ui.kit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The press-to-press timing every double-click in the editor shares: the outliner rows, the parameter
 * names and group headers, and the workspace tabs.
 */
class DoubleClickTrackerTest {
	/** A press inside the window after an unmodified press completes a double click; the edge counts. */
	@Test
	fun aSecondPressInsideTheWindowPairs() {
		val tracker = DoubleClickTracker(windowMillis = 300L)
		assertFalse(tracker.registerPress(10_000L), "the first press only arms")
		assertTrue(tracker.registerPress(10_120L), "a press 120ms later is the second half")

		val edgeTracker = DoubleClickTracker(windowMillis = 300L)
		edgeTracker.registerPress(10_000L)
		assertTrue(edgeTracker.registerPress(10_300L), "the window is inclusive")
	}

	/** A press past the window does not pair, and arms in its own right. */
	@Test
	fun aLatePressArmsAgain() {
		val tracker = DoubleClickTracker(windowMillis = 300L)
		tracker.registerPress(10_000L)
		assertFalse(tracker.registerPress(10_301L), "one millisecond late is a new single")
		assertTrue(tracker.registerPress(10_400L), "and that single arms the next double")
	}

	/** A completed double clears the pending press, so a third quick press is a fresh single. */
	@Test
	fun aThirdQuickPressStartsFresh() {
		val tracker = DoubleClickTracker(windowMillis = 300L)
		tracker.registerPress(10_000L)
		assertTrue(tracker.registerPress(10_100L))
		assertFalse(tracker.registerPress(10_200L), "a triple click is a double, then a single")
	}

	/**
	 * A modified press never takes part in a double click, on either side of the pair.  Ctrl-clicking a row
	 * twice toggles it in and out, and a plain click right after a Ctrl-click selects rather than renames.
	 */
	@Test
	fun aModifiedPressNeverPairs() {
		val secondModified = DoubleClickTracker(windowMillis = 300L)
		secondModified.registerPress(10_000L)
		assertFalse(secondModified.registerPress(10_100L, modified = true), "a modified second press")

		val firstModified = DoubleClickTracker(windowMillis = 300L)
		firstModified.registerPress(10_000L, modified = true)
		assertFalse(firstModified.registerPress(10_100L), "a plain press right after a modified one")
		assertTrue(firstModified.registerPress(10_200L), "which arms normally itself")

		val bothModified = DoubleClickTracker(windowMillis = 300L)
		bothModified.registerPress(10_000L, modified = true)
		assertFalse(bothModified.registerPress(10_100L, modified = true), "two modified presses")
	}

	/** Reset forgets the pending press. */
	@Test
	fun resetForgetsThePendingPress() {
		val tracker = DoubleClickTracker(windowMillis = 300L)
		tracker.registerPress(10_000L)
		tracker.reset()
		assertFalse(tracker.registerPress(10_100L))
	}

	/** The very first press is never a double, however early in the clock it lands. */
	@Test
	fun anEarlyFirstPressIsNotADouble() {
		val tracker = DoubleClickTracker(windowMillis = 300L)
		assertFalse(tracker.registerPress(0L))

		val nearZero = DoubleClickTracker(windowMillis = 300L)
		assertFalse(nearZero.registerPress(100L))
	}
}