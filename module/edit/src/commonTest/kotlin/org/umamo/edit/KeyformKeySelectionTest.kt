package org.umamo.edit

import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a removal does to the keyform sheet's key selection.
 *
 * The sheet used to drop the whole selection on any removal, which threw away a carefully built multi-key
 * selection for deleting one unrelated mark - and the AIMED removal behind Alt+I and the lane menu did the
 * opposite, leaving the refs untouched so a deleted key's ordinal came to name its neighbour: that mark lit
 * up as selected and the next Delete took it.  Both are the same missing rule, which is why it lives in one
 * place now.
 */
class KeyformKeySelectionTest {
	private val angleX = ParameterId("ParamAngleX")

	/** A key on the geometry row at [ordinal]. */
	private fun key(ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:d/geometry", ordinal)

	/** A key on a different row at [ordinal] - something a removal here must not disturb. */
	private fun otherRowKey(ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:other/geometry", ordinal)

	/** Deleting a mark the user did not select leaves their selection completely alone. */
	@Test
	fun removingAnUnselectedKeyLeavesTheSelectionAlone() {
		val selection = setOf(key(0), key(1))
		assertEquals(selection, selectionAfterKeyRemoval(selection, setOf(otherRowKey(3))))
		assertEquals(selection, selectionAfterKeyRemoval(selection, emptySet()), "and so does removing nothing")
	}

	/** The removed keys drop out, and the ones after them slide down to the ordinals they now hold. */
	@Test
	fun theKeysAfterARemovalRenumber() {
		assertEquals(
			setOf(key(0), key(1), key(2), key(3)),
			selectionAfterKeyRemoval(setOf(key(0), key(2), key(3), key(4)), setOf(key(1))),
			"all four survive, and the three above the removal each slide down one place",
		)
		assertEquals(
			setOf(key(0)),
			selectionAfterKeyRemoval(setOf(key(0), key(2)), setOf(key(2))),
			"a selected key that IS removed simply goes",
		)
		assertEquals(
			setOf(key(1)),
			selectionAfterKeyRemoval(setOf(key(0), key(3)), setOf(key(0), key(1))),
			"two removed below it, and the removed one of its own",
		)
	}

	/**
	 * A removal on one parameter's axis does not renumber another's.
	 *
	 * A linked pad renders the same row under two sections, so a row key alone does not identify the axis a
	 * key sits on - and shifting the other section's ordinals would move that selection onto the wrong marks.
	 */
	@Test
	fun aRemovalOnlyRenumbersItsOwnAxis() {
		val onAngleY = TrackKeyRef(ParameterId("ParamAngleY"), "drawable:d/geometry", 3)
		assertEquals(setOf(onAngleY), selectionAfterKeyRemoval(setOf(onAngleY), setOf(key(0))))
	}

	/**
	 * An insert shifts the selection up from where it lands, and leaves everything below it alone.
	 *
	 * The asymmetry is exactly what the bug looked like: inserting to the RIGHT of a selected mark behaved,
	 * while inserting to the LEFT handed that mark's ordinal to the new key, so the new key showed as
	 * selected instead.
	 */
	@Test
	fun anInsertShiftsOnlyTheKeysAboveIt() {
		assertEquals(
			setOf(key(3)),
			selectionAfterKeyInsertion(setOf(key(2)), key(0)),
			"inserted below: the selected mark moves up one ordinal to stay on the same key",
		)
		assertEquals(
			setOf(key(2)),
			selectionAfterKeyInsertion(setOf(key(2)), key(3)),
			"inserted above: nothing renumbers",
		)
		assertEquals(
			setOf(key(3)),
			selectionAfterKeyInsertion(setOf(key(2)), key(2)),
			"inserted AT the selected ordinal: the new key takes it, so the selection moves up",
		)
	}

	/** An insert on another row or another axis leaves the selection exactly as it was. */
	@Test
	fun anInsertOnlyRenumbersItsOwnRowAndAxis() {
		val selection = setOf(key(2))
		assertEquals(selection, selectionAfterKeyInsertion(selection, otherRowKey(0)))
		assertEquals(
			selection,
			selectionAfterKeyInsertion(selection, TrackKeyRef(ParameterId("ParamAngleY"), "drawable:d/geometry", 0)),
		)
	}
}
