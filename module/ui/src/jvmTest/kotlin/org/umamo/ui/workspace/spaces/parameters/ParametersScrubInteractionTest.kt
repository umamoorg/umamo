package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.ParameterSelection
import org.umamo.edit.parameter.createParameter
import org.umamo.edit.parameter.renameParameter
import org.umamo.runtime.model.ParameterKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

	/** A scrub of the value field drives the rig as it goes, and its release is one step, as a slider drag is. */
	@Test
	fun aFieldScrubPreviewsLiveAndCommitsOnceOnRelease() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val field = numberFieldBounds(PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			pressAndMove(field.center, listOf(field.besideCenter(12f), field.besideCenter(24f)))

			val previewed = harness.live(PanelIds.bodyX)
			assertTrue(previewed != null && previewed > 2f, "the renderer has to see the scrub as it happens, and saw $previewed")
			assertEquals(2f, harness.committed(PanelIds.bodyX), "a preview must never reach the session's pose")
			assertEquals(cursorBefore, harness.historyCursor, "a preview must not record an undo step")

			releasePress()
			assertEquals(previewed, harness.committed(PanelIds.bodyX), "the release commits the value the scrub last showed")
			assertEquals(cursorBefore + 1, harness.historyCursor, "a whole scrub is one undo step")
		}

	/** Escape mid-scrub hands the renderer and the row the committed value back, and the rest of the drag is dead. */
	@Test
	fun escapeMidFieldScrubPutsThePoseBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val field = numberFieldBounds(PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor
			pressAndMove(field.center, listOf(field.besideCenter(12f), field.besideCenter(24f)))
			assertFalse(showsText(PanelValues.BODY_X), "precondition: the scrub moved the row off its value")
			assertNotEquals(2f, harness.live(PanelIds.bodyX), "precondition: and the renderer with it")

			pressKey(Key.Escape)

			assertEquals(2f, harness.live(PanelIds.bodyX), "a cancelled scrub hands the renderer the committed value back")
			assertTrue(showsText(PanelValues.BODY_X), "and the row")
			assertNull(harness.scrubCancel.cancel, "the seam is released with the scrub")

			moveOn(listOf(field.besideCenter(36f)))
			assertEquals(2f, harness.live(PanelIds.bodyX), "the rest of a cancelled drag previews nothing")

			releasePress()
			assertEquals(2f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor, "a cancelled scrub records no step")
		}

	/** A pad axis's field scrubs its own axis, and the other stays where it was. */
	@Test
	fun aPadAxisFieldScrubMovesItsOwnAxisAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val field = numberFieldBounds(PanelValues.ANGLE_X)
			val cursorBefore = harness.historyCursor

			pressAndMove(field.center, listOf(field.besideCenter(12f), field.besideCenter(24f)))

			val previewed = harness.live(PanelIds.angleX)
			assertTrue(previewed != null && previewed > 3f, "the scrubbed axis previews live, and saw $previewed")
			assertEquals(-4f, harness.live(PanelIds.angleY), "the other axis is not touched")

			releasePress()
			assertEquals(previewed, harness.committed(PanelIds.angleX))
			assertEquals(-4f, harness.committed(PanelIds.angleY))
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** On a repeating parameter the field's preview wraps past an end (D10), as its commit does. */
	@Test
	fun aRepeatingFieldScrubWrapsAsItPreviews() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val field = numberFieldBounds(PanelValues.BODY_X)
			// Eight tenths of the travel is 16 of Body X's 20, so leftward from 2 it is -14, four past the minimum of -10
			// and short of a whole period, and only a wrap brings it back above where it started.  Leftward because the
			// field sits at the panel's right edge.
			val travel = with(density) { PARAMETER_FIELD_SCRUB_TRAVEL.toPx() } * 0.8f

			pressAndMove(field.center, listOf(0.25f, 0.5f, 0.75f, 1f).map { part -> field.besideCenter(-travel * part) })

			val previewed = harness.live(PanelIds.bodyX)
			assertTrue(previewed != null && previewed > 2f && previewed <= 10f, "a scrub past the minimum wraps in from the maximum, and saw $previewed")
			assertEquals(2f, harness.committed(PanelIds.bodyX))

			releasePress()
			assertEquals(previewed, harness.committed(PanelIds.bodyX))
		}

	/**
	 * A field scrub crosses any parameter's range over the same drag: a quarter of the travel is a quarter of 0..1 and
	 * a quarter of -10..10.  Each drag first clears any touch slop, which the field would never see, and is measured
	 * from there.
	 */
	@Test
	fun aFieldScrubCrossesEveryRangeOverTheSameTravel() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val quarterTravel = with(density) { PARAMETER_FIELD_SCRUB_TRAVEL.toPx() } / 4f

			val eyeOpen = numberFieldBounds(PanelValues.EYE_OPEN)
			pressAndMove(eyeOpen.center, listOf(eyeOpen.besideCenter(-PAST_THE_TOUCH_SLOP)))
			val eyeOpenPastSlop = harness.live(PanelIds.eyeOpen) ?: error("the scrub has to preview once past the slop")
			moveOn(listOf(eyeOpen.besideCenter(-PAST_THE_TOUCH_SLOP - quarterTravel)))
			val eyeOpenMoved = eyeOpenPastSlop - (harness.live(PanelIds.eyeOpen) ?: error("the scrub has to keep previewing"))
			releasePress()
			assertEquals(0.25f, eyeOpenMoved, ROUNDING, "a quarter of the travel is a quarter of Eye Open's 0..1")

			val bodyX = numberFieldBounds(PanelValues.BODY_X)
			pressAndMove(bodyX.center, listOf(bodyX.besideCenter(-PAST_THE_TOUCH_SLOP)))
			val bodyXPastSlop = harness.live(PanelIds.bodyX) ?: error("the scrub has to preview once past the slop")
			moveOn(listOf(bodyX.besideCenter(-PAST_THE_TOUCH_SLOP - quarterTravel)))
			val bodyXMoved = bodyXPastSlop - (harness.live(PanelIds.bodyX) ?: error("the scrub has to keep previewing"))
			releasePress()
			assertEquals(5f, bodyXMoved, ROUNDING, "and a quarter of Body X's -10..10")
		}

	/** A chevron steps a tenth of the range, rounded down to 1, 2, or 5: 0.1 on Breath's 0..1, 5 on Angle X's -30..30. */
	@Test
	fun aChevronStepsATenthOfTheRange() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			clickRightChevron(numberFieldBounds(PanelValues.BREATH))
			assertEquals(0.35f, harness.committed(PanelIds.breath) ?: Float.NaN, ROUNDING)
			assertEquals(cursorBefore + 1, harness.historyCursor, "a chevron press is one undo step")

			clickRightChevron(numberFieldBounds(PanelValues.ANGLE_X))
			assertEquals(8f, harness.committed(PanelIds.angleX) ?: Float.NaN, ROUNDING)
		}

	/**
	 * Presses a field's increment chevron, which composes only while the field is hovered.
	 *
	 * @param ControlBox field The field's bounds.
	 */
	private fun ComposeUiTest.clickRightChevron(field: ControlBox) {
		hoverAt(field.center)
		clickAt(Offset(field.right - with(density) { CHEVRON_INSET.toPx() }, field.center.y))
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

	/** A Ctrl scrub that ends near a key commits exactly the key. */
	@Test
	fun aCtrlScrubNearAKeyCommitsTheKey() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			setCtrlHeld(true)
			drag(slider.at(0.3f), listOf(slider.at(0.45f), slider.at(0.51f)))
			setCtrlHeld(false)

			assertEquals(0f, harness.committed(PanelIds.bodyX), "Body X is keyed at 0, a hair from where the drag ended")
		}

	/**
	 * The same scrub without Ctrl lands where the pointer let go, a little above 0.2 on Body X's -10..10: clear
	 * of the key at 0 and of the starting pose at 2, so it proves the scrub landed and did not snap.
	 */
	@Test
	fun withoutCtrlAScrubIgnoresTheKeys() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			drag(slider.at(0.3f), listOf(slider.at(0.45f), slider.at(0.51f)))

			val committed = harness.committed(PanelIds.bodyX)
			assertTrue(committed != null && committed in 0.1f..0.4f, "no snap without Ctrl: the release lands under the pointer, got $committed")
		}

	/** Far from every key, a Ctrl scrub follows the pointer. */
	@Test
	fun aCtrlScrubFarFromAKeyMovesFreely() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			setCtrlHeld(true)
			drag(slider.at(0.3f), listOf(slider.at(0.6f), slider.at(0.8f)))
			setCtrlHeld(false)

			assertNear(6f, harness.committed(PanelIds.bodyX), "out of reach of the key at 0")
		}

	/** Ctrl pressed after the scrub began still snaps: the way a drag picks up Ctrl on macOS. */
	@Test
	fun ctrlJoiningAScrubUnderWaySnaps() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			pressAndMove(slider.at(0.3f), listOf(slider.at(0.4f)))
			setCtrlHeld(true)
			moveOn(listOf(slider.at(0.51f)))
			releasePress()
			setCtrlHeld(false)

			assertEquals(0f, harness.committed(PanelIds.bodyX))
		}

	/** A Ctrl tap near a key lands on it. */
	@Test
	fun aCtrlTapNearAKeyLandsOnIt() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			setCtrlHeld(true)
			clickAt(slider.at(0.51f))
			setCtrlHeld(false)

			assertEquals(0f, harness.committed(PanelIds.bodyX))
		}

	private companion object {
		/** A first leg long enough that the field's drag detector has taken its slop and begun reporting. */
		const val PAST_THE_TOUCH_SLOP = 24f

		/** What the field's two places of rounding leave of a value. */
		const val ROUNDING = 0.011f

		/** From the field's right edge to the middle of its 16 dp increment chevron. */
		val CHEVRON_INSET = 8.dp
	}
}