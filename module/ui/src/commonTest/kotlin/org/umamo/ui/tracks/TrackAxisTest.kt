package org.umamo.ui.tracks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the track sheet's domain model - the one place a horizontal domain appears, and therefore the whole
 * reason the keyform sheet and a future time-based dope sheet can share the same widgets.
 */
class TrackAxisTest {
	/** A value maps to its proportional position, and the mapping inverts. */
	@Test
	fun mapsAndInvertsAcrossTheDomain() {
		val axis = TrackAxis(-30f, 30f)
		assertEquals(0f, axis.fractionOf(-30f))
		assertEquals(0.5f, axis.fractionOf(0f))
		assertEquals(1f, axis.fractionOf(30f))
		assertEquals(0f, axis.valueAt(0.5f))
		assertEquals(-30f, axis.valueAt(0f))
	}

	/**
	 * A degenerate axis maps everything to the middle rather than dividing by zero.
	 *
	 * A parameter whose min equals its max is not animatable but still renders a row, and a NaN sweeping
	 * through the layout would take the whole panel with it.
	 */
	@Test
	fun aDegenerateAxisDoesNotDivideByZero() {
		val axis = TrackAxis(5f, 5f)
		assertEquals(0.5f, axis.fractionOf(5f))
		assertEquals(0.5f, axis.fractionOf(999f))
		assertTrue(axis.fractionOf(0f).isFinite())
	}

	/** An out-of-range value maps outside 0..1 rather than being clamped, so it stays detectable. */
	@Test
	fun outOfRangeStaysOutOfRange() {
		val axis = TrackAxis(0f, 10f)
		assertTrue(axis.fractionOf(-5f) < 0f)
		assertTrue(axis.fractionOf(15f) > 1f)
	}

	/** Ticks land on human-readable steps that scale with the domain, not a fixed count. */
	@Test
	fun ticksUseAReadableStep() {
		assertEquals(listOf(-30f, -20f, -10f, 0f, 10f, 20f, 30f), TrackAxis(-30f, 30f).ticks())
		assertEquals(listOf(-1f, -0.5f, 0f, 0.5f, 1f), TrackAxis(-1f, 1f).ticks())
	}

	/** A degenerate axis yields one tick instead of spinning. */
	@Test
	fun degenerateTicksTerminate() {
		assertEquals(listOf(5f), TrackAxis(5f, 5f).ticks())
	}

	/** The nearest mark within tolerance wins; nothing outside it is picked. */
	@Test
	fun picksTheNearestMarkInRange() {
		val marks = listOf(TrackKeyMark(0, 0f), TrackKeyMark(1, 10f), TrackKeyMark(2, 20f))
		assertEquals(10f, assertNotNull(nearestMark(marks, 11f, tolerance = 2f)).position)
		assertNull(nearestMark(marks, 15f, tolerance = 2f), "a click between marks picks nothing")
		assertEquals(20f, assertNotNull(nearestMark(marks, 100f, tolerance = 1000f)).position, "a wide tolerance still picks the nearest")
	}

	/** A collapsed subtree is not walked; expanding one parent reveals only its own children. */
	@Test
	fun flatteningDescendsOnlyIntoExpandedRows() {
		val tree =
			listOf(
				TrackRow(
					key = "owner",
					label = "Owner",
					children =
						listOf(
							TrackRow(key = "owner/a", label = "A"),
							TrackRow(key = "owner/b", label = "B", children = listOf(TrackRow(key = "owner/b/1", label = "B1"))),
						),
				),
				TrackRow(key = "other", label = "Other"),
			)
		assertEquals(listOf("owner", "other"), flattenTrackRows(tree, emptySet()).map { line -> line.row.key })
		assertEquals(
			listOf("owner", "owner/a", "owner/b", "other"),
			flattenTrackRows(tree, setOf("owner")).map { line -> line.row.key },
			"expanding a parent must not expand its children too",
		)
		assertEquals(
			listOf("owner", "owner/a", "owner/b", "owner/b/1", "other"),
			flattenTrackRows(tree, setOf("owner", "owner/b")).map { line -> line.row.key },
		)
	}

	/** Depth and the chevron flags come from the tree, not from the caller. */
	@Test
	fun flatteningReportsDepthAndExpandability() {
		val tree = listOf(TrackRow(key = "owner", label = "Owner", children = listOf(TrackRow(key = "leaf", label = "Leaf"))))
		val collapsed = flattenTrackRows(tree, emptySet()).single()
		assertEquals(0, collapsed.depth)
		assertTrue(collapsed.expandable, "a row with children is expandable")
		assertTrue(!collapsed.expanded)
		val expanded = flattenTrackRows(tree, setOf("owner"))
		assertEquals(1, expanded[1].depth)
		assertTrue(!expanded[1].expandable, "a leaf has no chevron")
	}

	/** A collapsed group still shows where its subtree's keys are, deduplicated and ordered. */
	@Test
	fun summaryUnionsTheSubtreeMarks() {
		val row =
			TrackRow(
				key = "owner",
				label = "Owner",
				marks = listOf(TrackKeyMark(0, 30f)),
				children =
					listOf(
						TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0, 0f), TrackKeyMark(1, 30f))),
						TrackRow(key = "b", label = "B", marks = listOf(TrackKeyMark(0, -30f), TrackKeyMark(1, 0f))),
					),
			)
		assertEquals(listOf(-30f, 0f, 30f), summarizedMarks(row).map { mark -> mark.position })
	}

	/**
	 * Summary marks are renumbered to their ordinal within the summary.
	 *
	 * The winner's own ordinal is meaningless across the group - two channels keyed at one value can hold
	 * different ordinals - whereas a summary ordinal is a stable name the owner maps back to the whole set
	 * of keys the mark stands for.
	 */
	@Test
	fun summaryMarksAreRenumberedByPosition() {
		val row =
			TrackRow(
				key = "owner",
				label = "Owner",
				children =
					listOf(
						TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0, 30f), TrackKeyMark(1, 0f))),
						TrackRow(key = "b", label = "B", marks = listOf(TrackKeyMark(5, 30f))),
					),
			)
		val summary = summarizedMarks(row)
		assertEquals(listOf(0f, 30f), summary.map { mark -> mark.position }, "ascending by position")
		assertEquals(listOf(0, 1), summary.map { mark -> mark.keyIndex }, "and renumbered to that order")
		assertTrue(summary.all { mark -> mark.editable }, "a summary is a handle on the keys beneath it")
	}

	/** A key nothing can move is left out of the summary, so a summary mark never half-moves. */
	@Test
	fun summarySkipsUneditableKeys() {
		val row =
			TrackRow(
				key = "owner",
				label = "Owner",
				children =
					listOf(
						TrackRow(key = "blend", label = "Blend", marks = listOf(TrackKeyMark(0, 12f, editable = false))),
						TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0, 30f))),
					),
			)
		assertEquals(listOf(30f), summarizedMarks(row).map { mark -> mark.position })
	}

	/** A mark's shape survives the summary, so a blend-shape key still reads as a square when folded. */
	@Test
	fun summaryKeepsTheFirstMarkShape() {
		val row =
			TrackRow(
				key = "owner",
				label = "Owner",
				children = listOf(TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0, 0f, TrackKeyShape.Square)))),
			)
		assertEquals(TrackKeyShape.Square, summarizedMarks(row).single().shape)
	}

	/**
	 * A drag is bounded by the AXIS, not by neighbouring marks.
	 *
	 * Neighbours stopped being walls when keys gained the ability to cross: the grid re-sorts and permutes
	 * its cells to match, so clamping here would refuse a gesture the model accepts.
	 */
	@Test
	fun dragBoundsSpanTheAxis() {
		assertEquals(-30f..30f, dragBoundsOf(TrackAxis(-30f, 30f)))
		assertEquals(0f..1f, dragBoundsOf(TrackAxis(0f, 1f)))
	}

	/** A reversed axis still yields an ascending range, so coerceIn cannot throw on it. */
	@Test
	fun dragBoundsAscendOnAReversedAxis() {
		assertEquals(-30f..30f, dragBoundsOf(TrackAxis(30f, -30f)))
	}

	/** A window maps onto a domain as a proportional slice of it. */
	@Test
	fun aWindowSlicesItsDomainProportionally() {
		val full = TrackAxis(-30f, 30f)
		assertEquals(TrackAxis(-30f, 30f), TrackWindow.Full.axisOver(full))
		assertEquals(TrackAxis(-15f, 15f), TrackWindow(0.25f, 0.75f).axisOver(full))
	}

	/**
	 * The same window over two unrelated ranges lands at the same SCREEN positions.
	 *
	 * The reason the window is normalized: a linked pad shows two parameters at once, and a shared
	 * absolute window would overshoot the narrower one entirely.
	 */
	@Test
	fun oneWindowKeepsTwoDomainsInStep() {
		val window = TrackWindow(0.25f, 0.75f)
		val wide = window.axisOver(TrackAxis(-30f, 30f))
		val narrow = window.axisOver(TrackAxis(0f, 1f))
		assertEquals(-15f, wide.start)
		assertEquals(0.25f, narrow.start)
		assertEquals(wide.fractionOf(0f), narrow.fractionOf(0.5f), 1e-5f, "both sit at the same screen x")
	}

	/** Zooming holds the anchor under the pointer rather than drifting toward the center. */
	@Test
	fun zoomingHoldsItsAnchor() {
		val zoomed = TrackWindow.Full.zoomedBy(0.5f, focus = 0.25f)
		assertEquals(0.5f, zoomed.span, 1e-5f)
		assertEquals(0.25f, zoomed.start + zoomed.span * 0.25f, 1e-5f, "the anchored domain point stays put")
	}

	/** Zooming out near an end walks the window inward instead of running off the domain. */
	@Test
	fun zoomingOutStaysInsideTheDomain() {
		val atEnd = TrackWindow(0.9f, 1f)
		val zoomedOut = atEnd.zoomedBy(4f, focus = 1f)
		assertTrue(zoomedOut.start >= 0f && zoomedOut.end <= 1f, "got $zoomedOut")
		assertEquals(0.4f, zoomedOut.span, 1e-5f)
	}

	/** Zoom is bounded at both ends: never past the whole domain, never below the pixel-mapping floor. */
	@Test
	fun zoomIsBoundedBothWays() {
		assertEquals(1f, TrackWindow.Full.zoomedBy(10f, focus = 0.5f).span, 1e-5f)
		assertEquals(TrackWindow.MIN_SPAN, TrackWindow.Full.zoomedBy(1e-9f, focus = 0.5f).span, 1e-6f)
	}

	/**
	 * Panning keeps the span and stops at either end of the domain.
	 *
	 * Compared with a tolerance rather than by value: the window is carried as two floats, so re-anchoring
	 * it lands an ULP off by construction.  Asserting equality here would be asserting that float addition
	 * is exact, which is a different (and false) claim than the one this test is about.
	 */
	@Test
	fun panningKeepsTheSpanAndStopsAtTheEnds() {
		val window = TrackWindow(0.4f, 0.6f)
		assertWindow(expectedStart = 0.5f, expectedEnd = 0.7f, actual = window.pannedBy(0.1f))
		assertWindow(0f, 0.2f, window.pannedBy(-9f), "panning past the start stops at it")
		assertWindow(0.8f, 1f, window.pannedBy(9f), "panning past the end stops at it")
	}

	/**
	 * Tick labels ROUND to two decimals rather than truncate: the axis generates ticks by repeated float
	 * addition, so a 0.2-step tick arrives as 0.59999996 - truncation labeled it "0.59" beside 0.2 and
	 * 0.4, and negative ticks truncated the other way.
	 */
	@Test
	fun tickLabelsRoundInsteadOfTruncating() {
		assertEquals("0.6", defaultTickLabel(0.59999996f), "accumulated float error rounds up to the meant tick")
		assertEquals("-0.2", defaultTickLabel(-0.19999998f), "negative ticks round away from zero, not toward it")
		assertEquals("10", defaultTickLabel(10.0f), "an integral tick drops its .0")
		assertEquals("0.25", defaultTickLabel(0.25f), "an exact fraction is untouched")
	}

	/**
	 * Asserts a window's bounds within float tolerance.
	 *
	 * @param Float expectedStart The expected visible start.
	 * @param Float expectedEnd The expected visible end.
	 * @param TrackWindow actual The window to check.
	 * @param String message A label for the failure.
	 */
	private fun assertWindow(
		expectedStart: Float,
		expectedEnd: Float,
		actual: TrackWindow,
		message: String = "window",
	) {
		assertEquals(expectedStart, actual.start, 1e-5f, "$message: start")
		assertEquals(expectedEnd, actual.end, 1e-5f, "$message: end")
	}
}