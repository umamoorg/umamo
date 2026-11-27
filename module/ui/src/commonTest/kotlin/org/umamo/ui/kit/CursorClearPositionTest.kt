package org.umamo.ui.kit

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the tooltip's cursor placement: the card sits a gap after the cursor where it fits, and moves
 * wholly to the cursor's other side, a smaller gap before it, on an axis where it would cross the window
 * margin, so the card never covers the pointer's hotspot and never takes the click meant for the control
 * beneath it.
 */
class CursorClearPositionTest {
	private val window = IntSize(1000, 800)
	private val gapAfter = 16
	private val gapBefore = 4
	private val windowMargin = 4
	private val anchor = IntRect(100, 50, 600, 550)

	/**
	 * Places a card of [card] size for a cursor at [cursorInWindow].
	 *
	 * @param IntOffset       cursorInWindow  The cursor in window pixels.
	 * @param IntSize         card            The measured card size.
	 * @param LayoutDirection layoutDirection The layout direction.
	 * @return IntOffset The card's top-left.
	 */
	private fun positionOf(
		cursorInWindow: IntOffset,
		card: IntSize,
		layoutDirection: LayoutDirection = LayoutDirection.Ltr,
	): IntOffset {
		val cursorOffset = IntOffset(cursorInWindow.x - anchor.left, cursorInWindow.y - anchor.top)

		return CursorClearPositionProvider(cursorOffset, gapAfter, gapBefore, windowMargin)
			.calculatePosition(anchor, window, layoutDirection, card)
	}

	/**
	 * Whether a card at [cardTopLeft] keeps at least its side's gap from [cursor] on one axis or the other.
	 *
	 * @param IntOffset cardTopLeft The card's top-left.
	 * @param IntSize   card        The card size.
	 * @param IntOffset cursor      The cursor in window pixels.
	 * @return Boolean True when the card is clear of the cursor.
	 */
	private fun clearOfCursor(cardTopLeft: IntOffset, card: IntSize, cursor: IntOffset): Boolean {
		val clearHorizontally =
			cardTopLeft.x + card.width <= cursor.x - gapBefore || cardTopLeft.x >= cursor.x + gapAfter
		val clearVertically =
			cardTopLeft.y + card.height <= cursor.y - gapBefore || cardTopLeft.y >= cursor.y + gapAfter

		return clearHorizontally || clearVertically
	}

	@Test
	fun sitsBelowAndRightOfTheCursorWhenItFits() {
		assertEquals(IntOffset(316, 216), positionOf(IntOffset(300, 200), IntSize(200, 40)))
	}

	@Test
	fun flipsLeftNearTheRightEdge() {
		// 916 + 200 runs past 996, so the card ends the smaller gap before the cursor instead: 900 - 4 - 200.
		assertEquals(IntOffset(696, 216), positionOf(IntOffset(900, 200), IntSize(200, 40)))
	}

	@Test
	fun flipsAboveNearTheBottomEdge() {
		// 776 + 40 runs past 796, so the card ends the smaller gap above the cursor: 760 - 4 - 40.
		assertEquals(IntOffset(316, 716), positionOf(IntOffset(300, 760), IntSize(200, 40)))
	}

	@Test
	fun flipsBothAxesInTheBottomRightCorner() {
		assertEquals(IntOffset(696, 716), positionOf(IntOffset(900, 760), IntSize(200, 40)))
	}

	@Test
	fun prefersTheLeftSideInRightToLeftLayouts() {
		assertEquals(IntOffset(96, 216), positionOf(IntOffset(300, 200), IntSize(200, 40), LayoutDirection.Rtl))
		// No room on the left: the card takes the right side after all.
		assertEquals(IntOffset(116, 216), positionOf(IntOffset(100, 200), IntSize(200, 40), LayoutDirection.Rtl))
	}

	@Test
	fun clampsAnAxisWhereNeitherSideFitsAndClearsOnTheOther() {
		// A 700-wide card fits neither left nor right of a centered cursor, so it is clamped into the window;
		// it still sits a gap below the cursor.
		val cursor = IntOffset(500, 200)
		val card = IntSize(700, 40)
		val position = positionOf(cursor, card)

		assertEquals(IntOffset(296, 216), position)
		assertTrue(clearOfCursor(position, card, cursor))
	}

	@Test
	fun neverCoversTheCursorAnywhereInTheWindow() {
		val cards = listOf(IntSize(120, 24), IntSize(280, 60), IntSize(600, 40))

		for (card in cards) {
			for (cursorX in 0..window.width step 10) {
				for (cursorY in 0..window.height step 10) {
					val cursor = IntOffset(cursorX, cursorY)
					val position = positionOf(cursor, card)

					assertTrue(clearOfCursor(position, card, cursor), "card $card covers the cursor at $cursor")
					assertTrue(position.x >= windowMargin && position.x + card.width <= window.width - windowMargin)
					assertTrue(position.y >= windowMargin && position.y + card.height <= window.height - windowMargin)
				}
			}
		}
	}
}