package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.ParameterSelection
import org.umamo.edit.parameter.createParameter
import org.umamo.edit.parameter.renameParameter
import org.umamo.runtime.model.ParameterKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins how the panel writes the pose: a scrub streams previews and records nothing until release, a
 * discrete edit is one step, and the controls follow a pose that moved somewhere else.
 *
 * The two halves of a scrub are what every undo in the editor rests on.  A preview that reached the
 * session would turn one drag into a step per pointer move; a release that did not would lose the drag.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersScrubInteractionTest {
	/** Mid-drag the renderer has the new value and the session does not. */
	@Test
	fun aScrubPreviewsWithoutMovingTheCommittedPose() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			pressAndMove(slider.at(0.6f), listOf(slider.at(0.7f), slider.at(0.8f)))

			assertNear(6f, harness.live(PanelIds.bodyX), "the renderer has to see the scrub as it happens")
			assertEquals(2f, harness.committed(PanelIds.bodyX), "a preview must never reach the session's pose")
			assertEquals(cursorBefore, harness.historyCursor, "a preview must not record an undo step")
			assertFalse(showsText(PanelValues.BODY_X), "the row's number has to follow the scrub")
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value, "a scrub is a pose gesture and must not retarget")
			releasePress()
		}

	/** The release is what records the drag, as one step. */
	@Test
	fun releasingAScrubCommitsOneUndoStep() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))

			assertEquals(10f, harness.committed(PanelIds.bodyX), "a drag past the end lands on the range's maximum")
			assertEquals(cursorBefore + 1, harness.historyCursor, "a whole drag is one undo step")
			assertTrue(showsText("10.00"))
		}

	/** A tap is a gesture of its own: one preview and one commit. */
	@Test
	fun aTapOnTheSliderIsOneStep() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			clickAt(slider.at(0f))

			assertEquals(-10f, harness.committed(PanelIds.bodyX), "a tap on the left end lands on the range's minimum")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** The pose the session restores is the pose the row shows. */
	@Test
	fun undoAndRedoReposeTheSlider() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))
			assertTrue(showsText("10.00"))

			runOnIdle { harness.session.undo() }
			waitForIdle()
			assertTrue(showsText(PanelValues.BODY_X), "an undo has to put the row back on the pose it restored")
			assertEquals(2f, harness.live(PanelIds.bodyX), "and the renderer with it")

			runOnIdle { harness.session.redo() }
			waitForIdle()
			assertTrue(showsText("10.00"), "a redo has to put it forward again")
		}

	/** A pad writes two parameters, and still records one step. */
	@Test
	fun aPadDragMovesBothAxesAndCommitsOnce() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val cursorBefore = harness.historyCursor

			pressAndMove(pad.center, listOf(pad.at(0.7f, 0.3f), Offset(pad.right + 40f, pad.top - 40f)))
			assertEquals(3f, harness.committed(PanelIds.angleX), "a pad preview must never reach the session's pose")
			assertEquals(-4f, harness.committed(PanelIds.angleY))
			assertEquals(30f, harness.live(PanelIds.angleX), "right of the pad is the horizontal maximum")
			assertEquals(30f, harness.live(PanelIds.angleY), "above the pad is the vertical maximum")

			releasePress()
			assertEquals(30f, harness.committed(PanelIds.angleX))
			assertEquals(30f, harness.committed(PanelIds.angleY))
			assertEquals(cursorBefore + 1, harness.historyCursor, "both axes of one drag are one undo step")
		}

	/** A typed value is a discrete edit, held to the parameter's own range. */
	@Test
	fun aTypedValueCommitsOneStepAndClampsToTheRange() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField(PanelValues.BODY_X, "7")
			assertEquals(7f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor, "a typed value is one undo step")

			typeIntoNumberField("7.00", "99")
			assertEquals(10f, harness.committed(PanelIds.bodyX), "a value past the range lands on its end")
		}

	/** The glyph is an offer to go back, so a row already on its default has none. */
	@Test
	fun theResetGlyphShowsOnlyOffDefault() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			// Every axis the list shows is off its default but Eye Open.
			assertEquals(6, countOfDescription(harness.text.reset))
		}

	/** Reset is a typed value by another name: one step, to the default. */
	@Test
	fun theResetGlyphResetsAsOneStep() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor
			// In list order the glyphs belong to Smile Shape, Smile, Angle X, Angle Y, Body X, and Breath.
			val bodyReset = onAllNodesWithContentDescription(harness.text.reset, useUnmergedTree = true)[4]

			clickAt(panelBoundsOf(bodyReset).center)

			assertEquals(0f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertEquals(5, countOfDescription(harness.text.reset), "a row back on its default loses its glyph")
		}

	/** The keyform sheet scrubs the same pose; the sliders have to move while it does, not after. */
	@Test
	fun aPreviewFromOutsideMovesTheSlider() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			runOnIdle { harness.liveParamsHandle.preview(PanelIds.bodyX, 7f) }
			waitForIdle()

			assertTrue(showsText("7.00"), "a preview made elsewhere has to reach the row")
			assertEquals(2f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/**
	 * The values the rows show outlive an edit that adds or removes no parameter, and are reseeded by one
	 * that does.  With no live handle nothing but the panel's own state holds a scrubbed value, which is
	 * what lets this tell the two apart.
	 */
	@Test
	fun shownValuesSurviveARenameAndAreReseededByACreate() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(provideLiveParams = false)
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))
			assertTrue(showsText("10.00"))
			assertEquals(2f, harness.committed(PanelIds.bodyX), "with no handle the scrub reaches nothing but the row")

			runOnIdle { harness.session.renameParameter(PanelIds.breath, "Air") }
			waitForIdle()
			assertTrue(showsText("Air"))
			assertTrue(showsText("10.00"), "a rename adds no parameter, so the shown values stay")

			runOnIdle { harness.session.createParameter("Extra", ParameterKind.NORMAL) }
			waitForIdle()
			assertTrue(showsText(PanelValues.BODY_X), "a create rebuilds the shown values from the committed pose")
			assertFalse(showsText("10.00"))
		}

	/** The follow effects hold the values they write to; a create replaces those, and they must follow. */
	@Test
	fun theSliderStillFollowsAfterACreate() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.session.createParameter("Extra", ParameterKind.NORMAL) }
			waitForIdle()
			runOnIdle { harness.liveParamsHandle.preview(PanelIds.bodyX, 7f) }
			waitForIdle()

			assertTrue(showsText("7.00"), "a preview has to reach the row after the parameter list changed")
		}

	/** A scrub outlives the panel recomposing under it, which a scrub causes on every move. */
	@Test
	fun aScrubSurvivesThePanelRecomposing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			pressAndMove(slider.at(0.6f), listOf(slider.at(0.7f)))
			// Inert with nothing selected, so the list is unchanged; what it does is rebuild the panel body.
			runOnIdle { harness.viewState.showOnlySelected = true }
			waitForIdle()
			moveOn(listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))
			releasePress()

			assertEquals(10f, harness.committed(PanelIds.bodyX), "the drag has to keep driving the row it started on")
			assertEquals(cursorBefore + 1, harness.historyCursor, "and still be one step")
		}

	/** With no handle there is nothing to write to, and the panel must not invent a step. */
	@Test
	fun withNoLiveHandleAScrubRecordsNothing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(provideLiveParams = false)
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			drag(slider.at(0.6f), listOf(slider.at(0.8f)))

			assertEquals(2f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor)
		}
}