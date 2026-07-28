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
		val marks = listOf(TrackKeyMark(0f), TrackKeyMark(10f), TrackKeyMark(20f))
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
				marks = listOf(TrackKeyMark(30f)),
				children =
					listOf(
						TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0f), TrackKeyMark(30f))),
						TrackRow(key = "b", label = "B", marks = listOf(TrackKeyMark(-30f), TrackKeyMark(0f))),
					),
			)
		assertEquals(listOf(-30f, 0f, 30f), summarizedMarks(row).map { mark -> mark.position })
	}

	/** A mark's shape survives the summary, so a blend-shape key still reads as a square when folded. */
	@Test
	fun summaryKeepsTheFirstMarkShape() {
		val row =
			TrackRow(
				key = "owner",
				label = "Owner",
				children = listOf(TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0f, TrackKeyShape.Square)))),
			)
		assertEquals(TrackKeyShape.Square, summarizedMarks(row).single().shape)
	}
}
