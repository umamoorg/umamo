package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.edit.deleteParameter
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the grip's drag: where a drop lands, what it refuses, and everything that ends a drag without a
 * drop.  A drag is a long gesture, and whatever recomposes the panel under it must not end it.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersRowDragTest {
	/**
	 * A point over a slider row, by how far down the row it sits.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Int rowIndex The row, 0 for the first composed row.
	 * @param String valueText The text the row's number field shows.
	 * @param Float down The fraction of the row's height, 0 at its top edge.
	 * @return Offset The point, in the panel body's pixels.
	 */
	private fun overSliderRow(
		test: ComposeUiTest,
		harness: ParametersPanelHarness,
		rowIndex: Int,
		valueText: String,
		down: Float,
	): Offset {
		val grip = test.gripBounds(harness, rowIndex)
		val slider = test.sliderBox(harness, rowIndex, valueText)
		val field = test.numberFieldBounds(valueText)
		val padding = 6f * test.density.density
		val top = field.top - padding
		val bottom = slider.bottom + padding
		return Offset(grip.center.x, top + (bottom - top) * down)
	}

	/**
	 * A point over a group header row, by how far down the row it sits.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Int rowIndex The row, 0 for the first composed row.
	 * @param Float down The fraction of the row's height, 0 at its top edge.
	 * @return Offset The point, in the panel body's pixels.
	 */
	private fun overGroupRow(test: ComposeUiTest, harness: ParametersPanelHarness, rowIndex: Int, down: Float): Offset {
		val grip = test.gripBounds(harness, rowIndex)
		return Offset(grip.center.x, grip.top + grip.height * down)
	}

	/**
	 * The points a drag moves through on its way to [target]: one just past the touch slop, so the drag
	 * has started before it arrives, then the target itself.
	 *
	 * @param Offset from Where the drag was pressed.
	 * @param Offset target Where it is going.
	 * @return List<Offset> The points to move through.
	 */
	private fun pathTo(from: Offset, target: Offset): List<Offset> = listOf(Offset(from.x + 4f, from.y + 24f), target)

	/** A drop on a row's upper half lands before it. */
	@Test
	fun aDropOnTheUpperHalfLandsBefore() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor
			val grip = gripBounds(harness, PanelRows.BREATH).center

			drag(grip, pathTo(grip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.25f)))

			assertEquals(
				listOf(PanelIds.face.raw, PanelIds.angleX.raw, PanelIds.angleY.raw, PanelIds.breath.raw, PanelIds.bodyX.raw, PanelIds.fixed.raw, PanelIds.body.raw),
				rootOrderOf(harness.session.model.value),
			)
			assertEquals(cursorBefore + 1, harness.historyCursor, "a move is one undo step")
		}

	/** A drop on a row's lower half lands after it. */
	@Test
	fun aDropOnTheLowerHalfLandsAfter() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val grip = gripBounds(harness, PanelRows.BODY_X).center

			drag(grip, pathTo(grip, overSliderRow(this, harness, PanelRows.BREATH, PanelValues.BREATH, down = 0.75f)))

			assertEquals(
				listOf(PanelIds.face.raw, PanelIds.angleX.raw, PanelIds.angleY.raw, PanelIds.breath.raw, PanelIds.bodyX.raw, PanelIds.fixed.raw, PanelIds.body.raw),
				rootOrderOf(harness.session.model.value),
			)
		}

	/** A move changes where a parameter sits in the panel and nothing about the parameter list itself. */
	@Test
	fun aMoveLeavesTheParameterListAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val parametersBefore = harness.session.model.value.parameters
			val grip = gripBounds(harness, PanelRows.BREATH).center

			drag(grip, pathTo(grip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.25f)))

			assertEquals(parametersBefore, harness.session.model.value.parameters)
			assertTrue(showsText(PanelValues.BODY_X), "so the rows keep the values they were showing")
			assertTrue(showsText(PanelValues.BREATH))
		}

	/** A drop on the middle of a group header nests the row, and opens the group so the row can be seen. */
	@Test
	fun aDropOnAGroupsMiddleNestsAndOpensIt() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			assertFalse(showsText(PanelNames.EYE_OPEN), "the group has to start folded for the opening to mean anything")
			// With Face folded the list is Face, the pad, Body X, Breath, and Body.
			val grip = gripBounds(harness, 3).center

			drag(grip, pathTo(grip, overGroupRow(this, harness, 0, down = 0.5f)))

			assertTrue(PanelIds.breath in membersOf(harness.session.model.value, PanelIds.face))
			assertFalse(PanelIds.breath.raw in rootOrderOf(harness.session.model.value))
			assertEquals(true, harness.viewState.expandedGroups[PanelIds.face])
			assertTrue(showsText(PanelNames.EYE_OPEN))
		}

	/** Groups hold parameters only, so a group dragged over a row inside another group goes nowhere. */
	@Test
	fun aGroupNeverNests() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val modelBefore: PuppetModel = harness.session.model.value
			val cursorBefore = harness.historyCursor
			val grip = gripBounds(harness, PanelRows.BODY).center

			drag(grip, pathTo(grip, overSliderRow(this, harness, PanelRows.SMILE_SHAPE, PanelValues.SMILE_SHAPE, down = 0.5f)))

			assertEquals(modelBefore.parameterTree, harness.session.model.value.parameterTree)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** Escape abandons a drag, and the release that follows drops nothing. */
	@Test
	fun escapeCancelsADrag() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val modelBefore: PuppetModel = harness.session.model.value
			val cursorBefore = harness.historyCursor
			val grip = gripBounds(harness, PanelRows.BREATH).center

			pressAndMove(grip, pathTo(grip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.25f)))
			assertTrue(popupShows(PanelNames.BREATH), "the drag has to be under way for the cancel to mean anything")
			pressKey(Key.Escape)
			releasePress()

			assertEquals(modelBefore.parameterTree, harness.session.model.value.parameterTree)
			assertEquals(cursorBefore, harness.historyCursor)
			assertFalse(popupShows(PanelNames.BREATH), "the label following the pointer goes with the drag")
		}

	/** A release over no row drops nothing. */
	@Test
	fun aReleaseOverNoRowDropsNothing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			val modelBefore: PuppetModel = harness.session.model.value
			val cursorBefore = harness.historyCursor
			val grip = gripBounds(harness, PanelRows.BREATH).center
			val underTheLastRow = Offset(grip.x, gripBounds(harness, PanelRows.BODY).bottom + 50f)

			drag(grip, pathTo(grip, underTheLastRow))

			assertEquals(modelBefore.parameterTree, harness.session.model.value.parameterTree)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** The label following the pointer names what is being dragged; a pad goes by its horizontal axis. */
	@Test
	fun theDragLabelNamesTheRow() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			val breathGrip = gripBounds(harness, PanelRows.BREATH).center
			pressAndMove(breathGrip, pathTo(breathGrip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.75f)))
			assertTrue(popupShows(PanelNames.BREATH))
			pressKey(Key.Escape)
			releasePress()

			val padGrip = gripBounds(harness, PanelRows.ANGLE_PAD).center
			pressAndMove(padGrip, pathTo(padGrip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.75f)))
			assertTrue(popupShows(PanelNames.ANGLE_X))
			pressKey(Key.Escape)
			releasePress()

			val groupGrip = gripBounds(harness, PanelRows.BODY).center
			pressAndMove(groupGrip, pathTo(groupGrip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.75f)))
			assertTrue(popupShows(PanelNames.BODY))
			pressKey(Key.Escape)
			releasePress()
		}

	/** The label moves as far as the pointer does, and the same way. */
	@Test
	fun theDragLabelFollowsThePointer() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val grip = gripBounds(harness, PanelRows.BREATH).center
			val first = overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.75f)
			val second = Offset(first.x + 37f, overSliderRow(this, harness, PanelRows.SMILE, PanelValues.SMILE, down = 0.25f).y)

			pressAndMove(grip, pathTo(grip, first))
			val labelAtFirst = popupTextInWindow(PanelNames.BREATH)
			moveOn(listOf(second))
			val labelAtSecond = popupTextInWindow(PanelNames.BREATH)

			assertEquals(second.x - first.x, labelAtSecond.left - labelAtFirst.left, LABEL_TOLERANCE)
			assertEquals(second.y - first.y, labelAtSecond.top - labelAtFirst.top, LABEL_TOLERANCE)
			pressKey(Key.Escape)
			releasePress()
		}

	/** A drag outlives the panel recomposing under it. */
	@Test
	fun aDragSurvivesThePanelRecomposing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val grip = gripBounds(harness, PanelRows.BREATH).center
			val target = overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.25f)

			pressAndMove(grip, listOf(Offset(grip.x + 4f, grip.y + 24f)))
			// Inert with nothing selected, so the list is unchanged; what it does is rebuild the panel body.
			runOnIdle { harness.viewState.showOnlySelected = true }
			waitForIdle()
			moveOn(listOf(target))
			releasePress()

			assertEquals(
				listOf(PanelIds.face.raw, PanelIds.angleX.raw, PanelIds.angleY.raw, PanelIds.breath.raw, PanelIds.bodyX.raw, PanelIds.fixed.raw, PanelIds.body.raw),
				rootOrderOf(harness.session.model.value),
				"the drop has to land although the callbacks were rebuilt mid-drag",
			)
		}

	/** A row deleted while it is being dragged ends the drag, and the release moves nothing. */
	@Test
	fun deletingTheDraggedRowEndsTheDrag() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val grip = gripBounds(harness, PanelRows.BREATH).center
			pressAndMove(grip, pathTo(grip, overSliderRow(this, harness, PanelRows.BODY_X, PanelValues.BODY_X, down = 0.25f)))

			runOnIdle { harness.session.deleteParameter(PanelIds.breath) }
			waitForIdle()
			val cursorBefore = harness.historyCursor
			releasePress()

			assertFalse(popupShows(PanelNames.BREATH), "the label must not outlive the row it named")
			assertEquals(cursorBefore, harness.historyCursor, "and the release must not record a move")
			assertEquals(
				listOf(PanelIds.face.raw, PanelIds.angleX.raw, PanelIds.angleY.raw, PanelIds.bodyX.raw, PanelIds.fixed.raw, PanelIds.body.raw),
				rootOrderOf(harness.session.model.value),
			)
		}

	private companion object {
		/** The label lands on whole pixels, so it may sit up to one off the pointer's own move. */
		const val LABEL_TOLERANCE = 1f
	}
}