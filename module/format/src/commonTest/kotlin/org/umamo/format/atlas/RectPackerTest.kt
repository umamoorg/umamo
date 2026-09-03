package org.umamo.format.atlas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the rectangle bin packer on its own: placement geometry, page spill, rotation, seeded
 * footprints, and the oversized report.  Trimming, gutters, and pixels belong to [AtlasPackTest].
 */
class RectPackerTest {
	@Test
	fun placementsNeverOverlapAndStayInsideThePage() {
		val requests =
			listOf(
				RectPackRequest(0, 40, 30),
				RectPackRequest(1, 30, 40),
				RectPackRequest(2, 25, 25),
				RectPackRequest(3, 60, 10),
				RectPackRequest(4, 10, 60),
				RectPackRequest(5, 5, 5),
			)

		val layout = packRects(requests, pageSize = 64, allowRotation = false)

		assertTrue(layout.oversizedItemIndices.isEmpty(), "nothing here exceeds a 64 page")
		assertEquals(requests.size, layout.slots.size, "every request must be placed")
		assertNoRectOverlap(requests, layout, pageSize = 64)
	}

	@Test
	fun aRequestTooLargeForAPageIsReportedNotDropped() {
		val requests = listOf(RectPackRequest(0, 10, 10), RectPackRequest(1, 70, 10), RectPackRequest(2, 10, 70))

		val layout = packRects(requests, pageSize = 64, allowRotation = true)

		assertEquals(listOf(1, 2), layout.oversizedItemIndices.sorted())
		assertEquals(listOf(0), layout.slots.map { slot -> slot.itemIndex })
	}

	@Test
	fun requestsSpillOntoFurtherPagesWhenOneWillNotHold() {
		val requests = (0 until 5).map { itemIndex -> RectPackRequest(itemIndex, 40, 40) }

		val layout = packRects(requests, pageSize = 64, allowRotation = false)

		// A 64 page holds exactly one 40x40, so five requests need five pages.
		assertEquals(5, layout.slots.size)
		assertEquals(listOf(0, 1, 2, 3, 4), layout.slots.map { slot -> slot.pageIndex })
		assertEquals(List(5) { 40 }, layout.usedWidths)
		assertEquals(List(5) { 40 }, layout.usedHeights)
	}

	@Test
	fun rotationIsUsedOnlyWhenAllowed() {
		// The 16x8 fills the top half of a 16 page, leaving one 16x8 free strip; the 8x16 fits it
		// only quarter-turned.
		val requests = listOf(RectPackRequest(0, 16, 8), RectPackRequest(1, 8, 16))

		val turned = packRects(requests, pageSize = 16, allowRotation = true)
		assertEquals(1, turned.slots.count { slot -> slot.rotated }, "the second request must turn to fit")
		assertEquals(listOf(0, 0), turned.slots.map { slot -> slot.pageIndex })

		val upright = packRects(requests, pageSize = 16, allowRotation = false)
		assertTrue(upright.slots.none { slot -> slot.rotated }, "rotation must not appear when disallowed")
		assertEquals(listOf(0, 1), upright.slots.map { slot -> slot.pageIndex }, "it needs its own page instead")
	}

	@Test
	fun theUsedExtentBoundsEveryPlacement() {
		val requests = listOf(RectPackRequest(0, 30, 20), RectPackRequest(1, 20, 30), RectPackRequest(2, 10, 10))

		val layout = packRects(requests, pageSize = 128, allowRotation = false)

		for (slot in layout.slots) {
			val width = if (slot.rotated) requests[slot.itemIndex].height else requests[slot.itemIndex].width
			val height = if (slot.rotated) requests[slot.itemIndex].width else requests[slot.itemIndex].height
			assertTrue(
				slot.x + width <= layout.usedWidths[slot.pageIndex] && slot.y + height <= layout.usedHeights[slot.pageIndex],
				"item ${slot.itemIndex} runs past the reported used extent",
			)
		}
	}

	@Test
	fun aSeedIsKeptClearAndCountsTowardTheUsedExtent() {
		val seed = RectPackSeed(pageIndex = 0, x = 10, y = 10, width = 20, height = 20)
		val requests = (0 until 6).map { itemIndex -> RectPackRequest(itemIndex, 15, 15) }

		val layout = packRects(requests, pageSize = 64, allowRotation = false, seeds = listOf(seed))

		assertEquals(requests.size, layout.slots.size, "every request still finds room around the seed")
		for (slot in layout.slots.filter { slot -> slot.pageIndex == 0 }) {
			val separated = slot.x + 15 <= seed.x || seed.x + seed.width <= slot.x || slot.y + 15 <= seed.y || seed.y + seed.height <= slot.y
			assertTrue(separated, "item ${slot.itemIndex} at (${slot.x}, ${slot.y}) overlaps the seed")
		}
		assertNoRectOverlap(requests, layout, pageSize = 64)
		assertTrue(layout.usedWidths[0] >= seed.x + seed.width && layout.usedHeights[0] >= seed.y + seed.height, "the used extent covers the seed")
	}

	@Test
	fun seedingALaterPageOpensThePagesBeforeIt() {
		val layout = packRects(emptyList(), pageSize = 32, allowRotation = false, seeds = listOf(RectPackSeed(2, 0, 0, 8, 8)))

		assertEquals(3, layout.usedWidths.size, "pages 0 and 1 exist so the seed keeps its index")
		assertEquals(listOf(0, 0, 8), layout.usedWidths)
		assertEquals(listOf(0, 0, 8), layout.usedHeights)
	}

	@Test
	fun packingIsAFunctionOfTheRequestOrderAlone() {
		val requests = (0 until 12).map { itemIndex -> RectPackRequest(itemIndex, 10 + itemIndex, 40 - itemIndex) }

		val first = packRects(requests, pageSize = 64, allowRotation = true)
		val second = packRects(requests, pageSize = 64, allowRotation = true)

		assertContentEquals(
			first.slots.map { slot -> listOf(slot.itemIndex, slot.pageIndex, slot.x, slot.y, if (slot.rotated) 1 else 0) },
			second.slots.map { slot -> listOf(slot.itemIndex, slot.pageIndex, slot.x, slot.y, if (slot.rotated) 1 else 0) },
		)
	}

	/**
	 * Asserts no two placed rectangles intersect and every one fits the page.
	 *
	 * @param List requests        The packed requests, indexed by item index.
	 * @param RectPackLayout layout The packing outcome.
	 * @param Int pageSize          The page side the pack ran against.
	 */
	private fun assertNoRectOverlap(requests: List<RectPackRequest>, layout: RectPackLayout, pageSize: Int) {
		fun widthOf(slot: RectPackSlot): Int =
			if (slot.rotated) requests[slot.itemIndex].height else requests[slot.itemIndex].width

		fun heightOf(slot: RectPackSlot): Int =
			if (slot.rotated) requests[slot.itemIndex].width else requests[slot.itemIndex].height

		for (slot in layout.slots) {
			assertTrue(
				slot.x >= 0 && slot.y >= 0 && slot.x + widthOf(slot) <= pageSize && slot.y + heightOf(slot) <= pageSize,
				"item ${slot.itemIndex} runs outside the page",
			)
		}
		for (firstIndex in layout.slots.indices) {
			for (secondIndex in firstIndex + 1 until layout.slots.size) {
				val first = layout.slots[firstIndex]
				val second = layout.slots[secondIndex]
				if (first.pageIndex != second.pageIndex) {
					continue
				}
				val separated =
					first.x + widthOf(first) <= second.x ||
						second.x + widthOf(second) <= first.x ||
						first.y + heightOf(first) <= second.y ||
						second.y + heightOf(second) <= first.y
				assertTrue(separated, "items ${first.itemIndex} and ${second.itemIndex} overlap")
			}
		}
	}
}