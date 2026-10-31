package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TrackKeyRef
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.secondaryClickAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sheet's two context menus: a lane's, which inserts or deletes the key where it was opened, and a
 * group row label's, which selects the thing the row names.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetMenuTest {
	/** On empty track the lane's menu offers Insert, which adds a key where the menu was opened, as one step. */
	@Test
	fun theLaneMenuInsertsAKeyWhereItWasOpened() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val cursorBefore = harness.historyCursor

			secondaryClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f))
			assertFalse(popupShows(harness.text.keyformDelete), "empty track has no key to delete")
			clickMenuEntry(harness.text.keyformInsert)

			assertKeysNear(listOf(-5f, 0f, 2.5f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "a key at the spot")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/**
	 * On a mark the lane's menu offers Delete, which removes that key and keeps the selection on the keys it
	 * named: a selected key above the removed one is renumbered, not swapped for its neighbour.
	 */
	@Test
	fun theLaneMenuDeletesTheKeyUnderThePointer() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f))
			val cursorBefore = harness.historyCursor

			secondaryClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 0f))
			assertFalse(popupShows(harness.text.keyformInsert), "a key's own spot is taken")
			clickMenuEntry(harness.text.keyformDelete)

			assertEquals(listOf(-5f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(setOf(TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 1)), harness.session.keySelection.value, "still the key at 5")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A summary mark's Delete removes every key it stands for, and renumbers the selection around them. */
	@Test
	fun aSummaryMenuDeletesEveryKeyItStandsFor() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(chevronPoint(harness, SheetRows.DRAWABLE))
			clickAt(lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 5f))
			val cursorBefore = harness.historyCursor

			secondaryClickAt(lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 0f))
			assertFalse(popupShows(harness.text.keyformInsert), "a folded group cannot say which track a new key goes on")
			clickMenuEntry(harness.text.keyformDelete)

			assertEquals(listOf(-5f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(listOf(5f, 8f), opacityKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(
				setOf(TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 1), TrackKeyRef(PanelIds.bodyX, SheetRows.OPACITY, 0)),
				harness.session.keySelection.value,
				"the keys at 5 stay selected under their new ordinals",
			)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** In Edit mode, which changes no key, the lane menu's Insert is still offered but greyed out: choosing it adds nothing. */
	@Test
	fun inEditModeTheLaneMenusInsertAddsNoKey() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor

			secondaryClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f))
			assertTrue(popupShows(harness.text.keyformInsert), "the entry is still offered")
			clickMenuEntry(harness.text.keyformInsert)

			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** In Edit mode the lane menu's Delete on a mark is greyed out the same way: choosing it removes nothing. */
	@Test
	fun inEditModeTheLaneMenusDeleteRemovesNoKey() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor

			secondaryClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 0f))
			assertTrue(popupShows(harness.text.keyformDelete), "the entry is still offered")
			clickMenuEntry(harness.text.keyformDelete)

			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/**
	 * In Edit mode a summary mark's Delete removes nothing either.  It calls the session rather than a command, so
	 * no command availability guards it and the greyed entry is the whole of the gate.
	 */
	@Test
	fun inEditModeASummaryMenusDeleteRemovesNoKey() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(chevronPoint(harness, SheetRows.DRAWABLE))
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor

			secondaryClickAt(lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 0f))
			assertTrue(popupShows(harness.text.keyformDelete), "the entry is still offered")
			clickMenuEntry(harness.text.keyformDelete)

			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A group row's label offers to select what it names: a drawable. */
	@Test
	fun aDrawableGroupsLabelSelectsTheDrawable() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))

			secondaryClickAt(labelPoint(harness, SheetRows.DRAWABLE))
			clickMenuEntry(harness.text.selectDrawable)

			val target = SelectionTarget.Drawable(PanelIds.drawable)
			assertEquals(Selection(setOf(target), target), harness.session.selection.value)
		}

	/** A group row's label offers to select what it names: a part. */
	@Test
	fun aPartGroupsLabelSelectsThePart() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))

			secondaryClickAt(labelPoint(harness, SheetRows.PART))
			assertTrue(popupShows(harness.text.selectPart), "the entry names the owner's kind")
			clickMenuEntry(harness.text.selectPart)

			val target = SelectionTarget.Part(SheetIds.part)
			assertEquals(Selection(setOf(target), target), harness.session.selection.value)
		}

	/** A track row's label names a property, not a thing in the rig, so it opens no menu at all. */
	@Test
	fun aTracksLabelOpensNoMenu() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))

			secondaryClickAt(labelPoint(harness, SheetRows.GEOMETRY))

			assertFalse(anyPopupOpen())
		}
}