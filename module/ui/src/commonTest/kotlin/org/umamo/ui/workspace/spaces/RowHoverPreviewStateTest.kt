package org.umamo.ui.workspace.spaces

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hover preview's ownership rule: only the row that owns the preview may withdraw it.
 *
 * The order the rule exists for is the everyday one.  Moving the pointer down the list reports the next
 * row before the previous row's exit arrives, so an exit that cleared unconditionally would blank the
 * preview of the row the pointer is now on.
 */
class RowHoverPreviewStateTest {
	private fun preview(key: String): RowHoverPreview<String> = RowHoverPreview(key, "Row $key", Rect(0f, 0f, 100f, 20f))

	/** The next row's report arriving before the old row's exit keeps the next row. */
	@Test
	fun anOldRowsExitDoesNotClearTheNextRow() {
		val state = RowHoverPreviewState<String>()
		state.report(preview("a"))
		state.report(preview("b"))
		state.clear("a")
		assertEquals("b", state.hovered?.key)
	}

	/** The owning row's exit withdraws the preview. */
	@Test
	fun theOwningRowsExitClearsIt() {
		val state = RowHoverPreviewState<String>()
		state.report(preview("a"))
		state.clear("a")
		assertNull(state.hovered)
	}

	/** A report replaces whichever row was hovered, name and bounds included. */
	@Test
	fun aReportReplacesTheHoveredRow() {
		val state = RowHoverPreviewState<String>()
		state.report(preview("a"))
		val next = RowHoverPreview("b", "Renamed", Rect(0f, 20f, 100f, 40f))
		state.report(next)
		assertEquals(next, state.hovered)
	}
}