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
 * One declared slot, stripped of its content.
 *
 * @property OverflowSlotKind kind   How the slot behaves when width runs short.
 * @property Float            weight The share of leftover width, for [OverflowSlotKind.Flexible] only.
 */
internal data class OverflowSlotSpec(
	val kind: OverflowSlotKind,
	val weight: Float = 0f,
)

/**
 * One slot that made it onto the strip, at its resolved position.
 *
 * @property Int slotIndex The slot's index in the declared list.
 * @property Int xPx       The slot's left edge, relative to the strip.
 * @property Int widthPx   The slot's resolved width (a Flexible slot's share of the leftover).
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
 * The overflow chip's width is reserved unconditionally while packing, and the chip is simply not
 * placed when nothing collapsed.  Reserving it only on demand would be non-monotone: the chip's own
 * width can evict the very slot whose eviction makes the chip unnecessary, so the strip would
 * oscillate on a one-pixel resize.  The cost is a conservative band roughly one chip wide in which the
 * last slot collapses slightly early, which is the right trade for a strip that never flickers.
 *
 * Widths are pulled through [slotWidthPx] rather than passed in so the caller can compose lazily: every
 * Pinned slot is measured up front (they are always placed, so their width must be reserved before any
 * earlier Collapsible slot is admitted), Collapsible slots are measured in declaration order until one
 * does not fit, and nothing after that is measured at all.  A slot measuring zero is dropped entirely -
 * no spacing, not collapsed, absent from the dropdown - which is how a mode-gated control that renders
 * nothing costs nothing.
 *
 * 収まるスロットと座標、可変ギャップの幅を決める純粋関数。オーバーフローチップの幅は常に確保し、
 * 何も折り畳まれなければ配置しないだけ。これは 1px のリサイズで振動しないための設計。
 *
 * @param List     slots                 The declared slots, in declaration order.
 * @param Int      availableWidthPx      The strip's width; ignored for collapsing when [boundedWidth] is false.
 * @param Boolean  boundedWidth          Whether the strip has a real width.  When false nothing collapses and
 *   every flexible gap resolves to zero, since there is no leftover to share out.
 * @param Int      spacingPx             The gap between two adjacent placed slots.
 * @param Int      overflowButtonWidthPx The measured width of the trailing overflow chip.
 * @param Set      preCollapsedSlotIndices Collapsible slots an earlier pass already ruled out, collapsed
 *   again without being measured.  Deciding the FIRST slot that does not fit costs one measurement, so
 *   that slot is composed; feeding the verdict back lets the caller drop it on the next pass instead of
 *   leaving it composed-but-unplaced.  Empty re-evaluates every slot from scratch.
 * @param Function slotWidthPx           Measures one slot on demand; called at most once per index.
 * @return OverflowRowPacking The resolved strip.
 */
internal fun packOverflowRow(
	slots: List<OverflowSlotSpec>,
	availableWidthPx: Int,
	boundedWidth: Boolean,
	spacingPx: Int,
	overflowButtonWidthPx: Int,
	preCollapsedSlotIndices: Set<Int> = emptySet(),
	slotWidthPx: (slotIndex: Int) -> Int,
): OverflowRowPacking {
	// Pinned slots are always placed, so a pinned slot LATER in the strip still has to be reserved for
	// before an earlier collapsible one is admitted.  Measuring them up front is what makes that
	// reservation possible without measuring the collapsible tail.
	val measuredWidths = HashMap<Int, Int>(slots.size)
	slots.forEachIndexed { slotIndex, slot ->
		if (slot.kind == OverflowSlotKind.Pinned) {
			measuredWidths[slotIndex] = slotWidthPx(slotIndex)
		}
	}
	// One spacing per pinned slot, which over-reserves by at most a single gap.  Conservative on purpose:
	// an exact figure would need the placed set, which is what this reservation is being used to decide.
	val pinnedWidthAfter = IntArray(slots.size + 1)
	for (slotIndex in slots.indices.reversed()) {
		val ownWidth = measuredWidths[slotIndex] ?: 0
		val ownReserve = if (ownWidth > 0) ownWidth + spacingPx else 0
		pinnedWidthAfter[slotIndex] = pinnedWidthAfter[slotIndex + 1] + ownReserve
	}

	val budgetPx = (availableWidthPx - overflowButtonWidthPx).coerceAtLeast(0)
	val placedIndices = mutableListOf<Int>()
	val placedWidths = mutableListOf<Int>()
	val collapsedSlotIndices = mutableListOf<Int>()
	var usedPx = 0
	var previousWasFlexible = false
	var anythingPlaced = false
	var collapsing = false

	slots.forEachIndexed { slotIndex, slot ->
		if (slot.kind == OverflowSlotKind.Flexible) {
			placedIndices.add(slotIndex)
			placedWidths.add(0)
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
		val width = measuredWidths.getOrPut(slotIndex) { slotWidthPx(slotIndex) }
		if (width == 0) {
			return@forEachIndexed
		}
		// A flexible gap IS the separation, so it suppresses spacing on both sides.
		val leadingSpacingPx =
			if (anythingPlaced && !previousWasFlexible) {
				spacingPx
			} else {
				0
			}
		if (slot.kind == OverflowSlotKind.Collapsible && boundedWidth) {
			val wouldUsePx = usedPx + leadingSpacingPx + width + pinnedWidthAfter[slotIndex + 1]
			if (wouldUsePx > budgetPx) {
				collapsing = true
				collapsedSlotIndices.add(slotIndex)
				return@forEachIndexed
			}
		}
		placedIndices.add(slotIndex)
		placedWidths.add(width)
		usedPx += leadingSpacingPx + width
		previousWasFlexible = false
		anythingPlaced = true
	}

	// The reserved chip width returns to the flexible gaps whenever nothing actually collapsed.
	val overflowReservePx =
		if (collapsedSlotIndices.isEmpty()) {
			0
		} else {
			overflowButtonWidthPx + spacingPx
		}
	val leftoverPx =
		if (boundedWidth) {
			(availableWidthPx - usedPx - overflowReservePx).coerceAtLeast(0)
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
		placedWidths[position] = whole
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