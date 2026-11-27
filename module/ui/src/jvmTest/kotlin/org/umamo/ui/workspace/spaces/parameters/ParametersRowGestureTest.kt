package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.edit.ParameterSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what a press on each part of a row does: a name, a group header, a grip, and the island's own
 * surface, and the in-place rename a double click opens.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersRowGestureTest {
	/** The range editor opens from the name and closes from it. */
	@Test
	fun aSingleClickOnANameTogglesTheRangeEditor() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX])
			assertTrue(showsText(harness.text.rangeMinimum), "an open range editor shows its three captions")

			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertEquals(false, harness.viewState.openRangeEditors[PanelIds.bodyX])
			assertFalse(showsText(harness.text.rangeMinimum))
		}

	/** The chevron beside the name is the same toggle. */
	@Test
	fun theRangeChevronTogglesTheRangeEditor() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			// In list order the chevrons belong to Eye Open, Smile Shape, Smile, the pad, Body X, and Breath.
			val bodyChevron = onAllNodesWithContentDescription(harness.text.rangeToggle, useUnmergedTree = true)[4]

			clickAt(panelBoundsOf(bodyChevron).center)

			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX])
		}

	/** A press on a name is the name's alone: it opens the range editor and targets nothing. */
	@Test
	fun aClickOnANameLeavesTheTargetAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX], "the press must really have landed on the name")
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value)
			assertEquals(cursorBefore, harness.historyCursor, "an open range editor is view state, not a step")
		}

	/** The chevron beside a slider's name is part of the name's press, and targets nothing either. */
	@Test
	fun aClickOnASlidersChevronLeavesTheTargetAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor
			// In list order the chevrons belong to Eye Open, Smile Shape, Smile, the pad, Body X, and Breath.
			val bodyChevron = onAllNodesWithContentDescription(harness.text.rangeToggle, useUnmergedTree = true)[4]

			clickAt(panelBoundsOf(bodyChevron).center)

			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX])
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A pad's axis names are held to the same. */
	@Test
	fun aClickOnAnAxisNameLeavesTheTargetAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			clickAt(panelBoundsOfText(PanelNames.ANGLE_Y).center)

			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.angleX], "a pad keys its editor on its upper axis")
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A double click renames, and leaves a closed range editor closed: its second press undoes the first's toggle. */
	@Test
	fun aDoubleClickOnANameOpensRenameAndLeavesTheRangeEditorClosed() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			doubleClickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertEquals(PanelIds.bodyX, harness.viewState.renamingParameterId)
			assertTrue(renameFieldOpen())
			assertFalse(harness.viewState.openRangeEditors[PanelIds.bodyX] == true, "renaming a parameter must not open its range editor")
			assertFalse(showsText(harness.text.rangeMinimum))
			assertEquals(cursorBefore, harness.historyCursor, "nothing is recorded until the name commits")
		}

	/** The same from open: the parameter is renamed and its range editor stays open. */
	@Test
	fun aDoubleClickOnANameOpensRenameAndLeavesTheRangeEditorOpen() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertTrue(showsText(harness.text.rangeMinimum))

			doubleClickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertTrue(renameFieldOpen())
			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX], "renaming a parameter must not close its range editor")
			assertTrue(showsText(harness.text.rangeMinimum))
		}

	/** A pad's lower axis shares the upper one's range editor, and a double click on it leaves that one alone. */
	@Test
	fun aDoubleClickOnAnAxisNameLeavesThePadsRangeEditorClosed() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			doubleClickAt(panelBoundsOfText(PanelNames.ANGLE_Y).center)

			assertEquals(PanelIds.angleY, harness.viewState.renamingParameterId)
			assertTrue(renameFieldOpen())
			assertFalse(harness.viewState.openRangeEditors[PanelIds.angleX] == true)
		}

	/** Rename from the menu was never a press on the name, so it has no toggle to undo. */
	@Test
	fun renameFromTheMenuLeavesTheRangeEditorAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.rename)

			assertTrue(renameFieldOpen())
			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX])
		}

	/** Enter commits the name as one step. */
	@Test
	fun aRenameCommitsOnEnter() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			doubleClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoRenameField("Torso")
			pressKey(Key.Enter)

			assertEquals("Torso", parameterOf(harness, PanelIds.bodyX).name)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertNull(harness.viewState.renamingParameterId)
			assertTrue(showsText("Torso"))
		}

	/** Escape abandons the edit and records nothing. */
	@Test
	fun aRenameCancelsOnEscape() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			doubleClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoRenameField("Torso")
			pressKey(Key.Escape)

			assertEquals(PanelNames.BODY_X, parameterOf(harness, PanelIds.bodyX).name)
			assertEquals(cursorBefore, harness.historyCursor)
			assertNull(harness.viewState.renamingParameterId)
			assertFalse(renameFieldOpen())
		}

	/** A name cannot be blanked: an empty commit is a cancel. */
	@Test
	fun aBlankRenameKeepsTheName() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			doubleClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoRenameField("   ")
			pressKey(Key.Enter)

			assertEquals(PanelNames.BODY_X, parameterOf(harness, PanelIds.bodyX).name)
			assertEquals(cursorBefore, harness.historyCursor)
			assertNull(harness.viewState.renamingParameterId)
		}

	/** A press away from the field commits it, the way renaming a file in an explorer does. */
	@Test
	fun clickingAwayCommitsARename() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			doubleClickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoRenameField("Torso")
			onNodeWithTag(PANEL_ELSEWHERE_TAG).performClick()
			waitForIdle()

			assertEquals("Torso", parameterOf(harness, PanelIds.bodyX).name)
			assertNull(harness.viewState.renamingParameterId)
		}

	/** A pad's two names are renamed one at a time. */
	@Test
	fun aPadsAxesRenameIndependently() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			doubleClickAt(panelBoundsOfText(PanelNames.ANGLE_Y).center)
			assertEquals(PanelIds.angleY, harness.viewState.renamingParameterId)
			typeIntoRenameField("Tilt")
			pressKey(Key.Enter)

			assertEquals("Tilt", parameterOf(harness, PanelIds.angleY).name)
			assertEquals(PanelNames.ANGLE_X, parameterOf(harness, PanelIds.angleX).name)
		}

	/** Either axis name, or the chevron the two share, opens one editor holding both axes' ranges. */
	@Test
	fun aPadHasOneRangeEditorForBothAxes() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			clickAt(panelBoundsOfText(PanelNames.ANGLE_Y).center)
			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.angleX], "the pad's editor is keyed on its horizontal axis")
			assertEquals(2, onAllNodesWithText(harness.text.rangeMinimum, useUnmergedTree = true).fetchSemanticsNodes().size)

			val padChevron = onAllNodesWithContentDescription(harness.text.rangeToggle, useUnmergedTree = true)[3]
			clickAt(panelBoundsOf(padChevron).center)
			assertEquals(false, harness.viewState.openRangeEditors[PanelIds.angleX])
		}

	/** A group header folds its rows away and brings them back. */
	@Test
	fun aGroupClickHidesAndShowsItsRows() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			assertFalse(showsText(PanelNames.EYE_OPEN))
			assertTrue(showsText(PanelNames.BODY_X), "only the group's own rows fold")

			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			assertTrue(showsText(PanelNames.EYE_OPEN))
		}

	/** A closed group opens the same way. */
	@Test
	fun aClosedGroupOpensOnAClick() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			assertFalse(showsText(PanelNames.ARM))

			clickAt(panelBoundsOfText(PanelNames.BODY).center)

			assertTrue(showsText(PanelNames.ARM))
		}

	/** A double click renames an open group and leaves it open: the second press undoes the first's toggle. */
	@Test
	fun aDoubleClickOnAnOpenGroupOpensRenameAndLeavesItOpen() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			doubleClickAt(panelBoundsOfText(PanelNames.FACE).center)

			assertEquals(PanelIds.face, harness.viewState.renamingGroupId)
			assertTrue(renameFieldOpen())
			assertTrue(showsText(PanelNames.EYE_OPEN), "renaming a group must not fold it")
		}

	/** The same from closed: the group is renamed and stays closed. */
	@Test
	fun aDoubleClickOnAClosedGroupOpensRenameAndLeavesItClosed() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			doubleClickAt(panelBoundsOfText(PanelNames.BODY).center)

			assertEquals(PanelIds.body, harness.viewState.renamingGroupId)
			assertFalse(showsText(PanelNames.ARM), "renaming a group must not open it")
		}

	/** A group's name commits on Enter and is abandoned on Escape. */
	@Test
	fun aGroupRenameCommitsAndCancels() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			doubleClickAt(panelBoundsOfText(PanelNames.FACE).center)
			typeIntoRenameField("Head")
			pressKey(Key.Enter)
			assertTrue(showsText("Head"))
			assertNull(harness.viewState.renamingGroupId)

			val cursorBefore = harness.historyCursor
			doubleClickAt(panelBoundsOfText("Head").center)
			typeIntoRenameField("Skull")
			pressKey(Key.Escape)
			assertTrue(showsText("Head"))
			assertEquals(cursorBefore, harness.historyCursor)
			assertNull(harness.viewState.renamingGroupId)
		}

	/** The grip means "this row": a click on it targets the row's parameters. */
	@Test
	fun aGripClickTargetsTheRow() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			clickAt(gripBounds(harness, PanelRows.BODY_X).center)
			assertEquals(ParameterSelection.of(PanelIds.bodyX), harness.session.parameterSelection.value)

			clickAt(gripBounds(harness, PanelRows.ANGLE_PAD).center)
			assertEquals(
				ParameterSelection(setOf(PanelIds.angleX, PanelIds.angleY), PanelIds.angleX),
				harness.session.parameterSelection.value,
				"a pad targets both its axes, the horizontal one active",
			)

			val cursorBefore = harness.historyCursor
			clickAt(gripBounds(harness, PanelRows.FACE).center)
			assertEquals(
				ParameterSelection(setOf(PanelIds.angleX, PanelIds.angleY), PanelIds.angleX),
				harness.session.parameterSelection.value,
				"a group owns no parameter, so its grip targets nothing",
			)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A press on the island's own surface targets the row the same way. */
	@Test
	fun anIslandClickTargetsTheRow() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			clickAt(islandSurfacePoint(harness, PanelRows.BODY_X, PanelValues.BODY_X))

			assertEquals(ParameterSelection.of(PanelIds.bodyX), harness.session.parameterSelection.value)
		}

	/**
	 * Nothing in a row takes keyboard focus.  A control that did would take it along when its row is
	 * deleted, and a focus left null kills every shortcut until the next click.
	 */
	@Test
	fun noPressInARowTakesKeyboardFocus() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			// Tall enough that a range editor opening does not push the last rows out of the list.
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			assertTrue(harness.rootFocused, "the fixture's root has to start with the keyboard")
			val descriptions = listOf(harness.text.reset, harness.text.link, harness.text.unlink, harness.text.rangeToggle)

			clickAt(islandSurfacePoint(harness, PanelRows.BODY_X, PanelValues.BODY_X))
			assertTrue(harness.rootFocused, "the island must not take focus")
			clickAt(gripBounds(harness, PanelRows.BODY_X).center)
			assertTrue(harness.rootFocused, "the grip must not take focus")
			clickAt(panelBoundsOfText(PanelNames.BREATH).center)
			assertTrue(harness.rootFocused, "a name must not take focus")
			clickAt(panelBoundsOfText(PanelNames.BODY).center)
			assertTrue(harness.rootFocused, "a group header must not take focus")

			for (description in descriptions) {
				val glyph = onAllNodesWithContentDescription(description, useUnmergedTree = true)[0]
				clickAt(panelBoundsOf(glyph).center)
				assertTrue(harness.rootFocused, "'$description' must not take focus")
			}

			pressBoundChord()
			assertEquals(1, harness.shortcutRuns, "and the keymap still hears its chord")
		}

	/** A rename in progress belongs to its row, so rows folding away above it leave it alone. */
	@Test
	fun aRenameInProgressSurvivesTheListChanging() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			doubleClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			typeIntoRenameField("Air")

			runOnIdle { harness.viewState.expandedGroups[PanelIds.face] = false }
			waitForIdle()

			assertFalse(showsText(PanelNames.EYE_OPEN), "the group above has to have folded")
			assertEquals(PanelIds.breath, harness.viewState.renamingParameterId)
			assertTrue(renameFieldOpen(), "the field has to stay open on the row it belongs to")
			pressKey(Key.Enter)
			assertEquals("Air", parameterOf(harness, PanelIds.breath).name, "and keep what was typed into it")
		}
}