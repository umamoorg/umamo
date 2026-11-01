package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.spaces.keyformsheet.shiftClickAt
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.doubleClickAt
import org.umamo.ui.workspace.spaces.parameters.pressKey
import org.umamo.ui.workspace.spaces.parameters.renameFieldOpen
import org.umamo.ui.workspace.spaces.parameters.typeIntoRenameField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what a press on a row does: where a click, a modified click, a chevron, an eye, a pointer, and a
 * double click each land, and how an armed relation pick claims the click instead of the selection.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerGestureTest {
	private val head = SelectionTarget.Part(OutlinerIds.head)
	private val hair = SelectionTarget.Part(OutlinerIds.hair)
	private val eye = SelectionTarget.Drawable(OutlinerIds.eye)
	private val loose = SelectionTarget.Drawable(OutlinerIds.loose)

	/** A plain click replaces the selection with the row. */
	@Test
	fun aClickSelectsTheRow() =
		runComposeUiTest {
			val harness = mountOutliner()

			clickAt(rowBox(OutlinerNames.HEAD).center)

			assertEquals(Selection(setOf(head), head), harness.session.selection.value)
		}

	/** A Ctrl click toggles the row in and out of the selection and leaves the rest as it was. */
	@Test
	fun aCtrlClickTogglesTheRow() =
		runComposeUiTest {
			val harness = mountOutliner()
			clickAt(rowBox(OutlinerNames.HEAD).center)

			ctrlClickAt(rowBox(OutlinerNames.LOOSE).center)
			assertEquals(Selection(setOf(head, loose), loose), harness.session.selection.value)

			ctrlClickAt(rowBox(OutlinerNames.HEAD).center)
			assertEquals(setOf(loose), harness.session.selection.value.targets)
		}

	/** A Shift click adds the run of visible rows from the active row to the clicked one. */
	@Test
	fun aShiftClickRangesOverTheVisibleRows() =
		runComposeUiTest {
			val harness = mountOutliner()
			clickAt(slotPoint(harness.text.expand, OutlinerNames.HEAD))
			clickAt(rowBox(OutlinerNames.HEAD).center)

			shiftClickAt(rowBox(OutlinerNames.LOOSE).center)

			assertEquals(Selection(setOf(head, eye, hair, loose), loose), harness.session.selection.value)
		}

	/** A chevron press opens and closes the branch and selects nothing. */
	@Test
	fun aChevronPressFoldsAndNeverSelects() =
		runComposeUiTest {
			val harness = mountOutliner()
			assertFalse(outlinerShows(OutlinerNames.EYE), "the branch starts closed")

			clickAt(slotPoint(harness.text.expand, OutlinerNames.HEAD))

			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD))
			assertTrue(outlinerShows(OutlinerNames.EYE))
			assertTrue(harness.session.selection.value.isEmpty)

			clickAt(slotPoint(harness.text.collapse, OutlinerNames.HEAD))

			assertFalse(outlinerShows(OutlinerNames.EYE))
			assertTrue(harness.session.selection.value.isEmpty)
		}

	/** An eye press hides the row's part as one undo step and selects nothing. */
	@Test
	fun anEyePressHidesTheRowAndNeverSelects() =
		runComposeUiTest {
			val harness = mountOutliner()
			val cursorBefore = harness.historyCursor

			clickAt(slotPoint(harness.text.toggleVisibility, OutlinerNames.HEAD))

			assertFalse(partOf(harness, OutlinerIds.head).isVisible)
			assertTrue(harness.session.selection.value.isEmpty)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A pointer press flips whether the row's part can be picked in the viewport, and selects nothing. */
	@Test
	fun aPointerPressFlipsSelectability() =
		runComposeUiTest {
			val harness = mountOutliner()

			clickAt(slotPoint(harness.text.toggleSelectable, OutlinerNames.HEAD))

			assertFalse(partOf(harness, OutlinerIds.head).isSelectable)
			assertTrue(harness.session.selection.value.isEmpty)
		}

	/** With Shift held, the eye sets the part and everything under it to one value. */
	@Test
	fun aShiftEyePressSetsTheWholeSubtree() =
		runComposeUiTest {
			val harness = mountOutliner()

			shiftClickAt(slotPoint(harness.text.toggleVisibility, OutlinerNames.HEAD))

			assertFalse(partOf(harness, OutlinerIds.head).isVisible)
			assertFalse(drawableOf(harness, OutlinerIds.eye).isVisible)
			assertFalse(partOf(harness, OutlinerIds.hair).isVisible)
			assertFalse(drawableOf(harness, OutlinerIds.bang).isVisible)
			assertTrue(drawableOf(harness, OutlinerIds.loose).isVisible, "a row outside the subtree is untouched")
		}

	/** A double click opens the row's inline rename, and Escape abandons it with the name unchanged. */
	@Test
	fun aDoubleClickOpensRenameAndEscapeCancels() =
		runComposeUiTest {
			val harness = mountOutliner()

			doubleClickAt(rowBox(OutlinerNames.HEAD).center)
			assertTrue(renameFieldOpen())
			typeIntoRenameField("Skull")
			pressKey(Key.Escape)

			assertFalse(renameFieldOpen())
			assertEquals(OutlinerNames.HEAD, partOf(harness, OutlinerIds.head).name)
			assertTrue(outlinerShows(OutlinerNames.HEAD))
		}

	/** Enter commits the rename through the session as one undo step. */
	@Test
	fun aRenameCommitsOnEnter() =
		runComposeUiTest {
			val harness = mountOutliner()
			doubleClickAt(rowBox(OutlinerNames.HEAD).center)
			val cursorBefore = harness.historyCursor

			typeIntoRenameField("Skull")
			pressKey(Key.Enter)

			assertFalse(renameFieldOpen())
			assertEquals("Skull", partOf(harness, OutlinerIds.head).name)
			assertTrue(outlinerShows("Skull"))
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A click on the synthetic puppet row folds it, and it has nothing to select. */
	@Test
	fun aClickOnThePuppetRowFoldsIt() =
		runComposeUiTest {
			val harness = mountOutliner()

			clickAt(rowBox(harness.text.outlinerRoot).center)

			assertFalse(harness.outlinerViewState.isOpen(OUTLINER_ROOT_ID))
			assertFalse(outlinerShows(OutlinerNames.HEAD))
			assertTrue(harness.session.selection.value.isEmpty)
		}

	/** While a relation pick is armed, a click on a row it accepts binds the row and selects nothing. */
	@Test
	fun anArmedPickResolvesAnAcceptedClickWithoutSelecting() =
		runComposeUiTest {
			val harness = mountOutliner()
			var picked: SelectionTarget? = null
			runOnIdle { harness.relationPick.arm(setOf(PickKind.Part)) { target -> picked = target } }

			clickAt(rowBox(OutlinerNames.HEAD).center)

			assertEquals(head, picked)
			assertNull(harness.relationPick.request, "the pick is over")
			assertTrue(harness.session.selection.value.isEmpty)
		}

	/** While a relation pick is armed, a click on a row it does not accept is swallowed, and the pick stays armed. */
	@Test
	fun anArmedPickSwallowsAClickOnAnUnacceptedRow() =
		runComposeUiTest {
			val harness = mountOutliner()
			var picked: SelectionTarget? = null
			runOnIdle { harness.relationPick.arm(setOf(PickKind.Part)) { target -> picked = target } }

			clickAt(rowBox(OutlinerNames.LOOSE).center)

			assertNull(picked)
			assertNotNull(harness.relationPick.request, "the pick stays armed")
			assertTrue(harness.session.selection.value.isEmpty)
		}

	/** While a relation pick is armed, resting on a row reports it as what a click there would bind. */
	@Test
	fun anArmedPickFollowsTheHoveredRow() =
		runComposeUiTest {
			val harness = mountOutliner()
			runOnIdle { harness.relationPick.arm(setOf(PickKind.Part)) {} }

			hoverAt(rowBox(OutlinerNames.HEAD).center)
			assertEquals(head, harness.relationPick.hoveredTarget)

			hoverAt(rowBox(OutlinerNames.LOOSE).center)
			assertNull(harness.relationPick.hoveredTarget, "a drawable is not a kind the pick accepts")
		}
}