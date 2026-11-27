package org.umamo.ui.workspace.spaces

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * Pins where a row's hover preview goes: beside the row while there is room, and at the pointer when there
 * is none, where it must never sit under the pointer.  A preview under the pointer takes the row's hover,
 * which withdraws the preview, which returns the hover: the preview flickers, and it covers the chevron or
 * the icon the pointer was reaching for.
 *
 * A 1000 by 800 window and a 130 by 150 preview throughout, with an 8 pixel gap beside a row and a 16
 * pixel gap from the pointer.
 */
class RowPreviewPlacementTest {
	private val window = IntSize(1000, 800)
	private val preview = IntSize(130, 150)

	/** A row that spans the window, so neither side of it has room. */
	private val spanningRow = Rect(5f, 110f, 995f, 132f)

	/**
	 * The preview's position for a row, with the pointer where a case puts it.
	 *
	 * @param Rect row The hovered row's bounds.
	 * @param Offset? pointer The pointer, or null when it is not known.
	 * @return IntOffset The preview's top-left.
	 */
	private fun positionFor(row: Rect, pointer: Offset?): IntOffset = rowPreviewPosition(row, { pointer }, ROW_GAP, POINTER_GAP, window, preview)

	/**
	 * Whether the preview placed at [position] covers [pointer].
	 *
	 * @param IntOffset position The preview's top-left.
	 * @param Offset pointer The pointer.
	 * @return Boolean True when the pointer is inside the preview.
	 */
	private fun covers(position: IntOffset, pointer: Offset): Boolean =
		pointer.x >= position.x && pointer.x < position.x + preview.width && pointer.y >= position.y && pointer.y < position.y + preview.height

	/** With room on its right, the preview sits just right of the row, level with its top. */
	@Test
	fun withRoomOnTheRightThePreviewSitsRightOfTheRow() {
		val row = Rect(0f, 110f, 300f, 132f)

		assertEquals(IntOffset(308, 110), positionFor(row, Offset(20f, 120f)))
	}

	/** With no room on its right, the preview sits just left of the row. */
	@Test
	fun withRoomOnlyOnTheLeftThePreviewSitsLeftOfTheRow() {
		val row = Rect(700f, 110f, 1000f, 132f)

		assertEquals(IntOffset(700 - ROW_GAP - preview.width, 110), positionFor(row, Offset(720f, 120f)))
	}

	/** A preview beside its row does not ask where the pointer is, so it does not follow it. */
	@Test
	fun aPreviewBesideItsRowNeverAsksForThePointer() {
		val row = Rect(0f, 110f, 300f, 132f)

		rowPreviewPosition(row, { fail("a row with room beside it has no use for the pointer") }, ROW_GAP, POINTER_GAP, window, preview)
	}

	/** A preview beside a row near the window's bottom is held inside the window. */
	@Test
	fun aPreviewBesideALowRowStaysInsideTheWindow() {
		val row = Rect(0f, 780f, 300f, 800f)

		assertEquals(IntOffset(308, window.height - preview.height), positionFor(row, Offset(20f, 790f)))
	}

	/** With room on neither side, the preview sits below and right of the pointer. */
	@Test
	fun withNoRoomBesideTheRowThePreviewAnchorsToThePointer() {
		val pointer = Offset(32f, 121f)

		val position = positionFor(spanningRow, pointer)

		assertEquals(IntOffset(32 + POINTER_GAP, 121 + POINTER_GAP), position)
		assertFalse(covers(position, pointer), "the chevron under the pointer stays in reach")
	}

	/** Near the window's right edge the preview flips to the pointer's left. */
	@Test
	fun nearTheRightEdgeThePreviewFlipsLeftOfThePointer() {
		val pointer = Offset(950f, 121f)

		val position = positionFor(spanningRow, pointer)

		assertEquals(IntOffset(950 - POINTER_GAP - preview.width, 121 + POINTER_GAP), position)
		assertFalse(covers(position, pointer))
	}

	/** Near the window's bottom edge the preview flips above the pointer. */
	@Test
	fun nearTheBottomEdgeThePreviewFlipsAboveThePointer() {
		val lowRow = Rect(5f, 760f, 995f, 782f)
		val pointer = Offset(400f, 771f)

		val position = positionFor(lowRow, pointer)

		assertEquals(IntOffset(400 + POINTER_GAP, 771 - POINTER_GAP - preview.height), position)
		assertFalse(covers(position, pointer))
	}

	/** Wherever the pointer is in the window, a preview anchored to it never covers it. */
	@Test
	fun aPointerAnchoredPreviewNeverCoversThePointer() {
		for (pointerX in 0 until window.width step 25) {
			for (pointerY in 0 until window.height step 25) {
				val pointer = Offset(pointerX.toFloat(), pointerY.toFloat())
				val position = positionFor(spanningRow, pointer)

				assertFalse(covers(position, pointer), "the preview at $position covers the pointer at $pointer")
			}
		}
	}

	/** A pointer-anchored preview stays whole inside the window, wherever the pointer is. */
	@Test
	fun aPointerAnchoredPreviewStaysInsideTheWindow() {
		for (pointerX in 0 until window.width step 25) {
			for (pointerY in 0 until window.height step 25) {
				val position = positionFor(spanningRow, Offset(pointerX.toFloat(), pointerY.toFloat()))

				assertFalse(position.x < 0 || position.y < 0, "the preview at $position starts outside the window")
				assertFalse(
					position.x + preview.width > window.width || position.y + preview.height > window.height,
					"the preview at $position ends outside the window",
				)
			}
		}
	}

	/** With no pointer known, a row with no room beside it has only the window's edge to offer. */
	@Test
	fun withNoPointerKnownThePreviewIsHeldAtTheWindowsEdge() {
		assertEquals(IntOffset(0, 110), positionFor(spanningRow, null))
	}

	private companion object {
		const val ROW_GAP = 8
		const val POINTER_GAP = 16
	}
}