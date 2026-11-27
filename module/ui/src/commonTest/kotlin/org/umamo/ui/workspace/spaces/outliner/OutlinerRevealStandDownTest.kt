package org.umamo.ui.workspace.spaces.outliner

import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which change of the active selection the reveal stands down for: the one a click inside the
 * outliner produced, and no other.  The cases that matter are the clicks that produce no change at all,
 * because nothing wakes the reveal to clear up after them.
 */
class OutlinerRevealStandDownTest {
	private val head = SelectionTarget.Part(PartId("head"))
	private val eye = SelectionTarget.Drawable(DrawableId("eye"))

	/** A change nothing marked is one made elsewhere. */
	@Test
	fun aChangeMadeElsewhereIsNotClaimed() {
		val standDown = OutlinerRevealStandDown()

		assertFalse(standDown.claims(head))
	}

	/** A click's own change is claimed, once. */
	@Test
	fun aClicksOwnChangeIsClaimedOnce() {
		val standDown = OutlinerRevealStandDown()
		standDown.clicked(head)

		assertTrue(standDown.claims(head))
		assertFalse(standDown.claims(head), "the same target selected again, from elsewhere, is revealed")
	}

	/**
	 * A click on the row that is active already changes nothing, so no reveal wakes for it.  The next
	 * change is one made elsewhere, and is not claimed.
	 */
	@Test
	fun aClickThatChangedNothingDoesNotClaimTheNextChange() {
		val standDown = OutlinerRevealStandDown()
		standDown.clicked(head)

		assertFalse(standDown.claims(eye), "the selection changed to a target the click did not produce")
		assertFalse(standDown.claims(head), "and the mark did not outlive that change")
	}

	/** A toggle that empties the selection produces no active target, and that change is the click's own too. */
	@Test
	fun aClickThatEmptiesTheSelectionClaimsTheEmptying() {
		val standDown = OutlinerRevealStandDown()
		standDown.clicked(null)

		assertTrue(standDown.claims(null))
	}

	/** With no click marked, a selection emptied from elsewhere is not claimed. */
	@Test
	fun anEmptyingMadeElsewhereIsNotClaimed() {
		val standDown = OutlinerRevealStandDown()

		assertFalse(standDown.claims(null))
	}

	/** The latest click is the one that counts. */
	@Test
	fun aLaterClickReplacesAnEarlierMark() {
		val standDown = OutlinerRevealStandDown()
		standDown.clicked(head)
		standDown.clicked(eye)

		assertTrue(standDown.claims(eye))
	}
}