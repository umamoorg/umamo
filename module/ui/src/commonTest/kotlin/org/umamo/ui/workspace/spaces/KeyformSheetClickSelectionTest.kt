package org.umamo.ui.workspace.spaces

import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a Shift+click does to the keyform sheet's selection.
 *
 * The UI test can only reach as far as "Shift arrived at the callback"; the rule the sheet applies after
 * that is the part with a decision in it, so it is pinned here where no composition is needed.
 */
class KeyformSheetClickSelectionTest {
	private val angleX = ParameterId("ParamAngleX")

	/** A key on the geometry row at [ordinal]. */
	private fun key(ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:d/geometry", ordinal)

	/** Shift+click ADDS an unselected mark, leaving everything already selected alone. */
	@Test
	fun anUnselectedMarkIsAdded() {
		assertEquals(setOf(key(0), key(2)), selectionAfterAdditiveClick(setOf(key(0)), setOf(key(2))))
		assertEquals(setOf(key(1)), selectionAfterAdditiveClick(emptySet(), setOf(key(1))), "from nothing selected")
	}

	/**
	 * Shift+click REMOVES an already-selected mark.
	 *
	 * Toggle rather than add-only: this is the only gesture that can take one mark back out of a selection
	 * without starting over, and without it a mis-click is unrecoverable except by rebuilding the whole set.
	 */
	@Test
	fun anAlreadySelectedMarkIsRemoved() {
		assertEquals(setOf(key(0)), selectionAfterAdditiveClick(setOf(key(0), key(2)), setOf(key(2))))
		assertEquals(emptySet(), selectionAfterAdditiveClick(setOf(key(2)), setOf(key(2))), "down to nothing")
	}

	/**
	 * A summary mark toggles as ONE unit, and a partly-selected one COMPLETES rather than clearing.
	 *
	 * A collapsed group shows the user a single mark standing for several keys; removing only some of them
	 * would leave a selection whose state the mark cannot express.  Treating partly-selected as "not
	 * selected" is what lets a second Shift+click undo the first.
	 */
	@Test
	fun aSummaryTogglesAsOneUnit() {
		val members = setOf(key(0), key(1), key(2))
		assertEquals(members, selectionAfterAdditiveClick(emptySet(), members), "none selected -> all")
		assertEquals(members, selectionAfterAdditiveClick(setOf(key(1)), members), "partly selected -> all")
		assertEquals(emptySet(), selectionAfterAdditiveClick(members, members), "all selected -> none")
	}

	/** A mark standing for nothing leaves the selection exactly as it was, rather than clearing it. */
	@Test
	fun anEmptyClickChangesNothing() {
		val selection = setOf(key(0), key(1))
		assertEquals(selection, selectionAfterAdditiveClick(selection, emptySet()))
	}
}