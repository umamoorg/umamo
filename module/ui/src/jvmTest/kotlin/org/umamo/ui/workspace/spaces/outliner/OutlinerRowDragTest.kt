package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.runtime.model.OrgChild
import org.umamo.ui.workspace.spaces.keyformsheet.anyPopupOpen
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.moveOn
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.popupTextInWindow
import org.umamo.ui.workspace.spaces.parameters.pressKey
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the long-press drag: where a drop lands, what it refuses, and what ends a drag without a drop.  The
 * bands a drop reads are the ones the row indicator draws (:edit's outlinerDropBandFor), so each case
 * names its band by how far down the target row it drops.
 *
 * The press that starts a drag selects the row first, as any press does, so every case selects the row
 * with a click beforehand: the drag's own press then records nothing, and one history step is the move.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerRowDragTest {
	/**
	 * The points a drag moves through on its way to [target]: one a few pixels off the press, so the drag
	 * has moved before it arrives, then the target itself.
	 *
	 * @param Offset from Where the drag was pressed.
	 * @param Offset target Where it is going.
	 * @return List<Offset> The points to move through.
	 */
	private fun pathTo(from: Offset, target: Offset): List<Offset> = listOf(Offset(from.x + 4f, from.y + 6f), target)

	/** A drop on a part's middle band nests the row at the end of the part's children and opens the part. */
	@Test
	fun aDropOnAPartsMiddleNestsAndOpensIt() =
		runComposeUiTest {
			val harness = mountOutliner()
			val loose = rowBox(OutlinerNames.LOOSE).center
			clickAt(loose)
			val cursorBefore = harness.historyCursor

			longPressAndMove(loose, pathTo(loose, rowBandPoint(OutlinerNames.HEAD, 0.5f)))
			releasePress()

			assertEquals(
				listOf(OrgChild.Drawable(OutlinerIds.eye), OrgChild.Part(OutlinerIds.hair), OrgChild.Drawable(OutlinerIds.loose)),
				partOf(harness, OutlinerIds.head).children,
			)
			assertEquals(listOf(OrgChild.Part(OutlinerIds.head), OrgChild.Part(OutlinerIds.limbs)), harness.session.model.value.rootChildren)
			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD), "the destination opens so the moved row can be seen")
			assertTrue(outlinerShows(OutlinerNames.EYE))
			assertEquals(cursorBefore + 1, harness.historyCursor, "a move is one undo step")
		}

	/** A drop on a part's upper band lands the row before the part, among the part's siblings. */
	@Test
	fun aDropOnAPartsUpperBandLandsBefore() =
		runComposeUiTest {
			val harness = mountOutliner()
			val limbs = rowBox(OutlinerNames.LIMBS).center
			clickAt(limbs)

			longPressAndMove(limbs, pathTo(limbs, rowBandPoint(OutlinerNames.HEAD, 0.15f)))
			releasePress()

			assertEquals(
				listOf(OrgChild.Part(OutlinerIds.limbs), OrgChild.Part(OutlinerIds.head), OrgChild.Drawable(OutlinerIds.loose)),
				harness.session.model.value.rootChildren,
			)
			assertFalse(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD), "a reorder opens nothing")
		}

	/** A drop on a drawable's lower half lands the row after the drawable. */
	@Test
	fun aDropOnADrawablesLowerHalfLandsAfter() =
		runComposeUiTest {
			val harness = mountOutliner()
			val head = rowBox(OutlinerNames.HEAD).center
			clickAt(head)

			longPressAndMove(head, pathTo(head, rowBandPoint(OutlinerNames.LOOSE, 0.8f)))
			releasePress()

			assertEquals(
				listOf(OrgChild.Drawable(OutlinerIds.loose), OrgChild.Part(OutlinerIds.head), OrgChild.Part(OutlinerIds.limbs)),
				harness.session.model.value.rootChildren,
			)
		}

	/** A deformer dropped on a part is refused: the armature and the org tree never mix. */
	@Test
	fun aCrossDomainDropIsRefused() =
		runComposeUiTest {
			val harness = mountOutliner()
			clickAt(slotPoint(harness.text.expand, harness.text.outlinerArmature))
			val rootWarp = rowBox(OutlinerNames.ROOT_WARP).center
			clickAt(rootWarp)
			val modelBefore = harness.session.model.value
			val cursorBefore = harness.historyCursor

			longPressAndMove(rootWarp, pathTo(rootWarp, rowBandPoint(OutlinerNames.HEAD, 0.5f)))
			releasePress()

			assertEquals(modelBefore, harness.session.model.value)
			assertEquals(cursorBefore, harness.historyCursor, "a refused drop records nothing")
		}

	/** A drag released back over its own row drops nowhere. */
	@Test
	fun aDropBackOnItselfChangesNothing() =
		runComposeUiTest {
			val harness = mountOutliner()
			val loose = rowBox(OutlinerNames.LOOSE).center
			clickAt(loose)
			val modelBefore = harness.session.model.value

			longPressAndMove(loose, listOf(rowBandPoint(OutlinerNames.HEAD, 0.5f), loose))
			releasePress()

			assertEquals(modelBefore, harness.session.model.value)
		}

	/** A chip naming the dragged row follows the pointer while the drag is in flight, and goes with it. */
	@Test
	fun theDragChipNamesTheRow() =
		runComposeUiTest {
			mountOutliner()
			val loose = rowBox(OutlinerNames.LOOSE).center
			clickAt(loose)

			longPressAndMove(loose, pathTo(loose, rowBandPoint(OutlinerNames.HEAD, 0.5f)))
			assertTrue(popupShows(OutlinerNames.LOOSE), "the chip must really be up")

			releasePress()

			assertFalse(anyPopupOpen())
		}

	/** The chip moves as far as the pointer does, and the same way. */
	@Test
	fun theDragChipFollowsThePointer() =
		runComposeUiTest {
			mountOutliner()
			val loose = rowBox(OutlinerNames.LOOSE).center
			clickAt(loose)
			val first = rowBandPoint(OutlinerNames.HEAD, 0.5f)
			val second = Offset(first.x + 37f, rowBandPoint(OutlinerNames.LIMBS, 0.5f).y)

			longPressAndMove(loose, pathTo(loose, first))
			val chipAtFirst = popupTextInWindow(OutlinerNames.LOOSE)
			moveOn(listOf(second))
			val chipAtSecond = popupTextInWindow(OutlinerNames.LOOSE)

			assertEquals(second.x - first.x, chipAtSecond.left - chipAtFirst.left, CHIP_TOLERANCE)
			assertEquals(second.y - first.y, chipAtSecond.top - chipAtFirst.top, CHIP_TOLERANCE)
			pressKey(Key.Escape)
			releasePress()
		}

	/** Escape aborts a drag in flight: the chip goes, and the release then drops nothing. */
	@Test
	fun escapeCancelsADragInFlight() =
		runComposeUiTest {
			val harness = mountOutliner()
			val loose = rowBox(OutlinerNames.LOOSE).center
			clickAt(loose)
			val modelBefore = harness.session.model.value
			longPressAndMove(loose, pathTo(loose, rowBandPoint(OutlinerNames.HEAD, 0.5f)))
			assertTrue(popupShows(OutlinerNames.LOOSE), "the drag must really be in flight")

			pressKey(Key.Escape)
			assertFalse(anyPopupOpen(), "the chip goes with the drag")
			releasePress()

			assertEquals(modelBefore, harness.session.model.value)
		}

	private companion object {
		/** The chip lands on whole pixels, so it may sit up to one off the pointer's own move. */
		const val CHIP_TOLERANCE = 1f
	}
}