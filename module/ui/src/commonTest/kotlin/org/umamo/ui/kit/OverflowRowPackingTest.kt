package org.umamo.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the pure strip-packing math: which controls fit, where they land, how the flexible gaps
 * split the leftover, and - the properties the whole design rests on - that a collapsed control is
 * never measured, and that a control once collapsed never pops back as the strip keeps narrowing.
 */
class OverflowRowPackingTest {
	private val collapsible = OverflowSlotSpec(OverflowSlotKind.Collapsible)
	private val pinned = OverflowSlotSpec(OverflowSlotKind.Pinned)

	/**
	 * A flexible gap of the given weight.
	 *
	 * @param Float weight The gap's share of the leftover.
	 * @return OverflowSlotSpec The gap spec.
	 */
	private fun flexible(weight: Float = 1f): OverflowSlotSpec = OverflowSlotSpec(OverflowSlotKind.Flexible, weight)

	/**
	 * Packs [slots] against a fixed-width strip, measuring every slot at the matching entry of [widths].
	 *
	 * @param List slots                 The declared slots.
	 * @param List widths                Each slot's natural width; a Flexible slot's entry is ignored.
	 * @param Int  availableWidthPx      The strip's width.
	 * @param Int  spacingPx             The gap between adjacent placed slots.
	 * @param Int  overflowButtonWidthPx The overflow chip's width.
	 * @return OverflowRowPacking The resolved strip.
	 */
	private fun pack(
		slots: List<OverflowSlotSpec>,
		widths: List<Int>,
		availableWidthPx: Int,
		spacingPx: Int = 10,
		overflowButtonWidthPx: Int = 20,
	): OverflowRowPacking =
		packOverflowRow(
			slots = slots,
			availableWidthPx = availableWidthPx,
			boundedWidth = true,
			spacingPx = spacingPx,
			overflowButtonWidthPx = overflowButtonWidthPx,
			slotWidthPx = { slotIndex -> widths[slotIndex] },
		)

	/** With room to spare every slot is placed and the chip is measured but never positioned. */
	@Test
	fun everythingFitsPlacesEverySlotAndHidesTheChip() {
		val packing = pack(slots = listOf(collapsible, collapsible, collapsible), widths = listOf(30, 30, 30), availableWidthPx = 500)
		assertEquals(listOf(0, 1, 2), packing.placements.map { it.slotIndex })
		assertEquals(listOf(0, 40, 80), packing.placements.map { it.xPx })
		assertTrue(packing.collapsedSlotIndices.isEmpty())
		assertNull(packing.overflowButtonXPx)
	}

	/** The first slot that will not fit takes every later collapsible slot down with it. */
	@Test
	fun theFirstMissFlushesEveryLaterCollapsibleSlot() {
		// Budget is 100 - 20 = 80.  Slots 0 and 1 use 30 + 10 + 30 = 70; slot 2 would need 110.
		val packing = pack(slots = listOf(collapsible, collapsible, collapsible), widths = listOf(30, 30, 30), availableWidthPx = 100)
		assertEquals(listOf(0, 1), packing.placements.map { it.slotIndex })
		assertEquals(listOf(2), packing.collapsedSlotIndices)
		assertEquals(80, packing.overflowButtonXPx)
	}

	/** A later collapsible slot does NOT sneak in behind an earlier one that missed. */
	@Test
	fun aNarrowSlotAfterTheCollapsePointStillCollapses() {
		val packing = pack(slots = listOf(collapsible, collapsible, collapsible), widths = listOf(60, 60, 1), availableWidthPx = 100)
		assertEquals(listOf(0), packing.placements.map { it.slotIndex })
		assertEquals(listOf(1, 2), packing.collapsedSlotIndices)
	}

	/** A pinned slot is always placed, and its width is reserved before an earlier slot is admitted. */
	@Test
	fun aTrailingPinnedSlotIsReservedForAndAlwaysPlaced() {
		// Budget 80.  Reserving the trailing pinned 50 (+10 spacing) leaves 20, so the leading 30 cannot fit.
		val packing = pack(slots = listOf(collapsible, pinned), widths = listOf(30, 50), availableWidthPx = 100)
		assertEquals(listOf(1), packing.placements.map { it.slotIndex })
		assertEquals(listOf(0), packing.collapsedSlotIndices)
	}

	/** A pinned slot is placed even when it alone overruns the strip - clipped, never hidden. */
	@Test
	fun aPinnedSlotOverrunsRatherThanDisappearing() {
		val packing = pack(slots = listOf(pinned, pinned), widths = listOf(40, 40), availableWidthPx = 30)
		assertEquals(listOf(0, 1), packing.placements.map { it.slotIndex })
		assertEquals(listOf(0, 50), packing.placements.map { it.xPx })
		assertTrue(packing.placements.all { it.widthPx > 0 })
	}

	/** Leftover width goes to the flexible gaps, split by weight and summing exactly. */
	@Test
	fun flexibleGapsSplitTheLeftoverByWeight() {
		val packing =
			pack(
				slots = listOf(flexible(1f), collapsible, flexible(3f)),
				widths = listOf(0, 40, 0),
				availableWidthPx = 140,
			)
		val gapWidths = packing.placements.filter { it.slotIndex != 1 }.map { it.widthPx }
		assertEquals(100, gapWidths.sum())
		assertEquals(listOf(25, 75), gapWidths)
	}

	/** The rounding remainder is handed out so the gaps sum to exactly the leftover, never one pixel short. */
	@Test
	fun theRoundingRemainderIsDistributedNotDropped() {
		val packing =
			pack(
				slots = listOf(flexible(1f), flexible(1f), flexible(1f)),
				widths = listOf(0, 0, 0),
				availableWidthPx = 100,
			)
		assertEquals(100, packing.placements.sumOf { it.widthPx })
	}

	/** A flexible gap IS the separation, so it adds no spacing of its own on either side. */
	@Test
	fun aFlexibleGapSuppressesSpacingOnBothSides() {
		// 200 wide, two 30-wide controls either side of one gap: the gap absorbs 140 and no 10px spacing
		// is inserted anywhere, so the trailing control's left edge is exactly 30 + 140.
		val packing =
			pack(
				slots = listOf(collapsible, flexible(), collapsible),
				widths = listOf(30, 0, 30),
				availableWidthPx = 200,
			)
		assertEquals(listOf(0, 30, 170), packing.placements.map { it.xPx })
		assertEquals(140, packing.placements[1].widthPx)
	}

	/** A slot that measures zero is dropped whole: no spacing, not placed, absent from the dropdown. */
	@Test
	fun aZeroWidthSlotCostsNothing() {
		val packing = pack(slots = listOf(collapsible, collapsible, collapsible), widths = listOf(30, 0, 30), availableWidthPx = 500)
		assertEquals(listOf(0, 2), packing.placements.map { it.slotIndex })
		// 30 + one 10px gap - the dropped slot contributes no spacing of its own.
		assertEquals(listOf(0, 40), packing.placements.map { it.xPx })
		assertTrue(packing.collapsedSlotIndices.isEmpty())
	}

	/** The chip's width is reserved while packing, then handed back to the gaps when nothing collapsed. */
	@Test
	fun theReservedChipWidthReturnsToTheGapsWhenNothingCollapses() {
		val packing = pack(slots = listOf(collapsible, flexible()), widths = listOf(30, 0), availableWidthPx = 200)
		assertNull(packing.overflowButtonXPx)
		// The full 170 is shared out, not 170 minus the 20 the packer reserved while deciding.
		assertEquals(170, packing.placements.first { it.slotIndex == 1 }.widthPx)
	}

	/** A collapsed slot is never measured - composing a control only to hide it is the cost this avoids. */
	@Test
	fun collapsedSlotsAreNeverMeasured() {
		val measuredIndices = mutableListOf<Int>()
		val widths = listOf(20, 60, 60, 40)
		val packing =
			packOverflowRow(
				slots = listOf(collapsible, collapsible, collapsible, pinned),
				availableWidthPx = 100,
				boundedWidth = true,
				spacingPx = 10,
				overflowButtonWidthPx = 20,
				slotWidthPx = { slotIndex ->
					measuredIndices.add(slotIndex)
					widths[slotIndex]
				},
			)
		assertEquals(listOf(1, 2), packing.collapsedSlotIndices)
		assertEquals(listOf(0, 3), packing.placements.map { it.slotIndex })
		// The pinned slot is always measured (its width is reserved for) and slot 1 is the miss that
		// latches collapsing, but slot 2 is behind the latch and is never composed at all.
		assertTrue(3 in measuredIndices)
		assertTrue(2 !in measuredIndices)
		assertEquals(measuredIndices.size, measuredIndices.distinct().size, "each slot is measured at most once")
	}

	/** Narrowing the strip only ever collapses more - the unconditional chip reserve rules out oscillation. */
	@Test
	fun collapsingIsMonotoneAsTheStripNarrows() {
		val widths = listOf(40, 40, 40, 40)
		val slots = listOf(collapsible, collapsible, collapsible, collapsible)
		var previousCollapsed = emptySet<Int>()
		for (availableWidthPx in 300 downTo 0 step 1) {
			val collapsed = pack(slots = slots, widths = widths, availableWidthPx = availableWidthPx).collapsedSlotIndices.toSet()
			assertTrue(
				previousCollapsed.all { slotIndex -> slotIndex in collapsed },
				"width $availableWidthPx un-collapsed ${previousCollapsed - collapsed}",
			)
			previousCollapsed = collapsed
		}
	}

	/** An unbounded strip has no width to run out of: nothing collapses and the gaps resolve to zero. */
	@Test
	fun anUnboundedStripNeverCollapsesAndHasNoLeftover() {
		val widths = listOf(40, 0, 40)
		val packing =
			packOverflowRow(
				slots = listOf(collapsible, flexible(), collapsible),
				availableWidthPx = 0,
				boundedWidth = false,
				spacingPx = 10,
				overflowButtonWidthPx = 20,
				slotWidthPx = { slotIndex -> widths[slotIndex] },
			)
		assertTrue(packing.collapsedSlotIndices.isEmpty())
		assertNull(packing.overflowButtonXPx)
		assertEquals(0, packing.placements.first { it.slotIndex == 1 }.widthPx)
		assertEquals(80, packing.contentWidthPx)
	}

	/** The reported extent covers the chip when one is placed, so the strip does not under-report its width. */
	@Test
	fun theContentExtentIncludesThePlacedChip() {
		val packing = pack(slots = listOf(collapsible, collapsible), widths = listOf(30, 300), availableWidthPx = 100)
		assertNotNull(packing.overflowButtonXPx)
		assertEquals(100, packing.contentWidthPx)
	}
}