package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the document edits a row offers besides its name: the range editor's three fields and its Repeat
 * checkbox, and the link that folds two sliders into one pad.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersRangeAndLinkTest {
	/** Each field of the range editor commits on its own, as one step. */
	@Test
	fun eachRangeFieldIsOneStep() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField("10.00", "20")
			assertEquals(20f, parameterOf(harness, PanelIds.bodyX).max)
			assertEquals(cursorBefore + 1, harness.historyCursor)

			typeIntoNumberField("-10.00", "-5")
			assertEquals(-5f, parameterOf(harness, PanelIds.bodyX).min)
			assertEquals(cursorBefore + 2, harness.historyCursor)

			typeIntoNumberField("0.00", "1")
			assertEquals(1f, parameterOf(harness, PanelIds.bodyX).default)
			assertEquals(cursorBefore + 3, harness.historyCursor)
		}

	/** A range field takes a value the row's own field would refuse: the range is what is being changed. */
	@Test
	fun aRangeFieldIsNotHeldToTheRangeItEdits() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoNumberField("10.00", "500")

			assertEquals(500f, parameterOf(harness, PanelIds.bodyX).max)
		}

	/** A range that shrinks under the pose pulls the pose in with it, in the same step. */
	@Test
	fun aShrunkRangeClampsThePose() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField("10.00", "1")

			assertEquals(1f, harness.committed(PanelIds.bodyX), "the pose sat on 2, outside the new range")
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertTrue(showsText("1.00"))
		}

	/** The Repeat checkbox turns the flag on and off, a step each, and a click on it leaves the keyform target alone. */
	@Test
	fun theRepeatCheckboxIsOneStepEachWay() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			// The dial a repeating Body X shows is taller than its slider; folding the group above keeps its editor in the panel.
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor
			val targetBefore = harness.session.parameterSelection.value

			clickAt(panelBoundsOfText(harness.text.repeat).center)

			assertTrue(parameterOf(harness, PanelIds.bodyX).repeat)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertEquals(targetBefore, harness.session.parameterSelection.value, "a click on the checkbox must not reach the island")

			clickAt(panelBoundsOfText(harness.text.repeat).center)

			assertFalse(parameterOf(harness, PanelIds.bodyX).repeat)
			assertEquals(cursorBefore + 2, harness.historyCursor)
		}

	/** A pad's editor holds a Repeat per axis, and each sets its own axis. */
	@Test
	fun aPadsRepeatCheckboxesSetTheirOwnAxis() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			// Folding the group above lifts the pad, so the editor under it sits well inside the panel.
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)
			val checkboxes = onAllNodesWithText(harness.text.repeat, useUnmergedTree = true)
			assertEquals(2, checkboxes.fetchSemanticsNodes().size)

			clickAt(panelBoundsOf(checkboxes[1]).center)

			assertTrue(parameterOf(harness, PanelIds.angleY).repeat)
			assertFalse(parameterOf(harness, PanelIds.angleX).repeat)
		}

	/** A runtime below 5.3 offers no Repeat on a parameter that does not repeat. */
	@Test
	fun aRuntimeWithoutRepeatShowsNoCheckbox() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(runtimeTarget = RuntimeTarget.Cubism50)
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertTrue(showsText(harness.text.rangeMinimum), "the range editor has to be open for the checkbox's absence to mean anything")
			assertFalse(showsText(harness.text.repeat))
		}

	/** Below 5.3 a parameter that already repeats shows its checkbox until it is turned off. */
	@Test
	fun aRuntimeWithoutRepeatShowsAFlagUntilItIsOff() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(RuntimeTarget.Cubism50, repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			// The dial a repeating Body X shows is taller than its slider; folding the group above keeps its editor in the panel.
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertTrue(showsText(harness.text.repeat), "a flag the target cannot author still shows")

			clickAt(panelBoundsOfText(harness.text.repeat).center)

			assertFalse(parameterOf(harness, PanelIds.bodyX).repeat)
			assertFalse(showsText(harness.text.repeat), "once off, the target has nothing to offer")
		}

	/** A link folds a slider and the one below it into a pad; an unlink splits them again. */
	@Test
	fun aLinkFoldsTwoSlidersAndAnUnlinkSplitsThem() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			// In list order the link glyphs belong to Eye Open, Smile Shape, and Body X.
			clickDescribed(harness.text.link, index = 2)
			assertTrue(ParameterLink(PanelIds.bodyX, PanelIds.breath) in harness.session.model.value.parameterLinks)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertEquals(2, countOfDescription(harness.text.unlink), "the two sliders became a second pad")
			assertEquals(2, countOfDescription(harness.text.link), "and Body X no longer offers a link of its own")

			clickDescribed(harness.text.unlink, index = 1)
			assertFalse(ParameterLink(PanelIds.bodyX, PanelIds.breath) in harness.session.model.value.parameterLinks)
			assertEquals(cursorBefore + 2, harness.historyCursor)
			assertEquals(1, countOfDescription(harness.text.unlink))
			assertEquals(3, countOfDescription(harness.text.link))
		}

	/** A link keeps both values: the pad opens on the pose the two sliders were showing. */
	@Test
	fun aLinkKeepsThePose() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)

			clickDescribed(harness.text.link, index = 2)

			assertEquals(2f, harness.committed(PanelIds.bodyX))
			assertEquals(0.25f, harness.committed(PanelIds.breath))
			assertTrue(showsText(PanelValues.BODY_X))
			assertTrue(showsText(PanelValues.BREATH))
		}

	/** An open range editor stays open across a link and an unlink, keyed on the upper parameter. */
	@Test
	fun anOpenRangeEditorSurvivesLinkAndUnlink() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertEquals(1, onAllNodesWithText(harness.text.rangeMinimum, useUnmergedTree = true).fetchSemanticsNodes().size)

			clickDescribed(harness.text.link, index = 2)
			assertEquals(
				2,
				onAllNodesWithText(harness.text.rangeMinimum, useUnmergedTree = true).fetchSemanticsNodes().size,
				"the pad's editor holds a range per axis",
			)

			clickDescribed(harness.text.unlink, index = 1)
			assertEquals(1, onAllNodesWithText(harness.text.rangeMinimum, useUnmergedTree = true).fetchSemanticsNodes().size)
			assertEquals(true, harness.viewState.openRangeEditors[PanelIds.bodyX])
		}

	/** A link is offered only where the row below can take one. */
	@Test
	fun aLinkIsOfferedOnlyWhereItCanBeMade() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			// Eye Open and Smile Shape each have a slider below them in their group, and Body X has Breath.
			// Smile is the last of its group, Breath is followed by a parameter with no range, and the pad's
			// axes are linked already.
			assertEquals(3, countOfDescription(harness.text.link))
			assertEquals(1, countOfDescription(harness.text.unlink))
		}
}