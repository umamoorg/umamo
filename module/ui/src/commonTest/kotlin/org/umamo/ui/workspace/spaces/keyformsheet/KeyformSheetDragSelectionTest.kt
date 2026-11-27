package org.umamo.ui.workspace.spaces.keyformsheet

import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackKeyShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a drag does to the keyform sheet's selection, and which drags move the whole selection.
 *
 * Dragging a mark that is not selected selects it first, the way a click on it would, and the drag then works
 * on that selection.  A summary mark always drags as a group, because the keys it stands for are only
 * reachable through the selection.
 */
class KeyformSheetDragSelectionTest {
	private val angleX = ParameterId("ParamAngleX")
	private val parameter = Parameter(angleX, "ParamAngleX", min = -10f, max = 10f, default = 0f)

	/**
	 * A key on the geometry row at [ordinal].
	 *
	 * @param Int ordinal The key's ordinal.
	 * @return TrackKeyRef The key.
	 */
	private fun key(ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:d/geometry", ordinal)

	/**
	 * A key on the opacity row at [ordinal].
	 *
	 * @param Int ordinal The key's ordinal.
	 * @return TrackKeyRef The key.
	 */
	private fun opacityKey(ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:d/OPACITY", ordinal)

	/** A mark at 0, where every drag below starts. */
	private val mark = TrackKeyMark(0, 0f, TrackKeyShape.Circle)

	/** A drag of a key already selected works on the selection as it is. */
	@Test
	fun aSelectedKeyDragsTheSelectionAsItIs() {
		val selection = setOf(key(0), key(2))
		assertEquals(selection, selectionDraggedWith(selection, listOf(key(0))))
	}

	/** A drag of a key not selected replaces the selection with it, whatever was selected, or with nothing selected. */
	@Test
	fun anUnselectedKeyReplacesTheSelection() {
		assertEquals(setOf(key(1)), selectionDraggedWith(setOf(key(0), opacityKey(2)), listOf(key(1))))
		assertEquals(setOf(key(1)), selectionDraggedWith(emptySet(), listOf(key(1))), "from nothing selected")
	}

	/** A summary counts as selected only when every key it stands for is, so a partly-selected one is replaced whole. */
	@Test
	fun aPartlySelectedSummaryReplacesTheSelection() {
		val members = listOf(key(1), opacityKey(0))
		assertEquals(members.toSet(), selectionDraggedWith(setOf(key(1), key(2)), members))
		val withMore = setOf(key(1), opacityKey(0), key(2))
		assertEquals(withMore, selectionDraggedWith(withMore, members), "fully selected, the selection stands")
	}

	/** A plain mark drags alone when it is the whole selection, and with the selection when anything else is in it. */
	@Test
	fun aPlainMarkDragsAsAGroupOnlyWithCompany() {
		assertNull(groupDragFraction(parameter, setOf(key(0)), listOf(key(0)), summary = false, mark = mark, at = 2f))
		assertEquals(0.1f, groupDragFraction(parameter, setOf(key(0), key(2)), listOf(key(0)), summary = false, mark = mark, at = 2f))
	}

	/** A summary mark drags as a group even when its keys are the whole selection, even a summary of one key. */
	@Test
	fun aSummaryAlwaysDragsAsAGroup() {
		assertEquals(0.1f, groupDragFraction(parameter, setOf(key(0)), listOf(key(0)), summary = true, mark = mark, at = 2f))
		assertEquals(
			-0.25f,
			groupDragFraction(parameter, setOf(key(0), opacityKey(0)), listOf(key(0), opacityKey(0)), summary = true, mark = mark, at = -5f),
		)
	}

	/** No drag is a group drag when the dragged keys are not all in the selection it works on, or the range is empty. */
	@Test
	fun noGroupWithoutTheDraggedKeysOrARange() {
		assertNull(groupDragFraction(parameter, setOf(key(2), key(3)), listOf(key(0)), summary = false, mark = mark, at = 2f))
		val fixed = Parameter(angleX, "ParamAngleX", min = 0f, max = 0f, default = 0f)
		assertNull(groupDragFraction(fixed, setOf(key(0)), listOf(key(0)), summary = true, mark = mark, at = 0f))
	}
}