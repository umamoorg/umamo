package org.umamo.ui.kit

/**
 * What one declared slot is, from the packer's point of view.  The composable content is deliberately
 * absent - this file decides geometry only, so it stays a pure function the layout tests can drive
 * without a composition.
 */
internal enum class OverflowSlotKind {
	/** Collapses into the overflow dropdown when the strip runs out of room. */
	Collapsible,

	/** Always placed, even when that overflows the strip. */
	Pinned,

	/** A weighted gap that absorbs leftover width; never collapses and never carries spacing. */
	Flexible,
}

/**
 * Sentinel [OverflowSlotSpec.minWidthPx] and measure bound meaning "no limit" - a slot that is measured at
 * whatever width it asks for, and collapses whole rather than compressing.
 */
internal const val OVERFLOW_WIDTH_UNBOUNDED = Int.MAX_VALUE

/**
 * One declared slot, stripped of its content.
 *
 * @property OverflowSlotKind kind       How the slot behaves when width runs short.
 * @property Float            weight     The share of leftover width, for [OverflowSlotKind.Flexible] only.
 * @property Int              minWidthPx For a Collapsible slot, the narrowest it may be squeezed to before
 *   it collapses instead; for a Flexible gap, the separation it never closes below.  0 means a control is
 *   placed at its natural width or not at all, and a gap may close completely.
 */
internal data class OverflowSlotSpec(
	val kind: OverflowSlotKind,
	val weight: Float = 0f,
	val minWidthPx: Int = 0,
)

/**
 * One slot that made it onto the strip, at its resolved position.
 *
 * @property Int slotIndex The slot's index in the declared list.
 * @property Int xPx       The slot's left edge, relative to the strip.
 * @property Int widthPx   The slot's resolved width; for a Flexible gap, its floor plus its share of the
 *   leftover.
 */
internal data class OverflowPlacement(
	val slotIndex: Int,
	val xPx: Int,
	val widthPx: Int,
)

/**
 * The resolved strip: what is placed and where, what collapsed, and where the overflow chip goes.
 *
 * @property List placements           The placed slots in declaration order.
 * @property List collapsedSlotIndices The slots that did not fit, in declaration order - the dropdown's contents.
 * @property Int? overflowButtonXPx    The chip's left edge, or null when nothing collapsed (it is measured
 *   but not placed).
 * @property Int  contentWidthPx       The extent the strip actually occupies.
 */
internal data class OverflowRowPacking(
	val placements: List<OverflowPlacement>,
	val collapsedSlotIndices: List<Int>,
	val overflowButtonXPx: Int?,
	val contentWidthPx: Int,
)

/**
 * Decides which slots fit on the strip, at which x, and how wide each flexible gap resolves to.
 *
 * The strip is walked up to twice.  The first walk charges nothing for the overflow chip, because a
 * strip whose controls all fit does not show one; if that walk collapses nothing it is the answer.  Only
 * when something genuinely does not fit is the chip certain to exist, and the second walk charges for it.
 * That ordering is what keeps the two walks from disagreeing - once a control has missed at the full
 * width, reserving the chip can only push more out, never restore "everything fits" - so the strip
 * settles rather than oscillating on a one-pixel resize.
 *
 * Widths are pulled through [slotWidthPx] rather than passed in so the caller can compose lazily: every
 * Pinned slot is measured up front (they are always placed, so their width must be reserved before any
 * earlier Collapsible slot is admitted), Collapsible slots are measured in declaration order until one
 * does not fit, and nothing after that is measured at all.  A slot measuring zero is dropped entirely -
 * no spacing, not collapsed, absent from the dropdown - which is how a mode-gated control that renders
 * nothing costs nothing.
 *
 * @param List     slots                 The declared slots, in declaration order.
 * @param Int      availableWidthPx      The strip's width; ignored for collapsing when [boundedWidth] is false.
 * @param Boolean  boundedWidth          Whether the strip has a real width.  When false nothing collapses and
 *   every flexible gap sits at its floor, since there is no leftover to share out.
 * @param Int      spacingPx             The gap between two adjacent placed slots.
 * @param Int      overflowButtonWidthPx The measured width of the trailing overflow chip.
 * @param Set      preCollapsedSlotIndices Collapsible slots an earlier pass already ruled out, collapsed
 *   again without being measured.  Deciding the FIRST slot that does not fit costs one measurement, so
 *   that slot is composed; feeding the verdict back lets the caller drop it on the next pass instead of
 *   leaving it composed-but-unplaced.  Empty re-evaluates every slot from scratch.
 * @param Function slotWidthPx           Measures one slot at a given maximum width, on demand.  A slot
 *   declaring [OverflowSlotSpec.minWidthPx] can be asked twice with different bounds (once per walk), so
 *   the caller must handle a repeat request for an index it has already measured.
 * @return OverflowRowPacking The resolved strip.
 */
internal fun packOverflowRow(
	slots: List<OverflowSlotSpec>,
	availableWidthPx: Int,
	boundedWidth: Boolean,
	spacingPx: Int,
	overflowButtonWidthPx: Int,
	preCollapsedSlotIndices: Set<Int> = emptySet(),
	slotWidthPx: (slotIndex: Int, maxWidthPx: Int) -> Int,
): OverflowRowPacking {
	// Pinned slots are always placed, so a pinned slot LATER in the strip still has to be reserved for
	// before an earlier collapsible one is admitted.  Measuring them up front is what makes that
	// reservation possible without measuring the collapsible tail.
	val pinnedWidths = HashMap<Int, Int>(slots.size)
	slots.forEachIndexed { slotIndex, slot ->
		if (slot.kind == OverflowSlotKind.Pinned) {
			pinnedWidths[slotIndex] = slotWidthPx(slotIndex, OVERFLOW_WIDTH_UNBOUNDED)
		}
	}
	// What every slot from here on is already owed: a pinned slot's measured width (it is always placed),
	// and a flexible gap's floor (it never closes below it).  Charged against each admission so an earlier
	// control cannot take room its successors have a claim on.  One spacing per pinned slot over-reserves
	// by at most a single gap - an exact figure would need the placed set, which is what this decides.
	val reservedWidthAfter = IntArray(slots.size + 1)
	for (slotIndex in slots.indices.reversed()) {
		val slot = slots[slotIndex]
		val pinnedWidth = pinnedWidths[slotIndex] ?: 0
		val ownReserve =
			when {
				slot.kind == OverflowSlotKind.Flexible -> slot.minWidthPx
				pinnedWidth > 0 -> pinnedWidth + spacingPx
				else -> 0
			}
		reservedWidthAfter[slotIndex] = reservedWidthAfter[slotIndex + 1] + ownReserve
	}

	// Walked twice at most.  The first walk reserves NOTHING for the overflow chip, because a strip whose
	// controls all fit shows no chip and must not be charged for one - reserving unconditionally collapsed
	// the last control roughly a chip early, with visible slack still in the flexible gaps.  If that walk
	// collapses nothing, it is the answer.  If it collapses something, the chip is needed for certain, so
	// the second walk charges for it; that can only push MORE out, never bring the set back to "everything
	// fits", so the two walks cannot disagree about whether a chip exists and the strip cannot oscillate.
	val withoutChip = walkStrip(slots, availableWidthPx, boundedWidth, spacingPx, 0, preCollapsedSlotIndices, reservedWidthAfter, pinnedWidths, slotWidthPx)
	val walk =
		if (withoutChip.collapsedSlotIndices.isEmpty() || overflowButtonWidthPx == 0) {
			withoutChip
		} else {
			walkStrip(
				slots,
				availableWidthPx,
				boundedWidth,
				spacingPx,
				overflowButtonWidthPx,
				preCollapsedSlotIndices,
				reservedWidthAfter,
				pinnedWidths,
				slotWidthPx,
			)
		}

	val placedIndices = walk.placedIndices
	val placedWidths = walk.placedWidths
	val collapsedSlotIndices = walk.collapsedSlotIndices
	// The reserved chip width returns to the flexible gaps whenever nothing actually collapsed.
	val overflowReservePx =
		if (collapsedSlotIndices.isEmpty()) {
			0
		} else {
			overflowButtonWidthPx + spacingPx
		}
	val leftoverPx =
		if (boundedWidth) {
			(availableWidthPx - walk.usedPx - overflowReservePx).coerceAtLeast(0)
		} else {
			0
		}
	distributeFlexibleWidths(slots, placedIndices, placedWidths, leftoverPx)

	val placements = mutableListOf<OverflowPlacement>()
	var xPx = 0
	var previousPlacedWasFlexible = false
	placedIndices.forEachIndexed { placedIndex, slotIndex ->
		val isFlexible = slots[slotIndex].kind == OverflowSlotKind.Flexible
		if (placedIndex > 0 && !previousPlacedWasFlexible && !isFlexible) {
			xPx += spacingPx
		}
		placements.add(OverflowPlacement(slotIndex = slotIndex, xPx = xPx, widthPx = placedWidths[placedIndex]))
		xPx += placedWidths[placedIndex]
		previousPlacedWasFlexible = isFlexible
	}

	val overflowButtonXPx =
		if (collapsedSlotIndices.isEmpty()) {
			null
		} else {
			(availableWidthPx - overflowButtonWidthPx).coerceAtLeast(0)
		}
	val contentWidthPx = maxOf(xPx, overflowButtonXPx?.plus(overflowButtonWidthPx) ?: 0)
	return OverflowRowPacking(
		placements = placements,
		collapsedSlotIndices = collapsedSlotIndices,
		overflowButtonXPx = overflowButtonXPx,
		contentWidthPx = contentWidthPx,
	)
}

/**
 * One walk's outcome, before the flexible gaps are sized and the slots are positioned.
 *
 * @property MutableList placedIndices        The placed slots, in declaration order.
 * @property MutableList placedWidths         Each placed slot's width, parallel to [placedIndices].
 * @property MutableList collapsedSlotIndices The slots that did not fit.
 * @property Int         usedPx               The width the placed slots and their spacing consumed.
 */
private class StripWalk(
	val placedIndices: MutableList<Int>,
	val placedWidths: MutableList<Int>,
	val collapsedSlotIndices: MutableList<Int>,
	val usedPx: Int,
)

/**
 * Admits slots left to right against a budget, collapsing from the first one that does not fit.
 *
 * A slot declaring [OverflowSlotSpec.minWidthPx] is offered the room actually left rather than measured
 * unbounded, so it compresses toward its floor instead of collapsing the moment its natural width stops
 * fitting - a 160.dp search box has most of its width to give up before vanishing is the right answer.
 * Below the floor it collapses like anything else.
 *
 * @param List     slots                   The declared slots, in declaration order.
 * @param Int      availableWidthPx        The strip's width.
 * @param Boolean  boundedWidth            False when the strip has no real width, so nothing collapses.
 * @param Int      spacingPx               The gap between two adjacent placed slots.
 * @param Int      reservedForChipPx       Width held back for the trailing overflow chip.
 * @param Set      preCollapsedSlotIndices Collapsible slots an earlier pass already ruled out.
 * @param IntArray reservedWidthAfter        Width already owed at or after each index - pinned slots' widths
 *   and flexible gaps' floors - so an earlier control cannot take room its successors have a claim on.
 * @param Map      pinnedWidths            The pinned slots' already-measured widths.
 * @param Function slotWidthPx             Measures one slot at a given maximum width.
 * @return StripWalk What was placed, what collapsed, and how much width it took.
 */
private fun walkStrip(
	slots: List<OverflowSlotSpec>,
	availableWidthPx: Int,
	boundedWidth: Boolean,
	spacingPx: Int,
	reservedForChipPx: Int,
	preCollapsedSlotIndices: Set<Int>,
	reservedWidthAfter: IntArray,
	pinnedWidths: Map<Int, Int>,
	slotWidthPx: (slotIndex: Int, maxWidthPx: Int) -> Int,
): StripWalk {
	val budgetPx = (availableWidthPx - reservedForChipPx).coerceAtLeast(0)
	val placedIndices = mutableListOf<Int>()
	val placedWidths = mutableListOf<Int>()
	val collapsedSlotIndices = mutableListOf<Int>()
	var usedPx = 0
	var previousWasFlexible = false
	var anythingPlaced = false
	var collapsing = false

	slots.forEachIndexed { slotIndex, slot ->
		if (slot.kind == OverflowSlotKind.Flexible) {
			// Placed at its floor, not at zero: the floor is the gap's guaranteed separation, so it has to be
			// spent here where it counts against what the controls beside it may take.  Any leftover is added
			// on top afterwards.  A zero-floor gap behaves exactly as before.
			placedIndices.add(slotIndex)
			placedWidths.add(slot.minWidthPx)
			usedPx += slot.minWidthPx
			previousWasFlexible = true
			anythingPlaced = true
			return@forEachIndexed
		}
		// Past the collapse point no further COLLAPSIBLE slot is even measured: composing a control only to
		// hide it is the cost this packer exists to avoid.  A pinned slot still goes through - the latch
		// governs what may be dropped, and a pinned slot may not.
		if (slot.kind == OverflowSlotKind.Collapsible && (collapsing || slotIndex in preCollapsedSlotIndices)) {
			collapsing = true
			collapsedSlotIndices.add(slotIndex)
			return@forEachIndexed
		}
		// A flexible gap IS the separation, so it suppresses spacing on both sides.
		val leadingSpacingPx =
			if (anythingPlaced && !previousWasFlexible) {
				spacingPx
			} else {
				0
			}
		val roomPx = budgetPx - usedPx - leadingSpacingPx - reservedWidthAfter[slotIndex + 1]
		val compressible = slot.kind == OverflowSlotKind.Collapsible && boundedWidth && slot.minWidthPx > 0
		if (compressible && roomPx < slot.minWidthPx) {
			collapsing = true
			collapsedSlotIndices.add(slotIndex)
			return@forEachIndexed
		}
		// A compressible slot is offered the room left AFTER the controls behind it, so it gives up width to
		// keep them on the strip rather than taking its natural width and pushing them into the dropdown - a
		// search box narrowing is a far better trade than a filter chip vanishing.  It never yields below its
		// own floor; past that the tail collapses as usual.  Costing the tail means measuring it early, which
		// is the one place this packer gives up laziness - bounded by the handful of controls a strip carries.
		val measureMaxPx =
			if (compressible) {
				maxOf(slot.minWidthPx, roomPx - collapsibleTailWidthPx(slots, slotIndex, spacingPx, preCollapsedSlotIndices, slotWidthPx))
			} else {
				OVERFLOW_WIDTH_UNBOUNDED
			}
		val width = pinnedWidths[slotIndex] ?: slotWidthPx(slotIndex, measureMaxPx)
		if (width == 0) {
			return@forEachIndexed
		}
		if (slot.kind == OverflowSlotKind.Collapsible && boundedWidth && !compressible && width > roomPx) {
			collapsing = true
			collapsedSlotIndices.add(slotIndex)
			return@forEachIndexed
		}
		placedIndices.add(slotIndex)
		placedWidths.add(width)
		usedPx += leadingSpacingPx + width
		previousWasFlexible = false
		anythingPlaced = true
	}
	return StripWalk(placedIndices, placedWidths, collapsedSlotIndices, usedPx)
}

/**
 * The width the collapsible controls declared AFTER [slotIndex] need, so a compressible slot can leave
 * room for them instead of taking its natural width and pushing them off the strip.
 *
 * Only non-compressible slots count: a second compressible slot could give up width of its own, so
 * charging its natural width here would make the first one yield more than it has to.
 *
 * @param List     slots                   The declared slots, in declaration order.
 * @param Int      slotIndex               The compressible slot the tail is being costed for.
 * @param Int      spacingPx               The gap between two adjacent placed slots.
 * @param Set      preCollapsedSlotIndices Slots an earlier pass already ruled out; they cost nothing.
 * @param Function slotWidthPx             Measures one slot at a given maximum width.
 * @return Int The tail's width including the gap in front of each entry.
 */
private fun collapsibleTailWidthPx(
	slots: List<OverflowSlotSpec>,
	slotIndex: Int,
	spacingPx: Int,
	preCollapsedSlotIndices: Set<Int>,
	slotWidthPx: (slotIndex: Int, maxWidthPx: Int) -> Int,
): Int {
	var tailPx = 0
	for (laterIndex in slotIndex + 1 until slots.size) {
		val later = slots[laterIndex]
		if (later.kind != OverflowSlotKind.Collapsible || later.minWidthPx > 0 || laterIndex in preCollapsedSlotIndices) {
			continue
		}
		val laterWidth = slotWidthPx(laterIndex, OVERFLOW_WIDTH_UNBOUNDED)
		if (laterWidth > 0) {
			tailPx += laterWidth + spacingPx
		}
	}
	return tailPx
}

/**
 * Shares [leftoverPx] out across the placed flexible gaps in proportion to their weights, writing the
 * results back into [placedWidths].  Largest-remainder rather than plain rounding, so the parts sum to
 * exactly the leftover and the strip's right edge lands where the caller expects it to.
 *
 * @param List slots        The declared slots, for their kinds and weights.
 * @param List placedIndices The slot index behind each entry of [placedWidths].
 * @param MutableList placedWidths The resolved widths, mutated in place.
 * @param Int  leftoverPx   The width to share out.
 */
private fun distributeFlexibleWidths(
	slots: List<OverflowSlotSpec>,
	placedIndices: List<Int>,
	placedWidths: MutableList<Int>,
	leftoverPx: Int,
) {
	if (leftoverPx <= 0) {
		return
	}
	val flexiblePositions =
		placedIndices.indices.filter { position ->
			val slot = slots[placedIndices[position]]
			slot.kind == OverflowSlotKind.Flexible && slot.weight > 0f
		}
	if (flexiblePositions.isEmpty()) {
		return
	}
	val totalWeight = flexiblePositions.sumOf { position -> slots[placedIndices[position]].weight.toDouble() }
	var assignedPx = 0
	val fractions = DoubleArray(flexiblePositions.size)
	flexiblePositions.forEachIndexed { flexibleIndex, position ->
		val exact = leftoverPx * slots[placedIndices[position]].weight / totalWeight
		val whole = exact.toInt()
		// Added to the floor the walk already spent, not written over it - the leftover is what is free
		// BEYOND the floors, so overwriting would hand the floor's own pixels out a second time.
		placedWidths[position] = placedWidths[position] + whole
		fractions[flexibleIndex] = exact - whole
		assignedPx += whole
	}
	// Hand the rounding remainder to the largest fractional parts, earliest gap first on a tie.
	val byLargestFraction = flexiblePositions.indices.sortedWith(compareByDescending<Int> { fractions[it] }.thenBy { it })
	var remainderPx = leftoverPx - assignedPx
	var cursor = 0
	while (remainderPx > 0 && byLargestFraction.isNotEmpty()) {
		val position = flexiblePositions[byLargestFraction[cursor % byLargestFraction.size]]
		placedWidths[position] = placedWidths[position] + 1
		remainderPx -= 1
		cursor += 1
	}
}