package org.umamo.ui.kit

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the dropdown placement geometry: a menu drops below its anchor when it fits, flips above when it
 * would overflow the window bottom, and clamps when it fits neither way.  This is the regression the long
 * Masked By list exposed - the provider used to return the anchor's bottom unconditionally, so a tall menu
 * ran off screen and its tail was unreachable.
 */
class MenuPositionTest {
	private val window = IntSize(1000, 800)

	private fun positionOf(anchor: IntRect, popup: IntSize): IntOffset =
		BelowAnchorPositionProvider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

	@Test
	fun dropsBelowTheAnchorWhenItFits() {
		// Anchor near the top; a short menu fits underneath, so it opens at the anchor's bottom-left.
		assertEquals(IntOffset(120, 60), positionOf(IntRect(120, 40, 300, 60), IntSize(180, 200)))
	}

	@Test
	fun flipsAboveWhenItWouldOverflowTheBottom() {
		// Anchor near the bottom (bottom edge 760) with a 300-tall menu: 760 + 300 > 800, and there is room
		// above (700 - 300 = 400 >= 0), so it opens upward ending at the anchor's top.
		assertEquals(IntOffset(120, 400), positionOf(IntRect(120, 700, 300, 760), IntSize(180, 300)))
	}

	@Test
	fun clampsWhenItFitsNeitherAboveNorBelow() {
		// A 700-tall menu on an anchor mid-window fits neither way, so it is pushed up to sit fully on screen.
		assertEquals(IntOffset(0, 100), positionOf(IntRect(0, 400, 180, 440), IntSize(180, 700)))
		// Taller than the whole window: start at the top edge and let it scroll.
		assertEquals(IntOffset(0, 0), positionOf(IntRect(0, 400, 180, 440), IntSize(180, 900)))
	}

	@Test
	fun clampsTheLeftEdgeIntoTheWindow() {
		// An anchor near the right edge would push a wide menu past it; the left is pulled back so the whole
		// menu stays on screen.
		assertEquals(IntOffset(700, 60), positionOf(IntRect(950, 40, 990, 60), IntSize(300, 100)))
	}
}
